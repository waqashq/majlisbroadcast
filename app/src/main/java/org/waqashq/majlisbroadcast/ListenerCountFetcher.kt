package org.waqashq.majlisbroadcast

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 7: reads the current listener count and public listen-page URL
 * from AzuraCast's public now-playing API. Purely a read-only, best-effort
 * UI nicety -- entirely separate from the streaming pipeline (per section
 * 0's note that this was deliberately deferred to the end because it
 * "touches nothing in the streaming pipeline"). Any failure here must
 * never affect the broadcast.
 *
 * Uses the all-stations endpoint (/api/nowplaying) rather than the
 * per-station shortcode endpoint (/api/nowplaying/{shortcode}) so no extra
 * secret/config value is needed beyond the host we already have -- this is
 * a personal, single-station deployment, so the first (and only) entry in
 * the array is the station.
 */
object ListenerCountFetcher {
    private const val TIMEOUT_MS = 6_000

    data class NowPlayingInfo(val listenerCount: Int, val publicPlayerUrl: String?)

    /** Returns current now-playing info, or null on any failure (network, parsing, etc). */
    fun fetch(apiBaseUrl: String): NowPlayingInfo? {
        if (apiBaseUrl.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$apiBaseUrl/api/nowplaying")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val stations = JSONArray(body)
            if (stations.length() == 0) {
                null
            } else {
                val entry = stations.getJSONObject(0)
                val listeners = entry.getJSONObject("listeners").getInt("total")
                val playerUrl = entry.optJSONObject("station")?.optString("public_player_url")?.takeIf { it.isNotBlank() }
                NowPlayingInfo(listeners, playerUrl)
            }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
