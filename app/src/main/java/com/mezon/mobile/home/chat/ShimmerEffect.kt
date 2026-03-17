package com.mezon.mobile.home.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

class ShimmerEffect {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1C000000
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1C000000
    }

    private val rect = RectF()

    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float, isDark: Boolean) {
        if (right <= left || bottom <= top) return

        rect.set(left, top, right, bottom)

        bgPaint.color = if (isDark) 0x30FFFFFF else 0x1C000000
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        val time = System.currentTimeMillis() % 2000L
        val fraction = time / 2000f
        val alpha = (kotlin.math.sin(fraction * Math.PI * 2).toFloat() * 0.5f + 0.5f)
        val pulseAlpha = (alpha * 40).toInt().coerceIn(0, 255)

        pulsePaint.color = if (isDark) 0x00FFFFFF or (pulseAlpha shl 24)
            else 0x00000000 or (pulseAlpha shl 24)
        canvas.drawRoundRect(rect, radius, radius, pulsePaint)
    }
}
