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
 * Phase 7: settings/configuration screen, split out from the main Go-Live
 * screen so that one stays bare per section 8. Houses the view-only server
 * panel, the debug log viewer (both moved here from MainActivity), and the
 * in-app language toggle (English / Urdu / system default) via AndroidX's
 * per-app language API -- works down to minSdk 26, and on API 33+ also
 * shows up in system Settings > App info > Language via the manifest's
 * localeConfig (res/xml/locales_config.xml).
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
        title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 56, 56, 56)
        }

        // ---- Language ----
        val languageTitle = sectionTitle(getString(R.string.language_section_title))
        val languageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnSystem = Button(this).apply { text = getString(R.string.language_system); textSize = 12f }
        val btnEnglish = Button(this).apply { text = getString(R.string.language_english); textSize = 12f }
        val btnUrdu = Button(this).apply { text = getString(R.string.language_urdu); textSize = 12f }
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

        // ---- Server (view-only, moved from the main screen) ----
        val serverTitle = sectionTitle(getString(R.string.server_panel_title))
        val serverText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            text = if (BuildConfig.AZURACAST_HOST.isNotBlank()) {
                getString(R.string.server_panel_host_port, BuildConfig.AZURACAST_HOST, BuildConfig.AZURACAST_PORT)
            } else {
                getString(R.string.server_panel_not_configured)
            }
        }

        // ---- Diagnostics (moved from the main screen in the Phase 7+ redesign) ----
        val diagnosticsTitle = sectionTitle(getString(R.string.diagnostics_title))
        diagnosticsText = TextView(this).apply {
            textSize = 12f
            alpha = 0.8f
            gravity = Gravity.CENTER
        }

        // ---- Battery ----
        batteryStateLabel = TextView(this).apply {
            textSize = 12f
            alpha = 0.8f
            gravity = Gravity.CENTER
        }

        // ---- Debug log (moved from the main screen) ----
        val debugTitle = sectionTitle(getString(R.string.log_title))
        val viewLogButton = Button(this).apply {
            text = getString(R.string.btn_view_log)
            textSize = 13f
        }
        viewLogButton.setOnClickListener { showDebugLogDialog() }

        val versionLabel = TextView(this).apply {
            textSize = 11f
            alpha = 0.6f
            gravity = Gravity.CENTER
            text = getString(R.string.build_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        }

        listOf(
            languageTitle, languageRow,
            serverTitle, serverText,
            diagnosticsTitle, diagnosticsText, batteryStateLabel,
            debugTitle, viewLogButton,
            versionLabel
        ).forEach {
            root.addView(
                it,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 28
                }
            )
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(UiTheme.PRIMARY_GREEN)
        gravity = Gravity.CENTER
    }

    private fun highlightActiveLanguage(system: Button, english: Button, urdu: Button) {
        val current = AppCompatDelegate.getApplicationLocales()
        val activeTag = if (current.isEmpty) null else current[0]?.language
        listOf(system to null, english to "en", urdu to "ur").forEach { (btn, tag) ->
            val active = activeTag == tag
            btn.setTypeface(btn.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            btn.alpha = if (active) 1f else 0.6f
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

    // ================= Debug log (moved from MainActivity, section 9) =================

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
}
