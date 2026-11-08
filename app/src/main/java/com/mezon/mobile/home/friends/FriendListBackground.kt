package com.mezon.mobile.home.friends

import android.graphics.drawable.GradientDrawable
import com.mezon.mobile.core.ThemeColors

/** Matches the vertical primary-to-primaryGradient surface used by iOS friend screens. */
internal fun friendListBackground(colors: ThemeColors) = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(colors.surface, colors.desktop.conversationGradient?.startColor ?: colors.surface)
)
