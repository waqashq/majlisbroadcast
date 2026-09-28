package org.waqashq.majlisbroadcast

import android.content.Intent
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
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
 * Layout: logo and app name centered on the plain dark background --
 * redesign dropped the old full-width colored header bar in favor of the
 * same flat, no-chrome look used everywhere else in the app now.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val goToMain = Runnable {
        if (!isFinishing) {
            val target = Intent(this, MainActivity::class.java)
            if (intent.getBooleanExtra(MainActivity.EXTRA_AUTO_GO_LIVE, false)) {
                target.putExtra(MainActivity.EXTRA_AUTO_GO_LIVE, true)
            }
            startActivity(target)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Phase 11p: the satin photo sits behind this (added below), so
            // the layout itself no longer paints the flat dark background.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

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
        root.addView(logo, LinearLayout.LayoutParams(logoSize, logoSize))

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            gravity = Gravity.CENTER
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (20 * resources.displayMetrics.density).toInt()
            }
        )

        // Phase 11p: same backdrop as the login screen it follows -- photo,
        // dark scrim for text contrast, then the existing content on top.
        val backdrop = ImageView(this).apply {
            setImageResource(R.drawable.login_bg)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val scrim = View(this).apply {
            setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(UiTheme.STUDIO_BG, 0xA6))
        }
        val layers = android.widget.FrameLayout(this)
        val fill = {
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        layers.addView(backdrop, fill())
        layers.addView(scrim, fill())
        layers.addView(root, fill())
        setContentView(layers)

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
