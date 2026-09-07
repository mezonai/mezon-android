package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class ClanEventOptionPicker(
    context: Context,
    private val theme: ThemeColors,
    heading: CharSequence,
    private val defaultIcon: MezonIcon = MezonIcon.channelText,
    placeholder: CharSequence? = null,
    private val clearable: Boolean = true,
) : LinearLayout(context) {

    private val emptyLabel = placeholder ?: context.getString(R.string.webhooks_edit_select_channel_placeholder)

    private val valueLabel: TextView
    private val leadingIcon: ImageView
    private val trailingChevron: ImageView
    private val trailingClear: ImageView

    var selectedChannelId: Long = 0L
        private set

    var onPickRequested: (() -> Unit)? = null
    var onClearRequested: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        background = cardBackground()
        setPadding(PADDING_H, PADDING_V, PADDING_H, PADDING_V)
        isClickable = true
        isFocusable = true
        setOnClickListener { onPickRequested?.invoke() }

        addView(createHeadingView(heading))

        val valueRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, VALUE_ROW_TOP_PAD, 0, 0)
        }

        leadingIcon = createLeadingIcon()
        valueLabel = createValueLabel()
        trailingChevron = createTrailingChevron()
        trailingClear = createTrailingClear()

        valueRow.addView(
            leadingIcon,
            LayoutHelper.createLinear(ICON_SIZE, ICON_SIZE, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f),
        )
        valueRow.addView(valueLabel, LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
        valueRow.addView(trailingChevron, LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL))
        if (clearable) {
            valueRow.addView(trailingClear, LayoutHelper.createLinear(24, 24, 0f, Gravity.CENTER_VERTICAL))
        }

        addView(valueRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        if (clearable) {
            bind(null)
        }
    }

    fun bindValue(value: CharSequence, icon: MezonIcon = defaultIcon) {
        selectedChannelId = 0L
        bindLeadingIcon(icon, selected = true)
        leadingIcon.visibility = if (clearable) View.VISIBLE else View.GONE
        showSelectedState(value)
    }

    fun bind(channel: ClanChannelEntity?) {
        selectedChannelId = channel?.channelId ?: 0L
        if (channel == null) {
            bindLeadingIcon(defaultIcon, selected = false)
            showEmptyState()
        } else {
            val icon = ChannelItemCell.resolveChannelIcon(channel.type, channel.isPrivate, channel.isAgeRestricted)
            bindLeadingIcon(icon, selected = true)
            showSelectedState(formatChannelLabel(channel.channelLabel))
        }
    }

    fun bind(channelId: Long, channels: List<ClanChannelEntity>) {
        bind(channels.firstOrNull { it.channelId == channelId })
    }

    fun clearSelection() {
        bind(null)
    }

    private fun cardBackground() = GradientDrawable().apply {
        cornerRadius = LayoutHelper.dpf(12f)
        setColor(theme.secondaryLight)
        setStroke(LayoutHelper.dp(1), theme.outlineVariant)
    }

    private fun createHeadingView(heading: CharSequence) = TextView(context).apply {
        text = heading
        textSize = HEADING_TEXT_SIZE_SP
        setTextColor(theme.onSurfaceVariant)
    }

    private fun createLeadingIcon() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    private fun createValueLabel() = TextView(context).apply {
        textSize = VALUE_TEXT_SIZE_SP
        maxLines = 2
    }

    private fun createTrailingChevron() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setImageDrawable(tintedIcon(MezonIcon.chevronSmallRightIcon, CreateClanRnUiTokens.textDisabled(theme)))
    }

    private fun createTrailingClear() = ImageView(context).apply {
        contentDescription = context.getString(R.string.event_creator_clear_selection)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setImageDrawable(tintedIcon(MezonIcon.closeSmallBold, CreateClanRnUiTokens.textDisabled(theme)))
        val pad = LayoutHelper.dp(4f)
        setPadding(pad, pad, pad, pad)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClearRequested?.invoke() }
    }

    private fun bindLeadingIcon(icon: MezonIcon, selected: Boolean) {
        val color = if (selected) {
            CreateClanRnUiTokens.menuText(theme)
        } else {
            CreateClanRnUiTokens.textDisabled(theme)
        }
        leadingIcon.setImageDrawable(tintedIcon(icon, color))
    }

    private fun showEmptyState() {
        valueLabel.text = emptyLabel
        valueLabel.typeface = Typeface.DEFAULT
        valueLabel.setTextColor(theme.onSurfaceVariant)
        trailingChevron.visibility = View.VISIBLE
        trailingClear.visibility = View.GONE
    }

    private fun showSelectedState(value: CharSequence) {
        valueLabel.text = value
        valueLabel.typeface = Typeface.DEFAULT
        valueLabel.setTextColor(CreateClanRnUiTokens.menuText(theme))
        if (clearable) {
            trailingChevron.visibility = View.GONE
            trailingClear.visibility = View.VISIBLE
        } else {
            trailingChevron.visibility = View.VISIBLE
        }
    }

    private fun tintedIcon(icon: MezonIcon, color: Int): Drawable {
        return icon.getDrawable(context).mutate().apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }

    companion object {
        private const val HEADING_TEXT_SIZE_SP = 12f
        private const val VALUE_TEXT_SIZE_SP = 14f
        private const val ICON_SIZE = 14
        private val PADDING_H = LayoutHelper.dp(12f)
        private val PADDING_V = LayoutHelper.dp(12f)
        private val VALUE_ROW_TOP_PAD = LayoutHelper.dp(6f)

        fun formatChannelLabel(raw: String): String = raw.trim().removePrefix("#").trim()
    }
}
