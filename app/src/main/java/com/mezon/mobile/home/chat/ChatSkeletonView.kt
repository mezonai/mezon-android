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
import kotlin.math.max

class ChatSkeletonView(context: Context, themeColors: ThemeColors) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColors.onSurfaceVariant
    }
    private val rect = RectF()
    private val avatarLeft = LayoutHelper.dpf(6f)
    private val avatarSize = LayoutHelper.dpf(40f)
    private val avatarGap = LayoutHelper.dpf(12f)
    private val rightInset = LayoutHelper.dpf(24f)
    private val bottomInset = LayoutHelper.dpf(12f)
    private val nameHeight = LayoutHelper.dpf(12f)
    private val timeWidth = LayoutHelper.dpf(32f)
    private val timeHeight = LayoutHelper.dpf(9f)
    private val timeGap = LayoutHelper.dpf(8f)
    private val nameToBodyGap = LayoutHelper.dpf(10f)
    private val bodyHeight = LayoutHelper.dpf(12f)
    private val bodyGap = LayoutHelper.dpf(8f)
    private val rowGap = LayoutHelper.dpf(24f)
    private val nameWidths = floatArrayOf(0.26f, 0.20f, 0.32f, 0.23f, 0.29f)
    private val bodyLineCounts = intArrayOf(2, 1, 3, 2, 1, 2)
    private val bodyWidths = floatArrayOf(0.82f, 0.48f, 0.66f, 0.38f, 0.90f, 0.57f, 0.72f)

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
        val textLeft = avatarLeft + avatarSize + avatarGap
        val textSpan = width - textLeft - rightInset
        if (textSpan <= 0f || height <= 0) return
        var rowBottom = height - bottomInset
        var index = 0
        while (rowBottom > 0f) {
            val bodyLines = bodyLineCounts[index % bodyLineCounts.size]
            val barsHeight = nameHeight + nameToBodyGap + bodyHeight * bodyLines + bodyGap * (bodyLines - 1)
            val rowHeight = max(avatarSize, barsHeight)
            val top = rowBottom - rowHeight
            val fade = (rowBottom / height).coerceIn(FADE_MIN, 1f)

            paint.alpha = (AVATAR_ALPHA * fade).toInt()
            canvas.drawCircle(avatarLeft + avatarSize / 2f, top + avatarSize / 2f, avatarSize / 2f, paint)

            val nameWidth = textSpan * nameWidths[index % nameWidths.size]
            paint.alpha = (NAME_ALPHA * fade).toInt()
            drawBar(canvas, textLeft, top, nameWidth, nameHeight)

            paint.alpha = (TIME_ALPHA * fade).toInt()
            drawBar(canvas, textLeft + nameWidth + timeGap, top + (nameHeight - timeHeight) / 2f, timeWidth, timeHeight)

            paint.alpha = (BODY_ALPHA * fade).toInt()
            var lineTop = top + nameHeight + nameToBodyGap
            for (line in 0 until bodyLines) {
                drawBar(canvas, textLeft, lineTop, textSpan * bodyWidths[(index * 2 + line) % bodyWidths.size], bodyHeight)
                lineTop += bodyHeight + bodyGap
            }

            rowBottom = top - rowGap
            index++
        }
    }

    private fun drawBar(canvas: Canvas, left: Float, top: Float, barWidth: Float, barHeight: Float) {
        val radius = barHeight / 2f
        rect.set(left, top, left + barWidth, top + barHeight)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    companion object {
        private const val AVATAR_ALPHA = 80
        private const val NAME_ALPHA = 92
        private const val TIME_ALPHA = 44
        private const val BODY_ALPHA = 56
        private const val FADE_MIN = 0.3f
        private const val PULSE_MIN_ALPHA = 0.45f
        private const val PULSE_DURATION_MS = 900L
    }
}
