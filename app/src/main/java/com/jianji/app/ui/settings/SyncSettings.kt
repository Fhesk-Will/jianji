package com.jianji.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jianji.app.AppContainer
import com.jianji.app.JianJiApp
import com.jianji.app.data.prefs.Settings
import com.jianji.app.data.sync.PairOutcome
import com.jianji.app.data.sync.RestoreOutcome
import com.jianji.app.data.sync.SyncPushOutcome
import com.jianji.app.data.sync.SyncServerUrl
import com.jianji.app.data.sync.SyncStatus
import com.jianji.app.data.sync.SyncVersion
import com.jianji.app.data.sync.SyncVersionsOutcome
import com.jianji.app.ui.components.AppSwitch
import com.jianji.app.ui.theme.Ink
import com.jianji.app.ui.theme.Sub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val versionTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatEpochTime(epochMs: Long?): String =
    if (epochMs == null || epochMs <= 0) "时间未知" else versionTimeFormat.format(Instant.ofEpochMilli(epochMs))

data class SyncUiState(
    val running: Boolean = false,
    val wipe: SyncPushOutcome.WipeSuspected? = null,
)

data class VersionsUiState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val versions: List<SyncVersion> = emptyList(),
    val error: String? = null,
)

/** 保存配置弹窗的探测状态（M4：保存前必须先通过服务器配对探测）。 */
data class ConfigSaveUi(
    val probing: Boolean = false,
    /** 服务器已被其他设备占用：等待用户放弃或「接管此服务器」。 */
    val conflict: Boolean = false,
)

class SyncSettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<Settings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    val syncStatus: StateFlow<SyncStatus> = container.sync.status

    private val _syncUi = MutableStateFlow(SyncUiState())
    val syncUi: StateFlow<SyncUiState> = _syncUi.asStateFlow()

    private val _versionsUi = MutableStateFlow(VersionsUiState())
    val versionsUi: StateFlow<VersionsUiState> = _versionsUi.asStateFlow()

    private val _restoreRunning = MutableStateFlow(false)
    val restoreRunning: StateFlow<Boolean> = _restoreRunning.asStateFlow()

    private val _msg = MutableStateFlow<String?>(null)
    val msg: StateFlow<String?> = _msg.asStateFlow()

    private val _configSaveUi = MutableStateFlow(ConfigSaveUi())
    val configSaveUi: StateFlow<ConfigSaveUi> = _configSaveUi.asStateFlow()

    /** 配置已验证并保存的一次性事件：UI 消费后关闭弹窗。 */
    private val _configSaved = MutableStateFlow(false)
    val configSaved: StateFlow<Boolean> = _configSaved.asStateFlow()

    /** 探测中暂存的用户输入：冲突后「接管」复用同一对地址与密钥。 */
    private var pendingUrl: String = ""
    private var pendingKey: String = ""

    fun setSyncEnabled(enabled: Boolean) {
        val current = settings.value
        if (enabled && (current.serverUrl.isBlank() || current.syncKey.isBlank())) {
            _msg.value = "请先填写服务器地址与同步密钥"
            return
        }
        viewModelScope.launch {
            if (!enabled) container.sync.unpairOnDisable() // 关闭同步 = 解绑（M4）
            container.settings.setSyncEnabled(enabled)
            if (enabled) container.sync.kickAutoSync() else container.sync.cancelAutoSync()
        }
    }

    /**
     * 保存云同步配置（M4：先做配对探测，通过才真正保存）。
     * 两项都留空表示清除配置（无需探测）；探测期间 [ConfigSaveUi.probing]。
     */
    fun saveSyncConfig(rawServerUrl: String, rawSyncKey: String) {
        val serverUrl = rawServerUrl.trim()
        val syncKey = rawSyncKey.trim()
        if (serverUrl.isBlank() != syncKey.isBlank()) {
            _msg.value = "服务器地址与同步密钥需同时填写；两项都留空表示清除配置"
            return
        }
        if (serverUrl.isBlank()) {
            viewModelScope.launch {
                container.sync.unpairOnDisable() // 清除配置 = 解绑（M4）
                container.settings.setSyncConfig("", "")
                _msg.value = "已清除云同步配置"
            }
            _configSaved.value = true
            return
        }

        val normalized = SyncServerUrl.normalize(serverUrl)
        if (normalized.isFailure) {
            _msg.value = "服务器地址无效：${normalized.exceptionOrNull()?.message ?: "格式错误"}"
            return
        }

        pendingUrl = normalized.getOrThrow()
        pendingKey = syncKey
        _configSaveUi.value = ConfigSaveUi(probing = true)
        viewModelScope.launch { probeAndSave(takeover = false) }
    }

    /** 用户确认旧设备已停用，解除原配对并把本机设为同步设备。 */
    fun takeoverServer() {
        if (_configSaveUi.value.probing) return
        _configSaveUi.update { it.copy(probing = true) }
        viewModelScope.launch { probeAndSave(takeover = true) }
    }

    fun dismissConflict() {
        _configSaveUi.value = ConfigSaveUi()
    }

    fun consumeConfigSaved() {
        _configSaved.value = false
    }

    private suspend fun probeAndSave(takeover: Boolean) {
        val outcome = if (takeover) {
            container.sync.takeoverPairing(pendingUrl, pendingKey)
        } else {
            container.sync.probePairing(pendingUrl, pendingKey)
        }
        when (outcome) {
            is PairOutcome.Allowed -> {
                container.settings.setSyncConfig(pendingUrl, pendingKey)
                _msg.value = "云同步配置已保存"
                container.sync.kickAutoSync()
                _configSaveUi.value = ConfigSaveUi()
                _configSaved.value = true
            }

            is PairOutcome.Occupied -> {
                _msg.value = "该服务器已与另一台设备配对，设置未保存"
                _configSaveUi.value = ConfigSaveUi(conflict = true)
            }

            is PairOutcome.Failed -> {
                _msg.value = outcome.failure.userMessage
                _configSaveUi.update { it.copy(probing = false) }
            }
        }
    }

    // ---------- 版本列表与云端恢复 ----------

    fun loadVersions() {
        viewModelScope.launch {
            _versionsUi.update { it.copy(loading = true, error = null) }
            when (val result = container.sync.listVersions()) {
                is SyncVersionsOutcome.Done ->
                    _versionsUi.value = VersionsUiState(loaded = true, versions = result.versions)

                is SyncVersionsOutcome.Failed ->
                    _versionsUi.update { it.copy(loading = false, error = result.failure.userMessage) }
            }
        }
    }

    suspend fun localLiveCount(): Int = container.repo.liveRecordCount()

    fun restore(version: Long) {
        if (_restoreRunning.value) return
        viewModelScope.launch {
            _restoreRunning.value = true
            val result = container.sync.restore(version)
            _msg.value = when (result) {
                is RestoreOutcome.Done ->
                    "已从服务器 v${result.version} 恢复（${result.recordCount} 条记录）"

                is RestoreOutcome.WipeSuspected ->
                    "恢复中止：回滚点推送被熔断保护拦截，本地数据未做任何更改"

                is RestoreOutcome.Failed -> result.failure.userMessage
            }
            _restoreRunning.value = false
            if (result is RestoreOutcome.Done) loadVersions()
        }
    }

    fun syncNow(force: Boolean = false) {
        if (_syncUi.value.running) return
        viewModelScope.launch {
            _syncUi.update { it.copy(running = true, wipe = if (force) null else it.wipe) }
            val result = container.sync.pushNow(force)
            _msg.value = when (result) {
                is SyncPushOutcome.Done ->
                    when {
                        result.merged ->
                            "已把本机与服务器账本合并备份，版本 v${result.version}"

                        result.status == "noop" ->
                            "账本没有变化，服务器仍为 v${result.version}"

                        else -> "已备份到服务器，版本 v${result.version}"
                    }

                is SyncPushOutcome.WipeSuspected -> "检测到疑似误清空，已暂停覆盖"
                is SyncPushOutcome.Failed -> result.failure.userMessage
            }
            _syncUi.update {
                it.copy(
                    running = false,
                    wipe = result as? SyncPushOutcome.WipeSuspected,
                )
            }
        }
    }

    fun cancelWipe() {
        _syncUi.update { it.copy(wipe = null) }
    }

    fun consumeMsg() {
        _msg.value = null
    }
}

@Composable
fun SyncSettingsGroup() {
    val context = LocalContext.current
    val app = context.applicationContext as JianJiApp
    val vm: SyncSettingsViewModel = viewModel(initializer = { SyncSettingsViewModel(app.container) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncUi by vm.syncUi.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val versionsUi by vm.versionsUi.collectAsStateWithLifecycle()
    val restoreRunning by vm.restoreRunning.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    val configSaveUi by vm.configSaveUi.collectAsStateWithLifecycle()
    val configSaved by vm.configSaved.collectAsStateWithLifecycle()

    LaunchedEffect(msg) {
        msg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeMsg()
        }
    }

    var showConfig by remember { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<SyncVersion?>(null) }
    var serverUrl by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var syncKey by remember(settings.syncKey) { mutableStateOf(settings.syncKey) }
    val configured = settings.serverUrl.isNotBlank() && settings.syncKey.isNotBlank()

    LaunchedEffect(configSaved) {
        if (configSaved) {
            vm.consumeConfigSaved()
            showConfig = false
        }
    }

    SyncCard("云同步") {
        SyncSwitchRow(
            icon = Icons.Filled.Cloud,
            title = "云同步",
            sub = when {
                !configured -> "未配置服务器"
                settings.syncEnabled -> "已配置，当前开启"
                else -> "已配置，当前关闭"
            },
            checked = settings.syncEnabled,
            onToggle = { enabled ->
                if (enabled && !configured) showConfig = true else vm.setSyncEnabled(enabled)
            },
        )
        SyncIconRow(
            icon = Icons.Filled.Edit,
            title = "服务器与密钥",
            sub = if (configured) "已配置，点击修改" else "填写自部署服务器地址与 SYNC_KEY",
            onClick = { showConfig = true },
        )
        SyncIconRow(
            icon = Icons.Filled.CloudUpload,
            title = "立即同步",
            sub = when {
                syncUi.running -> "正在上传账本…"
                !settings.syncEnabled -> "先开启并配置云同步"
                else -> "手动把账本备份到服务器"
            },
            enabled = !syncUi.running,
            showArrow = false,
            onClick = {
                if (settings.syncEnabled) vm.syncNow() else showConfig = true
            },
        )
        SyncIconRow(
            icon = Icons.Filled.Info,
            title = "同步状态",
            sub = syncStatusSub(configured, settings.syncEnabled, syncUi.running, syncStatus),
            enabled = configured && !syncUi.running &&
                (syncStatus.failure != null || syncStatus.autoPaused),
            showArrow = false,
            onClick = { vm.syncNow() },
        )
        SyncIconRow(
            icon = Icons.Filled.CloudDownload,
            title = "从云端恢复",
            sub = when {
                !configured -> "先配置服务器"
                restoreRunning -> "正在恢复账本…"
                else -> "浏览服务器版本，替换本地账本"
            },
            enabled = configured && !restoreRunning,
            showArrow = false,
            onClick = {
                showVersions = true
                vm.loadVersions()
            },
        )
    }

    if (showConfig) {
        AlertDialog(
            onDismissRequest = { showConfig = false },
            title = { Text("云同步服务器") },
            text = {
                Column {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://example.com/jianji") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = syncKey,
                        onValueChange = { syncKey = it },
                        label = { Text("同步密钥 SYNC_KEY") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "正式服务器请使用 HTTPS；HTTP 仅限 localhost 或内网调试。密钥只保存在本机，不会显示。",
                        fontSize = 12.sp,
                        color = Sub,
                        lineHeight = 18.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !configSaveUi.probing,
                    onClick = { vm.saveSyncConfig(serverUrl, syncKey) },
                ) { Text(if (configSaveUi.probing) "验证中…" else "保存") }
            },
            dismissButton = {
                TextButton(onClick = { showConfig = false }) { Text("取消") }
            },
        )
    }

    if (configSaveUi.conflict) {
        AlertDialog(
            onDismissRequest = { vm.dismissConflict() },
            title = { Text("服务器已与其他设备配对") },
            text = {
                Text(
                    "该服务器已绑定另一台同步设备，本机不能保存这对地址与密钥，" +
                        "否则两台设备会互相覆盖账本。\n\n" +
                        "如果旧设备已停用，可以接管：解除原设备的绑定，并把本机设为同步设备。",
                    fontSize = 13.sp,
                    color = Ink,
                    lineHeight = 21.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !configSaveUi.probing,
                    onClick = { vm.takeoverServer() },
                ) { Text(if (configSaveUi.probing) "处理中…" else "接管此服务器") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissConflict() }) { Text("放弃") }
            },
        )
    }

    syncUi.wipe?.let { wipe ->
        AlertDialog(
            onDismissRequest = { vm.cancelWipe() },
            title = { Text("检测到疑似误清空") },
            text = {
                Text(
                    "服务器当前账本约 ${wipe.currentLiveCount} 条，本次上传约 ${wipe.incomingLiveCount} 条。\n\n" +
                        "为保护数据，默认不会覆盖；只有确认本次上传正确后才执行强制备份。",
                    fontSize = 13.sp,
                    color = Ink,
                    lineHeight = 21.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !syncUi.running,
                    onClick = { vm.syncNow(force = true) },
                ) { Text("确认覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelWipe() }) { Text("取消") }
            },
        )
    }

    if (showVersions) {
        AlertDialog(
            onDismissRequest = { showVersions = false },
            title = { Text("云端版本") },
            text = {
                when {
                    versionsUi.loading && !versionsUi.loaded ->
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        }

                    versionsUi.error != null ->
                        Text(versionsUi.error!!, fontSize = 13.sp, color = Ink)

                    versionsUi.versions.isEmpty() ->
                        Text("服务器上还没有任何版本", fontSize = 13.sp, color = Sub)

                    else ->
                        LazyColumn {
                            items(versionsUi.versions, key = { it.version }) { version ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pendingRestore = version
                                            showVersions = false
                                        }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Text(
                                        "v${version.version}",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Ink,
                                    )
                                    Text(
                                        "${formatEpochTime(version.createdAtEpochMs)} · " +
                                            "共 ${version.recordCount} 条 · 存活 ${version.liveCount} 条",
                                        fontSize = 11.sp,
                                        color = Sub,
                                    )
                                }
                            }
                        }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.loadVersions() }) { Text("刷新") }
            },
            dismissButton = {
                TextButton(onClick = { showVersions = false }) { Text("关闭") }
            },
        )
    }

    pendingRestore?.let { target ->
        var localCount by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(target) { localCount = vm.localLiveCount() }
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("恢复到 v${target.version}？") },
            text = {
                Text(
                    "将以服务器 v${target.version} 替换本地账本：\n" +
                        "目标版本共 ${target.recordCount} 条、存活 ${target.liveCount} 条；" +
                        "本地当前约 ${localCount?.toString() ?: "…"} 条。\n\n" +
                        "恢复前会先把本地账本备份为一个新版本作为回滚点；恢复后本地将被目标版本完全替换。",
                    fontSize = 13.sp,
                    color = Ink,
                    lineHeight = 21.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = localCount != null && !restoreRunning,
                    onClick = {
                        vm.restore(target.version)
                        pendingRestore = null
                    },
                ) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("取消") }
            },
        )
    }
}

/** 同步状态行文案：最近成功时间、服务器版本、待同步与失败原因。 */
private fun syncStatusSub(
    configured: Boolean,
    syncEnabled: Boolean,
    manualRunning: Boolean,
    status: SyncStatus,
): String = when {
    manualRunning || status.running -> "正在同步…"
    !configured -> "未配置服务器"
    !syncEnabled && status.lastSuccessEpochMs == null && status.failure == null -> "已配置，当前关闭"
    status.autoPaused -> "检测到疑似误清空，已暂停自动备份，点此重试"
    status.failure != null -> "${status.failure.userMessage}（点此重试）"
    status.lastSuccessEpochMs != null -> buildString {
        append("上次成功 ")
        append(formatEpochTime(status.lastSuccessEpochMs))
        status.lastVersion?.let { append(" · 服务器 v$it") }
        if (status.pendingChanges) append(" · 有未同步修改")
    }

    status.pendingChanges -> "有未同步修改"
    else -> "暂无同步记录"
}

@Composable
private fun SyncCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 13.dp)) {
        Text(
            title,
            fontSize = 10.5.sp,
            color = Sub,
            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SyncIconRow(
    icon: ImageVector,
    title: String,
    sub: String,
    enabled: Boolean = true,
    showArrow: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SyncIconBox(icon)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(sub, fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.weight(1f))
        if (onClick != null && enabled && showArrow) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Sub,
            )
        }
    }
}

@Composable
private fun SyncSwitchRow(
    icon: ImageVector,
    title: String,
    sub: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SyncIconBox(icon)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(sub, fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.weight(1f))
        AppSwitch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun SyncIconBox(icon: ImageVector) {
    androidx.compose.foundation.layout.Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(18.dp),
        )
    }
}
