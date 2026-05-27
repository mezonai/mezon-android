package com.mezon.mobile.home.chat

import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.UserDisplayRole
import com.mezon.mobile.network.LinkInvitePreview

class ChatAdapter(
    private val themeColors: ThemeColors,
    private val messages: ArrayList<MessageEntity>,
    var channelName: String = "",
    var currentUserId: String = "",
    var cellDelegate: ChatMessageCell.ChatMessageCellDelegate? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var pollBridge: com.mezon.mobile.home.chat.poll.ChatPollBridge? = null
    var shareContactOnlineResolver: ((Long) -> Boolean)? = null
    var displayRoleResolver: ((Long) -> UserDisplayRole?)? = null
    var onTopicClick: ((topicId: Long, rootMessageId: Long) -> Unit)? = null
    var topicCreatorResolver: ((Long) -> Pair<String, String>?)? = null
    var topicLastMessageIdResolver: ((Long) -> Long)? = null
    var topicBadgeResolver: ((Long) -> Int)? = null
    var topicButtonEnabled = true

    init { setHasStableIds(true) }

    companion object {
        private const val TYPE_SENT = 0
        private const val TYPE_RECEIVED = 1
        private const val TYPE_LOADING_UP = 2
        private const val TYPE_LOADING_DOWN = 3
        private const val TYPE_SYSTEM = 4
        private const val TYPE_WELCOME = 5
        private const val TYPE_SEND_TOKEN = 6
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

    fun updateRowsInternal() {
        rowCount = 0
        if (messages.isNotEmpty()) {
            if (showLoadingDown) {
                loadingDownRow = rowCount++
            } else {
                loadingDownRow = -1
            }
            messagesStartRow = rowCount
            rowCount += messages.size
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

    fun updateRowsSafe() {
        val prevRowCount = rowCount
        val prevLoadingUpRow = loadingUpRow
        val prevLoadingDownRow = loadingDownRow
        val prevMessagesStartRow = messagesStartRow
        val prevMessagesEndRow = messagesEndRow
        updateRowsInternal()
        if (prevRowCount != rowCount || prevLoadingUpRow != loadingUpRow ||
            prevLoadingDownRow != loadingDownRow || prevMessagesStartRow != messagesStartRow ||
            prevMessagesEndRow != messagesEndRow
        ) {
            notifyDataSetChanged()
        }
    }

    fun notifyMessagesUpdated() {
        updateRowsInternal()
        notifyDataSetChanged()
    }

    fun notifyMessageInsertedAt(modelIndex: Int) {
        val prevMessagesStartRow = messagesStartRow
        val prevRowCount = rowCount
        updateRowsInternal()
        val structuralChange = prevRowCount == 0 ||
            messagesStartRow != prevMessagesStartRow ||
            modelIndex !in 0..messages.size
        if (structuralChange) {
            notifyDataSetChanged()
        } else {
            notifyItemInserted(messagesStartRow + modelIndex)
        }
    }

    fun notifyMessageRemovedAt(modelIndex: Int) {
        if (modelIndex < 0) return
        val prevMessagesStartRow = messagesStartRow
        val prevRowCount = rowCount
        val adapterPos = prevMessagesStartRow + modelIndex
        updateRowsInternal()
        val structuralChange = rowCount == 0 ||
            prevRowCount == 0 ||
            messagesStartRow != prevMessagesStartRow
        if (structuralChange) {
            notifyDataSetChanged()
        } else {
            notifyItemRemoved(adapterPos)
        }
    }

    fun notifyMessageChangedAt(modelIndex: Int) {
        if (modelIndex !in messages.indices) return
        notifyItemChanged(messagesStartRow + modelIndex)
    }

    fun notifyWelcomeCellChanged() {
        val idx = messages.indexOfFirst { it.isWelcomeMessage }
        if (idx >= 0) notifyItemChanged(messagesStartRow + idx)
    }

    fun notifyChannelNameDependentCellsChanged() {
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg.isWelcomeMessage || (msg.isSystemMessage && msg.code == MessageEntity.CODE_FIRST_MESSAGE)) {
                notifyItemChanged(messagesStartRow + i)
            }
        }
    }

    fun getMessage(position: Int): MessageEntity? {
        val idx = position - messagesStartRow
        return if (idx in messages.indices) messages[idx] else null
    }

    override fun getItemCount(): Int = rowCount

    override fun getItemId(position: Int): Long = when (position) {
        loadingUpRow -> Long.MIN_VALUE
        loadingDownRow -> Long.MIN_VALUE + 1
        else -> {
            val idx = position - messagesStartRow
            if (idx in messages.indices) messages[idx].id else RecyclerView.NO_ID
        }
    }

    override fun getItemViewType(position: Int): Int = when (position) {
        loadingUpRow -> TYPE_LOADING_UP
        loadingDownRow -> TYPE_LOADING_DOWN
        else -> {
            val idx = position - messagesStartRow
            if (idx !in messages.indices) TYPE_RECEIVED
            else {
                val msg = messages[idx]
                when {
                    msg.isWelcomeMessage -> TYPE_WELCOME
                    msg.isSystemMessage -> TYPE_SYSTEM
                    msg.code == MessageEntity.CODE_SEND_TOKEN -> TYPE_SEND_TOKEN
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
            TYPE_WELCOME -> {
                val cell = WelcomeMessageCell(parent.context, themeColors).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                WelcomeViewHolder(cell)
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
            TYPE_SEND_TOKEN -> {
                val cell = SendTokenMessageCell(parent.context, themeColors).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                SendTokenViewHolder(cell)
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
        if (idx !in messages.indices) return
        when (holder) {
            is MessageViewHolder -> {
                holder.cell.delegate = cellDelegate
                holder.cell.pollBridge = pollBridge
                holder.cell.shareContactOnlineResolver = shareContactOnlineResolver
                holder.cell.loadLinkInvitePreview = loadLinkInvitePreview
                holder.cell.isCombined = computeCombined(idx)
                holder.cell.currentUserId = currentUserId.toLongOrNull() ?: 0L
                holder.cell.channelType = channelType
                holder.cell.clanId = clanId
                holder.cell.isChannelPrivate = isChannelPrivate
                val msg = messages[idx]
                holder.cell.displayRoleResolver = displayRoleResolver
                holder.cell.onTopicClick = onTopicClick
                holder.cell.topicCreatorResolver = topicCreatorResolver
                holder.cell.topicLastMessageIdResolver = topicLastMessageIdResolver
                holder.cell.topicBadgeResolver = topicBadgeResolver
                holder.cell.topicButtonEnabled = topicButtonEnabled
                holder.cell.hasMentionHighlight = currentUserId.isNotEmpty() &&
                    msg.hasMention(currentUserId)
                holder.cell.update(0, msg)
            }
            is WelcomeViewHolder -> {
                holder.cell.channelName = channelName
                holder.cell.channelType = channelType
                holder.cell.clanId = clanId
                holder.cell.isPrivate = isChannelPrivate
                holder.cell.avatarUrl = welcomeAvatarUrl
                holder.cell.avatarUserId = welcomeAvatarId
                holder.cell.avatarPlaceholderKey = welcomePlaceholderKey
                holder.cell.peerUsername = welcomePeerUsername
                holder.cell.creatorName = welcomeCreatorName
                holder.cell.update(messages[idx])
            }
            is SystemViewHolder -> {
                holder.cell.channelName = channelName
                holder.cell.delegate = systemMessageDelegate
                holder.cell.mentionInteractiveGate = systemMessageMentionGate
                holder.cell.creatorNameResolver = systemMessageCreatorResolver
                holder.cell.update(0, messages[idx])
            }
            is SendTokenViewHolder -> {
                holder.cell.delegate = sendTokenDelegate
                holder.cell.update(messages[idx])
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is MessageViewHolder) {
            holder.cell.clearState()
        }
    }

    private fun computeCombined(idx: Int): Boolean {
        if (idx + 1 >= messages.size) return false
        val current = messages[idx]
        if (current.isPollMessage) return false
        if (current.content.contains("\"references\"")) return false
        val prev = messages[idx + 1]
        if (prev.isPollMessage) return false
        if (current.senderId != prev.senderId) return false
        if (prev.isSystemMessage || current.isSystemMessage) return false
        val diff = current.timestampSeconds - prev.timestampSeconds
        return diff in 0..ChatMessageCell.COMBINE_TIME_THRESHOLD
    }

    var channelType = 0
    var clanId = 0L
    var isChannelPrivate = false
    var welcomeAvatarUrl: String = ""
    var welcomeAvatarId: Long = 0L
    var welcomePlaceholderKey: String = ""
    var welcomePeerUsername: String = ""
    var welcomeCreatorName: String = ""
    var loadLinkInvitePreview: (suspend (Long) -> LinkInvitePreview?)? = null
    var sendTokenDelegate: SendTokenMessageCell.Delegate? = null
    var systemMessageDelegate: SystemMessageCell.Delegate? = null
    var systemMessageMentionGate: ((userId: String?, roleId: String?, segmentText: String) -> Boolean)? = null
    var systemMessageCreatorResolver: ((Long) -> String)? = null

    class MessageViewHolder(val cell: ChatMessageCell) : RecyclerView.ViewHolder(cell)
    class WelcomeViewHolder(val cell: WelcomeMessageCell) : RecyclerView.ViewHolder(cell)
    class SystemViewHolder(val cell: SystemMessageCell) : RecyclerView.ViewHolder(cell)
    class SendTokenViewHolder(val cell: SendTokenMessageCell) : RecyclerView.ViewHolder(cell)
    class LoadingViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view)
}
