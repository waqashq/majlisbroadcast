package org.waqashq.majlisbroadcast

import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Launch entry point (replacing MainActivity per the user's request to reuse
 * the app's badge artwork "at the time of loading"). Purely cosmetic --
 * shows the same launcher badge on the studio-dark background for a short,
 * fixed delay, then hands off to MainActivity and finishes itself so back
 * from MainActivity exits the app rather than returning here.
 *
 * android:theme="@style/Theme.Splash" (set in the manifest) paints the
 * window background before this layout inflates, so there's no white/system
 * flash ahead of the dark background.
 *
 * Layout: a full-width green header bar (app name, same brand green as the
 * logo and MainActivity's own header) pinned at the top, with the logo
 * centered in the remaining space below -- replaces an earlier floating
 * "pill badge" title that didn't read well on its own.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val goToMain = Runnable {
        if (!isFinishing) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.STUDIO_BG)
        }

        val header = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(UiTheme.PRIMARY_GREEN)
            setPadding(24, 32, 24, 24)
        }
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val logoContainer = FrameLayout(this)

        val logoSize = (140 * resources.displayMetrics.density).toInt()
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        logoContainer.addView(
            logo,
            FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER)
        )

        root.addView(logoContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        handler.postDelayed(goToMain, SPLASH_DELAY_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goToMain)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_DELAY_MS = 1100L
    }
}
