package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.home.profile.AccountController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.mezon.mobile.search.GlobalSearchFragment
import com.mezon.mobile.ui.cells.MezonIcon

class ClansFragment : BaseFragment() {

    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController
    private lateinit var chatController: ChatController
    private lateinit var dialogsController: DialogsController
    private lateinit var accountController: AccountController
    private lateinit var userClanController:    UserClanController

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null
    var onSwitchToMessages: (() -> Unit)? = null

    private lateinit var serverRail: RecyclerListView
    private lateinit var channelListView: ChannelListView
    private lateinit var serverAdapter: ServerRailAdapter
    private var listFrozen = false

    // Clan header views
    private var bannerImage: ImageView? = null
    private var bannerCancellable: MezonImageLoader.Cancellable? = null
    private lateinit var clanNameText: TextView
    private var verifiedIcon: ImageView? = null
    private lateinit var memberCountText: TextView
    private var communityDot: View? = null
    private var communityLabel: TextView? = null
    private var viewJustCreated = false

    override fun onInject(entryPoint: FragmentEntryPoint) {
        clansController = entryPoint.clansController()
        channelController = entryPoint.channelController()
        chatController = entryPoint.chatController()
        dialogsController = entryPoint.dialogsController()
        accountController = entryPoint.accountController()
        userClanController = entryPoint.userClanController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.clansDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused || listFrozen) return@observe
            updateServerRail()
        }
        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            if (fragmentView == null || listFrozen) return@observe
            val clanId = args.firstOrNull() as? Long ?: return@observe
            if (clanId == clansController.selectedClanId.value) {
                updateChannelList()
            }
        }
        observe(NotificationCenter.clanInfoUpdated) { _, _, _ ->
            if (fragmentView == null || isPaused || listFrozen) return@observe
            updateServerRail()
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val clanId = args.firstOrNull() as? Long ?: return@observe
            if (clanId == clansController.selectedClanId.value) updateMemberCount()
        }
        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            if (fragmentView == null || isPaused || listFrozen) return@observe
            updateServerRail()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null || isPaused || listFrozen) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            updateVisibleRows(mask)
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.background)
            serverRail.setBackgroundColor(themeColors.serverRailBg)
            serverAdapter.notifyDataSetChanged()
            channelListView.invalidateTheme()
            // Refresh channel panel background
            (channelListView.parent as? ViewGroup)?.setBackgroundColor(themeColors.channelPanelBg)
        }

        clansController.loadClans()
        observe(NotificationCenter.selectedClanChanged) { _, _, args ->
            if (fragmentView == null || !clansController.clansLoaded) return@observe
            val clanId = args.firstOrNull() as? Long ?: 0L
            updateServerRail()
            if (clanId != 0L) updateChannelList()
        }
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(themeColors.background)
        }

        serverRail = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(themeColors.serverRailBg)
            isVerticalScrollBarEnabled = false
            setSelectorType(RecyclerListView.SELECTOR_CIRCLE_TO_BOUND)
        }
        serverAdapter = ServerRailAdapter()
        serverRail.adapter = serverAdapter
        serverRail.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
            when (view) {
                is ClanCell -> {
                    val clan = view.currentClan ?: return@OnItemClickListener
                    onClanSelected(clan)
                }
                is UnreadDmCell -> {
                    val dm = view.directMessage ?: return@OnItemClickListener
                    onOpenChat?.invoke(dm.channelId, dm.displayName.ifEmpty { dm.label }, 0L, dm.type)
                }
            }
        })

        val channelPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.channelPanelBg)
        }

        val clanHeader = buildClanHeader(context)

        channelListView = ChannelListView(context, themeColors).apply {
            onChannelClick = { channel -> onChannelSelected(channel) }
        }

        channelPanel.addView(clanHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        channelPanel.addView(channelListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        root.addView(serverRail, LayoutHelper.createLinear(56, LayoutHelper.MATCH_PARENT))
        root.addView(channelPanel, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))

        if (clansController.clansLoaded) {
            updateServerRail()
            val selectedId = clansController.selectedClanId.value
            if (selectedId != 0L) {
                updateChannelList()
                userClanController.loadClanMembers(selectedId)
            }
        }
        viewJustCreated = true

        return root
    }

    private fun buildClanHeader(context: Context): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.channelPanelBg)
        }

        // Banner image (RN: size.s_70 * 2 = 140)
        bannerImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        header.addView(bannerImage, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 140))

        // Content area (RN: paddingVertical s_14, paddingHorizontal s_12)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(14), LayoutHelper.dp(12), LayoutHelper.dp(14))
        }

        // Row 1: Clan name + verified badge (RN: gap Metrics.size.s=8, paddingBottom s_4)
        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(4))
        }
        clanNameText = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 16f // RN: s_16
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 500, false) // RN: fontWeight 500
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        nameRow.addView(clanNameText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        verifiedIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.verifyIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.blurple, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        nameRow.addView(verifiedIcon, LinearLayout.LayoutParams(
            LayoutHelper.dp(18), LayoutHelper.dp(18) // RN: s_18
        ).apply { leftMargin = LayoutHelper.dp(8) }) // RN: gap = Metrics.size.s = 8
        content.addView(nameRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Row 2: Member count + dot + Community (RN: subtitle 12sp, dot 4dp margin 8dp)
        val subtitleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        memberCountText = TextView(context).apply {
            setTextColor(themeColors.textDisabled)
            textSize = 12f // RN: s_12
            text = ""
        }
        subtitleRow.addView(memberCountText, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT
        ))
        communityDot = View(context).apply {
            val dotBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColors.blurple) // RN: baseColor.violetBlue
            }
            background = dotBg
            visibility = View.GONE
        }
        subtitleRow.addView(communityDot, LinearLayout.LayoutParams(
            LayoutHelper.dp(4), LayoutHelper.dp(4) // RN: s_4
        ).apply {
            leftMargin = LayoutHelper.dp(8) // RN: marginHorizontal s_8
            rightMargin = LayoutHelper.dp(8)
        })
        communityLabel = TextView(context).apply {
            setTextColor(themeColors.onSurface) // RN: textStrong
            textSize = 12f // RN: s_12
            text = "Community"
            visibility = View.GONE
        }
        subtitleRow.addView(communityLabel, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT
        ))
        content.addView(subtitleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Row 3: Navigation bar (RN: marginTop s_10, gap s_8)
        val navBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, LayoutHelper.dp(10), 0, 0) // RN: marginTop s_10
        }

        // Search pill — FrameLayout for absolute icon positioning (RN: icon position absolute left:12, text centered)
        val searchPill = FrameLayout(context).apply {
            val pillBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dp(32f).toFloat()
            }
            val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = LayoutHelper.dp(32f).toFloat()
            }
            val rippleColor = android.content.res.ColorStateList.valueOf(themeColors.onSurface and 0x1AFFFFFF)
            background = android.graphics.drawable.RippleDrawable(rippleColor, pillBg, rippleMask)
            isClickable = true
            isFocusable = true
            setOnClickListener { openSearch() }
        }
        // Search icon pinned left (RN: position absolute, left s_12)
        val searchIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.searchIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        searchPill.addView(searchIcon, FrameLayout.LayoutParams(
            LayoutHelper.dp(18), LayoutHelper.dp(18), // RN: s_18
            Gravity.START or Gravity.CENTER_VERTICAL
        ).apply { leftMargin = LayoutHelper.dp(12) }) // RN: left s_12
        // Search text centered (RN: justifyContent center, fontSize s_14)
        val searchText = TextView(context).apply {
            text = "Search"
            setTextColor(themeColors.onSurfaceVariant) // RN: colors.text
            textSize = 14f // RN: s_14
            gravity = Gravity.CENTER
        }
        searchPill.addView(searchText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        navBar.addView(searchPill, LinearLayout.LayoutParams(
            0, LayoutHelper.dp(32), 1f // RN: flex 1, height s_32
        ))

        // QR button (RN: iconWrapper 32dp, QrIcon 16dp)
        val qrButton = ImageView(context).apply {
            val circleBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColors.tertiary)
            }
            background = circleBg
            setImageDrawable(MezonIcon.scanQR.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = LayoutHelper.dp(8) // (32-16)/2 = 8dp padding for 16dp icon
            setPadding(pad, pad, pad, pad)
        }
        navBar.addView(qrButton, LinearLayout.LayoutParams(
            LayoutHelper.dp(32), LayoutHelper.dp(32) // RN: s_32
        ).apply { leftMargin = LayoutHelper.dp(8) }) // RN: gap s_8

        // Event/Calendar button (RN: iconWrapper 32dp, EventIcon 18dp)
        val eventButton = ImageView(context).apply {
            val circleBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColors.tertiary)
            }
            background = circleBg
            setImageDrawable(MezonIcon.calendarIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = LayoutHelper.dp(7) // (32-18)/2 = 7dp padding for 18dp icon
            setPadding(pad, pad, pad, pad)
        }
        navBar.addView(eventButton, LinearLayout.LayoutParams(
            LayoutHelper.dp(32), LayoutHelper.dp(32) // RN: s_32
        ).apply { leftMargin = LayoutHelper.dp(8) }) // RN: gap s_8

        content.addView(navBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        header.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Bottom divider (RN: borderBottomWidth 0.5)
        val divider = View(context).apply {
            setBackgroundColor(themeColors.outlineVariant)
        }
        header.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1))

        return header
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()

        if (viewJustCreated) {
            viewJustCreated = false
            return
        }
        if (clansController.clansLoaded) {
            updateVisibleRows(0)
        }
    }

    fun setListFrozen(frozen: Boolean) {
        if (listFrozen == frozen) return
        listFrozen = frozen
        if (!frozen && fragmentView != null) {
            updateServerRail()
            updateChannelList()
        }
    }

    private fun updateVisibleRows(mask: Int) {
        if (isPaused && mask != 0) {
            if ((mask and NotificationCenter.UPDATE_MASK_BADGE) != 0) {
                updateServerRail()
            }
            return
        }
        if (mask == 0) {
            updateServerRail()
            updateChannelList()
            return
        }

        if ((mask and NotificationCenter.UPDATE_MASK_BADGE) != 0) {
            updateServerRail()
        }

        val clans = clansController.clans.value
        val selectedId = clansController.selectedClanId.value
        val clanMap = HashMap<Long, ClanEntity>(clans.size)
        for (c in clans) clanMap[c.clanId] = c

        val count = serverRail.childCount
        for (i in 0 until count) {
            val child = serverRail.getChildAt(i)
            if (child is ClanCell) {
                val entity = child.currentClan ?: continue
                val updated = clanMap[entity.clanId]
                child.update(mask, updated, entity.clanId == selectedId)
            }
        }

        val channels = channelController.getChannels(selectedId)
        val channelMap = channels.associateBy { it.channelId }
        channelListView.updateVisibleRows(mask, channelMap)
    }

    private fun updateServerRail() {
        val unreadDms = dialogsController.getDialogs()
            .filter { it.unreadCount > 0 && !it.isMute }
        val clans = clansController.clans.value
        val selectedId = clansController.selectedClanId.value
        val logoUrl = accountController.accountInfo.value.logo
        serverAdapter.submitData(unreadDms, clans, selectedId, newLogoUrl = logoUrl)

        val selected = clans.find { it.clanId == selectedId }
        if (selected != null) updateClanHeader(selected)
    }

    private fun updateClanHeader(clan: ClanEntity) {
        clanNameText.text = clan.clanName

        // Verified badge
        verifiedIcon?.visibility = if (clan.isCommunity) View.VISIBLE else View.GONE

        // Community subtitle
        communityDot?.visibility = if (clan.isCommunity) View.VISIBLE else View.GONE
        communityLabel?.visibility = if (clan.isCommunity) View.VISIBLE else View.GONE

        // Banner
        if (clan.banner.isNotEmpty()) {
            bannerImage?.visibility = View.VISIBLE
            bannerCancellable?.cancel()
            val context = bannerImage?.context ?: return
            val loader = MezonImageLoader.getInstance(context)
            bannerCancellable = loader.load(clan.banner, 800, LayoutHelper.dp(140), onSuccess = { bitmap ->
                bannerImage?.setImageBitmap(bitmap)
            })
        } else {
            bannerImage?.visibility = View.GONE
        }

        updateMemberCount()
    }

    private fun updateMemberCount() {
        val clanId = clansController.selectedClanId.value
        val count = userClanController.getClanMemberCount(clanId)
        memberCountText.text = if (count > 0) "$count Members" else ""
    }

    private fun openSearch() {
        val fragment = GlobalSearchFragment().apply {
            this.onOpenChat = this@ClansFragment.onOpenChat
        }
        presentFragment(fragment)
    }

    private fun updateChannelList() {
        val clanId = clansController.selectedClanId.value
        val sections = channelController.getChannelSections(clanId)
        channelListView.bind(sections)
    }

    private fun onClanSelected(clan: ClanEntity) {
        val prevId = clansController.selectedClanId.value
        if (clan.clanId == prevId) return
        channelListView.resetExpansion()
        clansController.selectClan(clan.clanId)
        channelListView.clear()
        updateClanHeader(clan)

        val count = serverRail.childCount
        for (i in 0 until count) {
            val child = serverRail.getChildAt(i)
            if (child is ClanCell) {
                val c = child.currentClan ?: continue
                if (c.clanId == clan.clanId || c.clanId == prevId) {
                    child.update(0, newSelected = c.clanId == clan.clanId)
                }
            }
        }

        updateChannelList()
        userClanController.loadClanMembers(clan.clanId)
    }

    private fun onChannelSelected(channel: ClanChannelEntity) {
        chatController.openChannel(channel.channelId, channel.clanId, channel.type, channel.isPrivate)
        onOpenChat?.invoke(channel.channelId, channel.channelLabel, channel.clanId, channel.type)
    }

    inner class ServerRailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        init { setHasStableIds(true) }

        private val VIEW_TYPE_DM_HEADER = 0
        private val VIEW_TYPE_UNREAD_DM = 1
        private val VIEW_TYPE_SEPARATOR = 2
        private val VIEW_TYPE_CLAN = 3

        private val unreadDms = mutableListOf<DirectMessage>()
        private val clans = mutableListOf<ClanEntity>()
        private var selectedClanId = 0L
        private var pendingFriendCount = 0
        private var logoUrl = ""

        private val dmHeaderCount = 1
        private val hasSeparator: Boolean
            get() = clans.isNotEmpty()

        fun submitData(
            newUnreadDms: List<DirectMessage>,
            newClans: List<ClanEntity>,
            newSelectedId: Long,
            newPendingFriendCount: Int = 0,
            newLogoUrl: String = ""
        ) {
            val oldUnreadDms = ArrayList(unreadDms)
            val oldUnreadIds = oldUnreadDms.map { it.channelId }
            val oldClans = ArrayList(clans)
            val oldClanIds = oldClans.map { it.clanId }
            val oldSelectedId = selectedClanId
            val oldPendingFriendCount = pendingFriendCount
            val oldLogoUrl = logoUrl
            val oldSize = itemCount

            unreadDms.clear()
            unreadDms.addAll(newUnreadDms)
            clans.clear()
            clans.addAll(newClans)
            selectedClanId = newSelectedId
            pendingFriendCount = newPendingFriendCount
            logoUrl = newLogoUrl

            val newUnreadIds = newUnreadDms.map { it.channelId }
            val newClanIds = newClans.map { it.clanId }
            val newSize = itemCount

            val structureChanged = oldSize != newSize ||
                oldUnreadIds != newUnreadIds ||
                oldClanIds != newClanIds
            if (structureChanged) {
                notifyDataSetChanged()
                return
            }

            if (oldLogoUrl != newLogoUrl || oldPendingFriendCount != newPendingFriendCount) {
                notifyItemChanged(0)
            }

            for (i in newUnreadDms.indices) {
                val old = oldUnreadDms.getOrNull(i)
                val new = newUnreadDms[i]
                if (old == null || old.unreadCount != new.unreadCount ||
                    old.lastMessageContent != new.lastMessageContent ||
                    old.isOnline != new.isOnline) {
                    notifyItemChanged(dmHeaderCount + i)
                }
            }

            val sep = if (hasSeparator) 1 else 0
            val clanStart = dmHeaderCount + unreadDms.size + sep
            val oldClanMap = HashMap<Long, ClanEntity>(oldClans.size)
            for (c in oldClans) oldClanMap[c.clanId] = c

            for (i in newClans.indices) {
                val new = newClans[i]
                val old = oldClanMap[new.clanId]
                val selected = new.clanId == newSelectedId
                val wasSelected = new.clanId == oldSelectedId
                if (old == null ||
                    old.badgeCount != new.badgeCount ||
                    old.hasUnread != new.hasUnread ||
                    selected != wasSelected) {
                    notifyItemChanged(clanStart + i)
                }
            }
        }

        fun updatePendingFriendCount(count: Int) {
            if (pendingFriendCount == count) return
            pendingFriendCount = count
            notifyItemChanged(0)
        }

        override fun getItemCount(): Int {
            val sep = if (hasSeparator) 1 else 0
            return dmHeaderCount + unreadDms.size + sep + clans.size
        }

        override fun getItemId(position: Int): Long {
            if (position == 0) return Long.MIN_VALUE
            val afterHeader = position - dmHeaderCount
            if (afterHeader < unreadDms.size) return unreadDms[afterHeader].channelId
            if (hasSeparator && afterHeader == unreadDms.size) return Long.MIN_VALUE + 1
            val idx = clanIndex(position)
            return if (idx in clans.indices) clans[idx].clanId else RecyclerView.NO_ID
        }

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return VIEW_TYPE_DM_HEADER
            val afterHeader = position - dmHeaderCount
            if (afterHeader < unreadDms.size) return VIEW_TYPE_UNREAD_DM
            if (hasSeparator && afterHeader == unreadDms.size) return VIEW_TYPE_SEPARATOR
            return VIEW_TYPE_CLAN
        }

        private fun clanIndex(position: Int): Int {
            val sep = if (hasSeparator) 1 else 0
            return position - dmHeaderCount - unreadDms.size - sep
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                VIEW_TYPE_DM_HEADER -> DmLogoCell(parent.context, themeColors)
                VIEW_TYPE_UNREAD_DM -> UnreadDmCell(parent.context, themeColors)
                VIEW_TYPE_SEPARATOR -> SeparatorView(parent.context, themeColors)
                else -> ClanCell(parent.context, themeColors)
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val view = holder.itemView) {
                is DmLogoCell -> {
                    view.setLogoUrl(logoUrl)
                    view.setPendingFriendCount(pendingFriendCount)
                    view.setOnClickListener { onSwitchToMessages?.invoke() }
                }
                is UnreadDmCell -> {
                    val idx = position - dmHeaderCount
                    if (idx in unreadDms.indices) view.setData(unreadDms[idx])
                }
                is ClanCell -> {
                    val idx = clanIndex(position)
                    if (idx in clans.indices) {
                        val clan = clans[idx]
                        view.update(0, clan, clan.clanId == selectedClanId)
                    }
                }
            }
        }
    }

    private class SeparatorView(context: Context, private val theme: ThemeColors) : View(context) {
        private val paint = Paint().apply { color = theme.outlineVariant }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), LayoutHelper.dp(9))
        }
        override fun onDraw(canvas: Canvas) {
            val y = height / 2f
            val margin = LayoutHelper.dp(12f).toFloat()
            paint.color = theme.outlineVariant
            canvas.drawLine(margin, y, width - margin, y, paint)
        }
    }
}
