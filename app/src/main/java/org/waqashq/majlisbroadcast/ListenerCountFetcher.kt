package org.waqashq.majlisbroadcast

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 7: reads the current listener count and public listen-page URL
 * from AzuraCast's now-playing API. Purely a read-only, best-effort UI
 * nicety -- entirely separate from the streaming pipeline (per section 0's
 * note that this was deliberately deferred to the end because it "touches
 * nothing in the streaming pipeline"). Any failure here must never affect
 * the broadcast; it's fine for this to keep failing silently from the
 * broadcaster's point of view, but a failure that's silent *everywhere* is
 * impossible to diagnose remotely, so failures are logged to DebugLog
 * (once per failure streak, not every poll) so the reason shows up next
 * time the user shares a debug log export.
 *
 * Two endpoints are supported:
 *  - /api/nowplaying (all public stations, as a JSON array) -- used when no
 *    station shortcode is configured. AzuraCast's own docs say this only
 *    includes stations flagged public, and there's a known upstream bug
 *    where unauthenticated requests get an empty array back even for a
 *    station that otherwise works fine (github.com/AzuraCast/AzuraCast
 *    issue #5352) -- this is what was happening on this deployment.
 *  - /api/nowplaying/{station_shortcode} (single station, as a JSON
 *    object) -- reliable regardless of the above, per AzuraCast's docs.
 *    Used whenever azuracast.station_shortcode is set in secrets.properties.
 */
object ListenerCountFetcher {
    private const val TIMEOUT_MS = 6_000

    @Volatile private var failureAlreadyLogged = false

    data class NowPlayingInfo(val listenerCount: Int, val publicPlayerUrl: String?)

    /** Returns current now-playing info, or null on any failure (network, parsing, etc). */
    fun fetch(apiBaseUrl: String, stationShortcode: String? = null): NowPlayingInfo? {
        val base = apiBaseUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            logFailureOnce("azuracast.api_base_url / azuracast.host is empty -- set it in secrets.properties")
            return null
        }
        val path = if (!stationShortcode.isNullOrBlank()) {
            "/api/nowplaying/${stationShortcode.trim()}"
        } else {
            "/api/nowplaying"
        }

        tryFetch(base + path)?.let { onSuccess(); return it }

        // Many personal/self-hosted AzuraCast setups don't sit behind a TLS
        // reverse proxy, so the web panel is plain HTTP even though this
        // build's default guess is https://<host>. If the https attempt
        // failed, retry once over http before giving up.
        if (base.startsWith("https://", ignoreCase = true)) {
            val httpFallback = "http://" + base.substring("https://".length)
            tryFetch(httpFallback + path)?.let { onSuccess(); return it }
        }

        logFailureOnce("could not reach $base$path (tried https and http) -- check azuracast.api_base_url / azuracast.station_shortcode in secrets.properties, and that the AzuraCast web panel (not just the source port) is reachable from this phone")
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

    private fun parseEntry(entry: JSONObject): NowPlayingInfo {
        val listeners = entry.getJSONObject("listeners").getInt("total")
        val playerUrl = entry.optJSONObject("station")?.optString("public_player_url")?.takeIf { it.isNotBlank() }
        return NowPlayingInfo(listeners, playerUrl)
    }

    private fun tryFetch(fullUrl: String): NowPlayingInfo? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(fullUrl)
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
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                // All-stations endpoint: array, first (and only, for this
                // single-station deployment) entry is the station.
                val stations = JSONArray(trimmed)
                if (stations.length() == 0) {
                    logFailureOnce("$url returned an empty station list -- this station is likely not marked Public in AzuraCast, or hit the known all-stations empty-array bug; set azuracast.station_shortcode in secrets.properties to use the reliable per-station endpoint instead")
                    null
                } else {
                    parseEntry(stations.getJSONObject(0))
                }
            } else {
                // Per-station endpoint: a single JSON object.
                parseEntry(JSONObject(trimmed))
            }
        } catch (t: Throwable) {
            logFailureOnce("${t.javaClass.simpleName}: ${t.message} while reaching $fullUrl")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
