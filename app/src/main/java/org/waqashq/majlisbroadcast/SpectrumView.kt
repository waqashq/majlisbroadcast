package org.waqashq.majlisbroadcast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs

/**
 * Phase 11e: the Broadcast screen's frequency-bar visualizer, replacing the
 * old level-history strip (WaveformView). Bars are real per-band levels
 * from SpectrumAnalyzer -- low frequencies on the start side, high on the
 * end side -- so the shape reflects the actual voice, not a moving average.
 *
 * What makes it read as smooth rather than twitchy:
 *  - its own ~60fps animation loop (postOnAnimation), so bars glide even
 *    though fresh audio data only arrives every ~150ms;
 *  - asymmetric easing: quick attack, slow release, the way hardware level
 *    meters behave -- snapping down looks nervous;
 *  - light smoothing across neighbouring bars, so the outline flows as one
 *    curve instead of a picket fence;
 *  - square tops (Phase 11g) over a level-zone gradient, which is the one
 *    place this screen's otherwise-flat theme bends, for the same reason as
 *    the indicator lamps.
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BAND_COUNT = 22
        /** Fraction of the gap closed per frame going up / coming down. */
        private const val ATTACK = 0.42f
        private const val RELEASE = 0.10f
        /** How much of each bar's value bleeds into its neighbours. */
        private const val NEIGHBOUR_BLEED = 0.22f
        /** Idle bars still show this fraction of height, as a resting baseline. */
        private const val MIN_FRACTION = 0.05f
    }

    private val targets = FloatArray(BAND_COUNT)
    private val display = FloatArray(BAND_COUNT)
    private val smoothed = FloatArray(BAND_COUNT)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var barGradient: Shader? = null

    private val animTick = object : Runnable {
        override fun run() {
            for (i in 0 until BAND_COUNT) {
                val diff = targets[i] - display[i]
                display[i] += diff * (if (diff > 0f) ATTACK else RELEASE)
                if (abs(diff) < 0.25f) display[i] = targets[i]
            }
            // Spatial pass: average each bar with its neighbours so the tops
            // form a flowing outline.
            for (i in 0 until BAND_COUNT) {
                val prev = display[(i - 1).coerceAtLeast(0)]
                val next = display[(i + 1).coerceAtMost(BAND_COUNT - 1)]
                smoothed[i] = display[i] * (1f - NEIGHBOUR_BLEED) + (prev + next) / 2f * NEIGHBOUR_BLEED
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postOnAnimation(animTick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animTick)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Level-zone colouring, the way hardware level meters have always
        // done it: green for normal, amber for hot, red near the top. The
        // shader spans the whole view, so each bar only reveals the zones it
        // actually reaches -- a quiet bar is entirely green, only a loud one
        // shows red at its tip. (Colouring by frequency instead would look
        // busier and means nothing; zone colouring tells you about level.)
        // Phase 11g: more shades, and they arrive sooner. Light green ->
        // mid green -> deep green gives the bars visible depth instead of
        // one flat tone, and amber/red now start lower (amber from ~70% of
        // the bar height, red from ~88%) so normal speech shows colour
        // rather than only shouting.
        barGradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                UiTheme.METER_RED,
                UiTheme.METER_AMBER,
                UiTheme.METER_GREEN_LIGHT,
                UiTheme.METER_GREEN,
                ColorUtils.blendARGB(UiTheme.METER_GREEN, Color.BLACK, 0.45f)
            ),
            floatArrayOf(0f, 0.12f, 0.30f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /** Latest per-band levels (0-100). Length may differ from BAND_COUNT; extra entries are ignored. */
    fun pushSpectrum(bands: IntArray) {
        for (i in 0 until minOf(BAND_COUNT, bands.size)) {
            targets[i] = bands[i].coerceIn(0, 100).toFloat()
        }
    }

    /** Eases every bar back down to the resting baseline (call when not live). */
    fun reset() {
        targets.fill(0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gap = w * 0.012f
        val barWidth = (w - gap * (BAND_COUNT - 1)) / BAND_COUNT
        val minHeight = (h * MIN_FRACTION).coerceAtLeast(barWidth * 0.5f)

        barPaint.shader = barGradient
        for (i in 0 until BAND_COUNT) {
            val level = (smoothed[i] / 100f).coerceIn(0f, 1f)
            val barHeight = minHeight + (h - minHeight) * level
            val left = i * (barWidth + gap)
            // Bottom-anchored, like every bar meter: grows upward from the
            // baseline rather than out from the middle. Square tops (Phase
            // 11g, was rounded) -- sharp corners read as an instrument.
            canvas.drawRect(left, h - barHeight, left + barWidth, h, barPaint)
        }
        barPaint.shader = null
    }
}
