package com.mezon.mobile.core.theme

import com.mezon.mobile.ui.theme.ThemeMode

/** Mobile UI colors used by iOS AppTheme.swift outside the desktop token palette. */
internal data class MobileThemeColors(
    val secondaryLight: Int,
    val secondaryWeight: Int,
    val borderDim: Int,
    val charcoal: Int,
    val loginInputBg: Int,
    val loginInputBorder: Int,
) {
    companion object {
        val accent = 0xFF5A62F4.toInt()
        val unreadBadge = 0xFFEA2420.toInt()
        val newConversation = 0xFF5865F2.toInt()
        val searchAccent = 0xFF5973F2.toInt()

        private val dark = MobileThemeColors(
            0xFF26272F.toInt(), 0xFF212122.toInt(), 0xFF2F2F37.toInt(), 0xFF2B2B2E.toInt(),
            0xFF2A2E31.toInt(), 0xFF3E4045.toInt()
        )
        private val light = MobileThemeColors(
            0xFFFFFFFF.toInt(), 0xFFF0F0F0.toInt(), 0xFFDFDFDF.toInt(), 0xFFF2F3F5.toInt(),
            0xFFFFFFFF.toInt(), 0xFFCCCCCC.toInt()
        )
        private val sunrise = MobileThemeColors(
            0xFFF8EBEE.toInt(), 0xFFFFE5ED.toInt(), 0xFFF4F4F4.toInt(), 0xFFF2F3F5.toInt(),
            0xFFFFFFFF.toInt(), 0xFFCCCCCC.toInt()
        )
        private val redDark = MobileThemeColors(
            0xFF70393C.toInt(), 0xFF67171A.toInt(), 0xFF2F2F37.toInt(), 0xFF2B2B2E.toInt(),
            0xFF2A2E31.toInt(), 0xFF3E4045.toInt()
        )
        private val purpleHaze = MobileThemeColors(
            0xFF1E143A.toInt(), 0xFF1E1133.toInt(), 0xFF2F2F37.toInt(), 0xFF3B3B48.toInt(),
            0xFFFFFFFF.toInt(), 0xFFCCCCCC.toInt()
        )
        private val abyssDark = MobileThemeColors(
            0xFF24204E.toInt(), 0xFF0E0B20.toInt(), 0xFF2F2F37.toInt(), 0xFF2B2B2E.toInt(),
            0xFF2A2E31.toInt(), 0xFF3E4045.toInt()
        )
        private val sunset = MobileThemeColors(
            0xFF4C2C1B.toInt(), 0xFF2C1910.toInt(), 0xFF2F2F37.toInt(), 0xFF2B2B2E.toInt(),
            0xFF2A2E31.toInt(), 0xFF3E4045.toInt()
        )

        fun forMode(mode: ThemeMode, palette: DesktopThemePalette): MobileThemeColors = when (mode) {
            ThemeMode.DARK -> dark
            ThemeMode.LIGHT -> light
            ThemeMode.SUNRISE -> sunrise
            ThemeMode.RED_DARK -> redDark
            ThemeMode.PURPLE_HAZE -> purpleHaze
            ThemeMode.ABYSS -> abyssDark
            ThemeMode.SUNSET -> sunset
            ThemeMode.CISHER -> light.copy(
                secondaryLight = palette[DesktopThemeToken.BG_SURFACE],
                secondaryWeight = palette[DesktopThemeToken.BG_ACTIVE_FRIEND_LIST]
            )
            ThemeMode.BERRYNADE -> dark.copy(
                secondaryLight = palette[DesktopThemeToken.BG_SURFACE],
                secondaryWeight = palette[DesktopThemeToken.BG_ACTIVE_FRIEND_LIST]
            )
            ThemeMode.SYSTEM -> error("Resolve System before choosing UI colors")
        }
    }
}
