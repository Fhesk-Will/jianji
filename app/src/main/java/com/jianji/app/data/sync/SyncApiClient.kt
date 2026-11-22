package com.jianji.app.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 最小 SnapshotV1 推送客户端；不记录请求体与密钥。 */
class SyncApiClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val openConnection: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
) {

    suspend fun push(
        serverUrl: String,
        syncKey: String,
        payload: ByteArray,
        force: Boolean,
    ): SyncPushOutcome {
        val baseUrl = SyncServerUrl.normalize(serverUrl).getOrElse {
            return SyncPushOutcome.Failed(SyncFailure.InvalidServerUrl(it.message ?: "格式错误"))
        }
        return withContext(Dispatchers.IO) {
            pushBlocking(baseUrl, syncKey.trim(), payload, force)
        }
    }

    suspend fun listVersions(serverUrl: String, syncKey: String): SyncVersionsOutcome {
        val baseUrl = SyncServerUrl.normalize(serverUrl).getOrElse {
            return SyncVersionsOutcome.Failed(SyncFailure.InvalidServerUrl(it.message ?: "格式错误"))
        }
        return withContext(Dispatchers.IO) {
            when (val res = getBlocking("$baseUrl/api/versions", syncKey.trim())) {
                is GetResult.NetworkError ->
                    SyncVersionsOutcome.Failed(SyncFailure.Network(res.cause))

                is GetResult.Response ->
                    when (res.code) {
                        HttpURLConnection.HTTP_OK ->
                            SyncResponseParser.parseVersions(res.body)?.let { SyncVersionsOutcome.Done(it) }
                                ?: SyncVersionsOutcome.Failed(SyncFailure.BadResponse("版本列表格式不正确"))

                        else -> SyncVersionsOutcome.Failed(
                            SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body)),
                        )
                    }
            }
        }
    }

    /** version 为 null 表示取服务器当前版本。 */
    suspend fun export(serverUrl: String, syncKey: String, version: Long?): SyncExportOutcome {
        val baseUrl = SyncServerUrl.normalize(serverUrl).getOrElse {
            return SyncExportOutcome.Failed(SyncFailure.InvalidServerUrl(it.message ?: "格式错误"))
        }
        return withContext(Dispatchers.IO) {
            val query = version?.let { "?version=$it" }.orEmpty()
            when (val res = getBlocking("$baseUrl/api/export$query", syncKey.trim())) {
                is GetResult.NetworkError ->
                    SyncExportOutcome.Failed(SyncFailure.Network(res.cause))

                is GetResult.Response ->
                    when (res.code) {
                        HttpURLConnection.HTTP_OK ->
                            SyncExportOutcome.Done(res.body.toByteArray())

                        HttpURLConnection.HTTP_NOT_FOUND ->
                            SyncExportOutcome.Failed(SyncFailure.VersionNotFound)

                        else -> SyncExportOutcome.Failed(
                            SyncFailure.Http(res.code, SyncResponseParser.errorMessage(res.body)),
                        )
                    }
            }
        }
    }

    private sealed interface GetResult {
        data class Response(val code: Int, val body: String) : GetResult
        data class NetworkError(val cause: IOException) : GetResult
    }

    private fun getBlocking(url: String, syncKey: String): GetResult {
        val connection = try {
            openConnection(url)
        } catch (e: IOException) {
            return GetResult.NetworkError(e)
        }

        try {
            connection.apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $syncKey")
            }

            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                return GetResult.NetworkError(e)
            }
            return GetResult.Response(code, readBody(connection, code))
        } finally {
            connection.disconnect()
        }
    }

    private fun pushBlocking(
        baseUrl: String,
        syncKey: String,
        payload: ByteArray,
        force: Boolean,
    ): SyncPushOutcome {
        val query = if (force) "?force=true" else ""
        val connection = try {
            openConnection("$baseUrl/api/sync$query")
        } catch (e: IOException) {
            return SyncPushOutcome.Failed(SyncFailure.Network(e))
        }

        try {
            connection.apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $syncKey")
                setFixedLengthStreamingMode(payload.size)
            }

            try {
                connection.outputStream.use { it.write(payload) }
            } catch (e: IOException) {
                return SyncPushOutcome.Failed(SyncFailure.Network(e))
            }

            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                return SyncPushOutcome.Failed(SyncFailure.Network(e))
            }
            val body = readBody(connection, code)

            return when (code) {
                HttpURLConnection.HTTP_OK -> {
                    runCatching { SyncResponseParser.parsePush(body) }
                        .getOrElse {
                            SyncPushOutcome.Failed(SyncFailure.BadResponse("同步结果格式不正确"))
                        }
                }

                HttpURLConnection.HTTP_CONFLICT -> {
                    SyncResponseParser.parseWipeSuspected(body)?.let { wipe ->
                        SyncPushOutcome.WipeSuspected(
                            currentLiveCount = wipe.currentLiveCount,
                            incomingLiveCount = wipe.incomingLiveCount,
                            hint = wipe.hint,
                        )
                    } ?: SyncPushOutcome.Failed(
                        SyncFailure.Http(code, SyncResponseParser.errorMessage(body)),
                    )
                }

                else -> SyncPushOutcome.Failed(
                    SyncFailure.Http(code, SyncResponseParser.errorMessage(body)),
                )
            }
        } finally {
            connection.disconnect()
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
        return SyncPushOutcome.Done(status, version)
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
                version = obj["version"]!!.jsonPrimitive.long,
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

    fun errorMessage(body: String): String? = runCatching {
        if (body.isBlank()) return null
        val detail = json.parseToJsonElement(body).jsonObject["detail"] ?: return null
        detail.jsonPrimitive.content
    }.getOrNull()
}
