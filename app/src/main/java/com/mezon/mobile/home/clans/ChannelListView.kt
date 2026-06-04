package com.mezon.mobile.home.clans

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.channelapp.ChannelAppUiModel
import com.mezon.mobile.home.clans.channelapp.ChannelAppsStripView
import com.mezon.mobile.home.voice.VoiceCollapsedMembersCell
import com.mezon.mobile.home.voice.VoiceUserAvatarCell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROW_SECTION = 0
private const val ROW_CHANNEL = 1
private const val ROW_THREAD = 2
private const val ROW_VOICE_MEMBER = 3
private const val ROW_VOICE_COLLAPSED = 4
private const val ROW_CHANNEL_APPS = 5
private const val ROW_ONBOARDING_BANNER = 6

private const val DIFF_BG_THRESHOLD = 50

class ChannelListView(
    context: Context,
    private val themeColors: ThemeColors,
    private val expandStore: ChannelCategoryExpandStore
) : LinearLayout(context) {

    var onChannelClick: ((channel: ClanChannelEntity) -> Unit)? = null
    var onChannelLongClick: ((channel: ClanChannelEntity, anchorView: android.view.View) -> Unit)? = null
    var onSectionLongClick: ((categoryId: Long, categoryName: String, anchorView: android.view.View) -> Unit)? = null
    var onBannerClick: (() -> Unit)? = null
    var activeChannelId: Long = 0L

    private var onboardingEnabled = false
    private var onboardingStep = 0
    private var onboardingDoneMissions = 0
    private var onboardingTotalMissions = 0

    private val recyclerView: RecyclerListView
    private val adapter = Adapter()

    private val expandedCategories = mutableSetOf<Long>()
    private var allExpanded = true
    private var boundClanId: Long = 0L
    private var currentSections: List<ChannelSection> = emptyList()
    private var currentChannelApps: List<ChannelAppUiModel> = emptyList()
    private var onChannelAppClick: ((ChannelAppUiModel) -> Unit)? = null
    private var onChannelAppViewAllClick: (() -> Unit)? = null

    private var voiceMembersByChannel = HashMap<Long, List<VoiceMemberDisplay>>()

    fun setVoiceMembers(members: Map<Long, List<VoiceMemberDisplay>>) {
        voiceMembersByChannel.clear()
        voiceMembersByChannel.putAll(members)
        adapter.submitRows(buildRows(currentSections))
    }

    init {
        orientation = VERTICAL
        recyclerView = object : RecyclerListView(context) {
            override fun canHighlightChildAt(child: android.view.View, x: Float, y: Float): Boolean {
                return child !is ChannelAppsStripView
            }
        }.apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            itemAnimator = null
            setSelectorType(RecyclerListView.SELECTOR_ROUNDRECT)
            setSelectorRadius(LayoutHelper.dp(6))
        }
        recyclerView.adapter = adapter
        recyclerView.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
            when (view) {
                is ChannelSectionCell -> {
                    val row = adapter.getRowForView(view)
                    if (row is ChannelRow.Section) onSectionToggle(row.categoryId)
                }
                is ChannelItemCell -> view.channel?.let { onChannelClick?.invoke(it) }
                is ChannelThreadCell -> view.thread?.let { onChannelClick?.invoke(it) }
                is OnboardingBannerCell -> onBannerClick?.invoke()
            }
        })
        recyclerView.setOnItemLongClickListener(RecyclerListView.OnItemLongClickListener { view, _ ->
            when (view) {
                is ChannelSectionCell -> {
                    val row = adapter.getRowForView(view) as? ChannelRow.Section ?: return@OnItemLongClickListener false
                    if (row.isFavorite || row.categoryId == FAVORITE_CATEGORY_ID) return@OnItemLongClickListener false
                    val cb = onSectionLongClick ?: return@OnItemLongClickListener false
                    cb(row.categoryId, row.categoryName, view)
                    true
                }
                is ChannelItemCell -> {
                    val cb = onChannelLongClick ?: return@OnItemLongClickListener false
                    val ch = view.channel ?: return@OnItemLongClickListener false
                    cb(ch, view)
                    true
                }
                is ChannelThreadCell -> {
                    val cb = onChannelLongClick ?: return@OnItemLongClickListener false
                    val th = view.thread ?: return@OnItemLongClickListener false
                    cb(th, view)
                    true
                }
                else -> false
            }
        })
        addView(recyclerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setChannelApps(
        apps: List<ChannelAppUiModel>,
        onAppClick: (ChannelAppUiModel) -> Unit,
        onViewAllClick: () -> Unit
    ) {
        onChannelAppClick = onAppClick
        onChannelAppViewAllClick = onViewAllClick
        if (currentChannelApps == apps) return
        currentChannelApps = apps.toList()
        val newRows = buildRows(currentSections)
        if (adapter.rowsEqual(newRows)) return
        adapter.submitRows(newRows)
    }

    fun bind(clanId: Long, sections: List<ChannelSection>) {
        if (clanId != boundClanId) {
            boundClanId = clanId
            onboardingEnabled = false
            onboardingStep = 0
            onboardingDoneMissions = 0
            onboardingTotalMissions = 0
            applyExpandState(expandStore.load(clanId))
        }
        val prevCategoryIds = currentSections.mapNotNull { if (it.categoryId != FAVORITE_CATEGORY_ID) it.categoryId else null }.toSet()
        val newCategoryIds = sections.mapNotNull { if (it.categoryId != FAVORITE_CATEGORY_ID) it.categoryId else null }.toSet()
        val isClanSwitch = prevCategoryIds.isNotEmpty() && prevCategoryIds != newCategoryIds
        currentSections = sections
        val newRows = buildRows(sections)
        if (isClanSwitch) {
            adapter.swapRows(newRows)
        } else {
            if (adapter.rowsEqual(newRows)) return
            adapter.submitRows(newRows)
        }
    }

    fun clear() {
        currentSections = emptyList()
        currentChannelApps = emptyList()
        boundClanId = 0L
        allExpanded = true
        expandedCategories.clear()
        onboardingEnabled = false
        onboardingStep = 0
        onboardingDoneMissions = 0
        onboardingTotalMissions = 0
        adapter.swapRows(emptyList())
    }

    fun resetExpansion() {
        allExpanded = true
        expandedCategories.clear()
        persistExpansion()
    }

    fun setCategoriesGloballyExpanded(expandAll: Boolean) {
        if (expandAll) {
            resetExpansion()
        } else {
            allExpanded = false
            expandedCategories.clear()
            persistExpansion()
        }
        if (currentSections.isNotEmpty()) {
            adapter.submitRows(buildRows(currentSections))
        }
    }

    private fun applyExpandState(state: CategoryExpandState) {
        allExpanded = state.allExpanded
        expandedCategories.clear()
        if (!allExpanded) {
            expandedCategories.addAll(state.expandedCategoryIds)
        }
    }

    private fun persistExpansion() {
        if (boundClanId != 0L) {
            expandStore.save(boundClanId, allExpanded, expandedCategories)
        }
    }

    fun invalidateTheme() {
        adapter.notifyDataSetChanged()
    }

    fun updateVisibleRows(mask: Int, freshChannels: Map<Long, ClanChannelEntity>? = null) {
        if (freshChannels != null) {
            adapter.updateRowData(freshChannels)
        }
        val visibilityMask = NotificationCenter.UPDATE_MASK_NEW_MESSAGE or
            NotificationCenter.UPDATE_MASK_READ_DIALOG_MESSAGE or
            NotificationCenter.UPDATE_MASK_BADGE
        if (freshChannels != null &&
            !allExpanded &&
            currentSections.isNotEmpty() &&
            (mask and visibilityMask) != 0
        ) {
            var anyCollapsedChannelChanged = false
            for (section in currentSections) {
                if (section.categoryName.isEmpty()) continue
                if (section.categoryId == FAVORITE_CATEGORY_ID) continue
                if (section.categoryId in expandedCategories) continue
                for (oldCh in section.channels) {
                    val fresh = freshChannels[oldCh.channelId] ?: continue
                    if (fresh.unreadCount != oldCh.unreadCount || fresh.hasUnread != oldCh.hasUnread) {
                        anyCollapsedChannelChanged = true
                        break
                    }
                }
                if (anyCollapsedChannelChanged) break
            }
            if (anyCollapsedChannelChanged) {
                currentSections = currentSections.map { section ->
                    if (section.categoryId == FAVORITE_CATEGORY_ID) {
                        section
                    } else {
                        section.copy(channels = section.channels.map { freshChannels[it.channelId] ?: it })
                    }
                }
                val newRows = buildRows(currentSections)
                if (!adapter.rowsEqual(newRows)) {
                    adapter.submitRows(newRows)
                }
            }
        }
        val count = recyclerView.childCount
        for (i in 0 until count) {
            when (val child = recyclerView.getChildAt(i)) {
                is ChannelItemCell -> {
                    val ch = child.channel ?: continue
                    val updated = freshChannels?.get(ch.channelId)
                    child.update(mask, updated)
                }
                is ChannelThreadCell -> {
                    val th = child.thread ?: continue
                    val updated = freshChannels?.get(th.channelId)
                    child.update(mask, updated)
                }
            }
        }
    }

    private fun isVoiceType(type: Int) =
        type == CHANNEL_TYPE_VOICE || type == CHANNEL_TYPE_STREAMING || type == CHANNEL_TYPE_APP

    fun setOnboardingState(enabled: Boolean, step: Int, done: Int, total: Int) {
        if (onboardingEnabled == enabled && onboardingStep == step &&
            onboardingDoneMissions == done && onboardingTotalMissions == total) return
        onboardingEnabled = enabled
        onboardingStep = step
        onboardingDoneMissions = done
        onboardingTotalMissions = total
        adapter.submitRows(buildRows(currentSections))
    }

    private fun buildRows(sections: List<ChannelSection>): List<ChannelRow> {
        val rows = mutableListOf<ChannelRow>()
        if (currentChannelApps.isNotEmpty()) {
            rows.add(ChannelRow.ChannelApps(currentChannelApps))
        }
        if (onboardingEnabled && onboardingStep != 3 && onboardingTotalMissions > 0) {
            rows.add(ChannelRow.OnboardingBanner(onboardingDoneMissions, onboardingTotalMissions))
        }
        for (section in sections) {
            val isFav = section.categoryId == FAVORITE_CATEGORY_ID
            val expanded = allExpanded || section.categoryId in expandedCategories
            if (section.categoryName.isNotEmpty()) {
                rows.add(ChannelRow.Section(section.categoryId, section.categoryName, expanded, isFav))
            }
            if (expanded || section.categoryName.isEmpty()) {
                if (isFav) {
                    for (ch in section.channels) {
                        rows.add(ChannelRow.Channel(ch, ch.channelId == activeChannelId, isFavorite = true))
                    }
                } else {
                val visibleThreads = mutableListOf<Triple<ClanChannelEntity, Boolean, Boolean>>()
                var lastParentId = 0L
                for (ch in section.channels) {
                    if (ch.isThread) {
                        val isFirst = ch.parentId != lastParentId
                        lastParentId = ch.parentId
                        visibleThreads.add(Triple(ch, isFirst, ch.channelId == activeChannelId))
                    } else {
                        if (visibleThreads.isNotEmpty()) {
                            for ((idx, t) in visibleThreads.withIndex()) {
                                val isLast = idx == visibleThreads.size - 1 ||
                                    visibleThreads.getOrNull(idx + 1)?.first?.parentId != t.first.parentId
                                rows.add(ChannelRow.Thread(t.first, t.second, isLast, t.third))
                            }
                            visibleThreads.clear()
                        }
                        lastParentId = 0L
                        val voiceActive = isVoiceType(ch.type) &&
                            voiceMembersByChannel[ch.channelId]?.isNotEmpty() == true
                        rows.add(ChannelRow.Channel(ch, ch.channelId == activeChannelId, voiceActive = voiceActive))
                        if (isVoiceType(ch.type)) {
                            voiceMembersByChannel[ch.channelId]?.forEach { member ->
                                rows.add(ChannelRow.VoiceMember(ch.channelId, member))
                            }
                        }
                    }
                }
                if (visibleThreads.isNotEmpty()) {
                    for ((idx, t) in visibleThreads.withIndex()) {
                        val isLast = idx == visibleThreads.size - 1 ||
                            visibleThreads.getOrNull(idx + 1)?.first?.parentId != t.first.parentId
                        rows.add(ChannelRow.Thread(t.first, t.second, isLast, t.third))
                    }
                    visibleThreads.clear()
                }
                }
            } else {
                val threadsByParent = HashMap<Long, MutableList<ClanChannelEntity>>()
                for (ch in section.channels) {
                    if (ch.isThread) {
                        threadsByParent.getOrPut(ch.parentId) { mutableListOf() }.add(ch)
                    }
                }
                val visibleThreads = mutableListOf<Triple<ClanChannelEntity, Boolean, Boolean>>()
                var lastParentId = 0L
                for (ch in section.channels) {
                    if (ch.isThread) {
                        val isActive = ch.channelId == activeChannelId
                        if (isActive || ch.hasUnread) {
                            val isFirst = ch.parentId != lastParentId
                            lastParentId = ch.parentId
                            visibleThreads.add(Triple(ch, isFirst, isActive))
                        }
                    } else {
                        if (visibleThreads.isNotEmpty()) {
                            for ((idx, t) in visibleThreads.withIndex()) {
                                val isLast = idx == visibleThreads.size - 1 ||
                                    visibleThreads.getOrNull(idx + 1)?.first?.parentId != t.first.parentId
                                rows.add(ChannelRow.Thread(t.first, t.second, isLast, t.third))
                            }
                            visibleThreads.clear()
                        }
                        lastParentId = 0L
                        val isActive = ch.channelId == activeChannelId
                        val voiceActive = isVoiceType(ch.type) &&
                            voiceMembersByChannel[ch.channelId]?.isNotEmpty() == true
                        val children = threadsByParent[ch.channelId]
                        val hasActiveChild = children?.any { it.channelId == activeChannelId } == true
                        val hasUnreadChild = children?.any { it.hasUnread } == true
                        val shouldShow = isActive || ch.hasUnread || voiceActive || hasActiveChild || hasUnreadChild
                        if (shouldShow) {
                            rows.add(ChannelRow.Channel(ch, isActive, voiceActive = voiceActive))
                            if (isVoiceType(ch.type) && voiceActive) {
                                voiceMembersByChannel[ch.channelId]?.let { members ->
                                    rows.add(ChannelRow.VoiceCollapsedMembers(ch.channelId, members))
                                }
                            }
                        }
                    }
                }
                if (visibleThreads.isNotEmpty()) {
                    for ((idx, t) in visibleThreads.withIndex()) {
                        val isLast = idx == visibleThreads.size - 1 ||
                            visibleThreads.getOrNull(idx + 1)?.first?.parentId != t.first.parentId
                        rows.add(ChannelRow.Thread(t.first, t.second, isLast, t.third))
                    }
                    visibleThreads.clear()
                }
            }
        }
        return rows
    }

    private fun onSectionToggle(categoryId: Long) {
        if (allExpanded) {
            currentSections.mapTo(expandedCategories) { it.categoryId }
            allExpanded = false
        }
        if (categoryId in expandedCategories) expandedCategories.remove(categoryId)
        else expandedCategories.add(categoryId)
        persistExpansion()
        adapter.submitRows(buildRows(currentSections))
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }

        private val rows = mutableListOf<ChannelRow>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var diffJob: Job? = null

        fun currentRows(): List<ChannelRow> = rows

        fun getRowForView(view: android.view.View): ChannelRow? {
            val pos = recyclerView.getChildAdapterPosition(view)
            return if (pos in rows.indices) rows[pos] else null
        }

        fun updateRowData(lookup: Map<Long, ClanChannelEntity>) {
            for (i in rows.indices) {
                when (val row = rows[i]) {
                    is ChannelRow.ChannelApps -> {}
                    is ChannelRow.Channel -> {
                        val fresh = lookup[row.channel.channelId]
                        if (fresh != null && fresh != row.channel) {
                            rows[i] = row.copy(channel = fresh)
                            notifyItemChanged(i)
                        }
                    }
                    is ChannelRow.Thread -> {
                        val fresh = lookup[row.thread.channelId]
                        if (fresh != null && fresh != row.thread) {
                            rows[i] = row.copy(thread = fresh)
                            notifyItemChanged(i)
                        }
                    }
                    is ChannelRow.VoiceMember -> {}
                    is ChannelRow.VoiceCollapsedMembers -> {}
                    is ChannelRow.Section -> {}
                    is ChannelRow.OnboardingBanner -> {}
                }
            }
        }

        fun rowsEqual(newRows: List<ChannelRow>): Boolean = rows == newRows

        fun swapRows(newRows: List<ChannelRow>) {
            diffJob?.cancel()
            val oldSize = rows.size
            rows.clear()
            rows.addAll(newRows)
            if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
            if (newRows.isNotEmpty()) notifyItemRangeInserted(0, newRows.size)
        }

        fun submitRows(newRows: List<ChannelRow>) {
            diffJob?.cancel()
            val old = rows.toList()
            if (old.size < DIFF_BG_THRESHOLD && newRows.size < DIFF_BG_THRESHOLD) {
                applyDiff(newRows, DiffUtil.calculateDiff(RowDiffCallback(old, newRows)))
            } else {
                diffJob = scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        DiffUtil.calculateDiff(RowDiffCallback(old, newRows))
                    }
                    applyDiff(newRows, result)
                }
            }
        }

        private fun applyDiff(newRows: List<ChannelRow>, result: DiffUtil.DiffResult) {
            rows.clear()
            rows.addAll(newRows)
            result.dispatchUpdatesTo(this)
        }

        fun destroy() {
            diffJob?.cancel()
            scope.cancel()
        }

        override fun getItemId(pos: Int): Long = when (val row = rows[pos]) {
            is ChannelRow.ChannelApps -> Long.MIN_VALUE + 1L
            is ChannelRow.Section -> -row.categoryId
            is ChannelRow.Channel -> if (row.isFavorite) row.channel.channelId.inv() else row.channel.channelId
            is ChannelRow.Thread -> row.thread.channelId
            is ChannelRow.VoiceMember -> Long.MAX_VALUE - row.member.userId xor row.channelId
            is ChannelRow.VoiceCollapsedMembers -> Long.MIN_VALUE xor row.channelId
            is ChannelRow.OnboardingBanner -> Long.MIN_VALUE + 2L
        }

        override fun getItemViewType(pos: Int) = when (rows[pos]) {
            is ChannelRow.ChannelApps -> ROW_CHANNEL_APPS
            is ChannelRow.Section -> ROW_SECTION
            is ChannelRow.Channel -> ROW_CHANNEL
            is ChannelRow.Thread -> ROW_THREAD
            is ChannelRow.VoiceMember -> ROW_VOICE_MEMBER
            is ChannelRow.VoiceCollapsedMembers -> ROW_VOICE_COLLAPSED
            is ChannelRow.OnboardingBanner -> ROW_ONBOARDING_BANNER
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                ROW_CHANNEL_APPS -> ChannelAppsVH(
                    ChannelAppsStripView(parent.context, themeColors).apply {
                        layoutParams = RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT,
                            RecyclerView.LayoutParams.WRAP_CONTENT
                        ).apply {
                            leftMargin = LayoutHelper.dp(8)
                            rightMargin = LayoutHelper.dp(8)
                            topMargin = LayoutHelper.dp(4)
                        }
                    }
                )
                ROW_SECTION -> SectionVH(ChannelSectionCell(parent.context, themeColors))
                ROW_THREAD -> ThreadVH(ChannelThreadCell(parent.context, themeColors))
                ROW_VOICE_MEMBER -> VoiceMemberVH(VoiceUserAvatarCell(parent.context, themeColors))
                ROW_VOICE_COLLAPSED -> VoiceCollapsedVH(VoiceCollapsedMembersCell(parent.context, themeColors))
                ROW_ONBOARDING_BANNER -> OnboardingBannerVH(OnboardingBannerCell(parent.context, themeColors).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                })

                else -> ChannelVH(ChannelItemCell(parent.context, themeColors))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
            when (val row = rows[pos]) {
                is ChannelRow.ChannelApps -> {
                    (holder as ChannelAppsVH).cell.apply {
                        onAppClick = this@ChannelListView.onChannelAppClick
                        onViewAllClick = this@ChannelListView.onChannelAppViewAllClick
                        setApps(row.apps)
                    }
                }
                is ChannelRow.Section -> {
                    (holder as SectionVH).cell.bind(row.categoryName, row.isExpanded, row.isFavorite)
                }
                is ChannelRow.Channel -> {
                    (holder as ChannelVH).cell.bind(row.channel, row.isActive, row.voiceActive)
                }
                is ChannelRow.Thread -> {
                    (holder as ThreadVH).cell.bind(row.thread, row.isFirst, row.isLast, row.isActive)
                }
                is ChannelRow.VoiceMember -> {
                    (holder as VoiceMemberVH).cell.setUser(
                        row.member.userId,
                        row.member.displayName,
                        row.member.username,
                        row.member.avatarUrl
                    )
                }
                is ChannelRow.VoiceCollapsedMembers -> {
                    (holder as VoiceCollapsedVH).cell.setMembers(row.members)
                }
                is ChannelRow.OnboardingBanner -> {
                    (holder as OnboardingBannerVH).cell.bind(row.done, row.total)
                }

            }
        }

        inner class ChannelAppsVH(val cell: ChannelAppsStripView) : RecyclerView.ViewHolder(cell)
        inner class SectionVH(val cell: ChannelSectionCell) : RecyclerView.ViewHolder(cell)
        inner class ChannelVH(val cell: ChannelItemCell) : RecyclerView.ViewHolder(cell)
        inner class ThreadVH(val cell: ChannelThreadCell) : RecyclerView.ViewHolder(cell)
        inner class VoiceMemberVH(val cell: VoiceUserAvatarCell) : RecyclerView.ViewHolder(cell)
        inner class VoiceCollapsedVH(val cell: VoiceCollapsedMembersCell) : RecyclerView.ViewHolder(cell)
        inner class OnboardingBannerVH(val cell: OnboardingBannerCell) : RecyclerView.ViewHolder(cell)

    }

    fun destroy() {
        adapter.destroy()
    }
}

private class RowDiffCallback(
    private val old: List<ChannelRow>,
    private val new: List<ChannelRow>
) : DiffUtil.Callback() {
    override fun getOldListSize() = old.size
    override fun getNewListSize() = new.size
    override fun areItemsTheSame(o: Int, n: Int): Boolean {
        val a = old[o]; val b = new[n]
        if (a is ChannelRow.ChannelApps && b is ChannelRow.ChannelApps) return true
        if (a is ChannelRow.Section && b is ChannelRow.Section) return a.categoryId == b.categoryId
        if (a is ChannelRow.Channel && b is ChannelRow.Channel) return a.channel.channelId == b.channel.channelId && a.isFavorite == b.isFavorite
        if (a is ChannelRow.Thread && b is ChannelRow.Thread) return a.thread.channelId == b.thread.channelId
        if (a is ChannelRow.VoiceMember && b is ChannelRow.VoiceMember) return a.channelId == b.channelId && a.member.userId == b.member.userId
        if (a is ChannelRow.VoiceCollapsedMembers && b is ChannelRow.VoiceCollapsedMembers) return a.channelId == b.channelId
        if (a is ChannelRow.OnboardingBanner && b is ChannelRow.OnboardingBanner) return true
        return false
    }
    override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
}

data class VoiceMemberDisplay(
    val userId: Long,
    val displayName: String,
    val username: String = "",
    val avatarUrl: String?
)

sealed class ChannelRow {
    data class ChannelApps(val apps: List<ChannelAppUiModel>) : ChannelRow()
    data class Section(
        val categoryId: Long,
        val categoryName: String,
        val isExpanded: Boolean,
        val isFavorite: Boolean = false
    ) : ChannelRow()
    data class Channel(
        val channel: ClanChannelEntity,
        val isActive: Boolean,
        val isFavorite: Boolean = false,
        val voiceActive: Boolean = false
    ) : ChannelRow()
    data class Thread(val thread: ClanChannelEntity, val isFirst: Boolean, val isLast: Boolean, val isActive: Boolean) : ChannelRow()
    data class VoiceMember(val channelId: Long, val member: VoiceMemberDisplay) : ChannelRow()
    data class VoiceCollapsedMembers(val channelId: Long, val members: List<VoiceMemberDisplay>) : ChannelRow()
    data class OnboardingBanner(val done: Int, val total: Int) : ChannelRow()
}

class OnboardingBannerCell(context: Context, private val themeColors: ThemeColors) : FrameLayout(context) {
    private val titleText: TextView
    private val progressText: TextView
    private val progressBar: View
    private val chevronText: TextView

    init {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(12f)
                setColor(themeColors.tertiary)
                setStroke(LayoutHelper.dp(1), themeColors.borderDim)
            }
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(10))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleText = TextView(context).apply {
            text = context.getString(R.string.onboarding_get_started)
            setTextColor(themeColors.onSurface)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(titleText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        progressText = TextView(context).apply {
            setTextColor(themeColors.textDisabled)
            textSize = 12f
        }
        header.addView(progressText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
            rightMargin = LayoutHelper.dp(4)
        })

        chevronText = TextView(context).apply {
            text = "›"
            setTextColor(themeColors.textDisabled)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(chevronText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        container.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        progressBar = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(4f)
                setColor(themeColors.borderDim)
            }
        }
        container.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 4).apply {
            topMargin = LayoutHelper.dp(8)
        })

        addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            leftMargin = LayoutHelper.dp(8)
            rightMargin = LayoutHelper.dp(8)
            topMargin = LayoutHelper.dp(2)
            bottomMargin = LayoutHelper.dp(2)
        })
    }

    fun bind(done: Int, total: Int) {
        val ofString = context.getString(R.string.onboarding_of) ?: "of"
        progressText.text = "$done $ofString $total"
        val percent = if (total > 0) done.toFloat() / total else 0f
        progressBar.background = object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas: Canvas) {
                val r = bounds.height() / 2f
                paint.color = themeColors.borderDim
                canvas.drawRoundRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), r, r, paint)
                if (percent > 0f) {
                    paint.color = themeColors.onlineGreen
                    canvas.drawRoundRect(0f, 0f, bounds.width().toFloat() * percent, bounds.height().toFloat(), r, r, paint)
                }
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
        progressBar.invalidate()
    }
}


