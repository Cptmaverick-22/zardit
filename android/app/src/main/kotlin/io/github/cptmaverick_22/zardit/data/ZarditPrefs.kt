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

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_TREE_URI = "download_tree_uri"
    }
}
