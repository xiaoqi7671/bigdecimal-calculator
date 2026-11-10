package com.xiaoqi.calculator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条历史记录：算式、结果、时间、是否为"停留快照"。 */
data class HistoryEntry(
    val expr: String,
    val result: String,
    val date: Long,
    val snapshot: Boolean = false
)

/** 用 SharedPreferences + JSON 存历史，不引第三方库。 */
class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("calculator-history", Context.MODE_PRIVATE)

    fun load(): MutableList<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                HistoryEntry(
                    expr = item.optString("e"),
                    result = item.optString("r"),
                    date = item.optLong("d"),
                    snapshot = item.optBoolean("s", false)
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(entries: List<HistoryEntry>) {
        val keep = entries.takeLast(MAX_ENTRIES)
        val array = JSONArray()
        for (entry in keep) {
            array.put(
                JSONObject()
                    .put("e", entry.expr)
                    .put("r", entry.result)
                    .put("d", entry.date)
                    .put("s", entry.snapshot)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 200
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

fun formatDate(millis: Long): String = dateFormat.format(Date(millis))
