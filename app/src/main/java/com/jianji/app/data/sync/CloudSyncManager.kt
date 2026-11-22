package com.jianji.app.data.sync

import com.jianji.app.data.prefs.SettingsStore
import com.jianji.app.data.repository.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 云同步运行状态：设置页状态行展示用，不携带密钥。 */
data class SyncStatus(
    val running: Boolean = false,
    val lastSuccessEpochMs: Long? = null,
    val lastVersion: Long? = null,
    /** 本地账本有尚未成功同步到服务器的修改。 */
    val pendingChanges: Boolean = false,
    val failure: SyncFailure? = null,
    /** 熔断保护触发后暂停自动上传，等待用户在界面确认。 */
    val autoPaused: Boolean = false,
)

private data class SyncConfig(val serverUrl: String, val syncKey: String)

/**
 * 云同步入口：手动/自动上传、版本列表、云端恢复。
 *
 * 串行化约定：上传与恢复共用 [mutex]，自动同步的退避重试也走同一互斥，
 * 任何时刻只有一个网络操作在执行；恢复内部推送回滚点不再二次取锁。
 *
 * 自动同步约定：App 侧只按"内容指纹变化 + 去抖"触发，相同内容由服务端去重成 noop；
 * 网络错误有限次数指数退避，401/400 等永久错误不重试；wipe_suspected 暂停自动上传。
 */
class CloudSyncManager(
    private val repo: LedgerRepository,
    private val settings: SettingsStore,
    private val client: SyncApiClient,
    private val scope: CoroutineScope,
) {

    companion object {
        /** 连续记账去抖窗口：窗口内没有新变化才真正发起上传。 */
        private const val AUTO_DEBOUNCE_MS = 10_000L

        /** 自动同步网络错误的指数退避序列（毫秒）。 */
        private val AUTO_RETRY_DELAYS_MS = longArrayOf(10_000L, 30_000L, 90_000L)
    }

    private val mutex = Mutex()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** 最后一次成功同步（或恢复）覆盖的账本内容指纹。 */
    private var lastSyncedFingerprint: Long? = null

    private var autoJob: Job? = null

    // ---------- 手动同步（阶段 1 入口，行为不变） ----------

    suspend fun pushNow(force: Boolean = false): SyncPushOutcome = mutex.withLock {
        val config = loadConfig() ?: return SyncPushOutcome.Failed(SyncFailure.NotConfigured)
        pushLocked(config, force)
    }

    // ---------- 版本列表与云端恢复 ----------

    suspend fun listVersions(): SyncVersionsOutcome {
        val config = loadConfig() ?: return SyncVersionsOutcome.Failed(SyncFailure.NotConfigured)
        return client.listVersions(config.serverUrl, config.syncKey)
    }

    /**
     * 以目标云端版本替换本地账本：
     * 先把当前本地状态推送为新服务端版本作为回滚点（失败/熔断则中止恢复），
     * 再下载目标版本并在 Room 单事务内整表替换。
     */
    suspend fun restore(version: Long): RestoreOutcome = mutex.withLock {
        val config = loadConfig() ?: return RestoreOutcome.Failed(SyncFailure.NotConfigured)

        // 1. 回滚点：本地当前状态必须先落一个服务端版本
        when (val push = pushLocked(config, force = false)) {
            is SyncPushOutcome.Done -> Unit
            is SyncPushOutcome.WipeSuspected ->
                return RestoreOutcome.WipeSuspected(
                    currentLiveCount = push.currentLiveCount,
                    incomingLiveCount = push.incomingLiveCount,
                    hint = push.hint,
                )

            is SyncPushOutcome.Failed -> return RestoreOutcome.Failed(push.failure)
        }

        // 2. 下载目标版本
        val payload = when (val res = client.export(config.serverUrl, config.syncKey, version)) {
            is SyncExportOutcome.Done -> res.payload
            is SyncExportOutcome.Failed -> return RestoreOutcome.Failed(res.failure)
        }

        // 3. 校验并转换为本地实体
        val snapshot = runCatching { SnapshotCodec.decode(payload) }.getOrElse {
            return RestoreOutcome.Failed(SyncFailure.SnapshotInvalid("JSON 解析失败"))
        }
        if (snapshot.version != 1) {
            return RestoreOutcome.Failed(SyncFailure.SnapshotInvalid("不支持的快照版本 ${snapshot.version}"))
        }
        val (records, categories) = runCatching { SnapshotCodec.toEntities(snapshot) }.getOrElse {
            return RestoreOutcome.Failed(SyncFailure.SnapshotInvalid("记录或分类字段非法"))
        }

        // 4. 单事务替换本地账本；成功后以恢复内容为新同步基准，避免触发重复上传
        repo.restoreLedger(records, categories)
        lastSyncedFingerprint = SnapshotCodec.fingerprint(records, categories)
        _status.update {
            it.copy(
                pendingChanges = false,
                failure = null,
                lastSuccessEpochMs = System.currentTimeMillis(),
            )
        }
        RestoreOutcome.Done(
            version = version,
            recordCount = records.count { !it.isDeleted },
            categoryCount = categories.count { !it.isDeleted },
        )
    }

    // ---------- 自动同步 ----------

    /**
     * 账本内容变化回调（AppContainer 的 Room 快照流收集器调用）。
     * 只更新待同步标记并按需调度去抖上传；配置未启用或熔断暂停时不发起。
     */
    suspend fun onLocalDataChanged(fingerprint: Long) {
        val cfg = settings.settings.first()
        val active = cfg.syncEnabled && loadConfig() != null
        val pending = fingerprint != lastSyncedFingerprint
        _status.update { it.copy(pendingChanges = pending) }
        if (!active || !pending || _status.value.autoPaused) return
        scheduleAutoPush()
    }

    /** 用户开启同步或保存配置后立即尝试一次（仍受去抖前的配置校验约束）。 */
    fun kickAutoSync() {
        autoJob?.cancel()
        autoJob = scope.launch { autoPushWithRetry() }
    }

    fun cancelAutoSync() {
        autoJob?.cancel()
        autoJob = null
    }

    private fun scheduleAutoPush() {
        autoJob?.cancel()
        autoJob = scope.launch {
            delay(AUTO_DEBOUNCE_MS)
            autoPushWithRetry()
        }
    }

    private suspend fun autoPushWithRetry() {
        var attempt = 0
        while (true) {
            // 去抖窗口内数据可能又恢复/同步到一致，真正上传前再确认一次
            val config = loadConfig() ?: return
            val records = repo.snapshotRecords()
            val categories = repo.snapshotCategories()
            val snapshot = SnapshotCodec.build(
                records = records,
                categories = categories,
                exportedAtEpochMs = System.currentTimeMillis(),
            )
            val fingerprint = SnapshotCodec.fingerprint(records, categories)
            if (fingerprint == lastSyncedFingerprint) {
                _status.update { it.copy(pendingChanges = false) }
                return
            }

            when (val outcome = mutex.withLock { pushLocked(config, force = false) }) {
                is SyncPushOutcome.Done -> return
                is SyncPushOutcome.WipeSuspected -> {
                    _status.update { it.copy(autoPaused = true) }
                    return
                }

                is SyncPushOutcome.Failed -> {
                    _status.update { it.copy(failure = outcome.failure) }
                    // 网络错误指数退避重试；401/400 等永久错误不再重试
                    if (outcome.failure !is SyncFailure.Network || attempt >= AUTO_RETRY_DELAYS_MS.size) {
                        return
                    }
                    delay(AUTO_RETRY_DELAYS_MS[attempt])
                    attempt += 1
                }
            }
        }
    }

    // ---------- 内部 ----------

    private suspend fun loadConfig(): SyncConfig? {
        val cfg = settings.settings.first()
        if (!cfg.syncEnabled || cfg.syncKey.isBlank()) return null
        val serverUrl = SyncServerUrl.normalize(cfg.serverUrl).getOrNull() ?: return null
        return SyncConfig(serverUrl, cfg.syncKey.trim())
    }

    /** 调用方必须已持有 [mutex]。成功后更新同步基准与状态。 */
    private suspend fun pushLocked(config: SyncConfig, force: Boolean): SyncPushOutcome {
        _status.update { it.copy(running = true) }
        val records = repo.snapshotRecords()
        val categories = repo.snapshotCategories()
        val snapshot = SnapshotCodec.build(
            records = records,
            categories = categories,
            exportedAtEpochMs = System.currentTimeMillis(),
        )
        val fingerprint = SnapshotCodec.fingerprint(records, categories)
        return when (val outcome = client.push(config.serverUrl, config.syncKey, SnapshotCodec.encode(snapshot), force)) {
            is SyncPushOutcome.Done -> {
                lastSyncedFingerprint = fingerprint
                _status.update {
                    it.copy(
                        running = false,
                        lastSuccessEpochMs = System.currentTimeMillis(),
                        lastVersion = outcome.version,
                        pendingChanges = false,
                        failure = null,
                        autoPaused = false,
                    )
                }
                outcome
            }

            is SyncPushOutcome.WipeSuspected -> {
                _status.update { it.copy(running = false, autoPaused = true) }
                outcome
            }

            is SyncPushOutcome.Failed -> {
                _status.update { it.copy(running = false, failure = outcome.failure) }
                outcome
            }
        }
    }
}
