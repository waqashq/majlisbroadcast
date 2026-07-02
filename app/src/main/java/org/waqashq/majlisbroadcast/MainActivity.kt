package org.waqashq.majlisbroadcast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.util.Locale

/**
 * Phase 5: the real (minimal) app UI -- see majlisbroadcast.md section 8.
 * Go Live/Stop, status, mic level/clipping meter, elapsed time, a
 * view-only server panel, lightweight diagnostics, and an exportable
 * rolling debug log. Bilingual via res/values (English) and
 * res/values-ur (Urdu); RTL is handled by the system since supportsRtl is
 * set and nothing here hardcodes left/right.
 *
 * The Phase 1 local-file test harness (AacFileRecorder) is no longer wired
 * into this screen -- it already served its purpose (validating capture/
 * encode/ADTS offline) and section 8 wants the shipped UI to "stay bare."
 * The class itself is left in the repo in case it's useful for future
 * debugging.
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
    private lateinit var serverPanelText: TextView
    private lateinit var diagnosticsText: TextView
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
            goLiveButton.text = getString(R.string.btn_stop_live)
            uiHandler.post(livePoller)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 64, 48, 48)
        }

        statusText = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
        }
        goLiveButton = Button(this).apply {
            text = getString(R.string.btn_go_live)
            textSize = 18f
        }
        goLiveButton.setOnClickListener { onGoLiveClicked() }

        elapsedText = TextView(this).apply { textSize = 14f }

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
            setTextColor(Color.RED)
            visibility = View.GONE
        }

        serverPanelText = TextView(this).apply {
            textSize = 12f
            alpha = 0.8f
            gravity = Gravity.CENTER
        }

        diagnosticsText = TextView(this).apply {
            textSize = 11f
            alpha = 0.7f
            gravity = Gravity.CENTER
        }

        val viewLogButton = Button(this).apply {
            text = getString(R.string.btn_view_log)
            textSize = 11f
        }
        viewLogButton.setOnClickListener { showDebugLogDialog() }

        batteryStateLabel = TextView(this).apply { textSize = 11f; alpha = 0.6f }
        versionLabel = TextView(this).apply { textSize = 11f; alpha = 0.6f }

        listOf(
            statusText, goLiveButton, elapsedText,
            micLevelLabel, micLevelBar, micClippingText,
            serverPanelText, diagnosticsText, viewLogButton,
            batteryStateLabel, versionLabel
        ).forEach {
            root.addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 20 }
            )
        }
        // Horizontal ProgressBar needs an explicit width -- WRAP_CONTENT
        // collapses it to almost nothing.
        micLevelBar.layoutParams = LinearLayout.LayoutParams(600, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 8
        }

        setContentView(ScrollView(this).apply { addView(root) })

        refreshStaticInfo()
    }

    private fun refreshStaticInfo() {
        if (!isLive) statusText.text = getString(R.string.status_live_stopped)

        val serverLine = if (BuildConfig.AZURACAST_HOST.isNotBlank()) {
            getString(R.string.server_panel_host_port, BuildConfig.AZURACAST_HOST, BuildConfig.AZURACAST_PORT)
        } else {
            getString(R.string.server_panel_not_configured)
        }
        serverPanelText.text = getString(R.string.server_panel_title) + "\n" + serverLine

        versionLabel.text = getString(R.string.build_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        val ignoring = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        batteryStateLabel.text = getString(
            if (ignoring) R.string.battery_state_ignoring else R.string.battery_state_not_ignoring
        )
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
            goLiveButton.text = getString(R.string.btn_stop_live)
            statusText.text = getString(
                R.string.status_live_connecting, BuildConfig.AZURACAST_HOST, BuildConfig.AZURACAST_PORT
            )
            uiHandler.post(livePoller)
        } else {
            DebugLog.log("Stop tapped")
            BroadcastService.stop(this)
            isLive = false
            goLiveButton.text = getString(R.string.btn_go_live)
            statusText.text = getString(R.string.status_live_stopped)
            elapsedText.text = ""
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
                    goLiveButton.text = getString(R.string.btn_go_live)
                    statusText.text = getString(R.string.status_live_stopped)
                    elapsedText.text = ""
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

    // ================= Debug log (section 9) =================

    private fun showDebugLogDialog() {
        val lines = DebugLog.snapshot()
        val text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")

        val logView = TextView(this).apply {
            this.text = text
            textSize = 12f
            setPadding(32, 32, 32, 32)
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.log_title))
            .setView(scroll)
            .setPositiveButton(getString(R.string.btn_export_log)) { _, _ -> exportDebugLog() }
            .setNegativeButton(getString(R.string.btn_close_log), null)
            .show()
    }

    private fun exportDebugLog() {
        val file = DebugLog.export(this)
        android.widget.Toast.makeText(this, getString(R.string.log_exported, file.name), android.widget.Toast.LENGTH_SHORT).show()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.log_share_title)))
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(livePoller)
        // The broadcast service is NOT stopped here -- it keeps running in
        // the background. Only the explicit Stop control ends it.
    }
}
