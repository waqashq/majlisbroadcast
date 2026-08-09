package org.waqashq.majlisbroadcast

import android.content.Intent
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Phase 8b: the app's new true launcher (see AndroidManifest.xml), a purely
 * local app-lock gate -- not tied to AzuraCast auth at all -- so an
 * accidental tap can't start a live broadcast. Required on every cold
 * start (per the user's choice), not on every foreground resume.
 *
 * Two modes, decided by whether AppSettings.isLoginConfigured() is true
 * yet:
 *  - First run (nothing set up): "Set Up App Lock" -- create a username
 *    + password (with confirmation), saved via AppSettings, then proceeds
 *    straight through.
 *  - Every run after that: "Login" -- must match the saved credentials
 *    before SplashActivity (and from there, MainActivity) is reachable.
 *
 * The credentials themselves can be changed later from Settings (Phase 8b
 * also adds an App Lock card there) once already past this gate.
 *
 * Recovery for a forgotten username/password: tapping the logo 7 times
 * (mirrors Android's own "tap build number 7 times" pattern) bypasses the
 * gate the same as a correct login, landing in MainActivity so the user
 * can open Settings > App Lock and set new credentials. Deliberately not
 * hidden/undocumented in spirit -- the gate's actual job is stopping an
 * *accidental* tap from going live, not defending against someone who
 * deliberately taps 7 times, navigates to Settings, and re-logs in, which
 * is exactly the effort a legitimate forgetful owner would make anyway.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private var confirmField: EditText? = null
    private lateinit var errorText: TextView

    private val isCreateMode: Boolean by lazy { !AppSettings.isLoginConfigured(this) }

    private var logoTapCount = 0
    private var lastLogoTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.STUDIO_BG)
        }

        // Centered both ways: content sits inside a fillViewport ScrollView
        // so it's vertically centered on screens with room to spare, but
        // still scrolls normally once the keyboard is up and space is
        // tight (important in Create mode with 3 fields).
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(56, 56, 56, 56)
        }

        val logoSize = (152 * resources.displayMetrics.density).toInt()
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize).apply {
                bottomMargin = (28 * resources.displayMetrics.density).toInt()
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            isClickable = true
            setOnClickListener { onLogoTapped() }
        }
        content.addView(logo)

        val title = TextView(this).apply {
            text = getString(if (isCreateMode) R.string.login_create_title else R.string.login_title)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            gravity = Gravity.CENTER
        }
        content.addView(title, centeredParams(0))

        val subtitle = TextView(this).apply {
            text = getString(if (isCreateMode) R.string.login_create_subtitle else R.string.login_subtitle)
            textSize = 13f
            setTextColor(UiTheme.STUDIO_TEXT_MUTED)
            gravity = Gravity.CENTER
        }
        content.addView(subtitle, centeredParams(12))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiTheme.studioCard()
            setPadding(40, 40, 40, 40)
        }

        usernameField = input(getString(R.string.login_username_hint))
        card.addView(usernameField, fullWidthParams(0))

        passwordField = input(getString(R.string.login_password_hint), isPassword = true)
        card.addView(passwordField, fullWidthParams(28))

        if (isCreateMode) {
            confirmField = input(getString(R.string.login_confirm_password_hint), isPassword = true)
            card.addView(confirmField, fullWidthParams(20))
        }

        errorText = TextView(this).apply {
            textSize = 12f
            setTextColor(UiTheme.STUDIO_STOP_RED)
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
        }
        card.addView(errorText, fullWidthParams(16))

        val actionButton = Button(this).apply {
            text = getString(if (isCreateMode) R.string.btn_create_login else R.string.btn_login)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            isAllCaps = false
            background = UiTheme.pillButtonBackground(UiTheme.PRIMARY_GREEN)
            setPadding(0, 30, 0, 30)
        }
        actionButton.setOnClickListener { if (isCreateMode) attemptCreate() else attemptLogin() }
        card.addView(actionButton, fullWidthParams(36))

        content.addView(card, fullWidthParams(32))

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(content, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun input(hintText: String, isPassword: Boolean = false): EditText = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
        setHintTextColor(UiTheme.STUDIO_TEXT_MUTED)
        background = UiTheme.outlinePillBackground(UiTheme.STUDIO_TEXT_MUTED, strokeWidthPx = 2)
        setPadding(28, 22, 28, 22)
        setSingleLine(true)
        // Password EditTexts have a known Android quirk of ignoring the
        // surrounding layout's RTL direction and defaulting to LTR text
        // entry/alignment regardless of locale -- explicit here so Urdu
        // renders right-aligned like every other field.
        textDirection = View.TEXT_DIRECTION_LOCALE
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (isPassword) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    /** Full card width, explicit -- an EditText's WRAP_CONTENT width isn't
     * reliable across input types (a password field's masked-dot metrics
     * can measure narrower than a plain text field's), so every field and
     * the button below it get the same explicit MATCH_PARENT width. */
    private fun fullWidthParams(marginTopPx: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = marginTopPx }

    private fun centeredParams(marginTopPx: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = marginTopPx }

    private fun attemptCreate() {
        val username = usernameField.text.toString().trim()
        val password = passwordField.text.toString()
        val confirm = confirmField?.text?.toString().orEmpty()
        if (username.isBlank() || password.isBlank()) {
            showError(getString(R.string.login_error_incomplete))
            return
        }
        if (password != confirm) {
            showError(getString(R.string.login_error_mismatch))
            return
        }
        AppSettings.saveLogin(this, username, password)
        proceed()
    }

    private fun attemptLogin() {
        val username = usernameField.text.toString()
        val password = passwordField.text.toString()
        if (AppSettings.checkLogin(this, username, password)) {
            proceed()
        } else {
            showError(getString(R.string.login_error_wrong))
            passwordField.text.clear()
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun proceed() {
        val target = Intent(this, SplashActivity::class.java)
        // Phase 9: forward the home-screen "Go Live" shortcut's intent
        // through Login -> Splash -> MainActivity, which auto-triggers Go
        // Live once it lands (still gated by the same login/permission
        // checks everyone else goes through -- this only saves taps once
        // you're already through them).
        if (intent.getBooleanExtra(MainActivity.EXTRA_AUTO_GO_LIVE, false)) {
            target.putExtra(MainActivity.EXTRA_AUTO_GO_LIVE, true)
        }
        startActivity(target)
        finish()
    }

    /** Forgot-username/password recovery: see class doc. */
    private fun onLogoTapped() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogoTapAt > LOGO_TAP_RESET_GAP_MS) {
            logoTapCount = 0
        }
        lastLogoTapAt = now
        logoTapCount++
        if (logoTapCount >= LOGO_TAPS_TO_RECOVER) {
            logoTapCount = 0
            Toast.makeText(this, getString(R.string.login_recovery_unlocked), Toast.LENGTH_SHORT).show()
            proceed()
        }
    }

    companion object {
        private const val LOGO_TAPS_TO_RECOVER = 7
        private const val LOGO_TAP_RESET_GAP_MS = 1_500L
    }
}
