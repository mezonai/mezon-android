package com.mezon.mobile.home.clans

import android.os.Bundle
import android.view.LayoutInflater
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
import com.mezon.mobile.home.ChatController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ClansFragment : BaseFragment() {

    @Inject lateinit var clansController: ClansController
    @Inject lateinit var channelController: ChannelController
    @Inject lateinit var chatController: ChatController

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null

    private lateinit var serverRail: RecyclerView
    private lateinit var clanHeaderText: TextView
    private lateinit var channelListView: ChannelListView
    private lateinit var serverAdapter: ServerRailAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(themeColors.background)
        }

        serverRail = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            setBackgroundColor(themeColors.surface)
        }
        serverAdapter = ServerRailAdapter()
        serverRail.adapter = serverAdapter

        val channelPanel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        clanHeaderText = TextView(requireContext()).apply {
            setTextColor(themeColors.onSurface)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val hPad = LayoutHelper.dp(16)
            val vPad = LayoutHelper.dp(14)
            setPadding(hPad, vPad, hPad, vPad)
        }

        channelListView = ChannelListView(requireContext(), themeColors).apply {
            onChannelClick = { channel -> onChannelSelected(channel) }
        }

        channelPanel.addView(clanHeaderText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        channelPanel.addView(channelListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        root.addView(serverRail, LayoutHelper.createLinear(56, LayoutHelper.MATCH_PARENT))
        root.addView(channelPanel, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observe(NotificationCenter.clansDidLoad) { _, _ -> updateServerRail() }
        observe(NotificationCenter.channelsDidLoad) { _, args ->
            val clanId = args.firstOrNull() as? Long ?: return@observe
            if (clanId == clansController.selectedClanId.value) updateChannelList()
        }
        observe(NotificationCenter.clanInfoUpdated) { _, _ -> updateServerRail() }
        observe(NotificationCenter.themeChanged) { _, _ ->
            view.setBackgroundColor(themeColors.background)
            serverRail.setBackgroundColor(themeColors.surface)
            serverAdapter.notifyDataSetChanged()
            channelListView.invalidateTheme()
        }

        if (clansController.clansLoaded) {
            updateServerRail()
            val currentClanId = clansController.selectedClanId.value
            if (currentClanId != 0L) updateChannelList()
        }
        clansController.loadClans()
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
            holder.cell.bind(clan, clan.clanId == selectedId)
            holder.cell.setOnClickListener { onClanSelected(clan) }
        }

        inner class VH(val cell: ClanCell) : RecyclerView.ViewHolder(cell)
    }
}
