package com.jianji.app.data.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jianji.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** GitHub Release 中的一个可安装版本。 */
data class AppRelease(
    val versionName: String,
    val versionCode: Int,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sumsUrl: String?,
)

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val release: AppRelease) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/**
 * 应用内更新：直连公开仓库的 GitHub Release API，不引入第三方更新库。
 *
 * 流程：查最新 Release → 下载 APK 到缓存 → 按 SHA256SUMS.txt 校验 → 交给系统安装器。
 * Android 不允许应用静默安装，最后一步必须由用户在系统界面确认。
 */
class UpdateManager(
    private val context: Context,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
) {

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Fhesk-Will/jianji/releases/latest"

        private const val USER_AGENT = "JianJi-Android"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val SUMS_NAME = "SHA256SUMS.txt"

        /** 与 app/build.gradle.kts 同一套派生规则：versionName = 日期.序号 */
        private val VERSION_NAME = Regex("""^(\d{4})\.(\d{2})\.(\d{2})\.(\d{1,2})$""")
        private val SUMS_LINE = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")

        private val json = Json { ignoreUnknownKeys = true }
    }

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        val response = try {
            get(latestReleaseUrl, "application/vnd.github+json")
        } catch (_: IOException) {
            return@withContext UpdateCheck.Failed("网络连接失败，请检查网络后重试")
        }
        if (response.code != HttpURLConnection.HTTP_OK) {
            return@withContext UpdateCheck.Failed(
                when (response.code) {
                    HttpURLConnection.HTTP_FORBIDDEN, 429 -> "请求过于频繁，请稍后再试"
                    HttpURLConnection.HTTP_NOT_FOUND -> "未找到可用版本"
                    else -> "服务返回 HTTP ${response.code}"
                },
            )
        }
        val release = parseRelease(response.body)
            ?: return@withContext UpdateCheck.Failed("更新信息格式不正确")
        if (release.versionCode > currentVersionCode) {
            UpdateCheck.Available(release)
        } else {
            UpdateCheck.UpToDate
        }
    }

    /** 下载并校验安装包；onProgress 回调 0..100。 */
    suspend fun download(
        release: AppRelease,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            Result.success(downloadBlocking(release, onProgress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 拉起系统安装器；安装结果由系统界面反馈，应用无法静默完成。 */
    fun install(apk: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            clipData = ClipData.newRawUri("jianji-update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 跳系统「安装未知应用」授权页，授权后回到应用重试安装即可。 */
    fun openInstallPermission(): Result<Unit> = runCatching {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun parseRelease(body: String): AppRelease? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
        if (root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null

        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val versionName = tag.removePrefix("v")
        val versionCode = versionCodeOf(versionName) ?: return null

        val assets = root["assets"]?.jsonArray ?: JsonArray(emptyList())
        val apk = assets.asJsonObjects()
            .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
            ?: return null
        val apkName = apk["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val apkUrl = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        val apkSize = apk["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val sumsUrl = assets.asJsonObjects()
            .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == SUMS_NAME }
            ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull

        AppRelease(
            versionName = versionName,
            versionCode = versionCode,
            apkName = apkName,
            apkUrl = apkUrl,
            apkSize = apkSize,
            sumsUrl = sumsUrl,
        )
    }.getOrNull()

    private fun JsonArray.asJsonObjects(): List<JsonObject> =
        mapNotNull { runCatching { it.jsonObject }.getOrNull() }

    private fun versionCodeOf(versionName: String): Int? {
        val match = VERSION_NAME.matchEntire(versionName) ?: return null
        val (year, month, day, seq) = match.destructured
        val date = "$year$month$day".toIntOrNull() ?: return null
        val sequence = seq.toIntOrNull() ?: return null
        return date * 100 + sequence
    }

    private fun downloadBlocking(release: AppRelease, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // 只保留本次下载，避免缓存目录积累旧安装包
        dir.listFiles()?.forEach { it.delete() }

        val target = File(dir, release.apkName)
        val part = File(dir, "${release.apkName}.part")
        val sha256 = MessageDigest.getInstance("SHA-256")

        val connection = open(release.apkUrl, "application/octet-stream")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("下载失败（HTTP ${connection.responseCode}）")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sha256.update(buffer, 0, read)
                        received += read
                        if (total > 0) {
                            val percent = (received * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    if (total > 0 && received != total) {
                        throw IOException("下载不完整，请重试")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        // 摘要文件缺失时不影响安装（HTTPS 已保证传输完整性），存在则必须匹配
        val expected = release.sumsUrl?.let { url ->
            runCatching { fetchSums(url)[release.apkName] }.getOrNull()
        }
        if (expected != null) {
            val actual = sha256.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                part.delete()
                throw IOException("安装包校验失败，请重试")
            }
        }

        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return target
    }

    private fun fetchSums(url: String): Map<String, String> {
        val response = get(url, "text/plain")
        if (response.code != HttpURLConnection.HTTP_OK) return emptyMap()
        return response.body.lineSequence()
            .mapNotNull { line ->
                SUMS_LINE.find(line.trim())?.let { it.groupValues[1] to it.groupValues[2] }
            }
            .toMap()
    }

    private data class Response(val code: Int, val body: String)

    private fun get(url: String, accept: String): Response {
        val connection = open(url, accept)
        try {
            val code = connection.responseCode
            val stream: InputStream? =
                if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            return Response(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", USER_AGENT)
        }
}
