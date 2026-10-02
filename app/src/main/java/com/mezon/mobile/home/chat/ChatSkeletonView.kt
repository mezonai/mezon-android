package com.mezon.mobile.home.chat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ChatSkeletonView(context: Context, themeColors: ThemeColors) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColors.onSurfaceVariant
        alpha = SHAPE_ALPHA
    }
    private val rect = RectF()
    private val sidePadding = LayoutHelper.dp(16f).toFloat()
    private val avatarSize = LayoutHelper.dp(36f).toFloat()
    private val avatarGap = LayoutHelper.dp(12f).toFloat()
    private val lineHeight = LayoutHelper.dp(10f).toFloat()
    private val lineGap = LayoutHelper.dp(8f).toFloat()
    private val rowGap = LayoutHelper.dp(20f).toFloat()
    private val corner = lineHeight / 2f
    private val nameWidths = floatArrayOf(0.26f, 0.20f, 0.32f, 0.23f, 0.29f)
    private val textWidths = floatArrayOf(0.82f, 0.48f, 0.66f, 0.38f, 0.90f, 0.57f, 0.72f)

    private val pulse = ObjectAnimator.ofFloat(this, View.ALPHA, PULSE_MIN_ALPHA, 1f).apply {
        duration = PULSE_DURATION_MS
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncPulse()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncPulse()
    }

    private fun syncPulse() {
        val shouldRun = isAttachedToWindow && isShown
        if (shouldRun && !pulse.isStarted) {
            pulse.start()
        } else if (!shouldRun && pulse.isStarted) {
            pulse.cancel()
            alpha = 1f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val textLeft = sidePadding + avatarSize + avatarGap
        val textSpan = width - textLeft - sidePadding
        if (textSpan <= 0f) return
        var rowBottom = height - rowGap
        var index = 0
        while (rowBottom > 0f) {
            val twoBodyLines = index % 3 != 1
            val bodyLines = if (twoBodyLines) 2 else 1
            val rowHeight = lineHeight * (bodyLines + 1) + lineGap * bodyLines
            val top = rowBottom - rowHeight
            canvas.drawCircle(sidePadding + avatarSize / 2f, top + avatarSize / 2f, avatarSize / 2f, paint)
            drawLine(canvas, textLeft, top, textSpan * nameWidths[index % nameWidths.size])
            drawLine(canvas, textLeft, top + lineHeight + lineGap, textSpan * textWidths[index % textWidths.size])
            if (twoBodyLines) {
                drawLine(
                    canvas,
                    textLeft,
                    top + (lineHeight + lineGap) * 2,
                    textSpan * textWidths[(index + 3) % textWidths.size]
                )
            }
            rowBottom = top - rowGap
            index++
        }
    }

    private fun drawLine(canvas: Canvas, left: Float, top: Float, lineWidth: Float) {
        rect.set(left, top, left + lineWidth, top + lineHeight)
        canvas.drawRoundRect(rect, corner, corner, paint)
    }

    companion object {
        private const val SHAPE_ALPHA = 66
        private const val PULSE_MIN_ALPHA = 0.45f
        private const val PULSE_DURATION_MS = 900L
    }
}
