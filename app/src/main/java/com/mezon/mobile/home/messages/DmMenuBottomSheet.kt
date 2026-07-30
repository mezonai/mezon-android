package com.mezon.mobile.home.messages

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
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon

data class DmMenuOptions(
    val showLeaveGroup: Boolean,
    val showDeleteGroup: Boolean,
    val showCloseDm: Boolean,
    val showAddFriend: Boolean,
    val showRemoveFriend: Boolean,
    val showBlockUser: Boolean,
    val showUnblockUser: Boolean,
    val showMarkAsRead: Boolean,
    val showPin: Boolean,
    val isPinned: Boolean,
    val showMute: Boolean,
    val isMuted: Boolean,
)

class DmMenuBottomSheet(
    context: android.content.Context,
    private val dm: DirectMessage,
    private val options: DmMenuOptions,
    private val onLeaveGroup: () -> Unit,
    private val onDeleteGroup: () -> Unit,
    private val onCloseDm: () -> Unit,
    private val onAddFriend: () -> Unit,
    private val onRemoveFriend: () -> Unit,
    private val onBlockUser: () -> Unit,
    private val onUnblockUser: () -> Unit,
    private val onMarkAsRead: () -> Unit,
    private val onTogglePin: () -> Unit,
    private val onMute: () -> Unit,
) : BottomSheet(context) {

    private val theme = ThemeColors.instance

    init {
        containerHeight = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val sectionGap = 14f
        val newOriginalColorIcons = setOf(
            MezonIcon.markUnreadIcon,
            MezonIcon.pinIcon,
            MezonIcon.bellIcon,
            MezonIcon.bellSlashIcon,
            MezonIcon.userFriendIcon,
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
        ): View {
            val row = com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers.buildMezonMenuRow(
                context,
                theme,
                icon,
                label,
                labelColor,
                iconColor,
                keepOriginalIconColors = icon in newOriginalColorIcons,
                onPress = dismissAndRun(onClick),
            )
            return row
        }

        fun addSection(parent: LinearLayout, rows: List<View>, topGap: Float = sectionGap) {
            if (rows.all { it.visibility == View.GONE }) return
            parent.addView(
                com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers.buildMezonSection(context, theme, null, rows),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, topGap, 0f, 0f)
            )
        }

        val displayName = dm.displayName.ifBlank { dm.label }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(30))
        }
        val avatarWrap = FrameLayout(context)
        avatarWrap.addView(
            AvatarView(context).apply {
                setSizeDp(60)
                setRoundRadius(if (dm.type == CHANNEL_TYPE_GROUP) 10f else 30f)
                setInfo(dm.channelId, dm.avatarPlaceholderKey())
                setImageUrl(dm.avatarUrl.ifBlank { "" })
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
                text = displayName.ifBlank { "…" }
                setTextColor(theme.textStrong)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LayoutHelper.createLinear(0, 60, 1f, Gravity.CENTER_VERTICAL)
        )

        val groupRows = buildList {
            if (options.showDeleteGroup) {
                add(
                    buildRow(
                        context.getString(R.string.dm_menu_delete_group),
                        com.mezon.mobile.ui.cells.MezonIcon.closeSmallBold,
                        labelColor = theme.redStrong,
                        iconColor = theme.redStrong,
                        onClick = onDeleteGroup,
                    )
                )
            } else if (options.showLeaveGroup) {
                add(
                    buildRow(
                        context.getString(R.string.dm_menu_leave_group),
                        com.mezon.mobile.ui.cells.MezonIcon.leaveGroupIcon,
                        labelColor = theme.redStrong,
                        iconColor = theme.redStrong,
                        onClick = onLeaveGroup,
                    )
                )
            }
        }

        val profileRows = buildList {
            if (options.showCloseDm) {
                add(
                    buildRow(
                        context.getString(R.string.dm_menu_close_dm),
                        com.mezon.mobile.ui.cells.MezonIcon.closeDMIcon,
                        labelColor = theme.redStrong,
                        iconColor = theme.redStrong,
                        onClick = onCloseDm,
                    )
                )
            }
            if (options.showRemoveFriend) {
                add(
                    buildRow(
                        context.getString(R.string.dm_menu_remove_friend),
                        com.mezon.mobile.ui.cells.MezonIcon.removeFriend,
                        labelColor = theme.redStrong,
                        iconColor = theme.redStrong,
                        onClick = onRemoveFriend,
                    )
                )
            } else if (options.showAddFriend) {
                add(
                    buildRow(
                        context.getString(R.string.dm_add_friend),
                        MezonIcon.userFriendIcon,
                        onClick = onAddFriend,
                    )
                )
            }
            if (options.showUnblockUser) {
                add(
                    buildRow(
                        context.getString(R.string.dm_menu_unblock_user),
                        com.mezon.mobile.ui.cells.MezonIcon.unblockUser,
                        onClick = onUnblockUser,
                    )
                )
            } else if (options.showBlockUser) {
                add(
                    buildRow(
                        context.getString(R.string.dm_menu_block_user),
                        com.mezon.mobile.ui.cells.MezonIcon.blockUser,
                        labelColor = theme.redStrong,
                        iconColor = theme.redStrong,
                        onClick = onBlockUser,
                    )
                )
            }
        }

        val readRows = buildList {
            if (options.showMarkAsRead) {
                add(
                    buildRow(
                        context.getString(R.string.dm_menu_mark_as_read),
                        MezonIcon.markUnreadIcon,
                        onClick = onMarkAsRead,
                    )
                )
            }
            if (options.showPin) {
                val pinLabel = if (options.isPinned) {
                    context.getString(R.string.dm_menu_unpin_conversation)
                } else {
                    context.getString(R.string.dm_menu_pin_conversation)
                }
                val pinIcon = if (options.isPinned) {
                    com.mezon.mobile.ui.cells.MezonIcon.emptyPinIcon
                } else {
                    com.mezon.mobile.ui.cells.MezonIcon.pinIcon
                }
                add(
                    buildRow(
                        pinLabel,
                        pinIcon,
                        onClick = onTogglePin,
                    )
                )
            }
        }

        val muteRows = buildList {
            if (options.showMute) {
                val muteLabel = if (options.isMuted) {
                    context.getString(R.string.dm_menu_unmute)
                } else {
                    context.getString(R.string.dm_menu_mute)
                }
                val muteIcon = if (options.isMuted) {
                    MezonIcon.bellIcon
                } else {
                    MezonIcon.bellSlashIcon
                }
                add(
                    buildRow(
                        muteLabel,
                        muteIcon,
                        onClick = onMute,
                    )
                )
            }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            setPadding(LayoutHelper.dp(20), 0, LayoutHelper.dp(20), LayoutHelper.dp(20))
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            if (groupRows.isNotEmpty()) addSection(this, groupRows, topGap = 0f)
            if (profileRows.isNotEmpty()) addSection(this, profileRows)
            if (readRows.isNotEmpty()) addSection(this, readRows)
            if (muteRows.isNotEmpty()) addSection(this, muteRows)
        }

        setCustomView(com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers.newMezonScrollRoot(context).apply {
            addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        })
        super.onCreate(savedInstanceState)
    }
}
