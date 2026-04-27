package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.theme.ThemeMode

class InAppNotificationToastView(
    context: Context,
    private val theme: ThemeColors
) : LinearLayout(context) {

    private val titleView: TextView
    private val bodyView: TextView

    init {
        orientation = VERTICAL
        elevation = LayoutHelper.dpf(8f)
        val bg = GradientDrawable().apply {
            setColor(theme.channelPanelBg)
            cornerRadius = LayoutHelper.dpf(16f)
            setStroke(LayoutHelper.dp(2), theme.primary)
        }
        background = bg
        setPadding(0, 0, 0, 0)
        isClickable = true
        val contentPad = LayoutHelper.dp(10)
        val textBlock = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(contentPad, contentPad, contentPad, contentPad)
        }
        titleView = TextView(context).apply {
            setTextColor(
                if (theme.resolvedMode == ThemeMode.LIGHT) theme.onSurface else 0xFFFFFFFF.toInt()
            )
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bodyView = TextView(context).apply {
            setTextColor(theme.textStrong)
            textSize = 14f
            maxLines = 3
        }
        textBlock.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        textBlock.addView(
            bodyView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            textBlock,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    fun updateContent(title: String, body: String) {
        titleView.text = title
        bodyView.text = body
    }
}
