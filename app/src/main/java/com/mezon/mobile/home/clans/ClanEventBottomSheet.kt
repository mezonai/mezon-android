package com.mezon.mobile.home.clans

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.DateTimeUtil
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class ClanEventBottomSheet(
    context: Context,
    private val theme: ThemeColors,
    private val notificationCenter: NotificationCenter,
    private val clanEventController: ClanEventController,
    private val userClanController: UserClanController,
    private val clanId: Long,
    private val onCreateEvent: Runnable,
    private val onOpenEventDetail: (ClanEventEntity) -> Unit,
    private val onOpenChannel: (ClanChannelEntity) -> Unit,
    private val onEditEvent: (ClanEventEntity) -> Unit,
    private val onInviteExternalEvent: (String) -> Unit,
) : BottomSheet(context) {

    private val logoLoadTokens = ArrayList<MezonImageLoader.Cancellable>()
    private val eventsLoadedObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            val idArg = args.firstOrNull() as? Long ?: return
            if (idArg == clanId) loadClanEvent()
        }
    }
    private val membersLoadedObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            val idArg = args.firstOrNull() as? Long ?: return
            if (idArg == clanId) loadClanEvent()
        }
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(theme.surface)
    }
    private val scrollContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), LayoutHelper.dp(16))
    }
    private val listContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val loadingView = ProgressBar(context)
    private val errorView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }
    private val emptyView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }
    private lateinit var titleView: TextView
    private lateinit var headerLayout: FrameLayout
    private var eventScrollView: NestedScrollView
    private var swipeDismissFromHeader = false
    private var hiddenForNavigation = false
    private var endingEvent = false

    init {
        containerHeight = (AndroidUtilities.displaySize.y * 0.8f).toInt()
        buildHeader()
        buildEmptyState()
        buildErrorState()
        scrollContent.addView(
            loadingView,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 12f, 0f, 12f),
        )
        scrollContent.addView(errorView)
        scrollContent.addView(emptyView)
        scrollContent.addView(listContainer)
        eventScrollView = NestedScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                scrollContent,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            eventScrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setCustomView(root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixNavigationBar(theme.surface)
        userClanController.loadClanMembers(clanId)
        clanEventController.loadEvents(clanId, force = true)
        setOnDismissListener {
            logoLoadTokens.forEach { it.cancel() }
            logoLoadTokens.clear()
            notificationCenter.removeObserver(eventsLoadedObserver, NotificationCenter.clanEventsDidLoad)
            notificationCenter.removeObserver(membersLoadedObserver, NotificationCenter.clanMembersDidLoad)
            notificationCenter.removeObserver(membersLoadedObserver, NotificationCenter.clanRolesDidLoad)
        }
        notificationCenter.addObserver(eventsLoadedObserver, NotificationCenter.clanEventsDidLoad)
        notificationCenter.addObserver(membersLoadedObserver, NotificationCenter.clanMembersDidLoad)
        notificationCenter.addObserver(membersLoadedObserver, NotificationCenter.clanRolesDidLoad)
        loadClanEvent()
    }

    fun loadClanEvent() {
        logoLoadTokens.forEach { it.cancel() }
        logoLoadTokens.clear()
        val userId = clanEventController.currentUserId()
        val events = clanEventController.visibleEvents(clanId, userId)
        val loading = clanEventController.isLoading(clanId)
        val loadError = clanEventController.getLoadError(clanId)
        loadingView.visibility = if (loading && events.isEmpty()) View.VISIBLE else View.GONE
        errorView.visibility = if (!loading && loadError != null && events.isEmpty()) View.VISIBLE else View.GONE
        listContainer.removeAllViews()
        listContainer.visibility = if (events.isNotEmpty()) View.VISIBLE else View.GONE
        emptyView.visibility = if (!loading && loadError == null && events.isEmpty()) View.VISIBLE else View.GONE
        updateHeaderCount(events.size)
        events.forEachIndexed { index, event ->
            val creator = userClanController.getClanMembers(clanId).firstOrNull { it.userId == event.creatorId }
            val voiceChannel = clanEventController.getChannel(clanId, event.channelVoiceId)
            val linkedChannel = clanEventController.getChannel(clanId, event.channelId)
            listContainer.addView(
                buildEventRow(
                    context,
                    theme,
                    event,
                    creator,
                    userId,
                    voiceChannel,
                    linkedChannel,
                    onOpen = {
                        onOpenEventDetail(event)
                    },
                    onOpenChannel = { channel ->
                        dismiss()
                        onOpenChannel(channel)
                    },
                    onToggleInterest = {
                        val interested = !event.isInterested(userId)
                        clanEventController.setInterested(clanId, event.id, interested) { _, _ -> }
                    },
                    onEndEvent = if (clanEventController.canEndEvent(event)) {
                        { confirmEndEvent(event) }
                    } else null,
                    onEdit = if (clanEventController.canModifyEvent(event)) {
                        {
                            val latest = clanEventController.getEvent(clanId, event.id)
                            if (latest != null && clanEventController.canModifyEvent(latest)) {
                                onEditEvent(latest)
                            }
                        }
                    } else null,
                    onInviteExternalEvent = onInviteExternalEvent,
                    onLogoLoadToken = { logoLoadTokens.add(it) },
                ),
                LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT,
                    0f,
                    Gravity.NO_GRAVITY,
                    0f,
                    0f,
                    0f,
                    if (index < events.lastIndex) 10f else 0f,
                ),
            )
        }
    }

    private fun confirmEndEvent(event: ClanEventEntity) {
        val latest = clanEventController.getEvent(clanId, event.id) ?: return
        if (endingEvent || !clanEventController.canEndEvent(latest)) return
        AlertsCreator.createConfirmDialog(
            context = context,
            title = context.getString(R.string.clan_event_delete_confirm_title),
            message = context.getString(R.string.clan_event_delete_confirm_message),
            confirmText = context.getString(R.string.clan_event_menu_cancel),
            cancelText = context.getString(R.string.common_cancel),
            destructive = true,
            onConfirm = {
                endingEvent = true
                clanEventController.deleteEvent(clanId, latest.id, latest.creatorId, latest.title, latest.channelId) { success, error ->
                    endingEvent = false
                    val message = if (success) context.getString(R.string.clan_event_delete_success)
                        else error ?: context.getString(R.string.clan_event_delete_failed)
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
        ).show()
    }

    fun hideForNavigation() {
        if (isDismissed() || hiddenForNavigation) return
        hiddenForNavigation = true
        window?.decorView?.visibility = View.GONE
    }

    fun restoreAfterNavigation() {
        if (isDismissed() || !hiddenForNavigation) return
        hiddenForNavigation = false
        window?.decorView?.visibility = View.VISIBLE
    }

    override fun canDismissWithSwipe(): Boolean = swipeDismissFromHeader

    override fun onContainerTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> swipeDismissFromHeader = isInHeaderZone(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeDismissFromHeader = false
        }
        return false
    }

    private fun isInHeaderZone(ev: MotionEvent): Boolean {
        if (!::headerLayout.isInitialized || !headerLayout.isShown) return false
        val loc = IntArray(2)
        headerLayout.getLocationOnScreen(loc)
        return ev.rawY <= loc[1] + headerLayout.height
    }

    private fun buildHeader() {
        val padH = LayoutHelper.dp(16)
        headerLayout = FrameLayout(context).apply {
            setPadding(padH, LayoutHelper.dp(4), padH, LayoutHelper.dp(12))
            setBackgroundColor(theme.surface)
        }
        titleView = TextView(context).apply {
            textSize = 15f
            setTextColor(theme.textStrong)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        headerLayout.addView(
            titleView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        val createPadH = LayoutHelper.dp(14)
        val createPadV = LayoutHelper.dp(7)
        headerLayout.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_event_create)
                textSize = 13f
                setTextColor(theme.onPrimary)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(createPadH, createPadV, createPadH, createPadV)
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(8f).toFloat()
                    setColor(theme.blurple)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onCreateEvent.run()
                }
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        root.addView(headerLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        updateHeaderCount(0)
    }

    private fun updateHeaderCount(count: Int) {
        titleView.text = if (count == 1) {
            context.getString(R.string.clan_event_header_one)
        } else {
            context.getString(R.string.clan_event_header_many, count)
        }
    }

    private fun buildEmptyState() {
        emptyView.removeAllViews()
        emptyView.addView(
            FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.tertiary)
                }
                addView(
                    ImageView(context).apply {
                        setImageDrawable(MezonIcon.eventTimeIcon.getDrawable(context, theme.onSurfaceVariant))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    FrameLayout.LayoutParams(LayoutHelper.dp(24), LayoutHelper.dp(24), Gravity.CENTER),
                )
            },
            LayoutHelper.createLinear(LayoutHelper.dp(16), LayoutHelper.dp(16), 0f, Gravity.CENTER_HORIZONTAL, 0f, 8f, 0f, 4f),
        )
        emptyView.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_event_empty_title)
                textSize = 16f
                setTextColor(theme.colorText)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
        )
        emptyView.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_event_empty_desc)
                textSize = 13f
                setTextColor(theme.onSurfaceVariant)
                gravity = Gravity.CENTER
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(6), LayoutHelper.dp(12), 0)
            },
        )
    }

    private fun buildErrorState() {
        errorView.removeAllViews()
        errorView.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_event_load_failed)
                textSize = 14f
                setTextColor(theme.onSurfaceVariant)
                gravity = Gravity.CENTER
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 12f, 0f, 8f),
        )
        errorView.addView(
            TextView(context).apply {
                text = context.getString(R.string.common_retry)
                textSize = 14f
                setTextColor(theme.blurple)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { clanEventController.loadEvents(clanId, force = true) }
            },
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 12f),
        )
    }

    private fun formatEventStartTime(context: Context, startTimeSeconds: Int): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "EEE, MMM d · HH:mm" else "EEE, MMM d · h:mm a"
        return DateTimeUtil.formatEpochSeconds(startTimeSeconds, pattern, Locale.getDefault())
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
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener { onChannelClick(voiceChannel) }
            }
            row
        }
    }

    private fun buildEventLinkedChannelRow(
        context: Context,
        theme: ThemeColors,
        linkedChannel: ClanChannelEntity?,
        topMarginDp: Float = 4f,
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
            layoutParams = LayoutHelper.createLinear(
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

    private fun eventBadgeLabel(context: Context, event: ClanEventEntity): String = when {
        event.isPrivate -> context.getString(R.string.clan_event_badge_private)
        event.channelId != 0L -> context.getString(R.string.clan_event_badge_channel)
        else -> context.getString(R.string.clan_event_badge_clan)
    }

    private fun eventBadgeColor(event: ClanEventEntity): Int = when {
        event.isPrivate -> 0xFFEF4444.toInt()
        event.channelId != 0L -> 0xFFF97316.toInt()
        else -> 0xFF3B82F6.toInt()
    }

    private fun buildEventBadge(context: Context, event: ClanEventEntity): TextView {
        return TextView(context).apply {
            text = eventBadgeLabel(context, event)
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            val padH = LayoutHelper.dp(8)
            val padV = LayoutHelper.dp(3)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(6f).toFloat()
                setColor(eventBadgeColor(event))
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

    private fun externalEventUrl(event: ClanEventEntity): String? {
        if (!event.isPrivate || event.externalLink.isBlank()) return null
        val baseUrl = BuildConfig.MEZON_REDIRECT_URI.trimEnd('/')
        return "$baseUrl/${event.externalLink.trimStart('/')}"
    }

    private fun openExternalEvent(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, R.string.qr_external_link_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyExternalEventLink(url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("external_event", url))
        Toast.makeText(context, R.string.invite_link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun buildExternalEventAction(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(theme.textLink)
            gravity = Gravity.CENTER
            maxLines = 2
            minHeight = LayoutHelper.dp(40)
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(8f)
                setStroke(LayoutHelper.dp(1), theme.outlineVariant)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun buildEventRow(
        context: Context,
        theme: ThemeColors,
        event: ClanEventEntity,
        creator: ClanMember?,
        currentUserId: Long,
        voiceChannel: ClanChannelEntity?,
        linkedChannel: ClanChannelEntity?,
        onOpen: () -> Unit,
        onOpenChannel: (ClanChannelEntity) -> Unit,
        onToggleInterest: () -> Unit,
        onEndEvent: (() -> Unit)?,
        onEdit: (() -> Unit)?,
        onInviteExternalEvent: (String) -> Unit,
        onLogoLoadToken: (MezonImageLoader.Cancellable) -> Unit,
    ): View {
        val pad = LayoutHelper.dp(16)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(12f)
                setColor(theme.secondaryLight)
                setStroke(LayoutHelper.dp(1), theme.outlineVariant)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val openClick = View.OnClickListener { onOpen() }
    
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener(openClick)
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
        if (event.isStartToday()) {
            topRow.addView(
                TextView(context).apply {
                    text = context.getString(R.string.clan_event_badge_new)
                    textSize = 10f
                    setTextColor(0xFFFFFFFF.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                    val badgePadH = LayoutHelper.dp(6)
                    val badgePadV = LayoutHelper.dp(2)
                    setPadding(badgePadH, badgePadV, badgePadH, badgePadV)
                    background = GradientDrawable().apply {
                        cornerRadius = LayoutHelper.dp(4f).toFloat()
                        setColor(0xFF16A34A.toInt())
                    }
                },
                LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                    rightMargin = LayoutHelper.dp(6)
                },
            )
        }
        topRow.addView(
            ImageView(context).apply {
                setImageDrawable(MezonIcon.eventTimeIcon.getDrawable(context, statusColor))
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)),
        )
        topRow.addView(
            TextView(context).apply {
                text = statusText
                textSize = 12f
                setTextColor(statusColor)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(LayoutHelper.dp(6), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f),
        )
    
        val rightMeta = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rightMeta.addView(
            AvatarView(context).apply {
                setSizeDp(24)
                setRoundRadius(12f)
                val name = creator?.displayName.orEmpty().ifBlank { creator?.username.orEmpty() }
                setInfo(creator?.userId ?: 0L, name)
                val avatar = creator?.clanAvatar?.ifBlank { creator.avatarUrl }.orEmpty()
                if (avatar.isNotEmpty()) setImageUrl(avatar)
            },
        )
        rightMeta.addView(
            ImageView(context).apply {
                setImageDrawable(MezonIcon.groupIcon.getDrawable(context, theme.colorText))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(LayoutHelper.dp(6), 0, LayoutHelper.dp(2), 0)
            },
            LinearLayout.LayoutParams(LayoutHelper.dp(14), LayoutHelper.dp(14)),
        )
        rightMeta.addView(
            TextView(context).apply {
                text = event.interestedCount.toString()
                textSize = 12f
                setTextColor(theme.colorText)
            },
        )
        topRow.addView(rightMeta)
        content.addView(
            topRow,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 10f),
        )
    
        val mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        }
        val titleBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener(openClick)
        }
        titleBlock.addView(
            buildEventBadge(context, event),
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 6f),
        )
        titleBlock.addView(
            TextView(context).apply {
                text = event.title
                textSize = 15f
                setTextColor(theme.textStrong)
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        if (event.description.isNotBlank()) {
            titleBlock.addView(
                TextView(context).apply {
                    text = event.description
                    textSize = 13f
                    setTextColor(theme.onSurfaceVariant)
                    maxLines = 2
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 0f),
            )
        }
        textCol.addView(titleBlock)
        textCol.addView(
            buildEventLocationRow(context, theme, event, voiceChannel, 4f, onOpenChannel),
        )
        buildEventLinkedChannelRow(context, theme, linkedChannel, 4f, onOpenChannel)?.let {
            textCol.addView(it)
        }
        mainRow.addView(textCol)
        if (event.logo.isNotBlank()) {
            val thumb = ClanEventCreateUi.buildEventLogoThumbnail(context, theme, event.logo, onLogoLoadToken)
            thumb.isClickable = true
            thumb.isFocusable = true
            thumb.setOnClickListener(openClick)
            mainRow.addView(
                thumb,
                LinearLayout.LayoutParams(LayoutHelper.dp(ClanEventCreateUi.EVENT_THUMB_SIZE_DP), LayoutHelper.dp(ClanEventCreateUi.EVENT_THUMB_SIZE_DP)).apply {
                    leftMargin = LayoutHelper.dp(10)
                },
            )
        }
        content.addView(
            mainRow,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 10f),
        )

        if (event.isPrivate) {
            content.addView(TextView(context).apply {
                text = context.getString(R.string.event_creator_type_private_desc)
                textSize = 13f
                setTextColor(theme.onSurfaceVariant)
            }, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                0f,
                Gravity.START,
                0f,
                2f,
                0f,
                0f,
            ))

            externalEventUrl(event)?.let { url ->
                val externalActions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                externalActions.addView(
                    buildExternalEventAction(context.getString(R.string.qr_external_link_open)) {
                        openExternalEvent(url)
                    },
                    LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
                        marginEnd = LayoutHelper.dp(4)
                    },
                )
                externalActions.addView(
                    buildExternalEventAction(context.getString(R.string.clan_event_invite_action)) {
                        onInviteExternalEvent(url)
                    },
                    LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
                        marginStart = LayoutHelper.dp(4)
                        marginEnd = LayoutHelper.dp(4)
                    },
                )
                externalActions.addView(
                    buildExternalEventAction(context.getString(R.string.channel_menu_copy_link)) {
                        copyExternalEventLink(url)
                    },
                    LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
                        marginStart = LayoutHelper.dp(4)
                    },
                )
                content.addView(
                    externalActions,
                    LayoutHelper.createLinear(
                        LayoutHelper.MATCH_PARENT,
                        LayoutHelper.WRAP_CONTENT,
                        0f,
                        Gravity.NO_GRAVITY,
                        0f,
                        12f,
                        0f,
                        0f,
                    ),
                )
            }
        }

        val interested = event.isInterested(currentUserId)
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (onEdit != null) {
            actions.addView(
                buildEventActionChip(context, theme, MezonIcon.pencilIcon,
                    context.getString(R.string.clan_event_menu_edit), onEdit),
                LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
                    marginEnd = LayoutHelper.dp(8)
                },
            )
        }
        if (status != ClanEventStatus.ONGOING) {
            actions.addView(
                buildEventActionChip(
                    context,
                    theme,
                    if (interested) MezonIcon.eventBellSlashIcon else MezonIcon.eventBellIcon,
                    if (interested) context.getString(R.string.clan_event_uninterested) else context.getString(R.string.clan_event_interested),
                    onToggleInterest,
                ),
                LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f),
            )
        } else if (onEndEvent != null) {
            actions.addView(
                buildEventActionChip(context, theme, MezonIcon.closeIcon, context.getString(R.string.clan_event_end), onEndEvent),
                LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f),
            )
        }
        actions.visibility = if (actions.childCount == 0) View.GONE else View.VISIBLE
        content.addView(
            actions,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 12f, 0f, 8f),
        )
        root.addView(
            content,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )
        return root
    }
}
