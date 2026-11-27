package io.github.cptmaverick_22.zardit.downloader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.cptmaverick_22.zardit.BuildConfig
import io.github.cptmaverick_22.zardit.MainActivity
import io.github.cptmaverick_22.zardit.R
import io.github.cptmaverick_22.zardit.data.ZarditPrefs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ApkAsset(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val updatedAtSec: Long,
)

data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val tagName: String,
    val available: Boolean,
    val sameTagHotfix: Boolean,
    val releaseUrl: String,
    val releaseNotes: String,
    val asset: ApkAsset?,
)

data class UpdateDecision(
    val available: Boolean,
    val sameTagHotfix: Boolean,
)

object AppUpdater {
    private const val REPO = "Cptmaverick-22/zardit"
    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val COMMIT_URL_PREFIX = "https://api.github.com/repos/$REPO/commits/"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO/releases"

    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val SAME_TAG_GRACE_SEC = 20L * 60L
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_SUMS_BYTES = 256 * 1024
    private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    private const val NOTIF_CHANNEL_ID = "zardit_app_updates"
    private const val NOTIF_ID = 4102

    private val TRUSTED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    fun isTrustedAppUrl(url: String): Boolean {
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.US) ?: return false
        val host = parsed.host?.lowercase(Locale.US) ?: return false
        if (scheme != "https" || !parsed.userInfo.isNullOrEmpty()) return false
        return host in TRUSTED_HOSTS
    }

    fun parseVersionParts(version: String): List<Int> {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        val nums = Regex("\\d+").findAll(cleaned).mapNotNull { it.value.toIntOrNull() }.toList()
        return nums.ifEmpty { listOf(0) }
    }

    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersionParts(a)
        val pb = parseVersionParts(b)
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    fun parseSha256Sums(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val match = regex.matchEntire(line) ?: return@forEach
            val digest = match.groupValues[1].lowercase(Locale.US)
            val fileName = match.groupValues[2].trim().substringAfterLast('/')
            if (fileName.isNotEmpty()) {
                result[fileName] = digest
            }
        }
        return result
    }

    fun parseIsoEpochSec(isoText: String): Long {
        val raw = isoText.trim()
        if (raw.isEmpty()) return 0L
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return runCatching { (fmt.parse(raw)?.time ?: 0L) / 1000L }.getOrDefault(0L)
    }

    fun shouldFlagUpdate(
        localVersion: String,
        remoteTag: String,
        localCommit: String,
        remoteCommit: String,
        localBuiltAtSec: Long,
        assetUpdatedAtSec: Long,
        assetSha256: String,
        installedSha256: String,
    ): UpdateDecision {
        val cmp = compareVersions(remoteTag, localVersion)
        if (cmp > 0) {
            return UpdateDecision(available = true, sameTagHotfix = false)
        }
        if (cmp == 0 && assetSha256.isNotEmpty()) {
            if (installedSha256.isNotEmpty() && installedSha256.equals(assetSha256, ignoreCase = true)) {
                return UpdateDecision(available = false, sameTagHotfix = false)
            }
            val commitChanged = localCommit.isNotEmpty() && remoteCommit.isNotEmpty() &&
                !localCommit.startsWith(remoteCommit, ignoreCase = true) &&
                !remoteCommit.startsWith(localCommit, ignoreCase = true)
            val rebuiltLater = localBuiltAtSec > 0L && assetUpdatedAtSec > localBuiltAtSec + SAME_TAG_GRACE_SEC
            if (commitChanged || rebuiltLater) {
                return UpdateDecision(available = true, sameTagHotfix = true)
            }
        }
        return UpdateDecision(available = false, sameTagHotfix = false)
    }

    private fun openTrustedConnection(startUrl: String, accept: String, timeoutMs: Int): HttpURLConnection {
        var currentUrl = startUrl
        repeat(6) {
            if (!isTrustedAppUrl(currentUrl)) {
                throw IllegalStateException("Refused untrusted update host.")
            }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Zardit-Android/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", accept)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location").orEmpty()
                conn.disconnect()
                val nextUrl = if (loc.startsWith("http", ignoreCase = true)) {
                    loc
                } else {
                    URI(currentUrl).resolve(loc).toString()
                }
                currentUrl = nextUrl
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("GitHub returned HTTP $code.")
            }
            return conn
        }
        throw IllegalStateException("Too many redirects while checking for updates.")
    }

    private fun readBoundedText(url: String, accept: String, maxBytes: Int, timeoutMs: Int): String {
        val conn = openTrustedConnection(url, accept, timeoutMs)
        try {
            val bytes = conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        throw IllegalStateException("Update metadata exceeded safe size limit.")
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchCommitSha(tag: String): String {
        if (!Regex("[0-9A-Za-z._-]{1,40}").matches(tag)) return ""
        return runCatching {
            val json = JSONObject(readBoundedText("$COMMIT_URL_PREFIX$tag", "application/vnd.github+json", 256 * 1024, 6000))
            json.optString("sha").takeIf { Regex("[0-9a-fA-F]{40}").matches(it) }.orEmpty()
        }.getOrDefault("")
    }

    private fun summarizeNotes(body: String): String =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(6)
            .joinToString("\n")

    fun checkForUpdate(context: Context, timeoutMs: Int = 12000): AppUpdateInfo {
        val prefs = ZarditPrefs(context)
        val release = JSONObject(
            readBoundedText(RELEASES_LATEST_URL, "application/vnd.github+json", MAX_JSON_BYTES, timeoutMs),
        )
        val tagName = release.optString("tag_name").trim()
        require(tagName.isNotEmpty()) { "Release is missing tag name." }
        val latestVersion = tagName.removePrefix("v").removePrefix("V")
        val htmlUrl = release.optString("html_url").takeIf { it.startsWith("https://github.com/") }
            ?: RELEASES_PAGE_URL
        val notes = summarizeNotes(release.optString("body"))

        val assetsArr = release.optJSONArray("assets")
        var sumsUrl = ""
        val assetObjs = mutableListOf<JSONObject>()
        if (assetsArr != null) {
            for (i in 0 until assetsArr.length()) {
                val obj = assetsArr.optJSONObject(i) ?: continue
                assetObjs += obj
                if (obj.optString("name") == "SHA256SUMS.txt") {
                    sumsUrl = obj.optString("browser_download_url")
                }
            }
        }
        val checksums = if (sumsUrl.isNotEmpty()) {
            parseSha256Sums(readBoundedText(sumsUrl, "text/plain", MAX_SUMS_BYTES, timeoutMs))
        } else {
            emptyMap()
        }

        var selectedAsset: ApkAsset? = null
        for (obj in assetObjs) {
            val name = obj.optString("name")
            if (!name.startsWith("Zardit-") || !name.endsWith("-android-arm64.apk")) continue
            val url = obj.optString("browser_download_url")
            val sha = checksums[name].orEmpty()
            if (url.isEmpty() || !Regex("[0-9a-f]{64}").matches(sha)) continue
            selectedAsset = ApkAsset(
                name = name,
                url = url,
                sha256 = sha,
                sizeBytes = obj.optLong("size", 0L),
                updatedAtSec = parseIsoEpochSec(obj.optString("updated_at")),
            )
            break
        }

        val cmp = compareVersions(latestVersion, BuildConfig.VERSION_NAME)
        val remoteCommit = if (cmp == 0 && BuildConfig.GIT_COMMIT.isNotEmpty()) fetchCommitSha(tagName) else ""
        val decision = shouldFlagUpdate(
            localVersion = BuildConfig.VERSION_NAME,
            remoteTag = latestVersion,
            localCommit = BuildConfig.GIT_COMMIT,
            remoteCommit = remoteCommit,
            localBuiltAtSec = BuildConfig.BUILD_TIME_SEC,
            assetUpdatedAtSec = selectedAsset?.updatedAtSec ?: 0L,
            assetSha256 = selectedAsset?.sha256.orEmpty(),
            installedSha256 = prefs.getInstalledApkSha256(),
        )

        prefs.setLastAppUpdateCheckMs(System.currentTimeMillis())

        return AppUpdateInfo(
            currentVersion = BuildConfig.VERSION_NAME,
            latestVersion = latestVersion,
            tagName = tagName,
            available = decision.available,
            sameTagHotfix = decision.sameTagHotfix,
            releaseUrl = htmlUrl,
            releaseNotes = notes,
            asset = selectedAsset,
        )
    }

    fun backgroundCheckIfDue(context: Context): AppUpdateInfo? {
        val prefs = ZarditPrefs(context)
        if (!prefs.isAppUpdateAutoCheckEnabled()) return null
        val now = System.currentTimeMillis()
        if (now - prefs.getLastAppUpdateCheckMs() < CHECK_INTERVAL_MS) return null
        return runCatching {
            val info = checkForUpdate(context, timeoutMs = 7000)
            if (!info.available) return null
            val sha = info.asset?.sha256.orEmpty()
            if (sha.isNotEmpty() && sha == prefs.getDismissedApkSha256()) return null
            if (sha.isEmpty() && info.latestVersion == prefs.getDismissedVersion()) return null
            info
        }.getOrNull()
    }

    fun dismissUpdate(context: Context, info: AppUpdateInfo) {
        val prefs = ZarditPrefs(context)
        prefs.setDismissedApkSha256(info.asset?.sha256.orEmpty())
        prefs.setDismissedVersion(info.latestVersion)
    }

    fun downloadAndVerifyApk(
        context: Context,
        asset: ApkAsset,
        onProgress: (Int) -> Unit,
    ): File {
        val safeName = asset.name.substringAfterLast('/').substringAfterLast('\\')
        require(safeName == asset.name && safeName.endsWith(".apk")) { "Invalid APK asset name." }
        require(asset.sizeBytes <= MAX_APK_BYTES) { "APK exceeds safe size limit." }

        val dir = File(context.cacheDir, "app_updates").apply { mkdirs() }
        dir.listFiles()?.forEach { old -> if (old.name != safeName) old.delete() }
        val partFile = File(dir, "$safeName.part")
        val targetFile = File(dir, safeName)

        val conn = openTrustedConnection(asset.url, "*/*", 30000)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
            if (total > MAX_APK_BYTES) throw IllegalStateException("APK exceeds safe size limit.")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L

            conn.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        received += read
                        if (received > MAX_APK_BYTES) {
                            throw IllegalStateException("Download exceeded safe size limit.")
                        }
                        digest.update(buf, 0, read)
                        output.write(buf, 0, read)
                        if (total > 0) {
                            onProgress(((received * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }

            val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHex.equals(asset.sha256, ignoreCase = true)) {
                partFile.delete()
                throw IllegalStateException("SHA-256 checksum mismatch! APK verification failed.")
            }

            if (targetFile.exists()) targetFile.delete()
            check(partFile.renameTo(targetFile)) { "Could not prepare verified APK." }
            ZarditPrefs(context).setInstalledApkSha256(actualHex)
            return targetFile
        } catch (t: Throwable) {
            partFile.delete()
            throw t
        } finally {
            conn.disconnect()
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun launchApkInstall(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun notifyUpdateIfPermitted(context: Context, info: AppUpdateInfo) {
        if (!info.available) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifies when a new Zardit release or verified hotfix is ready."
            }
            manager.createNotificationChannel(channel)
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (info.sameTagHotfix) {
            "Zardit ${info.tagName} hotfix ready"
        } else {
            "Zardit ${info.tagName} is available"
        }
        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("Tap Updates in Zardit to verify and install.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(NOTIF_ID, notification)
    }
}
