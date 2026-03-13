package com.mezon.mobile.home.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.mezon.mobile.core.LayoutHelper

class RadialProgress {

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(2.5f)
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }

    private val rect = RectF()
    private val radius = LayoutHelper.dp(24).toFloat()
    private val progressRadius = LayoutHelper.dp(18).toFloat()

    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }
    var isLoading = false

    fun draw(canvas: Canvas, cx: Float, cy: Float) {
        if (!isLoading && progress >= 1f) return

        canvas.drawCircle(cx, cy, radius, bgPaint)

        if (isLoading) {
            val sweepAngle = 90f
            val startAngle = (System.currentTimeMillis() % 1000L) / 1000f * 360f
            rect.set(cx - progressRadius, cy - progressRadius, cx + progressRadius, cy + progressRadius)
            canvas.drawArc(rect, startAngle, sweepAngle, false, progressPaint)
        } else if (progress < 1f) {
            rect.set(cx - progressRadius, cy - progressRadius, cx + progressRadius, cy + progressRadius)
            canvas.drawArc(rect, -90f, 360f * progress, false, progressPaint)
        }
    }
}
