package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class VoiceHeaderView(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    companion object {
        private val BUTTON_SIZE = LayoutHelper.dp(40)
        private val BUTTON_RADIUS = LayoutHelper.dp(40).toFloat()
        private val ICON_SIZE = LayoutHelper.dp(20)
        private val LEFT_GAP = LayoutHelper.dp(20)
        private val RIGHT_GAP = LayoutHelper.dp(10)
        private val H_PADDING = LayoutHelper.dp(10)
    }

    var onMinimizeClick: (() -> Unit)? = null
    var onSwitchCameraClick: (() -> Unit)? = null
    var onAudioOutputClick: ((View) -> Unit)? = null
    var onMoreClick: ((View) -> Unit)? = null

    private val channelNameText: TextView

    init {
        setPadding(H_PADDING, 0, H_PADDING, 0)

        val leftContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val minimizeBtn = createCircleButton(context, MezonIcon.chevronDownSmallIcon) {
            onMinimizeClick?.invoke()
        }
        leftContainer.addView(minimizeBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))

        channelNameText = TextView(context).apply {
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
        val nameLP = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = LEFT_GAP
        }
        leftContainer.addView(channelNameText, nameLP)

        addView(leftContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            rightMargin = LayoutHelper.dp(10) + (BUTTON_SIZE + RIGHT_GAP) * 3
        })

        val rightContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val switchCameraBtn = createCircleButton(context, MezonIcon.cameraFront) {
            onSwitchCameraClick?.invoke()
        }
        rightContainer.addView(switchCameraBtn, LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE))

        val audioOutputBtn = createCircleButton(context, MezonIcon.channelVoice) { v ->
            onAudioOutputClick?.invoke(v)
        }
        val audioLP = LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply {
            marginStart = RIGHT_GAP
        }
        rightContainer.addView(audioOutputBtn, audioLP)

        val moreBtn = createCircleButton(context, MezonIcon.moreVerticalIcon) { v ->
            onMoreClick?.invoke(v)
        }
        val moreLP = LinearLayout.LayoutParams(BUTTON_SIZE, BUTTON_SIZE).apply {
            marginStart = RIGHT_GAP
        }
        rightContainer.addView(moreBtn, moreLP)

        addView(rightContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    fun setChannelName(name: String) {
        channelNameText.text = name
    }

    private fun createCircleButton(context: Context, icon: MezonIcon, onClick: (View) -> Unit): FrameLayout {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(themeColors.channelPanelBg)
            setStroke(LayoutHelper.dp(1), themeColors.border)
        }
        return FrameLayout(context).apply {
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)

            val iconView = ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            addView(iconView, LayoutParams(ICON_SIZE, ICON_SIZE, Gravity.CENTER))
        }
    }
}
