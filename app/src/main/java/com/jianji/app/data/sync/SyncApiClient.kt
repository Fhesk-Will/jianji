package com.jianji.app.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 最小 SnapshotV1 推送客户端；不记录请求体与密钥。
 *
 * 所有 SYNC_KEY 请求带 X-Jianji-Client 身份头（M4 单客户端配对）；
 * 服务端身份不一致返回 409 client_mismatch → [SyncFailure.ClientMismatch]。
 */
class SyncApiClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val openConnection: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
) {

    companion object {
        const val HEADER_CLIENT = "X-Jianji-Client"
    }

    suspend fun push(
        serverUrl: String,
        syncKey: String,
        clientId: String,
        payload: ByteArray,
        force: Boolean,
    ): SyncPushOutcome {
        val baseUrl = SyncServerUrl.normalize(serverUrl).getOrElse {
            return SyncPushOutcome.Failed(SyncFailure.InvalidServerUrl(it.message ?: "格式错误"))
        }
        return withContext(Dispatchers.IO) {
            pushBlocking(baseUrl, syncKey.trim(), clientId, payload, force)
        }
    }

    suspend fun listVersions(
        serverUrl: String,
        syncKey: String,
        clientId: String,
    ): SyncVersionsOutcome {
        val baseUrl = SyncServerUrl.normalize(serverUrl).getOrElse {
            return SyncVersionsOutcome.Failed(SyncFailure.InvalidServerUrl(it.message ?: "格式错误"))
        }
        return withContext(Dispatchers.IO) {
            when (val res = getBlocking("$baseUrl/api/versions", syncKey.trim(), clientId)) {
                is CallResult.NetworkError ->
                    SyncVersionsOutcome.Failed(SyncFailure.Network(res.cause))

                is CallResult.Response ->
                    when (res.code) {
                        HttpURLConnection.HTTP_OK ->
                            SyncResponseParser.parseVersions(res.body)?.let { SyncVersionsOutcome.Done(it) }
                                ?: SyncVersionsOutcome.Failed(SyncFailure.BadResponse("版本列表格式不正确"))

                        HttpURLConnection.HTTP_CONFLICT ->
                            if (SyncResponseParser.isClientMismatch(res.body)) {
                                SyncVersionsOutcome.Failed(SyncFailure.ClientMismatch)
                            } else {
                                SyncVersionsOutcome.Failed(
                                    SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body)),
                                )
                            }

                        else -> SyncVersionsOutcome.Failed(
                            SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body)),
                        )
                    }
            }
        }
    }

    /** version 为 null 表示取服务器当前版本。 */
    suspend fun export(
        serverUrl: String,
        syncKey: String,
        clientId: String,
        version: Long?,
    ): SyncExportOutcome {
        val baseUrl = SyncServerUrl.normalize(serverUrl).getOrElse {
            return SyncExportOutcome.Failed(SyncFailure.InvalidServerUrl(it.message ?: "格式错误"))
        }
        return withContext(Dispatchers.IO) {
            val query = version?.let { "?version=$it" }.orEmpty()
            when (val res = getBlocking("$baseUrl/api/export$query", syncKey.trim(), clientId)) {
                is CallResult.NetworkError ->
                    SyncExportOutcome.Failed(SyncFailure.Network(res.cause))

                is CallResult.Response ->
                    when (res.code) {
                        HttpURLConnection.HTTP_OK ->
                            SyncExportOutcome.Done(res.body.toByteArray())

                        HttpURLConnection.HTTP_NOT_FOUND ->
                            SyncExportOutcome.Failed(SyncFailure.VersionNotFound)

                        HttpURLConnection.HTTP_CONFLICT ->
                            if (SyncResponseParser.isClientMismatch(res.body)) {
                                SyncExportOutcome.Failed(SyncFailure.ClientMismatch)
                            } else {
                                SyncExportOutcome.Failed(
                                    SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body)),
                                )
                            }

                        else -> SyncExportOutcome.Failed(
                            SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body)),
                        )
                    }
            }
        }
    }

    /**
     * 保存配置前的配对探测（M4）：
     * [PairOutcome.Allowed] = 配对成功（新抢占或已是本设备），或旧服务端无 /api/pair（404）；
     * [PairOutcome.Occupied] = 已被其他设备占用，不允许保存。
     */
    suspend fun pair(serverUrl: String, syncKey: String, clientId: String): PairOutcome {
        val baseUrl = SyncServerUrl.normalize(serverUrl).getOrElse {
            return PairOutcome.Failed(SyncFailure.InvalidServerUrl(it.message ?: "格式错误"))
        }
        return withContext(Dispatchers.IO) {
            val body = JsonObject(mapOf("clientId" to JsonPrimitive(clientId)))
                .toString().encodeToByteArray()
            when (val res = postBlocking("$baseUrl/api/pair", syncKey.trim(), clientId, body)) {
                is CallResult.NetworkError ->
                    PairOutcome.Failed(SyncFailure.Network(res.cause))

                is CallResult.Response ->
                    when (res.code) {
                        HttpURLConnection.HTTP_OK -> PairOutcome.Allowed
                        HttpURLConnection.HTTP_NOT_FOUND -> PairOutcome.Allowed
                        HttpURLConnection.HTTP_CONFLICT ->
                            if (SyncResponseParser.isClientMismatch(res.body)) {
                                PairOutcome.Occupied
                            } else {
                                PairOutcome.Failed(
                                    SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body)),
                                )
                            }

                        else -> PairOutcome.Failed(
                            SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body)),
                        )
                    }
            }
        }
    }

    /** 解除服务器配对（接管流程第一步）；返回 null 表示成功。 */
    suspend fun unpair(serverUrl: String, syncKey: String): SyncFailure? {
        val baseUrl = SyncServerUrl.normalize(serverUrl).getOrElse {
            return SyncFailure.InvalidServerUrl(it.message ?: "格式错误")
        }
        return withContext(Dispatchers.IO) {
            when (val res = postBlocking("$baseUrl/api/unpair", syncKey.trim(), "", null)) {
                is CallResult.NetworkError -> SyncFailure.Network(res.cause)
                is CallResult.Response ->
                    if (res.code == HttpURLConnection.HTTP_OK) null
                    else SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body))
            }
        }
    }

    private sealed interface CallResult {
        data class Response(val code: Int, val body: String) : CallResult
        data class NetworkError(val cause: IOException) : CallResult
    }

    private fun getBlocking(url: String, syncKey: String, clientId: String): CallResult {
        val connection = try {
            openConnection(url)
        } catch (e: IOException) {
            return CallResult.NetworkError(e)
        }

        try {
            connection.apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $syncKey")
                if (clientId.isNotBlank()) setRequestProperty(HEADER_CLIENT, clientId)
            }

            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                return CallResult.NetworkError(e)
            }
            return CallResult.Response(code, readBody(connection, code))
        } finally {
            connection.disconnect()
        }
    }

    private fun postBlocking(
        url: String,
        syncKey: String,
        clientId: String,
        body: ByteArray?,
    ): CallResult {
        val connection = try {
            openConnection(url)
        } catch (e: IOException) {
            return CallResult.NetworkError(e)
        }

        try {
            connection.apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                requestMethod = "POST"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $syncKey")
                if (clientId.isNotBlank()) setRequestProperty(HEADER_CLIENT, clientId)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setFixedLengthStreamingMode(body.size)
                }
            }

            if (body != null) {
                try {
                    connection.outputStream.use { it.write(body) }
                } catch (e: IOException) {
                    return CallResult.NetworkError(e)
                }
            }

            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                return CallResult.NetworkError(e)
            }
            return CallResult.Response(code, readBody(connection, code))
        } finally {
            connection.disconnect()
        }
    }

    private fun pushBlocking(
        baseUrl: String,
        syncKey: String,
        clientId: String,
        payload: ByteArray,
        force: Boolean,
    ): SyncPushOutcome {
        val query = if (force) "?force=true" else ""
        return when (val res = postBlocking("$baseUrl/api/sync$query", syncKey, clientId, payload)) {
            is CallResult.NetworkError -> SyncPushOutcome.Failed(SyncFailure.Network(res.cause))

            is CallResult.Response ->
                when (res.code) {
                    HttpURLConnection.HTTP_OK ->
                        runCatching { SyncResponseParser.parsePush(res.body) }
                            .getOrElse {
                                SyncPushOutcome.Failed(SyncFailure.BadResponse("同步结果格式不正确"))
                            }

                    HttpURLConnection.HTTP_CONFLICT -> {
                        SyncResponseParser.parseWipeSuspected(res.body)?.let { wipe ->
                            SyncPushOutcome.WipeSuspected(
                                currentLiveCount = wipe.currentLiveCount,
                                incomingLiveCount = wipe.incomingLiveCount,
                                hint = wipe.hint,
                            )
                        } ?: if (SyncResponseParser.isClientMismatch(res.body)) {
                            SyncPushOutcome.Failed(SyncFailure.ClientMismatch)
                        } else {
                            SyncPushOutcome.Failed(
                                SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body)),
                            )
                        }
                    }

                    else -> SyncPushOutcome.Failed(
                        SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body)),
                    )
                }
        }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String =
        try {
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.use { it.readBytes().decodeToString() }.orEmpty()
        } catch (_: IOException) {
            ""
        }
}

internal object SyncResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parsePush(body: String): SyncPushOutcome.Done {
        val root = json.parseToJsonElement(body).jsonObject
        val status = root["status"]?.jsonPrimitive?.content
        require(status == "ok" || status == "noop") { "未知同步状态：$status" }
        val version = root["version"]?.jsonPrimitive?.long ?: error("缺少 version")
        val merged = root["merged"]?.jsonPrimitive?.content == "true"
        return SyncPushOutcome.Done(status, version, merged)
    }

    data class Wipe(
        val currentLiveCount: Int,
        val incomingLiveCount: Int,
        val hint: String,
    )

    fun parseVersions(body: String): List<SyncVersion>? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        root["versions"]?.jsonArray?.map { item ->
            val obj = item.jsonObject
            SyncVersion(
                version = obj["version"]!!.jsonPrimitive.content.toLong(),
                recordCount = obj["recordCount"]!!.jsonPrimitive.content.toInt(),
                liveCount = obj["liveCount"]!!.jsonPrimitive.content.toInt(),
                createdAtEpochMs = obj["createdAt"]?.jsonPrimitive?.content
                    ?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                    ?: 0L,
            )
        }
    }.getOrNull()

    fun parseWipeSuspected(body: String): Wipe? = runCatching {
        val detail = json.parseToJsonElement(body).jsonObject["detail"]?.jsonObject ?: return null
        if (detail["code"]?.jsonPrimitive?.content != "wipe_suspected") return null
        Wipe(
            currentLiveCount = detail["currentLiveCount"]?.jsonPrimitive?.content?.toInt() ?: 0,
            incomingLiveCount = detail["incomingLiveCount"]?.jsonPrimitive?.content?.toInt() ?: 0,
            hint = detail["hint"]?.jsonPrimitive?.content.orEmpty(),
        )
    }.getOrNull()

    /** 409 且 detail.code == client_mismatch（M4 单客户端锁定）。 */
    fun isClientMismatch(body: String): Boolean = runCatching {
        json.parseToJsonElement(body).jsonObject["detail"]?.jsonObject
            ?.get("code")?.jsonPrimitive?.content == "client_mismatch"
    }.getOrDefault(false)

    fun errorMessage(body: String): String? = runCatching {
        if (body.isBlank()) return null
        val detail = json.parseToJsonElement(body).jsonObject["detail"] ?: return null
        detail.jsonPrimitive.content
    }.getOrNull()
}
