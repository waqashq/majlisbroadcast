package org.waqashq.majlisbroadcast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Phase 7+ cosmetic: a small rolling bar visualization driven by real mic
 * level history (BroadcastService.micLevel), not decoration. Not a true
 * frequency-spectrum analyzer -- the app only has an overall peak level per
 * frame (see BroadcastEngine.applyGainAndMeasure), not per-band data, so
 * this is a level-history strip with a fixed color-per-position palette
 * rather than real spectral colors.
 *
 * Two independent animations run every frame (postOnAnimation), decoupled
 * from how often pushLevel() is actually called (~300ms from MainActivity's
 * poll):
 *  - Height easing (displayLevels chasing targetLevels) -- the original
 *    Phase 7+ smoothing, unchanged.
 *  - Horizontal scroll easing (scrollProgress 0->1) -- newer: without this,
 *    the whole strip hard-jumps one bar-width left the instant pushLevel()
 *    is called, which reads as a jerky "step" every ~300ms rather than a
 *    continuous flow like a typical audio recorder's waveform. A phantom
 *    (BAR_COUNT+1-th) bar is drawn during the transition so the newest bar
 *    visibly slides in from the right edge instead of popping in.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val BAR_COUNT = 32
        private const val MIN_BAR_HEIGHT_FRACTION = 0.08f
        // Fraction of the remaining gap closed per animation frame --
        // lower = smoother/slower, higher = snappier.
        private const val SMOOTHING = 0.18f
        // How long the left-scroll slide takes to settle after each new
        // sample arrives. Doesn't need to exactly match the real ~300ms
        // poll interval -- if a new sample arrives before this finishes,
        // the in-flight slide just restarts from wherever it was (still
        // reads as continuous); if it settles first, the strip sits at
        // rest briefly, which is normal.
        private const val SCROLL_DURATION_MS = 260f
    }

    private val targetLevels = IntArray(BAR_COUNT)
    private val displayLevels = FloatArray(BAR_COUNT)
    private var writeIndex = 0

    private var scrollProgress = 1f // 1f = settled, 0f = just shifted
    private var lastFrameRealtimeMs = 0L

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barColors = IntArray(BAR_COUNT) { i ->
        // Fixed hue sweep across the strip (violet -> teal -> gold), same
        // spirit as the reference mockup's colorful bars.
        val hue = 260f - (200f * i / BAR_COUNT) // 260 (violet) down to ~60 (gold)
        Color.HSVToColor(floatArrayOf(((hue % 360f) + 360f) % 360f, 0.65f, 1f))
    }

    private val animTick = object : Runnable {
        override fun run() {
            for (i in 0 until BAR_COUNT) {
                val diff = targetLevels[i] - displayLevels[i]
                if (kotlin.math.abs(diff) > 0.4f) {
                    displayLevels[i] += diff * SMOOTHING
                } else if (displayLevels[i] != targetLevels[i].toFloat()) {
                    displayLevels[i] = targetLevels[i].toFloat()
                }
            }

            if (scrollProgress < 1f) {
                val now = SystemClock.elapsedRealtime()
                val dt = if (lastFrameRealtimeMs == 0L) 16L else (now - lastFrameRealtimeMs)
                lastFrameRealtimeMs = now
                scrollProgress = (scrollProgress + dt / SCROLL_DURATION_MS).coerceAtMost(1f)
            } else {
                lastFrameRealtimeMs = 0L
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

    /** Call with the latest 0-100 mic level; the animation loop eases toward it and redraws. */
    fun pushLevel(level: Int) {
        targetLevels[writeIndex] = level.coerceIn(0, 100)
        writeIndex = (writeIndex + 1) % BAR_COUNT
        scrollProgress = 0f
    }

    /** Resets to a flat idle strip (call when not live). */
    fun reset() {
        targetLevels.fill(0)
        displayLevels.fill(0f)
        scrollProgress = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gap = w * 0.015f
        val barWidth = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT
        val slot = barWidth + gap
        val minHeight = h * MIN_BAR_HEIGHT_FRACTION
        val midY = h / 2f
        val shiftPx = (1f - scrollProgress) * slot

        // BAR_COUNT settled bars, plus one phantom bar (index BAR_COUNT)
        // that slides in from the right edge during the post-push
        // transition -- see class doc.
        for (i in 0..BAR_COUNT) {
            val isPhantom = i == BAR_COUNT
            val left = i * slot - shiftPx
            if (left + barWidth < 0f || left > w) continue // fully off-screen

            val level = if (isPhantom) {
                displayLevels[(writeIndex + BAR_COUNT - 1) % BAR_COUNT]
            } else {
                displayLevels[(writeIndex + i) % BAR_COUNT]
            }
            val barHeight = minHeight + (h - minHeight) * (level / 100f)
            barPaint.color = barColors[if (isPhantom) BAR_COUNT - 1 else i]
            canvas.drawRoundRect(
                left, midY - barHeight / 2f, left + barWidth, midY + barHeight / 2f,
                barWidth / 2f, barWidth / 2f, barPaint
            )
        }
    }
}
