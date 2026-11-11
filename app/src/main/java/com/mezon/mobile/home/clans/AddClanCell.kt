package com.mezon.mobile.home.clans

import com.mezon.mobile.ui.cells.drawCenteredIcon
import com.mezon.mobile.ui.cells.setIconColor
import com.mezon.mobile.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class AddClanCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    companion object {
        private val CORNER_RADIUS = LayoutHelper.dp(8).toFloat()
        private val PLUS_ICON_SIZE = LayoutHelper.dp(18)
    }

    private val iconSizePx = LayoutHelper.dp(42)
    private val plusDrawable: Drawable = MezonIcon.loadDrawable(context, R.drawable.mz_plus_20, ThemeColors.defaultIconTint)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, iconSizePx + LayoutHelper.dp(16))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = iconSizePx / 2f
        bgPaint.color = themeColors.surfaceVariant
        canvas.drawRoundRect(
            cx - half,
            cy - half,
            cx + half,
            cy + half,
            CORNER_RADIUS,
            CORNER_RADIUS,
            bgPaint
        )
        plusDrawable.setIconColor(themeColors.blurple)
        plusDrawable.drawCenteredIcon(canvas, cx.toInt(), cy.toInt())
    }
}
