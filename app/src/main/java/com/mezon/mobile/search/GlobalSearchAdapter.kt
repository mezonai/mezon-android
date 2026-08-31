package com.mezon.mobile.search

import android.util.TypedValue
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mezon.api.SearchMessageDocument
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.CHANNEL_TYPE_STREAMING
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.ui.cells.ChannelSearchCell
import com.mezon.mobile.ui.cells.HeaderCell
import com.mezon.mobile.ui.cells.ProfileSearchCell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GlobalSearchAdapter(
    private val themeColors: ThemeColors,
    private val resolveMessageSenderName: (SearchMessageDocument) -> String? = { null }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_MEMBER = 0
        const val VIEW_TYPE_CHANNEL = 1
        const val VIEW_TYPE_MESSAGE = 2
        const val VIEW_TYPE_HEADER = 3
        const val VIEW_TYPE_EMPTY = 4
        private const val DIFF_THRESHOLD = 50
    }

    private val items = ArrayList<Any>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null
    var hasMore = false
    var onChannelJoinClick: ((ChannelSearchDisplay) -> Unit)? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        if (position !in items.indices) return RecyclerView.NO_ID
        return when (val item = items[position]) {
            is SearchMember -> item.id
            is ChannelSearchDisplay -> item.channel.channelId
            is SearchMessageDocument -> {
                item.messageId.toLongOrNull()
                    ?: (item.messageId.hashCode().toLong() xor (item.channelId.hashCode().toLong() shl 32))
            }
            is SectionHeader -> item.stableId
            is EmptyItem -> -999L
            else -> RecyclerView.NO_ID
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (position !in items.indices) return VIEW_TYPE_EMPTY
        return when (items[position]) {
            is SearchMember -> VIEW_TYPE_MEMBER
            is ChannelSearchDisplay -> VIEW_TYPE_CHANNEL
            is SearchMessageDocument -> VIEW_TYPE_MESSAGE
            is SectionHeader -> VIEW_TYPE_HEADER
            is EmptyItem -> VIEW_TYPE_EMPTY
            else -> VIEW_TYPE_EMPTY
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        val view = when (viewType) {
            VIEW_TYPE_MEMBER -> ProfileSearchCell(context, themeColors)
            VIEW_TYPE_CHANNEL -> ChannelSearchCell(context, themeColors)
            VIEW_TYPE_MESSAGE -> MessageSearchCell(context, themeColors)
            VIEW_TYPE_HEADER -> HeaderCell(context, themeColors)
            else -> HeaderCell(context, themeColors)
        }
        view.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position !in items.indices) return
        val item = items[position]
        when (val view = holder.itemView) {
            is ProfileSearchCell -> if (item is SearchMember) view.update(0, item)
            is ChannelSearchCell -> if (item is ChannelSearchDisplay) {
                view.setJoinClickListener(
                    if (item.showJoinUi) onChannelJoinClick else null
                )
                view.update(0, item)
            }
            is MessageSearchCell -> if (item is SearchMessageDocument) {
                view.update(0, item, resolveMessageSenderName(item))
            }
            is HeaderCell -> if (item is SectionHeader) {
                view.setText(item.title)
                if (item.muted) {
                    view.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    view.textView.letterSpacing = 0.06f
                } else {
                    view.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    view.textView.letterSpacing = 0f
                }
            }
        }
    }

    fun setMembers(members: List<SearchMember>, header: String? = null) {
        val newItems = ArrayList<Any>()
        if (members.isEmpty()) {
            newItems.add(EmptyItem)
        } else {
            if (header != null) newItems.add(SectionHeader(header, MEMBERS_HEADER_STABLE_ID, muted = true))
            newItems.addAll(members)
        }
        submitList(newItems, forceSync = true)
    }

    fun setChannelSearchItems(
        displays: List<ChannelSearchDisplay>,
        textHeader: String,
        voiceHeader: String,
        streamHeader: String
    ) {
        val newItems = ArrayList<Any>()
        if (displays.isEmpty()) {
            newItems.add(EmptyItem)
        } else {
            var lastSec = -1
            for (d in displays) {
                val sec = channelSectionIndex(d.channel.type)
                if (sec != lastSec) {
                    val title = when (sec) {
                        0 -> textHeader
                        1 -> voiceHeader
                        else -> streamHeader
                    }
                    newItems.add(SectionHeader(title, sectionStableId(sec)))
                    lastSec = sec
                }
                newItems.add(d)
            }
        }
        submitList(newItems, forceSync = true)
    }

    fun setMessages(messages: List<SearchMessageDocument>) {
        val newItems = ArrayList<Any>()
        if (messages.isEmpty()) {
            newItems.add(EmptyItem)
        } else {
            newItems.addAll(messages)
        }
        submitList(newItems)
    }

    fun refreshMessageSenders() {
        if (items.any { it is SearchMessageDocument }) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    private fun submitList(newItems: List<Any>, forceSync: Boolean = false) {
        diffJob?.cancel()
        if (forceSync || (newItems.size < DIFF_THRESHOLD && items.size < DIFF_THRESHOLD)) {
            applyDiff(newItems, DiffUtil.calculateDiff(SearchDiffCallback(items, newItems)))
        } else {
            val oldList = ArrayList(items)
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(SearchDiffCallback(oldList, newItems))
                }
                applyDiff(newItems, result)
            }
        }
    }

    private fun applyDiff(newItems: List<Any>, result: DiffUtil.DiffResult) {
        items.clear()
        items.addAll(newItems)
        result.dispatchUpdatesTo(this)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    fun getItemAt(position: Int): Any? =
        if (position in items.indices) items[position] else null
}

data class SectionHeader(val title: String, val stableId: Long, val muted: Boolean = false)

object EmptyItem

private fun channelSectionIndex(type: Int): Int = when (type) {
    CHANNEL_TYPE_VOICE -> 1
    CHANNEL_TYPE_STREAMING -> 2
    else -> 0
}

private fun sectionStableId(sec: Int): Long = -(2000L + sec)

private const val MEMBERS_HEADER_STABLE_ID = -3000L

private class SearchDiffCallback(
    private val oldList: List<Any>,
    private val newList: List<Any>
) : DiffUtil.Callback() {

    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size

    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
        val oldItem = oldList[oldPos]
        val newItem = newList[newPos]
        if (oldItem::class != newItem::class) return false
        return when (oldItem) {
            is SearchMember -> oldItem.id == (newItem as SearchMember).id
            is ChannelSearchDisplay ->
                oldItem.channel.channelId == (newItem as ChannelSearchDisplay).channel.channelId
            is SearchMessageDocument -> oldItem.messageId == (newItem as SearchMessageDocument).messageId
            is SectionHeader -> oldItem.stableId == (newItem as SectionHeader).stableId
            is EmptyItem -> true
            else -> false
        }
    }

    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
        val oldItem = oldList[oldPos]
        val newItem = newList[newPos]
        return oldItem == newItem
    }
}
