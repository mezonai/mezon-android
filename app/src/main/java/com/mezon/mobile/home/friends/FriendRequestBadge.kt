package com.mezon.mobile.home.friends

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

internal fun createFriendRequestBadgeView(context: Context, themeColors: ThemeColors): TextView {
    return TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = LayoutHelper.dp(18)
        setPadding(LayoutHelper.dp(5), 0, LayoutHelper.dp(5), 0)
        updateFriendRequestBadge(0, themeColors)
    }
}

internal fun TextView.updateFriendRequestBadge(count: Int, themeColors: ThemeColors) {
    val safeCount = count.coerceAtLeast(0)
    if (safeCount > 0) {
        text = if (safeCount > 99) "99+" else safeCount.toString()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = LayoutHelper.dp(9f).toFloat()
            setColor(themeColors.badgeRed)
        }
        visibility = View.VISIBLE
    } else {
        text = ""
        visibility = View.GONE
    }
}
