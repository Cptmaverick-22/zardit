package io.github.cptmaverick_22.zardit.data

import android.content.Context
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
)

class HistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("zardit_history", Context.MODE_PRIVATE)

    fun loadAll(): List<HistoryItem> {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
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
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(item: HistoryItem) {
        val current = loadAll().filterNot { it.id == item.id }
        saveAll(listOf(item) + current.take(99))
    }

    fun remove(id: String) {
        saveAll(loadAll().filterNot { it.id == id })
    }

    fun clear() {
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
                },
            )
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items"
    }
}
