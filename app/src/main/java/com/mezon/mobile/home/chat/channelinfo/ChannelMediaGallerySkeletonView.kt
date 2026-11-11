package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import kotlin.math.min
import kotlin.math.sin

class ChannelMediaGallerySkeletonView(
    context: Context,
    private val theme: ThemeColors,
    private val cellSizePx: () -> Int,
) : View(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pulsing = false
    private val rectF = RectF()
    private val bgPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    private val pulsePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

    private val shimmerTick =
        object : Runnable {
            override fun run() {
                if (!pulsing || !isAttachedToWindow) return
                invalidate()
                mainHandler.postDelayed(this, 32L)
            }
        }

    private val isDark: Boolean
        get() = !theme.resolvedMode.isLight

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulsing = true
        mainHandler.postDelayed(shimmerTick, 32L)
    }

    override fun onDetachedFromWindow() {
        pulsing = false
        mainHandler.removeCallbacks(shimmerTick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        if (w <= 0) return

        val baseArgb =
            if (isDark) {
                theme.surfaceVariant
            } else {
                theme.secondaryLight
            }
        bgPaint.color = (baseArgb and 0x00FFFFFF) or (0xE4000000.toInt())

        val reservedX = PAD_H * 2 + MARGIN * 2 + GAP * 2
        val cellFit = ((w - reservedX) / COLS).coerceAtLeast(1)
        val rnStyleCell = ((w.toFloat() / COLS) - RN_STYLE_PAD).toInt()
            .coerceAtLeast(MIN_CELL)
        val cell = min(
            cellSizePx().coerceAtLeast(MIN_CELL),
            min(cellFit, rnStyleCell)
        )

        val time = System.currentTimeMillis() % 2000L
        val fraction = time / 2000f
        val alphaWave = (sin(fraction * Math.PI * 2).toFloat() * 0.5f + 0.5f)
        val pulseAlpha = (alphaWave * 40f).toInt().coerceIn(0, 255)

        pulsePaint.color =
            if (isDark) {
                0x00FFFFFF or (pulseAlpha shl 24)
            } else {
                0x00000000 or (pulseAlpha shl 24)
            }

        var y = PAD_TOP.toFloat()
        repeat(ROWS) {
            var x = (PAD_H + MARGIN).toFloat()
            repeat(COLS) {
                rectF.set(x, y, x + cell, y + cell)
                canvas.drawRoundRect(rectF, RADIUS, RADIUS, bgPaint)
                canvas.drawRoundRect(rectF, RADIUS, RADIUS, pulsePaint)
                x += cell + GAP
            }
            y += cell + GAP
        }
    }

    companion object {
        private const val COLS = 3
        private const val ROWS = 4
        private val PAD_H = LayoutHelper.dp(8f)
        private val MARGIN = LayoutHelper.dp(4f)
        private val GAP = LayoutHelper.dp(8f)
        private val RADIUS = LayoutHelper.dp(8f).toFloat()
        private val PAD_TOP = LayoutHelper.dp(8f)
        private val RN_STYLE_PAD = LayoutHelper.dp(16f)
        private val MIN_CELL = LayoutHelper.dp(48f)
    }
}
