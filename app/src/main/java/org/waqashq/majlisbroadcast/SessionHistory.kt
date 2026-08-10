package org.waqashq.majlisbroadcast

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 9: a lightweight local log of past broadcast sessions (start time,
 * duration, peak listener count, data used), purely for the broadcaster's
 * own record. Not sensitive, so a plain (unencrypted) SharedPreferences
 * file is fine, unlike AppSettings. Stored as a single JSON array capped to
 * the most recent MAX_ENTRIES so it never grows unbounded.
 */
object SessionHistory {
    private const val PREFS_NAME = "majlis_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 50

    data class Entry(
        val startedAtMs: Long,
        val durationMs: Long,
        val peakListeners: Int,
        val bytesUploaded: Long
    )

    fun record(context: Context, entry: Entry) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = try {
            JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        } catch (_: Throwable) {
            JSONArray()
        }
        val obj = JSONObject().apply {
            put("startedAtMs", entry.startedAtMs)
            put("durationMs", entry.durationMs)
            put("peakListeners", entry.peakListeners)
            put("bytesUploaded", entry.bytesUploaded)
        }
        array.put(obj)
        val trimmed = if (array.length() > MAX_ENTRIES) {
            JSONArray().apply {
                for (i in (array.length() - MAX_ENTRIES) until array.length()) {
                    put(array.get(i))
                }
            }
        } else {
            array
        }
        prefs.edit().putString(KEY_ENTRIES, trimmed.toString()).apply()
    }

    /** Wipes the whole log -- used by the "Clear All" action in HistoryActivity. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_ENTRIES).apply()
    }

    /** Most recent first. */
    fun list(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = try {
            JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        } catch (_: Throwable) {
            return emptyList()
        }
        val out = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out.add(
                Entry(
                    startedAtMs = obj.optLong("startedAtMs"),
                    durationMs = obj.optLong("durationMs"),
                    peakListeners = obj.optInt("peakListeners"),
                    bytesUploaded = obj.optLong("bytesUploaded")
                )
            )
        }
        return out.asReversed()
    }
}
