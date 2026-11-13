package com.mezon.mobile.home.call

import com.mezon.mobile.R
import com.mezon.mobile.ui.cells.MezonIcon
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.voice.VoiceChrome
import com.mezon.mobile.home.voice.VoiceStyleCircleButton
import com.mezon.mobile.ui.theme.ThemeMode

class CallControlBar(
    context: Context,
    private val themeColors: ThemeColors,
    private val showLabels: Boolean = false
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
    private val row: LinearLayout
    private val pillBg: GradientDrawable?
    private val speakerItem: View
    private val micItem: View
    private val micLabel: TextView?

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
        pillBg = if (showLabels) {
            null
        } else {
            GradientDrawable().apply {
                cornerRadius = PILL_RADIUS
                setColor(themeColors.channelPanelBg)
            }
        }

        row = LinearLayout(context).apply {
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
            contentDescription = context.getString(R.string.call_speaker_action)
            setOnClickListener { delegate?.onSpeakerClicked() }
        }
        speakerItem = addControl(speakerBtn, createLabel(R.string.call_speaker_action), 0)

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
            contentDescription = context.getString(R.string.call_end_action)
            setOnClickListener { delegate?.onEndCallClicked() }
        }
        addControl(endBtn, createLabel(R.string.call_end_action), BUTTON_GAP)

        micBtn = VoiceStyleCircleButton(context, R.drawable.mz_mic_off_20, tertiary, btnBorder, defaultTint).apply {
            setOnClickListener { delegate?.onMicClicked() }
        }
        micLabel = createLabel(R.string.call_mute_action)
        micItem = addControl(micBtn, micLabel, BUTTON_GAP)

        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        applySpeakerUi()
        applyMicUi()
    }

    fun setConnecting(connecting: Boolean) {
        speakerItem.visibility = if (connecting) INVISIBLE else VISIBLE
        micItem.visibility = speakerItem.visibility
        row.background = if (connecting) null else pillBg
    }

    private fun createLabel(label: Int): TextView? {
        if (!showLabels) return null
        return TextView(context).apply {
            setText(label)
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(themeColors.onSurface)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun addControl(button: VoiceStyleCircleButton, label: TextView?, gap: Int): View {
        if (label == null) {
            row.addView(button, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply { marginStart = gap })
            return button
        }
        val buttonSize = LayoutHelper.dp(56)
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(button, LinearLayout.LayoutParams(buttonSize, buttonSize))
            label.setOnClickListener { button.performClick() }
            label.isFocusable = false
            addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(5)
            })
        }
        row.addView(item, LinearLayout.LayoutParams(LayoutHelper.dp(72), LayoutParams.WRAP_CONTENT))
        return item
    }

    private fun applySpeakerUi() {
        speakerBtn.isSelected = isSpeakerActive
        speakerBtn.updateBgColor(if (showLabels && isSpeakerActive) themeColors.primaryContainer else controlFill())
        speakerBtn.updateIcon(if (isSpeakerActive) R.drawable.mz_speaker_24 else R.drawable.mz_speaker_low_24)
    }

    private fun applyMicUi() {
        val action = if (isMicActive) R.string.call_mute_action else R.string.call_unmute_action
        micBtn.contentDescription = context.getString(action)
        micLabel?.setText(action)
        micBtn.updateIcon(if (isMicActive) R.drawable.mz_mic_20 else R.drawable.mz_mic_off_20)
        micBtn.updateBgColor(controlFill())
        micBtn.updateIconTint(themeColors.onSurface)
    }

    private fun controlFill(): Int =
        (themeColors.mobile.secondaryWeight and 0x00FFFFFF) or 0x8C000000.toInt()
}
