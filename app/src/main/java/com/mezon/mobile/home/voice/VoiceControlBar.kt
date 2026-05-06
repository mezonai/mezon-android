package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class VoiceControlBar(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    companion object {
        private val BUTTON_SIZE = LayoutHelper.dp(50)
        private val RAISE_HAND_ICON_SIZE = LayoutHelper.dp(32)
        private val BUTTON_GAP = LayoutHelper.dp(10)
        private val PILL_H_PADDING = LayoutHelper.dp(10)
        private val PILL_V_PADDING = LayoutHelper.dp(4)
        private val INNER_PADDING = LayoutHelper.dp(6)
        private val PILL_RADIUS = LayoutHelper.dp(80).toFloat()
        private val RAISE_HAND_ACTIVE = 0xFFEFBC39.toInt()
    }

    var onCameraToggle: ((enabled: Boolean) -> Unit)? = null
    var onMicToggle: ((enabled: Boolean) -> Unit)? = null
    var onChatClick: (() -> Unit)? = null
    var onRaiseHandClick: (() -> Unit)? = null
    var onEndCallClick: (() -> Unit)? = null

    private var cameraEnabled = false
    private var micEnabled = false

    private val cameraButton: VoiceStyleCircleButton
    private val micButton: VoiceStyleCircleButton
    private val chatButton: VoiceStyleCircleButton
    private val raiseHandButton: VoiceStyleCircleButton
    private val endCallButton: VoiceStyleCircleButton

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

        val defaultTint = themeColors.tabLabelActive
        val btnBorder = themeColors.textDisabled

        cameraButton = VoiceStyleCircleButton(context, MezonIcon.videoSlashIcon, themeColors.tertiary, btnBorder, defaultTint).apply {
            setOnClickListener {
                cameraEnabled = !cameraEnabled
                updateIcon(if (cameraEnabled) MezonIcon.videoIcon else MezonIcon.videoSlashIcon)
                onCameraToggle?.invoke(cameraEnabled)
            }
        }
        addButton(row, cameraButton, false)

        micButton = VoiceStyleCircleButton(context, MezonIcon.microphoneSlashIcon, themeColors.tertiary, btnBorder, defaultTint).apply {
            setOnClickListener {
                micEnabled = !micEnabled
                updateIcon(if (micEnabled) MezonIcon.microphoneIcon else MezonIcon.microphoneSlashIcon)
                onMicToggle?.invoke(micEnabled)
            }
        }
        addButton(row, micButton, true)

        chatButton = VoiceStyleCircleButton(context, MezonIcon.notificationTabMessages, themeColors.tertiary, btnBorder, defaultTint).apply {
            setOnClickListener { onChatClick?.invoke() }
            setApplyTint(false)
        }
        addButton(row, chatButton, true)

        raiseHandButton = VoiceStyleCircleButton(
            context,
            MezonIcon.raiseHandIcon,
            themeColors.tertiary,
            btnBorder,
            defaultTint,
            RAISE_HAND_ICON_SIZE
        ).apply {
            setOnClickListener { onRaiseHandClick?.invoke() }
        }
        addButton(row, raiseHandButton, true)

        endCallButton = VoiceStyleCircleButton(
            context,
            MezonIcon.callCancelIcon,
            VoiceChrome.RED_STRONG,
            0,
            0xFFFFFFFF.toInt(),
            VoiceStyleCircleButton.END_CALL_ICON_SIZE
        ).apply {
            setOnClickListener { onEndCallClick?.invoke() }
        }
        addButton(row, endCallButton, true)

        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun addButton(row: LinearLayout, button: VoiceStyleCircleButton, addGap: Boolean) {
        val lp = LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE)
        if (addGap) lp.marginStart = BUTTON_GAP
        row.addView(button, lp)
    }

    fun setGroupCallMode(isGroupCall: Boolean) {
        val visibility = if (isGroupCall) GONE else VISIBLE
        chatButton.visibility = visibility
        raiseHandButton.visibility = visibility
    }

    fun setMicEnabled(enabled: Boolean) {
        if (micEnabled == enabled) return
        micEnabled = enabled
        micButton.updateIcon(if (enabled) MezonIcon.microphoneIcon else MezonIcon.microphoneSlashIcon)
    }

    fun setCameraEnabled(enabled: Boolean) {
        if (cameraEnabled == enabled) return
        cameraEnabled = enabled
        cameraButton.updateIcon(if (enabled) MezonIcon.videoIcon else MezonIcon.videoSlashIcon)
    }

    fun setRaiseHandActive(active: Boolean) {
        if (active) {
            raiseHandButton.updateBgColor(themeColors.tertiary)
            raiseHandButton.updateIconTint(RAISE_HAND_ACTIVE)
        } else {
            raiseHandButton.updateBgColor(themeColors.tertiary)
            raiseHandButton.updateIconTint(themeColors.tabLabelActive)
        }
    }
}
