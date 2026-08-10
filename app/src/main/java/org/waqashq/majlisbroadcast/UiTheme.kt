package org.waqashq.majlisbroadcast

import android.graphics.drawable.GradientDrawable

/**
 * "Noor" -- the app's shared palette/drawable helpers, redesigned at the
 * user's request for a sleeker, flatter look: one confident emerald accent
 * instead of the old green + teal + amber mix, flat bordered cards instead
 * of a thick colored outline, and a quieter neutral status chip instead of
 * a loud solid-color pill. No gradients or glow anywhere -- the flatness
 * itself is the point.
 *
 * Constant names are kept from the previous "studio" palette even where
 * their values changed, to avoid touching every call site across the app
 * for a pure re-theme -- e.g. STUDIO_BORDER_TEAL is no longer literally
 * teal, it's just PRIMARY_GREEN's value again, reused so every screen that
 * already referenced it (section titles, active nav tab, slider tint)
 * automatically picks up the new single-accent look with zero code change.
 *
 * The Broadcast screen renders dark independent of system light/dark mode
 * (a deliberate "studio" look, unchanged in this redesign); Settings stays
 * on the system DayNight theme. This app's UI is built programmatically in
 * Kotlin rather than XML layouts, so styling lives here too.
 */
object UiTheme {
    // The one accent used everywhere something needs to read as "the app's
    // color": Go Live button, active nav tab, section titles, slider tint,
    // the on-air/share accents. Reused by name below instead of duplicated,
    // so the whole app shares a single hue.
    const val PRIMARY_GREEN = 0xFF3FAE84.toInt()

    const val STUDIO_BG = 0xFF0B0D0E.toInt()
    const val STUDIO_CARD_BG = 0xFF141718.toInt()
    const val STUDIO_CARD_BORDER = 0xFF24282A.toInt()
    const val STUDIO_INSET_BG = 0xFF0F1112.toInt()
    const val STUDIO_DIVIDER = 0xFF2E3436.toInt()
    const val STUDIO_BORDER_TEAL = 0xFF3FAE84.toInt() // = PRIMARY_GREEN, see class doc
    const val STUDIO_TEXT_PRIMARY = 0xFFF2F2F0.toInt()
    const val STUDIO_TEXT_SECONDARY = 0xFFC9CDC9.toInt()
    const val STUDIO_TEXT_MUTED = 0xFF6E7470.toInt()
    const val STUDIO_ON_AIR_GREEN = 0xFF3FAE84.toInt() // = PRIMARY_GREEN, see class doc
    const val STUDIO_ON_AIR_BG = 0xFF141718.toInt()
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

    /** The flat bordered card used everywhere: thin hairline border, no thick colored outline like before. */
    fun studioCard(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 40f
        setColor(STUDIO_CARD_BG)
        setStroke(2, STUDIO_CARD_BORDER)
    }

    /** Neutral bordered chip background for the status indicator -- color now comes from the dot inside it, not the chip itself. */
    fun studioPillBadge(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 60f
        setColor(STUDIO_CARD_BG)
        setStroke(2, STUDIO_DIVIDER)
    }

    /** Small solid circle -- used for the status dot and (tinted per state) anywhere else a plain colored dot/circle is needed. */
    fun studioMicCircle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Sunken bordered strip background, darker than a card -- used behind the mic level meter. */
    fun insetBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(STUDIO_INSET_BG)
        setStroke(2, STUDIO_CARD_BORDER)
    }

    /** Small filled rounded chip behind the active bottom-nav tab. */
    fun navActiveChip(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(0xFF1B2E27.toInt())
    }
}
