package com.mezon.mobile.home.clans

import android.content.Context
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
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController

class ClansFragment : BaseFragment() {

    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController
    private lateinit var chatController: ChatController

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    private lateinit var serverRail: RecyclerListView
    private lateinit var clanHeaderText: TextView
    private lateinit var channelListView: ChannelListView
    private lateinit var serverAdapter: ServerRailAdapter

    override fun onInject(entryPoint: FragmentEntryPoint) {
        clansController = entryPoint.clansController()
        channelController = entryPoint.channelController()
        chatController = entryPoint.chatController()    
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
        }
        serverAdapter = ServerRailAdapter()
        serverRail.adapter = serverAdapter
        serverRail.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, position ->
            if (view is ClanCell) {
                val clan = view.currentClan ?: return@OnItemClickListener
                onClanSelected(clan)
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
        if ((mask and NotificationCenter.UPDATE_MASK_NEW_MESSAGE) != 0 || mask == 0) {
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
        val clans = clansController.clans.value
        val selectedId = clansController.selectedClanId.value
        serverAdapter.submitClans(clans, selectedId)

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

    inner class ServerRailAdapter : RecyclerView.Adapter<ServerRailAdapter.VH>() {
        private val clans = mutableListOf<ClanEntity>()
        private var selectedId = 0L

        fun submitClans(newClans: List<ClanEntity>, newSelectedId: Long) {
            val old = clans.toList()
            val oldSelectedId = selectedId
            clans.clear()
            clans.addAll(newClans)
            selectedId = newSelectedId
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newClans.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].clanId == newClans[n].clanId
                override fun areContentsTheSame(o: Int, n: Int): Boolean {
                    val a = old[o]; val b = newClans[n]
                    return a.clanName == b.clanName && a.logo == b.logo
                        && a.hasUnread == b.hasUnread && a.badgeCount == b.badgeCount
                        && (a.clanId == oldSelectedId) == (b.clanId == newSelectedId)
                }
            }).dispatchUpdatesTo(this)
        }

        override fun getItemCount() = clans.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ClanCell(parent.context, themeColors))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val clan = clans[position]
            holder.cell.update(0, clan, clan.clanId == selectedId)
        }

        inner class VH(val cell: ClanCell) : RecyclerView.ViewHolder(cell)
    }
}
