package com.mezon.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode { LIGHT, DARK, ABYSS, SYSTEM }

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }

private val MezonDarkScheme = darkColorScheme(
    primary = Blurple,
    onPrimary = Color.White,
    primaryContainer = MezonDarkColors.MidnightBlue,
    onPrimaryContainer = Color(0xFFD0BCFF),
    secondary = MezonDarkColors.Secondary,
    onSecondary = MezonDarkColors.Text,
    secondaryContainer = MezonDarkColors.SecondaryLight,
    onSecondaryContainer = MezonDarkColors.TextStrong,
    tertiary = LinkBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF004D8F),
    onTertiaryContainer = Color(0xFFB0D4FF),
    background = MezonDarkColors.Tertiary,
    onBackground = MezonDarkColors.TextStrong,
    surface = MezonDarkColors.Primary,
    onSurface = MezonDarkColors.TextStrong,
    surfaceVariant = MezonDarkColors.Secondary,
    onSurfaceVariant = MezonDarkColors.Text,
    surfaceTint = Blurple,
    outline = MezonDarkColors.Border,
    outlineVariant = MezonDarkColors.BorderHighlight,
    error = MezonRed,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = MezonLightColors.Primary,
    inverseOnSurface = MezonLightColors.TextStrong,
    inversePrimary = Color(0xFF4A52D4),
    scrim = Color.Black,
    surfaceContainerHighest = MezonDarkColors.Charcoal,
    surfaceContainerHigh = MezonDarkColors.Jet,
    surfaceContainer = MezonDarkColors.Secondary,
    surfaceContainerLow = MezonDarkColors.Primary,
    surfaceContainerLowest = MezonDarkColors.Tertiary,
    surfaceBright = Color(0xFF3A3A3D),
    surfaceDim = MezonDarkColors.Tertiary
)

private val MezonLightScheme = lightColorScheme(
    primary = Blurple,
    onPrimary = Color.White,
    primaryContainer = MezonLightColors.MidnightBlue,
    onPrimaryContainer = Color(0xFF1C1D22),
    secondary = MezonLightColors.Primary,
    onSecondary = MezonLightColors.Text,
    secondaryContainer = MezonLightColors.Tertiary,
    onSecondaryContainer = MezonLightColors.Text,
    tertiary = LinkBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1E0FF),
    onTertiaryContainer = Color(0xFF001A40),
    background = MezonLightColors.Primary,
    onBackground = MezonLightColors.TextStrong,
    surface = MezonLightColors.Secondary,
    onSurface = MezonLightColors.TextStrong,
    surfaceVariant = MezonLightColors.Primary,
    onSurfaceVariant = MezonLightColors.TextDisabled,
    surfaceTint = Blurple,
    outline = MezonLightColors.Border,
    outlineVariant = MezonLightColors.BorderHighlight,
    error = MezonRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = MezonDarkColors.Primary,
    inverseOnSurface = MezonDarkColors.TextStrong,
    inversePrimary = Color(0xFFB0C0FF),
    scrim = Color.Black,
    surfaceContainerHighest = MezonLightColors.Tertiary,
    surfaceContainerHigh = MezonLightColors.Charcoal,
    surfaceContainer = MezonLightColors.Primary,
    surfaceContainerLow = MezonLightColors.Secondary,
    surfaceContainerLowest = Color.White,
    surfaceBright = Color.White,
    surfaceDim = MezonLightColors.Primary
)

private val MezonAbyssScheme = darkColorScheme(
    primary = Blurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2470),
    onPrimaryContainer = Color(0xFFD0BCFF),
    secondary = MezonAbyssColors.Secondary,
    onSecondary = MezonDarkColors.Text,
    secondaryContainer = MezonAbyssColors.SecondaryLight,
    onSecondaryContainer = MezonDarkColors.TextStrong,
    tertiary = LinkBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF004D8F),
    onTertiaryContainer = Color(0xFFB0D4FF),
    background = MezonAbyssColors.SecondaryWeight,
    onBackground = MezonDarkColors.TextStrong,
    surface = MezonAbyssColors.Primary,
    onSurface = MezonDarkColors.TextStrong,
    surfaceVariant = MezonAbyssColors.Secondary,
    onSurfaceVariant = MezonDarkColors.Text,
    surfaceTint = Blurple,
    outline = Color(0xFF2E2860),
    outlineVariant = Color(0xFF231D50),
    error = MezonRed,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = MezonLightColors.Primary,
    inverseOnSurface = MezonLightColors.TextStrong,
    inversePrimary = Color(0xFF4A52D4),
    scrim = Color.Black,
    surfaceContainerHighest = MezonAbyssColors.Charcoal,
    surfaceContainerHigh = MezonAbyssColors.Jet,
    surfaceContainer = MezonAbyssColors.Secondary,
    surfaceContainerLow = MezonAbyssColors.Primary,
    surfaceContainerLowest = MezonAbyssColors.SecondaryWeight,
    surfaceBright = MezonAbyssColors.SecondaryLight,
    surfaceDim = MezonAbyssColors.SecondaryWeight
)

@Composable
fun MezonTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val colorScheme: ColorScheme = when (themeMode) {
        ThemeMode.LIGHT -> MezonLightScheme
        ThemeMode.DARK -> MezonDarkScheme
        ThemeMode.ABYSS -> MezonAbyssScheme
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) MezonDarkScheme else MezonLightScheme
    }

    val dimens = rememberScreenDimens()
    val typography = scaledTypography(dimens)

    CompositionLocalProvider(
        LocalDimens provides dimens,
        LocalThemeMode provides themeMode
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
