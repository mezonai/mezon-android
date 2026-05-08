package com.mezon.mobile.home.call

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.voice.VoiceStyleCircleButton
import com.mezon.mobile.ui.cells.MezonIcon

class DmCallHeaderView(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    companion object {
        private val BUTTON_SIZE = LayoutHelper.dp(40)
        private val LEFT_GAP = LayoutHelper.dp(20)
        private val RIGHT_GAP = LayoutHelper.dp(10)
        private val H_PADDING = LayoutHelper.dp(10)
    }

    var onCloseClick: (() -> Unit)? = null
    var onSwitchCameraClick: (() -> Unit)? = null
    var onVideoToggleClick: (() -> Unit)? = null

    private val peerNameText: TextView
    private val closeButton: VoiceStyleCircleButton
    private val switchCameraButton: VoiceStyleCircleButton
    private val videoButton: VoiceStyleCircleButton

    init {
        setPadding(H_PADDING, 0, H_PADDING, 0)

        val defaultTint = themeColors.tabLabelActive
        val btnBorder = themeColors.textDisabled

        val leftWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        closeButton = VoiceStyleCircleButton(context, MezonIcon.closeIcon, themeColors.tertiary, btnBorder, defaultTint).apply {
            setOnClickListener { onCloseClick?.invoke() }
        }
        leftWrap.addView(closeButton, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))

        peerNameText = TextView(context).apply {
            setTextColor(themeColors.colorText)
            textSize = 20f
            typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(Typeface.DEFAULT, 600, false)
            } else {
                Typeface.DEFAULT_BOLD
            }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        leftWrap.addView(peerNameText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = LEFT_GAP
        })

        addView(leftWrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            rightMargin = LayoutHelper.dp(10) + (BUTTON_SIZE + RIGHT_GAP) * 2
        })

        val rightWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        switchCameraButton = VoiceStyleCircleButton(context, MezonIcon.cameraFront, themeColors.tertiary, btnBorder, defaultTint).apply {
            setOnClickListener { onSwitchCameraClick?.invoke() }
            visibility = View.GONE
        }
        videoButton = VoiceStyleCircleButton(context, MezonIcon.videoSlashIcon, themeColors.tertiary, btnBorder, defaultTint).apply {
            setOnClickListener { onVideoToggleClick?.invoke() }
        }

        rightWrap.addView(switchCameraButton, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))
        rightWrap.addView(videoButton, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply {
            marginStart = RIGHT_GAP
        })

        addView(rightWrap, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    fun setPeerName(name: String) {
        peerNameText.text = name
    }

    fun setSwitchCameraVisible(visible: Boolean) {
        switchCameraButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setLocalVideoEnabled(enabled: Boolean) {
        videoButton.updateIcon(if (enabled) MezonIcon.videoIcon else MezonIcon.videoSlashIcon)
    }
}
