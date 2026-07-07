package org.waqashq.majlisbroadcast

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat

/**
 * Phase 7+: settings/configuration screen, split out from the main Go-Live
 * screen so that one stays bare per section 8. Houses the editable server
 * settings panel (Phase 8 -- host/port/mount/username/password/sample
 * rate/bit rate, encrypted on-device via AppSettings), diagnostics + battery state, the debug log viewer, and the in-app
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

    // Mirrors BroadcastEngine's queue-capacity constant -- purely for display.
    private val queueCapacityDisplay = 150

    private lateinit var diagnosticsText: TextView
    private lateinit var batteryStateLabel: TextView

    // Phase 8: server settings form fields.
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var mountField: EditText
    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var sampleRateRow: LinearLayout
    private lateinit var bitRateRow: LinearLayout
    private var selectedSampleRate = AppSettings.DEFAULT_SAMPLE_RATE
    private var selectedBitRateBps = AppSettings.DEFAULT_BIT_RATE_BPS

    // Phase 8b: app-lock (login gate) credentials, changeable from here.
    private lateinit var appLockUsernameField: EditText
    private lateinit var appLockPasswordField: EditText

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
            AppSettings.bitRateBps(this) / 1000,
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

        // ---- Server card (Phase 8: editable, saved on-device) ----
        val serverCard = card()
        serverCard.addView(cardTitle(getString(R.string.server_panel_title)), topMarginParams(0))

        hostField = fieldInput(getString(R.string.server_field_host_label)).apply {
            setText(AppSettings.host(this@SettingsActivity))
        }
        serverCard.addView(hostField, topMarginParams(20))

        portField = fieldInput(getString(R.string.server_field_port_label)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            val port = AppSettings.port(this@SettingsActivity)
            if (port != 0) setText(port.toString())
        }
        serverCard.addView(portField, topMarginParams(16))

        mountField = fieldInput(getString(R.string.server_field_mount_label)).apply {
            setText(AppSettings.mount(this@SettingsActivity))
        }
        serverCard.addView(mountField, topMarginParams(16))

        usernameField = fieldInput(getString(R.string.server_field_username_label)).apply {
            setText(AppSettings.username(this@SettingsActivity))
        }
        serverCard.addView(usernameField, topMarginParams(16))

        passwordField = fieldInput(getString(R.string.server_field_password_label)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(AppSettings.password(this@SettingsActivity))
        }
        serverCard.addView(passwordField, topMarginParams(16))

        selectedSampleRate = AppSettings.sampleRate(this)
        sampleRateRow = dropdownRow(
            getString(R.string.server_sample_rate_label),
            sampleRateLabel(selectedSampleRate)
        ) { showSampleRatePicker() }
        serverCard.addView(sampleRateRow, topMarginParams(20))

        selectedBitRateBps = AppSettings.bitRateBps(this)
        bitRateRow = dropdownRow(
            getString(R.string.server_bit_rate_label),
            bitRateLabel(selectedBitRateBps)
        ) { showBitRatePicker() }
        serverCard.addView(bitRateRow, topMarginParams(16))

        val saveServerButton = pillButton(getString(R.string.btn_save_server))
        saveServerButton.setOnClickListener { saveServerSettings() }
        serverCard.addView(
            saveServerButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 }
        )

        val liveEditNotice = cardBody().apply {
            text = getString(R.string.server_live_edit_notice)
            textSize = 11f
        }
        serverCard.addView(liveEditNotice, topMarginParams(12))

        // ---- App Lock card (Phase 8b) ----
        val appLockCard = card()
        appLockCard.addView(cardTitle(getString(R.string.app_lock_section_title)), topMarginParams(0))
        val appLockSubtitle = cardBody().apply {
            text = getString(R.string.app_lock_section_subtitle)
            textSize = 11f
        }
        appLockCard.addView(appLockSubtitle, topMarginParams(8))

        appLockUsernameField = fieldInput(getString(R.string.login_username_hint)).apply {
            setText(AppSettings.loginUsername(this@SettingsActivity))
        }
        appLockCard.addView(appLockUsernameField, topMarginParams(20))

        appLockPasswordField = fieldInput(getString(R.string.login_password_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(AppSettings.loginPassword(this@SettingsActivity))
        }
        appLockCard.addView(appLockPasswordField, topMarginParams(16))

        val saveAppLockButton = pillButton(getString(R.string.btn_save_app_lock))
        saveAppLockButton.setOnClickListener { saveAppLock() }
        appLockCard.addView(
            saveAppLockButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 }
        )

        val appLockResetNotice = cardBody().apply {
            text = getString(R.string.app_lock_reset_notice)
            textSize = 11f
        }
        appLockCard.addView(appLockResetNotice, topMarginParams(12))

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

        listOf(languageCard, serverCard, appLockCard, diagCard, debugCard).forEach {
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

    private fun fieldInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 14f
        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
        setHintTextColor(UiTheme.STUDIO_TEXT_MUTED)
        background = UiTheme.outlinePillBackground(UiTheme.STUDIO_TEXT_MUTED, strokeWidthPx = 2)
        setPadding(28, 20, 28, 20)
        setSingleLine(true)
    }

    /** A tappable "current value ›" row (Sample Rate / Bit Rate) that opens a picker dialog. */
    private fun dropdownRow(label: String, initialValue: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiTheme.outlinePillBackground(UiTheme.STUDIO_TEXT_MUTED, strokeWidthPx = 2)
            setPadding(28, 24, 28, 24)
            isClickable = true
            isFocusable = true
        }
        val labelText = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(UiTheme.STUDIO_TEXT_MUTED)
        }
        val valueText = TextView(this).apply {
            text = "$initialValue  ›"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            gravity = Gravity.END
        }
        row.addView(labelText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.setOnClickListener { onClick() }
        row.tag = valueText
        return row
    }

    private fun sampleRateLabel(rate: Int): String {
        val khz = rate / 1000.0
        val formatted = if (khz == khz.toLong().toDouble()) khz.toLong().toString() else khz.toString()
        return "$formatted kHz"
    }

    private fun bitRateLabel(bitRateBps: Int): String = "${bitRateBps / 1000} kbps"

    private fun showSampleRatePicker() {
        val options = AppSettings.SAMPLE_RATE_OPTIONS
        val labels = options.map { sampleRateLabel(it) }.toTypedArray()
        val checkedIndex = options.indexOf(selectedSampleRate).coerceAtLeast(0)
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle(getString(R.string.server_sample_rate_label))
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                selectedSampleRate = options[which]
                (sampleRateRow.tag as TextView).text = "${labels[which]}  ›"
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_close_log), null)
            .show()
    }

    private fun showBitRatePicker() {
        val options = AppSettings.BIT_RATE_OPTIONS_BPS
        val labels = options.map { bitRateLabel(it) }.toTypedArray()
        val checkedIndex = options.indexOf(selectedBitRateBps).coerceAtLeast(0)
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle(getString(R.string.server_bit_rate_label))
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                selectedBitRateBps = options[which]
                (bitRateRow.tag as TextView).text = "${labels[which]}  ›"
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_close_log), null)
            .show()
    }

    private fun saveServerSettings() {
        val host = hostField.text.toString().trim()
        val port = portField.text.toString().trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            Toast.makeText(this, getString(R.string.server_validation_error), Toast.LENGTH_LONG).show()
            return
        }
        AppSettings.save(
            this,
            host = host,
            port = port,
            mount = mountField.text.toString(),
            username = usernameField.text.toString(),
            password = passwordField.text.toString(),
            sampleRate = selectedSampleRate,
            bitRateBps = selectedBitRateBps
        )
        SuccessOverlay.show(this, getString(R.string.dialog_settings_saved)) {
            // Per request: redirect back to the main Broadcast screen after
            // the confirmation, rather than staying on Settings.
            finish()
        }
    }

    private fun saveAppLock() {
        val username = appLockUsernameField.text.toString().trim()
        val password = appLockPasswordField.text.toString()
        if (username.isBlank() || password.isBlank()) {
            Toast.makeText(this, getString(R.string.login_error_incomplete), Toast.LENGTH_LONG).show()
            return
        }
        AppSettings.saveLogin(this, username, password)
        Toast.makeText(this, getString(R.string.app_lock_saved_toast), Toast.LENGTH_SHORT).show()
    }

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

        // Theme_AppCompat_Dialog's default body-text color is a mid-gray
        // meant for a lighter dialog background, which made the log almost
        // unreadable against this dark theme -- set both the background and
        // text color explicitly here instead of relying on the dialog
        // theme's defaults.
        val logView = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            setBackgroundColor(UiTheme.STUDIO_BG)
            setPadding(32, 32, 32, 32)
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            setBackgroundColor(UiTheme.STUDIO_BG)
        }

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
