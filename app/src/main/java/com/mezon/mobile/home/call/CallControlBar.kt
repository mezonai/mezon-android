package com.mezon.mobile.home.call

import com.mezon.mobile.R
import com.mezon.mobile.ui.cells.MezonIcon
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.voice.VoiceChrome
import com.mezon.mobile.home.voice.VoiceStyleCircleButton
import com.mezon.mobile.ui.theme.ThemeMode

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
        private const val ACTIVE_SPEAKER_TINT = 0xFF939BF6.toInt()
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
        val defaultTint = themeColors.onSurface

        speakerBtn = VoiceStyleCircleButton(
            context,
            R.drawable.mz_speaker_low_24,
            tertiary,
            btnBorder,
            defaultTint,
            VoiceStyleCircleButton.defaultIconSizePx(context),
            fixedIconLoader = { iconContext, icon ->
                if (icon == R.drawable.mz_speaker_24) {
                    MezonIcon.loadDrawable(iconContext, icon, ACTIVE_SPEAKER_TINT)
                } else {
                    MezonIcon.loadDrawable(iconContext, icon, themeColors.iconTint)
                }
            }
        ).apply {
            setOnClickListener { delegate?.onSpeakerClicked() }
        }
        row.addView(speakerBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))

        endBtn = VoiceStyleCircleButton(
            context,
            R.drawable.mz_call_cancel_24,
            VoiceChrome.RED_STRONG,
            0,
            0xFFFFFFFF.toInt(),
            VoiceStyleCircleButton.endCallIconSizePx(context),
            fixedIconLoader = { iconContext, icon ->
                MezonIcon.loadDrawable(iconContext, icon, ThemeMode.DARK.iconTint)
            }
        ).apply {
            setOnClickListener { delegate?.onEndCallClicked() }
        }
        row.addView(endBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply { marginStart = BUTTON_GAP })

        micBtn = VoiceStyleCircleButton(context, R.drawable.mz_mic_off_20, tertiary, btnBorder, defaultTint).apply {
            setOnClickListener { delegate?.onMicClicked() }
        }
        row.addView(micBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply { marginStart = BUTTON_GAP })

        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        applySpeakerUi()
        applyMicUi()
    }

    private fun applySpeakerUi() {
        speakerBtn.updateBgColor(controlFill())
        speakerBtn.updateIcon(if (isSpeakerActive) R.drawable.mz_speaker_24 else R.drawable.mz_speaker_low_24)
    }

    private fun applyMicUi() {
        micBtn.updateIcon(if (isMicActive) R.drawable.mz_mic_20 else R.drawable.mz_mic_off_20)
        micBtn.updateBgColor(controlFill())
        micBtn.updateIconTint(themeColors.onSurface)
    }

    private fun controlFill(): Int =
        (themeColors.mobile.secondaryWeight and 0x00FFFFFF) or 0x8C000000.toInt()
}
