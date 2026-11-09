package com.mezon.mobile.home.call

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.voice.VoiceChrome
import com.mezon.mobile.home.voice.VoiceStyleCircleButton
import com.mezon.mobile.ui.cells.MezonIcon

class CallControlBar(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    interface Delegate {
        fun onSpeakerClicked()
        fun onEndCallClicked()
        fun onMicClicked()
    }

    var delegate: Delegate? = null

    private val speakerBtn: VoiceStyleCircleButton
    private val micBtn: VoiceStyleCircleButton
    private val endBtn: VoiceStyleCircleButton

    var isSpeakerActive = false
        set(value) {
            field = value
            applySpeakerUi()
        }

    var isMicActive = true
        set(value) {
            field = value
            applyMicUi()
        }

    companion object {
        private val BUTTON_SIZE = LayoutHelper.dp(50)
        private val BUTTON_GAP = LayoutHelper.dp(10)
        private val PILL_H_PADDING = LayoutHelper.dp(10)
        private val PILL_V_PADDING = LayoutHelper.dp(4)
        private val INNER_PADDING = LayoutHelper.dp(6)
        private val PILL_RADIUS = LayoutHelper.dp(80).toFloat()
    }

    init {
        val pillBg = GradientDrawable().apply {
            cornerRadius = PILL_RADIUS
            setColor(themeColors.channelPanelBg)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = pillBg
            setPadding(
                PILL_H_PADDING + INNER_PADDING,
                PILL_V_PADDING + INNER_PADDING,
                PILL_H_PADDING + INNER_PADDING,
                PILL_V_PADDING + INNER_PADDING
            )
        }

        val btnBorder = themeColors.textDisabled
        val tertiary = themeColors.tertiary
        val defaultTint = if (themeColors.usesLegacyDarkColors) themeColors.tabLabelActive else themeColors.onSurface

        speakerBtn = VoiceStyleCircleButton(context, MezonIcon.voiceLowIcon, tertiary, btnBorder, defaultTint).apply {
            setOnClickListener { delegate?.onSpeakerClicked() }
        }
        row.addView(speakerBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))

        endBtn = VoiceStyleCircleButton(
            context,
            MezonIcon.callCancelIcon,
            VoiceChrome.RED_STRONG,
            0,
            0xFFFFFFFF.toInt(),
            VoiceStyleCircleButton.endCallIconSizePx(context)
        ).apply {
            setOnClickListener { delegate?.onEndCallClicked() }
        }
        row.addView(endBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply { marginStart = BUTTON_GAP })

        micBtn = VoiceStyleCircleButton(context, MezonIcon.microphoneSlashIcon, tertiary, btnBorder, defaultTint).apply {
            setOnClickListener { delegate?.onMicClicked() }
        }
        row.addView(micBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply { marginStart = BUTTON_GAP })

        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        applySpeakerUi()
        applyMicUi()
    }

    private fun applySpeakerUi() {
        speakerBtn.updateBgColor(controlFill(isSpeakerActive))
        if (isSpeakerActive) {
            speakerBtn.updateIcon(MezonIcon.voiceWaveDoubleIcon)
            speakerBtn.updateIconTint(if (themeColors.usesLegacyDarkColors) themeColors.tabIconActivePrimary else themeColors.surface)
        } else {
            speakerBtn.updateIcon(MezonIcon.voiceWaveIcon)
            speakerBtn.updateIconTint(if (themeColors.usesLegacyDarkColors) themeColors.tabLabelActive else themeColors.onSurface)
        }
    }

    private fun applyMicUi() {
        micBtn.updateIcon(if (isMicActive) MezonIcon.microphoneIcon else MezonIcon.microphoneSlashIcon)
        micBtn.updateBgColor(controlFill(isMicActive))
        micBtn.updateIconTint(if (themeColors.usesLegacyDarkColors) themeColors.tabLabelActive else if (isMicActive) themeColors.surface else themeColors.onSurface)
    }

    private fun controlFill(active: Boolean): Int = when {
        themeColors.usesLegacyDarkColors -> themeColors.tertiary
        active -> themeColors.onSurface
        else -> (themeColors.mobile.secondaryWeight and 0x00FFFFFF) or 0x8C000000.toInt()
    }
}
