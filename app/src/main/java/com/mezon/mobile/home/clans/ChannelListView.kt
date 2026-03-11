package com.mezon.mobile.home.clans

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors

private const val ROW_SECTION = 0
private const val ROW_CHANNEL = 1
private const val ROW_THREAD = 2

class ChannelListView(
    context: Context,
    private val themeColors: ThemeColors
) : LinearLayout(context) {

    var onChannelClick: ((channel: ClanChannelEntity) -> Unit)? = null
    var activeChannelId: Long = 0L

    private val recyclerView: RecyclerListView
    private val adapter = Adapter()

    private val expandedCategories = mutableSetOf<Long>()
    private var allExpanded = true
    private var currentSections: List<ChannelSection> = emptyList()

    init {
        orientation = VERTICAL
        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = OVER_SCROLL_NEVER
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
            }
        })
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(sections: List<ChannelSection>) {
        currentSections = sections
        adapter.submitRows(buildRows(sections))
    }

    fun clear() {
        currentSections = emptyList()
        allExpanded = true
        expandedCategories.clear()
        adapter.submitRows(emptyList())
    }

    fun resetExpansion() {
        allExpanded = true
        expandedCategories.clear()
    }

    fun invalidateTheme() {
        adapter.notifyDataSetChanged()
    }

    fun updateVisibleRows(mask: Int) {
        val rowMap = HashMap<Long, ChannelRow>()
        for (row in adapter.currentRows()) {
            when (row) {
                is ChannelRow.Channel -> rowMap[row.channel.channelId] = row
                is ChannelRow.Thread -> rowMap[row.thread.channelId] = row
                else -> {}
            }
        }
        val count = recyclerView.childCount
        for (i in 0 until count) {
            when (val child = recyclerView.getChildAt(i)) {
                is ChannelItemCell -> {
                    val ch = child.channel ?: continue
                    val row = rowMap[ch.channelId] as? ChannelRow.Channel
                    child.update(mask, row?.channel)
                }
                is ChannelThreadCell -> {
                    val th = child.thread ?: continue
                    val row = rowMap[th.channelId] as? ChannelRow.Thread
                    child.update(mask, row?.thread)
                }
            }
        }
    }

    private fun buildRows(sections: List<ChannelSection>): List<ChannelRow> {
        val rows = mutableListOf<ChannelRow>()
        for (section in sections) {
            val expanded = allExpanded || section.categoryId in expandedCategories
            if (section.categoryName.isNotEmpty()) {
                rows.add(ChannelRow.Section(section.categoryId, section.categoryName, expanded))
            }
            if (expanded || section.categoryName.isEmpty()) {
                var lastParentId = 0L
                for (ch in section.channels) {
                    if (ch.isThread) {
                        val isFirst = ch.parentId != lastParentId
                        lastParentId = ch.parentId
                        if (ch.unreadCount > 0 || ch.channelId == activeChannelId) {
                            rows.add(ChannelRow.Thread(ch, isFirst, ch.channelId == activeChannelId))
                        }
                    } else {
                        lastParentId = 0L
                        rows.add(ChannelRow.Channel(ch, ch.channelId == activeChannelId))
                    }
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
        adapter.submitRows(buildRows(currentSections))
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = mutableListOf<ChannelRow>()

        fun currentRows(): List<ChannelRow> = rows

        fun getRowForView(view: android.view.View): ChannelRow? {
            val pos = recyclerView.getChildAdapterPosition(view)
            return if (pos in rows.indices) rows[pos] else null
        }

        fun submitRows(newRows: List<ChannelRow>) {
            val old = rows.toList()
            rows.clear()
            rows.addAll(newRows)
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newRows.size
                override fun areItemsTheSame(o: Int, n: Int): Boolean {
                    val a = old[o]; val b = newRows[n]
                    if (a is ChannelRow.Section && b is ChannelRow.Section) return a.categoryId == b.categoryId
                    if (a is ChannelRow.Channel && b is ChannelRow.Channel) return a.channel.channelId == b.channel.channelId
                    if (a is ChannelRow.Thread && b is ChannelRow.Thread) return a.thread.channelId == b.thread.channelId
                    return false
                }
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == newRows[n]
            }).dispatchUpdatesTo(this)
        }

        override fun getItemViewType(pos: Int) = when (rows[pos]) {
            is ChannelRow.Section -> ROW_SECTION
            is ChannelRow.Channel -> ROW_CHANNEL
            is ChannelRow.Thread -> ROW_THREAD
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                ROW_SECTION -> SectionVH(ChannelSectionCell(parent.context, themeColors))
                ROW_THREAD -> ThreadVH(ChannelThreadCell(parent.context, themeColors))
                else -> ChannelVH(ChannelItemCell(parent.context, themeColors))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
            when (val row = rows[pos]) {
                is ChannelRow.Section -> {
                    (holder as SectionVH).cell.bind(row.categoryName, row.isExpanded)
                }
                is ChannelRow.Channel -> {
                    (holder as ChannelVH).cell.bind(row.channel, row.isActive)
                }
                is ChannelRow.Thread -> {
                    (holder as ThreadVH).cell.bind(row.thread, row.isFirst, row.isActive)
                }
            }
        }

        inner class SectionVH(val cell: ChannelSectionCell) : RecyclerView.ViewHolder(cell)
        inner class ChannelVH(val cell: ChannelItemCell) : RecyclerView.ViewHolder(cell)
        inner class ThreadVH(val cell: ChannelThreadCell) : RecyclerView.ViewHolder(cell)
    }
}

sealed class ChannelRow {
    data class Section(val categoryId: Long, val categoryName: String, val isExpanded: Boolean) : ChannelRow()
    data class Channel(val channel: ClanChannelEntity, val isActive: Boolean) : ChannelRow()
    data class Thread(val thread: ClanChannelEntity, val isFirst: Boolean, val isActive: Boolean) : ChannelRow()
}
