package com.mezon.mobile.home.call

import android.content.Context
import android.graphics.Typeface
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.voice.VoiceChrome
import com.mezon.mobile.home.voice.VoiceStyleCircleButton
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon

class CallingOverlay(context: Context) : FrameLayout(context) {

    interface Delegate {
        fun onAcceptClicked()
        fun onDeclineClicked()
    }

    var delegate: Delegate? = null

    private val tc = ThemeColors.instance
    private val avatarView: AvatarView
    private val titleTv: TextView
    private val nameTv: TextView

    init {
        val corner = LayoutHelper.dp(12).toFloat()
        background = GradientDrawable().apply {
            setColor(tc.serverRailBg)
            setStroke(LayoutHelper.dp(1), tc.textDisabled)
            this.cornerRadius = corner
        }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        elevation = LayoutHelper.dpf(24f)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(8), LayoutHelper.dp(10), LayoutHelper.dp(8))
        }

        val avatarPx = LayoutHelper.dp(40)
        avatarView = AvatarView(context).apply {
            setSizeDp(40)
            setRoundRadius(20f)
        }
        row.addView(avatarView, LinearLayout.LayoutParams(avatarPx, avatarPx))

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(10), 0, 0, 0)
        }
        titleTv = TextView(context).apply {
            text = "Incoming Call"
            setTextColor(tc.colorText)
            textSize = 14f
        }
        nameTv = TextView(context).apply {
            setTextColor(tc.textStrong)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        textCol.addView(titleTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        textCol.addView(nameTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val pillBg = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(80).toFloat()
            setColor(tc.channelPanelBg)
        }
        val pillWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(6), LayoutHelper.dp(8), LayoutHelper.dp(6))
        }

        val btnDim = LayoutHelper.dp(44)

        pillWrap.addView(
            VoiceStyleCircleButton(context, MezonIcon.callCancelIcon, VoiceChrome.RED_STRONG, 0, 0xFFFFFFFF.toInt(), VoiceStyleCircleButton.endCallIconSizePx(context)).apply {
                setOnClickListener { delegate?.onDeclineClicked() }
            },
            LinearLayout.LayoutParams(btnDim, btnDim)
        )

        pillWrap.addView(
            VoiceStyleCircleButton(context, MezonIcon.phoneCallIcon, tc.onlineGreen, 0, 0xFFFFFFFF.toInt(), VoiceStyleCircleButton.endCallIconSizePx(context)).apply {
                setOnClickListener { delegate?.onAcceptClicked() }
            },
            LinearLayout.LayoutParams(btnDim, btnDim).apply { marginStart = LayoutHelper.dp(12) }
        )

        row.addView(pillWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setCallerInfo(name: String, avatarUrl: String?) {
        nameTv.text = name
        avatarView.setInfo(0L, name)
        avatarView.setImageUrl(avatarUrl)
    }

    fun show() {
        visibility = View.VISIBLE
    }

    fun dismiss() {
        visibility = View.GONE
    }
}
