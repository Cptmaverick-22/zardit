package io.github.cptmaverick_22.zardit.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val id: String,
    val mediaId: String,
    val title: String,
    val platform: String,
    val qualityLabel: String,
    val fileName: String,
    val savedLocation: String,
    val contentUri: String?,
    val timestampMs: Long,
    val thumbnailPath: String? = null,
    val fileSizeBytes: Long? = null,
)

class HistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("zardit_history", Context.MODE_PRIVATE)

    fun loadAll(): List<HistoryItem> {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val thumb = obj.optString("thumbnailPath").ifBlank { null }
                    val size = obj.optLong("fileSizeBytes", -1L).takeIf { it > 0L }
                    add(
                        HistoryItem(
                            id = obj.optString("id"),
                            mediaId = obj.optString("mediaId"),
                            title = obj.optString("title"),
                            platform = obj.optString("platform"),
                            qualityLabel = obj.optString("qualityLabel"),
                            fileName = obj.optString("fileName"),
                            savedLocation = obj.optString("savedLocation"),
                            contentUri = obj.optString("contentUri").ifBlank { null },
                            timestampMs = obj.optLong("timestampMs"),
                            thumbnailPath = thumb?.takeIf { File(it).isFile },
                            fileSizeBytes = size,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(item: HistoryItem) {
        val current = loadAll().filterNot { it.id == item.id }
        val kept = listOf(item) + current.take(99)
        saveAll(kept)
    }

    fun remove(id: String) {
        val current = loadAll()
        val removed = current.firstOrNull { it.id == id }
        removed?.thumbnailPath?.let { path ->
            runCatching { File(path).delete() }
        }
        saveAll(current.filterNot { it.id == id })
    }

    fun clear() {
        runCatching {
            thumbnailsDir(appContext).listFiles()?.forEach { it.delete() }
        }
        saveAll(emptyList())
    }

    private fun saveAll(items: List<HistoryItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("mediaId", item.mediaId)
                    put("title", item.title)
                    put("platform", item.platform)
                    put("qualityLabel", item.qualityLabel)
                    put("fileName", item.fileName)
                    put("savedLocation", item.savedLocation)
                    put("contentUri", item.contentUri ?: "")
                    put("timestampMs", item.timestampMs)
                    put("thumbnailPath", item.thumbnailPath ?: "")
                    put("fileSizeBytes", item.fileSizeBytes ?: -1L)
                },
            )
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items"

        fun thumbnailsDir(context: Context): File =
            File(context.applicationContext.filesDir, "thumbnails").apply { mkdirs() }
    }
}
