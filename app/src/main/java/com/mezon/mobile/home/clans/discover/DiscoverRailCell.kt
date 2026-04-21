package com.mezon.mobile.home.clans.discover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class DiscoverRailCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    private val iconSizePx = LayoutHelper.dp(42)
    private val joinDrawable: Drawable = MezonIcon.joinClanIcon.getDrawable(context)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, iconSizePx + LayoutHelper.dp(16))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = iconSizePx / 2f
        bgPaint.color = themeColors.tertiary
        canvas.drawRoundRect(
            cx - half,
            cy - half,
            cx + half,
            cy + half,
            LayoutHelper.dp(8).toFloat(),
            LayoutHelper.dp(8).toFloat(),
            bgPaint
        )
        MezonIcon.drawIcon(canvas, joinDrawable, cx.toInt(), cy.toInt(), LayoutHelper.dp(15))
    }
}
