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
 * never affect the broadcast; it's fine for this to keep failing silently
 * from the broadcaster's point of view, but a failure that's silent
 * *everywhere* is impossible to diagnose remotely, so failures are logged
 * to DebugLog (once per failure streak, not every poll) so the reason
 * shows up next time the user shares a debug log export.
 *
 * Uses the all-stations endpoint (/api/nowplaying) rather than the
 * per-station shortcode endpoint (/api/nowplaying/{shortcode}) so no extra
 * secret/config value is needed beyond the host we already have -- this is
 * a personal, single-station deployment, so the first (and only) entry in
 * the array is the station.
 */
object ListenerCountFetcher {
    private const val TIMEOUT_MS = 6_000

    @Volatile private var failureAlreadyLogged = false

    data class NowPlayingInfo(val listenerCount: Int, val publicPlayerUrl: String?)

    /** Returns current now-playing info, or null on any failure (network, parsing, etc). */
    fun fetch(apiBaseUrl: String): NowPlayingInfo? {
        val base = apiBaseUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            logFailureOnce("azuracast.api_base_url / azuracast.host is empty -- set it in secrets.properties")
            return null
        }

        tryFetch(base)?.let { onSuccess(); return it }

        // Many personal/self-hosted AzuraCast setups don't sit behind a TLS
        // reverse proxy, so the web panel is plain HTTP even though this
        // build's default guess is https://<host>. If the https attempt
        // failed, retry once over http before giving up.
        if (base.startsWith("https://", ignoreCase = true)) {
            val httpFallback = "http://" + base.substring("https://".length)
            tryFetch(httpFallback)?.let { onSuccess(); return it }
        }

        logFailureOnce("could not reach $base/api/nowplaying (tried https and http) -- check azuracast.api_base_url in secrets.properties, and that the AzuraCast web panel (not just the source port) is reachable from this phone")
        return null
    }

    private fun onSuccess() {
        failureAlreadyLogged = false
    }

    private fun logFailureOnce(reason: String) {
        if (!failureAlreadyLogged) {
            failureAlreadyLogged = true
            DebugLog.log("Listener count unavailable: $reason")
        }
    }

    private fun tryFetch(base: String): NowPlayingInfo? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$base/api/nowplaying")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                logFailureOnce("HTTP $code from $url")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val stations = JSONArray(body)
            if (stations.length() == 0) {
                logFailureOnce("$url returned an empty station list")
                null
            } else {
                val entry = stations.getJSONObject(0)
                val listeners = entry.getJSONObject("listeners").getInt("total")
                val playerUrl = entry.optJSONObject("station")?.optString("public_player_url")?.takeIf { it.isNotBlank() }
                NowPlayingInfo(listeners, playerUrl)
            }
        } catch (t: Throwable) {
            logFailureOnce("${t.javaClass.simpleName}: ${t.message} while reaching $base/api/nowplaying")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
