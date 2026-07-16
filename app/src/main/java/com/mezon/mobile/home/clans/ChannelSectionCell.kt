package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class ChannelSectionCell(
    context: Context,
    private val themeColors: ThemeColors
) : BaseCell(context) {

    companion object {
        val ARROW_SIZE = LayoutHelper.dp(18)
        val PADDING_START = LayoutHelper.dp(8)
        val HEIGHT = LayoutHelper.dp(36)

        private val PADDING_END = LayoutHelper.dp(16)

        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    private var categoryName: String = ""
    private var isExpanded: Boolean = true
    private var truncated: String = ""
    private var truncatedWidth = -1

    private var arrowDrawable: Drawable? = null
    private var arrowTint = 0

    private val paddingStartPx = PADDING_START
    private val paddingEndPx = PADDING_END
    private val arrowSizePx = ARROW_SIZE
    private val cellHeightPx = HEIGHT

    fun bind(name: String, expanded: Boolean, favorite: Boolean = false) {
        categoryName = name.uppercase()
        isExpanded = expanded
        truncated = ""
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), cellHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        val contentColor = themeColors.colorText
        textPaint.color = contentColor
        val cy = (height / 2).toFloat()

        val arrow = resolveArrow(contentColor)
        val arrowTop = (cy - arrowSizePx / 2f).toInt()
        arrow.setBounds(paddingStartPx, arrowTop, paddingStartPx + arrowSizePx, arrowTop + arrowSizePx)
        if (isExpanded) {
            arrow.draw(canvas)
        } else {
            canvas.save()
            canvas.rotate(-90f, paddingStartPx + arrowSizePx / 2f, cy)
            arrow.draw(canvas)
            canvas.restore()
        }

        val textX = paddingStartPx + arrowSizePx
        val availW = width - textX - paddingEndPx
        if (truncated.isEmpty() || truncatedWidth != availW) {
            truncatedWidth = availW
            truncated = TextUtils.ellipsize(categoryName, textPaint, availW.toFloat(), TextUtils.TruncateAt.END).toString()
        }
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(truncated, textX.toFloat(), textY, textPaint)
    }

    private fun resolveArrow(tint: Int): Drawable {
        val existing = arrowDrawable
        if (existing != null && arrowTint == tint) return existing
        arrowTint = tint
        return MezonIcon.chevronDownSmallIcon.getDrawable(context, tint).also { arrowDrawable = it }
    }
}
