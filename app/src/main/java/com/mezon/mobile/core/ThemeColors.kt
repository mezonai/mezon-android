package com.mezon.mobile.core

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.mezon.mobile.ui.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeColors @Inject constructor() {

    @Volatile
    var currentMode: ThemeMode = ThemeMode.DARK
        private set

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
        textSize = LayoutHelper.sp(11f)
    }
    val dialogBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val dialogBadgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(11f)
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    val dialogOnlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val dialogOnlineBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2).toFloat()
    }
    val dividerPaint = Paint()

    val chatSenderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(12f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val chatContentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(15f)
    }
    val chatTimePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(11f)
    }
    val chatBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    val settingsNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(16f)
    }
    val settingsValuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
    }
    val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
        typeface = Typeface.DEFAULT_BOLD
    }
    val buttonTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
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

    init {
        applyPaintColors()
    }

    fun setTheme(mode: ThemeMode) {
        currentMode = mode
        applyPaintColors()
    }

    private fun applyPaintColors() {
        dialogNamePaint.color = onSurface
        dialogNameBoldPaint.color = onSurface
        dialogMessagePaint.color = onSurfaceVariant
        dialogMessageBoldPaint.color = onSurface
        dialogTimePaint.color = onSurfaceVariant
        dialogBadgePaint.color = badgeRed
        dialogOnlinePaint.color = onlineGreen
        dialogOnlineBorderPaint.color = surface
        dividerPaint.color = outlineVariant
        chatSenderPaint.color = primary
        chatContentPaint.color = onSurfaceVariant
        chatTimePaint.color = onSurfaceVariant
        chatBubblePaint.color = surfaceVariant
        settingsNamePaint.color = onSurface
        settingsValuePaint.color = onSurfaceVariant
        headerPaint.color = primary
        buttonTextPaint.color = onPrimary
        buttonBgPaint.color = primary
        radioPaint.color = outline
        radioFillPaint.color = primary
        switchTrackPaint.color = outline
    }

    val background: Int get() = when (currentMode) {
        ThemeMode.LIGHT -> 0xFFFFFBFE.toInt()
        ThemeMode.DARK -> 0xFF1C1B1F.toInt()
        ThemeMode.ABYSS -> 0xFF0A0A12.toInt()
        ThemeMode.SYSTEM -> 0xFF1C1B1F.toInt()
    }

    val surface: Int get() = when (currentMode) {
        ThemeMode.LIGHT -> 0xFFFFFBFE.toInt()
        ThemeMode.DARK -> 0xFF1C1B1F.toInt()
        ThemeMode.ABYSS -> 0xFF0D0D18.toInt()
        ThemeMode.SYSTEM -> 0xFF1C1B1F.toInt()
    }

    val surfaceVariant: Int get() = when (currentMode) {
        ThemeMode.LIGHT -> 0xFFE7E0EC.toInt()
        ThemeMode.DARK -> 0xFF49454F.toInt()
        ThemeMode.ABYSS -> 0xFF1A1A2E.toInt()
        ThemeMode.SYSTEM -> 0xFF49454F.toInt()
    }

    val primary: Int get() = when (currentMode) {
        ThemeMode.LIGHT -> 0xFF5A62F4.toInt()
        ThemeMode.DARK -> 0xFF5A62F4.toInt()
        ThemeMode.ABYSS -> 0xFF5A62F4.toInt()
        ThemeMode.SYSTEM -> 0xFF5A62F4.toInt()
    }

    val onPrimary: Int get() = Color.WHITE

    val onSurface: Int get() = when (currentMode) {
        ThemeMode.LIGHT -> 0xFF1C1B1F.toInt()
        ThemeMode.DARK -> 0xFFE6E1E5.toInt()
        ThemeMode.ABYSS -> 0xFFE6E1E5.toInt()
        ThemeMode.SYSTEM -> 0xFFE6E1E5.toInt()
    }

    val onSurfaceVariant: Int get() = when (currentMode) {
        ThemeMode.LIGHT -> 0xFF49454F.toInt()
        ThemeMode.DARK -> 0xFFCAC4D0.toInt()
        ThemeMode.ABYSS -> 0xFFCAC4D0.toInt()
        ThemeMode.SYSTEM -> 0xFFCAC4D0.toInt()
    }

    val outline: Int get() = when (currentMode) {
        ThemeMode.LIGHT -> 0xFF79747E.toInt()
        ThemeMode.DARK -> 0xFF938F99.toInt()
        ThemeMode.ABYSS -> 0xFF2A2A40.toInt()
        ThemeMode.SYSTEM -> 0xFF938F99.toInt()
    }

    val outlineVariant: Int get() = when (currentMode) {
        ThemeMode.LIGHT -> 0xFFCAC4D0.toInt()
        ThemeMode.DARK -> 0xFF49454F.toInt()
        ThemeMode.ABYSS -> 0xFF1A1A2E.toInt()
        ThemeMode.SYSTEM -> 0xFF49454F.toInt()
    }

    val error: Int get() = 0xFFD30E0E.toInt()

    val primaryContainer: Int get() = when (currentMode) {
        ThemeMode.LIGHT -> 0xFFEADDFF.toInt()
        ThemeMode.DARK -> 0xFF4F378B.toInt()
        ThemeMode.ABYSS -> 0xFF2A2A50.toInt()
        ThemeMode.SYSTEM -> 0xFF4F378B.toInt()
    }

    val onPrimaryContainer: Int get() = when (currentMode) {
        ThemeMode.LIGHT -> 0xFF21005D.toInt()
        ThemeMode.DARK -> 0xFFEADDFF.toInt()
        ThemeMode.ABYSS -> 0xFFEADDFF.toInt()
        ThemeMode.SYSTEM -> 0xFFEADDFF.toInt()
    }

    val connectedColor: Int get() = 0xFF43B581.toInt()
    val connectingColor: Int get() = 0xFFFAA61A.toInt()
    val disconnectedColor: Int get() = 0xFFF04747.toInt()

    val blurple: Int get() = 0xFF5A62F4.toInt()

    val badgeRed: Int get() = 0xFFD30E0E.toInt()
    val onlineGreen: Int get() = 0xFF43B581.toInt()
}
