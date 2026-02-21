package blbl.cat3399.core.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import blbl.cat3399.core.net.ipv4OnlyDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object ApkUpdater {
    private const val DEBUG_APK_URL = "https://cat3399.top/blbl/blbl-latest-debug.apk"
    private const val RELEASE_APK_URL = "https://cat3399.top/blbl/blbl-latest-release.apk"
    val TEST_APK_URL: String
        get() = if (BuildConfig.DEBUG) DEBUG_APK_URL else RELEASE_APK_URL
    val TEST_APK_VERSION_URL: String
        get() = "${TEST_APK_URL.substringBeforeLast('/')}/version"

    private const val COOLDOWN_MS = 5_000L

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val okHttpLazy: Lazy<OkHttpClient> =
        lazy {
            OkHttpClient.Builder()
                .dns(ipv4OnlyDns { BiliClient.prefs.ipv4OnlyEnabled })
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    fun evictConnections() {
        if (okHttpLazy.isInitialized()) okHttp.connectionPool.evictAll()
    }

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        append(formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${formatBytes(bytesPerSecond)}/s）")
                }
        }
    }

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastStartedAtMs
        val left = (last + COOLDOWN_MS) - nowMs
        return left.coerceAtLeast(0)
    }

    suspend fun fetchLatestVersionName(
        url: String = TEST_APK_VERSION_URL,
    ): String {
        // Entering Settings -> About triggers an automatic check. On some networks/devices the first request
        // may fail transiently but succeeds immediately when retried (e.g. connection warm-up / route setup).
        return withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            val maxAttempts = 3
            for (attempt in 1..maxAttempts) {
                ensureActive()
                try {
                    return@withContext fetchLatestVersionNameOnce(url)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                    val shouldRetry =
                        attempt < maxAttempts &&
                            (t is IOException || t.message?.startsWith("HTTP ") == true)
                    if (!shouldRetry) throw t
                    delay(400L * attempt)
                }
            }
            throw lastError ?: IllegalStateException("fetch latest version failed")
        }
    }

    private fun fetchLatestVersionNameOnce(url: String): String {
        val req = Request.Builder().url(url).get().build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val versionName = body.string().trim()
            check(versionName.isNotBlank()) { "版本号为空" }
            check(parseVersion(versionName) != null) { "版本号格式不正确：$versionName" }
            return versionName
        }
    }

    fun isRemoteNewer(remoteVersionName: String, currentVersionName: String = BuildConfig.VERSION_NAME): Boolean {
        val remote = parseVersion(remoteVersionName) ?: return false
        val current = parseVersion(currentVersionName) ?: return remoteVersionName.trim() != currentVersionName.trim()
        return compareVersion(remote, current) > 0
    }

    suspend fun downloadApkToCache(
        context: Context,
        url: String = TEST_APK_URL,
        onProgress: (Progress) -> Unit,
    ): File {
        onProgress(Progress.Connecting)

        val dir = File(context.cacheDir, "test_update").apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        runCatching { part.delete() }
        runCatching { target.delete() }

        val req = Request.Builder().url(url).get().build()
        val call = okHttp.newCall(req)
        val res = call.await()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L

                        var lastEmitAtMs = 0L
                        var speedAtMs = System.currentTimeMillis()
                        var speedBytes = 0L
                        var bytesPerSecond = 0L

                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read

                            // Speed estimate (1s window)
                            speedBytes += read
                            val nowMs = System.currentTimeMillis()
                            val speedElapsedMs = nowMs - speedAtMs
                            if (speedElapsedMs >= 1_000) {
                                bytesPerSecond = (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                speedBytes = 0L
                                speedAtMs = nowMs
                            }

                            // UI progress: at most 5 updates per second.
                            if (nowMs - lastEmitAtMs >= 200) {
                                lastEmitAtMs = nowMs
                                onProgress(Progress.Downloading(downloadedBytes = downloaded, totalBytes = total, bytesPerSecond = bytesPerSecond))
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        }

        check(part.exists() && part.length() > 0) { "downloaded file is empty" }
        check(part.renameTo(target)) { "rename failed" }
        return target
    }

    fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v")
        val digitsOnly =
            cleaned.takeWhile { ch ->
                ch.isDigit() || ch == '.'
            }
        if (digitsOnly.isBlank()) return null
        val parts = digitsOnly.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}
