package com.mezon.mobile.home.clans

import android.graphics.drawable.GradientDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.theme.ThemeMode

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
        return when (theme.resolvedMode) {
            ThemeMode.LIGHT -> intArrayOf(0xFFF4F4F8.toInt(), 0xFFF4F4F8.toInt())
            ThemeMode.DARK -> intArrayOf(0xFF121218.toInt(), 0xFF121218.toInt())
            ThemeMode.ABYSS -> intArrayOf(0xFF060933.toInt(), 0xFF04045B.toInt())
            else -> intArrayOf(0xFF121218.toInt(), 0xFF121218.toInt())
        }
    }

    fun menuItemBackground(theme: ThemeColors): Int {
        return when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFFFFFFFF.toInt()
            ThemeMode.DARK -> 0xFF1C1D23.toInt()
            ThemeMode.ABYSS -> 0xFF04045B.toInt()
            else -> 0xFF1C1D23.toInt()
        }
    }

    fun menuBorder(theme: ThemeColors): Int {
        return when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFFEDEDF1.toInt()
            ThemeMode.DARK -> 0xFF2A2D31.toInt()
            ThemeMode.ABYSS -> 0xFF16206A.toInt()
            else -> 0xFF2A2D31.toInt()
        }
    }

    fun menuText(theme: ThemeColors): Int {
        return when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFF29292B.toInt()
            ThemeMode.DARK -> 0xFFCCCCCC.toInt()
            ThemeMode.ABYSS -> 0xFFD6D0EB.toInt()
            else -> 0xFFCCCCCC.toInt()
        }
    }

    fun textStrong(theme: ThemeColors): Int {
        return when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFF070709.toInt()
            ThemeMode.DARK -> 0xFFDFE0E4.toInt()
            ThemeMode.ABYSS -> 0xFFF1EDFF.toInt()
            else -> 0xFFDFE0E4.toInt()
        }
    }

    fun textDisabled(theme: ThemeColors): Int {
        return when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFF606065.toInt()
            ThemeMode.DARK -> 0xFF7B7B83.toInt()
            ThemeMode.ABYSS -> 0xFFBDB4DC.toInt()
            else -> 0xFF7B7B83.toInt()
        }
    }

    fun closeIcon(theme: ThemeColors): Int = menuText(theme)

    const val communityGuidelinesLinkAzureBlue: Int = 0xFF4173C3.toInt()
}
