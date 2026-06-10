package com.mezon.mobile.home.chat

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.PinMessageData
import com.mezon.mobile.home.chat.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PinMessageAdapter(
    private val themeColors: ThemeColors,
    private val delegate: PinMessageCell.PinMessageCellDelegate,
    private val nameResolver: (PinMessageData) -> String?,
    private val avatarResolver: (PinMessageData) -> String?
) : RecyclerView.Adapter<PinMessageAdapter.ViewHolder>() {

    private val items = ArrayList<PinMessageData>()
    private val messageById = HashMap<Long, MessageEntity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        if (position !in items.indices) return RecyclerView.NO_ID
        val key = items[position].pinDedupeKey()
        if (key.isNotEmpty()) return key.hashCode().toLong()
        return position.toLong()
    }

    override fun getItemCount(): Int = items.size

    fun getItem(position: Int): PinMessageData? =
        if (position in items.indices) items[position] else null

    val pinCount: Int get() = items.size

    fun removeByMessageId(messageId: Long): Boolean {
        if (messageId == 0L) return false
        val index = items.indexOfFirst { it.messageId == messageId }
        if (index < 0) return false
        items.removeAt(index)
        messageById.remove(messageId)
        notifyItemRemoved(index)
        return true
    }

    fun setData(
        newItems: List<PinMessageData>,
        messages: Map<Long, MessageEntity> = emptyMap(),
        mergeMessages: Boolean = false
    ) {
        diffJob?.cancel()
        if (mergeMessages) {
            messageById.putAll(messages)
        } else {
            messageById.clear()
            messageById.putAll(messages)
        }

        if (newItems.size < 50 && items.size < 50) {
            applyDiff(newItems, DiffUtil.calculateDiff(PinDiffCallback(items, newItems)))
        } else {
            val oldList = ArrayList(items)
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(PinDiffCallback(oldList, newItems))
                }
                applyDiff(newItems, result)
            }
        }
    }

    private fun applyDiff(newItems: List<PinMessageData>, result: DiffUtil.DiffResult) {
        items.clear()
        items.addAll(newItems)
        result.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val cell = PinMessageCell(parent.context, themeColors).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }
        cell.delegate = delegate
        return ViewHolder(cell)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val data = items[position]
        holder.cell.setData(
            data,
            nameResolver(data),
            avatarResolver(data),
            messageById[data.messageId]
        )
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    class ViewHolder(val cell: PinMessageCell) : RecyclerView.ViewHolder(cell)
}

private class PinDiffCallback(
    private val old: List<PinMessageData>,
    private val new: List<PinMessageData>
) : DiffUtil.Callback() {
    override fun getOldListSize(): Int = old.size
    override fun getNewListSize(): Int = new.size
    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
        val oldKey = old[oldPos].pinDedupeKey()
        val newKey = new[newPos].pinDedupeKey()
        return oldKey.isNotEmpty() && oldKey == newKey
    }
    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
        old[oldPos] == new[newPos]
}
