package org.waqashq.majlisbroadcast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Phase 5/7: the real (minimal) app UI -- see majlisbroadcast.md section 8.
 * Go Live/Stop, status, mic level/clipping meter, elapsed time, live
 * listener count, and lightweight diagnostics. Bilingual via res/values
 * (English) and res/values-ur (Urdu); RTL is handled by the system since
 * supportsRtl is set and nothing here hardcodes left/right.
 *
 * Phase 7 moved the view-only server panel and the debug log viewer out to
 * SettingsActivity to keep this screen bare per section 8, and added the
 * Settings entry point (top-right) plus a small cosmetic pass (UiTheme).
 *
 * The Phase 1 local-file test harness (AacFileRecorder) is no longer wired
 * into this screen -- it already served its purpose (validating capture/
 * encode/ADTS offline). The class itself is left in the repo in case it's
 * useful for future debugging.
 */
class MainActivity : AppCompatActivity() {

    private val prefBatteryExemptionAsked = "battery_exemption_asked"
    private val prefsName = "majlis_prefs"

    // Mirrors BroadcastEngine's private constants -- purely for display,
    // update here too if those ever change.
    private val queueCapacityDisplay = 150
    private val configuredBitrateKbps = 64

    private lateinit var statusText: TextView
    private lateinit var goLiveButton: Button
    private lateinit var elapsedText: TextView
    private lateinit var micLevelBar: ProgressBar
    private lateinit var micClippingText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var listenerCountText: TextView
    private lateinit var batteryStateLabel: TextView
    private lateinit var versionLabel: TextView

    private var isLive = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val livePoller = object : Runnable {
        override fun run() {
            pollLiveState()
            if (isLive) uiHandler.postDelayed(this, 300)
        }
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) proceedWithFirstRunChecks() else statusText.text = getString(R.string.status_mic_denied)
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless -- the FGS still runs, the notification just won't show without it */
        proceedWithFirstRunChecks()
    }

    private val requestBatteryExemption = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* proceed regardless of the user's choice */
        proceedWithFirstRunChecks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        DebugLog.log("App opened")

        // First-run permission chain fires immediately on open, not just
        // when Go Live is tapped (section 7: "First-run: request...").
        proceedWithFirstRunChecks()

        // If the service is already live from before this Activity was
        // (re)created, reflect that immediately instead of a stale button.
        if (BroadcastService.state == BroadcastEngine.State.LIVE ||
            BroadcastService.state == BroadcastEngine.State.CONNECTING ||
            BroadcastService.state == BroadcastEngine.State.RECONNECTING
        ) {
            isLive = true
            updateGoLiveButtonStyle()
            uiHandler.post(livePoller)
        }
    }

    private fun buildUi() {
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(24, 24, 24, 0)
        }
        val settingsButton = Button(this).apply {
            text = getString(R.string.btn_settings)
            textSize = 12f
        }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        topBar.addView(settingsButton)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 32, 48, 48)
        }

        statusText = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        goLiveButton = Button(this).apply {
            text = getString(R.string.btn_go_live)
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(64, 32, 64, 32)
        }
        goLiveButton.setOnClickListener { onGoLiveClicked() }

        elapsedText = TextView(this).apply { textSize = 14f }

        // ---- Mic meter card ----
        val micCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = UiTheme.cardBackground()
            setPadding(32, 24, 32, 24)
        }
        val micLevelLabel = TextView(this).apply {
            text = getString(R.string.mic_level_label)
            textSize = 12f
        }
        micLevelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        micClippingText = TextView(this).apply {
            text = getString(R.string.mic_clipping_warning)
            textSize = 12f
            setTextColor(UiTheme.STOP_RED)
            visibility = View.GONE
        }
        micLevelBar.layoutParams = LinearLayout.LayoutParams(600, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8
        }
        listOf(micLevelLabel, micLevelBar, micClippingText).forEach {
            micCard.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 })
        }

        // ---- Diagnostics card ----
        val diagCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = UiTheme.cardBackground()
            setPadding(32, 24, 32, 24)
        }
        diagnosticsText = TextView(this).apply {
            textSize = 11f
            alpha = 0.7f
            gravity = Gravity.CENTER
        }
        listenerCountText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.ACCENT_GOLD)
            visibility = View.GONE
        }
        listOf(diagnosticsText, listenerCountText).forEach {
            diagCard.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6 })
        }

        batteryStateLabel = TextView(this).apply { textSize = 11f; alpha = 0.6f }
        versionLabel = TextView(this).apply { textSize = 11f; alpha = 0.6f }

        listOf(
            statusText, goLiveButton, elapsedText,
            micCard, diagCard,
            batteryStateLabel, versionLabel
        ).forEach {
            content.addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 24 }
            )
        }

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        outer.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(outer) })

        updateGoLiveButtonStyle()
        refreshStaticInfo()
    }

    private fun refreshStaticInfo() {
        if (!isLive) statusText.text = getString(R.string.status_live_stopped)

        versionLabel.text = getString(R.string.build_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        val ignoring = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        batteryStateLabel.text = getString(
            if (ignoring) R.string.battery_state_ignoring else R.string.battery_state_not_ignoring
        )
    }

    /** Recolors/relabels the Go Live button for its current idle/live state (Phase 7 cosmetic pass). */
    private fun updateGoLiveButtonStyle() {
        goLiveButton.text = getString(if (isLive) R.string.btn_stop_live else R.string.btn_go_live)
        goLiveButton.background = UiTheme.pillButtonBackground(if (isLive) UiTheme.STOP_RED else UiTheme.PRIMARY_GREEN)
    }

    // ================= First-run permission chain (section 7) =================

    private fun proceedWithFirstRunChecks() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!prefs.getBoolean(prefBatteryExemptionAsked, false) &&
            !powerManager.isIgnoringBatteryOptimizations(packageName)
        ) {
            prefs.edit().putBoolean(prefBatteryExemptionAsked, true).apply()
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                requestBatteryExemption.launch(intent)
            } catch (_: Throwable) {
                // Some OEM skins don't implement this standard intent --
                // fall back to the app's own settings page.
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Throwable) {
                }
            }
            return
        }
        refreshStaticInfo()
    }

    // ================= Go Live =================

    private fun onGoLiveClicked() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            proceedWithFirstRunChecks()
            return
        }

        if (!isLive) {
            if (BuildConfig.AZURACAST_HOST.isBlank() || BuildConfig.AZURACAST_PORT == 0) {
                statusText.text = getString(R.string.status_live_missing_secrets)
                return
            }
            DebugLog.log("Go Live tapped")
            BroadcastService.start(this)
            isLive = true
            updateGoLiveButtonStyle()
            statusText.text = getString(
                R.string.status_live_connecting, BuildConfig.AZURACAST_HOST, BuildConfig.AZURACAST_PORT
            )
            uiHandler.post(livePoller)
        } else {
            DebugLog.log("Stop tapped")
            BroadcastService.stop(this)
            isLive = false
            updateGoLiveButtonStyle()
            statusText.text = getString(R.string.status_live_stopped)
            elapsedText.text = ""
            listenerCountText.visibility = View.GONE
            uiHandler.removeCallbacks(livePoller)
        }
    }

    private fun pollLiveState() {
        when (BroadcastService.state) {
            BroadcastEngine.State.CONNECTING -> statusText.text = getString(
                R.string.status_live_connecting, BuildConfig.AZURACAST_HOST, BuildConfig.AZURACAST_PORT
            )
            BroadcastEngine.State.LIVE -> {
                val muted = if (BroadcastService.focusLost) getString(R.string.status_muted_suffix) else ""
                statusText.text = getString(R.string.status_live_on_air) + muted
            }
            BroadcastEngine.State.RECONNECTING -> statusText.text = getString(R.string.status_live_reconnecting)
            BroadcastEngine.State.ERROR -> statusText.text = getString(
                R.string.status_live_error, BroadcastService.lastError ?: "unknown"
            )
            BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE -> {
                if (isLive) {
                    isLive = false
                    updateGoLiveButtonStyle()
                    statusText.text = getString(R.string.status_live_stopped)
                    elapsedText.text = ""
                    listenerCountText.visibility = View.GONE
                    uiHandler.removeCallbacks(livePoller)
                }
            }
        }

        if (BroadcastService.sessionStartRealtime > 0) {
            val elapsedSec = (SystemClock.elapsedRealtime() - BroadcastService.sessionStartRealtime) / 1000
            elapsedText.text = getString(R.string.elapsed_time_format, formatElapsed(elapsedSec))
        }

        micLevelBar.progress = BroadcastService.micLevel
        micClippingText.visibility = if (BroadcastService.micClipping) View.VISIBLE else View.GONE

        val count = BroadcastService.listenerCount
        if (isLive && count != null) {
            listenerCountText.text = getString(R.string.listener_count_label, count)
            listenerCountText.visibility = View.VISIBLE
        } else if (isLive) {
            listenerCountText.text = getString(R.string.listener_count_unavailable)
            listenerCountText.visibility = View.VISIBLE
        } else {
            listenerCountText.visibility = View.GONE
        }

        // Buffering-latency estimate: how long audio currently sits in our
        // own queue + the coalescing window -- NOT true end-to-end/network
        // latency, which we have no way to measure without server support.
        val latencyEstimateMs = (BroadcastService.queueDepth * 23) + 200
        diagnosticsText.text = getString(R.string.diagnostics_title) + "\n" + getString(
            R.string.diagnostics_line,
            BroadcastService.reconnectCount,
            BroadcastService.dropCount,
            BroadcastService.burstDropEvents,
            BroadcastService.queueDepth, queueCapacityDisplay,
            configuredBitrateKbps,
            latencyEstimateMs,
            BroadcastService.scoRefusalCount
        )
    }

    private fun formatElapsed(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(livePoller)
        // The broadcast service is NOT stopped here -- it keeps running in
        // the background. Only the explicit Stop control ends it.
    }
}
