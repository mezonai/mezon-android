package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
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
        private val DEFAULT_ICON_SIZE = LayoutHelper.dp(20)
        private val RAISE_HAND_ICON_SIZE = LayoutHelper.dp(32)
        private val END_CALL_ICON_SIZE = LayoutHelper.dp(24)
        private val BUTTON_GAP = LayoutHelper.dp(10)
        private val PILL_H_PADDING = LayoutHelper.dp(10)
        private val PILL_V_PADDING = LayoutHelper.dp(4)
        private val INNER_PADDING = LayoutHelper.dp(6)
        private val PILL_RADIUS = LayoutHelper.dp(80).toFloat()
        private val BORDER_WIDTH = LayoutHelper.dp(1).toFloat() * 0.5f
        private val RED_STRONG = 0xFFC61E1B.toInt()
        private val RAISE_HAND_ACTIVE = 0xFFEFBC39.toInt()
    }

    var onCameraToggle: ((enabled: Boolean) -> Unit)? = null
    var onMicToggle: ((enabled: Boolean) -> Unit)? = null
    var onChatClick: (() -> Unit)? = null
    var onRaiseHandClick: (() -> Unit)? = null
    var onEndCallClick: (() -> Unit)? = null

    private var cameraEnabled = false
    private var micEnabled = false

    private val cameraButton: ControlButton
    private val micButton: ControlButton
    private val chatButton: ControlButton
    private val raiseHandButton: ControlButton
    private val endCallButton: ControlButton

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

        cameraButton = ControlButton(context, MezonIcon.videoSlashIcon, themeColors.tertiary, btnBorder, defaultTint).apply {
            setOnClickListener {
                cameraEnabled = !cameraEnabled
                updateIcon(if (cameraEnabled) MezonIcon.videoIcon else MezonIcon.videoSlashIcon)
                onCameraToggle?.invoke(cameraEnabled)
            }
        }
        addButton(row, cameraButton, false)

        micButton = ControlButton(context, MezonIcon.microphoneSlashIcon, themeColors.tertiary, btnBorder, defaultTint).apply {
            setOnClickListener {
                micEnabled = !micEnabled
                updateIcon(if (micEnabled) MezonIcon.microphoneIcon else MezonIcon.microphoneSlashIcon)
                onMicToggle?.invoke(micEnabled)
            }
        }
        addButton(row, micButton, true)

        chatButton = ControlButton(context, MezonIcon.notificationTabMessages, themeColors.tertiary, btnBorder, defaultTint).apply {
            setOnClickListener { onChatClick?.invoke() }
            setApplyTint(false)
        }
        addButton(row, chatButton, true)

        raiseHandButton = ControlButton(
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

        endCallButton = ControlButton(
            context,
            MezonIcon.callCancelIcon,
            RED_STRONG,
            0,
            0xFFFFFFFF.toInt(),
            END_CALL_ICON_SIZE
        ).apply {
            setOnClickListener { onEndCallClick?.invoke() }
        }
        addButton(row, endCallButton, true)

        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun addButton(row: LinearLayout, button: ControlButton, addGap: Boolean) {
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

    class ControlButton(
        context: Context,
        private var icon: MezonIcon,
        bgColor: Int,
        borderColor: Int,
        initialTint: Int,
        private val iconSize: Int = DEFAULT_ICON_SIZE
    ) : FrameLayout(context) {

        private val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
            if (borderColor != 0) {
                setStroke((LayoutHelper.dp(1).toFloat() * 0.5f).toInt().coerceAtLeast(1), borderColor)
            }
        }
        private val iconView: ImageView
        private var iconTint = initialTint
        private var applyTint = true

        init {
            background = bgDrawable
            isClickable = true
            isFocusable = true

            iconView = ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context).apply {
                    if (applyTint) {
                        colorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
                    }
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            addView(iconView, LayoutParams(iconSize, iconSize, Gravity.CENTER))
            applyVoiceButtonPressFeedback()
        }

        fun updateIcon(newIcon: MezonIcon) {
            icon = newIcon
            iconView.setImageDrawable(icon.getDrawable(context).apply {
                if (applyTint) {
                    colorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
                }
            })
        }

        fun updateBgColor(color: Int) {
            bgDrawable.setColor(color)
        }

        fun updateIconTint(color: Int) {
            iconTint = color
            if (!applyTint) return
            iconView.setImageDrawable(icon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
            })
        }

        fun setApplyTint(enabled: Boolean) {
            if (applyTint == enabled) return
            applyTint = enabled
            iconView.setImageDrawable(icon.getDrawable(context).apply {
                if (applyTint) {
                    colorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
                }
            })
        }
    }
}
