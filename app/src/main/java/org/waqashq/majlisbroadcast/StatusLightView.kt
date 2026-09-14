package org.waqashq.majlisbroadcast

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * Phase 11: a small "3D" indicator lamp -- a glossy domed bulb in a dark
 * bezel, with a soft glow around it while lit. Used on the Broadcast
 * screen for the website live/offline light.
 *
 * This is a deliberate, contained exception to the flat "Noor" look (see
 * UiTheme's doc -- no gradients or glow anywhere else): the user asked for
 * this one element to read as a physical light, and the gradients live
 * entirely inside this View so nothing else in the app changes.
 *
 * How the depth illusion is drawn, back to front:
 *  1. glow   -- radial fade from the lamp color to transparent (lit only)
 *  2. bezel  -- dark ring, lighter at the top than the bottom (metal rim)
 *  3. dome   -- radial gradient whose center is offset up and to the left,
 *               so the bulb looks lit from above instead of a flat disc
 *  4. shine  -- small white oval near the top, the specular highlight
 * Everything is sized from the View's own width/height, so any square
 * size works. The gradient objects depend only on size + lamp state, so
 * they're built in rebuild() when either changes, not on every draw.
 */
class StatusLightView(context: Context) : View(context) {

    enum class Lamp { LIVE, OFFLINE, UNKNOWN }

    var lamp: Lamp = Lamp.UNKNOWN
        set(value) {
            if (field != value) {
                field = value
                rebuild()
                invalidate()
            }
        }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val domePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineOval = RectF()

    private var cx = 0f
    private var cy = 0f
    private var outerR = 0f
    private var bezelR = 0f
    private var domeR = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        cx = width / 2f
        cy = height / 2f
        outerR = minOf(width, height) / 2f
        // The glow needs room outside the bulb, so the bezel only uses the
        // inner ~62% of the View's radius.
        bezelR = outerR * 0.62f
        domeR = bezelR * 0.80f

        val base = when (lamp) {
            Lamp.LIVE -> LIVE_COLOR
            Lamp.OFFLINE -> UiTheme.STUDIO_STOP_RED
            Lamp.UNKNOWN -> UNLIT_COLOR
        }
        val lit = lamp != Lamp.UNKNOWN

        glowPaint.shader = if (lit) {
            RadialGradient(
                cx, cy, outerR,
                intArrayOf(ColorUtils.setAlphaComponent(base, 0x90), ColorUtils.setAlphaComponent(base, 0x30), Color.TRANSPARENT),
                floatArrayOf(bezelR / outerR, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }

        bezelPaint.shader = LinearGradient(
            cx, cy - bezelR, cx, cy + bezelR,
            0xFF5A5F5D.toInt(), 0xFF141617.toInt(),
            Shader.TileMode.CLAMP
        )

        // Highlight center sits up-left of the true center.
        val light = ColorUtils.blendARGB(base, Color.WHITE, if (lit) 0.55f else 0.25f)
        val dark = ColorUtils.blendARGB(base, Color.BLACK, 0.55f)
        domePaint.shader = RadialGradient(
            cx - domeR * 0.35f, cy - domeR * 0.40f, domeR * 1.5f,
            intArrayOf(light, base, dark),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        val shineW = domeR * 0.95f
        val shineH = domeR * 0.55f
        val shineTop = cy - domeR * 0.85f
        shineOval.set(cx - shineW / 2f, shineTop, cx + shineW / 2f, shineTop + shineH)
        shinePaint.shader = LinearGradient(
            0f, shineOval.top, 0f, shineOval.bottom,
            ColorUtils.setAlphaComponent(Color.WHITE, if (lit) 0xB0 else 0x50), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (domeR == 0f) return
        if (glowPaint.shader != null) canvas.drawCircle(cx, cy, outerR, glowPaint)
        canvas.drawCircle(cx, cy, bezelR, bezelPaint)
        canvas.drawCircle(cx, cy, domeR, domePaint)
        canvas.drawOval(shineOval, shinePaint)
    }

    private companion object {
        // Slightly brighter than PRIMARY_GREEN so a lit lamp pops against
        // the dark card instead of reading as the (flat) accent color.
        const val LIVE_COLOR = 0xFF2FD67E.toInt()
        const val UNLIT_COLOR = 0xFF3A3F3D.toInt()
    }
}
