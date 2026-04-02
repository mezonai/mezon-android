package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class AddClanCell(
    context: Context,
    private val themeColors: ThemeColors
) : BaseCell(context) {

    private val iconSizePx = LayoutHelper.dp(40)
    private val paddingVPx = LayoutHelper.dp(6)
    private val cornerRadius = LayoutHelper.dp(16).toFloat()
    private val strokeWidth = LayoutHelper.dp(2).toFloat()
    private val plusSizePx = LayoutHelper.dp(14).toFloat()

    private val shapeRectF = RectF()
    private val clipPath = Path()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@AddClanCell.strokeWidth
    }
    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = this@AddClanCell.strokeWidth
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            iconSizePx + paddingVPx * 2
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = iconSizePx / 2f
        val left = cx - half
        val top = cy - half
        val right = cx + half
        val bottom = cy + half

        // Background
        bgPaint.color = themeColors.surface
        shapeRectF.set(left, top, right, bottom)
        clipPath.reset()
        clipPath.addRoundRect(shapeRectF, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.drawPath(clipPath, bgPaint)

        // Dashed border
        borderPaint.color = themeColors.onSurface.let {
            // 40% alpha
            (it and 0x00FFFFFF) or (0x66000000.toInt())
        }
        canvas.drawRoundRect(shapeRectF, cornerRadius, cornerRadius, borderPaint)

        // Plus icon
        plusPaint.color = themeColors.onSurface.let {
            (it and 0x00FFFFFF) or (0xB3000000.toInt())
        }
        // Horizontal bar
        canvas.drawLine(cx - plusSizePx / 2, cy, cx + plusSizePx / 2, cy, plusPaint)
        // Vertical bar
        canvas.drawLine(cx, cy - plusSizePx / 2, cx, cy + plusSizePx / 2, plusPaint)
    }
}
