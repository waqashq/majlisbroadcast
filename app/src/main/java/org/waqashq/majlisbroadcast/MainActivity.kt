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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Phase 5/7+: the main Broadcast screen -- see majlisbroadcast.md section 8
 * (deliberately minimal in *feature* surface, though the Phase 7+ redesign
 * gave it a dedicated dark "studio" look independent of system light/dark
 * mode: status pill, latency, elapsed time, Go Live/Stop, local recording,
 * a live mic-level waveform, listener count, and a share-listen-link
 * action). Settings (view-only server panel, diagnostics, debug log,
 * language) lives in SettingsActivity, reached via the bottom nav.
 *
 * Bilingual via res/values (English) and res/values-ur (Urdu); RTL is
 * handled by the system since supportsRtl is set and nothing here
 * hardcodes left/right.
 *
 * The Phase 1 local-file test harness (AacFileRecorder) is superseded by
 * BroadcastEngine's own fork-to-file local recording (Phase 7+) and is no
 * longer wired in; left in the repo for reference.
 */
class MainActivity : AppCompatActivity() {

    private val prefBatteryExemptionAsked = "battery_exemption_asked"
    private val prefsName = "majlis_prefs"

    // Fixed Share Event content -- see onShareClicked().
    private val SHARE_MESSAGE_UR = "مجلس  آن  لائن  سننے  کے  لیے:"
    private val SHARE_URL = "https://waqashq.org/"

    private lateinit var statusPill: TextView
    private lateinit var latencyIcon: ImageView
    private lateinit var latencyText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var goLiveButton: Button
    private lateinit var recordButton: Button
    private lateinit var micCircle: View
    private lateinit var waveform: WaveformView
    private lateinit var bitrateText: TextView
    private lateinit var micClippingText: TextView
    private lateinit var listenerCountText: TextView
    private lateinit var shareButton: LinearLayout

    private var isLive = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val livePoller = object : Runnable {
        override fun run() {
            pollLiveState()
            // 150ms, not 300 -- matches BroadcastEngine's own mic-level
            // report throttle (reportLevel() there caps at ~150ms), so the
            // waveform picks up fresh data twice as often. Smaller, more
            // frequent position jumps read as noticeably smoother without
            // needing custom scroll interpolation (see WaveformView doc).
            if (isLive) uiHandler.postDelayed(this, 150)
        }
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) proceedWithFirstRunChecks() else statusSubtitle.text = getString(R.string.status_mic_denied)
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

    override fun onResume() {
        super.onResume()
        // Refreshes the bitrate meter to whatever's currently saved in
        // Settings -- but only while not live, so a bit rate changed
        // mid-broadcast (which the Settings screen itself says only takes
        // effect next time you go live) doesn't make the meter claim a
        // number the actual running session isn't using.
        if (!isLive) {
            bitrateText.text = currentBitrateLabel()
        }
    }

    private fun currentBitrateLabel(): String = getString(R.string.bitrate_format, AppSettings.bitRateBps(this) / 1000)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.STUDIO_BG)
        }

        // ---- Top header bar -- full width, fixed (not part of the
        // scrollable content), green background matching the logo's brand
        // color, white contrasting centered text. Sits directly below the
        // system status bar. ----
        val header = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(UiTheme.PRIMARY_GREEN)
            setPadding(24, 32, 24, 24)
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // ---- Logo header -- same badge artwork as the launcher icon, per
        // user request to reuse it inside the app (loading screen + here). ----
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            val size = (88 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (20 * resources.displayMetrics.density).toInt()
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        scrollContent.addView(logo)

        // ---- Card ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = UiTheme.studioCard()
            setPadding(48, 40, 48, 36)
        }

        statusPill = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(40, 14, 40, 14)
            gravity = Gravity.CENTER
        }

        val latencyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        latencyIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_signal_bars)
            layoutParams = LinearLayout.LayoutParams(32, 32)
        }
        latencyText = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(12, 0, 0, 0)
        }
        latencyRow.addView(latencyIcon)
        latencyRow.addView(latencyText)

        elapsedText = TextView(this).apply {
            textSize = 46f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            gravity = Gravity.CENTER
        }

        statusSubtitle = TextView(this).apply {
            textSize = 13f
            setTextColor(UiTheme.STUDIO_TEXT_MUTED)
            gravity = Gravity.CENTER
        }

        goLiveButton = Button(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(0, 34, 0, 34)
        }
        goLiveButton.setOnClickListener { onGoLiveClicked() }

        recordButton = Button(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            setPadding(0, 22, 0, 22)
        }
        recordButton.setOnClickListener { onRecordClicked() }

        // ---- mic + waveform + bitrate row ----
        val meterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val micStack = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(88, 88)
        }
        micCircle = View(this).apply {
            background = UiTheme.studioMicCircle(UiTheme.STUDIO_TEXT_MUTED)
        }
        val micIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.WHITE)
        }
        micStack.addView(micCircle, FrameLayout.LayoutParams(88, 88))
        micStack.addView(
            micIcon,
            FrameLayout.LayoutParams(44, 44).apply { gravity = Gravity.CENTER }
        )
        micStack.isClickable = true
        micStack.setOnClickListener { onMicToggleClicked() }

        waveform = WaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 80, 1f).apply {
                marginStart = 20
                marginEnd = 20
            }
        }

        bitrateText = TextView(this).apply {
            textSize = 12f
            setTextColor(UiTheme.STUDIO_TEXT_MUTED)
            text = currentBitrateLabel()
        }

        meterRow.addView(micStack)
        meterRow.addView(waveform)
        meterRow.addView(bitrateText)

        micClippingText = TextView(this).apply {
            text = getString(R.string.mic_clipping_warning)
            textSize = 11f
            setTextColor(UiTheme.STUDIO_STOP_RED)
            gravity = Gravity.CENTER
            // INVISIBLE (not GONE): reserves its row's height at all times
            // so it doesn't push the rest of the layout around when it
            // appears/disappears.
            visibility = View.INVISIBLE
        }

        listOf(
            statusPill, latencyRow, elapsedText, statusSubtitle,
            goLiveButton, recordButton, meterRow, micClippingText
        ).forEach {
            card.addView(
                it,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 20
                }
            )
        }
        // goLiveButton/recordButton/meterRow should stretch to the card's
        // width. Generous top margins per user feedback: more breathing
        // room between elapsed time -> Go Live, Stop -> Start Recording,
        // and Start Recording -> the mic/waveform row.
        goLiveButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 44 }
        recordButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 28 }
        meterRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 40 }
        micClippingText.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }

        scrollContent.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- Listeners + Share (below the card) ----
        listenerCountText = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_ON_AIR_GREEN)
            gravity = Gravity.CENTER
        }
        // Solid fill (distinct from the outline style used elsewhere), per
        // request -- dark text/icon for contrast against the bright green.
        val shareIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_share)
            setColorFilter(UiTheme.STUDIO_BG)
            layoutParams = LinearLayout.LayoutParams(30, 30)
        }
        val shareLabel = TextView(this).apply {
            text = getString(R.string.btn_share_event)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_BG)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 16
            }
        }
        shareButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = UiTheme.pillButtonBackground(UiTheme.STUDIO_ON_AIR_GREEN)
            setPadding(0, 22, 0, 22)
            isClickable = true
            isFocusable = true
            addView(shareIcon)
            addView(shareLabel)
        }
        shareButton.setOnClickListener { onShareClicked() }

        listOf(listenerCountText, shareButton).forEach {
            scrollContent.addView(
                it,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 40
                }
            )
        }

        val scrollView = ScrollView(this).apply { addView(scrollContent) }

        // ---- Bottom nav ----
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(UiTheme.STUDIO_CARD_BG)
        }
        val navBroadcast = buildNavTab(R.drawable.ic_radio, getString(R.string.nav_broadcast), active = true)
        val navSettings = buildNavTab(R.drawable.ic_settings_sliders, getString(R.string.btn_settings), active = false)
        navSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        nav.addView(navBroadcast, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(navSettings, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        updateGoLiveButtonStyle()
        updateRecordButtonStyle()
        refreshStaticInfo()
    }

    private fun buildNavTab(iconRes: Int, label: String, active: Boolean): LinearLayout {
        val tab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 22)
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(if (active) UiTheme.STUDIO_BORDER_TEAL else UiTheme.STUDIO_TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(44, 44)
        }
        val text = TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (active) UiTheme.STUDIO_BORDER_TEAL else UiTheme.STUDIO_TEXT_MUTED)
            gravity = Gravity.CENTER
        }
        tab.addView(icon, LinearLayout.LayoutParams(44, 44).apply { topMargin = 0 })
        tab.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6 })
        return tab
    }

    private fun refreshStaticInfo() {
        if (!isLive) applyStatusStyle(BroadcastEngine.State.IDLE, callMuted = false, manualMuted = false)
    }

    /** Recolors/relabels the Go Live button for its current idle/live state. */
    private fun updateGoLiveButtonStyle() {
        goLiveButton.text = getString(if (isLive) R.string.btn_stop_live else R.string.btn_go_live)
        goLiveButton.background = UiTheme.pillButtonBackground(if (isLive) UiTheme.STUDIO_STOP_RED else UiTheme.PRIMARY_GREEN)
    }

    private fun updateRecordButtonStyle() {
        val recording = BroadcastService.isRecording
        recordButton.text = getString(if (recording) R.string.btn_stop_recording else R.string.btn_start_recording)
        recordButton.background = UiTheme.outlinePillBackground(if (recording) UiTheme.STUDIO_STOP_RED else UiTheme.STUDIO_TEXT_MUTED)
        recordButton.setTextColor(if (recording) UiTheme.STUDIO_STOP_RED else UiTheme.STUDIO_TEXT_PRIMARY)
        recordButton.isEnabled = isLive
        recordButton.alpha = if (isLive) 1f else 0.5f
    }

    /** Central place mapping engine state (+ mute) to the pill badge, subtitle, and latency row. */
    private fun applyStatusStyle(state: BroadcastEngine.State, callMuted: Boolean, manualMuted: Boolean) {
        val (pillText, pillBg, pillFg) = when (state) {
            BroadcastEngine.State.CONNECTING -> Triple(getString(R.string.status_pill_connecting), UiTheme.STUDIO_CARD_BG, UiTheme.STUDIO_AMBER)
            BroadcastEngine.State.LIVE -> Triple(getString(R.string.status_pill_on_air), UiTheme.STUDIO_ON_AIR_BG, UiTheme.STUDIO_ON_AIR_GREEN)
            BroadcastEngine.State.RECONNECTING -> Triple(getString(R.string.status_pill_reconnecting), UiTheme.STUDIO_CARD_BG, UiTheme.STUDIO_AMBER)
            BroadcastEngine.State.ERROR -> Triple(getString(R.string.status_pill_error), UiTheme.STUDIO_CARD_BG, UiTheme.STUDIO_STOP_RED)
            BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE -> Triple(getString(R.string.status_pill_offline), UiTheme.STUDIO_CARD_BG, UiTheme.STUDIO_TEXT_MUTED)
        }
        statusPill.text = pillText
        statusPill.background = UiTheme.studioPillBadge(pillBg)
        statusPill.setTextColor(pillFg)

        val isLiveState = state == BroadcastEngine.State.LIVE
        statusSubtitle.text = if (isLiveState && callMuted) {
            getString(R.string.status_subtitle_muted)
        } else if (isLiveState && manualMuted) {
            getString(R.string.status_subtitle_muted_manual)
        } else {
            when (state) {
                BroadcastEngine.State.CONNECTING -> getString(R.string.status_subtitle_connecting)
                BroadcastEngine.State.LIVE -> getString(R.string.status_subtitle_on_air)
                BroadcastEngine.State.RECONNECTING -> getString(R.string.status_subtitle_reconnecting)
                BroadcastEngine.State.ERROR -> getString(R.string.status_subtitle_error)
                BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE -> getString(R.string.status_subtitle_offline)
            }
        }

        val micColor = when {
            isLiveState && (callMuted || manualMuted) -> UiTheme.STUDIO_STOP_RED
            isLiveState -> UiTheme.STUDIO_ON_AIR_GREEN
            else -> UiTheme.STUDIO_TEXT_MUTED
        }
        micCircle.background = UiTheme.studioMicCircle(micColor)
        latencyIcon.setColorFilter(if (isLiveState) UiTheme.STUDIO_ON_AIR_GREEN else UiTheme.STUDIO_TEXT_MUTED)
        latencyText.setTextColor(if (isLiveState) UiTheme.STUDIO_ON_AIR_GREEN else UiTheme.STUDIO_TEXT_MUTED)
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
            if (!AppSettings.isConfigured(this)) {
                statusSubtitle.text = getString(R.string.status_live_missing_secrets)
                return
            }
            DebugLog.log("Go Live tapped")
            // Snapshot now, not just at buildUi() time -- this is the
            // exact value BroadcastService/BroadcastEngine will read a
            // moment from now, so the meter reflects the actual running
            // session even if Settings was changed since the app opened.
            bitrateText.text = currentBitrateLabel()
            BroadcastService.start(this)
            isLive = true
            updateGoLiveButtonStyle()
            updateRecordButtonStyle()
            applyStatusStyle(BroadcastEngine.State.CONNECTING, callMuted = false, manualMuted = false)
            uiHandler.post(livePoller)
            awaitStateAndShowDialog(
                setOf(BroadcastEngine.State.LIVE),
                getString(R.string.dialog_broadcast_started)
            )
        } else {
            DebugLog.log("Stop tapped")
            BroadcastService.stop(this)
            isLive = false
            updateGoLiveButtonStyle()
            updateRecordButtonStyle()
            applyStatusStyle(BroadcastEngine.State.STOPPED, callMuted = false, manualMuted = false)
            elapsedText.text = ""
            latencyText.text = getString(R.string.latency_unavailable)
            listenerCountText.text = getString(R.string.listener_count_unavailable)
            waveform.reset()
            uiHandler.removeCallbacks(livePoller)
            awaitStateAndShowDialog(
                setOf(BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE),
                getString(R.string.dialog_broadcast_ended)
            )
        }
    }

    /**
     * Watches BroadcastService.state (separately from the main livePoller,
     * whose own scheduling is tied to isLive and stops right after Stop is
     * tapped) until it actually reaches one of [targetStates], then shows
     * the success modal -- per request, "Broadcast Started"/"Broadcast
     * Ended" should reflect a confirmed connect/disconnect, not just the
     * button tap. Gives up silently after [timeoutMs] (e.g. the connect
     * attempt ends in ERROR instead of LIVE -- no dialog is correct there,
     * the status pill already shows the error).
     */
    private fun awaitStateAndShowDialog(
        targetStates: Set<BroadcastEngine.State>,
        message: String,
        timeoutMs: Long = 20_000L
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val checkRunnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val state = BroadcastService.state
                if (state in targetStates) {
                    SuccessOverlay.show(this@MainActivity, message)
                } else if (SystemClock.elapsedRealtime() < deadline) {
                    uiHandler.postDelayed(this, 250)
                }
            }
        }
        uiHandler.post(checkRunnable)
    }

    private fun onRecordClicked() {
        if (!isLive) return
        if (BroadcastService.isRecording) {
            BroadcastService.stopRecording(this)
            BroadcastService.lastRecordingFileName?.let { name ->
                val location = BroadcastService.lastRecordingLocation ?: "Music"
                Toast.makeText(this, getString(R.string.recording_saved_toast, name, location), Toast.LENGTH_LONG).show()
            }
        } else {
            BroadcastService.startRecording(this)
        }
        // Reflects the *intent*; the next 300ms poll tick picks up the
        // service's actual confirmed state (it's a fire-and-forget Intent).
        updateRecordButtonStyle()
    }

    private fun onMicToggleClicked() {
        if (!isLive) return
        BroadcastService.setMicMuted(this, !BroadcastService.manuallyMuted)
        // Next 300ms poll tick reflects the service's confirmed state
        // (fire-and-forget Intent, same pattern as recording).
    }

    private fun onShareClicked() {
        // Fixed message + the station's public-facing site -- deliberately
        // not derived from AzuraCast's own API/host (that's the admin
        // panel's address, not something to hand to listeners) and
        // deliberately not localized to the app's own display language,
        // per request: this is what a listener should see regardless of
        // which language the broadcaster's own app UI is set to.
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$SHARE_MESSAGE_UR\n$SHARE_URL")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_event_chooser_title)))
    }

    private fun pollLiveState() {
        val state = BroadcastService.state
        applyStatusStyle(state, BroadcastService.focusLost, BroadcastService.manuallyMuted)

        when (state) {
            BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE -> {
                if (isLive) {
                    isLive = false
                    updateGoLiveButtonStyle()
                    updateRecordButtonStyle()
                    elapsedText.text = ""
                    latencyText.text = getString(R.string.latency_unavailable)
                    listenerCountText.text = getString(R.string.listener_count_unavailable)
                    waveform.reset()
                    uiHandler.removeCallbacks(livePoller)
                }
            }
            else -> {}
        }

        if (BroadcastService.sessionStartRealtime > 0) {
            val elapsedSec = (SystemClock.elapsedRealtime() - BroadcastService.sessionStartRealtime) / 1000
            elapsedText.text = formatElapsed(elapsedSec)
        }

        waveform.pushLevel(BroadcastService.micLevel)
        micClippingText.visibility = if (BroadcastService.micClipping) View.VISIBLE else View.INVISIBLE

        val latencyEstimateMs = (BroadcastService.queueDepth * 23) + 200
        latencyText.text = if (state == BroadcastEngine.State.LIVE) {
            getString(R.string.latency_format, latencyEstimateMs)
        } else {
            getString(R.string.latency_unavailable)
        }

        val count = BroadcastService.listenerCount
        listenerCountText.text = if (isLive && count != null) {
            getString(R.string.listener_count_label, count)
        } else {
            getString(R.string.listener_count_unavailable)
        }

        updateRecordButtonStyle()
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
