package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.setIconColor

internal class AddClanCell(context: Context, themeColors: ThemeColors) :
    ClanRailActionCell(context, themeColors, R.drawable.mz_plus_24, R.string.clan_add)

internal class DiscoverRailCell(context: Context, themeColors: ThemeColors) :
    ClanRailActionCell(context, themeColors, R.drawable.mz_discover_20, R.string.discover_title)

internal abstract class ClanRailActionCell(
    context: Context,
    private val themeColors: ThemeColors,
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
) : View(context) {

    private val tileSize = LayoutHelper.dp(42)
    private val cornerRadius = LayoutHelper.dp(8).toFloat()
    private val iconSize = LayoutHelper.dp(24)
    private val icon = MezonIcon.loadDrawable(context, iconRes)
    private val tileBounds = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = context.getString(labelRes)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), tileSize + LayoutHelper.dp(16))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = tileSize / 2f
        val railColor = themeColors.serverRailBg
        val onSurface = themeColors.onSurface
        val selected = isSelected
        tileBounds.set(cx - half, cy - half, cx + half, cy + half)
        fillPaint.color = if (selected) themeColors.primary else ColorUtils.blendARGB(railColor, onSurface, 0.16f)
        canvas.drawRoundRect(tileBounds, cornerRadius, cornerRadius, fillPaint)

        icon.setIconColor(if (selected) themeColors.onPrimary else onSurface)
        MezonIcon.drawIcon(canvas, icon, cx.toInt(), cy.toInt(), iconSize)
    }
}
