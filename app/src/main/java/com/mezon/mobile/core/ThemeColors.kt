package com.mezon.mobile.core

import com.mezon.mobile.core.theme.MobileThemeColors
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.mezon.mobile.core.theme.DesktopThemePalette
import com.mezon.mobile.core.theme.DesktopThemeToken as Token
import com.mezon.mobile.ui.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeColors @Inject constructor() {

    init {
        instance = this
    }

    interface ResourcesProvider {
        fun getColor(key: Int): Int?
        fun getColorOrDefault(key: Int): Int = getColor(key) ?: instance.getColor(key)
        fun hasColor(key: Int): Boolean = getColor(key) != null
    }

    @Volatile
    var currentMode: ThemeMode = ThemeMode.DARK
        private set

    @Volatile
    var resolvedMode: ThemeMode = ThemeMode.DARK
        private set

    private val currentColors = IntArray(NUM_KEYS)
    private val defaultColors = IntArray(NUM_KEYS)

    val dialogNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(16f)
    }
    val dialogNameBoldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(16f)
        typeface = Typeface.DEFAULT_BOLD
    }
    val dialogMessagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
    }
    val dialogMessageBoldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val dialogTimePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(12f)
    }
    val dialogBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val dialogBadgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(11f)
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    val buzzBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val buzzBadgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    val dialogOnlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val dialogOnlineBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2).toFloat()
    }
    val dividerPaint = Paint()

    val chatSenderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val chatContentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f) 
    }
    val chatContentOutPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
    }
    val chatTimePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(11f)
    }
    val chatTimeOutPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(11f)
    }
    val chatFileNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(12f)
    }
    val chatBuzzTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
    }
    val chatBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val chatBubbleOutPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val systemMessageTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
    }

    val settingsNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(17f)
    }
    val settingsValuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
    }
    val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(15f)
        typeface = Typeface.DEFAULT_BOLD
    }
    val buttonTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(15f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val radioPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2).toFloat()
    }
    val radioFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    val switchTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val switchThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val shadowPaint = Paint().apply { color = 0x12000000 }

    val iconTint: Int get() = resolvedMode.iconTint

    val desktop: DesktopThemePalette get() = DesktopThemePalette.forTheme(resolvedMode)
    internal var mobile: MobileThemeColors = MobileThemeColors.forMode(resolvedMode, desktop)
        private set

    val textThemePrimary: Int get() = desktop[Token.TEXT_THEME_PRIMARY]
    val textThemeMessage: Int get() = desktop[Token.TEXT_THEME_MESSAGE]

    init {
        applyPaintColors()
        refreshColorKeys()
    }

    fun setTheme(
        mode: ThemeMode,
        systemIsDark: Boolean = true,
        effectiveMode: ThemeMode = mode.resolve(systemIsDark)
    ) {
        currentMode = mode
        resolvedMode = effectiveMode.resolve(systemIsDark)
        mobile = MobileThemeColors.forMode(resolvedMode, desktop)
        applyPaintColors()
        refreshColorKeys()
    }

    fun getColor(key: Int): Int {
        if (key < 0 || key >= NUM_KEYS) return 0
        return currentColors[key]
    }

    fun getColor(key: Int, provider: ResourcesProvider?): Int {
        val override = provider?.getColor(key)
        if (override != null) return override
        return getColor(key)
    }

    fun hasColor(key: Int): Boolean = key in 0 until NUM_KEYS

    fun getDefaultColor(key: Int): Int {
        if (key < 0 || key >= NUM_KEYS) return 0
        return defaultColors[key]
    }

    fun setColor(key: Int, color: Int) {
        if (key < 0 || key >= NUM_KEYS) return
        currentColors[key] = color
    }

    private fun refreshColorKeys() {
        currentColors[key_windowBackgroundWhite] = surface
        currentColors[key_windowBackgroundGray] = background
        currentColors[key_divider] = outlineVariant
        currentColors[key_shadow] = 0x12000000

        currentColors[key_actionBarDefault] = surface
        currentColors[key_actionBarDefaultIcon] = onSurface
        currentColors[key_actionBarDefaultTitle] = onSurface
        currentColors[key_actionBarDefaultSubtitle] = onSurfaceVariant
        currentColors[key_actionBarDefaultSelector] = (primary and 0x00FFFFFF) or 0x29000000

        currentColors[key_chats_name] = textThemePrimary
        currentColors[key_chats_nameBold] = onSurface
        currentColors[key_chats_message] = onSurfaceVariant
        currentColors[key_chats_date] = onSurfaceVariant
        currentColors[key_chats_unreadCounter] = MobileThemeColors.unreadBadge
        currentColors[key_chats_unreadCounterText] = Color.WHITE
        currentColors[key_chats_onlineCircle] = onlineGreen

        currentColors[key_chat_inBubble] = surfaceVariant
        currentColors[key_chat_outBubble] = primary
        currentColors[key_chat_inText] = textThemeMessage
        currentColors[key_chat_outText] = onPrimary
        currentColors[key_chat_inTime] = onSurfaceVariant
        currentColors[key_chat_outTime] = onPrimary and 0x99FFFFFF.toInt()
        currentColors[key_chat_senderName] = primary
        currentColors[key_chat_linkColor] = textLink

        currentColors[key_settings_name] = onSurface
        currentColors[key_settings_value] = onSurfaceVariant
        currentColors[key_settings_header] = primary
        currentColors[key_settings_icon] = onSurfaceVariant

        currentColors[key_button_text] = onPrimary
        currentColors[key_button_background] = primary
        currentColors[key_radio_outline] = outline
        currentColors[key_radio_fill] = primary
        currentColors[key_switch_track] = outline
        currentColors[key_switch_thumb] = Color.WHITE
        currentColors[key_checkbox_check] = Color.WHITE

        currentColors[key_text_primary] = onSurface
        currentColors[key_text_secondary] = onSurfaceVariant
        currentColors[key_icon_primary] = onSurface
        currentColors[key_icon_secondary] = onSurfaceVariant
        currentColors[key_color_primary] = primary
        currentColors[key_color_error] = error
        currentColors[key_primaryContainer] = primaryContainer
        currentColors[key_onPrimaryContainer] = onPrimaryContainer
        currentColors[key_connectedColor] = connectedColor
        currentColors[key_connectingColor] = connectingColor
        currentColors[key_disconnectedColor] = disconnectedColor
        // Desktop SUCCESS message buttons have a separate color from the generic success tint.
        currentColors[key_color_success] = 0xFF16A34A.toInt()

        currentColors[key_dialogBackground] = surface
        currentColors[key_dialogTextBlack] = onSurface
        currentColors[key_dialogButton] = primary
        currentColors[key_dialogButtonRedText] = error
        currentColors[key_dialogRoundCheckBox] = primary
        currentColors[key_dialogGrayLine] = outlineVariant
        currentColors[key_sheetBackground] = surface
        currentColors[key_sheetTitle] = onSurface
        currentColors[key_sheetHandle] = outline

        currentColors[key_actionBarActionModeDefault] = surface
        currentColors[key_actionBarActionModeDefaultIcon] = onSurface
        currentColors[key_actionBarActionModeDefaultTop] = surfaceVariant
        currentColors[key_listSelector] = (primary and 0x00FFFFFF) or 0x29000000
        currentColors[key_switchTrackChecked] = primary
        currentColors[key_radioBackground] = outline
        currentColors[key_radioBackgroundChecked] = primary
        currentColors[key_progressCircle] = primary
        currentColors[key_fastScrollActive] = primary
        currentColors[key_fastScrollInactive] = outlineVariant
        currentColors[key_fastScrollText] = onPrimary
        currentColors[key_text_RedRegular] = error
        currentColors[key_text_RedBold] = error
        currentColors[key_chats_menuItemIcon] = onSurfaceVariant
        currentColors[key_chats_menuItemText] = onSurface
        currentColors[key_chats_tabSelectedText] = primary
        currentColors[key_chats_tabUnselectedText] = onSurfaceVariant
        currentColors[key_chats_tabUnreadActiveBackground] = primary
        currentColors[key_chats_tabUnreadUnactiveBackground] = outlineVariant
        currentColors[key_chats_verifiedBackground] = primary
        currentColors[key_chats_verifiedCheck] = onPrimary
        currentColors[key_avatar_backgroundBlue] = 0xFF5A62F4.toInt()
        currentColors[key_avatar_backgroundGreen] = 0xFF43B581.toInt()
        currentColors[key_avatar_backgroundOrange] = 0xFFFAA61A.toInt()
        currentColors[key_avatar_backgroundViolet] = 0xFF9B59B6.toInt()
        currentColors[key_avatar_backgroundRed] = 0xFFD30E0E.toInt()
        currentColors[key_profile_tabSelectedLine] = primary
        currentColors[key_profile_tabSelectedText] = primary
        currentColors[key_profile_tabText] = onSurfaceVariant
        currentColors[key_profile_tabSelector] = (primary and 0x00FFFFFF) or 0x29000000

        currentColors[key_sheetItemBackground] = surface
        currentColors[key_dialogTextRed] = error
        currentColors[key_dialogIcon] = onSurfaceVariant

        System.arraycopy(currentColors, 0, defaultColors, 0, NUM_KEYS)
    }

    private fun applyPaintColors() {
        dialogNamePaint.color = textThemePrimary
        dialogNameBoldPaint.color = onSurface
        dialogMessagePaint.color = onSurfaceVariant
        dialogMessageBoldPaint.color = onSurface
        dialogTimePaint.color = onSurfaceVariant
        dialogBadgePaint.color = MobileThemeColors.unreadBadge
        buzzBadgePaint.color = buzzRed
        dialogOnlinePaint.color = onlineGreen
        dialogOnlineBorderPaint.color = channelPanelBg
        dividerPaint.color = outlineVariant
        chatSenderPaint.color = primary
        val fs = LayoutHelper.sp(SharedConfig.fontSize.toFloat())
        chatContentPaint.color = textThemeMessage
        chatContentPaint.textSize = fs
        chatContentOutPaint.color = onPrimary
        chatContentOutPaint.textSize = fs
        chatTimePaint.color = onSurfaceVariant
        chatTimeOutPaint.color = onPrimary and 0x99FFFFFF.toInt()
        chatFileNamePaint.color = onSurface
        chatBuzzTextPaint.color = buzzRed
        chatBuzzTextPaint.textSize = LayoutHelper.sp(SharedConfig.fontSize.toFloat())
        chatBubblePaint.color = surfaceVariant
        chatBubbleOutPaint.color = primary
        systemMessageTextPaint.color = onSurfaceVariant
        settingsNamePaint.color = onSurface
        settingsValuePaint.color = onSurfaceVariant
        headerPaint.color = primary
        buttonTextPaint.color = onPrimary
        buttonBgPaint.color = primary
        radioPaint.color = outline
        radioFillPaint.color = primary
        switchTrackPaint.color = outline
    }

    fun applyFontSize() {
        val fs = LayoutHelper.sp(SharedConfig.fontSize.toFloat())
        chatContentPaint.textSize = fs
        chatContentOutPaint.textSize = fs
        chatBuzzTextPaint.textSize = fs
    }

    val background: Int get() = desktop[Token.BG_THEME_DIRECT_MESSAGE]

    val chatBackground: Int get() = desktop[Token.BG_SECONDARY]

    val surface: Int get() = desktop[Token.BG_SECONDARY]

    val surfaceVariant: Int get() = desktop[Token.BG_PRIMARY]

    val secondaryLight: Int get() = desktop[Token.BG_SURFACE]

    val primary: Int get() = desktop[Token.BUTTON_THEME_PRIMARY]

    val onPrimary: Int get() = Color.WHITE

    val primaryGradiant: Int get() = primaryContainer

    val textStrong: Int get() = desktop.semantic.textPrimary

    val secondaryInputBackground: Int get() = desktop[Token.BG_INPUT_SECONDARY]

    val createClanDiscordBlurple: Int get() = primary

    val createClanCameraGray: Int get() = 0xFF676B73.toInt()

    val communityGuidelineLinkBlue: Int get() = textLink

    val onSurface: Int get() = desktop.semantic.textPrimary

    val onSurfaceVariant: Int get() = desktop.semantic.textSecondary

    val outline: Int get() = desktop[Token.BORDER_PRIMARY]

    val outlineVariant: Int get() = outline

    val dividerColor: Int get() = outlineVariant

    val error: Int get() = 0xFFD30E0E.toInt()
    val redStrong: Int get() = 0xFFC61E1B.toInt()
    val success: Int get() = 0xFF00D4AA.toInt()

    val primaryContainer: Int get() = desktop[Token.BG_ACTIVE_FRIEND_LIST]

    val onPrimaryContainer: Int get() = onSurface

    val connectedColor: Int get() = desktop.semantic.statusOnline
    val connectingColor: Int get() = 0xFFFAA61A.toInt()
    val disconnectedColor: Int get() = 0xFFF04747.toInt()

    val colorSuccess: Int get() = getColor(key_color_success)

    val blurple: Int get() = primary

    val textLink: Int get() = desktop[Token.COLOR_MENTION_HOVER]
    val midnightBlue: Int get() = desktop[Token.MENTION_PRIMARY]
    val mentionHighlightBg: Int get() = desktop[Token.BG_HIGHLIGHT]
    val textRoleLink: Int get() = desktop[Token.COLOR_MENTION_EVRYONE]
    val darkMossGreen: Int get() = desktop[Token.BG_MENTION_EVRYONE]

    val badgeRed: Int get() = desktop.semantic.mentionBadge
    val onlineGreen: Int get() = DesktopThemePalette.onlinePresenceBadge
    val voiceActiveGreen: Int get() = desktop.semantic.statusOnline

    val reactionBgColor: Int get() = (primary and 0x00FFFFFF) or 0x2E000000
    val reactionBorderColor: Int get() = primary

    val qrOverlayDim: Int get() = 0x66000000
    val qrOverlayFrame: Int get() = Color.WHITE
    val qrPillBackground: Int get() = (surface and 0x00FFFFFF) or 0xCC000000.toInt()
    val qrPillContent: Int get() = onSurface
    val qrBrandAccent: Int get() = 0xFFE91E8C.toInt()
    val qrAvatarBackground: Int get() = Color.WHITE

    internal val bottomTabLabelActive: Int
        get() = if (resolvedMode.isLight) 0xFF070709.toInt() else 0xFFDFE0E4.toInt()

    internal val bottomTabLabelInactive: Int
        get() = if (resolvedMode.isLight) 0xFF6C7077.toInt() else 0xFFAEAEAE.toInt()

    val tabIconDetail: Int get() = desktop[Token.BG_ICON_THEME]

    val tabLabelActive: Int get() = desktop[Token.TEXT_SECONDARY]

    val tabLabelInactive: Int get() = desktop.semantic.textSecondary

    val channelPanelBg: Int get() = desktop[Token.BG_THEME_DIRECT_MESSAGE]

    val serverRailBg: Int get() = desktop[Token.BG_PRIMARY]

    val profileSheetAvatarRingColor: Int get() = surface

    val profileSheetBackdropFallback: Int get() = 0xFF808080.toInt()

    val tertiary: Int get() = desktop[Token.BG_PRIMARY]

    val textDisabled: Int get() = desktop.semantic.textMuted

    val charcoal: Int get() = surfaceVariant

    val borderDim: Int get() = outline

    val border: Int get() = outline

    val secondaryWeight: Int get() = desktop[Token.BG_ACTIVE_FRIEND_LIST]

    val colorText: Int get() = desktop.semantic.textSecondary

    val colorAvatarDefault: Int get() = desktop[Token.BG_ICON_THEME]

    val buzzRed: Int get() = 0xFFEA2420.toInt()

    val codeInlineBg: Int get() = desktop[Token.BG_ACTIVE_MEMBER_CHANNEL]

    val codeInlineText: Int get() = desktop[Token.TEXT_SECONDARY]

    val codeFenceBg: Int get() = desktop[Token.BG_MARKDOWN_CODE]

    val codeFenceBorder: Int get() = outline

    companion object {
        val defaultIconTint: Int
            get() = if (::instance.isInitialized) instance.iconTint else ThemeMode.DARK.iconTint

        @Volatile
        lateinit var instance: ThemeColors
            private set

        fun setInstance(theme: ThemeColors) {
            instance = theme
        }

        const val key_windowBackgroundWhite = 0
        const val key_windowBackgroundGray = 1
        const val key_divider = 2
        const val key_shadow = 3

        const val key_actionBarDefault = 10
        const val key_actionBarDefaultIcon = 11
        const val key_actionBarDefaultTitle = 12
        const val key_actionBarDefaultSubtitle = 13
        const val key_actionBarDefaultSelector = 14

        const val key_chats_name = 20
        const val key_chats_nameBold = 21
        const val key_chats_message = 22
        const val key_chats_date = 23
        const val key_chats_unreadCounter = 24
        const val key_chats_unreadCounterText = 25
        const val key_chats_onlineCircle = 26

        const val key_chat_inBubble = 30
        const val key_chat_outBubble = 31
        const val key_chat_inText = 32
        const val key_chat_outText = 33
        const val key_chat_inTime = 34
        const val key_chat_outTime = 35
        const val key_chat_senderName = 36
        const val key_chat_linkColor = 37

        const val key_settings_name = 40
        const val key_settings_value = 41
        const val key_settings_header = 42
        const val key_settings_icon = 43

        const val key_button_text = 50
        const val key_button_background = 51
        const val key_radio_outline = 52
        const val key_radio_fill = 53
        const val key_switch_track = 54
        const val key_switch_thumb = 55
        const val key_checkbox_check = 56

        const val key_text_primary = 60
        const val key_text_secondary = 61
        const val key_icon_primary = 62
        const val key_icon_secondary = 63
        const val key_color_primary = 64
        const val key_color_error = 65
        const val key_primaryContainer = 66
        const val key_onPrimaryContainer = 67
        const val key_connectedColor = 68
        const val key_connectingColor = 69
        const val key_disconnectedColor = 70

        const val key_dialogBackground = 71
        const val key_dialogTextBlack = 72
        const val key_dialogButton = 73
        const val key_dialogButtonRedText = 74
        const val key_dialogRoundCheckBox = 75
        const val key_dialogGrayLine = 76
        const val key_sheetBackground = 77
        const val key_sheetTitle = 78
        const val key_sheetHandle = 79

        const val key_actionBarActionModeDefault = 80
        const val key_actionBarActionModeDefaultIcon = 81
        const val key_actionBarActionModeDefaultTop = 82
        const val key_listSelector = 83
        const val key_switchTrackChecked = 84
        const val key_radioBackground = 85
        const val key_radioBackgroundChecked = 86
        const val key_progressCircle = 87
        const val key_fastScrollActive = 88
        const val key_fastScrollInactive = 89
        const val key_fastScrollText = 90
        const val key_text_RedRegular = 91
        const val key_text_RedBold = 92
        const val key_chats_menuItemIcon = 93
        const val key_chats_menuItemText = 94
        const val key_chats_tabSelectedText = 95
        const val key_chats_tabUnselectedText = 96
        const val key_chats_tabUnreadActiveBackground = 97
        const val key_chats_tabUnreadUnactiveBackground = 98
        const val key_chats_verifiedBackground = 99
        const val key_chats_verifiedCheck = 100
        const val key_avatar_backgroundBlue = 101
        const val key_avatar_backgroundGreen = 102
        const val key_avatar_backgroundOrange = 103
        const val key_avatar_backgroundViolet = 104
        const val key_avatar_backgroundRed = 105
        const val key_profile_tabSelectedLine = 106
        const val key_profile_tabSelectedText = 107
        const val key_profile_tabText = 108
        const val key_profile_tabSelector = 109

        const val key_sheetItemBackground = 110
        const val key_dialogTextRed = 111
        const val key_dialogIcon = 112
        const val key_color_success = 113

        const val NUM_KEYS = 114
    }
}
