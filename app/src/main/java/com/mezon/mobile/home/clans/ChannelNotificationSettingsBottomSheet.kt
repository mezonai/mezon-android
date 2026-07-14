package com.mezon.mobile.home.clans

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers
import com.mezon.mobile.ui.cells.RadioCell

class ChannelNotificationSettingsBottomSheet(
    context: android.content.Context,
    val channelId: Long,
    initialType: Int,
    private val onTypeSelected: (notificationType: Int, complete: (Boolean) -> Unit) -> Unit,
) : BottomSheet(context) {

    private val theme = ThemeColors.instance
    private val radioCells = LinkedHashMap<Int, RadioCell>()
    private val optionRows = ArrayList<View>()
    private var selectedType = normalizeChannelNotificationType(initialType)
    private var loadingInitialType = true
    private var saving = false

    init {
        containerHeight = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val title = TextView(context).apply {
            text = context.getString(R.string.channel_notification_settings_title)
            setTextColor(theme.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, LayoutHelper.dp(22))
        }

        val options = listOf(
            Triple(
                CHANNEL_NOTIFICATION_USE_DEFAULT,
                R.string.channel_notification_settings_use_default,
                R.string.channel_notification_settings_default_all_messages,
            ),
            Triple(
                CHANNEL_NOTIFICATION_ALL_MESSAGES,
                R.string.channel_notification_settings_all_messages,
                null,
            ),
            Triple(
                CHANNEL_NOTIFICATION_MENTIONS_ONLY,
                R.string.channel_notification_settings_mentions_only,
                null,
            ),
            Triple(
                CHANNEL_NOTIFICATION_NOTHING,
                R.string.channel_notification_settings_nothing,
                null,
            ),
        )

        optionRows.clear()
        radioCells.clear()
        options.forEach { (type, titleRes, subtitleRes) ->
            optionRows += buildOptionRow(
                type = type,
                title = context.getString(titleRes),
                subtitle = subtitleRes?.let(context::getString),
            )
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            setPadding(LayoutHelper.dp(20), 0, LayoutHelper.dp(20), LayoutHelper.dp(20))
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(
                ClanSettingsUiHelpers.buildMezonSection(context, theme, null, optionRows),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
            )
        }

        setCustomView(ClanSettingsUiHelpers.newMezonScrollRoot(context).apply {
            addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        })
        super.onCreate(savedInstanceState)
        updateSelection(selectedType)
        setRowsEnabled(false)
    }

    fun updateSelection(notificationType: Int) {
        if (saving) return
        selectedType = normalizeChannelNotificationType(notificationType)
        radioCells.forEach { (type, radio) ->
            radio.setChecked(type == selectedType, animated = false)
        }
    }

    fun completeInitialLoad(notificationType: Int?) {
        notificationType?.let(::updateSelection)
        loadingInitialType = false
        setRowsEnabled(true)
    }

    private fun buildOptionRow(type: Int, title: String, subtitle: String?): View {
        val radio = RadioCell(context, theme).apply {
            drawSelectionAsCheckmark = false
            setChecked(type == selectedType, animated = false)
        }
        radioCells[type] = radio

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            if (subtitle.isNullOrBlank()) {
                minimumHeight = LayoutHelper.dp(38)
            }
            addView(
                TextView(context).apply {
                    text = title
                    setTextColor(theme.textStrong)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    includeFontPadding = false
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
            )
            if (!subtitle.isNullOrBlank()) {
                addView(
                    TextView(context).apply {
                        text = subtitle
                        setTextColor(theme.onSurfaceVariant)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        includeFontPadding = false
                    },
                    LayoutHelper.createLinear(
                        LayoutHelper.MATCH_PARENT,
                        LayoutHelper.WRAP_CONTENT,
                        topMargin = 2f,
                    ),
                )
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutHelper.dp(58)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(10), LayoutHelper.dp(16), LayoutHelper.dp(10))
            setBackgroundColor(theme.border)
            isClickable = true
            isFocusable = true
            contentDescription = title
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            addView(
                radio,
                LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT,
                    gravity = Gravity.CENTER_VERTICAL,
                    leftMargin = 12f,
                ),
            )
            setOnClickListener { select(type) }
        }
    }

    private fun select(type: Int) {
        if (loadingInitialType || saving) return
        if (type == selectedType) {
            dismiss()
            return
        }
        saving = true
        setRowsEnabled(false)
        onTypeSelected(type) { success ->
            AndroidUtilities.runOnUIThread {
                completeSelection(type, success)
            }
        }
    }

    private fun completeSelection(type: Int, success: Boolean) {
        saving = false
        if (success) {
            selectedType = type
            radioCells.forEach { (rowType, radio) ->
                radio.setChecked(rowType == selectedType, animated = false)
            }
            dismiss()
        } else {
            setRowsEnabled(true)
        }
    }

    private fun setRowsEnabled(enabled: Boolean) {
        optionRows.forEach { row ->
            row.isEnabled = enabled
            row.alpha = if (enabled) 1f else 0.55f
        }
    }
}
