package org.waqashq.majlisbroadcast

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A brief, self-dismissing "success" modal: dark scrim, a studio-card
 * badge with an animated green checkmark, and a message -- shown for
 * ~1 second then dismissed automatically, optionally followed by a
 * caller-supplied action (e.g. navigating back to the Broadcast screen).
 *
 * There's no actual GIF asset in this project (and bundling one isn't a
 * good fit for this codebase's hand-coded-vector-drawable approach), so
 * "green tick" is a custom vector checkmark (ic_check_circle) with a
 * scale + fade "pop in" animation instead -- same visual idea, no binary
 * asset to source or maintain.
 */
object SuccessOverlay {

    fun show(activity: Activity, message: String, durationMs: Long = 1100L, onDismissed: () -> Unit = {}) {
        if (activity.isFinishing) {
            onDismissed()
            return
        }

        val dialog = Dialog(activity, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.setCancelable(false)

        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(0xB0000000.toInt())
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = UiTheme.studioCard()
            setPadding(56, 48, 56, 48)
        }

        val checkSize = (72 * activity.resources.displayMetrics.density).toInt()
        val checkIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_check_circle)
            layoutParams = LinearLayout.LayoutParams(checkSize, checkSize)
            scaleX = 0.4f
            scaleY = 0.4f
            alpha = 0f
        }
        card.addView(checkIcon)

        val messageText = TextView(activity).apply {
            text = message
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
            gravity = Gravity.CENTER
        }
        card.addView(
            messageText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (20 * activity.resources.displayMetrics.density).toInt()
            }
        )

        scrim.addView(
            card,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        dialog.setContentView(scrim)
        dialog.show()

        checkIcon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator())
            .start()

        Handler(Looper.getMainLooper()).postDelayed({
            if (!activity.isFinishing && dialog.isShowing) {
                dialog.dismiss()
            }
            onDismissed()
        }, durationMs)
    }
}
