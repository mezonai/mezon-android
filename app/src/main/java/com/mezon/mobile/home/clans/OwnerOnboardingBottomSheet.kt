package com.mezon.mobile.home.clans

import android.content.Context
import com.mezon.mobile.R
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.home.UserClanController

class OwnerOnboardingBottomSheet(
    context: Context,
    private val clanId: Long,
    private val creatorId: Long,
    private val isOwner: Boolean,
    private val channelController: ChannelController,
    private val userClanController: UserClanController,
    private val notificationCenter: NotificationCenter,
    private val onCreateChannel: () -> Unit,
    private val onInviteFriends: () -> Unit,
    private val onSendFirstMessage: () -> Unit,
    private val onVisitWelcomeChannel: () -> Unit,
    private val onSendWelcomeMessage: () -> Unit,
    private val onInstallApps: () -> Unit
) : BottomSheet(context) {

    private val themeColors = ThemeColors.instance
    private lateinit var subtitleView: TextView

    private lateinit var createChannelRow: TaskRowViews
    private lateinit var inviteFriendsRow: TaskRowViews
    private lateinit var sendMessageRow: TaskRowViews

    private class TaskRowViews(
        val row: LinearLayout,
        val statusContainer: FrameLayout,
        val arrow: ImageView
    )

    private val onboardingObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            updateStates()
        }
    }

    init {
        containerHeight = LayoutHelper.WRAP_CONTENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(themeColors.background)
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(16), LayoutHelper.dp(20), LayoutHelper.dp(24))
        }

        if (isOwner) {
            val logoView = ImageView(context).apply {
                setImageDrawable(MezonIcon.ownerMission.getDrawable(context))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            root.addView(logoView, LayoutHelper.createLinear(96, 96).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = LayoutHelper.dp(20)
                bottomMargin = LayoutHelper.dp(16)
            })
        } else {
            val logoCircle = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor((themeColors.primary and 0x00FFFFFF) or 0x1F000000)
                }
            }
            val logoView = ImageView(context).apply {
                setImageDrawable(MezonIcon.target.getDrawable(context))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            logoCircle.addView(logoView, LayoutHelper.createFrame(32, 32, Gravity.CENTER))
            root.addView(logoCircle, LayoutHelper.createLinear(64, 64).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = LayoutHelper.dp(10)
                bottomMargin = LayoutHelper.dp(16)
            })
        }

        val titleView = TextView(context).apply {
            text = if (isOwner) context.getString(R.string.clan_onboarding_owner_title) else context.getString(R.string.clan_onboarding_member_title)
            setTextColor(themeColors.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isOwner) 22f else 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            if (isOwner) {
                setLineSpacing(0f, 1.1f)
            }
        }
        root.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(if (isOwner) 8 else 6)
        })

        subtitleView = TextView(context).apply {
            setTextColor(themeColors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isOwner) 13f else 14f)
            gravity = Gravity.CENTER
        }
        root.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(24)
        })

        if (isOwner) {
            createChannelRow = createTaskRow(
                MezonIcon.ownerCreateChannel,
                "",
                context.getString(R.string.clan_onboarding_owner_task_create_channel_title),
                context.getString(R.string.clan_onboarding_owner_task_create_channel_desc)
            ) {
                dismiss()
                onCreateChannel()
            }
            root.addView(createChannelRow.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(12)
            })

            inviteFriendsRow = createTaskRow(
                MezonIcon.ownerInvite,
                "",
                context.getString(R.string.clan_onboarding_owner_task_invite_friends_title),
                context.getString(R.string.clan_onboarding_owner_task_invite_friends_desc)
            ) {
                dismiss()
                onInviteFriends()
            }
            root.addView(inviteFriendsRow.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(12)
            })

            sendMessageRow = createTaskRow(
                MezonIcon.ownerChat,
                "",
                context.getString(R.string.clan_onboarding_owner_task_send_message_title),
                context.getString(R.string.clan_onboarding_owner_task_send_message_desc)
            ) {
                dismiss()
                onSendFirstMessage()
            }
            root.addView(sendMessageRow.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(12)
            })
        } else {
            createChannelRow = createTaskRow(
                MezonIcon.target,
                "",
                context.getString(R.string.clan_onboarding_member_task_visit_welcome_title),
                context.getString(R.string.clan_onboarding_member_task_visit_welcome_desc)
            ) {
                dismiss()
                onVisitWelcomeChannel()
            }
            root.addView(createChannelRow.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(12)
            })

            inviteFriendsRow = createTaskRow(
                MezonIcon.target,
                "",
                context.getString(R.string.clan_onboarding_member_task_send_welcome_title),
                context.getString(R.string.clan_onboarding_member_task_send_welcome_desc)
            ) {
                dismiss()
                onSendWelcomeMessage()
            }
            root.addView(inviteFriendsRow.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(12)
            })

            sendMessageRow = createTaskRow(
                MezonIcon.target,
                "",
                context.getString(R.string.clan_onboarding_member_task_install_apps_title),
                context.getString(R.string.clan_onboarding_member_task_install_apps_desc)
            ) {
                OwnerOnboardingManager.setUserInstalledApps(context, clanId, true)
                updateStates()
            }
            root.addView(sendMessageRow.row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                bottomMargin = LayoutHelper.dp(12)
            })
        }

        setCustomView(root)
        super.onCreate(savedInstanceState)

        setOnDismissListener {
            notificationCenter.removeObserver(onboardingObserver, NotificationCenter.ownerOnboardingStateChanged)
            notificationCenter.removeObserver(onboardingObserver, NotificationCenter.channelsDidLoad)
            notificationCenter.removeObserver(onboardingObserver, NotificationCenter.clanMembersDidLoad)
        }
        notificationCenter.addObserver(onboardingObserver, NotificationCenter.ownerOnboardingStateChanged)
        notificationCenter.addObserver(onboardingObserver, NotificationCenter.channelsDidLoad)
        notificationCenter.addObserver(onboardingObserver, NotificationCenter.clanMembersDidLoad)

        updateStates()
    }

    private fun createTaskRow(
        icon: MezonIcon?,
        emoji: String,
        title: String,
        description: String,
        clickable: Boolean = true,
        onClick: () -> Unit
    ): TaskRowViews {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(14), LayoutHelper.dp(16), LayoutHelper.dp(14))
            val cardBg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(if (isOwner) 16f else 12f).toFloat()
                setColor(if (isOwner) themeColors.getColor(ThemeColors.key_sheetItemBackground) else themeColors.surfaceVariant)
            }
            background = if (clickable) {
                RippleDrawable(
                    ColorStateList.valueOf(if (isOwner) (themeColors.onSurface and 0x00FFFFFF) or 0x1A000000 else themeColors.onSurface and 0x1AFFFFFF),
                    cardBg,
                    null
                )
            } else {
                cardBg
            }
            isClickable = clickable
            isFocusable = clickable
            if (clickable) {
                setOnClickListener { onClick() }
            }
        }

        if (icon != null) {
            val iconView = ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(iconView, LayoutHelper.createLinear(if (isOwner) 40 else 36, if (isOwner) 40 else 36).apply {
                marginEnd = LayoutHelper.dp(12)
            })
        } else {
            val emojiText = TextView(context).apply {
                text = emoji
                textSize = 24f
                gravity = Gravity.CENTER
            }
            row.addView(emojiText, LayoutHelper.createLinear(36, 36).apply {
                marginEnd = LayoutHelper.dp(12)
            })
        }

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleText = TextView(context).apply {
            text = title
            setTextColor(themeColors.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val descText = TextView(context).apply {
            text = description
            setTextColor(themeColors.onSurfaceVariant)
            if (isOwner) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, LayoutHelper.dp(2), 0, 0)
            } else {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
        }
        textContainer.addView(titleText)
        textContainer.addView(descText)
        row.addView(textContainer, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
            marginEnd = LayoutHelper.dp(12)
        })

        val rightContainer = FrameLayout(context)
        row.addView(rightContainer, LayoutHelper.createLinear(24, 24).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        val statusContainer = FrameLayout(context).apply {
            visibility = View.GONE
        }
        rightContainer.addView(statusContainer, LayoutHelper.createFrame(24, 24, Gravity.CENTER))

        val arrow = ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronSmallRightIcon.getDrawable(context, themeColors.onSurfaceVariant))
            visibility = View.VISIBLE
        }
        rightContainer.addView(arrow, LayoutHelper.createFrame(16, 16, Gravity.CENTER))

        return TaskRowViews(row, statusContainer, arrow)
    }

    private fun updateStates() {
        val completedCount: Int
        val task1Completed: Boolean
        val task2Completed: Boolean
        val task3Completed: Boolean

        if (isOwner) {
            val channels = channelController.getChannels(clanId)
            val members = userClanController.getClanMembers(clanId)
            val hasOtherMember = members.any { it.userId != creatorId && it.userId != 0L }
            task1Completed = OwnerOnboardingManager.isCreatedChannel(context, clanId, channels)
            task2Completed = OwnerOnboardingManager.isInvitedFriends(context, clanId, hasOtherMember)
            task3Completed = OwnerOnboardingManager.isSentMessage(context, clanId)
            completedCount = OwnerOnboardingManager.getCompletedCount(context, clanId, channels, hasOtherMember)
        } else {
            task1Completed = OwnerOnboardingManager.isUserVisitedWelcome(context, clanId)
            task2Completed = OwnerOnboardingManager.isUserSentWelcome(context, clanId)
            task3Completed = OwnerOnboardingManager.isUserInstalledApps(context, clanId)
            completedCount = OwnerOnboardingManager.getUserCompletedCount(context, clanId)
        }

        subtitleView.text = context.getString(R.string.clan_onboarding_progress, completedCount)

        updateTaskStatus(createChannelRow.statusContainer, createChannelRow.arrow, task1Completed)
        updateTaskStatus(inviteFriendsRow.statusContainer, inviteFriendsRow.arrow, task2Completed)
        updateTaskStatus(sendMessageRow.statusContainer, sendMessageRow.arrow, task3Completed)

        if (completedCount == 3) {
            AndroidUtilities.runOnUIThread({ dismiss() }, 1000L)
        }
    }

    private fun updateTaskStatus(statusContainer: FrameLayout, arrow: ImageView, isCompleted: Boolean) {
        statusContainer.removeAllViews()
        if (isCompleted) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.getColor(ThemeColors.key_color_success))
            }
            val check = ImageView(context).apply {
                setImageDrawable(MezonIcon.checkmarkSmallIcon.getDrawable(context, 0xFFFFFFFF.toInt()))
            }
            statusContainer.background = bg
            statusContainer.addView(check, LayoutHelper.createFrame(14, 14, Gravity.CENTER))
            statusContainer.visibility = View.VISIBLE
            arrow.visibility = View.GONE
        } else {
            statusContainer.visibility = View.GONE
            arrow.visibility = View.VISIBLE
        }
    }
}
