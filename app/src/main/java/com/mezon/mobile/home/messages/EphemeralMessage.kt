package com.mezon.mobile.home.messages

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

object EphemeralMessageUi {

    val HORIZONTAL_INSET: Int = LayoutHelper.dp(6)
    val INDICATOR_ICON_GAP: Int = LayoutHelper.dp(4)

    private val cornerRadius: Float = LayoutHelper.dpf(6f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun indicatorTextPaint(theme: ThemeColors): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(12f)
            color = theme.textDisabled
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }

    fun buildIndicatorLayout(text: String, textWidth: Int, paint: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth.coerceAtLeast(1))
            .setMaxLines(2)
            .build()

    fun indicatorIconSize(): Int = LayoutHelper.dp(12)

    fun drawBubbleBackground(canvas: Canvas, theme: ThemeColors, bounds: RectF) {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        fillPaint.color = theme.blurple and 0x00FFFFFF or (0x1A shl 24)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)
    }

    fun drawIndicatorRow(
        canvas: Canvas,
        x: Float,
        y: Float,
        layout: StaticLayout,
        icon: Drawable?,
        iconSize: Int,
        gapAfterIcon: Int
    ) {
        val iconTop = (y + (layout.height - iconSize) / 2f).toInt()
        icon?.setBounds(x.toInt(), iconTop, x.toInt() + iconSize, iconTop + iconSize)
        icon?.draw(canvas)
        canvas.save()
        canvas.translate(x + iconSize + gapAfterIcon, y)
        layout.draw(canvas)
        canvas.restore()
    }
}
