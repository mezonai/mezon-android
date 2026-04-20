package com.mezon.mobile.home.qr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.mezon.mobile.core.LayoutHelper

/**
 * Simple scan overlay: semi-transparent dim on 4 sides around a white-border rectangle.
 * No animation, no corner brackets — matching design exactly.
 */
class QrScanOverlayView(context: Context) : View(context) {

    private val dimPaint = Paint().apply { color = 0x66000000 }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2f).toFloat()
    }

    private val frameRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val size = minOf(w, h) * 0.72f
        val cx = w / 2f
        val cy = h * 0.44f          // slightly above center, matching image
        val left = cx - size / 2f
        val top  = cy - size / 2f
        frameRect.set(left, top, left + size, top + size)

        // Dim 4 surrounding rectangles
        canvas.drawRect(0f, 0f, w, frameRect.top, dimPaint)
        canvas.drawRect(0f, frameRect.bottom, w, h, dimPaint)
        canvas.drawRect(0f, frameRect.top, frameRect.left, frameRect.bottom, dimPaint)
        canvas.drawRect(frameRect.right, frameRect.top, w, frameRect.bottom, dimPaint)

        // White rectangle border (no rounded corners, matching image)
        canvas.drawRect(frameRect, framePaint)
    }
}
