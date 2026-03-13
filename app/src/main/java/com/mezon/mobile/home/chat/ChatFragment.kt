package com.mezon.mobile.home.chat

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LongSparseArray
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.PageDownButton

class ChatFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_MESSAGE_ID = "message_id"
        private const val VIEWPORT_LIMIT = 100
        private const val VIEWPORT_TARGET = 50
        private const val PAGE_DOWN_SCROLL_THRESHOLD = 15

        fun newInstance(
            channelId: Long,
            channelName: String,
            clanId: Long = 0L,
            channelType: Int = 0,
            messageId: Long = 0L
        ): ChatFragment = ChatFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putString(ARG_CHANNEL_NAME, channelName)
                putLong(ARG_CLAN_ID, clanId)
                putInt(ARG_CHANNEL_TYPE, channelType)
                if (messageId != 0L) putLong(ARG_MESSAGE_ID, messageId)
            }
        }
    }

    private lateinit var chatController: ChatController
    private lateinit var dialogsController: DialogsController
    private lateinit var channelController: ChannelController

    private lateinit var recyclerView: RecyclerListView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var adapter: ChatAdapter
    private lateinit var rootView: LinearLayout
    private lateinit var inputBar: LinearLayout
    private lateinit var pageDownButton: PageDownButton

    private var channelId = 0L
    private var channelName = ""
    private var clanId = 0L
    private var channelType = 0
    private var startLoadFromMessageId = 0L
    private var isLoading = false
    private var isLoadingMore = false
    private var hasMoreTop = false
    private var hasMoreBottom = false
    private var isViewingOlder = false
    private var firstLoad = true
    private var newUnreadCount = 0
    private var lastSeenMessageId = 0L
    private var lastSentMessageId = 0L

    private val messages = ArrayList<MessageEntity>()
    private val messagesDict = LongSparseArray<MessageEntity>()

    fun getChannelId(): Long = channelId

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME) ?: ""
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: 0
        startLoadFromMessageId = arguments?.getLong(ARG_MESSAGE_ID) ?: 0L

        if (clanId == 0L) {
            val dm = dialogsController.getDialog(channelId)
            lastSeenMessageId = dm?.lastSeenMessageId ?: 0L
            lastSentMessageId = dm?.lastSentMessageId ?: 0L
        } else {
            val ch = channelController.findChannelById(channelId)
            lastSeenMessageId = ch?.lastSeenMessageId ?: 0L
            lastSentMessageId = ch?.lastSentMessageId ?: 0L
        }
        val isSeenUpToDate = lastSentMessageId == 0L || lastSeenMessageId >= lastSentMessageId
        val hasUnread = !isSeenUpToDate && lastSeenMessageId != 0L

        observe(NotificationCenter.messagesDidLoad) { _, _, args ->
            if (args.size < 5 || args[0] != channelId) return@observe
            @Suppress("UNCHECKED_CAST")
            val loadedMessages = args[1] as? ArrayList<MessageEntity> ?: return@observe
            val moreTop = args[2] as? Boolean ?: false
            val moreBottom = args[3] as? Boolean ?: false
            val isCache = args[4] as? Boolean ?: false

            if (isCache) {
                var addedFromCache = false
                for (m in loadedMessages) {
                    if (messagesDict.get(m.id) == null) {
                        messagesDict.put(m.id, m)
                        addedFromCache = true
                    }
                }
                if (addedFromCache || messages.isEmpty()) {
                    messages.clear()
                    val all = ArrayList<MessageEntity>(messagesDict.size())
                    for (i in 0 until messagesDict.size()) all.add(messagesDict.valueAt(i))
                    all.sortByDescending { it.timestampSeconds }
                    messages.addAll(all)
                }
                hasMoreTop = moreTop
                hasMoreBottom = moreBottom
            } else {
                if (messages.isEmpty()) {
                    for (m in loadedMessages.reversed()) messages.add(m)
                    for (m in loadedMessages) messagesDict.put(m.id, m)
                    hasMoreTop = moreTop
                    hasMoreBottom = moreBottom
                } else {
                    var addedNew = false
                    for (m in loadedMessages) {
                        if (messagesDict.get(m.id) == null) {
                            if (m.timestampSeconds >= (messages.firstOrNull()?.timestampSeconds ?: 0L)) {
                                messages.add(0, m)
                            } else {
                                messages.add(m)
                            }
                            messagesDict.put(m.id, m)
                            addedNew = true
                        }
                    }
                    if (moreTop) hasMoreTop = true
                    if (!moreTop && addedNew) hasMoreTop = false
                    if (moreBottom) hasMoreBottom = true
                    if (!moreBottom && addedNew) hasMoreBottom = false
                    trimViewport()
                }
            }

            isLoading = false
            isLoadingMore = false
            if (fragmentView != null) {
                val wasFirstLoad = firstLoad
                firstLoad = false
                refreshUI()
                if (startLoadFromMessageId != 0L) {
                    scrollToMessageId(startLoadFromMessageId)
                    startLoadFromMessageId = 0L
                } else if (wasFirstLoad && lastSeenMessageId != 0L && hasUnread) {
                    scrollToMessageId(lastSeenMessageId)
                    updatePageDownVisibility()
                } else if (wasFirstLoad) {
                    forceScrollToBottom()
                    markAsRead()
                }
            }
        }

        observe(NotificationCenter.didReceiveNewMessages) { _, _, args ->
            if (args.size < 2 || args[0] != channelId) return@observe
            val entity = args[1] as? MessageEntity ?: return@observe
            if (messagesDict.get(entity.id) != null) return@observe
            if (isViewingOlder) {
                newUnreadCount++
                hasMoreBottom = true
                if (::pageDownButton.isInitialized) {
                    pageDownButton.setUnreadCount(newUnreadCount)
                    pageDownButton.show(true)
                }
                return@observe
            }
            messages.add(0, entity)
            messagesDict.put(entity.id, entity)
            trimViewportOldest()
            if (fragmentView != null) {
                refreshUI()
                scrollToBottom()
            }
        }

        observe(NotificationCenter.messageDidUpdate) { _, _, args ->
            if (args.size < 2 || args[0] != channelId) return@observe
            val entity = args[1] as? MessageEntity ?: return@observe
            val idx = messages.indexOfFirst { it.id == entity.id }
            if (idx >= 0) {
                messages[idx] = entity
                messagesDict.put(entity.id, entity)
                val mask = if (args.size >= 3) args[2] as? Int ?: NotificationCenter.UPDATE_MASK_MESSAGE_TEXT else NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
                if (fragmentView != null) updateVisibleRows(mask)
            }
        }

        observe(NotificationCenter.messageDidDelete) { _, _, args ->
            if (args.size < 2 || args[0] != channelId) return@observe
            val messageId = args[1] as? Long ?: return@observe
            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                messages.removeAt(idx)
                messagesDict.delete(messageId)
                if (fragmentView != null) refreshUI()
            }
        }

        observe(NotificationCenter.messagesLoadError) { _, _, args ->
            if (args.isNotEmpty() && args[0] == channelId) {
                isLoading = false
                isLoadingMore = false
                if (fragmentView != null && messages.isEmpty()) {
                    showError(args.getOrNull(1) as? String ?: "Failed to load")
                }
            }
        }

        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            updateVisibleRows(mask)
        }

        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            rootView.setBackgroundColor(themeColors.background)
            inputBar.setBackgroundColor(themeColors.surface)
            inputField.setTextColor(themeColors.onSurface)
            inputField.setHintTextColor(themeColors.onSurfaceVariant)
            sendButton.setColorFilter(themeColors.primary)
            actionBar?.applyTheme()
            pageDownButton.applyColors()
            adapter.notifyDataSetChanged()
        }

        if (hasUnread && startLoadFromMessageId == 0L) {
            chatController.loadMessagesAround(channelId, clanId, lastSeenMessageId)
        } else {
            chatController.loadMessages(channelId, clanId)
        }
        return true
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        chatController = entryPoint.chatController()
        dialogsController = entryPoint.dialogsController()
        channelController = entryPoint.channelController()
    }

    override fun createView(context: Context): View {
        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        val chatActionBar = ActionBarView(context, themeColors).apply {
            setTitle(channelName)
            setBackClickListener { finishFragment() }
        }
        actionBar = chatActionBar
        rootView.addView(chatActionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))

        val contentFrame = FrameLayout(context)
        rootView.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        recyclerView = RecyclerListView(context).apply {
            val lm = LinearLayoutManager(context)
            lm.reverseLayout = true
            lm.stackFromEnd = false
            layoutManager = lm
            visibility = View.GONE
        }
        contentFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingView = ProgressBar(context).apply { visibility = View.GONE }
        contentFrame.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        errorView = TextView(context).apply {
            setTextColor(themeColors.error)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentFrame.addView(errorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        pageDownButton = PageDownButton(context, themeColors).apply {
            visibility = View.GONE
            setOnClickListener { jumpToPresent() }
        }
        contentFrame.addView(
            pageDownButton,
            FrameLayout.LayoutParams(
                LayoutHelper.dp(64f), LayoutHelper.dp(64f),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = LayoutHelper.dp(8f)
                bottomMargin = LayoutHelper.dp(8f)
            }
        )

        inputBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(themeColors.surface)
            val pad = LayoutHelper.dp(8)
            setPadding(pad, pad, pad, pad)
        }
        rootView.addView(inputBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        inputField = EditText(context).apply {
            hint = getString(R.string.message_input_placeholder)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            textSize = 15f
            maxLines = 6
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        inputBar.addView(inputField, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        sendButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setColorFilter(themeColors.primary)
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { sendMessage() }
        }
        inputBar.addView(sendButton, LayoutHelper.createLinear(48, 48))

        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val hasText = s?.isNotBlank() == true
                sendButton.isEnabled = hasText
                sendButton.alpha = if (hasText) 1f else 0.5f
            }
        })

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }

        adapter = ChatAdapter(themeColors, cellDelegate = object : ChatMessageCell.ChatMessageCellDelegate {
            override fun didClickMedia(cell: ChatMessageCell, msg: MessageEntity) {
                val url = msg.attachmentUrl
                if (url.isEmpty()) return
                when (msg.messageType) {
                    MessageEntity.TYPE_VIDEO -> {
                        VideoPlayerDialog(context).play(url)
                    }
                    MessageEntity.TYPE_PHOTO -> {
                        val gallery = messages.filter { it.messageType == MessageEntity.TYPE_PHOTO && it.attachmentUrl.isNotEmpty() }
                            .map { it.attachmentUrl }
                        val idx = gallery.indexOf(url).coerceAtLeast(0)
                        PhotoViewer(context).show(url, gallery = gallery, index = idx)
                    }
                    MessageEntity.TYPE_GIF -> {
                        PhotoViewer(context).show(url, animated = true)
                    }
                }
            }
            override fun didClickFile(cell: ChatMessageCell, msg: MessageEntity) {
                val url = msg.attachmentUrl
                if (url.isEmpty()) return
                try {
                    val mime = when {
                        msg.attachmentFiletype.isNotEmpty() -> msg.attachmentFiletype
                        else -> "*/*"
                    }
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    intent.setDataAndType(android.net.Uri.parse(url), mime)
                    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val filename = msg.attachmentFilename.ifEmpty { url.substringAfterLast('/') }
                        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                            .setTitle(filename)
                            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, filename)
                        val dm = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                        dm.enqueue(request)
                        android.widget.Toast.makeText(context, "Downloading $filename", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
            }
        })
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val firstVisible = lm.findFirstVisibleItemPosition()
                val wasViewingOlder = isViewingOlder
                isViewingOlder = firstVisible > PAGE_DOWN_SCROLL_THRESHOLD

                if (isViewingOlder != wasViewingOlder) {
                    updatePageDownVisibility()
                    if (!isViewingOlder && !hasMoreBottom) markAsRead()
                }

                if (!isLoadingMore && hasMoreTop) {
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val totalCount = adapter.itemCount
                    if (totalCount > 0 && lastVisible >= totalCount - 5) {
                        val oldest = messages.lastOrNull()?.id ?: return
                        isLoadingMore = true
                        chatController.loadMoreTop(channelId, clanId, oldest)
                    }
                }

                if (!isLoadingMore && hasMoreBottom) {
                    if (firstVisible <= 3 && messages.isNotEmpty()) {
                        val newest = messages.firstOrNull()?.id ?: return
                        isLoadingMore = true
                        chatController.loadMoreBottom(channelId, clanId, newest)
                    }
                }
            }
        })

        return rootView
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        dialogsController.setCurrentChannel(channelId)
        if (clanId != 0L) {
            channelController.setCurrentChannel(channelId)
            channelController.markChannelAsRead(channelId)
        }
        if (messages.isNotEmpty()) {
            showMessages()
            if (!isViewingOlder) markAsRead()
        } else {
            isLoading = true
            showLoading()
        }
    }

    override fun onFragmentDestroy() {
        dialogsController.clearCurrentChannel()
        if (clanId != 0L) channelController.clearCurrentChannel()
        messages.clear()
        messagesDict.clear()
        super.onFragmentDestroy()
    }

    private fun refreshUI() {
        if (messages.isNotEmpty()) showMessages()
        else if (isLoading) showLoading()
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        errorView.visibility = View.GONE
    }

    private fun showError(message: String) {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorView.text = message
    }

    private fun showMessages() {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        adapter.showLoadingUp = hasMoreTop
        adapter.showLoadingDown = hasMoreBottom
        adapter.setData(messages)
    }

    private fun forceScrollToBottom() {
        recyclerView.post { recyclerView.scrollToPosition(0) }
    }

    private fun scrollToBottom() {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = lm.findFirstVisibleItemPosition()
        if (firstVisible <= 3 || firstVisible == RecyclerView.NO_POSITION) {
            recyclerView.post { recyclerView.scrollToPosition(0) }
        }
    }

    private fun scrollToMessageId(messageId: Long) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
            recyclerView.post { lm.scrollToPositionWithOffset(idx, recyclerView.height / 3) }
        }
    }

    private fun jumpToPresent() {
        newUnreadCount = 0
        isViewingOlder = false
        hasMoreBottom = false
        pageDownButton.show(false)
        pageDownButton.setUnreadCount(0)

        messages.clear()
        messagesDict.clear()
        firstLoad = true
        chatController.loadMessages(channelId, clanId)
        markAsRead()
    }

    private fun markAsRead() {
        val newest = messages.firstOrNull() ?: return
        chatController.updateLastSeenMessage(
            channelId, clanId, channelType,
            newest.id, newest.timestampSeconds.toInt()
        )
    }

    private fun updatePageDownVisibility() {
        val shouldShow = isViewingOlder || hasMoreBottom
        pageDownButton.show(shouldShow)
        if (!shouldShow) {
            newUnreadCount = 0
            pageDownButton.setUnreadCount(0)
        }
    }

    private fun trimViewport() {
        if (messages.size <= VIEWPORT_LIMIT) return
        if (isViewingOlder) {
            while (messages.size > VIEWPORT_TARGET) {
                val removed = messages.removeAt(0)
                messagesDict.delete(removed.id)
            }
            hasMoreBottom = true
        } else {
            while (messages.size > VIEWPORT_TARGET) {
                val removed = messages.removeAt(messages.size - 1)
                messagesDict.delete(removed.id)
            }
            hasMoreTop = true
        }
    }

    private fun trimViewportOldest() {
        while (messages.size > VIEWPORT_LIMIT) {
            val removed = messages.removeAt(messages.size - 1)
            messagesDict.delete(removed.id)
            hasMoreTop = true
        }
    }

    private fun updateVisibleRows(mask: Int = 0) {
        if (isPaused) return
        val count = recyclerView.childCount
        for (i in 0 until count) {
            when (val child = recyclerView.getChildAt(i)) {
                is ChatMessageCell -> {
                    val msg = child.messageEntity ?: continue
                    val updated = messagesDict.get(msg.id) ?: continue
                    if (mask == 0) {
                        if (updated !== msg) child.update(0, updated)
                    } else {
                        child.update(mask, if (updated !== msg) updated else null)
                    }
                }
                is SystemMessageCell -> {
                    if (mask == 0) {
                        val msg = child.messageEntity ?: continue
                        val updated = messagesDict.get(msg.id) ?: continue
                        if (updated !== msg) child.update(0, updated)
                    }
                }
            }
        }
    }

    private fun sendMessage() {
        val text = inputField.text?.toString()?.trim() ?: return
        if (text.isBlank()) return
        chatController.sendMessage(channelId, clanId, channelType, text)
        inputField.text?.clear()
    }
}
