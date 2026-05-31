package com.mezon.mobile.home.chat.thread

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ThreadListAdapter(
    private val theme: ThemeColors,
    private val creatorNameResolver: (Long, String) -> String,
    private val senderNameResolver: (Long) -> String,
    private val onThreadClick: (ThreadInfo) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items = ArrayList<Any>()
    private var displayNames = HashMap<Long, String>()
    private var adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: kotlinx.coroutines.Job? = null

    init {
        setHasStableIds(true)
    }

    fun setData(sections: List<ThreadSection>, animateChanges: Boolean = true) {
        val newItems = ArrayList<Any>()
        val threads = ArrayList<ThreadInfo>()
        for (section in sections) {
            newItems.add(section.title)
            for (thread in section.threads) {
                newItems.add(thread)
                threads.add(thread)
            }
        }
        diffJob?.cancel()
        if (!animateChanges) {
            items.clear()
            items.addAll(newItems)
            displayNames = resolveNames(threads)
            notifyDataSetChanged()
            return
        }
        if (items.size > 50 || newItems.size > 50) {
            val old = items
            diffJob = adapterScope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(ThreadDiffCallback(old, newItems))
                }
                val newNames = withContext(Dispatchers.Default) { resolveNames(threads) }
                items = newItems
                displayNames = newNames
                result.dispatchUpdatesTo(this@ThreadListAdapter)
            }
        } else {
            val newNames = resolveNames(threads)
            val result = DiffUtil.calculateDiff(ThreadDiffCallback(items, newItems))
            items = newItems
            displayNames = newNames
            result.dispatchUpdatesTo(this)
        }
    }

    private fun resolveNames(threads: List<ThreadInfo>): HashMap<Long, String> {
        val names = HashMap<Long, String>(threads.size)
        for (thread in threads) {
            names[thread.channelId] = resolveDisplayName(thread)
        }
        return names
    }

    private fun resolveDisplayName(thread: ThreadInfo): String {
        val creatorName = creatorNameResolver(thread.creatorId, thread.creatorName)
        return if (thread.lastSenderId != 0L) {
            senderNameResolver(thread.lastSenderId).ifBlank { creatorName }
        } else {
            creatorName
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        return if (items[position] is String) VIEW_TYPE_SECTION else VIEW_TYPE_THREAD
    }

    override fun getItemId(position: Int): Long {
        val item = items[position]
        return if (item is ThreadInfo) item.channelId else (item as String).hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = when (viewType) {
            VIEW_TYPE_SECTION -> ThreadSectionCell(parent.context, theme)
            else -> ThreadCell(parent.context, theme)
        }
        val holder = object : RecyclerView.ViewHolder(view) {}
        if (viewType == VIEW_TYPE_THREAD) {
            view.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = items.getOrNull(pos) as? ThreadInfo ?: return@setOnClickListener
                onThreadClick(item)
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is String -> {
                (holder.itemView as ThreadSectionCell).setTitle(item)
            }
            is ThreadInfo -> {
                val senderName = displayNames[item.channelId] ?: item.creatorName
                (holder.itemView as ThreadCell).setData(item, senderName)
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    fun getThreadAt(position: Int): ThreadInfo? {
        return items.getOrNull(position) as? ThreadInfo
    }

    private class ThreadDiffCallback(
        private val oldList: List<Any>,
        private val newList: List<Any>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            if (old is String && new is String) return old == new
            if (old is ThreadInfo && new is ThreadInfo) return old.channelId == new.channelId
            return false
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val new = newList[newPos]
            if (old is String && new is String) return old == new
            if (old is ThreadInfo && new is ThreadInfo) {
                return old.channelLabel == new.channelLabel &&
                    old.active == new.active &&
                    old.isPrivate == new.isPrivate &&
                    old.creatorId == new.creatorId &&
                    old.creatorName == new.creatorName &&
                    old.lastMessageContent == new.lastMessageContent &&
                    old.lastMessageTs == new.lastMessageTs &&
                    old.lastSenderId == new.lastSenderId
            }
            return false
        }
    }

    companion object {
        const val VIEW_TYPE_SECTION = 0
        const val VIEW_TYPE_THREAD = 1
    }
}
