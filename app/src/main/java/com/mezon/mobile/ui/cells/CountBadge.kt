package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import com.mezon.mobile.core.LayoutHelper

internal fun createCountBadgeView(
    context: Context,
    @ColorInt badgeColor: Int,
    heightDp: Int = 18,
    horizontalPaddingDp: Int = 5,
): TextView = TextView(context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(Color.WHITE)
    gravity = Gravity.CENTER
    includeFontPadding = false
    minWidth = LayoutHelper.dp(heightDp)
    val horizontalPadding = LayoutHelper.dp(horizontalPaddingDp)
    setPadding(horizontalPadding, 0, horizontalPadding, 0)
    background = GradientDrawable().apply {
        cornerRadius = LayoutHelper.dpf(heightDp / 2f)
        setColor(badgeColor)
    }
    visibility = View.GONE
}

internal fun TextView.updateCountBadge(count: Int, @ColorInt badgeColor: Int) {
    text = when {
        count <= 0 -> ""
        count > 99 -> "99+"
        else -> count.toString()
    }
    (background as? GradientDrawable)?.setColor(badgeColor)
    visibility = if (count > 0) View.VISIBLE else View.GONE
}
