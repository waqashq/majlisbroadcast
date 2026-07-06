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

        val root = FrameLayout(this).apply {
            setBackgroundColor(UiTheme.STUDIO_BG)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val logoSize = (140 * resources.displayMetrics.density).toInt()
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(logoSize, logoSize)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        content.addView(logo)

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (24 * resources.displayMetrics.density).toInt() }
        }
        content.addView(title)

        root.addView(
            content,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
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
