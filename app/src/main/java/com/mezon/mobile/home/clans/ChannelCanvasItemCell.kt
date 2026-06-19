package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class ChannelCanvasItemCell(
    context: Context,
    private val theme: ThemeColors
) : LinearLayout(context) {

    private val titleView: TextView
    private val copyButton: FrameLayout

    var onCopyLinkClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = LayoutHelper.dp(10f)
        val padV = LayoutHelper.dp(4f)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            setColor(theme.channelPanelBg)
            cornerRadius = LayoutHelper.dpf(8f)
        }

        titleView = TextView(context).apply {
            setTextColor(theme.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            maxLines = 1
        }
        addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10f, 0f, 8f, 0f))

        copyButton = createIconButton(MezonIcon.linkIcon, theme.onSurface)
        copyButton.setOnClickListener { onCopyLinkClick?.invoke() }
        addView(
            copyButton,
            LayoutHelper.createLinear(30, 30, 0f, Gravity.CENTER_VERTICAL)
        )
    }

    fun bind(title: String) {
        if (titleView.text != title) titleView.text = title
    }

    private fun createIconButton(icon: MezonIcon, tint: Int): FrameLayout {
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x00000000)
                setStroke(LayoutHelper.dp(1f), theme.outlineVariant)
            }
            val iconView = ImageView(context).apply {
                val d = icon.getDrawable(context).mutate()
                d.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                setImageDrawable(d)
            }
            addView(iconView, LayoutHelper.createFrame(16, 16, Gravity.CENTER))
        }
    }
}
