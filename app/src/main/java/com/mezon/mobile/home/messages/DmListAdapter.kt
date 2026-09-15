package com.mezon.mobile.home.messages

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.HeaderCell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class DmListEntry {
    abstract val stableId: Long

    data class Header(val title: String, val headerId: Long) : DmListEntry() {
        override val stableId: Long = headerId
    }

    data class Message(val dm: DirectMessage) : DmListEntry() {
        override val stableId: Long = dm.channelId
    }
}

class DmListAdapter(
    private val themeColors: ThemeColors,
    private val inVoiceChecker: ((DirectMessage) -> Boolean)? = null,
    private val buzzChecker: ((Long) -> Boolean)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val PAYLOAD_VOICE_PRESENCE = "voice_presence"
        private const val DIFF_BG_THRESHOLD = 50
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_MESSAGE = 1
        const val HEADER_ID_PINNED = -1L
        const val HEADER_ID_ALL = -2L
    }

    private val items = ArrayList<DmListEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null
    private var entryBuilder: ((List<DirectMessage>) -> List<DmListEntry>)? = null

    fun setEntryBuilder(builder: (List<DirectMessage>) -> List<DmListEntry>) {
        entryBuilder = builder
    }

    private fun messagesToEntries(messages: List<DirectMessage>): List<DmListEntry> {
        return entryBuilder?.invoke(messages) ?: messages.map { DmListEntry.Message(it) }
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DmListEntry.Header -> VIEW_TYPE_HEADER
        is DmListEntry.Message -> VIEW_TYPE_MESSAGE
    }

    override fun getItemId(position: Int): Long =
        if (position in items.indices) items[position].stableId else RecyclerView.NO_ID

    fun getMessageAt(position: Int): DirectMessage? =
        (items.getOrNull(position) as? DmListEntry.Message)?.dm

    fun setData(newItems: List<DmListEntry>) {
        diffJob?.cancel()

        if (newItems.size < DIFF_BG_THRESHOLD && items.size < DIFF_BG_THRESHOLD) {
            applyDiff(newItems, DiffUtil.calculateDiff(DmEntryDiffCallback(items, newItems)))
        } else {
            val oldList = ArrayList(items)
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(DmEntryDiffCallback(oldList, newItems))
                }
                applyDiff(newItems, result)
            }
        }
    }

    fun setMessages(newItems: List<DirectMessage>) {
        setData(messagesToEntries(newItems))
    }

    private fun applyDiff(newItems: List<DmListEntry>, result: DiffUtil.DiffResult) {
        items.clear()
        items.addAll(newItems)
        result.dispatchUpdatesTo(this)
    }

    fun updateVisibleRows(recyclerView: RecyclerView, mask: Int, dialogs: List<DirectMessage>? = null) {
        val dialogMap: HashMap<Long, DirectMessage>?
        if (dialogs != null) {
            dialogMap = HashMap(dialogs.size)
            for (dm in dialogs) dialogMap[dm.channelId] = dm
            for (i in items.indices) {
                val entry = items[i]
                if (entry is DmListEntry.Message) {
                    val updated = dialogMap[entry.dm.channelId]
                    if (updated != null && updated != entry.dm) {
                        items[i] = DmListEntry.Message(updated)
                    }
                }
            }
        } else {
            dialogMap = null
        }

        val count = recyclerView.childCount
        for (i in 0 until count) {
            val child = recyclerView.getChildAt(i)
            if (child is DialogCell) {
                val current = child.directMessage ?: continue
                child.hasBuzz = buzzChecker?.invoke(current.channelId) == true
                val updated = dialogMap?.get(current.channelId)
                if (child.update(mask, updated)) {
                    val messages = if (dialogs != null) {
                        buildFlatMessagesFromEntries(items, dialogs)
                    } else {
                        items.filterIsInstance<DmListEntry.Message>().map { it.dm }
                    }
                    setData(messagesToEntries(messages))
                    break
                }
            }
        }
    }

    fun refreshVoicePresence() {
        if (items.isEmpty()) return
        notifyItemRangeChanged(0, items.size, PAYLOAD_VOICE_PRESENCE)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val cell = HeaderCell(parent.context, themeColors)
                cell.layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                HeaderViewHolder(cell)
            }
            else -> {
                val cell = DialogCell(parent.context, themeColors)
                cell.layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                MessageViewHolder(cell)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = items[position]) {
            is DmListEntry.Header -> (holder as HeaderViewHolder).cell.setText(entry.title)
            is DmListEntry.Message -> {
                val cell = (holder as MessageViewHolder).cell
                cell.hasBuzz = buzzChecker?.invoke(entry.dm.channelId) == true
                cell.isInVoice = inVoiceChecker?.invoke(entry.dm) == true
                cell.update(0, entry.dm)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val entry = items.getOrNull(position)
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_VOICE_PRESENCE }) {
            if (entry is DmListEntry.Message && holder is MessageViewHolder) {
                holder.cell.applyInVoice(inVoiceChecker?.invoke(entry.dm) == true)
            }
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    class HeaderViewHolder(val cell: HeaderCell) : RecyclerView.ViewHolder(cell)
    class MessageViewHolder(val cell: DialogCell) : RecyclerView.ViewHolder(cell)

    private class DmEntryDiffCallback(
        private val old: List<DmListEntry>,
        private val new: List<DmListEntry>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val o = old[oldPos]
            val n = new[newPos]
            return when {
                o is DmListEntry.Header && n is DmListEntry.Header -> o.headerId == n.headerId
                o is DmListEntry.Message && n is DmListEntry.Message -> o.dm.channelId == n.dm.channelId
                else -> false
            }
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = old[oldPos] == new[newPos]
    }

    private fun buildFlatMessagesFromEntries(entries: List<DmListEntry>, dialogs: List<DirectMessage>): List<DirectMessage> {
        val dialogMap = HashMap<Long, DirectMessage>(dialogs.size)
        for (dm in dialogs) dialogMap[dm.channelId] = dm
        return entries.filterIsInstance<DmListEntry.Message>().mapNotNull { dialogMap[it.dm.channelId] ?: it.dm }
    }
}

fun buildSectionedDmEntries(
    messages: List<DirectMessage>,
    pinnedIds: List<Long>,
    pinnedSectionTitle: String,
    allMessagesSectionTitle: String,
): List<DmListEntry> {
    if (pinnedIds.isEmpty()) {
        return messages.map { DmListEntry.Message(it) }
    }
    val pinnedSet = pinnedIds.toHashSet()
    val pinned = ArrayList<DirectMessage>(pinnedIds.size)
    for (id in pinnedIds) {
        val dm = messages.firstOrNull { it.channelId == id } ?: continue
        pinned.add(dm)
    }
    val unpinned = messages.filter { it.channelId !in pinnedSet }
    val result = ArrayList<DmListEntry>(pinned.size + unpinned.size + 2)
    if (pinned.isNotEmpty()) {
        result.add(DmListEntry.Header(pinnedSectionTitle, DmListAdapter.HEADER_ID_PINNED))
        for (dm in pinned) result.add(DmListEntry.Message(dm))
    }
    if (unpinned.isNotEmpty()) {
        result.add(DmListEntry.Header(allMessagesSectionTitle, DmListAdapter.HEADER_ID_ALL))
        for (dm in unpinned) result.add(DmListEntry.Message(dm))
    }
    return result
}
