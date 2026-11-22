package com.jianji.app.data.sync

/** 一次手动云备份的明确结果；所有网络与解析错误都收敛到这里。 */
sealed interface SyncPushOutcome {

    data class Done(
        val status: String,
        val version: Long,
        /** 换机合并：服务端把本机数据与镜像并集落版，App 需回拉全量收敛。 */
        val merged: Boolean = false,
    ) : SyncPushOutcome

    /** 服务端熔断：只有用户确认后才能带 force=true 重传。 */
    data class WipeSuspected(
        val currentLiveCount: Int,
        val incomingLiveCount: Int,
        val hint: String,
    ) : SyncPushOutcome

    data class Failed(val failure: SyncFailure) : SyncPushOutcome
}

sealed class SyncFailure(val userMessage: String) {

    data object NotConfigured : SyncFailure("请先开启云同步并填写服务器地址与同步密钥")

    class InvalidServerUrl(reason: String) :
        SyncFailure("服务器地址无效：正式服务器请使用 HTTPS；HTTP 仅限 localhost 或内网调试（$reason）")

    class Http(
        val code: Int,
        val serverMessage: String?,
    ) : SyncFailure(
        when (code) {
            400 -> "服务器拒绝了这份账本快照${serverMessage?.let { "：$it" } ?: ""}"
            401 -> "服务器拒绝访问，请检查同步密钥"
            403 -> "服务器拒绝当前请求"
            413 -> "账本快照超过服务器大小限制"
            else -> "服务器返回 HTTP $code${serverMessage?.let { "：$it" } ?: ""}"
        },
    )

    class Network(cause: Throwable) :
        SyncFailure("无法连接服务器，请检查网络、服务器地址和服务状态（${cause.javaClass.simpleName}）")

    class BadResponse(reason: String) :
        SyncFailure("服务器响应异常：$reason")

    /** 恢复时目标版本已被滚动清理。 */
    data object VersionNotFound : SyncFailure("该版本已不存在，请刷新版本列表后重试")

    /** M4 单客户端锁定：服务器已与其他设备配对。 */
    data object ClientMismatch :
        SyncFailure("服务器已与其他设备配对，无法在此设备上同步")

    class SnapshotInvalid(reason: String) :
        SyncFailure("云端快照校验失败：$reason")
}

/** 保存配置前的配对探测结果（M4）。 */
sealed interface PairOutcome {
    /** 可以保存：配对成功（新抢占或已是本设备），或旧服务端无配对概念（404）。 */
    data object Allowed : PairOutcome

    /** 服务器已被其他设备占用，不允许保存这一对地址与密钥。 */
    data object Occupied : PairOutcome

    data class Failed(val failure: SyncFailure) : PairOutcome
}

/** 服务端一个历史版本（GET /api/versions 的单条）。 */
data class SyncVersion(
    val version: Long,
    val recordCount: Int,
    val liveCount: Int,
    val createdAtEpochMs: Long,
)

/** 拉取版本列表的明确结果。 */
sealed interface SyncVersionsOutcome {
    data class Done(val versions: List<SyncVersion>) : SyncVersionsOutcome
    data class Failed(val failure: SyncFailure) : SyncVersionsOutcome
}

/** 按版本导出快照的明确结果。 */
sealed interface SyncExportOutcome {
    data class Done(val payload: ByteArray) : SyncExportOutcome
    data class Failed(val failure: SyncFailure) : SyncExportOutcome
}

/** 云端恢复的明确结果。 */
sealed interface RestoreOutcome {
    data class Done(val version: Long, val recordCount: Int, val categoryCount: Int) : RestoreOutcome

    /** 恢复前的回滚点推送被熔断，恢复已安全中止。 */
    data class WipeSuspected(
        val currentLiveCount: Int,
        val incomingLiveCount: Int,
        val hint: String,
    ) : RestoreOutcome

    data class Failed(val failure: SyncFailure) : RestoreOutcome
}
