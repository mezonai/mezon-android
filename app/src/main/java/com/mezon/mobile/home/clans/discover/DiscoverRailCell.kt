package com.mezon.mobile.home.clans.discover

import com.mezon.mobile.ui.cells.drawCenteredIcon
import com.mezon.mobile.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.theme.ThemeMode

class DiscoverRailCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    companion object {
        private val CORNER_RADIUS = LayoutHelper.dp(8).toFloat()
    }

    private val iconSizePx = LayoutHelper.dp(42)
    private val discoverDrawable: Drawable = MezonIcon.loadDrawable(context, R.drawable.mz_discover_20)
    private val highlightedDiscoverDrawable by lazy {
        MezonIcon.loadDrawable(context, R.drawable.mz_discover_20, ThemeMode.DARK.iconTint)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var embeddedDiscoverHighlight: Boolean = false

    fun setEmbeddedDiscoverHighlight(active: Boolean) {
        if (embeddedDiscoverHighlight == active) return
        embeddedDiscoverHighlight = active
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, iconSizePx + LayoutHelper.dp(16))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = iconSizePx / 2f
        bgPaint.color = if (embeddedDiscoverHighlight) themeColors.blurple else themeColors.surfaceVariant
        canvas.drawRoundRect(
            cx - half,
            cy - half,
            cx + half,
            cy + half,
            CORNER_RADIUS,
            CORNER_RADIUS,
            bgPaint
        )
        val icon = if (embeddedDiscoverHighlight) highlightedDiscoverDrawable else discoverDrawable
        if (!embeddedDiscoverHighlight) icon.setTint(themeColors.iconTint)
        icon.drawCenteredIcon(canvas, cx.toInt(), cy.toInt())
    }
}
