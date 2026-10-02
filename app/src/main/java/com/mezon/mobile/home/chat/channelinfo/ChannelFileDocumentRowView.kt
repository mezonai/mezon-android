package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.isAudioAttachmentType
import com.mezon.mobile.ui.cells.MezonIcon

class ChannelFileDocumentRowView(
    context: Context,
    private val theme: ThemeColors
) : FrameLayout(context) {

    private val iconView = ImageView(context)
    private val nameView = TextView(context)
    private val sharedView = TextView(context)
    private val timeView = TextView(context)

    init {
        setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(16f), LayoutHelper.dp(10f), LayoutHelper.dp(16f))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconSize = LayoutHelper.dp(16f)
        iconView.scaleType = ImageView.ScaleType.FIT_CENTER
        iconView.adjustViewBounds = true
        row.addView(iconView, LayoutHelper.createLinear(iconSize, iconSize, 0f, Gravity.CENTER_VERTICAL))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        content.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(sharedView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        footer.addView(timeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        content.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        row.addView(content, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))
        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        updateBackground()
    }

    private fun updateBackground() {
        background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(8f)
            setColor(theme.surfaceVariant)
        }
    }

    fun bind(
        item: ChannelDocumentItem,
        sharedFooterText: String,
        timeLabel: String
    ) {
        iconView.clearColorFilter()
        val icon = if (isAudioAttachmentType(item.filetype)) {
            MezonIcon.musicNoteIcon.getDrawable(context, theme.onSurface)
        } else {
            MezonIcon.fileIconNew.getDrawable(context)
        }
        iconView.setImageDrawable(icon)
        nameView.text = item.filename
        nameView.setTextColor(theme.blurple)
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        nameView.maxLines = 1
        nameView.ellipsize = TextUtils.TruncateAt.END
        sharedView.text = sharedFooterText
        sharedView.setTextColor(theme.onSurface)
        sharedView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
        sharedView.maxLines = 1
        sharedView.ellipsize = TextUtils.TruncateAt.END
        timeView.text = timeLabel
        timeView.setTextColor(theme.textDisabled)
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
    }
}
