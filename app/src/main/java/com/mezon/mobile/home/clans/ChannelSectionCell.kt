package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ChannelSectionCell(
    context: Context,
    private val themeColors: ThemeColors
) : BaseCell(context) {

    companion object {
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LayoutHelper.dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val arrowPath = Path()
    }

    private var categoryName: String = ""
    private var isExpanded: Boolean = true
    private var truncated: CharSequence = ""

    private val paddingHPx = LayoutHelper.dp(16)
    private val paddingVPx = LayoutHelper.dp(10)
    private val arrowSizePx = LayoutHelper.dp(10)
    private val cellHeightPx = LayoutHelper.dp(36)



    fun bind(name: String, expanded: Boolean) {
        categoryName = name.uppercase()
        isExpanded = expanded
        truncated = ""
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), cellHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        textPaint.color = themeColors.onSurfaceVariant

        val arrowX = paddingHPx.toFloat()
        val arrowY = (height / 2).toFloat()
        arrowPaint.color = themeColors.onSurfaceVariant

        arrowPath.reset()
        if (isExpanded) {
            arrowPath.moveTo(arrowX, arrowY - arrowSizePx / 3f)
            arrowPath.lineTo(arrowX + arrowSizePx / 2f, arrowY + arrowSizePx / 3f)
            arrowPath.lineTo(arrowX + arrowSizePx.toFloat(), arrowY - arrowSizePx / 3f)
        } else {
            arrowPath.moveTo(arrowX + arrowSizePx / 3f, arrowY - arrowSizePx / 2f)
            arrowPath.lineTo(arrowX + arrowSizePx - arrowSizePx / 3f, arrowY)
            arrowPath.lineTo(arrowX + arrowSizePx / 3f, arrowY + arrowSizePx / 2f)
        }
        canvas.drawPath(arrowPath, arrowPaint)

        val textX = paddingHPx + arrowSizePx + LayoutHelper.dp(8)
        val availW = width - textX - paddingHPx
        if (truncated.isEmpty() || truncated.length != categoryName.length) {
            truncated = TextUtils.ellipsize(categoryName, textPaint, availW.toFloat(), TextUtils.TruncateAt.END)
        }
        val textY = arrowY - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(truncated.toString(), textX.toFloat(), textY, textPaint)
    }
}
