package com.mezon.mobile.home.clans

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon

class ChannelMenuBottomSheet(
    context: android.content.Context,
    private val channel: ClanChannelEntity,
    private val clanName: String,
    private val clanLogoUrl: String,
    private val isFavorite: Boolean,
    private val showMarkFavorite: Boolean,
    private val showThreadList: Boolean,
    private val showEditChannel: Boolean,
    private val showDeleteChannel: Boolean,
    private val onMarkAsRead: () -> Unit,
    private val onToggleFavorite: () -> Unit,
    private val onCopyLink: () -> Unit,
    private val onMuteChannel: () -> Unit,
    private val onNotificationSettings: () -> Unit,
    private val onOpenThreadList: () -> Unit,
    private val showLeaveThread: Boolean,
    private val onLeaveThread: () -> Unit,
    private val onEditChannel: () -> Unit,
    private val onDeleteChannel: () -> Unit,
) : BottomSheet(context) {

    private val theme = ThemeColors.instance
    private var isMuted = channel.isMuted
    private var muteRow: LinearLayout? = null

    init {
        containerHeight = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val sectionGap = 14f
        val isChannel = !channel.isThread
        val showMarkAsRead = channel.type != CHANNEL_TYPE_STREAMING && channel.type != CHANNEL_TYPE_VOICE
        val originalColorIcons = setOf(
            MezonIcon.markUnreadIcon,
            MezonIcon.copyIcon,
            MezonIcon.favoriteFilledIcon,
            MezonIcon.starIcon,
            MezonIcon.bellIcon,
            MezonIcon.bellSlashIcon,
            MezonIcon.channelNotificaitionIcon,
            MezonIcon.threadIcon,
            MezonIcon.settingClanIcon,
        )

        fun dismissAndRun(action: () -> Unit): Runnable = Runnable {
            dismiss()
            action()
        }

        fun buildRow(
            label: String,
            icon: MezonIcon,
            labelColor: Int = theme.colorText,
            iconColor: Int = theme.textStrong,
            onClick: () -> Unit,
        ) = ClanSettingsUiHelpers.buildMezonMenuRow(
            context,
            theme,
            icon,
            label,
            labelColor,
            iconColor,
            keepOriginalIconColors = icon in originalColorIcons,
            onPress = dismissAndRun(onClick),
        )

        fun addSection(parent: LinearLayout, rows: List<View>, topGap: Float = sectionGap) {
            if (rows.all { it.visibility == View.GONE }) return
            parent.addView(
                ClanSettingsUiHelpers.buildMezonSection(context, theme, null, rows),
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
                setInfo(channel.clanId, clanName)
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
                text = channel.channelLabel.ifBlank { clanName.ifBlank { "…" } }
                setTextColor(theme.textStrong)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LayoutHelper.createLinear(0, 60, 1f, Gravity.CENTER_VERTICAL)
        )

        val watchRows = buildList {
            if (showMarkAsRead && isChannel) {
                add(
                    buildRow(
                        context.getString(R.string.channel_menu_mark_as_read),
                        MezonIcon.markUnreadIcon,
                        onClick = onMarkAsRead,
                    )
                )
            }
            if (channel.isThread) {
                add(
                    buildRow(
                        context.getString(R.string.channel_menu_copy_link),
                        MezonIcon.copyIcon,
                        onClick = onCopyLink,
                    )
                )
            }
        }

        val inviteRows = if (isChannel) {
            buildList {
                if (showMarkFavorite) {
                    val favoriteLabelRes = if (isFavorite) {
                        R.string.channel_menu_unmark_favorite
                    } else {
                        R.string.channel_menu_mark_favorite
                    }
                    val favoriteIcon = if (isFavorite) {
                        MezonIcon.favoriteFilledIcon
                    } else {
                        MezonIcon.starIcon
                    }
                    add(
                        buildRow(
                            context.getString(favoriteLabelRes),
                            favoriteIcon,
                            onClick = onToggleFavorite,
                        )
                    )
                }
                add(
                    buildRow(
                        context.getString(R.string.channel_menu_copy_link),
                        MezonIcon.copyIcon,
                        onClick = onCopyLink,
                    )
                )
            }
        } else {
            emptyList()
        }

        val muteLabelRes = when {
            isMuted && channel.isThread -> R.string.channel_menu_unmute_thread
            isMuted -> R.string.channel_menu_unmute_channel
            channel.isThread -> R.string.channel_menu_mute_thread
            else -> R.string.channel_menu_mute_channel
        }
        val muteIcon = if (isMuted) {
            MezonIcon.bellIcon
        } else {
            MezonIcon.bellSlashIcon
        }
        val notificationRows = listOf(
            buildRow(
                context.getString(muteLabelRes),
                muteIcon,
                onClick = onMuteChannel,
            ).also { muteRow = it },
            buildRow(
                context.getString(R.string.channel_menu_notification_settings),
                MezonIcon.channelNotificaitionIcon,
                onClick = onNotificationSettings,
            ),
        )

        val threadRows = if (showThreadList) {
            listOf(
                buildRow(
                    context.getString(R.string.channel_menu_threads),
                    MezonIcon.threadIcon,
                    onClick = onOpenThreadList,
                )
            )
        } else {
            emptyList()
        }

        val editLabelRes = if (channel.isThread) {
            R.string.channel_menu_edit_thread
        } else {
            R.string.channel_menu_edit_channel
        }
        val deleteLabelRes = if (channel.isThread) {
            R.string.channel_settings_menu_delete_thread
        } else {
            R.string.channel_settings_delete_channel
        }
        val organizationRows = buildList {
            if (showLeaveThread) {
                add(
                    buildRow(
                        context.getString(R.string.channel_settings_menu_leave_thread),
                        MezonIcon.leaveGroupIcon,
                        labelColor = theme.redStrong,
                        iconColor = theme.redStrong,
                        onClick = onLeaveThread,
                    )
                )
            }
            if (showEditChannel) {
                add(
                    buildRow(
                        context.getString(editLabelRes),
                        MezonIcon.settingClanIcon,
                        onClick = onEditChannel,
                    )
                )
            }
            if (showDeleteChannel) {
                add(
                    buildRow(
                        context.getString(deleteLabelRes),
                        MezonIcon.closeSmallBold,
                        labelColor = theme.redStrong,
                        iconColor = theme.redStrong,
                        onClick = onDeleteChannel,
                    )
                )
            }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            setPadding(LayoutHelper.dp(20), 0, LayoutHelper.dp(20), LayoutHelper.dp(20))
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            if (watchRows.isNotEmpty()) {
                addSection(this, watchRows, topGap = 0f)
            }
            if (inviteRows.isNotEmpty()) {
                addSection(this, inviteRows)
            }
            addSection(this, notificationRows)
            if (threadRows.isNotEmpty()) {
                addSection(this, threadRows)
            }
            if (organizationRows.isNotEmpty()) {
                addSection(this, organizationRows)
            }
        }

        setCustomView(ClanSettingsUiHelpers.newMezonScrollRoot(context).apply {
            addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        })
        super.onCreate(savedInstanceState)
    }

    fun updateMuteState(muted: Boolean) {
        isMuted = muted
        val row = muteRow ?: return
        val labelRes = when {
            muted && channel.isThread -> R.string.channel_menu_unmute_thread
            muted -> R.string.channel_menu_unmute_channel
            channel.isThread -> R.string.channel_menu_mute_thread
            else -> R.string.channel_menu_mute_channel
        }
        val icon = if (muted) MezonIcon.bellIcon else MezonIcon.bellSlashIcon
        (row.getChildAt(0) as? ImageView)?.setImageDrawable(icon.getDrawable(context))
        (row.getChildAt(1) as? TextView)?.setText(labelRes)
        row.contentDescription = context.getString(labelRes)
    }
}
