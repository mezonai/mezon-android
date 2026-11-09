package com.mezon.mobile.home.clans

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon

class CategoryMenuBottomSheet(
    context: android.content.Context,
    private val clanId: Long,
    private val clanName: String,
    private val clanLogoUrl: String,
    private val categoryId: Long,
    private val categoryName: String,
    private val canManageChannel: Boolean,
    private val canManageClan: Boolean,
    private val onMarkAsRead: () -> Unit,
    private val onCreateChannel: () -> Unit,
) : BottomSheet(context) {

    private val theme = ThemeColors.instance

    init {
        containerHeight = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val sectionGap = 14f
        val showCreateChannel = canManageChannel && categoryId != 0L

        fun dismissAndRun(action: () -> Unit): Runnable = Runnable {
            dismiss()
            action()
        }

        fun buildRow(
            label: String,
            icon: MezonIcon,
            labelColor: Int = if (theme.usesLegacyDarkColors) theme.colorText else theme.textStrong,
            iconColor: Int = theme.textStrong,
            onClick: () -> Unit,
        ) = ClanSettingsUiHelpers.buildMezonMenuRow(
            context,
            theme,
            icon,
            label,
            labelColor,
            iconColor,
            dismissAndRun(onClick),
            rowBackgroundColor = if (theme.usesLegacyDarkColors) theme.border else theme.background,
        )

        fun addSection(parent: LinearLayout, rows: List<View>, topGap: Float = sectionGap) {
            if (rows.all { it.visibility == View.GONE }) return
            parent.addView(
                ClanSettingsUiHelpers.buildMezonSection(
                    context, theme, null, rows,
                    cardColor = if (theme.usesLegacyDarkColors) theme.channelPanelBg else theme.background,
                    separatorColor = if (theme.usesLegacyDarkColors) theme.tertiary else theme.border
                ),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, topGap, 0f, 0f)
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(30))
        }
        val avatarWrap = FrameLayout(context)
        avatarWrap.addView(
            AvatarView(context).apply {
                setSizeDp(60)
                setRoundRadius(10f)
                setInfo(clanId, clanName)
                setImageUrl(clanLogoUrl.ifBlank { "" })
            },
            FrameLayout.LayoutParams(LayoutHelper.dp(60), LayoutHelper.dp(60))
        )
        header.addView(
            avatarWrap,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                marginEnd = LayoutHelper.dp(15)
            }
        )
        header.addView(
            TextView(context).apply {
                text = categoryName.ifBlank { clanName.ifBlank { "…" } }
                setTextColor(theme.textStrong)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f)
        )

        val watchRows = listOf(
            buildRow(
                context.getString(R.string.category_menu_mark_as_read),
                MezonIcon.eyeIcon,
                onClick = onMarkAsRead,
            )
        )

        val organizationRows = buildList {
            if (showCreateChannel) {
                add(
                    buildRow(
                        context.getString(R.string.category_menu_create_channel),
                        MezonIcon.plusLargeIcon,
                        onClick = onCreateChannel,
                    )
                )
            }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(sheetBackgroundColor)
            setPadding(LayoutHelper.dp(20), 0, LayoutHelper.dp(20), LayoutHelper.dp(20))
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addSection(this, watchRows, topGap = 0f)
            if (organizationRows.isNotEmpty()) {
                addSection(this, organizationRows)
            }
        }

        setCustomView(ClanSettingsUiHelpers.newMezonScrollRoot(context).apply {
            addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        })
        super.onCreate(savedInstanceState)
    }
}
