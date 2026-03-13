package com.mezon.mobile.home.chat

import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ChatAdapter(
    private val themeColors: ThemeColors,
    var cellDelegate: ChatMessageCell.ChatMessageCellDelegate? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SENT = 0
        private const val TYPE_RECEIVED = 1
        private const val TYPE_LOADING_UP = 2
        private const val TYPE_LOADING_DOWN = 3
        private const val TYPE_SYSTEM = 4
    }

    private val items = ArrayList<MessageEntity>()

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
        items.clear()
        items.addAll(newItems)
        updateRowsInternal()
        notifyDataSetChanged()
    }

    fun getMessage(position: Int): MessageEntity? {
        val idx = position - messagesStartRow
        return if (idx in items.indices) items[idx] else null
    }

    override fun getItemCount(): Int = rowCount

    override fun getItemViewType(position: Int): Int = when (position) {
        loadingUpRow -> TYPE_LOADING_UP
        loadingDownRow -> TYPE_LOADING_DOWN
        else -> {
            val idx = position - messagesStartRow
            if (idx !in items.indices) TYPE_RECEIVED
            else {
                val msg = items[idx]
                when {
                    msg.isSystemMessage -> TYPE_SYSTEM
                    msg.isMe -> TYPE_SENT
                    else -> TYPE_RECEIVED
                }
            }
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
            TYPE_SYSTEM -> {
                val cell = SystemMessageCell(parent.context, themeColors).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                SystemViewHolder(cell)
            }
            else -> {
                val cell = ChatMessageCell(parent.context, themeColors).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                MessageViewHolder(cell)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val idx = position - messagesStartRow
        if (idx !in items.indices) return
        when (holder) {
            is MessageViewHolder -> {
                holder.cell.delegate = cellDelegate
                holder.cell.isCombined = computeCombined(idx)
                holder.cell.update(0, items[idx])
            }
            is SystemViewHolder -> {
                holder.cell.update(0, items[idx])
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MessageViewHolder) {
            holder.cell.clearState()
        }
    }

    private fun computeCombined(idx: Int): Boolean {
        if (idx + 1 >= items.size) return false
        val current = items[idx]
        val prev = items[idx + 1]
        if (current.senderId != prev.senderId) return false
        if (prev.isSystemMessage || current.isSystemMessage) return false
        val diff = current.timestampSeconds - prev.timestampSeconds
        return diff in 0..ChatMessageCell.COMBINE_TIME_THRESHOLD
    }

    class MessageViewHolder(val cell: ChatMessageCell) : RecyclerView.ViewHolder(cell)
    class SystemViewHolder(val cell: SystemMessageCell) : RecyclerView.ViewHolder(cell)
    class LoadingViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view)
}
