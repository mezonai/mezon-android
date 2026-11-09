package com.mezon.mobile.home.messages

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.CHANNEL_MUTE_ACTIVE_INFINITY
import com.mezon.mobile.home.clans.CHANNEL_MUTE_DURATION_15M
import com.mezon.mobile.home.clans.CHANNEL_MUTE_DURATION_1H
import com.mezon.mobile.home.clans.CHANNEL_MUTE_DURATION_24H
import com.mezon.mobile.home.clans.CHANNEL_MUTE_DURATION_3H
import com.mezon.mobile.home.clans.CHANNEL_MUTE_DURATION_8H
import com.mezon.mobile.home.clans.SET_MUTE_ACTIVE_UNMUTE
import com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers

class DmMuteBottomSheet(
    context: android.content.Context,
    private val channelLabel: String,
    private val onDurationSelected: (muteTimeSeconds: Int, active: Int) -> Unit,
) : BottomSheet(context) {

    private val theme = ThemeColors.instance

    init {
        containerHeight = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        fun dismissAndRun(action: () -> Unit): Runnable = Runnable {
            dismiss()
            action()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(30))
        }
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.dm_menu_mute)
                setTextColor(theme.textStrong)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        header.addView(
            TextView(context).apply {
                text = channelLabel.ifBlank { "…" }
                setTextColor(theme.onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(4)
            }
        )

        val durations = listOf(
            context.getString(R.string.channel_mute_duration_15m) to CHANNEL_MUTE_DURATION_15M,
            context.getString(R.string.channel_mute_duration_1h) to CHANNEL_MUTE_DURATION_1H,
            context.getString(R.string.channel_mute_duration_3h) to CHANNEL_MUTE_DURATION_3H,
            context.getString(R.string.channel_mute_duration_8h) to CHANNEL_MUTE_DURATION_8H,
            context.getString(R.string.channel_mute_duration_24h) to CHANNEL_MUTE_DURATION_24H,
            context.getString(R.string.channel_mute_duration_until_manual) to CHANNEL_MUTE_ACTIVE_INFINITY,
        )

        val durationRows = durations.map { (label, value) ->
            ClanSettingsUiHelpers.buildMezonTextMenuRow(
                context,
                theme,
                label,
                dismissAndRun {
                    if (value == CHANNEL_MUTE_ACTIVE_INFINITY) {
                        onDurationSelected(CHANNEL_MUTE_ACTIVE_INFINITY, SET_MUTE_ACTIVE_UNMUTE)
                    } else {
                        onDurationSelected(value, SET_MUTE_ACTIVE_UNMUTE)
                    }
                },
                textColor = theme.textStrong,
                rowBackgroundColor = theme.background,
            )
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(20), 0, LayoutHelper.dp(20), LayoutHelper.dp(20))
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(
                ClanSettingsUiHelpers.buildMezonSection(context, theme, null, durationRows, cardColor = theme.background, separatorColor = theme.border),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
        }

        setCustomView(ClanSettingsUiHelpers.newMezonScrollRoot(context).apply {
            addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        })
        super.onCreate(savedInstanceState)
    }
}
