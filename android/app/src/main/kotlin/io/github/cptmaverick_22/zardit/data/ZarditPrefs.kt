package io.github.cptmaverick_22.zardit.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.cptmaverick_22.zardit.downloader.ThemeMode

class ZarditPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("zardit_prefs", Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val raw = prefs.getString(KEY_THEME, ThemeMode.AUTO.name)
        return runCatching { ThemeMode.valueOf(raw ?: ThemeMode.AUTO.name) }
            .getOrDefault(ThemeMode.AUTO)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun getDefaultVideoQuality(): Int = prefs.getInt(KEY_DEFAULT_VIDEO_QUALITY, 1080)

    fun setDefaultVideoQuality(height: Int) {
        prefs.edit().putInt(KEY_DEFAULT_VIDEO_QUALITY, height).apply()
    }

    fun isWifiOnlyDownloadsEnabled(): Boolean = prefs.getBoolean(KEY_WIFI_ONLY_DOWNLOADS, false)

    fun setWifiOnlyDownloadsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY_DOWNLOADS, enabled).apply()
    }

    fun getTreeUri(): Uri? {
        val raw = prefs.getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun setTreeUri(uri: Uri?) {
        if (uri == null) {
            prefs.edit().remove(KEY_TREE_URI).apply()
        } else {
            prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        }
    }

    fun getFolderDisplayName(): String {
        val uri = getTreeUri() ?: return "Downloads/Zardit"
        val doc = DocumentFile.fromTreeUri(appContext, uri)
        return doc?.name?.ifBlank { null } ?: "Custom folder"
    }

    fun isAppUpdateAutoCheckEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true)

    fun setAppUpdateAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATES, enabled).apply()
    }

    fun getLastAppUpdateCheckMs(): Long = prefs.getLong(KEY_LAST_APP_UPDATE_CHECK, 0L)

    fun setLastAppUpdateCheckMs(tsMs: Long) {
        prefs.edit().putLong(KEY_LAST_APP_UPDATE_CHECK, tsMs).apply()
    }

    fun getInstalledApkSha256(): String = prefs.getString(KEY_INSTALLED_APK_SHA, "") ?: ""

    fun setInstalledApkSha256(sha: String) {
        prefs.edit().putString(KEY_INSTALLED_APK_SHA, sha).apply()
    }

    fun getDismissedApkSha256(): String = prefs.getString(KEY_DISMISSED_APK_SHA, "") ?: ""

    fun setDismissedApkSha256(sha: String) {
        prefs.edit().putString(KEY_DISMISSED_APK_SHA, sha).apply()
    }

    fun getDismissedVersion(): String = prefs.getString(KEY_DISMISSED_VERSION, "") ?: ""

    fun setDismissedVersion(version: String) {
        prefs.edit().putString(KEY_DISMISSED_VERSION, version).apply()
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DEFAULT_VIDEO_QUALITY = "default_video_quality"
        private const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
        private const val KEY_TREE_URI = "download_tree_uri"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_app_updates"
        private const val KEY_LAST_APP_UPDATE_CHECK = "last_app_update_check_ms"
        private const val KEY_INSTALLED_APK_SHA = "installed_apk_sha256"
        private const val KEY_DISMISSED_APK_SHA = "dismissed_apk_sha256"
        private const val KEY_DISMISSED_VERSION = "dismissed_app_version"
    }
}
