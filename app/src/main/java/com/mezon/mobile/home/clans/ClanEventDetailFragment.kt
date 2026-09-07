package com.mezon.mobile.home.clans
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.cells.ActionBarMenu
import com.mezon.mobile.ui.cells.ActionBarMenuItem
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.PopupMenu
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.DateTimeUtil
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.createImgproxyUrl
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class ClanEventDetailFragment : BaseFragment() {

    var onOpenChannel: ((ClanChannelEntity) -> Unit)? = null

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_EVENT_ID = "eventId"
        private const val ARG_CLAN_NAME = "clanName"
        private const val ARG_CLAN_LOGO = "clanLogo"
        private const val MENU_MORE = 2

        fun newInstance(
            clanId: Long,
            eventId: Long,
            clanName: String,
            clanLogo: String,
        ): ClanEventDetailFragment = ClanEventDetailFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CLAN_ID, clanId)
                putLong(ARG_EVENT_ID, eventId)
                putString(ARG_CLAN_NAME, clanName)
                putString(ARG_CLAN_LOGO, clanLogo)
            }
        }
    }

    private var clanId = 0L
    private var eventId = 0L
    private var clanName = ""
    private var clanLogo = ""

    private lateinit var clanEventController: ClanEventController
    private lateinit var userClanController: UserClanController
    private lateinit var accountController: AccountController
    private lateinit var userController: UserController
    private lateinit var contentHost: LinearLayout
    private var actionBarMenu: ActionBarMenu? = null
    private var moreMenuItem: ActionBarMenuItem? = null
    private var eventMenuPopup: PopupMenu? = null
    private var deleting = false

    override fun onInject(entryPoint: FragmentEntryPoint) {
        clanEventController = entryPoint.clanEventController()
        userClanController = entryPoint.userClanController()
        accountController = entryPoint.accountController()
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        eventId = arguments?.getLong(ARG_EVENT_ID) ?: 0L
        clanName = arguments?.getString(ARG_CLAN_NAME).orEmpty()
        clanLogo = arguments?.getString(ARG_CLAN_LOGO).orEmpty()
        if (clanId != 0L) {
            userClanController.loadClanMembers(clanId)
            val hasEvent = clanEventController.getEvent(clanId, eventId) != null
            clanEventController.loadEvents(clanId, force = !hasEvent)
        }
        observe(NotificationCenter.clanEventsDidLoad) { _, _, args ->
            if (isPaused || fragmentView == null) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) bindContent()
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused || fragmentView == null) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) bindContent()
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        hideModifyMenu()
    }

    override fun onResume() {
        super.onResume()
        clanEventController.getEvent(clanId, eventId)?.let { updateActionBarMenu(it) }
    }

    override fun onFragmentDestroy() {
        dismissEventMenu()
        actionBarMenu = null
        moreMenuItem = null
        super.onFragmentDestroy()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        actionBar = ActionBarView(context, themeColors).apply {
            setAddToContainer(false)
            occupyStatusBar = true
            setTitle(getString(R.string.clan_event_detail_title))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.common_close))
            setCenterTitle(true)
            actionBarMenu = createMenu()
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        MENU_MORE -> {
                            val event = clanEventController.getEvent(clanId, eventId) ?: return
                            if (canModifyEvent(event)) showEventMenu() else hideModifyMenu()
                        }
                    }
                }
            })
        }
        checkNotNull(actionBar).backButton.apply {
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                width = LayoutHelper.dp(48f)
                height = LayoutHelper.dp(48f)
            }
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        contentHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = NestedScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(
                contentHost,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        bindContent(context)
        return root
    }

    private fun bindContent(context: Context? = fragmentView?.context) {
        val ctx = context ?: return
        contentHost.removeAllViews()
        val event = clanEventController.getEvent(clanId, eventId)
        if (event != null) {
            updateActionBarMenu(event)
            val members = userClanController.getClanMembers(clanId)
            val creator = members.firstOrNull { it.userId == event.creatorId }
            val userId = currentUserId()
            val voiceChannel = clanEventController.getChannel(clanId, event.channelVoiceId)
            val linkedChannel = clanEventController.getChannel(clanId, event.channelId)
            contentHost.addView(
                buildEventDetailView(
                    ctx,
                    themeColors,
                    event,
                    clanName,
                    clanLogo,
                    creator,
                    members,
                    voiceChannel,
                    linkedChannel,
                    userId,
                    onToggleInterest = {
                        val interested = !event.isInterested(userId)
                        clanEventController.setInterested(clanId, event.id, interested) { _, _ -> bindContent() }
                    },
                ),
            )
            return
        }
        hideModifyMenu()
        val loading = clanEventController.isLoading(clanId)
        val loadError = clanEventController.getLoadError(clanId)
        when {
            loading -> contentHost.addView(buildLoadingView(ctx))
            loadError != null -> contentHost.addView(buildErrorView(ctx, loadError))
            else -> contentHost.addView(buildNotFoundView(ctx))
        }
    }

    private fun buildLoadingView(context: Context): View {
        return FrameLayout(context).apply {
            addView(
                ProgressBar(context),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
    }

    private fun buildErrorView(context: Context, message: String): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(48), LayoutHelper.dp(24), LayoutHelper.dp(24))
            addView(
                TextView(context).apply {
                    text = message.ifBlank { getString(R.string.clan_event_load_failed) }
                    textSize = 14f
                    setTextColor(themeColors.onSurfaceVariant)
                    gravity = Gravity.CENTER
                },
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.common_retry)
                    textSize = 14f
                    setTextColor(themeColors.blurple)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, LayoutHelper.dp(12), 0, 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { clanEventController.loadEvents(clanId, force = true) }
                },
            )
        }
    }

    private fun buildNotFoundView(context: Context): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(48), LayoutHelper.dp(24), LayoutHelper.dp(24))
            addView(
                TextView(context).apply {
                    text = getString(R.string.clan_event_not_found)
                    textSize = 14f
                    setTextColor(themeColors.onSurfaceVariant)
                    gravity = Gravity.CENTER
                },
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.common_retry)
                    textSize = 14f
                    setTextColor(themeColors.blurple)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, LayoutHelper.dp(12), 0, 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { clanEventController.loadEvents(clanId, force = true) }
                },
            )
        }
    }

    private fun currentUserId(): Long {
        val accountId = accountController.accountInfo.value.userId
        return accountId.takeIf { it != 0L } ?: userController.userId
    }

    private fun canModifyEvent(event: ClanEventEntity): Boolean {
        if (event.creatorId == 0L) return false
        val userId = currentUserId()
        if (userId != 0L && userId == event.creatorId) return true
        val profileId = userController.userIdStr
        return profileId.isNotEmpty() && profileId == event.creatorId.toString()
    }

    private fun updateActionBarMenu(event: ClanEventEntity) {
        if (isPaused || fragmentView == null) {
            hideModifyMenu()
            return
        }
        if (canModifyEvent(event)) {
            showModifyMenuButton()
        } else {
            hideModifyMenu()
        }
    }

    private fun showModifyMenuButton() {
        if (moreMenuItem != null) return
        val menu = actionBarMenu ?: return
        moreMenuItem = menu.addItem(MENU_MORE, R.drawable.ic_more_vertical_24).also { item ->
            item.setIconColor(themeColors.textStrong)
            item.contentDescription = getString(R.string.clan_event_menu_content_desc)
            item.getItemIconView().apply {
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    LayoutHelper.dp(48),
                    LayoutHelper.dp(48),
                    Gravity.CENTER,
                )
            }
        }
    }

    private fun hideModifyMenu() {
        dismissEventMenu()
        if (moreMenuItem != null) {
            actionBarMenu?.removeItem(MENU_MORE)
            moreMenuItem = null
        }
    }

    private fun showEventMenu() {
        val event = clanEventController.getEvent(clanId, eventId) ?: return
        if (!canModifyEvent(event)) {
            hideModifyMenu()
            return
        }
        val ctx = fragmentView?.context ?: return
        val anchor = moreMenuItem ?: return
        dismissEventMenu()
        val popup = PopupMenu(ctx, themeColors)
        popup.addItem(
            getString(R.string.clan_event_menu_edit),
            MezonIcon.pencilIcon.getDrawable(ctx, themeColors.colorText),
        )
        popup.addItem(
            getString(R.string.clan_event_menu_cancel),
            MezonIcon.trashIcon.getDrawable(ctx, themeColors.redStrong),
            destructive = true,
        )
        popup.setOnItemClickListener { index ->
            val latestEvent = clanEventController.getEvent(clanId, eventId) ?: return@setOnItemClickListener
            if (!canModifyEvent(latestEvent)) {
                hideModifyMenu()
                return@setOnItemClickListener
            }
            when (index) {
                0 -> {
                    dismissEventMenu()
                    presentFragment(ClanEventCreateFragment.newInstance(clanId, eventId))
                }
                1 -> {
                    dismissEventMenu()
                    confirmDeleteEvent(latestEvent)
                }
            }
        }
        eventMenuPopup = popup
        popup.show(anchor)
    }

    private fun dismissEventMenu() {
        eventMenuPopup?.dismiss()
        eventMenuPopup = null
    }

    private fun confirmDeleteEvent(event: ClanEventEntity) {
        val ctx = fragmentView?.context ?: return
        AlertsCreator.createConfirmDialog(
            context = ctx,
            title = getString(R.string.clan_event_delete_confirm_title),
            message = getString(R.string.clan_event_delete_confirm_message),
            confirmText = getString(R.string.clan_event_menu_cancel),
            cancelText = getString(R.string.common_cancel),
            destructive = true,
            onConfirm = { runDeleteEvent(event) },
        ).show()
    }

    private fun runDeleteEvent(event: ClanEventEntity) {
        if (deleting) return
        deleting = true
        val creatorId = accountController.accountInfo.value.userId
        clanEventController.deleteEvent(
            clanId = clanId,
            eventId = event.id,
            creatorId = creatorId,
            title = event.title,
            channelId = event.channelId,
        ) { success, error ->
            deleting = false
            if (success) {
                MezonToast.show(
                    this@ClanEventDetailFragment,
                    ToastOverlay.ToastType.SUCCESS,
                    getString(R.string.clan_event_delete_success),
                )
                finishFragment()
            } else {
                MezonToast.show(
                    this@ClanEventDetailFragment,
                    ToastOverlay.ToastType.ERROR,
                    error ?: getString(R.string.clan_event_delete_failed),
                )
            }
        }
    }

    private fun formatEventStartTime(context: Context, startTimeSeconds: Int): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "EEE, MMM d · HH:mm" else "EEE, MMM d · h:mm a"
        return DateTimeUtil.formatEpochSeconds(startTimeSeconds, pattern, Locale.getDefault())
    }

    private fun memberDisplayName(member: ClanMember?): String {
        if (member == null) return ""
        return member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
    }

    private fun memberAvatarUrl(member: ClanMember?): String {
        if (member == null) return ""
        return member.clanAvatar.ifBlank { member.avatarUrl }
    }

    private fun interestedSummary(context: Context, count: Int): String = when (count) {
        0 -> context.getString(R.string.clan_event_no_one_interested)
        1 -> context.getString(R.string.clan_event_one_person_interested)
        else -> context.getString(R.string.clan_event_people_interested, count)
    }

    private val EVENT_INFO_LEADING_DP = 24

    private val EVENT_INFO_ICON_DP = 20

    private fun buildInfoLeadingSlot(context: Context, content: View, contentSizeDp: Int = EVENT_INFO_ICON_DP): FrameLayout {
        return FrameLayout(context).apply {
            addView(
                content,
                FrameLayout.LayoutParams(
                    LayoutHelper.dp(contentSizeDp),
                    LayoutHelper.dp(contentSizeDp),
                    Gravity.CENTER,
                ),
            )
        }
    }

    private fun buildInfoIconLeading(
        context: Context,
        icon: MezonIcon,
        iconColor: Int,
    ): View {
        val iconView = ImageView(context).apply {
            setImageDrawable(icon.getDrawable(context, iconColor))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        return buildInfoLeadingSlot(context, iconView)
    }

    private fun buildInfoAvatarLeading(
        context: Context,
        userId: Long,
        name: String,
        avatarUrl: String,
    ): View {
        val avatar = AvatarView(context).apply {
            setSizeDp(EVENT_INFO_LEADING_DP)
            setRoundRadius(EVENT_INFO_LEADING_DP / 2f)
            setInfo(userId, name)
            if (avatarUrl.isNotEmpty()) {
                setImageUrl(avatarImgproxyUrl(avatarUrl, LayoutHelper.dp(EVENT_INFO_LEADING_DP)))
            }
        }
        return buildInfoLeadingSlot(context, avatar, EVENT_INFO_LEADING_DP)
    }

    private fun buildInlineInfoRow(
        context: Context,
        theme: ThemeColors,
        icon: MezonIcon,
        text: String,
        iconColor: Int = theme.colorText,
        textColor: Int = theme.onSurfaceVariant,
        topMarginDp: Float = 0f,
    ): LinearLayout {
        return buildDetailInfoRow(context, theme, buildInfoIconLeading(context, icon, iconColor), text, textColor, topMarginDp)
    }

    private fun buildDetailInfoRow(
        context: Context,
        theme: ThemeColors,
        leading: View,
        text: String,
        textColor: Int = theme.onSurfaceVariant,
        topMarginDp: Float = 0f,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                leading,
                LinearLayout.LayoutParams(LayoutHelper.dp(EVENT_INFO_LEADING_DP), LayoutHelper.dp(EVENT_INFO_LEADING_DP)),
            )
            addView(
                TextView(context).apply {
                    this.text = text
                    textSize = 14f
                    setTextColor(textColor)
                    setPadding(LayoutHelper.dp(10), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f),
            )
        }.also {
            if (topMarginDp > 0f) {
                it.layoutParams = LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT,
                    0f,
                    Gravity.START,
                    0f,
                    topMarginDp,
                    0f,
                    0f,
                )
            }
        }
    }

    private fun openEventChannel(channel: ClanChannelEntity) {
        onOpenChannel?.invoke(channel) ?: run {
            (getParentActivity() as? MainActivity)?.openChat(
                channel.channelId,
                channel.channelLabel,
                if (channel.clanId != 0L) channel.clanId else clanId,
                channel.type,
            )
        }
    }

    private fun buildEventLocationRow(
        context: Context,
        theme: ThemeColors,
        event: ClanEventEntity,
        voiceChannel: ClanChannelEntity?,
        topMarginDp: Float = 4f,
        onChannelClick: ((ClanChannelEntity) -> Unit)? = null,
    ): View {
        return if (event.isOfflineEvent()) {
            buildInlineInfoRow(
                context,
                theme,
                MezonIcon.locationIcon,
                event.address,
                iconColor = theme.textStrong,
                textColor = theme.textStrong,
                topMarginDp = topMarginDp,
            )
        } else {
            val label = voiceChannel?.channelLabel?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.clan_event_private_room)
            val row = buildInlineInfoRow(
                context,
                theme,
                MezonIcon.channelVoice,
                label,
                iconColor = if (voiceChannel != null) theme.blurple else theme.textStrong,
                textColor = if (voiceChannel != null) theme.blurple else theme.textStrong,
                topMarginDp = topMarginDp,
            )
            if (voiceChannel != null && onChannelClick != null) {
                applyChannelRowClick(row, voiceChannel, onChannelClick)
            }
            row
        }
    }

    private fun buildEventChannelDetailRow(
        context: Context,
        theme: ThemeColors,
        linkedChannel: ClanChannelEntity?,
        topMarginDp: Float = 8f,
        onChannelClick: ((ClanChannelEntity) -> Unit)? = null,
    ): View? {
        if (linkedChannel == null) return null
        return TextView(context).apply {
            text = context.getString(R.string.clan_event_channel_in, linkedChannel.channelLabel)
            textSize = 12f
            setTextColor(theme.blurple)
            if (onChannelClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onChannelClick(linkedChannel) }
            }
        }.also {
            it.layoutParams = LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                0f,
                Gravity.START,
                0f,
                topMarginDp,
                0f,
                0f,
            )
        }
    }

    private fun applyChannelRowClick(
        row: View,
        channel: ClanChannelEntity,
        onChannelClick: (ClanChannelEntity) -> Unit,
    ) {
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onChannelClick(channel) }
    }

    private fun buildInterestedMembersSection(
        context: Context,
        theme: ThemeColors,
        interestedIds: List<Long>,
        members: List<ClanMember>,
    ): View {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_event_interested_people)
                textSize = 14f
                setTextColor(theme.textStrong)
                typeface = Typeface.DEFAULT_BOLD
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 16f, 0f, 10f),
        )
        if (interestedIds.isEmpty()) {
            val empty = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, LayoutHelper.dp(12), 0, LayoutHelper.dp(12))
            }
            empty.addView(
                ImageView(context).apply {
                    setImageDrawable(MezonIcon.peopleIcon.getDrawable(context, theme.onSurfaceVariant))
                },
                LayoutHelper.createLinear(LayoutHelper.dp(24), LayoutHelper.dp(24), 0f, Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 8f),
            )
            empty.addView(
                TextView(context).apply {
                    text = context.getString(R.string.clan_event_no_one_interested)
                    textSize = 13f
                    setTextColor(theme.onSurfaceVariant)
                    gravity = Gravity.CENTER
                },
            )
            section.addView(empty)
            return section
        }
        val membersById = members.associateBy { it.userId }
        interestedIds.forEach { userId ->
            val member = membersById[userId]
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, LayoutHelper.dp(6), 0, LayoutHelper.dp(6))
            }
            row.addView(
                AvatarView(context).apply {
                    setSizeDp(40)
                    setRoundRadius(20f)
                    setInfo(userId, member?.username ?: "")
                    val avatar = memberAvatarUrl(member)
                    if (avatar.isNotEmpty()) setImageUrl(avatarImgproxyUrl(avatar, LayoutHelper.dp(40)))
                },
            )
            row.addView(
                TextView(context).apply {
                    text = memberDisplayName(member).ifBlank { context.getString(R.string.account_username) }
                    textSize = 14f
                    setTextColor(theme.colorText)
                    setPadding(LayoutHelper.dp(10), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f),
            )
            section.addView(row)
        }
        return section
    }

    private fun buildEventDetailView(
        context: Context,
        theme: ThemeColors,
        event: ClanEventEntity,
        clanName: String,
        clanLogo: String,
        creator: ClanMember?,
        members: List<ClanMember>,
        voiceChannel: ClanChannelEntity?,
        linkedChannel: ClanChannelEntity?,
        currentUserId: Long,
        onToggleInterest: () -> Unit,
    ): View {
        val contentPad = LayoutHelper.dp(16)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }
    
        if (event.logo.isNotBlank()) {
            val bannerWidthPx = AndroidUtilities.displaySize.x - LayoutHelper.dp(32)
            val bannerHeightPx = (bannerWidthPx * 9f / 16f).roundToInt()
            val corner = LayoutHelper.dpf(ClanEventCreateUi.EVENT_BANNER_CORNER_DP)
            val bannerShell = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = corner
                    setColor(theme.tertiary)
                }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipChildren = true
            }
            val bannerView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = false
            }
            bannerShell.addView(
                bannerView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            val reqW = max(bannerWidthPx * 2, LayoutHelper.dp(640)).coerceAtMost(1200)
            val reqH = (reqW * 9f / 16f).roundToInt()
            val bannerUrl = createImgproxyUrl(event.logo, reqW, reqH, "fill")
            MezonImageLoader.getInstance(context).load(
                bannerUrl,
                reqW,
                reqH,
                onSuccess = { bitmap -> bannerView.setImageBitmap(bitmap) },
            )
            root.addView(
                bannerShell,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    bannerHeightPx,
                ).apply {
                    marginStart = LayoutHelper.dp(16)
                    marginEnd = LayoutHelper.dp(16)
                    topMargin = LayoutHelper.dp(12)
                },
            )
        }
    
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(contentPad, contentPad, contentPad, contentPad)
        }
    
        val status = event.displayStatus()
        val statusColor = when (status) {
            ClanEventStatus.UPCOMING -> theme.blurple
            ClanEventStatus.ONGOING -> 0xFF16A34A.toInt()
            else -> theme.textStrong
        }
        val statusText = when (status) {
            ClanEventStatus.UPCOMING -> context.getString(R.string.clan_event_status_upcoming, event.minutesUntilStart())
            ClanEventStatus.ONGOING -> context.getString(R.string.clan_event_status_ongoing)
            else -> formatEventStartTime(context, event.startTimeSeconds)
        }
        content.addView(
            buildDetailInfoRow(
                context,
                theme,
                buildInfoIconLeading(context, MezonIcon.eventTimeIcon, statusColor),
                statusText,
                statusColor,
                0f,
            ),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 8f),
        )
    
        if (eventBadgeLabel(context, event) != null) {
            content.addView(
                buildEventBadge(context, theme, event),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 8f),
            )
        }
    
        content.addView(
            TextView(context).apply {
                text = event.title
                textSize = 20f
                setTextColor(theme.textStrong)
                typeface = Typeface.DEFAULT_BOLD
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f),
        )
    
        val infoBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(theme.tertiary)
            }
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
        }
        infoBlock.addView(
            buildDetailInfoRow(
                context,
                theme,
                buildInfoAvatarLeading(context, 0L, clanName, clanLogo),
                clanName,
                theme.colorText,
            ),
        )
        infoBlock.addView(
            buildEventLocationRow(context, theme, event, voiceChannel, 10f) { channel ->
                openEventChannel(channel)
            },
        )
        infoBlock.addView(
            buildInlineInfoRow(
                context,
                theme,
                MezonIcon.bellIcon,
                interestedSummary(context, event.interestedCount),
                iconColor = theme.colorText,
                topMarginDp = 10f,
            ),
        )
        infoBlock.addView(
            buildDetailInfoRow(
                context,
                theme,
                buildInfoAvatarLeading(
                    context,
                    creator?.userId ?: 0L,
                    memberDisplayName(creator),
                    memberAvatarUrl(creator),
                ),
                context.getString(R.string.clan_event_created_by, memberDisplayName(creator)),
                theme.onSurfaceVariant,
                10f,
            ),
        )
        content.addView(
            infoBlock,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f),
        )
    
        if (event.description.isNotBlank()) {
            content.addView(
                TextView(context).apply {
                    text = event.description
                    textSize = 14f
                    setTextColor(theme.colorText)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 12f),
            )
        }
    
        if (event.endTimeSeconds > event.startTimeSeconds) {
            content.addView(
                buildInlineInfoRow(
                    context,
                    theme,
                    MezonIcon.eventTimeIcon,
                    context.getString(R.string.clan_event_ends_at, formatEventStartTime(context, event.endTimeSeconds)),
                    iconColor = theme.onSurfaceVariant,
                    topMarginDp = 0f,
                ),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 8f),
            )
        }
    
        buildEventChannelDetailRow(context, theme, linkedChannel, 0f) { channel ->
            openEventChannel(channel)
        }?.let {
            content.addView(it, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 8f, 0f, 0f))
        }
    
        val interested = event.isInterested(currentUserId)
        content.addView(
            buildEventActionChip(
                context,
                theme,
                if (interested) MezonIcon.bellSlashIcon else MezonIcon.bellIcon,
                if (interested) context.getString(R.string.clan_event_uninterested) else context.getString(R.string.clan_event_interested),
                onToggleInterest,
            ),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 12f, 0f, 0f),
        )

        content.addView(
            View(context).apply { setBackgroundColor(theme.outlineVariant) },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0f, Gravity.NO_GRAVITY, 0f, 4f, 0f, 0f),
        )
        content.addView(buildInterestedMembersSection(context, theme, event.interestedUserIds(), members))
        root.addView(
            content,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )
        return root
    }

    private fun eventBadgeLabel(context: Context, event: ClanEventEntity): String? = when {
        event.isPrivate -> context.getString(R.string.clan_event_badge_private)
        event.channelId != 0L -> context.getString(R.string.clan_event_badge_channel)
        else -> context.getString(R.string.clan_event_badge_clan)
    }

    private fun eventBadgeColor(theme: ThemeColors, event: ClanEventEntity): Int = when {
        event.isPrivate -> theme.onSurfaceVariant
        event.channelId != 0L -> 0xFFF97316.toInt()
        else -> theme.blurple
    }

    private fun buildEventBadge(context: Context, theme: ThemeColors, event: ClanEventEntity): TextView {
        return TextView(context).apply {
            text = eventBadgeLabel(context, event).orEmpty()
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            val padH = LayoutHelper.dp(8)
            val padV = LayoutHelper.dp(3)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(6f).toFloat()
                setColor(eventBadgeColor(theme, event))
            }
        }
    }

    private fun buildEventActionChip(
        context: Context,
        theme: ThemeColors,
        icon: MezonIcon,
        label: String?,
        onClick: () -> Unit,
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
            )
            val padV = LayoutHelper.dp(10)
            val padH = LayoutHelper.dp(12)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(theme.border)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                ImageView(context).apply {
                    setImageDrawable(icon.getDrawable(context, theme.colorText))
                },
                LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)),
            )
            if (!label.isNullOrBlank()) {
                addView(
                    TextView(context).apply {
                        text = label
                        textSize = 13f
                        setTextColor(theme.colorText)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(LayoutHelper.dp(8), 0, 0, 0)
                    },
                )
            }
        }
    }
}
