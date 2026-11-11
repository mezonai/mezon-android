package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import com.mezon.mobile.ui.cells.MezonIcon
import kotlin.math.roundToInt

object VoiceChrome {
    val RED_STRONG: Int get() = 0xFFC61E1B.toInt()
}

class VoiceStyleCircleButton(
    context: Context,
    private var icon: Int,
    bgColor: Int,
    borderColor: Int,
    initialTint: Int,
    private val iconSize: Int,
    private val fixedIconLoader: ((Context, Int) -> Drawable)? = null
) : FrameLayout(context) {

    companion object {
        fun iconSizePx(context: Context, dip: Float): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dip,
                context.resources.displayMetrics
            ).roundToInt()

        fun defaultIconSizePx(context: Context) = iconSizePx(context, 20f)
        fun endCallIconSizePx(context: Context) = iconSizePx(context, 24f)
    }

    constructor(
        context: Context,
        icon: Int,
        bgColor: Int,
        borderColor: Int,
        initialTint: Int
    ) : this(context, icon, bgColor, borderColor, initialTint, defaultIconSizePx(context))

    private val bgDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(bgColor)
        if (borderColor != 0) {
            val strokePx = (Companion.iconSizePx(context, 1f) / 2).coerceAtLeast(1)
            setStroke(strokePx, borderColor)
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
            setImageDrawable(loadIcon())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        addView(iconView, LayoutParams(iconSize, iconSize, Gravity.CENTER))
        applyVoiceButtonPressFeedback()
    }

    private fun loadIcon(): Drawable {
        fixedIconLoader?.let { return it(context, icon) }
        return if (applyTint) {
            MezonIcon.loadDrawable(context, icon, iconTint)
        } else {
            MezonIcon.loadDrawable(context, icon)
        }
    }

    fun updateIcon(newIcon: Int) {
        icon = newIcon
        iconView.setImageDrawable(loadIcon())
    }

    fun setIconSize(px: Int) {
        val lp = iconView.layoutParams as LayoutParams
        if (lp.width == px && lp.height == px) return
        lp.width = px
        lp.height = px
        iconView.layoutParams = lp
    }

    fun updateBgColor(color: Int) {
        bgDrawable.setColor(color)
    }

    fun updateBorderColor(color: Int) {
        val strokePx = (Companion.iconSizePx(context, 1f) / 2).coerceAtLeast(1)
        bgDrawable.setStroke(strokePx, color)
    }

    fun updateIconTint(color: Int) {
        iconTint = color
        if (!applyTint) return
        iconView.setImageDrawable(loadIcon())
    }

    fun setApplyTint(enabled: Boolean) {
        if (applyTint == enabled) return
        applyTint = enabled
        iconView.setImageDrawable(loadIcon())
    }
}
