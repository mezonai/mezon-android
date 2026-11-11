package com.mezon.mobile.ui.theme

import androidx.annotation.StringRes
import com.mezon.mobile.R

enum class ThemeMode(
    val storageKey: String,
    @param:StringRes val titleResId: Int,
    val isLight: Boolean = false
) {
    // Keep the original names and storage keys for existing installations.
    LIGHT("light", R.string.setting_theme_light, true),
    DARK("dark", R.string.setting_theme_dark),
    ABYSS("abyss", R.string.setting_theme_abyss),
    SYSTEM("system", R.string.setting_theme_system),
    SUNRISE("sunrise", R.string.setting_theme_sunrise, true),
    RED_DARK("redDark", R.string.setting_theme_red_dark),
    PURPLE_HAZE("purple_haze", R.string.setting_theme_purple_haze),
    SUNSET("sunset", R.string.setting_theme_sunset),
    CISHER("cisher", R.string.setting_theme_cisher, true),
    BERRYNADE("berrynade", R.string.setting_theme_berrynade);

    val iconTint: Int
        get() = if (isLight) MezonIconColors.LIGHT.second else MezonIconColors.DARK.second

    fun resolve(systemIsDark: Boolean): ThemeMode =
        if (this == SYSTEM) { if (systemIsDark) DARK else LIGHT } else this

    fun asNightTheme(): ThemeMode = if (this == SYSTEM || isLight) DARK else this

    companion object {
        val appearanceOptions = listOf(
            SYSTEM, DARK, LIGHT, SUNRISE, RED_DARK, PURPLE_HAZE, ABYSS, SUNSET, CISHER, BERRYNADE
        )

        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == value } ?: DARK
    }
}
