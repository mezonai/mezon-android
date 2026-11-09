package com.mezon.mobile.core.theme

import com.mezon.mobile.core.theme.DesktopThemeToken as Token
import com.mezon.mobile.ui.theme.ThemeMode

internal fun appThemePalette(mode: ThemeMode): DesktopThemePalette =
    if (mode == ThemeMode.DARK) legacyDarkPalette else DesktopThemePalette.forTheme(mode)

// Android Dark colors from before desktop theme sync (d4e549d6).
private val legacyDarkPalette: DesktopThemePalette by lazy {
    val desktopDark = DesktopThemePalette.forTheme(ThemeMode.DARK)
    val colors = Token.entries.map { desktopDark[it] }.toIntArray()
    val surface = 0xFF1C1B1F.toInt()
    val raisedSurface = 0xFF383A43.toInt()
    val selectedBackground = 0xFF4F378B.toInt()
    val textPrimary = 0xFFE6E1E5.toInt()
    val overrides = mapOf(
        Token.BG_ACTIVE_FRIEND_LIST to selectedBackground,
        Token.BG_ACTIVE_MEMBER_CHANNEL to selectedBackground,
        Token.BG_BUTTON_PRIMARY to MobileThemeColors.accent,
        Token.BG_ICON_THEME to 0xFF334155.toInt(),
        Token.BG_INPUT_SECONDARY to 0xFF1C1D23.toInt(),
        Token.BG_MARKDOWN_CODE to raisedSurface,
        Token.BG_MENTION_EVRYONE to 0xFF3C4C43.toInt(),
        Token.BG_PRIMARY to raisedSurface,
        Token.BG_SECONDARY to surface,
        Token.BG_SURFACE to 0xFF26272F.toInt(),
        Token.BG_THEME_DIRECT_MESSAGE to surface,
        Token.BORDER_PRIMARY to 0xFF2A2D31.toInt(),
        Token.BUTTON_THEME_PRIMARY to MobileThemeColors.accent,
        Token.COLOR_MENTION_EVRYONE to 0xFF009C67.toInt(),
        Token.COLOR_MENTION_HOVER to 0xFF3297FF.toInt(),
        Token.COLOR_TEXT_ACTIVE_BUTTON to 0xFF939BF6.toInt(),
        Token.MENTION_COLOR to 0xFF3297FF.toInt(),
        Token.MENTION_PRIMARY to 0xFF3B426E.toInt(),
        Token.TEXT_SECONDARY to textPrimary,
        Token.TEXT_THEME_MESSAGE to textPrimary,
        Token.TEXT_THEME_PRIMARY to 0xFFCAC4D0.toInt(),
        Token.THEME_SETTING_NAV to 0xFF212122.toInt(),
        Token.THEME_SETTING_PRIMARY to 0xFF26272F.toInt(),
    )
    overrides.forEach { (token, color) -> colors[token.ordinal] = color }
    DesktopThemePalette(
        colors = colors,
        semantic = DesktopThemePalette.SemanticColors(
            textPrimary = textPrimary,
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

internal class AndroidThemeOverrides(
    val chatLink: Int? = null,
    val successButton: Int? = null,
    val sheetItemBackground: Int? = null,
    val dialogName: Int? = null,
    val chatBackground: Int? = null,
    val surfaceVariant: Int? = null,
    val strongText: Int? = null,
    val createClanAccent: Int? = null,
    val outline: Int? = null,
    val outlineVariant: Int? = null,
    val onPrimaryContainer: Int? = null,
    val unreadBadge: Int? = null,
    val onlinePresence: Int? = null,
    val voiceActive: Int? = null,
    val reactionBackground: Int? = null,
    val reactionBorder: Int? = null,
    val tabIconDetail: Int? = null,
    val tabLabelActive: Int? = null,
    val tabLabelInactive: Int? = null,
    val channelPanelBackground: Int? = null,
    val channelDocumentBackground: Int? = null,
    val messageMenuItemBackground: Int? = null,
    val messageMenuGroupBackground: Int? = null,
    val messageMenuText: Int? = null,
    val messageMenuWarning: Int? = null,
    val messageMenuDivider: Int? = null,
    val messageMenuWarningBackground: Int? = null,
    val serverRailBackground: Int? = null,
    val threadCardBackground: Int? = null,
    val threadDivider: Int? = null,
    val threadSearchClear: Int? = null,
    val threadEmptyIconBackground: Int? = null,
    val friendHint: Int? = null,
    val friendRequestCount: Int? = null,
    val raisedControlBackground: Int? = null,
    val pollFormHint: Int? = null,
    val pollFormFieldStroke: Int? = null,
    val pollPostDisabledBackground: Int? = null,
    val profileAvatarRing: Int? = null,
    val charcoal: Int? = null,
    val borderDim: Int? = null,
    val secondaryWeight: Int? = null,
    val colorText: Int? = null,
    val codeInlineBackground: Int? = null,
    val tintChannelIconFromName: Boolean = false,
    val tintThreadEmptyIcon: Boolean = true,
    val friendCardsUseSheetBackground: Boolean = false,
    val showFriendScreenDividers: Boolean = true,
)

private val defaultAndroidOverrides = AndroidThemeOverrides()

internal fun androidThemeOverrides(mode: ThemeMode): AndroidThemeOverrides =
    if (mode == ThemeMode.DARK) darkAndroidOverrides else defaultAndroidOverrides

// Android-specific roles that differ from the desktop defaults.
private val darkAndroidOverrides: AndroidThemeOverrides by lazy {
    val palette = appThemePalette(ThemeMode.DARK)
    val semantic = palette.semantic
    val mobile = MobileThemeColors.forMode(ThemeMode.DARK, palette)
    val accent = palette[Token.BUTTON_THEME_PRIMARY]
    val chatBackground = palette[Token.BG_INPUT_SECONDARY]
    val raisedSurface = palette[Token.BG_PRIMARY]
    val surfaceVariant = 0xFF49454F.toInt()
    val serverRail = 0xFF121218.toInt()
    val strongText = 0xFFDFE0E4.toInt()
    val colorText = 0xFFCCCCCC.toInt()

    AndroidThemeOverrides(
        chatLink = accent,
        successButton = semantic.statusOnline,
        sheetItemBackground = 0xFF2B2B33.toInt(),
        dialogName = semantic.textPrimary,
        chatBackground = chatBackground,
        surfaceVariant = surfaceVariant,
        strongText = strongText,
        createClanAccent = 0xFF5865F2.toInt(),
        outline = 0xFF938F99.toInt(),
        outlineVariant = surfaceVariant,
        onPrimaryContainer = 0xFFEADDFF.toInt(),
        unreadBadge = semantic.mentionBadge,
        onlinePresence = semantic.statusOnline,
        voiceActive = 0xFF16A34A.toInt(),
        reactionBackground = 0x80373A54.toInt(),
        reactionBorder = 0xFF2563EB.toInt(),
        tabIconDetail = semantic.textMuted,
        tabLabelActive = strongText,
        tabLabelInactive = 0xFFAEAEAE.toInt(),
        channelPanelBackground = chatBackground,
        channelDocumentBackground = surfaceVariant,
        messageMenuItemBackground = palette[Token.BORDER_PRIMARY],
        messageMenuGroupBackground = chatBackground,
        messageMenuText = colorText,
        messageMenuWarning = 0xFFC61E1B.toInt(),
        messageMenuDivider = raisedSurface,
        messageMenuWarningBackground = raisedSurface,
        serverRailBackground = serverRail,
        threadCardBackground = serverRail,
        threadDivider = surfaceVariant,
        threadSearchClear = semantic.textSecondary,
        threadEmptyIconBackground = surfaceVariant,
        friendHint = semantic.textSecondary,
        friendRequestCount = semantic.textSecondary,
        raisedControlBackground = raisedSurface,
        pollFormHint = semantic.textSecondary,
        pollFormFieldStroke = surfaceVariant,
        pollPostDisabledBackground = raisedSurface,
        profileAvatarRing = serverRail,
        charcoal = mobile.charcoal,
        borderDim = mobile.borderDim,
        secondaryWeight = mobile.secondaryWeight,
        colorText = colorText,
        codeInlineBackground = raisedSurface,
        tintChannelIconFromName = true,
        tintThreadEmptyIcon = false,
        friendCardsUseSheetBackground = true,
        showFriendScreenDividers = false,
    )
}
