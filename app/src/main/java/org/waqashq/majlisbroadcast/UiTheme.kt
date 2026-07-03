package org.waqashq.majlisbroadcast

import android.graphics.drawable.GradientDrawable

/**
 * Phase 7+ shared palette/drawable helpers so the app's screens read as one
 * design instead of default-Material pieces. The Broadcast screen
 * deliberately always renders dark (a "studio" look, independent of system
 * light/dark mode) per the redesign brief; Settings stays on the system
 * DayNight theme. This app's UI is built programmatically in Kotlin rather
 * than XML layouts (section 8 -- deliberately bare), so styling lives here
 * too rather than in a styles.xml theme overhaul.
 */
object UiTheme {
    const val PRIMARY_GREEN = 0xFF0F5132.toInt()
    const val ACCENT_GOLD = 0xFFE8C468.toInt()
    const val STOP_RED = 0xFFB3261E.toInt()
    const val CARD_TINT = 0x14000000 // ~8% black overlay -- used on the (light/dark-following) Settings screen

    // Dark "studio" palette for the Broadcast screen specifically.
    const val STUDIO_BG = 0xFF0B0D0E.toInt()
    const val STUDIO_CARD_BG = 0xFF15181A.toInt()
    const val STUDIO_BORDER_TEAL = 0xFF2FBFA8.toInt()
    const val STUDIO_TEXT_PRIMARY = 0xFFF2F2F2.toInt()
    const val STUDIO_TEXT_MUTED = 0xFF9AA0A6.toInt()
    const val STUDIO_ON_AIR_GREEN = 0xFF34C759.toInt()
    const val STUDIO_ON_AIR_BG = 0xFF123321.toInt()
    const val STUDIO_AMBER = 0xFFE8A33D.toInt()
    const val STUDIO_STOP_RED = 0xFFE0453A.toInt()

    fun pillButtonBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 56f
        setColor(color)
    }

    fun outlinePillBackground(strokeColor: Int, strokeWidthPx: Int = 3): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 56f
        setColor(0x00000000)
        setStroke(strokeWidthPx, strokeColor)
    }

    fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(CARD_TINT)
    }

    fun studioCard(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 48f
        setColor(STUDIO_CARD_BG)
        setStroke(3, STUDIO_BORDER_TEAL)
    }

    fun studioPillBadge(bgColor: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 60f
        setColor(bgColor)
    }

    fun studioMicCircle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
