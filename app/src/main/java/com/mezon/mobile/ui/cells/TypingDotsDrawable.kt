package com.mezon.mobile.ui.cells

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import com.mezon.mobile.core.LayoutHelper
import kotlin.math.PI
import kotlin.math.sin

class TypingDotsDrawable(color: Int) : Drawable(), Animatable {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val dotRadius = LayoutHelper.dpf(2.5f)
    private val dotGap = LayoutHelper.dpf(3f)
    private val bounce = LayoutHelper.dpf(1.5f)
    private var startedAtMs = 0L
    private var running = false

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidateSelf()
        }

    override fun getIntrinsicWidth(): Int = (dotRadius * 6 + dotGap * 2).toInt() + 1

    override fun getIntrinsicHeight(): Int = (dotRadius * 2 + bounce * 2).toInt() + 1

    override fun draw(canvas: Canvas) {
        val b = bounds
        val elapsed = if (running) SystemClock.uptimeMillis() - startedAtMs else 0L
        val phase = (elapsed % CYCLE_MS).toFloat() / CYCLE_MS
        val baseAlpha = paint.alpha
        val cy = b.exactCenterY()
        var cx = b.left + dotRadius
        for (i in 0 until DOT_COUNT) {
            val local = (phase - i * DOT_DELAY + 1f) % 1f
            val pulse = if (local < PULSE_SPAN) sin(local / PULSE_SPAN * PI).toFloat() else 0f
            paint.alpha = (baseAlpha * (MIN_ALPHA + (1f - MIN_ALPHA) * pulse)).toInt()
            canvas.drawCircle(cx, cy - bounce * pulse, dotRadius, paint)
            cx += dotRadius * 2 + dotGap
        }
        paint.alpha = baseAlpha
        if (running) invalidateSelf()
    }

    override fun start() {
        if (running) return
        running = true
        startedAtMs = SystemClock.uptimeMillis()
        invalidateSelf()
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val DOT_COUNT = 3
        const val CYCLE_MS = 1200L
        const val DOT_DELAY = 0.18f
        const val PULSE_SPAN = 0.5f
        const val MIN_ALPHA = 0.35f
    }
}
