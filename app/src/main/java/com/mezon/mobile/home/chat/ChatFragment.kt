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

    private data class SavedScrollState(
        val messageId: Long,
        val offset: Int,
        val atBottom: Boolean,
        val timestamp: Long
    )

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_MESSAGE_ID = "message_id"
        private const val ARG_FORCE_LATEST = "force_latest"
        private const val ARG_FROM_DM_NOTIFICATION = "fromDmNotification"
        private const val VIEWPORT_LIMIT = 100
        private const val PAGE_DOWN_SCROLL_THRESHOLD = 15
        private const val SCROLL_STATE_TTL_MS = 5 * 60 * 1000L
        // Unique ID cho RecyclerView lưới emoji trong full picker
        internal const val EMOJI_GRID_VIEW_ID = 0x1F600

        private val scrollStates = HashMap<Long, SavedScrollState>()

        fun newInstance(
            channelId: Long,
            channelName: String,
            clanId: Long = 0L,
            channelType: Int = 0,
            messageId: Long = 0L,
            forceLatest: Boolean = false,
            fromDmNotification: Boolean = false
        ): ChatFragment = ChatFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putString(ARG_CHANNEL_NAME, channelName)
                putLong(ARG_CLAN_ID, clanId)
                putInt(ARG_CHANNEL_TYPE, channelType)
                if (messageId != 0L) putLong(ARG_MESSAGE_ID, messageId)
                if (forceLatest) putBoolean(ARG_FORCE_LATEST, true)
                if (fromDmNotification) putBoolean(ARG_FROM_DM_NOTIFICATION, true)
            }
        }
    }

    private lateinit var chatController: ChatController
    private lateinit var dialogsController: DialogsController
    private lateinit var channelController: ChannelController
    private lateinit var emojiRepository: EmojiRepository

    private var myUserId: String = ""  // populated from session in onFragmentCreate

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
    private var forceLatest = false
    private var startLoadFromMessageId = 0L
    private var startLoadFromMessageOffset = Int.MAX_VALUE
    private var loadingFromOldPosition = false
    private var pausedOnLastMessage = false
    private var needScrollRestore = false
    private var isLoading = false
    private var isLoadingMore = false
    private var loadMoreDirection = 0
    private var hasMoreTop = false
    private var hasMoreBottom = false
    private var isViewingOlder = false
    private var firstLoad = true
    private var newUnreadCount = 0
    private var lastSeenMessageId = 0L
    private var lastSentMessageId = 0L
    private var fromDmNotification = false

    private val messages = ArrayList<MessageEntity>()
    private val messagesDict = LongSparseArray<MessageEntity>()

    fun getChannelId(): Long = channelId

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME) ?: ""
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: 0
        forceLatest = arguments?.getBoolean(ARG_FORCE_LATEST) ?: false
        fromDmNotification = arguments?.getBoolean(ARG_FROM_DM_NOTIFICATION) ?: false
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

        if (startLoadFromMessageId == 0L && !forceLatest) {
            val saved = scrollStates[channelId]
            if (saved != null && System.currentTimeMillis() - saved.timestamp < SCROLL_STATE_TTL_MS) {
                if (saved.atBottom) {
                    pausedOnLastMessage = true
                } else if (saved.messageId != 0L) {
                    loadingFromOldPosition = true
                    needScrollRestore = true
                    startLoadFromMessageOffset = saved.offset
                    startLoadFromMessageId = saved.messageId
                }
            } else if (saved != null) {
                scrollStates.remove(channelId)
            }
        }
        if (hasUnread && startLoadFromMessageId == 0L && !pausedOnLastMessage && !forceLatest) {
            needScrollRestore = true
        }

        observe(NotificationCenter.messagesDidLoad) { _, _, args ->
            if (args.size < 5 || args[0] != channelId) return@observe
            @Suppress("UNCHECKED_CAST")
            val loadedMessages = args[1] as? ArrayList<MessageEntity> ?: return@observe
            val moreTop = args[2] as? Boolean ?: false
            val moreBottom = args[3] as? Boolean ?: false
            val isCache = args[4] as? Boolean ?: false

            val wasLoadingMore = isLoadingMore
            val direction = loadMoreDirection

            if (wasLoadingMore && fragmentView != null && messages.isNotEmpty()) {
                var newRowsCount = 0
                val newMessages = ArrayList<MessageEntity>()
                for (m in loadedMessages) {
                    if (messagesDict.get(m.id) == null) {
                        messagesDict.put(m.id, m)
                        newMessages.add(m)
                    }
                }
                newMessages.sortByDescending { it.timestampSeconds }
                newRowsCount = newMessages.size

                if (direction == 1) {
                    messages.addAll(newMessages)
                    hasMoreTop = moreTop
                } else {
                    messages.addAll(0, newMessages)
                    hasMoreBottom = if (lastSentMessageId != 0L && messages.isNotEmpty()) {
                        messages.first().id < lastSentMessageId
                    } else {
                        moreBottom
                    }
                    if (!hasMoreBottom) {
                        isViewingOlder = false
                        clearSavedScrollPosition()
                    }
                }

                if (newRowsCount > 0) {
                    var scrollToMessageId = 0L
                    var top = 0
                    for (i in 0 until recyclerView.childCount) {
                        val v = recyclerView.getChildAt(i)
                        val msgId = when (v) {
                            is ChatMessageCell -> v.messageEntity?.id
                            is SystemMessageCell -> v.messageEntity?.id
                            else -> null
                        } ?: continue
                        scrollToMessageId = msgId
                        top = getScrollingOffsetForView(v)
                        break
                    }

                    if (direction == 1) {
                        val insertAt = adapter.messagesEndRow
                        val oldLoadingUpRow = adapter.loadingUpRow
                        adapter.showLoadingUp = hasMoreTop
                        adapter.updateRowsInternal()
                        if (oldLoadingUpRow >= 0 && adapter.loadingUpRow < 0) {
                            adapter.notifyItemRemoved(oldLoadingUpRow)
                        }
                        adapter.notifyItemRangeInserted(insertAt, newRowsCount)
                    } else {
                        adapter.showLoadingDown = hasMoreBottom
                        adapter.notifyItemRangeInserted(1, newRowsCount)
                        adapter.updateRowsSafe()
                    }

                    if (scrollToMessageId != 0L) {
                        val scrollToIndex = messages.indexOfFirst { it.id == scrollToMessageId }
                        if (scrollToIndex >= 0) {
                            val lm = recyclerView.layoutManager as? LinearLayoutManager
                            lm?.scrollToPositionWithOffset(adapter.messagesStartRow + scrollToIndex, top)
                        }
                    }
                } else {
                    if (direction == 1) adapter.showLoadingUp = hasMoreTop
                    else adapter.showLoadingDown = hasMoreBottom
                    adapter.updateRowsSafe()
                }

                isLoadingMore = false
                loadMoreDirection = 0
                return@observe
            }

            isLoading = false
            isLoadingMore = false
            loadMoreDirection = 0

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
                    for (m in loadedMessages) {
                        if (messagesDict.get(m.id) == null) {
                            messagesDict.put(m.id, m)
                        }
                    }
                    messages.clear()
                    val all = ArrayList<MessageEntity>(messagesDict.size())
                    for (i in 0 until messagesDict.size()) all.add(messagesDict.valueAt(i))
                    all.sortByDescending { it.timestampSeconds }
                    messages.addAll(all)
                    hasMoreTop = moreTop
                    hasMoreBottom = moreBottom
                }
            }

            if (lastSentMessageId != 0L && messages.isNotEmpty()) {
                val newestInList = messages.first().id
                if (newestInList < lastSentMessageId) {
                    hasMoreBottom = true
                }
            }

            if (fragmentView != null) {
                val wasFirstLoad = firstLoad
                firstLoad = false
                refreshUI()
                if (forceLatest && wasFirstLoad) {
                    forceScrollToBottom()
                    markAsRead()
                } else if (startLoadFromMessageId != 0L) {
                    scrollToMessageWithOffset(startLoadFromMessageId, startLoadFromMessageOffset)
                    if (loadingFromOldPosition) {
                        val newestInList = messages.firstOrNull()?.id ?: 0L
                        val moreBelow = lastSentMessageId != 0L && newestInList < lastSentMessageId
                        if (moreBelow) {
                            isViewingOlder = true
                            hasMoreBottom = true
                            updatePageDownVisibility()
                        }
                    }
                    startLoadFromMessageId = 0L
                    startLoadFromMessageOffset = Int.MAX_VALUE
                    loadingFromOldPosition = false
                } else if (wasFirstLoad && lastSeenMessageId != 0L && hasUnread) {
                    scrollToMessageId(lastSeenMessageId)
                    val newestInList = messages.firstOrNull()?.id ?: 0L
                    if (lastSentMessageId != 0L && newestInList < lastSentMessageId) {
                        isViewingOlder = true
                        hasMoreBottom = true
                    }
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
            if (entity.id > lastSentMessageId) lastSentMessageId = entity.id
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

        // ─ Reaction events ────────────────────────────────
        observe(NotificationCenter.messageReactionsDidUpdate) { _, _, args ->
            if (fragmentView == null) return@observe
            val chId = args.getOrNull(0) as? Long ?: return@observe
            if (chId != channelId) return@observe
            val msgId = args.getOrNull(1) as? Long ?: return@observe
            val reactions = args.getOrNull(2) as? com.mezon.mobile.home.chat.MessageReactions ?: return@observe
            val chips = reactions.toChips(myUserId)
            // Update any visible cell that matches
            for (i in 0 until recyclerView.childCount) {
                val cell = recyclerView.getChildAt(i) as? ChatMessageCell ?: continue
                if (cell.messageEntity?.id == msgId) {
                    cell.setReactions(chips)
                    break
                }
            }
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

        if (forceLatest) {
            chatController.loadMessages(channelId, clanId)
        } else if (startLoadFromMessageId != 0L) {
            chatController.loadMessagesAround(channelId, clanId, startLoadFromMessageId)
        } else if (hasUnread && !pausedOnLastMessage) {
            chatController.loadMessagesAround(channelId, clanId, lastSeenMessageId)
        } else {
            chatController.loadMessages(channelId, clanId)
        }
        // Sync recent emojis từ server (giống RN: fetchEmojiRecent khi vào channel)
        // Chạy background, không block UI
        chatController.fetchEmojiRecent()
        return true
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        chatController = entryPoint.chatController()
        dialogsController = entryPoint.dialogsController()
        channelController = entryPoint.channelController()
        emojiRepository = entryPoint.emojiRepository()
        // Populate myUserId once session is ready (cachedUserId in ChatController)
        myUserId = chatController.getCurrentUserId().toString()
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
            itemAnimator = null
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

        adapter = ChatAdapter(themeColors, messages, channelName, cellDelegate = object : ChatMessageCell.ChatMessageCellDelegate {
            override fun didClickMedia(cell: ChatMessageCell, msg: MessageEntity, attachmentIndex: Int) {
                val allMedia = msg.allImageAttachments
                val att = allMedia.getOrNull(attachmentIndex) ?: allMedia.firstOrNull() ?: return
                val url = att.url
                if (url.isEmpty()) return

                val isVideo = att.filetype.startsWith("video/")
                val isGif = att.filetype.contains("gif", true) || url.contains("tenor.com", true)
                val thumbBmp = cell.getMediaBitmap(attachmentIndex)

                when {
                    isVideo -> VideoPlayerDialog(context).play(url)
                    isGif -> PhotoViewer(context).show(url, animated = true, thumbBitmap = thumbBmp)
                    else -> {
                        val gallery = allMedia.filter { !it.filetype.startsWith("video/") }.map { it.url }
                        val idx = gallery.indexOf(url).coerceAtLeast(0)
                        PhotoViewer(context).show(url, gallery = gallery, index = idx, thumbBitmap = thumbBmp)
                    }
                }
            }
            override fun onMessageLongPressed(cell: ChatMessageCell, msg: MessageEntity) {
                showMessageOptions(context, msg)
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

            override fun onReactionChipClicked(
                cell: ChatMessageCell, msg: MessageEntity,
                emojiId: String, shortname: String, isMine: Boolean
            ) {
                // Tap luôn là THÊM reaction
                if (myUserId.isEmpty()) myUserId = chatController.getCurrentUserId().toString()
                chatController.applyOptimisticReaction(
                    channelId    = channelId,
                    messageId    = msg.id,
                    emojiId      = emojiId,
                    shortname    = shortname,
                    myUserId     = myUserId,
                    actionDelete = false,
                    count        = 1
                )
                chatController.reactToMessage(
                    channelId       = channelId,
                    clanId          = clanId,
                    channelType     = channelType,
                    messageId       = msg.id,
                    emojiId         = emojiId,
                    emojiShortname  = shortname,
                    messageSenderId = msg.senderId,
                    senderName      = msg.senderName,
                    actionDelete    = false,
                    countToRemove   = 1
                )
            }

            override fun onReactionChipLongPressed(
                cell: ChatMessageCell, msg: MessageEntity,
                emojiId: String, shortname: String
            ) {
                // Long press → hiện bottom sheet reaction detail
                showReactionDetailSheet(context, msg, emojiId, shortname)
            }
        })
        adapter.channelType = channelType
        adapter.clanId = clanId
        adapter.isChannelPrivate = resolveChannelPrivate()
        adapter.onBindReactions = { cell, msg ->
            // Restore cached reactions when a cell is (re-)bound
            if (myUserId.isEmpty()) myUserId = chatController.getCurrentUserId().toString()
            val reactions = chatController.getReactions(msg.id)
            val chips = reactions?.toChips(myUserId) ?: emptyList()
            cell.setReactions(chips)
        }
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING, RecyclerView.SCROLL_STATE_SETTLING -> {
                        for (i in 0 until rv.childCount) {
                            (rv.getChildAt(i) as? ChatMessageCell)?.stopHeavyOperations()
                        }
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        for (i in 0 until rv.childCount) {
                            val child = rv.getChildAt(i) as? ChatMessageCell ?: continue
                            child.startHeavyOperations()
                            updateCellVisibility(rv, child)
                        }
                    }
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val firstVisible = lm.findFirstVisibleItemPosition()
                val wasViewingOlder = isViewingOlder
                isViewingOlder = firstVisible > PAGE_DOWN_SCROLL_THRESHOLD

                if (isViewingOlder != wasViewingOlder) {
                    updatePageDownVisibility()
                    if (!isViewingOlder && !hasMoreBottom) markAsRead()
                }

                if (!isLoadingMore && hasMoreTop && messages.size >= 10) {
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val totalCount = adapter.itemCount
                    if (totalCount > 0 && lastVisible >= totalCount - 5) {
                        val oldest = messages.lastOrNull()?.id ?: return
                        isLoadingMore = true
                        loadMoreDirection = 1
                        chatController.loadMoreTop(channelId, clanId, oldest)
                    }
                }

                if (!isLoadingMore && hasMoreBottom && messages.size >= 10) {
                    if (firstVisible <= 3) {
                        val newest = messages.firstOrNull()?.id ?: return
                        isLoadingMore = true
                        loadMoreDirection = 2
                        chatController.loadMoreBottom(channelId, clanId, newest)
                    }
                }
            }
        })

        return rootView
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        pausedOnLastMessage = false
        dialogsController.setCurrentChannel(channelId)
        if (clanId != 0L) {
            channelController.setCurrentChannel(channelId)
            channelController.markChannelAsRead(channelId)
        }
        if (messages.isNotEmpty()) {
            showMessages()
            if (!isViewingOlder) markAsRead()
        } else if (!isLoading) {
            isLoading = true
            showLoading()
            chatController.loadMessages(channelId, clanId)
        } else {
            showLoading()
        }
    }

    override fun onPause() {
        super.onPause()
        saveScrollPosition()
    }

    private fun saveScrollPosition() {
        if (pausedOnLastMessage || firstLoad || !::recyclerView.isInitialized) return

        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        var messageId = 0L
        var offset = 0
        val position = lm.findFirstVisibleItemPosition()

        if (position != 0) {
            val holder = recyclerView.findViewHolderForAdapterPosition(position)
            if (holder != null) {
                val msgId = when (val v = holder.itemView) {
                    is ChatMessageCell -> v.messageEntity?.id
                    is SystemMessageCell -> v.messageEntity?.id
                    is WelcomeMessageCell -> null
                    else -> null
                }
                if (msgId != null && msgId != 0L) {
                    messageId = msgId
                    offset = holder.itemView.bottom - recyclerView.measuredHeight
                }
            }
        }

        val now = System.currentTimeMillis()
        if (messageId != 0L) {
            scrollStates[channelId] = SavedScrollState(messageId, offset, atBottom = false, timestamp = now)
        } else {
            pausedOnLastMessage = true
            scrollStates[channelId] = SavedScrollState(0L, 0, atBottom = true, timestamp = now)
        }
    }

    override fun onFragmentDestroy() {
        if (fromDmNotification) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToMessagesTab)
        }
        dialogsController.clearCurrentChannel()
        if (clanId != 0L) channelController.clearCurrentChannel()
        messages.clear()
        messagesDict.clear()
        super.onFragmentDestroy()
    }

    private fun refreshUI() {
        if (messages.isNotEmpty()) showMessages()
        else if (isLoading) showLoading()
        else showEmpty()
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
        errorView.visibility = View.GONE
        recyclerView.visibility = if (needScrollRestore) View.INVISIBLE else View.VISIBLE
        adapter.showLoadingUp = hasMoreTop
        adapter.showLoadingDown = hasMoreBottom
        adapter.notifyMessagesUpdated()
    }

    private fun showEmpty() {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        adapter.notifyMessagesUpdated()
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

    private fun scrollToMessageWithOffset(messageId: Long, pixelOffset: Int) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val yOffset = if (pixelOffset != Int.MAX_VALUE) {
                -pixelOffset - recyclerView.paddingBottom
            } else {
                recyclerView.height / 3
            }
            lm.scrollToPositionWithOffset(adapter.messagesStartRow + idx, yOffset)
            recyclerView.post {
                recyclerView.visibility = View.VISIBLE
                needScrollRestore = false
            }
        } else {
            recyclerView.visibility = View.VISIBLE
            needScrollRestore = false
        }
    }

    private fun scrollToMessageId(messageId: Long) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
            lm.scrollToPositionWithOffset(adapter.messagesStartRow + idx, recyclerView.height / 3)
            if (needScrollRestore) {
                recyclerView.post {
                    recyclerView.visibility = View.VISIBLE
                    needScrollRestore = false
                }
            }
        } else {
            if (needScrollRestore) {
                recyclerView.visibility = View.VISIBLE
                needScrollRestore = false
            }
        }
    }

    private fun jumpToPresent() {
        newUnreadCount = 0
        isViewingOlder = false
        hasMoreBottom = false
        pausedOnLastMessage = true
        pageDownButton.show(false)
        pageDownButton.setUnreadCount(0)

        clearSavedScrollPosition()

        messages.clear()
        messagesDict.clear()
        firstLoad = true
        chatController.loadMessages(channelId, clanId)
        markAsRead()
    }

    private fun clearSavedScrollPosition() {
        scrollStates[channelId] = SavedScrollState(0L, 0, atBottom = true, timestamp = System.currentTimeMillis())
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

    private fun getScrollingOffsetForView(v: android.view.View): Int {
        return recyclerView.measuredHeight - v.bottom - recyclerView.paddingBottom
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

    private fun updateCellVisibility(rv: RecyclerView, cell: ChatMessageCell) {
        val rvTop = rv.paddingTop
        val rvBottom = rv.height - rv.paddingBottom
        val cellTop = cell.top
        val cellBottom = cell.bottom
        if (cellBottom <= rvTop || cellTop >= rvBottom) {
            cell.setVisibleOnScreen(false)
        } else {
            val clipTop = (rvTop - cellTop).coerceAtLeast(0).toFloat()
            val clipBottom = (cellBottom - rvBottom).coerceAtLeast(0).toFloat()
            cell.setVisibleOnScreen(true, clipTop, clipBottom)
        }
    }

    private fun resolveChannelPrivate(): Boolean {
        if (clanId != 0L) {
            return channelController.findChannelById(channelId)?.isPrivate ?: false
        }
        return false
    }

    private fun sendMessage() {
        val text = inputField.text?.toString()?.trim() ?: return
        if (text.isBlank()) return
        chatController.sendMessage(channelId, clanId, channelType, resolveChannelPrivate(), text)
        inputField.text?.clear()
    }

    private fun showMessageOptions(context: Context, msg: MessageEntity) {
        var bottomSheetDialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null

        bottomSheetDialog = com.mezon.mobile.ui.cells.MezonBottomSheetDialog.create(context, themeColors) { container ->

            // ─── 1. Quick Reaction Row ──────────────────────────────────────
            val emojiRow = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(
                    com.mezon.mobile.core.LayoutHelper.dp(4f),
                    com.mezon.mobile.core.LayoutHelper.dp(4f),
                    com.mezon.mobile.core.LayoutHelper.dp(4f),
                    com.mezon.mobile.core.LayoutHelper.dp(16f)
                )
            }

            val emojiScroll = android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(emojiRow, android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }

            // Spinner hiển thị trong khi fetch emoji
            val loadingSpinner = android.widget.ProgressBar(context).apply {
                setPadding(0, com.mezon.mobile.core.LayoutHelper.dp(4f), 0, com.mezon.mobile.core.LayoutHelper.dp(16f))
            }

            // Ban đầu hiện spinner, ẩn emojiRow
            container.addView(loadingSpinner, com.mezon.mobile.core.LayoutHelper.createLinear(36, 36, leftMargin = 12f))
            container.addView(emojiScroll, com.mezon.mobile.core.LayoutHelper.createLinear(
                com.mezon.mobile.core.LayoutHelper.MATCH_PARENT,
                com.mezon.mobile.core.LayoutHelper.WRAP_CONTENT
            ))
            emojiScroll.visibility = android.view.View.GONE



            // ─── Helper functions khai báo trước khi dùng ──────────────────
            // Build emoji cell với src URL ưu tiên từ BE
            fun buildEmojiCell(emoji: com.mezon.mobile.home.chat.IEmoji): android.widget.ImageView {
                val imgUrl = emoji.src?.takeIf { it.isNotBlank() } ?: getSrcEmoji(emoji.id)
                val cell = android.widget.ImageView(context).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(themeColors.surfaceVariant)
                    }
                    clipToOutline = true
                    setOnClickListener {
                        if (myUserId.isEmpty()) myUserId = chatController.getCurrentUserId().toString()
                        chatController.applyOptimisticReaction(
                            channelId    = channelId,
                            messageId    = msg.id,
                            emojiId      = emoji.id,
                            shortname    = emoji.shortname ?: emoji.id,
                            myUserId     = myUserId,
                            actionDelete = false,
                            emojiSrc     = imgUrl
                        )
                        chatController.reactToMessage(
                            channelId       = channelId,
                            clanId          = clanId,
                            channelType     = channelType,
                            messageId       = msg.id,
                            emojiId         = emoji.id,
                            emojiShortname  = emoji.shortname ?: "",
                            messageSenderId = msg.senderId,
                            senderName      = msg.senderName,
                            actionDelete    = false
                        )
                        bottomSheetDialog?.dismiss()
                    }
                }
                MezonImageLoader.getInstance(context).load(
                    url       = imgUrl,
                    reqWidth  = com.mezon.mobile.core.LayoutHelper.dp(36f),
                    reqHeight = com.mezon.mobile.core.LayoutHelper.dp(36f),
                    onSuccess = { bmp -> cell.setImageBitmap(bmp) },
                    onError   = {}
                )
                return cell
            }

            // Nút mở full emoji picker (icon mặt cười)
            fun buildMoreBtn(): android.widget.ImageView {
                return android.widget.ImageView(context).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding(
                        com.mezon.mobile.core.LayoutHelper.dp(6f),
                        com.mezon.mobile.core.LayoutHelper.dp(6f),
                        com.mezon.mobile.core.LayoutHelper.dp(6f),
                        com.mezon.mobile.core.LayoutHelper.dp(6f)
                    )
                    setImageResource(com.mezon.mobile.R.drawable.ic_emoji_icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(themeColors.onSurfaceVariant)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(themeColors.surfaceVariant)
                    }
                    setOnClickListener {
                        bottomSheetDialog?.dismiss()
                        showFullEmojiPicker(context, msg)
                    }
                }
            }

            fun populateEmojiRow(apiFallback: List<com.mezon.mobile.home.chat.IEmoji> = emptyList()) {
                emojiRow.removeAllViews()

                // 1) Recent emojis từ SharedPreferences
                val recentList = emojiRepository.getRecentEmojis()
                val recentEmojis = recentList.map { recent ->
                    val fromApi = apiFallback.firstOrNull { it.id == recent.id }
                    fromApi ?: com.mezon.mobile.home.chat.IEmoji(
                        id = recent.id,
                        shortname = recent.shortname,
                        src = "https://cdn.mezon.vn/emojis/${recent.id}.webp"
                    )
                }
                val alreadyInRecent = recentEmojis.map { it.id }.toSet()
                val padFromBE = apiFallback.filter { it.id !in alreadyInRecent }
                val quickList = (recentEmojis + padFromBE).take(5)

                for (emoji in quickList) {
                    emojiRow.addView(buildEmojiCell(emoji),
                        com.mezon.mobile.core.LayoutHelper.createLinear(36, 36, rightMargin = 8f))
                }
                emojiRow.addView(buildMoreBtn(),
                    com.mezon.mobile.core.LayoutHelper.createLinear(36, 36, leftMargin = 4f))
                loadingSpinner.visibility = android.view.View.GONE
                emojiScroll.visibility   = android.view.View.VISIBLE
            }

            val cachedEmojis = emojiRepository.getCachedEmojis()
            if (cachedEmojis != null && cachedEmojis.isNotEmpty()) {
                populateEmojiRow(cachedEmojis)
            } else {
                if (emojiRepository.getRecentEmojis().isNotEmpty()) {
                    populateEmojiRow()
                }
                chatController.fetchEmojis { emojiList ->
                    if (emojiList.isNotEmpty()) {
                        populateEmojiRow(emojiList)
                    }
                }
            }


            // ─── 2. Helper: tạo khối action bo góc ─────────────────────────
            fun createBlock(items: List<Pair<String, com.mezon.mobile.ui.cells.MezonIcon>>): android.widget.LinearLayout {
                val block = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = com.mezon.mobile.core.LayoutHelper.dpf(12f)
                        setColor(themeColors.surfaceVariant)
                    }
                }
                items.forEachIndexed { index, pair ->
                    val cell = com.mezon.mobile.ui.cells.TextSettingsCell(context, themeColors).apply {
                        setTextAndIcon(pair.first, pair.second.resId, divider = index != items.size - 1)
                        setOnClickListener {
                            android.widget.Toast.makeText(context, pair.first, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    block.addView(
                        cell,
                        com.mezon.mobile.core.LayoutHelper.createLinear(
                            com.mezon.mobile.core.LayoutHelper.MATCH_PARENT,
                            com.mezon.mobile.core.LayoutHelper.WRAP_CONTENT
                        )
                    )
                }
                return block
            }

            // ─── 3. Block 1: Quà, Trả lời, Chuyển tiếp ─────────────────────
            val block1 = createBlock(listOf(
                "Mời một ly cà phê" to com.mezon.mobile.ui.cells.MezonIcon.giftIcon,
                "Trả lời" to com.mezon.mobile.ui.cells.MezonIcon.replyMsg,
                "Chuyển tiếp tin nhắn" to com.mezon.mobile.ui.cells.MezonIcon.forwardAllIcon
            ))
            container.addView(
                block1,
                com.mezon.mobile.core.LayoutHelper.createLinear(
                    com.mezon.mobile.core.LayoutHelper.MATCH_PARENT,
                    com.mezon.mobile.core.LayoutHelper.WRAP_CONTENT,
                    bottomMargin = 12f
                )
            )

            // ─── 4. Block 2: Sao chép, Đánh dấu, Thảo luận, Ghim ───────────
            val block2 = createBlock(listOf(
                "Sao chép văn bản" to com.mezon.mobile.ui.cells.MezonIcon.copyIcon,
                "Đánh dấu chưa đọc" to com.mezon.mobile.ui.cells.MezonIcon.markUnreadIcon,
                "Thảo luận chủ đề" to com.mezon.mobile.ui.cells.MezonIcon.threadIcon,
                "Ghim tin nhắn" to com.mezon.mobile.ui.cells.MezonIcon.pinIcon
            ))
            container.addView(
                block2,
                com.mezon.mobile.core.LayoutHelper.createLinear(
                    com.mezon.mobile.core.LayoutHelper.MATCH_PARENT,
                    com.mezon.mobile.core.LayoutHelper.WRAP_CONTENT
                )
            )
        }
        bottomSheetDialog.show()
    }

    /**
     * Bottom sheet hiện khi long-press vào 1 reaction chip.
     * Layout:
     *   [Tab row: chip emoji cho từng loại trên msg đó]
     *   [Divider]
     *   [List: avatar + tên người react + nút 🗑 nếu là mình]
     */
    private fun showReactionDetailSheet(
        context: Context,
        msg: MessageEntity,
        initialEmojiId: String,
        initialShortname: String
    ) {
        if (myUserId.isEmpty()) myUserId = chatController.getCurrentUserId().toString()
        val reactions = chatController.getReactions(msg.id) ?: return
        val allChips = reactions.toChips(myUserId)
        if (allChips.isEmpty()) return

        // Track which tab is active
        var activeEmojiId = initialEmojiId

        // Build dialog
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(themeColors.surface)
            val pad = com.mezon.mobile.core.LayoutHelper.dp(16f)
            setPadding(pad, com.mezon.mobile.core.LayoutHelper.dp(12f), pad, pad)
        }

        // ── Tab row: emoji chips (horizontal scroll) ──────────────────────
        val tabRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val tabScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(tabScroll, com.mezon.mobile.core.LayoutHelper.createLinear(
            com.mezon.mobile.core.LayoutHelper.MATCH_PARENT,
            com.mezon.mobile.core.LayoutHelper.WRAP_CONTENT,
            bottomMargin = 12f
        ))

        // ── Sender list ────────────────────────────────────────────────────
        val senderList = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val scrollView = android.widget.ScrollView(context).apply {
            addView(senderList, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scrollView, com.mezon.mobile.core.LayoutHelper.createLinear(
            com.mezon.mobile.core.LayoutHelper.MATCH_PARENT,
            com.mezon.mobile.core.LayoutHelper.WRAP_CONTENT
        ))

        // Function to rebuild sender list for a given emojiId
        fun rebuildSenders(emojiId: String, shortname: String) {
            senderList.removeAllViews()
            val senders = reactions.getSenders(emojiId)
            for (sender in senders) {
                val row = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val padV = com.mezon.mobile.core.LayoutHelper.dp(10f)
                    setPadding(0, padV, 0, padV)
                }

                // Avatar (circle placeholder for now)
                val avatar = android.widget.ImageView(context).apply {
                    val sz = com.mezon.mobile.core.LayoutHelper.dp(36f)
                    layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(themeColors.surfaceVariant)
                    }
                    clipToOutline = true
                }
                row.addView(avatar)

                // Name + count
                val nameCol = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val lp = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    lp.marginStart = com.mezon.mobile.core.LayoutHelper.dp(12f)
                    layoutParams = lp
                }
                val nameTv = android.widget.TextView(context).apply {
                    text = sender.displayName
                    setTextColor(themeColors.onSurface)
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val countTv = android.widget.TextView(context).apply {
                    text = "x${sender.count}"
                    setTextColor(themeColors.onSurfaceVariant)
                    textSize = 12f
                }
                nameCol.addView(nameTv)
                nameCol.addView(countTv)
                row.addView(nameCol)

                // Trash icon — only for current user
                if (sender.senderId == myUserId) {
                    val trashBtn = android.widget.ImageView(context).apply {
                        val sz = com.mezon.mobile.core.LayoutHelper.dp(36f)
                        layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz)
                        setImageResource(com.mezon.mobile.R.drawable.ic_delete)
                        imageTintList = android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = com.mezon.mobile.core.LayoutHelper.dpf(8f)
                            setColor(0x20EF4444.toInt())
                        }
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        val pad = com.mezon.mobile.core.LayoutHelper.dp(8f)
                        setPadding(pad, pad, pad, pad)
                        setOnClickListener {
                            // Xóa reaction của mình cho emoji này
                            chatController.applyOptimisticReaction(
                                channelId    = channelId,
                                messageId    = msg.id,
                                emojiId      = emojiId,
                                shortname    = shortname,
                                myUserId     = myUserId,
                                actionDelete = true,
                                count        = sender.count.coerceAtLeast(1)
                            )
                            chatController.reactToMessage(
                                channelId       = channelId,
                                clanId          = clanId,
                                channelType     = channelType,
                                messageId       = msg.id,
                                emojiId         = emojiId,
                                emojiShortname  = shortname,
                                messageSenderId = msg.senderId,
                                senderName      = msg.senderName,
                                actionDelete    = true,
                                countToRemove   = sender.count.coerceAtLeast(1)
                            )
                            dialog.dismiss()
                        }
                    }
                    row.addView(trashBtn)
                }

                senderList.addView(row, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        // Build tab buttons
        val tabViews = mutableMapOf<String, android.view.View>()
        fun updateTabActive(emojiId: String) {
            tabViews.forEach { (id, v) ->
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = com.mezon.mobile.core.LayoutHelper.dpf(20f)
                    if (id == emojiId) {
                        setColor(0xFF4E5057.toInt())
                        setStroke(com.mezon.mobile.core.LayoutHelper.dp(2f), 0xFF8B8FF0.toInt())
                    } else {
                        setColor(themeColors.surfaceVariant)
                    }
                }
            }
        }

        for (chip in allChips) {
            val tabChip = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val padH = com.mezon.mobile.core.LayoutHelper.dp(10f)
                val padV = com.mezon.mobile.core.LayoutHelper.dp(6f)
                setPadding(padH, padV, padH, padV)
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = com.mezon.mobile.core.LayoutHelper.dp(8f)
                layoutParams = lp
            }

            val emojiImg = android.widget.ImageView(context).apply {
                val sz = com.mezon.mobile.core.LayoutHelper.dp(22f)
                layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            MezonImageLoader.getInstance(context).load(
                url = chip.emojiSrc.ifBlank { getSrcEmoji(chip.emojiId) },
                reqWidth = com.mezon.mobile.core.LayoutHelper.dp(22f),
                reqHeight = com.mezon.mobile.core.LayoutHelper.dp(22f),
                onSuccess = { bmp -> emojiImg.setImageBitmap(bmp) }
            )
            tabChip.addView(emojiImg)

            val cntTv = android.widget.TextView(context).apply {
                text = " ${chip.count}"
                setTextColor(themeColors.onSurface)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            tabChip.addView(cntTv)

            tabViews[chip.emojiId] = tabChip
            tabRow.addView(tabChip)

            tabChip.setOnClickListener {
                activeEmojiId = chip.emojiId
                updateTabActive(chip.emojiId)
                rebuildSenders(chip.emojiId, chip.shortname)
            }
        }

        // Init active tab
        updateTabActive(activeEmojiId)
        rebuildSenders(activeEmojiId, initialShortname)

        dialog.setContentView(root)
        dialog.show()
    }

    /**
     * Mở Full Emoji Picker bottom sheet.
     * Fetch toàn bộ danh sách emoji từ API, hiển thị dạng grid 9 cột.
     */
    private fun showFullEmojiPicker(context: Context, msg: MessageEntity) {
        val pickerDialog = android.app.Dialog(context, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_BottomSheetDialog)
        pickerDialog.setContentView(buildEmojiPickerView(context))
        pickerDialog.window?.apply {
            val screenHeight = context.resources.displayMetrics.heightPixels
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (screenHeight * 0.80).toInt())
            setGravity(android.view.Gravity.BOTTOM)
        }
        pickerDialog.show()

        var allEmojis: List<com.mezon.mobile.home.chat.IEmoji> = emptyList()

        // Helper: populate grid và ẩn spinner
        fun populateGrid(emojis: List<com.mezon.mobile.home.chat.IEmoji>, query: String = "") {
            if (!pickerDialog.isShowing) return
            val gridView = pickerDialog.findViewById<androidx.recyclerview.widget.RecyclerView>(EMOJI_GRID_VIEW_ID)
                ?: return
            val rootLayout = gridView.parent as? android.view.ViewGroup
            for (i in 0 until (rootLayout?.childCount ?: 0)) {
                if (rootLayout?.getChildAt(i) is android.widget.ProgressBar) {
                    rootLayout.getChildAt(i).visibility = android.view.View.GONE
                    break
                }
            }
            gridView.visibility = android.view.View.VISIBLE
            gridView.adapter = buildEmojiGridAdapter(context, emojis, msg, pickerDialog, query)
        }

        fun wireSearch(emojis: List<com.mezon.mobile.home.chat.IEmoji>) {
            allEmojis = emojis
            val gridView = pickerDialog.findViewById<androidx.recyclerview.widget.RecyclerView>(EMOJI_GRID_VIEW_ID)
            val rootLayout = gridView?.parent as? android.view.ViewGroup ?: return
            val searchField = (0 until rootLayout.childCount)
                .map { rootLayout.getChildAt(it) }
                .filterIsInstance<android.widget.EditText>()
                .firstOrNull() ?: return
            searchField.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    populateGrid(allEmojis, s?.toString() ?: "")
                }
            })
        }

        // Nếu có cache → hiện ngay lập tức
        val cached = emojiRepository.getCachedEmojis()
        if (cached != null && cached.isNotEmpty()) {
            populateGrid(cached)
            wireSearch(cached)
        } else {
            // Không có cache → fetch từ BE
            chatController.fetchEmojis { emojis ->
                if (emojis.isNotEmpty()) {
                    populateGrid(emojis)
                    wireSearch(emojis)
                } else {
                    if (!pickerDialog.isShowing) return@fetchEmojis
                    val gridView = pickerDialog.findViewById<androidx.recyclerview.widget.RecyclerView>(EMOJI_GRID_VIEW_ID)
                    val rootLayout = gridView?.parent as? android.view.ViewGroup
                    if (rootLayout != null) {
                        for (i in 0 until rootLayout.childCount) {
                            val child = rootLayout.getChildAt(i)
                            if (child is android.widget.ProgressBar) {
                                child.visibility = android.view.View.GONE
                                break
                            }
                        }
                    }
                    gridView?.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    // Minimal emoji picker scaffold (search box + grid + spinner)
    private fun buildEmojiPickerView(context: Context): android.view.View {
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = com.mezon.mobile.core.LayoutHelper.dp(12f)
            setPadding(pad, pad, pad, pad)
        }

        val search = android.widget.EditText(context).apply {
            hint = "Search emoji"
            setSingleLine()
        }
        root.addView(search, com.mezon.mobile.core.LayoutHelper.createLinear(
            com.mezon.mobile.core.LayoutHelper.MATCH_PARENT,
            com.mezon.mobile.core.LayoutHelper.WRAP_CONTENT,
            bottomMargin = 8f
        ))

        val spinner = android.widget.ProgressBar(context)
        root.addView(spinner, com.mezon.mobile.core.LayoutHelper.createLinear(
            com.mezon.mobile.core.LayoutHelper.WRAP_CONTENT,
            com.mezon.mobile.core.LayoutHelper.WRAP_CONTENT,
            gravity = android.view.Gravity.CENTER_HORIZONTAL,
            bottomMargin = 8f
        ))

        val grid = androidx.recyclerview.widget.RecyclerView(context).apply {
            id = EMOJI_GRID_VIEW_ID
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 9)
            visibility = android.view.View.GONE
        }
        root.addView(grid, com.mezon.mobile.core.LayoutHelper.createLinear(
            com.mezon.mobile.core.LayoutHelper.MATCH_PARENT,
            0,
            weight = 1f
        ))

        return root
    }

    private fun buildEmojiGridAdapter(
        context: Context,
        emojis: List<com.mezon.mobile.home.chat.IEmoji>,
        msg: MessageEntity,
        pickerDialog: android.app.Dialog,
        query: String = ""
    ): RecyclerView.Adapter<*> {
        val filtered = if (query.isNotBlank()) {
            emojis.filter { it.shortname?.contains(query, true) == true || it.id.contains(query, true) }
        } else emojis

        return object : RecyclerView.Adapter<EmojiViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): EmojiViewHolder {
                val iv = android.widget.ImageView(context).apply {
                    val sz = com.mezon.mobile.core.LayoutHelper.dp(36f)
                    layoutParams = RecyclerView.LayoutParams(sz, sz)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                }
                return EmojiViewHolder(iv)
            }

            override fun getItemCount(): Int = filtered.size

            override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
                val emoji = filtered[position]
                val url = emoji.src?.takeIf { it.isNotBlank() } ?: getSrcEmoji(emoji.id)
                MezonImageLoader.getInstance(context).load(
                    url,
                    com.mezon.mobile.core.LayoutHelper.dp(36f),
                    com.mezon.mobile.core.LayoutHelper.dp(36f),
                    onSuccess = { bmp -> holder.iv.setImageBitmap(bmp) }
                )
                holder.iv.setOnClickListener {
                    if (myUserId.isEmpty()) myUserId = chatController.getCurrentUserId().toString()
                    chatController.applyOptimisticReaction(
                        channelId    = channelId,
                        messageId    = msg.id,
                        emojiId      = emoji.id,
                        shortname    = emoji.shortname ?: emoji.id,
                        myUserId     = myUserId,
                        actionDelete = false,
                        emojiSrc     = url
                    )
                    chatController.reactToMessage(
                        channelId       = channelId,
                        clanId          = clanId,
                        channelType     = channelType,
                        messageId       = msg.id,
                        emojiId         = emoji.id,
                        emojiShortname  = emoji.shortname ?: "",
                        messageSenderId = msg.senderId,
                        senderName      = msg.senderName,
                        actionDelete    = false
                    )
                    pickerDialog.dismiss()
                }
            }
        }
    }

    private class EmojiViewHolder(val iv: android.widget.ImageView) : RecyclerView.ViewHolder(iv)
 }
