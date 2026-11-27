package io.github.cptmaverick_22.zardit.downloader

import android.content.Context
import android.os.Build
import io.github.cptmaverick_22.zardit.data.DiagnosticLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

data class FfmpegAddonAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val tagName: String,
)

sealed interface FfmpegAddonAvailability {
    data class Available(val asset: FfmpegAddonAsset) : FfmpegAddonAvailability
    data class NotPublished(val message: String) : FfmpegAddonAvailability
}

/**
 * Manages an optional static FFmpeg binary stored strictly in app-private storage.
 *
 * Keeps the base Zardit APK small (~21 MB) while allowing users who want MP3
 * conversion / non-MP4 audio extraction to install FFmpeg on demand with strict
 * SHA-256 verification against SHA256SUMS.txt.
 */
object FfmpegAddonManager {

    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/Cptmaverick-22/zardit/releases/latest"
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_SUMS_BYTES = 128 * 1024
    private const val MIN_BINARY_BYTES = 100 * 1024L
    private const val MAX_BINARY_BYTES = 120 * 1024 * 1024L

    fun binaryFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "ffmpeg")
        return File(dir, "ffmpeg")
    }

    fun isInstalled(context: Context): Boolean {
        val bin = binaryFile(context)
        return bin.isFile && bin.length() >= MIN_BINARY_BYTES && bin.canExecute()
    }

    fun installedSizeBytes(context: Context): Long? {
        val bin = binaryFile(context)
        return if (isInstalled(context)) bin.length() else null
    }

    fun uninstall(context: Context) {
        val dir = File(context.applicationContext.filesDir, "ffmpeg")
        runCatching { dir.deleteRecursively() }
    }

    fun checkAvailability(timeoutMs: Int = 12_000): FfmpegAddonAvailability {
        val release = JSONObject(
            readBoundedText(RELEASES_LATEST_URL, "application/vnd.github+json", MAX_JSON_BYTES, timeoutMs),
        )
        val tagName = release.optString("tag_name").trim().ifBlank { "latest" }
        val assetsArr = release.optJSONArray("assets")
            ?: return FfmpegAddonAvailability.NotPublished("FFmpeg addon package is not published for $tagName yet.")

        var sumsUrl = ""
        val candidates = mutableListOf<JSONObject>()
        for (i in 0 until assetsArr.length()) {
            val obj = assetsArr.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            if (name == "SHA256SUMS.txt") {
                sumsUrl = obj.optString("browser_download_url")
            } else if (isMatchingFfmpegAssetName(name)) {
                candidates += obj
            }
        }

        val chosen = selectBestAddonAsset(candidates)
            ?: return FfmpegAddonAvailability.NotPublished(
                "Optional FFmpeg binary asset is not published in release $tagName yet. Base MP4/M4A/image features already work without it.",
            )

        if (sumsUrl.isBlank()) {
            return FfmpegAddonAvailability.NotPublished("Release $tagName is missing SHA256SUMS.txt for verification.")
        }

        val sumsText = readBoundedText(sumsUrl, "text/plain", MAX_SUMS_BYTES, timeoutMs)
        val checksums = AppUpdater.parseSha256Sums(sumsText)
        val name = chosen.optString("name")
        val downloadUrl = chosen.optString("browser_download_url")
        val sha = checksums[name]
            ?: return FfmpegAddonAvailability.NotPublished("Missing SHA-256 digest for $name in SHA256SUMS.txt.")

        if (!isTrustedReleaseUrl(downloadUrl)) {
            return FfmpegAddonAvailability.NotPublished("Untrusted FFmpeg asset host.")
        }

        return FfmpegAddonAvailability.Available(
            FfmpegAddonAsset(
                name = name,
                downloadUrl = downloadUrl,
                sizeBytes = chosen.optLong("size", 0L),
                sha256 = sha,
                tagName = tagName,
            ),
        )
    }

    fun install(
        context: Context,
        asset: FfmpegAddonAsset,
        onProgress: (Int) -> Unit = {},
    ): File {
        require(isTrustedReleaseUrl(asset.downloadUrl)) { "Untrusted download URL." }
        val target = binaryFile(context)
        val dir = target.parentFile ?: error("Missing addon directory.")
        dir.mkdirs()

        val temp = File(dir, "ffmpeg.part")
        temp.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        val conn = (URL(asset.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Zardit-Android")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code downloading FFmpeg addon")
            }
            val finalHost = conn.url?.host.orEmpty().lowercase(Locale.US)
            if (conn.url?.protocol != "https" || !isTrustedDownloadHost(finalHost)) {
                throw SecurityException("Refused redirect to untrusted host $finalHost")
            }
            val total = asset.sizeBytes.takeIf { it > 0L } ?: conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        downloaded += n
                        if (downloaded > MAX_BINARY_BYTES) {
                            throw IOException("FFmpeg addon exceeded maximum allowed size.")
                        }
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        if (total > 0L) {
                            onProgress(((downloaded * 100L) / total).toInt().coerceIn(0, 99))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualSha.equals(asset.sha256, ignoreCase = true)) {
            temp.delete()
            throw SecurityException("SHA-256 verification failed for ${asset.name}.")
        }

        if (temp.length() < MIN_BINARY_BYTES) {
            temp.delete()
            throw IOException("Downloaded FFmpeg binary looks incomplete.")
        }

        target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target.setExecutable(true, true)
        onProgress(100)
        return target
    }

    fun convertAudioToMp3(
        context: Context,
        inputFile: File,
        outputMp3: File,
        bitrateKbps: Int = 192,
    ): Boolean {
        val bin = binaryFile(context)
        if (!isInstalled(context)) return false
        return runCatching {
            val process = ProcessBuilder(
                bin.absolutePath,
                "-y",
                "-i",
                inputFile.absolutePath,
                "-vn",
                "-acodec",
                "libmp3lame",
                "-b:a",
                "${bitrateKbps}k",
                outputMp3.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val logOutput = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit != 0 || !outputMp3.isFile || outputMp3.length() < 1024L) {
                DiagnosticLog.log("FfmpegMp3Err", "exit=$exit ${logOutput.takeLast(300)}")
                false
            } else {
                true
            }
        }.onFailure { err ->
            DiagnosticLog.log("FfmpegMp3Err", err.message ?: err::class.java.simpleName)
        }.getOrDefault(false)
    }

    internal fun isMatchingFfmpegAssetName(name: String): Boolean {
        val low = name.lowercase(Locale.US)
        return low.startsWith("ffmpeg-android") && !low.endsWith(".sha256") && !low.endsWith(".txt")
    }

    private fun selectBestAddonAsset(candidates: List<JSONObject>): JSONObject? {
        if (candidates.isEmpty()) return null
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase(Locale.US) }
        for (abi in abis) {
            val match = candidates.firstOrNull {
                abi in it.optString("name").lowercase(Locale.US)
            }
            if (match != null) return match
        }
        return candidates.firstOrNull { "arm64" in it.optString("name").lowercase(Locale.US) }
            ?: candidates.firstOrNull()
    }

    private fun isTrustedReleaseUrl(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        if (!parsed.protocol.equals("https", ignoreCase = true)) return false
        return isTrustedDownloadHost(parsed.host.lowercase(Locale.US))
    }

    private fun isTrustedDownloadHost(host: String): Boolean {
        return host == "github.com" ||
            host.endsWith(".github.com") ||
            host == "objects.githubusercontent.com" ||
            host == "release-assets.githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
    }

    private fun readBoundedText(url: String, accept: String, maxBytes: Int, timeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Zardit-Android")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code from $url")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > maxBytes) {
                throw IOException("Response exceeded $maxBytes bytes")
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}
