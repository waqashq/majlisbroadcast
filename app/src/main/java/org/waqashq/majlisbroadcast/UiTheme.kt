package org.waqashq.majlisbroadcast

import android.graphics.drawable.GradientDrawable

/**
 * Phase 7 cosmetic pass: a small shared palette so MainActivity and
 * SettingsActivity read as one app instead of two default-Material screens.
 * Colors loosely echo the launcher icon (deep green / gold). This app's UI
 * is built programmatically in Kotlin rather than XML layouts (see section
 * 8 -- deliberately bare), so styling lives here too rather than in a
 * styles.xml theme overhaul.
 */
object UiTheme {
    const val PRIMARY_GREEN = 0xFF0F5132.toInt()
    const val PRIMARY_GREEN_DARK = 0xFF0A3B24.toInt()
    const val ACCENT_GOLD = 0xFFE8C468.toInt()
    const val STOP_RED = 0xFFB3261E.toInt()
    const val CARD_TINT = 0x14000000 // ~8% black overlay -- stays legible in both light and dark system theme

    fun pillButtonBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 56f
        setColor(color)
    }

    fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(CARD_TINT)
    }
}
