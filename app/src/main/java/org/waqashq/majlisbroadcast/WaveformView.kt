package org.waqashq.majlisbroadcast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Phase 7+ cosmetic: a small rolling bar visualization driven by real mic
 * level history (BroadcastService.micLevel), not decoration. Not a true
 * frequency-spectrum analyzer -- the app only has an overall peak level per
 * frame (see BroadcastEngine.applyGainAndMeasure), not per-band data, so
 * this is a level-history strip with a fixed color-per-position palette
 * rather than real spectral colors. Cheap to draw, no allocations per
 * frame.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val BAR_COUNT = 32
        private const val MIN_BAR_HEIGHT_FRACTION = 0.08f
    }

    private val levels = IntArray(BAR_COUNT)
    private var writeIndex = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barColors = IntArray(BAR_COUNT) { i ->
        // Fixed hue sweep across the strip (violet -> teal -> gold), same
        // spirit as the reference mockup's colorful bars.
        val hue = 260f - (200f * i / BAR_COUNT) // 260 (violet) down to ~60 (gold)
        Color.HSVToColor(floatArrayOf(((hue % 360f) + 360f) % 360f, 0.65f, 1f))
    }

    /** Call with the latest 0-100 mic level; shifts the history strip left and redraws. */
    fun pushLevel(level: Int) {
        levels[writeIndex] = level.coerceIn(0, 100)
        writeIndex = (writeIndex + 1) % BAR_COUNT
        invalidate()
    }

    /** Resets to a flat idle strip (call when not live). */
    fun reset() {
        levels.fill(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gap = w * 0.015f
        val barWidth = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT
        val minHeight = h * MIN_BAR_HEIGHT_FRACTION
        val midY = h / 2f

        for (i in 0 until BAR_COUNT) {
            // Oldest-to-newest left-to-right: read starting at writeIndex
            // (the next slot to be overwritten is the oldest sample).
            val level = levels[(writeIndex + i) % BAR_COUNT]
            val barHeight = minHeight + (h - minHeight) * (level / 100f)
            val left = i * (barWidth + gap)
            barPaint.color = barColors[i]
            canvas.drawRoundRect(
                left, midY - barHeight / 2f, left + barWidth, midY + barHeight / 2f,
                barWidth / 2f, barWidth / 2f, barPaint
            )
        }
    }
}
