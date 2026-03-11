package com.mezon.mobile.home.chat

import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatAdapter(
    private val themeColors: ThemeColors
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SENT = 0
        private const val TYPE_RECEIVED = 1
        private const val TYPE_LOADING_UP = 2
        private const val TYPE_LOADING_DOWN = 3
        private const val DIFF_BG_THRESHOLD = 50
        private const val PRECACHE_COUNT = 10
    }

    private val items = ArrayList<MessageEntity>()
    private val scope = CoroutineScope(Dispatchers.Main)
    private var diffJob: Job? = null
    private val cellCache = ArrayDeque<ChatMessageCell>(PRECACHE_COUNT)
    private var cacheContext: android.content.Context? = null

    fun precacheCells(context: android.content.Context) {
        cacheContext = context
        for (i in 0 until PRECACHE_COUNT) {
            val cell = ChatMessageCell(context, themeColors).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            cellCache.addLast(cell)
        }
    }

    private fun obtainCell(context: android.content.Context): ChatMessageCell {
        return cellCache.removeFirstOrNull() ?: ChatMessageCell(context, themeColors).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }
    }

    fun recycleCells(recyclerView: RecyclerView) {
        val count = recyclerView.childCount
        for (i in 0 until count) {
            val child = recyclerView.getChildAt(i)
            if (child is ChatMessageCell && cellCache.size < PRECACHE_COUNT) {
                cellCache.addLast(child)
            }
        }
    }

    var loadingUpRow = -1
        private set
    var loadingDownRow = -1
        private set
    var messagesStartRow = 0
        private set
    var messagesEndRow = 0
        private set

    private var rowCount = 0
    var showLoadingUp = false
    var showLoadingDown = false

    fun updateRowsSafe() {
        val prevRowCount = rowCount
        val prevLoadingUp = loadingUpRow
        val prevLoadingDown = loadingDownRow
        val prevStart = messagesStartRow
        val prevEnd = messagesEndRow

        updateRowsInternal()

        if (prevRowCount != rowCount ||
            prevLoadingUp != loadingUpRow ||
            prevLoadingDown != loadingDownRow ||
            prevStart != messagesStartRow ||
            prevEnd != messagesEndRow
        ) {
            notifyDataSetChanged()
        }
    }

    private fun updateRowsInternal() {
        rowCount = 0

        if (items.isNotEmpty()) {
            if (showLoadingDown) {
                loadingDownRow = rowCount++
            } else {
                loadingDownRow = -1
            }

            messagesStartRow = rowCount
            rowCount += items.size
            messagesEndRow = rowCount

            if (showLoadingUp) {
                loadingUpRow = rowCount++
            } else {
                loadingUpRow = -1
            }
        } else {
            loadingUpRow = -1
            loadingDownRow = -1
            messagesStartRow = 0
            messagesEndRow = 0
        }
    }

    fun setData(newItems: List<MessageEntity>) {
        diffJob?.cancel()

        if (newItems.size < DIFF_BG_THRESHOLD && items.size < DIFF_BG_THRESHOLD) {
            applyDiff(newItems, DiffUtil.calculateDiff(MessageDiffCallback(items, newItems)))
        } else {
            val oldList = ArrayList(items)
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(MessageDiffCallback(oldList, newItems))
                }
                applyDiff(newItems, result)
            }
        }
    }

    private fun applyDiff(newItems: List<MessageEntity>, result: DiffUtil.DiffResult) {
        items.clear()
        items.addAll(newItems)
        updateRowsInternal()
        result.dispatchUpdatesTo(this)
    }

    fun getMessage(position: Int): MessageEntity? {
        val idx = position - messagesStartRow
        return if (idx in items.indices) items[idx] else null
    }

    override fun getItemCount(): Int {
        updateRowsInternal()
        return rowCount
    }

    override fun getItemViewType(position: Int): Int = when (position) {
        loadingUpRow -> TYPE_LOADING_UP
        loadingDownRow -> TYPE_LOADING_DOWN
        else -> {
            val idx = position - messagesStartRow
            if (idx in items.indices && items[idx].isMe) TYPE_SENT else TYPE_RECEIVED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_LOADING_UP, TYPE_LOADING_DOWN -> {
                val pb = ProgressBar(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        LayoutHelper.dp(48)
                    )
                }
                LoadingViewHolder(pb)
            }
            else -> {
                MessageViewHolder(obtainCell(parent.context))
            }
        }
    }

    var freezeDuringFastScroll = false
    private var recyclerListView: com.mezon.mobile.core.RecyclerListView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerListView = recyclerView as? com.mezon.mobile.core.RecyclerListView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerListView = null
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is MessageViewHolder) {
            if (freezeDuringFastScroll && recyclerListView?.isFastScrolling() == true) {
                return
            }
            val idx = position - messagesStartRow
            if (idx in items.indices) {
                holder.cell.update(0, items[idx])
            }
        }
    }

    class MessageViewHolder(val cell: ChatMessageCell) : RecyclerView.ViewHolder(cell)
    class LoadingViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view)

    private class MessageDiffCallback(
        private val old: List<MessageEntity>,
        private val new: List<MessageEntity>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos].id == new[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos] == new[newPos]
    }
}
