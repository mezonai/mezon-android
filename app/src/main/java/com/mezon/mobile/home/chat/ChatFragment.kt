package com.mezon.mobile.home.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.LongSparseArray
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.PageDownButton

private const val TAG = "ChatFragment"

class ChatFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_MESSAGE_ID = "message_id"
        private const val ARG_FORCE_LATEST = "force_latest"
        private const val VIEWPORT_LIMIT = 100
        private const val PAGE_DOWN_SCROLL_THRESHOLD = 15
        private const val SCROLL_PREFS = "chat_scroll_positions"

        fun newInstance(
            channelId: Long,
            channelName: String,
            clanId: Long = 0L,
            channelType: Int = 0,
            messageId: Long = 0L,
            forceLatest: Boolean = false
        ): ChatFragment = ChatFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putString(ARG_CHANNEL_NAME, channelName)
                putLong(ARG_CLAN_ID, clanId)
                putInt(ARG_CHANNEL_TYPE, channelType)
                if (messageId != 0L) putLong(ARG_MESSAGE_ID, messageId)
                if (forceLatest) putBoolean(ARG_FORCE_LATEST, true)
            }
        }
    }

    private lateinit var chatController: ChatController
    private lateinit var dialogsController: DialogsController
    private lateinit var channelController: ChannelController
    private lateinit var mediaController: MediaController

    private lateinit var recyclerView: RecyclerListView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var attachButton: ImageButton
    private lateinit var emojiButton: ImageButton
    private lateinit var adapter: ChatAdapter
    private lateinit var rootView: LinearLayout
    private lateinit var inputBar: LinearLayout
    private lateinit var inputWrapper: FrameLayout
    private lateinit var pageDownButton: PageDownButton
    private lateinit var unreadDecoration: UnreadDividerDecoration
    private var attachmentPreviewStrip: LinearLayout? = null
    private var attachmentPreviewScroll: HorizontalScrollView? = null

    private val pendingAttachments = ArrayList<AttachmentPickerItem>()

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
    private var dividerSeenMessageId = 0L
    private var lastSentMessageId = 0L
    private var hasUnread = false
    private var jumpingToPresent = false

    private var slidingView: ChatMessageCell? = null
    private var maybeStartTrackingSlidingView = false
    private var startedTrackingSlidingView = false
    private var startedTrackingX = 0
    private var startedTrackingY = 0
    private var startedTrackingPointerId = -1

    private val messages = ArrayList<MessageEntity>()
    private val messagesDict = LongSparseArray<MessageEntity>()
    private var transitionAnimationIndex = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSeenMessageId = 0L
    private var pendingSeenTimestamp = 0
    private var pendingBadgeCount = 0
    private val markVisibleRunnable = Runnable { flushPendingSeen() }

    private val postponeNewMessagesCallback = object : NotificationCenter.PostponeNotificationCallback {
        override fun needPostpone(id: Int, currentAccount: Int, args: Array<out Any?>): Boolean {
            if (id == NotificationCenter.didReceiveNewMessages) {
                val did = args.firstOrNull() as? Long ?: return false
                if (firstLoad && did == channelId) return true
            }
            return false
        }
    }

    fun getChannelId(): Long = channelId

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME) ?: ""
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: 0
        forceLatest = arguments?.getBoolean(ARG_FORCE_LATEST) ?: false
        startLoadFromMessageId = arguments?.getLong(ARG_MESSAGE_ID) ?: 0L
        if (startLoadFromMessageId != 0L) {
            needScrollRestore = true
        }

        if (clanId == 0L) {
            val dm = dialogsController.getDialog(channelId)
            lastSeenMessageId = dm?.lastSeenMessageId ?: 0L
            lastSentMessageId = dm?.lastSentMessageId ?: 0L
        } else {
            val ch = channelController.findChannelById(channelId)
            lastSeenMessageId = ch?.lastSeenMessageId ?: 0L
            lastSentMessageId = ch?.lastSentMessageId ?: 0L
        }
        dividerSeenMessageId = lastSeenMessageId
        val isSeenUpToDate = lastSentMessageId == 0L || lastSeenMessageId >= lastSentMessageId
        hasUnread = !isSeenUpToDate && lastSeenMessageId != 0L

        if (startLoadFromMessageId == 0L && !forceLatest) {
            val prefs = getParentActivity()?.getSharedPreferences(SCROLL_PREFS, android.content.Context.MODE_PRIVATE)
            val savedMid = prefs?.getLong("mid_$channelId", 0L) ?: 0L
            val savedOffset = prefs?.getInt("off_$channelId", 0) ?: 0
            val savedAtBottom = prefs?.getBoolean("bot_$channelId", true) ?: true
            if (savedAtBottom) {
                pausedOnLastMessage = true
            } else if (savedMid != 0L) {
                loadingFromOldPosition = true
                needScrollRestore = true
                startLoadFromMessageOffset = savedOffset
                startLoadFromMessageId = savedMid
            }
        }
        if (hasUnread && startLoadFromMessageId == 0L && !forceLatest) {
            needScrollRestore = true
        }

        observe(NotificationCenter.messagesDidLoad) { _, _, args ->
            if (args.size < 5 || args[0] != channelId) return@observe
            @Suppress("UNCHECKED_CAST")
            val loadedMessages = args[1] as? ArrayList<MessageEntity> ?: return@observe
            val moreTop = args[2] as? Boolean ?: false
            val moreBottom = args[3] as? Boolean ?: false
            val isCache = args[4] as? Boolean ?: false
            val serverLastSeenId = args.getOrNull(5) as? Long ?: 0L
            Log.d(TAG, "messagesDidLoad: isCache=$isCache firstLoad=$firstLoad hasUnread=$hasUnread " +
                "loaded=${loadedMessages.size} lastSeen=$lastSeenMessageId lastSent=$lastSentMessageId serverLastSeen=$serverLastSeenId")
            if (serverLastSeenId != 0L) {
                val newSeen = maxOf(lastSeenMessageId, serverLastSeenId)
                if (newSeen != lastSeenMessageId) {
                    Log.d(TAG, "lastSeenMessageId Math.max: $lastSeenMessageId → $newSeen")
                    lastSeenMessageId = newSeen
                    if (firstLoad) dividerSeenMessageId = newSeen
                }
                hasUnread = lastSentMessageId != 0L && lastSeenMessageId < lastSentMessageId && lastSeenMessageId != 0L
            }

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
                newMessages.sortByDescending { it.id }
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
                    if (newRowsCount > 0 && newUnreadCount > 0) {
                        newUnreadCount = (newUnreadCount - newRowsCount).coerceAtLeast(0)
                        pageDownButton.setUnreadCount(newUnreadCount)
                    }
                    updatePageDownVisibility()
                    if (!isViewingOlder && !hasMoreBottom) markAsRead()
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

            if (jumpingToPresent && isCache) {
                if (fragmentView != null) showLoading()
                return@observe
            }

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
                    all.sortByDescending { it.id }
                    messages.addAll(all)
                }
                hasMoreTop = moreTop
                hasMoreBottom = moreBottom
            } else {
                val savedHasUnread = hasUnread
                val savedLastSeen = lastSeenMessageId

                val apiMinId = loadedMessages.minOfOrNull { it.id } ?: 0L
                val apiMaxId = loadedMessages.maxOfOrNull { it.id } ?: 0L
                val existingMinId = messages.minOfOrNull { it.id } ?: 0L
                val existingMaxId = messages.maxOfOrNull { it.id } ?: 0L
                val hasOverlap = messages.isNotEmpty() &&
                    apiMinId <= existingMaxId && apiMaxId >= existingMinId

                if (hasOverlap) {
                    for (m in loadedMessages) messagesDict.put(m.id, m)
                } else {
                    messagesDict.clear()
                    for (m in loadedMessages) messagesDict.put(m.id, m)
                }
                messages.clear()
                val all = ArrayList<MessageEntity>(messagesDict.size())
                for (i in 0 until messagesDict.size()) all.add(messagesDict.valueAt(i))
                all.sortByDescending { it.id }
                messages.addAll(all)
                hasMoreTop = moreTop
                hasMoreBottom = moreBottom

                hasUnread = savedHasUnread
                lastSeenMessageId = savedLastSeen
            }

            // Reconcile lastSentMessageId with the per-channel newest message store
            // (equivalent to RN's state.messages.lastMessageByChannel[channelId]).
            // This is more reliable than the channel list API which can be stale.
            val fromStore = chatController.getLastMessageId(channelId)
            lastSentMessageId = maxOf(lastSentMessageId, fromStore)

            if (messages.isNotEmpty()) {
                val newestInList = messages.first().id
                lastSentMessageId = maxOf(lastSentMessageId, newestInList)
            }

            if (!jumpingToPresent && !isCache) {
                if (!hasUnread && lastSentMessageId != 0L && lastSeenMessageId != 0L
                    && lastSeenMessageId < lastSentMessageId) {
                    hasUnread = true
                    needScrollRestore = true
                    Log.d(TAG, "hasUnread re-evaluated to TRUE: lastSeen=$lastSeenMessageId < lastSent=$lastSentMessageId")
                }

                if (lastSentMessageId != 0L && messages.isNotEmpty()) {
                    val newestInList = messages.first().id
                    if (newestInList < lastSentMessageId) {
                        hasMoreBottom = true
                    }
                }
            }

            if (!hasUnread && lastSeenMessageId != 0L && messages.isNotEmpty()) {
                val newestInList = messages.first().id
                if (newestInList > lastSeenMessageId && messages.any { it.id == lastSeenMessageId }) {
                    hasUnread = true
                    if (!needScrollRestore && startLoadFromMessageId == 0L) needScrollRestore = true
                }
            }

            if (fragmentView != null) {
                val wasFirstLoad = firstLoad
                firstLoad = false

                if (wasFirstLoad) {
                    notificationCenter.removePostponeNotificationsCallback(postponeNewMessagesCallback)
                    val allowedDuringLoad = intArrayOf(
                        NotificationCenter.messagesDidLoad,
                        NotificationCenter.messagesLoadError,
                        NotificationCenter.closeChats
                    )
                    transitionAnimationIndex = notificationCenter.setAnimationInProgress(
                        transitionAnimationIndex, allowedDuringLoad, false
                    )
                    mainHandler.postDelayed({
                        notificationCenter.onAnimationFinish(transitionAnimationIndex)
                    }, 500)
                }

                if (jumpingToPresent) {
                    jumpingToPresent = false
                    showMessages()
                    forceScrollToBottom()
                    markAsRead()
                } else {
                    Log.d(TAG, "messagesDidLoad decision: wasFirstLoad=$wasFirstLoad hasUnread=$hasUnread isCache=$isCache firstLoad=$firstLoad msgs=${messages.size}")

                    var anchorMsgId = 0L
                    var anchorOffset = 0
                    if (!wasFirstLoad && ::recyclerView.isInitialized && recyclerView.childCount > 0) {
                        for (i in 0 until recyclerView.childCount) {
                            val v = recyclerView.getChildAt(i)
                            val msgId = when (v) {
                                is ChatMessageCell -> v.messageEntity?.id
                                is SystemMessageCell -> v.messageEntity?.id
                                else -> null
                            } ?: continue
                            anchorMsgId = msgId
                            anchorOffset = getScrollingOffsetForView(v)
                            break
                        }
                    }

                    refreshUI()
                    if (forceLatest && wasFirstLoad) {
                        forceScrollToBottom()
                        markAsRead()
                    } else if (startLoadFromMessageId != 0L) {
                        val idx = messages.indexOfFirst { it.id == startLoadFromMessageId }
                        if (idx >= 0 || !isCache) {
                        scrollToMessageWithOffset(startLoadFromMessageId, startLoadFromMessageOffset)
                        if (loadingFromOldPosition) {
                            val newestInList = messages.firstOrNull()?.id ?: 0L
                            val moreBelow = lastSentMessageId != 0L && newestInList < lastSentMessageId
                            if (moreBelow) {
                                isViewingOlder = true
                                hasMoreBottom = true
                                applyInitialUnreadCount()
                                updatePageDownVisibility()
                            }
                        }
                        startLoadFromMessageId = 0L
                        startLoadFromMessageOffset = Int.MAX_VALUE
                        loadingFromOldPosition = false
                        recyclerView.post { markVisibleAsRead() }
                        }
                    } else if (needScrollRestore && lastSeenMessageId != 0L && hasUnread) {
                        scrollToFirstUnread()
                        needScrollRestore = false
                        val newestInList = messages.firstOrNull()?.id ?: 0L
                        if (lastSentMessageId != 0L && newestInList < lastSentMessageId) {
                            isViewingOlder = true
                            hasMoreBottom = true
                        }
                        applyInitialUnreadCount()
                        updatePageDownVisibility()
                        recyclerView.post { markVisibleAsRead() }
                    } else if (wasFirstLoad) {
                        forceScrollToBottom()
                        markAsRead()
                    } else if (anchorMsgId != 0L) {
                        val idx = messages.indexOfFirst { it.id == anchorMsgId }
                        if (idx >= 0) {
                            val lm = recyclerView.layoutManager as? LinearLayoutManager
                            lm?.scrollToPositionWithOffset(adapter.messagesStartRow + idx, anchorOffset)
                        }
                    }
                }
            }
        }

        observe(NotificationCenter.didReceiveNewMessages) { _, _, args ->
            if (args.size < 2 || args[0] != channelId) return@observe
            val entity = args[1] as? MessageEntity ?: return@observe
            if (messagesDict.get(entity.id) != null) return@observe
            if (entity.id > lastSentMessageId) lastSentMessageId = entity.id
            if (entity.isMe && (isViewingOlder || hasMoreBottom)) {
                jumpToPresent()
                return@observe
            }
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
                if (entity.isMe) forceScrollToBottom() else scrollToBottom()
            }
            if (!isPaused) markAsRead()
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
            (inputField.background as? android.graphics.drawable.GradientDrawable)?.setColor(themeColors.tertiary)
            (attachButton.background as? android.graphics.drawable.GradientDrawable)?.setColor(themeColors.tertiary)
            attachButton.setColorFilter(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary))
            emojiButton.setColorFilter(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary))
            micButton.setColorFilter(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary))
            attachmentPreviewScroll?.setBackgroundColor(themeColors.surface)
            actionBar?.applyTheme()
            pageDownButton.applyColors()
            unreadDecoration.applyColors()
            adapter.notifyDataSetChanged()
        }

        observe(NotificationCenter.appDidReconnect) { _, _, _ ->
            if (isPaused) return@observe
            Log.d(TAG, "appDidReconnect: reloading messages for channel $channelId")
            chatController.loadMessages(channelId, clanId)
        }

        observe(NotificationCenter.scrollToBottomChat) { _, _, args ->
            val targetId = args.firstOrNull() as? Long ?: return@observe
            if (targetId != channelId) return@observe
            isViewingOlder = false
            hasMoreBottom = false
            newUnreadCount = 0
            pageDownButton.show(false)
            pageDownButton.setUnreadCount(0)
            forceScrollToBottom()
            markAsRead()
        }

        notificationCenter.addPostponeNotificationsCallback(postponeNewMessagesCallback)

        isLoading = true
        if (forceLatest) {
            chatController.loadMessages(channelId, clanId)
        } else if (startLoadFromMessageId != 0L) {
            chatController.loadMessagesAround(channelId, clanId, startLoadFromMessageId)
        } else {
            chatController.loadMessages(channelId, clanId)
        }
        return true
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        chatController = entryPoint.chatController()
        dialogsController = entryPoint.dialogsController()
        channelController = entryPoint.channelController()
        mediaController = entryPoint.mediaController()
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
        unreadDecoration = UnreadDividerDecoration(themeColors, getString(R.string.message_new_messages))
        recyclerView.addItemDecoration(unreadDecoration)
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
                LayoutHelper.dp(56f), LayoutHelper.dp(56f),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = LayoutHelper.dp(8f)
                bottomMargin = LayoutHelper.dp(4f)
            }
        )

        attachmentPreviewScroll = HorizontalScrollView(context).apply {
            visibility = View.GONE
            setBackgroundColor(themeColors.surface)
            isHorizontalScrollBarEnabled = false
            val pad = LayoutHelper.dp(8)
            setPadding(pad, pad, pad, 0)
        }
        attachmentPreviewStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        attachmentPreviewScroll!!.addView(attachmentPreviewStrip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LayoutHelper.dp(56f)
        ))
        rootView.addView(attachmentPreviewScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val blurple = 0xFF5865F2.toInt()

        inputBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(themeColors.surface)
            setPadding(LayoutHelper.dp(6f), LayoutHelper.dp(10f), LayoutHelper.dp(2f), LayoutHelper.dp(10f))
        }
        rootView.addView(inputBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val btnPad = LayoutHelper.dp(8f)
        attachButton = ImageButton(context).apply {
            val drawable = MezonIcon.plusLargeIcon.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary), PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPad, btnPad, btnPad, btnPad)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColors.tertiary)
            }
            setOnClickListener { showAttachmentPicker() }
        }
        inputBar.addView(attachButton, LayoutHelper.createLinear(40, 40, gravity = Gravity.BOTTOM))

        inputWrapper = FrameLayout(context)
        inputBar.addView(inputWrapper, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.BOTTOM, 6f, 0f, 6f, 0f))

        inputField = EditText(context).apply {
            hint = getString(R.string.message_input_placeholder)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            textSize = 15f
            maxLines = 4
            minimumHeight = LayoutHelper.dp(40f)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dp(20f).toFloat()
            }
            setPadding(LayoutHelper.dp(20f), LayoutHelper.dp(8f), LayoutHelper.dp(40f), LayoutHelper.dp(12f))
        }
        inputWrapper.addView(inputField, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        emojiButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_emoji_icon)
            setColorFilter(PorterDuffColorFilter(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary), PorterDuff.Mode.SRC_IN))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
        }
        inputWrapper.addView(emojiButton, FrameLayout.LayoutParams(
            LayoutHelper.dp(24f), LayoutHelper.dp(24f),
            Gravity.END or Gravity.BOTTOM
        ).apply {
            rightMargin = LayoutHelper.dp(8f)
            bottomMargin = LayoutHelper.dp(8f)
        })

        sendButton = ImageButton(context).apply {
            val drawable = MezonIcon.sendMessageIcon.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPad, btnPad, btnPad, btnPad)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(blurple)
            }
            visibility = View.GONE
            setOnClickListener { sendMessage() }
        }
        inputBar.addView(sendButton, LayoutHelper.createLinear(40, 40, gravity = Gravity.BOTTOM))

        micButton = ImageButton(context).apply {
            val drawable = MezonIcon.microphoneIcon.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary), PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPad, btnPad, btnPad, btnPad)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        inputBar.addView(micButton, LayoutHelper.createLinear(40, 40, gravity = Gravity.BOTTOM))

        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSendButtonState()
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
            override fun didLongPress(cell: ChatMessageCell, msg: MessageEntity) {
                showMessageActionSheet(msg)
            }
            override fun didClickAvatar(cell: ChatMessageCell, msg: MessageEntity) {
                showUserProfile(msg)
            }
        })
        adapter.channelType = channelType
        adapter.clanId = clanId
        adapter.isChannelPrivate = resolveChannelPrivate()
        recyclerView.adapter = adapter

        setupSwipeInterceptor()

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
                        markVisibleAsRead()
                    }
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val firstVisible = lm.findFirstVisibleItemPosition()
                val wasViewingOlder = isViewingOlder
                isViewingOlder = firstVisible > PAGE_DOWN_SCROLL_THRESHOLD

                if (isViewingOlder != wasViewingOlder) {
                    if (isViewingOlder) pausedOnLastMessage = false
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
        val hasDivider = unreadDecoration.firstUnreadAdapterPosition != RecyclerView.NO_POSITION
        Log.d(TAG, "onBecomeFullyVisible: msgs=${messages.size} isLoading=$isLoading isViewingOlder=$isViewingOlder hasDivider=$hasDivider needScrollRestore=$needScrollRestore firstLoad=$firstLoad")
        if (messages.isNotEmpty()) {
            needScrollRestore = false
            loadingView.visibility = View.GONE
            errorView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.showLoadingUp = hasMoreTop
            adapter.showLoadingDown = hasMoreBottom
            adapter.updateRowsSafe()
            updateUnreadDividerPosition()
            if (!isViewingOlder && !hasDivider) markAsRead()
        } else if (!isLoading) {
            isLoading = true
            showLoading()
            if (startLoadFromMessageId != 0L) {
                chatController.loadMessagesAround(channelId, clanId, startLoadFromMessageId)
            } else {
                chatController.loadMessages(channelId, clanId)
            }
        } else {
            showLoading()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::recyclerView.isInitialized) recyclerView.stopScroll()
        saveScrollPosition()
    }

    private fun saveScrollPosition() {
        if (firstLoad || !::recyclerView.isInitialized) return

        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val position = lm.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val prefs = getParentActivity()?.getSharedPreferences(SCROLL_PREFS, android.content.Context.MODE_PRIVATE)
            ?: return

        if (position <= PAGE_DOWN_SCROLL_THRESHOLD && !hasMoreBottom) {
            prefs.edit()
                .putLong("mid_$channelId", 0L)
                .putInt("off_$channelId", 0)
                .putBoolean("bot_$channelId", true)
                .commit()
            return
        }

        var messageId = 0L
        var offset = 0
        for (i in 0..1) {
            val holder = recyclerView.findViewHolderForAdapterPosition(position + i) ?: continue
            val msgId = when (val v = holder.itemView) {
                is ChatMessageCell -> v.messageEntity?.id
                is SystemMessageCell -> v.messageEntity?.id
                else -> null
            }
            if (msgId != null && msgId != 0L) {
                messageId = msgId
                offset = holder.itemView.bottom - recyclerView.measuredHeight
                break
            }
        }

        if (messageId != 0L) {
            prefs.edit()
                .putLong("mid_$channelId", messageId)
                .putInt("off_$channelId", offset)
                .putBoolean("bot_$channelId", false)
                .commit()
        } else {
            val fallbackMsg = messages.firstOrNull()
            if (fallbackMsg != null && (isViewingOlder || hasMoreBottom)) {
                prefs.edit()
                    .putLong("mid_$channelId", fallbackMsg.id)
                    .putInt("off_$channelId", Int.MAX_VALUE)
                    .putBoolean("bot_$channelId", false)
                    .commit()
            }
        }
    }

    override fun onFragmentDestroy() {
        notificationCenter.removePostponeNotificationsCallback(postponeNewMessagesCallback)
        notificationCenter.onAnimationFinish(transitionAnimationIndex)
        saveScrollPosition()
        mainHandler.removeCallbacks(markVisibleRunnable)
        markVisibleAsRead()
        flushPendingSeen()
        dialogsController.clearCurrentChannel()
        if (clanId != 0L) channelController.clearCurrentChannel()
        messages.clear()
        messagesDict.clear()
        pendingAttachments.clear()
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
        updateUnreadDividerPosition()
    }

    private fun showEmpty() {
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        adapter.notifyMessagesUpdated()
    }

    private fun forceScrollToBottom() {
        unreadDecoration.clear()
        recyclerView.post { recyclerView.scrollToPosition(0) }
    }

    private fun scrollToBottom() {
        unreadDecoration.clear()
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
            lm.scrollToPositionWithOffset(adapter.messagesStartRow + idx, recyclerView.height / 2)
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
        val hadMoreBottom = hasMoreBottom
        newUnreadCount = 0
        isViewingOlder = false
        hasMoreBottom = false
        hasUnread = false
        needScrollRestore = false
        pausedOnLastMessage = true
        pageDownButton.show(false)
        pageDownButton.setUnreadCount(0)
        clearSavedScrollPosition()
        unreadDecoration.clear()

        if (!hadMoreBottom && lastSentMessageId != 0L && messagesDict.get(lastSentMessageId) != null) {
            forceScrollToBottom()
            markAsRead()
        } else {
            jumpingToPresent = true
            messages.clear()
            messagesDict.clear()
            firstLoad = true
            chatController.loadMessages(channelId, clanId, forceRefresh = true)
        }
    }

    private fun clearSavedScrollPosition() {
        getParentActivity()?.getSharedPreferences(SCROLL_PREFS, android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putLong("mid_$channelId", 0L)
            ?.putInt("off_$channelId", 0)
            ?.putBoolean("bot_$channelId", true)
            ?.commit()
    }

    private fun markAsRead() {
        val newest = messages.firstOrNull() ?: return
        if (messagesDict[newest.id] == null) return
        if (clanId != 0L) {
            if (channelController.findChannelById(channelId) == null) return
        } else {
            if (dialogsController.getDialog(channelId) == null) return
        }
        if (newest.id <= lastSeenMessageId) return
        lastSeenMessageId = newest.id

        pendingSeenMessageId = newest.id
        pendingSeenTimestamp = newest.timestampSeconds.toInt()
        pendingBadgeCount = 0
        mainHandler.removeCallbacks(markVisibleRunnable)
        mainHandler.postDelayed(markVisibleRunnable, 500)
    }

    private fun markVisibleAsRead() {
        if (!::recyclerView.isInitialized || messages.isEmpty()) return
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstPos = lm.findFirstVisibleItemPosition()
        if (firstPos == RecyclerView.NO_POSITION) return

        val msgIndex = firstPos - adapter.messagesStartRow
        val visibleMsg = if (msgIndex in messages.indices) messages[msgIndex] else messages.firstOrNull() ?: return
        if (visibleMsg.id <= lastSeenMessageId) return

        lastSeenMessageId = visibleMsg.id
        val remaining = if (lastSentMessageId != 0L && visibleMsg.id < lastSentMessageId) {
            messages.count { it.id > visibleMsg.id }
        } else {
            0
        }
        newUnreadCount = remaining
        if (::pageDownButton.isInitialized) pageDownButton.setUnreadCount(remaining)

        pendingSeenMessageId = visibleMsg.id
        pendingSeenTimestamp = visibleMsg.timestampSeconds.toInt()
        pendingBadgeCount = remaining
        mainHandler.removeCallbacks(markVisibleRunnable)
        mainHandler.postDelayed(markVisibleRunnable, 500)
    }

    private fun flushPendingSeen() {
        if (pendingSeenMessageId == 0L) return
        val msgId = pendingSeenMessageId
        val ts = pendingSeenTimestamp
        val badge = pendingBadgeCount
        pendingSeenMessageId = 0L
        chatController.updateLastSeenMessage(
            channelId, clanId, channelType,
            msgId, ts, badgeCount = badge
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

    private fun applyInitialUnreadCount() {
        if (newUnreadCount > 0 || lastSentMessageId == 0L || lastSeenMessageId == 0L) return
        if (lastSeenMessageId >= lastSentMessageId) return
        val estimate = ((lastSentMessageId ushr 22) - (lastSeenMessageId ushr 22)).toInt()
            .coerceIn(0, 999)
        if (estimate > 0) {
            newUnreadCount = estimate
            if (::pageDownButton.isInitialized) pageDownButton.setUnreadCount(estimate)
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

    private fun updateUnreadDividerPosition() {
        if (!hasUnread || dividerSeenMessageId == 0L || messages.isEmpty()) {
            unreadDecoration.clear()
            return
        }
        val seenIdx = messages.indexOfFirst { it.id == dividerSeenMessageId }
        if (seenIdx <= 0) {
            unreadDecoration.clear()
            return
        }
        val firstUnreadMsg = messages[seenIdx - 1]
        if (firstUnreadMsg.senderId == chatController.getCurrentUserId()) {
            unreadDecoration.clear()
            return
        }
        unreadDecoration.firstUnreadAdapterPosition = adapter.messagesStartRow + seenIdx - 1
    }

    private fun scrollToFirstUnread() {
        if (!hasUnread || dividerSeenMessageId == 0L) return
        val seenIdx = messages.indexOfFirst { it.id == dividerSeenMessageId }
        if (seenIdx > 0) {
            val firstUnreadIdx = seenIdx - 1
            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
            lm.scrollToPositionWithOffset(adapter.messagesStartRow + firstUnreadIdx, recyclerView.height / 2)
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
        val text = inputField.text?.toString()?.trim() ?: ""
        if (text.isBlank() && pendingAttachments.isEmpty()) return

        val isPrivate = resolveChannelPrivate()
        if (pendingAttachments.isNotEmpty()) {
            val ctx = getContext() ?: return
            chatController.sendMessageWithAttachments(
                channelId, clanId, channelType, isPrivate, text,
                ArrayList(pendingAttachments),
                ctx.contentResolver
            )
            clearPendingAttachments()
        } else {
            chatController.sendMessage(channelId, clanId, channelType, isPrivate, text)
        }
        inputField.text?.clear()
    }

    private fun showAttachmentPicker() {
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        if (!hasMediaPermission()) {
            requestMediaPermission()
            return
        }

        openAttachAlert()
    }

    private fun hasMediaPermission(): Boolean {
        val ctx = getContext() ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermission() {
        val activity = getParentActivity() ?: return
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        activity.requestPermissions(permissions, ChatAttachAlert.REQUEST_CODE_MEDIA_PERMISSION)
    }

    private fun openAttachAlert() {
        val ctx = getContext() ?: return
        val alert = ChatAttachAlert(ctx, mediaController, themeColors)
        alert.attachDelegate = object : ChatAttachAlert.ChatAttachAlertDelegate {
            override fun onAttachmentsSelected(items: List<AttachmentPickerItem>) {
                pendingAttachments.addAll(items)
                updateAttachmentPreview()
                updateSendButtonState()
            }
        }
        alert.setDrawNavigationBar(true)
        alert.show()
    }

    private fun updateSendButtonState() {
        val hasText = inputField.text?.isNotBlank() == true
        val hasAttachments = pendingAttachments.isNotEmpty()
        val showSend = hasText || hasAttachments
        sendButton.visibility = if (showSend) View.VISIBLE else View.GONE
        micButton.visibility = if (showSend) View.GONE else View.VISIBLE
    }

    private fun updateAttachmentPreview() {
        val strip = attachmentPreviewStrip ?: return
        val scroll = attachmentPreviewScroll ?: return
        strip.removeAllViews()

        if (pendingAttachments.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE

        val ctx = getContext() ?: return
        val thumbSize = LayoutHelper.dp(48f)
        val margin = LayoutHelper.dp(4f)

        for (i in pendingAttachments.indices) {
            val item = pendingAttachments[i]
            val container = FrameLayout(ctx)

            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageURI(item.uri)
            }
            container.addView(thumb, FrameLayout.LayoutParams(thumbSize, thumbSize))

            val closeBtn = ImageView(ctx).apply {
                val drawable = MezonIcon.closeSmallBold.getDrawable(ctx)
                drawable.colorFilter = PorterDuffColorFilter(
                    android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN
                )
                setImageDrawable(drawable)
                setBackgroundColor(0x80000000.toInt())
                setPadding(LayoutHelper.dp(2f), LayoutHelper.dp(2f), LayoutHelper.dp(2f), LayoutHelper.dp(2f))
                setOnClickListener {
                    pendingAttachments.removeAt(i)
                    updateAttachmentPreview()
                    updateSendButtonState()
                }
            }
            container.addView(closeBtn, FrameLayout.LayoutParams(
                LayoutHelper.dp(18f), LayoutHelper.dp(18f), Gravity.TOP or Gravity.END
            ))

            val lp = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                rightMargin = margin
            }
            strip.addView(container, lp)
        }
    }

    private fun clearPendingAttachments() {
        pendingAttachments.clear()
        updateAttachmentPreview()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == ChatAttachAlert.REQUEST_CODE_MEDIA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openAttachAlert()
            }
        }
    }

    private fun setupSwipeInterceptor() {
        recyclerView.setOnInterceptTouchListener(RecyclerListView.OnInterceptTouchListener { e ->
            processTouchEventForSwipe(e)
            false // Don't consume — let RecyclerView handle normally
        })
    }

    private fun processTouchEventForSwipe(e: MotionEvent) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!startedTrackingSlidingView && !maybeStartTrackingSlidingView) {
                    val view = recyclerView.findChildViewUnder(e.x, e.y)
                    if (view is ChatMessageCell) {
                        slidingView = view
                        startedTrackingPointerId = e.getPointerId(0)
                        maybeStartTrackingSlidingView = true
                        startedTrackingX = e.x.toInt()
                        startedTrackingY = e.y.toInt()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (slidingView != null && e.getPointerId(0) == startedTrackingPointerId) {
                    val dx = Math.abs(e.x.toInt() - startedTrackingX)
                    val dy = Math.abs(e.y.toInt() - startedTrackingY)
                    val swipeThreshold = AndroidUtilities.getPixelsInCM(0.4f, true).toInt()
                    if (maybeStartTrackingSlidingView && !startedTrackingSlidingView
                        && dx >= swipeThreshold && dx / 3 > dy) {
                        val cancel = MotionEvent.obtain(
                            0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0
                        )
                        slidingView?.onTouchEvent(cancel)
                        cancel.recycle()

                        maybeStartTrackingSlidingView = false
                        startedTrackingSlidingView = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                slidingView = null
                maybeStartTrackingSlidingView = false
                startedTrackingSlidingView = false
                startedTrackingPointerId = -1
            }
        }
    }

    private fun showMessageActionSheet(msg: MessageEntity) {
        val ctx = getContext() ?: return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val userId = chatController.getCurrentUserId()
        val isMyMessage = msg.senderId == userId
        val hasMedia = msg.allImageAttachments.isNotEmpty() ||
            msg.attachmentUrl.isNotEmpty() && (msg.attachmentFiletype.startsWith("image/") || msg.attachmentFiletype.startsWith("video/"))
        val hasImage = msg.allImageAttachments.any { it.filetype.startsWith("image/") }

        val sheet = MessageActionBottomSheet(
            context = ctx,
            message = msg,
            isMyMessage = isMyMessage,
            isDM = clanId == 0L,
            isPinned = false, // TODO: check if pinned via PinController
            canDeleteMessage = isMyMessage, // TODO: check permission
            canManageThread = clanId != 0L, // TODO: check permission
            hasMedia = hasMedia,
            hasImage = hasImage,
            listener = object : MessageActionBottomSheet.MessageActionListener {
                override fun onActionSelected(action: MessageActionBottomSheet.ActionType, message: MessageEntity) {
                    handleMessageAction(action, message)
                }
            }
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }

    private fun showUserProfile(msg: MessageEntity) {
        val ctx = getContext() ?: return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val currentUserId = chatController.getCurrentUserId()
        val isOwnProfile = msg.senderId == currentUserId

        val sheet = UserProfileBottomSheet(
            context = ctx,
            userId = msg.senderId,
            displayName = msg.senderName ?: "Unknown",
            username = msg.senderName ?: "unknown",
            avatarUrl = msg.senderAvatar,
            aboutMe = null,  // TODO: fetch from user profile API
            memberSince = null, // TODO: fetch from user profile API
            isOwnProfile = isOwnProfile,
            isDM = clanId == 0L,
            listener = object : UserProfileBottomSheet.UserProfileListener {
                override fun onSendMessage(userId: Long) {
                    // TODO: open DM with this user
                    Log.d(TAG, "UserProfile: Send message to $userId")
                }
                override fun onVoiceCall(userId: Long) {
                    // TODO: initiate voice call with this user
                    Log.d(TAG, "UserProfile: Voice call to $userId")
                }
                override fun onAddFriend(userId: Long) {
                    // TODO: send friend request to this user
                    Log.d(TAG, "UserProfile: Add friend $userId")
                }
            }
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }

    private fun handleMessageAction(action: MessageActionBottomSheet.ActionType, msg: MessageEntity) {
        when (action) {
            MessageActionBottomSheet.ActionType.Reply -> {
                // TODO: set chatbox to reply mode
                Log.d(TAG, "Action: Reply to message ${msg.id}")
            }
            MessageActionBottomSheet.ActionType.EditMessage -> {
                // TODO: set chatbox to edit mode
                Log.d(TAG, "Action: Edit message ${msg.id}")
            }
            MessageActionBottomSheet.ActionType.CopyText -> {
                val ctx = getContext() ?: return
                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", msg.content))
                android.widget.Toast.makeText(ctx, R.string.message_toast_copy_text, android.widget.Toast.LENGTH_SHORT).show()
            }
            MessageActionBottomSheet.ActionType.ForwardMessage -> {
                // TODO: open forward screen
                Log.d(TAG, "Action: Forward message ${msg.id}")
            }
            MessageActionBottomSheet.ActionType.PinMessage -> {
                // TODO: call pin API
                Log.d(TAG, "Action: Pin message ${msg.id}")
            }
            MessageActionBottomSheet.ActionType.UnPinMessage -> {
                // TODO: call unpin API
                Log.d(TAG, "Action: Unpin message ${msg.id}")
            }
            MessageActionBottomSheet.ActionType.DeleteMessage -> {
                showDeleteConfirmation(msg)
            }
            MessageActionBottomSheet.ActionType.CreateThread -> {
                // TODO: open create thread
                Log.d(TAG, "Action: Create thread from message ${msg.id}")
            }
            MessageActionBottomSheet.ActionType.MarkUnRead -> {
                // TODO: call mark unread API
                Log.d(TAG, "Action: Mark unread from message ${msg.id}")
            }
            MessageActionBottomSheet.ActionType.SaveMedia -> {
                // TODO: download media
                Log.d(TAG, "Action: Save media for message ${msg.id}")
            }
            MessageActionBottomSheet.ActionType.CopyMediaLink -> {
                val url = msg.attachmentUrl
                if (url.isNotEmpty()) {
                    val ctx = getContext() ?: return
                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("media_url", url))
                    android.widget.Toast.makeText(ctx, R.string.action_copy_link, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            MessageActionBottomSheet.ActionType.CopyImage,
            MessageActionBottomSheet.ActionType.ShareImage -> {
                // TODO: implement image copy/share
                Log.d(TAG, "Action: ${action.name} for message ${msg.id}")
            }
            MessageActionBottomSheet.ActionType.Report -> {
                // TODO: open report dialog
                Log.d(TAG, "Action: Report message ${msg.id}")
            }
        }
    }

    private fun showDeleteConfirmation(msg: MessageEntity) {
        val ctx = getContext() ?: return
        val builder = android.app.AlertDialog.Builder(ctx)
        builder.setTitle(R.string.message_delete_title)
        builder.setMessage(R.string.message_delete_description)
        builder.setPositiveButton(R.string.common_delete) { _, _ ->
            chatController.deleteMessage(channelId, clanId, channelType, resolveChannelPrivate(), msg.id)
        }
        builder.setNegativeButton(R.string.common_cancel, null)
        builder.show()
    }
}