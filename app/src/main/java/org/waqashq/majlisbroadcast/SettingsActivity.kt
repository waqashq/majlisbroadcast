package org.waqashq.majlisbroadcast

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat

/**
 * Phase 7+: settings/configuration screen, split out from the main Go-Live
 * screen so that one stays bare per section 8. Houses the view-only server
 * panel, diagnostics + battery state, the debug log viewer, and the in-app
 * language toggle (English / Urdu / system default) via AndroidX's per-app
 * language API -- works down to minSdk 26, and on API 33+ also shows up in
 * system Settings > App info > Language via the manifest's localeConfig
 * (res/xml/locales_config.xml).
 *
 * Restyled to match the Broadcast screen's dark "studio" look: card-grouped
 * sections, a custom header instead of the system ActionBar (avoids the
 * DayNight action bar's text color fighting a forced-dark background), and
 * a dark AlertDialog for the debug log.
 */
class SettingsActivity : AppCompatActivity() {

    private val prefsName = "majlis_prefs"
    private val prefLanguageChoice = "language_choice"

    // Mirrors BroadcastEngine's private constants -- purely for display.
    private val queueCapacityDisplay = 150
    private val configuredBitrateKbps = 64

    private lateinit var diagnosticsText: TextView
    private lateinit var batteryStateLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        buildUi()
        refreshDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        // Static snapshot on each visit rather than a continuous poller --
        // this screen isn't meant to be watched live during a broadcast,
        // just checked before/after one.
        refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        val latencyEstimateMs = (BroadcastService.queueDepth * 23) + 200
        diagnosticsText.text = getString(
            R.string.diagnostics_line,
            BroadcastService.reconnectCount,
            BroadcastService.dropCount,
            BroadcastService.burstDropEvents,
            BroadcastService.queueDepth, queueCapacityDisplay,
            configuredBitrateKbps,
            latencyEstimateMs,
            BroadcastService.scoRefusalCount
        )
        val ignoring = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        batteryStateLabel.text = getString(
            if (ignoring) R.string.battery_state_ignoring else R.string.battery_state_not_ignoring
        )
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.STUDIO_BG)
        }

        // ---- Custom header (avoids theming the system ActionBar dark) ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 56, 32, 24)
        }
        val backArrow = TextView(this).apply {
            text = "‹" // ‹
            textSize = 28f
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            setPadding(16, 8, 32, 8)
            isClickable = true
            setOnClickListener { finish() }
        }
        val headerTitle = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
        }
        header.addView(backArrow)
        header.addView(headerTitle)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 48)
        }

        // ---- Language card ----
        val languageCard = card()
        languageCard.addView(cardTitle(getString(R.string.language_section_title)), topMarginParams(0))
        val languageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnSystem = pillToggle(getString(R.string.language_system))
        val btnEnglish = pillToggle(getString(R.string.language_english))
        val btnUrdu = pillToggle(getString(R.string.language_urdu))
        highlightActiveLanguage(btnSystem, btnEnglish, btnUrdu)
        btnSystem.setOnClickListener { setLanguage(null) }
        btnEnglish.setOnClickListener { setLanguage("en") }
        btnUrdu.setOnClickListener { setLanguage("ur") }
        listOf(btnSystem, btnEnglish, btnUrdu).forEach { btn ->
            languageRow.addView(
                btn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 8
                    marginEnd = 8
                }
            )
        }
        languageCard.addView(languageRow, topMarginParams(20))

        // ---- Server card (view-only) ----
        val serverCard = card()
        serverCard.addView(cardTitle(getString(R.string.server_panel_title)), topMarginParams(0))
        val serverText = cardBody().apply {
            text = if (BuildConfig.AZURACAST_HOST.isNotBlank()) {
                getString(R.string.server_panel_host_port, BuildConfig.AZURACAST_HOST, BuildConfig.AZURACAST_PORT)
            } else {
                getString(R.string.server_panel_not_configured)
            }
        }
        serverCard.addView(serverText, topMarginParams(12))

        // ---- Diagnostics + battery card ----
        val diagCard = card()
        diagCard.addView(cardTitle(getString(R.string.diagnostics_title)), topMarginParams(0))
        diagnosticsText = cardBody()
        diagCard.addView(diagnosticsText, topMarginParams(12))
        batteryStateLabel = cardBody()
        diagCard.addView(batteryStateLabel, topMarginParams(16))

        // ---- Debug log card ----
        val debugCard = card()
        debugCard.addView(cardTitle(getString(R.string.log_title)), topMarginParams(0))
        val viewLogButton = pillButton(getString(R.string.btn_view_log))
        viewLogButton.setOnClickListener { showDebugLogDialog() }
        debugCard.addView(
            viewLogButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
        )

        val versionLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(UiTheme.STUDIO_TEXT_MUTED)
            gravity = Gravity.CENTER
            text = getString(R.string.build_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        }

        listOf(languageCard, serverCard, diagCard, debugCard).forEach {
            content.addView(
                it,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 28
                }
            )
        }
        content.addView(
            versionLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 28 }
        )

        val scrollView = ScrollView(this).apply { addView(content) }

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    // ================= Small studio-styled building blocks =================

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = UiTheme.studioCard()
        setPadding(36, 32, 36, 32)
    }

    private fun cardTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(UiTheme.STUDIO_BORDER_TEAL)
        gravity = Gravity.CENTER
    }

    private fun cardBody(): TextView = TextView(this).apply {
        textSize = 13f
        setTextColor(UiTheme.STUDIO_TEXT_MUTED)
        gravity = Gravity.CENTER
    }

    private fun pillToggle(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
        setPadding(0, 18, 0, 18)
    }

    private fun pillButton(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
        background = UiTheme.outlinePillBackground(UiTheme.STUDIO_BORDER_TEAL)
        setPadding(0, 24, 0, 24)
    }

    private fun topMarginParams(margin: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = margin }

    private fun highlightActiveLanguage(system: Button, english: Button, urdu: Button) {
        val current = AppCompatDelegate.getApplicationLocales()
        val activeTag = if (current.isEmpty) null else current[0]?.language
        listOf(system to null, english to "en", urdu to "ur").forEach { (btn, tag) ->
            val active = activeTag == tag
            btn.background = if (active) {
                UiTheme.pillButtonBackground(UiTheme.STUDIO_BORDER_TEAL)
            } else {
                UiTheme.outlinePillBackground(UiTheme.STUDIO_TEXT_MUTED)
            }
        }
    }

    private fun setLanguage(tag: String?) {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit()
            .putString(prefLanguageChoice, tag ?: "")
            .apply()
        val locales = if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        // Triggers an Activity recreate (this one and MainActivity, once
        // it's next resumed) via AppCompat's locale-change machinery.
        AppCompatDelegate.setApplicationLocales(locales)
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

        // A built-in AppCompat *always-dark* dialog style (not DayNight),
        // to match the rest of the app rather than following the system
        // theme.
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
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
}
