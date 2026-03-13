package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
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
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.home.profile.AccountController

class ClansFragment : BaseFragment() {

    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController
    private lateinit var chatController: ChatController
    private lateinit var dialogsController: DialogsController
    private lateinit var accountController: AccountController

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null
    var onSwitchToMessages: (() -> Unit)? = null

    private lateinit var serverRail: RecyclerListView
    private lateinit var clanHeaderText: TextView
    private lateinit var channelListView: ChannelListView
    private lateinit var serverAdapter: ServerRailAdapter

    override fun onInject(entryPoint: FragmentEntryPoint) {
        clansController = entryPoint.clansController()
        channelController = entryPoint.channelController()
        chatController = entryPoint.chatController()
        dialogsController = entryPoint.dialogsController()
        accountController = entryPoint.accountController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.clansDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            updateServerRail()
        }
        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val clanId = args.firstOrNull() as? Long ?: return@observe
            if (clanId == clansController.selectedClanId.value) updateChannelList()
        }
        observe(NotificationCenter.clanInfoUpdated) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            updateServerRail()
        }
        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            updateServerRail()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            updateVisibleRows(mask)
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.background)
            serverRail.setBackgroundColor(themeColors.surface)
            serverAdapter.notifyDataSetChanged()
            channelListView.invalidateTheme()
        }

        clansController.loadClans()
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(themeColors.background)
        }

        serverRail = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(themeColors.surface)
            isVerticalScrollBarEnabled = false
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
            setBackgroundColor(themeColors.background)
        }

        clanHeaderText = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val hPad = LayoutHelper.dp(16)
            val vPad = LayoutHelper.dp(14)
            setPadding(hPad, vPad, hPad, vPad)
        }

        channelListView = ChannelListView(context, themeColors).apply {
            onChannelClick = { channel -> onChannelSelected(channel) }
        }

        channelPanel.addView(clanHeaderText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        channelPanel.addView(channelListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        root.addView(serverRail, LayoutHelper.createLinear(56, LayoutHelper.MATCH_PARENT))
        root.addView(channelPanel, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))

        if (clansController.clansLoaded) {
            updateServerRail()
            val selectedId = clansController.selectedClanId.value
            if (selectedId != 0L) updateChannelList()
        }

        return root
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()

        if (clansController.clansLoaded) {
            updateServerRail()
            val currentClanId = clansController.selectedClanId.value
            if (currentClanId != 0L) updateChannelList()
        }
    }

    private fun updateVisibleRows(mask: Int) {
        if (isPaused) return
        if ((mask and NotificationCenter.UPDATE_MASK_NEW_MESSAGE) != 0 ||
            (mask and NotificationCenter.UPDATE_MASK_BADGE) != 0 || mask == 0) {
            updateServerRail()
            updateChannelList()
            return
        }
        val count = serverRail.childCount
        val clans = clansController.clans.value
        val selectedId = clansController.selectedClanId.value
        val clanMap = HashMap<Long, ClanEntity>(clans.size)
        for (c in clans) clanMap[c.clanId] = c

        for (i in 0 until count) {
            val child = serverRail.getChildAt(i)
            if (child is ClanCell) {
                val entity = child.currentClan ?: continue
                val updated = clanMap[entity.clanId]
                child.update(mask, updated, entity.clanId == selectedId)
            }
        }

        channelListView.updateVisibleRows(mask)
    }

    private fun updateServerRail() {
        val unreadDms = dialogsController.getDialogs()
            .filter { it.unreadCount > 0 && !it.isMute }
        val clans = clansController.clans.value
        val selectedId = clansController.selectedClanId.value
        val logoUrl = accountController.accountInfo.value.logo
        serverAdapter.submitData(unreadDms, clans, selectedId, newLogoUrl = logoUrl)

        val selected = clans.find { it.clanId == selectedId }
        if (selected != null) clanHeaderText.text = selected.clanName
    }

    private fun updateChannelList() {
        val clanId = clansController.selectedClanId.value
        val sections = channelController.getChannelSections(clanId)
        channelListView.bind(sections)
    }

    private fun onClanSelected(clan: ClanEntity) {
        if (clan.clanId == clansController.selectedClanId.value) return
        clansController.selectClan(clan.clanId)
        clanHeaderText.text = clan.clanName
        channelListView.resetExpansion()
        updateServerRail()
        channelListView.clear()
    }

    private fun onChannelSelected(channel: ClanChannelEntity) {
        chatController.openChannel(channel.channelId, channel.clanId, channel.type)
        onOpenChat?.invoke(channel.channelId, channel.channelLabel, channel.clanId, channel.type)
    }

    inner class ServerRailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
            unreadDms.clear()
            unreadDms.addAll(newUnreadDms)
            clans.clear()
            clans.addAll(newClans)
            selectedClanId = newSelectedId
            pendingFriendCount = newPendingFriendCount
            logoUrl = newLogoUrl
            notifyDataSetChanged()
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
