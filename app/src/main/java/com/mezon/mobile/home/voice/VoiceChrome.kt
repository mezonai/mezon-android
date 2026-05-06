package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.MezonIcon

object VoiceChrome {
    val RED_STRONG: Int get() = 0xFFC61E1B.toInt()
}

class VoiceStyleCircleButton(
    context: Context,
    private var icon: MezonIcon,
    bgColor: Int,
    borderColor: Int,
    initialTint: Int,
    private val iconSize: Int
) : FrameLayout(context) {

    companion object {
        val DEFAULT_ICON_SIZE = LayoutHelper.dp(20)
        val END_CALL_ICON_SIZE = LayoutHelper.dp(24)
    }

    constructor(
        context: Context,
        icon: MezonIcon,
        bgColor: Int,
        borderColor: Int,
        initialTint: Int
    ) : this(context, icon, bgColor, borderColor, initialTint, DEFAULT_ICON_SIZE)

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
