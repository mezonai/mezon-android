package com.mezon.mobile.home.clans

import android.graphics.drawable.GradientDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

object CreateClanRnUiTokens {

    fun clanSettingDiagonalGradient(theme: ThemeColors): GradientDrawable {
        val cols = screenGradientColors(theme)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cols[0], cols[1])).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
    }

    fun clanSettingsMenuCornerPx(): Float = LayoutHelper.dp(10f).toFloat()

    fun clanSettingsSectionTitleSp(): Float = LayoutHelper.sp(14f)

    fun screenGradientColors(theme: ThemeColors): IntArray = intArrayOf(theme.background, theme.background)

    fun menuItemBackground(theme: ThemeColors): Int = theme.surface

    fun menuBorder(theme: ThemeColors): Int = theme.border

    fun menuText(theme: ThemeColors): Int = theme.colorText

    fun textStrong(theme: ThemeColors): Int = theme.textStrong

    fun textDisabled(theme: ThemeColors): Int = theme.textDisabled

    fun closeIcon(theme: ThemeColors): Int = menuText(theme)

    const val communityGuidelinesLinkAzureBlue: Int = 0xFF4173C3.toInt()
}
