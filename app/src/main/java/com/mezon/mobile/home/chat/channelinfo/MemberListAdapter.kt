package com.mezon.mobile.home.chat.channelinfo

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemberListAdapter(
    private val theme: ThemeColors,
    private val isDm: Boolean,
    private val creatorId: Long
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = ArrayList<Any>()
    private var allMembers = ArrayList<ClanMember>()
    private var filterQuery = ""
    private var diffJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        setHasStableIds(true)
    }

    fun setData(members: List<ClanMember>) {
        allMembers = ArrayList(members)
        rebuildRows()
    }

    fun setFilter(query: String) {
        filterQuery = query.trim().lowercase()
        rebuildRows()
    }

    private fun rebuildRows() {
        val filtered = if (filterQuery.isEmpty()) {
            allMembers
        } else {
            allMembers.filter { m ->
                m.clanNick.lowercase().contains(filterQuery) ||
                    m.displayName.lowercase().contains(filterQuery) ||
                    m.username.lowercase().contains(filterQuery)
            }
        }

        val newRows = ArrayList<Any>()

        val count = filtered.size
        newRows.add(SectionHeader("Members — $count"))
        newRows.addAll(filtered.sortedBy { resolveSortName(it) })

        diffJob?.cancel()
        val oldRows = ArrayList(rows)
        if (oldRows.size > DIFF_THRESHOLD || newRows.size > DIFF_THRESHOLD) {
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(MemberDiffCallback(oldRows, newRows))
                }
                rows.clear()
                rows.addAll(newRows)
                result.dispatchUpdatesTo(this@MemberListAdapter)
            }
        } else {
            val result = DiffUtil.calculateDiff(MemberDiffCallback(oldRows, newRows))
            rows.clear()
            rows.addAll(newRows)
            result.dispatchUpdatesTo(this)
        }
    }

    private fun resolveSortName(m: ClanMember): String {
        return (m.clanNick.ifBlank { m.displayName.ifBlank { m.username } }).lowercase()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is SectionHeader -> VIEW_TYPE_HEADER
            else -> VIEW_TYPE_MEMBER
        }
    }

    override fun getItemId(position: Int): Long {
        return when (val item = rows[position]) {
            is ClanMember -> item.userId
            is SectionHeader -> item.title.hashCode().toLong()
            else -> position.toLong()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val tv = TextView(parent.context).apply {
                    setTextColor(theme.onSurfaceVariant)
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val pad = LayoutHelper.dp(16)
                    val padTop = LayoutHelper.dp(16)
                    val padBottom = LayoutHelper.dp(6)
                    setPadding(pad, padTop, pad, padBottom)
                }
                object : RecyclerView.ViewHolder(tv) {}
            }
            else -> {
                val cell = MemberCell(parent.context, theme).apply {
                    setIsDm(isDm)
                    setCreatorId(creatorId)
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        LayoutHelper.dp(56f)
                    )
                }
                object : RecyclerView.ViewHolder(cell) {}
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = rows[position]) {
            is SectionHeader -> (holder.itemView as TextView).text = item.title
            is ClanMember -> (holder.itemView as MemberCell).update(0, item)
        }
    }

    fun getMember(position: Int): ClanMember? = rows.getOrNull(position) as? ClanMember

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        scope.cancel()
    }

    data class SectionHeader(val title: String)

    private class MemberDiffCallback(
        private val old: List<Any>,
        private val new: List<Any>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val o = old[oldPos]
            val n = new[newPos]
            if (o is ClanMember && n is ClanMember) return o.userId == n.userId
            if (o is SectionHeader && n is SectionHeader) return o.title == n.title
            return false
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val o = old[oldPos]
            val n = new[newPos]
            if (o is ClanMember && n is ClanMember) return o == n
            if (o is SectionHeader && n is SectionHeader) return o.title == n.title
            return false
        }
    }

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_MEMBER = 1
        private const val DIFF_THRESHOLD = 50
    }
}
