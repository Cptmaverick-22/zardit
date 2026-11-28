package io.github.cptmaverick_22.zardit.downloader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.FileProvider
import io.github.cptmaverick_22.zardit.data.DiagnosticLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
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
 * Manages the optional Zardit FFmpeg companion plugin APK (`io.github.cptmaverick_22.zardit.ffmpeg`).
 *
 * Distributing FFmpeg as an on-demand companion APK keeps the base Zardit APK slim (~41 MB),
 * remains W^X / SELinux compliant on Android 10–17 (binaries live inside the addon's
 * read-only nativeLibraryDir), and lets users install or completely uninstall it cleanly.
 */
object FfmpegAddonManager {

    const val ADDON_PACKAGE_ID = "io.github.cptmaverick_22.zardit.ffmpeg"
    const val ADDON_AUTHORITY = "io.github.cptmaverick_22.zardit.ffmpeg.provider"

    private const val METHOD_PING = "ping"
    private const val METHOD_CONVERT_MP3 = "convert_mp3"
    private const val METHOD_MUX_MP4 = "mux_mp4"

    private const val KEY_OK = "ok"
    private const val KEY_ERROR = "error"
    private const val KEY_INPUT_URI = "input_uri"
    private const val KEY_COVER_URI = "cover_uri"
    private const val KEY_OUTPUT_URI = "output_uri"
    private const val KEY_VIDEO_URI = "video_uri"
    private const val KEY_AUDIO_URI = "audio_uri"
    private const val KEY_BITRATE_KBPS = "bitrate_kbps"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"

    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/Cptmaverick-22/zardit/releases/latest"
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_SUMS_BYTES = 128 * 1024
    private const val MIN_APK_BYTES = 512 * 1024L
    private const val MAX_APK_BYTES = 150 * 1024 * 1024L

    fun isInstalled(context: Context): Boolean {
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(ADDON_PACKAGE_ID, 0) }.getOrNull() ?: return false
        return info.enabled
    }

    fun installedSizeBytes(context: Context): Long? {
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(ADDON_PACKAGE_ID, 0) }.getOrNull() ?: return null
        val apkBytes = info.sourceDir?.let(::File)?.takeIf { it.isFile }?.length() ?: 0L
        val libBytes = info.nativeLibraryDir?.let(::File)
            ?.takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L
        val total = apkBytes + libBytes
        return total.takeIf { it > 0L }
    }

    fun launchUninstall(context: Context): Boolean {
        if (!isInstalled(context)) return false
        return runCatching {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$ADDON_PACKAGE_ID")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun checkAvailability(timeoutMs: Int = 12_000): FfmpegAddonAvailability {
        val release = JSONObject(
            readBoundedText(RELEASES_LATEST_URL, "application/vnd.github+json", MAX_JSON_BYTES, timeoutMs),
        )
        val tagName = release.optString("tag_name").trim().ifBlank { "latest" }
        val assetsArr = release.optJSONArray("assets")
            ?: return FfmpegAddonAvailability.NotPublished("FFmpeg addon APK is not published for $tagName yet.")

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
                "Optional FFmpeg addon APK is not published in release $tagName yet. Base MP4/M4A/image features already work without it.",
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
            return FfmpegAddonAvailability.NotPublished("Untrusted FFmpeg addon URL.")
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

    fun downloadAndVerifyApk(
        context: Context,
        asset: FfmpegAddonAsset,
        onProgress: (Int) -> Unit = {},
    ): File {
        require(isTrustedReleaseUrl(asset.downloadUrl)) { "Untrusted download URL." }
        val dir = File(context.cacheDir, "app_updates").apply { mkdirs() }
        val target = File(dir, asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val part = File(dir, "${target.name}.part")
        part.delete()

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
                throw IOException("HTTP $code downloading FFmpeg addon APK")
            }
            val finalHost = conn.url?.host.orEmpty().lowercase(Locale.US)
            if (conn.url?.protocol != "https" || !isTrustedDownloadHost(finalHost)) {
                throw SecurityException("Refused redirect to untrusted host $finalHost")
            }
            val total = asset.sizeBytes.takeIf { it > 0L } ?: conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        downloaded += n
                        if (downloaded > MAX_APK_BYTES) {
                            throw IOException("FFmpeg addon APK exceeded maximum allowed size.")
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
            part.delete()
            throw SecurityException("SHA-256 verification failed for ${asset.name}.")
        }
        if (part.length() < MIN_APK_BYTES) {
            part.delete()
            throw IOException("Downloaded FFmpeg addon APK looks incomplete.")
        }

        target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        onProgress(100)
        return target
    }

    fun convertAudioToMp3(
        context: Context,
        inputFile: File,
        outputMp3: File,
        bitrateKbps: Int = 192,
        coverFile: File? = null,
        title: String? = null,
        artist: String? = null,
    ): Boolean {
        if (!isInstalled(context) || !inputFile.isFile) return false
        val ipcDir = File(context.cacheDir, "ffmpeg_ipc/${UUID.randomUUID()}").apply { mkdirs() }
        val grantedUris = mutableListOf<Uri>()
        return try {
            val sharedIn = File(ipcDir, "in.media").also { inputFile.copyTo(it, overwrite = true) }
            val sharedOut = File(ipcDir, "out.mp3").apply { createNewFile() }
            val authority = "${context.packageName}.fileprovider"

            val inUri = FileProvider.getUriForFile(context, authority, sharedIn).also {
                context.grantUriPermission(ADDON_PACKAGE_ID, it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                grantedUris += it
            }
            val outUri = FileProvider.getUriForFile(context, authority, sharedOut).also {
                context.grantUriPermission(
                    ADDON_PACKAGE_ID,
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                grantedUris += it
            }
            val coverUri = if (coverFile != null && coverFile.isFile && coverFile.length() > 64L) {
                val sharedCover = File(ipcDir, "cover.jpg").also { coverFile.copyTo(it, overwrite = true) }
                FileProvider.getUriForFile(context, authority, sharedCover).also {
                    context.grantUriPermission(ADDON_PACKAGE_ID, it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    grantedUris += it
                }
            } else {
                null
            }

            val extras = Bundle().apply {
                putParcelable(KEY_INPUT_URI, inUri)
                putParcelable(KEY_OUTPUT_URI, outUri)
                coverUri?.let { putParcelable(KEY_COVER_URI, it) }
                putInt(KEY_BITRATE_KBPS, bitrateKbps)
                title?.let { putString(KEY_TITLE, it) }
                artist?.let { putString(KEY_ARTIST, it) }
            }
            val res = context.contentResolver.call(
                Uri.parse("content://$ADDON_AUTHORITY"),
                METHOD_CONVERT_MP3,
                null,
                extras,
            )
            if (res?.getBoolean(KEY_OK, false) == true && sharedOut.isFile && sharedOut.length() >= 1024L) {
                sharedOut.copyTo(outputMp3, overwrite = true)
                true
            } else {
                val err = res?.getString(KEY_ERROR) ?: "No response from FFmpeg addon"
                DiagnosticLog.log("FfmpegMp3Err", err)
                false
            }
        } catch (t: Throwable) {
            DiagnosticLog.log("FfmpegMp3Err", t.message ?: t::class.java.simpleName)
            false
        } finally {
            grantedUris.forEach { uri ->
                runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            ipcDir.deleteRecursively()
        }
    }

    fun muxToMp4(
        context: Context,
        videoFile: File,
        audioFile: File?,
        outputMp4: File,
    ): Boolean {
        if (!isInstalled(context) || !videoFile.isFile) return false
        val ipcDir = File(context.cacheDir, "ffmpeg_ipc/${UUID.randomUUID()}").apply { mkdirs() }
        val grantedUris = mutableListOf<Uri>()
        return try {
            val sharedVideo = File(ipcDir, "video.media").also { videoFile.copyTo(it, overwrite = true) }
            val sharedOut = File(ipcDir, "out.mp4").apply { createNewFile() }
            val authority = "${context.packageName}.fileprovider"

            val videoUri = FileProvider.getUriForFile(context, authority, sharedVideo).also {
                context.grantUriPermission(ADDON_PACKAGE_ID, it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                grantedUris += it
            }
            val audioUri = if (audioFile != null && audioFile.isFile && audioFile.length() > 256L) {
                val sharedAudio = File(ipcDir, "audio.media").also { audioFile.copyTo(it, overwrite = true) }
                FileProvider.getUriForFile(context, authority, sharedAudio).also {
                    context.grantUriPermission(ADDON_PACKAGE_ID, it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    grantedUris += it
                }
            } else {
                null
            }
            val outUri = FileProvider.getUriForFile(context, authority, sharedOut).also {
                context.grantUriPermission(
                    ADDON_PACKAGE_ID,
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                grantedUris += it
            }

            val extras = Bundle().apply {
                putParcelable(KEY_VIDEO_URI, videoUri)
                audioUri?.let { putParcelable(KEY_AUDIO_URI, it) }
                putParcelable(KEY_OUTPUT_URI, outUri)
            }
            val res = context.contentResolver.call(
                Uri.parse("content://$ADDON_AUTHORITY"),
                METHOD_MUX_MP4,
                null,
                extras,
            )
            if (res?.getBoolean(KEY_OK, false) == true && sharedOut.isFile && sharedOut.length() >= 4096L) {
                sharedOut.copyTo(outputMp4, overwrite = true)
                true
            } else {
                val err = res?.getString(KEY_ERROR) ?: "No response from FFmpeg addon"
                DiagnosticLog.log("FfmpegMuxErr", err)
                false
            }
        } catch (t: Throwable) {
            DiagnosticLog.log("FfmpegMuxErr", t.message ?: t::class.java.simpleName)
            false
        } finally {
            grantedUris.forEach { uri ->
                runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            ipcDir.deleteRecursively()
        }
    }

    internal fun isMatchingFfmpegAssetName(name: String): Boolean {
        val low = name.lowercase(Locale.US)
        return (low.startsWith("zardit-ffmpeg-addon") || low.startsWith("ffmpeg-android")) &&
            low.endsWith(".apk")
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
