package org.waqashq.majlisbroadcast

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Shared builder functions for the "studio" dark-themed sub-screens
 * (Settings, Recordings, History -- everything besides the Broadcast/
 * Login/Splash screens, which have their own bespoke green-header layout).
 *
 * Refactor only -- no behavior change. These were previously three
 * near-identical private copies (one per Activity) that had already
 * started drifting slightly out of sync with each other (different card
 * paddings, a missing centered-gravity on two of the three cardBody()s,
 * different pill-button text sizes). Every call site below still passes
 * its own pre-existing value for anything that differed, so extracting
 * these doesn't change how any current screen looks -- it just gives
 * future screens one place to reuse instead of a fourth copy-paste.
 */

/** A back-arrow + title row, used as the fixed (non-scrolling) header on every "studio" sub-screen. */
fun Context.studioHeader(title: String, onBack: () -> Unit): LinearLayout {
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(32, 56, 32, 24)
    }
    val backArrow = TextView(this).apply {
        text = "‹"
        textSize = 28f
        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
        setPadding(16, 8, 32, 8)
        isClickable = true
        setOnClickListener { onBack() }
    }
    val headerTitle = TextView(this).apply {
        text = title
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
    }
    header.addView(backArrow)
    header.addView(headerTitle)
    return header
}

/** The dark bordered card background used to group each section. [paddingVertical] varies by screen (kept explicit per call site). */
fun Context.studioCard(paddingVertical: Int): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = UiTheme.studioCard()
    setPadding(36, paddingVertical, 36, paddingVertical)
}

/** A card's bold teal section title. */
fun Context.studioCardTitle(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 14f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(UiTheme.STUDIO_BORDER_TEAL)
    gravity = Gravity.CENTER
}

/** Muted body text inside a card. [centered] varies by screen (kept explicit per call site). */
fun Context.studioCardBody(centered: Boolean = false): TextView = TextView(this).apply {
    textSize = 13f
    setTextColor(UiTheme.STUDIO_TEXT_MUTED)
    if (centered) gravity = Gravity.CENTER
}

/** An outline pill button. [textSizeSp] varies by screen (kept explicit per call site). */
fun Context.studioPillButton(text: String, textSizeSp: Float): Button = Button(this).apply {
    this.text = text
    textSize = textSizeSp
    isAllCaps = false
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(UiTheme.STUDIO_TEXT_PRIMARY)
    background = UiTheme.outlinePillBackground(UiTheme.STUDIO_BORDER_TEAL)
    setPadding(0, 24, 0, 24)
}

/** MATCH_PARENT/WRAP_CONTENT LayoutParams with just a top margin -- the layout row spacing used everywhere in these screens. */
fun topMarginParams(marginPx: Int): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = marginPx
    }

/** "H:MM:SS" once past an hour, else "MM:SS" -- used by Recordings and History for row durations. */
fun formatDuration(totalMs: Long): String {
    val totalSeconds = totalMs / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/** One-decimal megabytes, e.g. "12.3" -- used by Recordings and History for data-size rows. */
fun formatMegabytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return String.format(Locale.US, "%.1f", mb)
}
