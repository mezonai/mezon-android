package com.mezon.mobile.home.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

class PttPulseView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var ringColor = Color.WHITE
    private var minRadius = 0f
    private var maxRadius = 0f
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private val ringCount = 3

    init {
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    fun configure(color: Int, minRadiusPx: Float, maxRadiusPx: Float, strokeWidthPx: Float) {
        ringColor = color
        minRadius = minRadiusPx
        maxRadius = maxRadiusPx
        paint.strokeWidth = strokeWidthPx
    }

    fun start() {
        if (animator != null) {
            visibility = VISIBLE
            return
        }
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        if (animator == null) return
        val cx = width / 2f
        val cy = height / 2f
        for (i in 0 until ringCount) {
            var p = phase + i.toFloat() / ringCount
            if (p > 1f) p -= 1f
            val radius = minRadius + p * (maxRadius - minRadius)
            paint.color = ringColor
            paint.alpha = ((1f - p) * 170f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }
}
