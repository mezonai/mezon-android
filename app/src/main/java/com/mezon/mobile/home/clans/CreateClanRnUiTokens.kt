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

    fun screenGradientColors(theme: ThemeColors): IntArray {
        val background = if (theme.usesLegacyDarkColors) theme.serverRailBg else theme.background
        return intArrayOf(background, background)
    }

    fun menuItemBackground(theme: ThemeColors): Int = if (theme.usesLegacyDarkColors) theme.channelPanelBg else theme.surface

    fun menuBorder(theme: ThemeColors): Int = theme.border

    fun menuText(theme: ThemeColors): Int = theme.colorText

    fun textStrong(theme: ThemeColors): Int = theme.textStrong

    fun textDisabled(theme: ThemeColors): Int = if (theme.usesLegacyDarkColors) 0xFF7B7B83.toInt() else theme.textDisabled

    fun closeIcon(theme: ThemeColors): Int = menuText(theme)

    const val communityGuidelinesLinkAzureBlue: Int = 0xFF4173C3.toInt()
}
