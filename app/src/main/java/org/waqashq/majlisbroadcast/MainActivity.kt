package org.waqashq.majlisbroadcast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    companion object {
        /** Phase 9: home-screen "Go Live" shortcut extra, forwarded through Login -> Splash -> here. */
        const val EXTRA_AUTO_GO_LIVE = "org.waqashq.majlisbroadcast.extra.AUTO_GO_LIVE"
    }

    private val prefBatteryExemptionAsked = "battery_exemption_asked"
    private val prefsName = "majlis_prefs"

    // Fixed Share Event content -- see onShareClicked().
    private val SHARE_MESSAGE_UR = "مجلس  آن  لائن  سننے  کے  لیے:"
    private val SHARE_URL = "https://waqashq.org/"

    private lateinit var appLight: StatusLightView
    private lateinit var statusPill: TextView
    private lateinit var latencyIcon: ImageView
    private lateinit var latencyText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var goLiveButton: Button
    private lateinit var recordButton: Button
    private lateinit var micIcon: ImageView
    private lateinit var visualizer: SpectrumView
    private lateinit var disconnectedIcon: ImageView
    private lateinit var bitrateText: TextView
    private lateinit var micClippingText: TextView
    private lateinit var listenerCountText: TextView
    private lateinit var shareButton: LinearLayout
    private lateinit var bassSeekBar: SeekBar
    private lateinit var bassValueText: TextView
    private lateinit var echoSeekBar: SeekBar
    private lateinit var echoValueText: TextView
    private lateinit var websiteLight: StatusLightView
    private lateinit var websiteStatusText: TextView

    // Phase 11: website live light polling -- runs only while this screen
    // is in the foreground (onResume..onPause), whether or not the app
    // itself is broadcasting. 15s matches waqashq.org's own player script.
    private val websitePollIntervalMs = 15_000L
    private var websitePolling = false
    @Volatile private var websiteFetchInFlight = false
    private val websitePoller = object : Runnable {
        override fun run() {
            fetchWebsiteStatus()
            if (websitePolling) uiHandler.postDelayed(this, websitePollIntervalMs)
        }
    }

    // Phase 11f: local mic preview -- drives the frequency bars while NOT
    // broadcasting so the mic can be checked before going live. Holds the
    // mic, so it must be released before BroadcastService starts.
    private var micPreview: MicPreview? = null
    // Phase 11g: mute now works while idle too. BroadcastService applies mute
    // to the running engine, so with nothing live there is no engine to tell:
    // muting while idle simply stops the preview capture, and the choice is
    // carried into the broadcast once the engine exists (pendingMuteOnLive).
    private var previewMuted = false
    private var pendingMuteOnLive = false
    private var roundButtonSizePx = 0
    private var isLive = false
    // Phase 9: set from the "Go Live" shortcut's intent extra, consumed
    // (set back to false) the first time maybeAutoGoLive() actually fires.
    private var pendingAutoGoLive = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val livePoller = object : Runnable {
        override fun run() {
            pollLiveState()
            // 150ms, matching BroadcastEngine's own mic-level/spectrum
            // report throttle (reportLevel() there caps at ~150ms), so the
            // visualizer gets fresh band data as soon as it exists. Its own
            // per-frame easing (SpectrumView) does the smoothing between
            // these updates.
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
        // Every other screen already hides the system ActionBar in favor of
        // its own custom header -- this one was missing it, so the system
        // bar (showing the app name in the default light theme colors) was
        // rendering as a second header stacked above the custom green one.
        supportActionBar?.hide()
        pendingAutoGoLive = intent?.getBooleanExtra(EXTRA_AUTO_GO_LIVE, false) == true
        buildUi()
        DebugLog.log("App opened")

        // Phase 8c: sweep any recordings left behind in the old app-private
        // location (from before that storage bug was fixed) into the new
        // public Music folder, so they become visible without the user
        // having to do anything. Cheap no-op once nothing's left to move.
        migrateOldRecordingsIfAny()

        // If the service is already live from before this Activity was
        // (re)created, reflect that immediately -- this MUST run before
        // proceedWithFirstRunChecks() below, since that can synchronously
        // reach maybeAutoGoLive() (Phase 9 shortcut flow), whose "don't
        // fire if already live" guard depends on isLive already being
        // correct. Without this ordering, recreating this Activity (e.g. a
        // rotation -- this screen isn't orientation-locked) while a
        // shortcut-started broadcast is already live could fire Go Live a
        // second time and pop a spurious "Broadcast Started" dialog.
        if (BroadcastService.state == BroadcastEngine.State.LIVE ||
            BroadcastService.state == BroadcastEngine.State.CONNECTING ||
            BroadcastService.state == BroadcastEngine.State.RECONNECTING
        ) {
            isLive = true
            updateGoLiveButtonStyle()
            uiHandler.post(livePoller)
        }

        // First-run permission chain fires immediately on open, not just
        // when Go Live is tapped (section 7: "First-run: request...").
        proceedWithFirstRunChecks()
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
        websitePolling = true
        uiHandler.removeCallbacks(websitePoller)
        uiHandler.post(websitePoller)
        startMicPreview()
    }

    override fun onPause() {
        super.onPause()
        websitePolling = false
        uiHandler.removeCallbacks(websitePoller)
        // Never hold the mic (or burn battery) while off screen.
        stopMicPreview()
    }

    private fun currentBitrateLabel(): String = getString(R.string.bitrate_format, AppSettings.bitRateBps(this) / 1000)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.STUDIO_BG)
        }

        // ---- Top header row -- fixed (not part of the scrollable
        // content), no colored bar behind it (redesign: flat, blends into
        // the screen background like every other header in the app). Small
        // logo badge beside the "Malfoozat e Akhtar" title, start-aligned
        // -- Gravity.START (not an explicit left/right) so this reads
        // left-aligned in English and mirrors to right-aligned in Urdu
        // automatically via RTL layout direction.
        val headerLogo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            val size = (32 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (12 * resources.displayMetrics.density).toInt()
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        val headerTitle = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(24, 32, 24, 20)
            addView(headerLogo)
            addView(headerTitle)
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // ---- Card ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = UiTheme.studioCard()
            setPadding(48, 40, 48, 36)
        }

        // ---- Two matching indicator chips, each [lamp][icon][state]:
        //  - phone icon: the app's own connection (ON AIR / CONNECTING / ...)
        //  - globe icon: what listeners on waqashq.org actually see
        //    (AzuraCast's live.is_live, Phase 11)
        // The two can legitimately disagree, which is the point of showing
        // both. Stacked rather than side by side so longer labels
        // (RECONNECTING, Urdu) never overflow a narrow card. ----
        appLight = StatusLightView(this)
        statusPill = indicatorText()
        val statusChip = indicatorChip(appLight, R.drawable.ic_phone, R.string.cd_app_status, statusPill)

        websiteLight = StatusLightView(this)
        websiteStatusText = indicatorText()
        val websiteChip = indicatorChip(websiteLight, R.drawable.ic_globe, R.string.cd_website_status, websiteStatusText)
        applyWebsiteStatus(StatusLightView.Lamp.UNKNOWN)

        // Phase 11d: side by side (was stacked), each chip taking an equal
        // half of the card's width so the pair reads as one row and the two
        // lamps sit at the same height. Equal halves rather than
        // wrap-content because the app chip's label changes width with
        // state (OFFLINE -> CONNECTING -> RECONNECTING), which would
        // otherwise shift the website chip sideways on every transition.
        val indicators = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statusChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(websiteChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 14 })
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

        // Phase 11e: fills the card's empty middle while not on air -- the
        // space the elapsed clock occupies once live. Muted, not red: this
        // is a resting state, not an error (the chips above already say
        // OFFLINE), and the two swap places in updateGoLiveButtonStyle.
        disconnectedIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_disconnected)
            setColorFilter(UiTheme.STUDIO_TEXT_MUTED)
            contentDescription = getString(R.string.status_pill_offline)
        }

        statusSubtitle = TextView(this).apply {
            textSize = 13f
            setTextColor(UiTheme.STUDIO_TEXT_MUTED)
            gravity = Gravity.CENTER
        }

        // Phase 11f: the two primary controls are round domed buttons side
        // by side (LIVE / REC) instead of two stacked full-width pills.
        // Short labels because a circle has far less room than a pill.
        // Phase 11g: 30% smaller than 112dp, per request.
        roundButtonSizePx = (78 * resources.displayMetrics.density).toInt()
        goLiveButton = Button(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            // Buttons carry a default minWidth/minHeight and insets that
            // would stop a circle from actually being circular.
            minWidth = 0
            minHeight = 0
            stateListAnimator = null
        }
        goLiveButton.setOnClickListener { onGoLiveClicked() }

        recordButton = Button(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            stateListAnimator = null
        }
        recordButton.setOnClickListener { onRecordClicked() }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(goLiveButton, LinearLayout.LayoutParams(roundButtonSizePx, roundButtonSizePx))
            addView(
                recordButton,
                LinearLayout.LayoutParams(roundButtonSizePx, roundButtonSizePx).apply {
                    marginStart = (28 * resources.displayMetrics.density).toInt()
                }
            )
        }

        // ---- mic + waveform + bitrate row -- a flat bordered inset strip
        // (redesign) instead of a filled circle behind the mic icon. Tapping
        // the mic icon still toggles mute while live, same as before, just
        // restyled: a plain glyph whose tint reflects mute state instead of
        // a colored circle behind it. ----
        val meterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiTheme.insetBackground()
            setPadding(24, 18, 24, 18)
        }
        // Phase 11f: much bigger and now visibly a button (bordered circle)
        // -- at 36px beside the new 240px bars it read as decoration, and it
        // is in fact the mute control (tap while live to mute/unmute).
        micIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            val d = resources.displayMetrics.density
            val box = (52 * d).toInt()
            val pad = (13 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(box, box).apply { marginEnd = (14 * d).toInt() }
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.cd_mic_mute)
        }
        micIcon.setOnClickListener { onMicToggleClicked() }

        // Phase 11e: real frequency bars (SpectrumAnalyzer), much taller than
        // the old 40px level strip -- the height is what makes it read as a
        // visualizer rather than a thin meter line.
        visualizer = SpectrumView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 168, 1f).apply {
                marginEnd = 16
            }
        }

        bitrateText = TextView(this).apply {
            textSize = 11f
            setTextColor(UiTheme.STUDIO_TEXT_MUTED)
            text = currentBitrateLabel()
        }

        meterRow.addView(micIcon)
        meterRow.addView(visualizer)
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

        // Phase 11g: the elapsed clock (live) and the disconnected icon
        // (idle) sit in ONE fixed-height slot, both centred, so swapping
        // between them cannot nudge anything above or below -- the screen
        // stayed still complaint. Height is set from the taller of the two.
        val statusSlot = FrameLayout(this).apply {
            addView(
                disconnectedIcon,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )
            addView(
                elapsedText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )
        }

        listOf(
            indicators, latencyRow, statusSlot, statusSubtitle,
            buttonRow, meterRow, micClippingText
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
        // Explicit size: the loop above adds every card child with
        // WRAP_CONTENT params, which would otherwise leave this at the
        // drawable's own 48dp and look lost in the space it's filling.
        val discSize = (56 * resources.displayMetrics.density).toInt()
        disconnectedIcon.layoutParams = FrameLayout.LayoutParams(discSize, discSize).apply { gravity = Gravity.CENTER }
        // Fixed height, comfortably fitting both the 46sp clock and the icon.
        statusSlot.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (76 * resources.displayMetrics.density).toInt()
        ).apply { topMargin = 20 }
        // Full card width so the two indicator chips inside can split it in half.
        indicators.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        buttonRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 40 }
        meterRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 40 }
        micClippingText.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        scrollContent.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- Voice effects card (Phase 9): optional Bass Boost / Echo,
        // both default to whatever was last saved (0/off on a fresh
        // install). Applied live via BroadcastService while on air;
        // otherwise just remembered in AppSettings for the next session. ----
        val fxCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiTheme.studioCard()
            setPadding(48, 32, 48, 32)
        }
        val fxTitle = TextView(this).apply {
            text = getString(R.string.fx_section_title)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_BORDER_TEAL)
            gravity = Gravity.CENTER
        }
        fxCard.addView(fxTitle)

        val bassLabelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bassLabel = TextView(this).apply {
            text = getString(R.string.fx_bass_label)
            textSize = 13f
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bassValueText = TextView(this).apply {
            textSize = 13f
            setTextColor(UiTheme.STUDIO_TEXT_MUTED)
        }
        bassLabelRow.addView(bassLabel)
        bassLabelRow.addView(bassValueText)
        fxCard.addView(bassLabelRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })

        bassSeekBar = SeekBar(this).apply {
            max = 100
            progress = AppSettings.bassLevel(this@MainActivity)
            progressTintList = ColorStateList.valueOf(UiTheme.STUDIO_BORDER_TEAL)
            thumbTintList = ColorStateList.valueOf(UiTheme.STUDIO_BORDER_TEAL)
        }
        bassValueText.text = getString(R.string.fx_percent_format, bassSeekBar.progress)
        bassSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                bassValueText.text = getString(R.string.fx_percent_format, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val level = seekBar?.progress ?: 0
                AppSettings.saveBassLevel(this@MainActivity, level)
                if (isLive) BroadcastService.setBassLevel(this@MainActivity, level)
            }
        })
        fxCard.addView(bassSeekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 })

        val echoLabelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val echoLabel = TextView(this).apply {
            text = getString(R.string.fx_echo_label)
            textSize = 13f
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        echoValueText = TextView(this).apply {
            textSize = 13f
            setTextColor(UiTheme.STUDIO_TEXT_MUTED)
        }
        echoLabelRow.addView(echoLabel)
        echoLabelRow.addView(echoValueText)
        fxCard.addView(echoLabelRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })

        echoSeekBar = SeekBar(this).apply {
            max = 100
            progress = AppSettings.echoLevel(this@MainActivity)
            progressTintList = ColorStateList.valueOf(UiTheme.STUDIO_BORDER_TEAL)
            thumbTintList = ColorStateList.valueOf(UiTheme.STUDIO_BORDER_TEAL)
        }
        echoValueText.text = getString(R.string.fx_percent_format, echoSeekBar.progress)
        echoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                echoValueText.text = getString(R.string.fx_percent_format, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val level = seekBar?.progress ?: 0
                AppSettings.saveEchoLevel(this@MainActivity, level)
                if (isLive) BroadcastService.setEchoLevel(this@MainActivity, level)
            }
        })
        fxCard.addView(echoSeekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 })

        scrollContent.addView(
            fxCard,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 28 }
        )

        // ---- Listeners + Share (below the card) ----
        listenerCountText = TextView(this).apply {
            textSize = 15f
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

        // Phase 11c: 28px gaps to match the spacing between the two cards
        // above (was 40). The listener/data rows are hidden entirely while
        // not live (see updateGoLiveButtonStyle) -- they were blank then
        // anyway, and left a large empty gap above Share Event.
        // Phase 11g: listeners + data on ONE line, and only ever INVISIBLE
        // (never GONE) while idle -- its row stays reserved so nothing below
        // it moves when going live or stopping. dataUsageText is folded into
        // this line and no longer added to the layout.
        scrollContent.addView(
            listenerCountText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        )
        scrollContent.addView(
            shareButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 28 }
        )

        val scrollView = ScrollView(this).apply { addView(scrollContent) }

        // ---- Bottom nav -- shared across all four top-level screens, see
        // BottomNav.kt (previously built inline here only, so it disappeared
        // the moment you navigated to Recordings/History/Settings). ----
        val nav = buildBottomNav(NavTab.BROADCAST)

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        updateGoLiveButtonStyle()
        updateRecordButtonStyle()
        refreshStaticInfo()
    }

    private fun refreshStaticInfo() {
        if (!isLive) applyStatusStyle(BroadcastEngine.State.IDLE, callMuted = false, manualMuted = false)
    }

    /** Recolors/relabels the Go Live button for its current idle/live state. */
    private fun updateGoLiveButtonStyle() {
        goLiveButton.text = getString(if (isLive) R.string.btn_stop_short else R.string.btn_live_short)
        goLiveButton.background = UiTheme.round3dButton(
            if (isLive) UiTheme.STUDIO_STOP_RED else UiTheme.PRIMARY_GREEN,
            roundButtonSizePx.toFloat()
        )
        // Dark text on the flat accent fill (idle "Go live"), light text on
        // the flat red fill (live "Stop") -- same on-fill pairing already
        // used by the Share button elsewhere on this screen.
        goLiveButton.setTextColor(if (isLive) UiTheme.STUDIO_TEXT_PRIMARY else UiTheme.STUDIO_BG)
        // Called on every idle<->live transition, so the live-only rows
        // below the voice effects card are shown/hidden here too.
        // INVISIBLE, not GONE: the row keeps its space so the Share button
        // and everything else stays put across live/idle transitions.
        listenerCountText.visibility = if (isLive) View.VISIBLE else View.INVISIBLE
        // The elapsed clock and the disconnected icon live in the same
        // fixed-height slot (statusSlot), so swapping them cannot move
        // anything else on the screen.
        elapsedText.visibility = if (isLive) View.VISIBLE else View.INVISIBLE
        disconnectedIcon.visibility = if (isLive) View.INVISIBLE else View.VISIBLE
    }

    private fun updateRecordButtonStyle() {
        val recording = BroadcastService.isRecording
        recordButton.text = getString(R.string.btn_rec_short)
        // Filled red dome while actually recording, quiet outline otherwise --
        // the label stays REC either way, so the fill is what tells you it is
        // running (two STOP buttons side by side would be ambiguous).
        recordButton.background = if (recording) {
            UiTheme.round3dButton(UiTheme.STUDIO_STOP_RED, roundButtonSizePx.toFloat())
        } else {
            UiTheme.roundOutline(UiTheme.STUDIO_TEXT_MUTED)
        }
        recordButton.setTextColor(if (recording) UiTheme.STUDIO_TEXT_PRIMARY else UiTheme.STUDIO_TEXT_SECONDARY)
        recordButton.isEnabled = isLive
        recordButton.alpha = if (isLive) 1f else 0.5f
    }

    /** Central place mapping engine state (+ mute) to the pill badge, subtitle, and latency row. */
    private fun applyStatusStyle(state: BroadcastEngine.State, callMuted: Boolean, manualMuted: Boolean) {
        // Redesign: the chip itself is a fixed neutral bordered background
        // (set once, at construction) -- only the small dot and the label
        // text change color per state now, instead of a solid colored fill.
        val (pillText, pillFg) = when (state) {
            BroadcastEngine.State.CONNECTING -> getString(R.string.status_pill_connecting) to UiTheme.STUDIO_AMBER
            BroadcastEngine.State.LIVE -> getString(R.string.status_pill_on_air) to UiTheme.STUDIO_ON_AIR_GREEN
            BroadcastEngine.State.RECONNECTING -> getString(R.string.status_pill_reconnecting) to UiTheme.STUDIO_AMBER
            BroadcastEngine.State.ERROR -> getString(R.string.status_pill_error) to UiTheme.STUDIO_STOP_RED
            // Red (was muted grey) so the label agrees with the red lamp, same as the website chip.
            BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE -> getString(R.string.status_pill_offline) to UiTheme.STUDIO_STOP_RED
        }
        statusPill.text = pillText
        statusPill.setTextColor(pillFg)
        // Offline (idle/stopped) lights red rather than unlit, matching the
        // website lamp's red/green scheme; amber while (re)connecting.
        appLight.lamp = when (state) {
            BroadcastEngine.State.LIVE -> StatusLightView.Lamp.LIVE
            BroadcastEngine.State.CONNECTING, BroadcastEngine.State.RECONNECTING -> StatusLightView.Lamp.WAITING
            BroadcastEngine.State.ERROR, BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE -> StatusLightView.Lamp.OFFLINE
        }

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
            previewMuted -> UiTheme.STUDIO_STOP_RED
            else -> UiTheme.STUDIO_TEXT_SECONDARY
        }
        micIcon.setColorFilter(micColor)
        latencyIcon.setColorFilter(if (isLiveState) UiTheme.STUDIO_ON_AIR_GREEN else UiTheme.STUDIO_TEXT_MUTED)
        latencyText.setTextColor(if (isLiveState) UiTheme.STUDIO_ON_AIR_GREEN else UiTheme.STUDIO_TEXT_MUTED)
    }

    // ================= Mic preview (Phase 11f) =================

    /**
     * Starts local mic capture so the frequency bars respond before going
     * live. No-op while live (BroadcastService owns the mic then), without
     * mic permission, or if already running.
     */
    private fun startMicPreview() {
        if (isLive || micPreview != null || previewMuted) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        micPreview = MicPreview(AppSettings.sampleRate(this)) { bands ->
            if (!isLive) visualizer.pushSpectrum(bands)
        }.also { it.start() }
    }

    private fun stopMicPreview() {
        micPreview?.stop()
        micPreview = null
        if (!isLive) visualizer.reset()
    }

    // ================= Website live light (Phase 11) =================

    /**
     * One background fetch of AzuraCast's now-playing API, same endpoint
     * and same `live.is_live` rule as waqashq.org's player. Runs on a plain
     * throwaway thread (network is not allowed on the main thread) and
     * skips a tick if the previous fetch is still waiting on a slow network,
     * so requests never pile up.
     *
     * If the fetch fails, what the website shows depends on *why*: when this
     * phone has working internet, the server itself is unreachable, and the
     * website's own fetch would fail too -- it shows Offline, so we do too.
     * When the phone has no internet, we genuinely can't tell, so the lamp
     * goes unlit/unknown rather than falsely claiming the site is offline.
     */
    private fun fetchWebsiteStatus() {
        if (websiteFetchInFlight) return
        websiteFetchInFlight = true
        val apiBase = AppSettings.apiBaseUrl(this)
        Thread({
            val info = ListenerCountFetcher.fetch(
                apiBase,
                BuildConfig.AZURACAST_STATION_SHORTCODE.takeIf { it.isNotBlank() }
            )
            val lamp = when {
                info != null -> if (info.isLive) StatusLightView.Lamp.LIVE else StatusLightView.Lamp.OFFLINE
                hasWorkingInternet() -> StatusLightView.Lamp.OFFLINE
                else -> StatusLightView.Lamp.UNKNOWN
            }
            websiteFetchInFlight = false
            uiHandler.post {
                if (!isFinishing && !isDestroyed) applyWebsiteStatus(lamp)
            }
        }, "MainActivity-websiteStatus").start()
    }

    private fun applyWebsiteStatus(lamp: StatusLightView.Lamp) {
        websiteLight.lamp = lamp
        val (label, color) = when (lamp) {
            StatusLightView.Lamp.LIVE -> getString(R.string.website_status_live) to UiTheme.STUDIO_ON_AIR_GREEN
            StatusLightView.Lamp.OFFLINE -> getString(R.string.website_status_offline) to UiTheme.STUDIO_STOP_RED
            // WAITING is never produced for the website -- it's live or it isn't.
            StatusLightView.Lamp.WAITING, StatusLightView.Lamp.UNKNOWN -> getString(R.string.website_status_unknown) to UiTheme.STUDIO_TEXT_MUTED
        }
        websiteStatusText.text = label
        websiteStatusText.setTextColor(color)
    }

    private fun indicatorText() = TextView(this).apply {
        // 12sp, single line: two chips share one row now, and the longest
        // label (RECONNECTING) has to fit half a narrow phone's card width.
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /**
     * One indicator chip: [3D lamp][icon][state text] in the neutral bordered
     * pill. The icon replaces a text label ("WEBSITE:") -- its meaning is
     * still exposed to screen readers via [contentDescRes] on the chip.
     */
    private fun indicatorChip(light: StatusLightView, iconRes: Int, contentDescRes: Int, text: TextView): LinearLayout {
        val density = resources.displayMetrics.density
        light.layoutParams = LinearLayout.LayoutParams((26 * density).toInt(), (26 * density).toInt())
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(UiTheme.STUDIO_TEXT_SECONDARY)
            val size = (15 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = (1 * density).toInt()
                marginEnd = (6 * density).toInt()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = UiTheme.studioPillBadge()
            // Small start padding: the lamp's glow is a transparent halo
            // around the bulb, which already acts as padding. Relative
            // (start/end) so it mirrors correctly in Urdu.
            setPaddingRelative(6, 4, 14, 4)
            contentDescription = getString(contentDescRes)
            addView(light)
            addView(icon)
            addView(text)
        }
    }

    /** True if the active network has been validated by Android as actually reaching the internet. */
    private fun hasWorkingInternet(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
        startMicPreview()
        maybeAutoGoLive()
    }

    /**
     * Phase 9: fires the "Go Live" shortcut's intent once every first-run
     * gate (mic/notification permission, battery-exemption prompt) has
     * already cleared -- reuses onGoLiveClicked() itself so the battery-low
     * confirmation and every other normal safety check still applies.
     * Consume-once: won't re-fire on a later permission-callback re-entry
     * or if you're already live.
     */
    private fun maybeAutoGoLive() {
        if (!pendingAutoGoLive) return
        pendingAutoGoLive = false
        if (isLive) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        onGoLiveClicked()
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
            // Phase 9: a majlis can run 1-2+ hours -- catch a low, unplugged
            // battery before going live rather than mid-session. Purely a
            // heads-up; the user can still choose to continue.
            if (isBatteryLow()) {
                // Disable Go Live while this is up so a rapid double-tap
                // (or a tap while the dialog is still animating in) can't
                // stack a second copy of the same dialog on top -- the
                // dismiss listener re-enables it however the dialog closes
                // (either button, back press, or tap-outside-to-cancel).
                goLiveButton.isEnabled = false
                val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
                    .setTitle(getString(R.string.battery_low_title))
                    .setMessage(getString(R.string.battery_low_message, currentBatteryPercent()))
                    .setPositiveButton(getString(R.string.battery_low_continue)) { _, _ -> startBroadcastNow() }
                    .setNegativeButton(getString(R.string.battery_low_cancel), null)
                    .create()
                dialog.setOnDismissListener { goLiveButton.isEnabled = true }
                dialog.show()
                return
            }
            startBroadcastNow()
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
            visualizer.reset()
            uiHandler.removeCallbacks(livePoller)
            previewMuted = false
            startMicPreview()
            awaitStateAndShowDialog(
                setOf(BroadcastEngine.State.STOPPED, BroadcastEngine.State.IDLE),
                getString(R.string.dialog_broadcast_ended)
            )
        }
    }

    private fun startBroadcastNow() {
        DebugLog.log("Go Live tapped")
        // Release the mic first: only one capture can own it, so the preview
        // has to be gone before BroadcastService opens its own AudioRecord.
        stopMicPreview()
        // Snapshot now, not just at buildUi() time -- this is the
        // exact value BroadcastService/BroadcastEngine will read a
        // moment from now, so the meter reflects the actual running
        // session even if Settings was changed since the app opened.
        bitrateText.text = currentBitrateLabel()
        // Carried, not applied now: the service has no engine to mute until it
        // reaches LIVE, so pollLiveState() sends it once that happens.
        pendingMuteOnLive = previewMuted
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
    }

    // ================= Battery / data (Phase 9) =================

    private fun isBatteryLow(): Boolean {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return false
        return try {
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            level in 1..20 && !bm.isCharging
        } catch (_: Throwable) {
            false
        }
    }

    private fun currentBatteryPercent(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return 100
        return try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Throwable) {
            100
        }
    }

    private fun isOnMobileData(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
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

    /**
     * Phase 8c one-time sweep: moves any recordings still sitting in the old
     * app-private folder into the new public Music/Malfoozat e Akhtar folder.
     * Runs off the main thread (file I/O); shows a toast only if it actually
     * found something to move, so this stays invisible on every normal launch.
     */
    private fun migrateOldRecordingsIfAny() {
        Thread {
            val result = RecordingStorage.migrateOldRecordings(applicationContext)
            if (result.moved > 0) {
                uiHandler.post {
                    Toast.makeText(
                        this,
                        getString(R.string.recordings_migrated_toast, result.moved),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
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
        if (!isLive) {
            previewMuted = !previewMuted
            if (previewMuted) stopMicPreview() else startMicPreview()
            applyStatusStyle(BroadcastService.state, callMuted = false, manualMuted = false)
            return
        }
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
        if (pendingMuteOnLive && state == BroadcastEngine.State.LIVE) {
            pendingMuteOnLive = false
            BroadcastService.setMicMuted(this, true)
        }
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
                    visualizer.reset()
                    uiHandler.removeCallbacks(livePoller)
                    // Fresh session: the mic starts unmuted again.
                    previewMuted = false
                    startMicPreview()
                }
            }
            else -> {}
        }

        if (BroadcastService.sessionStartRealtime > 0) {
            val elapsedSec = (SystemClock.elapsedRealtime() - BroadcastService.sessionStartRealtime) / 1000
            elapsedText.text = formatElapsed(elapsedSec)
        }

        visualizer.pushSpectrum(BroadcastService.micSpectrum)
        micClippingText.visibility = if (BroadcastService.micClipping) View.VISIBLE else View.INVISIBLE

        val latencyEstimateMs = (BroadcastService.queueDepth * 23) + 200
        latencyText.text = if (state == BroadcastEngine.State.LIVE) {
            getString(R.string.latency_format, latencyEstimateMs)
        } else {
            getString(R.string.latency_unavailable)
        }

        val count = BroadcastService.listenerCount
        val mb = BroadcastService.bytesUploadedTotal / 1024.0 / 1024.0
        val connLabel = getString(if (isOnMobileData()) R.string.data_conn_mobile else R.string.data_conn_wifi)
        listenerCountText.text = if (isLive) {
            getString(R.string.listener_data_line, count ?: 0, mb, connLabel)
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
        stopMicPreview()
        // The broadcast service is NOT stopped here -- it keeps running in
        // the background. Only the explicit Stop control ends it.
    }
}
