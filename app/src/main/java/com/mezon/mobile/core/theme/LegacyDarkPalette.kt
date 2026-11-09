package com.mezon.mobile.core.theme

import com.mezon.mobile.core.theme.DesktopThemeToken as Token
import com.mezon.mobile.ui.theme.ThemeMode

internal fun appThemePalette(mode: ThemeMode): DesktopThemePalette =
    if (mode == ThemeMode.DARK) legacyDarkPalette else DesktopThemePalette.forTheme(mode)

// Android Dark colors from before desktop theme sync (d4e549d6).
private val legacyDarkPalette: DesktopThemePalette by lazy {
    val desktopDark = DesktopThemePalette.forTheme(ThemeMode.DARK)
    val colors = Token.entries.map { desktopDark[it] }.toIntArray()
    val overrides = mapOf(
        Token.BG_ACTIVE_FRIEND_LIST to 0xFF4F378B.toInt(),
        Token.BG_ACTIVE_MEMBER_CHANNEL to 0xFF4F378B.toInt(),
        Token.BG_BUTTON_PRIMARY to 0xFF5A62F4.toInt(),
        Token.BG_ICON_THEME to 0xFF334155.toInt(),
        Token.BG_INPUT_SECONDARY to 0xFF1C1D23.toInt(),
        Token.BG_MARKDOWN_CODE to 0xFF383A43.toInt(),
        Token.BG_MENTION_EVRYONE to 0xFF3C4C43.toInt(),
        Token.BG_PRIMARY to 0xFF383A43.toInt(),
        Token.BG_SECONDARY to 0xFF1C1B1F.toInt(),
        Token.BG_SURFACE to 0xFF26272F.toInt(),
        Token.BG_THEME_DIRECT_MESSAGE to 0xFF1C1B1F.toInt(),
        Token.BORDER_PRIMARY to 0xFF2A2D31.toInt(),
        Token.BUTTON_THEME_PRIMARY to 0xFF5A62F4.toInt(),
        Token.COLOR_MENTION_EVRYONE to 0xFF009C67.toInt(),
        Token.COLOR_MENTION_HOVER to 0xFF3297FF.toInt(),
        Token.COLOR_TEXT_ACTIVE_BUTTON to 0xFF939BF6.toInt(),
        Token.MENTION_COLOR to 0xFF3297FF.toInt(),
        Token.MENTION_PRIMARY to 0xFF3B426E.toInt(),
        Token.TEXT_SECONDARY to 0xFFE6E1E5.toInt(),
        Token.TEXT_THEME_MESSAGE to 0xFFE6E1E5.toInt(),
        Token.TEXT_THEME_PRIMARY to 0xFFCAC4D0.toInt(),
        Token.THEME_SETTING_NAV to 0xFF212122.toInt(),
        Token.THEME_SETTING_PRIMARY to 0xFF26272F.toInt(),
    )
    overrides.forEach { (token, color) -> colors[token.ordinal] = color }
    DesktopThemePalette(
        colors = colors,
        semantic = DesktopThemePalette.SemanticColors(
            textPrimary = 0xFFE6E1E5.toInt(),
            textSecondary = 0xFFCAC4D0.toInt(),
            textMuted = 0xFF6B6B6B.toInt(),
            statusOnline = 0xFF43B581.toInt(),
            mentionBadge = 0xFFD30E0E.toInt(),
        ),
        previewColor = 0xFF111827.toInt(),
        previewGradient = null,
        conversationGradient = null,
        chatGradient = null,
        composerGradient = null,
        primaryFillGradient = null,
    )
}
