package com.mezon.mobile.home.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.LOAD_TYPE_INITIAL
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.PageDownButton
import com.mezon.mobile.util.EmojiMarker
import com.mezon.mobile.util.MentionData
import com.mezon.mobile.util.parseContentText
import com.mezon.mobile.util.parseMarkdownAndStrip
import com.mezon.mobile.util.resolveStickerSourceUrl
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.SizeNotifierFrameLayout
import com.mezon.mobile.home.chat.emoji.EmojiView

private const val TAG = "ChatFragment"

class ChatFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_MESSAGE_ID = "message_id"
        private const val ARG_FORCE_LATEST = "force_latest"
        private const val VIEWPORT_LIMIT = 300
        private const val PAGE_DOWN_SCROLL_THRESHOLD = 2
        private const val SCROLL_PREFS = "chat_scroll_positions"
        private const val REQUEST_CODE_LOCATION_PERMISSION = 1002

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
    private lateinit var advancedFunctionButton: ImageButton
    private lateinit var emojiButton: ImageButton
    private lateinit var adapter: ChatAdapter
    private lateinit var rootView: FrameLayout
    private lateinit var inputBar: LinearLayout
    private lateinit var inputWrapper: FrameLayout
    private lateinit var pageDownButton: PageDownButton
    private lateinit var unreadDecoration: UnreadDividerDecoration
    private var attachmentPreviewStrip: LinearLayout? = null
    private var attachmentPreviewScroll: HorizontalScrollView? = null

    private lateinit var emojiController: EmojiController
    private var emojiView: EmojiView? = null
    private var emojiViewVisible = false
    private var emojiPadding = 0
    private var emojiSearchExpanded = false
    private var searchKeyboardWasVisible = false
    private val emojiObjPicked = HashMap<String, String>()
    private lateinit var sizeNotifierRoot: SizeNotifierFrameLayout

    private val pendingAttachments = ArrayList<AttachmentPickerItem>()
    private val pendingAttachmentThumbTasks = ArrayList<Runnable?>()

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
    private var initialApiDone = false
    private var pendingBottomScroll: Runnable? = null
    private var chatAdjustPanHelper: com.mezon.mobile.core.AdjustPanLayoutHelper? = null

    private val waitingForSocketIds = ArrayList<Long>()
    private val socketRealIds = ArrayList<Long>()

    private var replyingToMessage: MessageEntity? = null
    private var replyBar: LinearLayout? = null
    private var replyNameView: TextView? = null
    private var replyCloseButton: ImageButton? = null

    private var editingMessage: MessageEntity? = null
    private var editBar: LinearLayout? = null
    private var editNameView: TextView? = null
    private var editCloseButton: ImageButton? = null

    private lateinit var userClanController: UserClanController
    private var mentionsPopup: MentionsPopupView? = null
    private var mentionsAdapter: MentionSuggestionsAdapter? = null
    private val mentionTrackers = mutableListOf<MentionData>()
    private var mentionAtPosition = -1
    private var mentionQueryLength = 0

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
    private var showLoadingPending = false
    private val showLoadingRunnable = Runnable {
        showLoadingPending = false
        if (isLoading && messages.isEmpty() && fragmentView != null) {
            loadingView.visibility = View.VISIBLE
        }
    }
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

        if (clanId != 0L) {
            userClanController.loadClanMembers(clanId)
            val ch = channelController.findChannelById(channelId)
            if (ch != null && (ch.isPrivate || ch.parentId != 0L)) {
                val targetChannelId = if (ch.parentId != 0L) ch.parentId else channelId
                userClanController.loadChannelMembers(clanId, targetChannelId, channelType)
            }
        }
        Log.d(TAG, "onFragmentCreate: startLoadFromMessageId=$startLoadFromMessageId forceLatest=$forceLatest channelId=$channelId")
        if (startLoadFromMessageId == 0L && !forceLatest) {
            val prefs = getParentActivity()?.getSharedPreferences(SCROLL_PREFS, android.content.Context.MODE_PRIVATE)
            val savedMid = prefs?.getLong("mid_$channelId", 0L) ?: 0L
            val savedOffset = prefs?.getInt("off_$channelId", 0) ?: 0
            val savedAtBottom = prefs?.getBoolean("bot_$channelId", true) ?: true
            Log.d(TAG, "scrollRestore: savedMid=$savedMid savedOffset=$savedOffset savedAtBottom=$savedAtBottom")
            if (savedAtBottom) {
                pausedOnLastMessage = true
            } else if (savedMid != 0L) {
                loadingFromOldPosition = true
                needScrollRestore = true
                startLoadFromMessageOffset = savedOffset
                startLoadFromMessageId = savedMid
                Log.d(TAG, "scrollRestore: will loadMessagesAround mid=$savedMid offset=$savedOffset")
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
            val loadType = args.getOrNull(6) as? Int ?: LOAD_TYPE_INITIAL
            Log.d(TAG, "messagesDidLoad: isCache=$isCache firstLoad=$firstLoad hasUnread=$hasUnread " +
                "loaded=${loadedMessages.size} lastSeen=$lastSeenMessageId lastSent=$lastSentMessageId serverLastSeen=$serverLastSeenId loadType=$loadType")
            if (serverLastSeenId != 0L) {
                val newSeen = maxOf(lastSeenMessageId, serverLastSeenId)
                if (newSeen != lastSeenMessageId) {
                    Log.d(TAG, "lastSeenMessageId Math.max: $lastSeenMessageId → $newSeen")
                    lastSeenMessageId = newSeen
                    if (firstLoad || dividerSeenMessageId == 0L) dividerSeenMessageId = newSeen
                }
                hasUnread = lastSentMessageId != 0L && lastSeenMessageId < lastSentMessageId && lastSeenMessageId != 0L
            }

            val direction = loadType

            if (loadType != LOAD_TYPE_INITIAL && fragmentView != null && messages.isNotEmpty()) {
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
                    messages.sortByDescending { it.id }
                    hasMoreTop = moreTop
                    trimViewportNewest()
                } else {
                    messages.addAll(0, newMessages)
                    messages.sortByDescending { it.id }
                    hasMoreBottom = if (lastSentMessageId != 0L && messages.isNotEmpty()) {
                        messages.first().id < lastSentMessageId
                    } else {
                        moreBottom
                    }
                    trimViewportOldest()
                    if (!hasMoreBottom) {
                        isViewingOlder = false
                        clearSavedScrollPosition()
                    }
                    if (newRowsCount > 0 && newUnreadCount > 0) {
                        newUnreadCount = (newUnreadCount - newRowsCount).coerceAtLeast(0)
                        pageDownButton.setUnreadCount(newUnreadCount)
                    }
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
                        adapter.showLoadingUp = hasMoreTop
                    } else {
                        adapter.showLoadingDown = hasMoreBottom
                    }
                    adapter.notifyMessagesUpdated()

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
                return@observe
            }

            isLoading = false

            if (jumpingToPresent && isCache) {
                Log.d(TAG, "jumpToPresent: skip cache response (waiting for API), loaded=${loadedMessages.size}")
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
                    if (dividerSeenMessageId == 0L) dividerSeenMessageId = lastSeenMessageId
                    Log.d(TAG, "hasUnread re-evaluated to TRUE: lastSeen=$lastSeenMessageId < lastSent=$lastSentMessageId dividerSeen=$dividerSeenMessageId")
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
                    Log.d(TAG, "jumpToPresent: API done, msgs=${messages.size}, showing list + scrollToBottom")
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

                    if (pendingHighlightMessageId != 0L) {
                        val highlightId = pendingHighlightMessageId
                        pendingHighlightMessageId = 0L
                        val hIdx = messages.indexOfFirst { it.id == highlightId }
                        Log.d(TAG, "pendingHighlight: id=$highlightId idx=$hIdx msgsSize=${messages.size}")
                        if (hIdx >= 0) {
                            val newestInList = messages.firstOrNull()?.id ?: 0L
                            if (lastSentMessageId != 0L && newestInList < lastSentMessageId) {
                                isViewingOlder = true
                                hasMoreBottom = true
                            }
                            recyclerView.post { scrollToAndHighlight(hIdx) }
                        }
                    } else if (forceLatest && wasFirstLoad) {
                        forceScrollToBottom()
                        markAsRead()
                    } else if (startLoadFromMessageId != 0L) {
                        Log.d(TAG, "scrollDecision: startLoadFromMessageId=$startLoadFromMessageId offset=$startLoadFromMessageOffset")
                        scrollToMessageWithOffset(startLoadFromMessageId, startLoadFromMessageOffset)
                        if (loadingFromOldPosition) {
                            val newestInList = messages.firstOrNull()?.id ?: 0L
                            val moreBelow = lastSentMessageId != 0L && newestInList < lastSentMessageId
                            if (moreBelow) {
                                isViewingOlder = true
                                hasMoreBottom = true
                                applyInitialUnreadCount()
                            }
                        }
                        startLoadFromMessageId = 0L
                        startLoadFromMessageOffset = Int.MAX_VALUE
                        loadingFromOldPosition = false
                        recyclerView.post { markVisibleAsRead() }
                    } else if (needScrollRestore && lastSeenMessageId != 0L && hasUnread) {
                        scrollToFirstUnread()
                        needScrollRestore = false
                        recyclerView.post {
                            if (recyclerView.visibility != View.VISIBLE) {
                                recyclerView.visibility = View.VISIBLE
                            }
                        }
                        val newestInList = messages.firstOrNull()?.id ?: 0L
                        if (lastSentMessageId != 0L && newestInList < lastSentMessageId) {
                            isViewingOlder = true
                            hasMoreBottom = true
                        }
                        applyInitialUnreadCount()
                        recyclerView.post { markVisibleAsRead() }
                    } else if (wasFirstLoad) {
                        Log.d(TAG, "scrollDecision: wasFirstLoad→forceScrollToBottom")
                        forceScrollToBottom()
                        markAsRead()
                    } else if (anchorMsgId != 0L) {
                        Log.d(TAG, "scrollDecision: anchorRestore anchorMsgId=$anchorMsgId offset=$anchorOffset")
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
            if (entity.isSending) {
                if (isViewingOlder || hasMoreBottom) {
                    jumpToPresent()
                    return@observe
                }
                messagesDict.put(entity.id, entity)
                messages.add(0, entity)
                if (fragmentView != null) {
                    refreshUI()
                    forceScrollToBottom()
                }
                return@observe
            }
            if (entity.isMe) {
                val pendingTempId = waitingForSocketIds.removeFirstOrNull()
                if (pendingTempId != null) {
                    applyRealId(pendingTempId, entity.id)
                } else {
                    socketRealIds.add(entity.id)
                }
                return@observe
            }

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
            messagesDict.put(entity.id, entity)
            if (messages.isEmpty() || entity.id >= messages.first().id) {
                messages.add(0, entity)
            } else {
                val pos = messages.indexOfFirst { entity.id > it.id }
                messages.add(if (pos >= 0) pos else messages.size, entity)
            }
            trimViewportOldest()
            if (fragmentView != null) {
                refreshUI()
                if (entity.isMe) forceScrollToBottom() else scrollToBottom()
            }
            if (!isPaused) markAsRead()
        }

        observe(NotificationCenter.pendingMessageSent) { _, _, args ->
            if (args.size < 3 || args[0] != channelId) return@observe
            val tempId = args[1] as? Long ?: return@observe
            val apiRealId = args[2] as? Long ?: return@observe
            Log.d(TAG, "pendingMessageSent tempId=$tempId apiRealId=$apiRealId")
            val resolvedRealId = when {
                apiRealId != 0L -> apiRealId
                else -> socketRealIds.removeFirstOrNull() ?: 0L
            }
            if (resolvedRealId != 0L) {
                applyRealId(tempId, resolvedRealId)
            } else {
                markMessageSent(tempId)
                waitingForSocketIds.add(tempId)
            }
        }

        observe(NotificationCenter.pendingMessageError) { _, _, args ->
            if (args.size < 2 || args[0] != channelId) return@observe
            val tempId = args[1] as? Long ?: return@observe
            Log.e(TAG, "pendingMessageError tempId=$tempId channelId=$channelId channelType=$channelType")
            val idx = messages.indexOfFirst { it.id == tempId }
            if (idx >= 0) {
                val old = messages[idx]
                val updated = old.copy(sendState = MessageEntity.SEND_STATE_ERROR, isError = true)
                messages[idx] = updated
                messagesDict.put(tempId, updated)
                if (fragmentView != null) updateVisibleRows(NotificationCenter.UPDATE_MASK_SEND_STATE)
            }
        }

        observe(NotificationCenter.messageDidUpdate) { _, _, args ->
            if (args.size < 2 || args[0] != channelId) return@observe
            val updateEntity = args[1] as? MessageEntity ?: return@observe
            val idx = messages.indexOfFirst { it.id == updateEntity.id }
            if (idx >= 0) {
                val existing = messages[idx]
                val merged = existing.copy(
                    content = updateEntity.content,
                    updateTimeSeconds = updateEntity.updateTimeSeconds,
                    hideEditted = updateEntity.hideEditted,
                    code = updateEntity.code
                )
                messages[idx] = merged
                messagesDict.put(merged.id, merged)
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
            advancedFunctionButton.setColorFilter(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary))
            emojiButton.setColorFilter(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary))
            micButton.setColorFilter(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary))
            attachmentPreviewScroll?.setBackgroundColor(themeColors.surface)
            replyBar?.setBackgroundColor(themeColors.surface)
            replyNameView?.setTextColor(themeColors.onSurface)
            replyCloseButton?.let { btn ->
                val d = MezonIcon.closeSmallBold.getDrawable(btn.context)
                d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
                btn.setImageDrawable(d)
            }
            editBar?.setBackgroundColor(themeColors.surface)
            editNameView?.setTextColor(themeColors.onSurface)
            mentionsPopup?.applyColors()
            editCloseButton?.let { btn ->
                val d = MezonIcon.closeSmallBold.getDrawable(btn.context)
                d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
                btn.setImageDrawable(d)
            }
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

        observe(NotificationCenter.channelMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val loadedChannelId = args.firstOrNull() as? Long ?: return@observe
            val ch = channelController.findChannelById(channelId)
            val targetChannelId = if (ch?.parentId != 0L && ch?.parentId != null) ch.parentId else channelId
            if (loadedChannelId == targetChannelId) {
                checkMentionTrigger()
            }
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
        userClanController = entryPoint.userClanController()
        emojiController = entryPoint.emojiController()
    }

    override fun createView(context: Context): View {
        sizeNotifierRoot = SizeNotifierFrameLayout(context, parentLayout)
        sizeNotifierRoot.setBackgroundColor(themeColors.background)
        rootView = sizeNotifierRoot

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val chatActionBar = ActionBarView(context, themeColors).apply {
            setTitle(channelName)
            setBackClickListener {
                if (emojiViewVisible) {
                    hideEmojiView()
                } else {
                    finishFragment()
                }
            }
        }
        actionBar = chatActionBar
        innerLayout.addView(chatActionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))

        val contentFrame = FrameLayout(context)
        innerLayout.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        recyclerView = RecyclerListView(context).apply {
            val lm = LinearLayoutManager(context)
            lm.reverseLayout = true
            lm.stackFromEnd = false
            layoutManager = lm
            itemAnimator = null
            visibility = View.INVISIBLE
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

        mentionsAdapter = MentionSuggestionsAdapter(themeColors) { item -> onMentionSelected(item) }
        mentionsPopup = MentionsPopupView(context, themeColors).apply {
            recyclerView.adapter = mentionsAdapter
        }
        contentFrame.addView(
            mentionsPopup,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                leftMargin = LayoutHelper.dp(8f)
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
        innerLayout.addView(attachmentPreviewScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        replyBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(themeColors.surface)
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(8f), LayoutHelper.dp(8f), LayoutHelper.dp(4f))
            visibility = View.GONE
        }

        val replyBarIndicator = View(context).apply {
            setBackgroundColor(0xFF5865F2.toInt())
        }
        replyBar!!.addView(replyBarIndicator, LinearLayout.LayoutParams(
            LayoutHelper.dp(3f), LayoutHelper.dp(28f)
        ).apply { rightMargin = LayoutHelper.dp(8f) })

        replyNameView = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        replyBar!!.addView(replyNameView, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        replyCloseButton = ImageButton(context).apply {
            val drawable = MezonIcon.closeSmallBold.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = LayoutHelper.dp(8f)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { clearReplyState() }
        }
        replyBar!!.addView(replyCloseButton, LinearLayout.LayoutParams(
            LayoutHelper.dp(32f), LayoutHelper.dp(32f)
        ).also { it.gravity = android.view.Gravity.CENTER_VERTICAL })

        innerLayout.addView(replyBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val blurple = 0xFF5865F2.toInt()

        editBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(themeColors.surface)
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(8f), LayoutHelper.dp(8f), LayoutHelper.dp(4f))
            visibility = View.GONE
        }

        val editBarIndicator = View(context).apply {
            setBackgroundColor(0xFF43B581.toInt())
        }
        editBar!!.addView(editBarIndicator, LinearLayout.LayoutParams(
            LayoutHelper.dp(3f), LayoutHelper.dp(28f)
        ).apply { rightMargin = LayoutHelper.dp(8f) })

        editNameView = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        editBar!!.addView(editNameView, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        editCloseButton = ImageButton(context).apply {
            val drawable = MezonIcon.closeSmallBold.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = LayoutHelper.dp(8f)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { clearEditState() }
        }
        editBar!!.addView(editCloseButton, LinearLayout.LayoutParams(
            LayoutHelper.dp(32f), LayoutHelper.dp(32f)
        ).also { it.gravity = android.view.Gravity.CENTER_VERTICAL })

        innerLayout.addView(editBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        inputBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(themeColors.surface)
            setPadding(LayoutHelper.dp(6f), LayoutHelper.dp(10f), LayoutHelper.dp(2f), LayoutHelper.dp(10f))
        }
        innerLayout.addView(inputBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

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

        advancedFunctionButton = ImageButton(context).apply {
            val drawable = MezonIcon.advancedFunctionIcon.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary), PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPad, btnPad, btnPad, btnPad)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColors.tertiary)
            }
            setOnClickListener { showAdvancedFunctionMenu() }
        }
        inputBar.addView(advancedFunctionButton, LayoutHelper.createLinear(40, 40, gravity = Gravity.BOTTOM, leftMargin = 6f))

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
            setOnClickListener {
                if (emojiViewVisible) {
                    openKeyboardFromEmoji()
                } else {
                    showEmojiView()
                }
            }
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
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(themeColors.tertiary)
            }
        }
        inputBar.addView(micButton, LayoutHelper.createLinear(40, 40, gravity = Gravity.BOTTOM))

        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSendButtonState()
                checkMentionTrigger()
            }
        })

        inputField.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DEL && event.action == android.view.KeyEvent.ACTION_DOWN) {
                deleteEmojiTokenAtCursor()
            } else false
        }

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
            override fun didPressReply(cell: ChatMessageCell, replyMessageId: Long) {
                scrollToReplyMessage(replyMessageId)
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
                        chatController.loadMoreTop(channelId, clanId, oldest)
                    }
                }

                if (!isLoadingMore && hasMoreBottom && messages.size >= 10) {
                    if (firstVisible <= 3) {
                        val newest = messages.firstOrNull()?.id ?: return
                        isLoadingMore = true
                        chatController.loadMoreBottom(channelId, clanId, newest)
                    }
                }
            }
        })

        chatAdjustPanHelper = object : com.mezon.mobile.core.AdjustPanLayoutHelper(rootView) {
            override fun onTransitionStart(keyboardVisible: Boolean, contentHeight: Int) {
                if (!keyboardVisible) recyclerView.stopScroll()
            }
            override fun onPanTranslationUpdate(y: Float, progress: Float, keyboardVisible: Boolean) {
                actionBar?.translationY = y
                if (keyboardVisible && progress > 0f && !recyclerView.canScrollVertically(1)) {
                    recyclerView.scrollBy(0, -y.toInt())
                }
            }
        }

        rootView.addView(innerLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        sizeNotifierRoot.setDelegate(object : SizeNotifierFrameLayout.SizeNotifierFrameLayoutDelegate {
            override fun onSizeChanged(keyboardHeight: Int, isWidthGreater: Boolean) {
                if (emojiSearchExpanded) {
                    if (keyboardHeight > LayoutHelper.dp(50f)) {
                        SharedConfig.saveKeyboardHeight(keyboardHeight, isWidthGreater)
                        searchKeyboardWasVisible = true
                    }
                    if (keyboardHeight <= LayoutHelper.dp(20f) && searchKeyboardWasVisible) {
                        collapseEmojiSearch()
                    }
                    return
                }
                if (keyboardHeight > LayoutHelper.dp(50f)) {
                    SharedConfig.saveKeyboardHeight(keyboardHeight, isWidthGreater)
                    if (emojiViewVisible) {
                        dismissEmojiSilently()
                    }
                }
                if (keyboardHeight <= LayoutHelper.dp(20f) && !emojiViewVisible && emojiPadding != 0) {
                    emojiPadding = 0
                    sizeNotifierRoot.setEmojiKeyboardHeight(0)
                    sizeNotifierRoot.requestLayout()
                }
            }
        })

        observe(NotificationCenter.emojisNeedReload) { _, _, _ ->
            emojiView?.onEmojisReloaded()
        }

        observe(NotificationCenter.stickersNeedReload) { _, _, _ ->
            emojiView?.onStickersReloaded()
        }

        observe(NotificationCenter.gifsNeedReload) { _, _, _ ->
            emojiView?.onGifsReloaded()
        }

        fragmentView = rootView
        return rootView
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (emojiViewVisible || (emojiView != null && emojiView!!.visibility == View.VISIBLE)) {
            dismissEmojiSilently()
        }
        pausedOnLastMessage = false
        if (!initialApiDone) initialApiDone = true
        dialogsController.setCurrentChannel(channelId)
        if (clanId != 0L) {
            channelController.setCurrentChannel(channelId)
            channelController.markChannelAsRead(channelId)
        }
        val hasDivider = unreadDecoration.firstUnreadAdapterPosition != RecyclerView.NO_POSITION
        if (messages.isNotEmpty()) {
            cancelPendingLoading()
            needScrollRestore = false
            loadingView.visibility = View.GONE
            errorView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.showLoadingUp = hasMoreTop
            adapter.showLoadingDown = hasMoreBottom
            adapter.updateRowsSafe()
            updateUnreadDividerPosition()
            if (!isViewingOlder && !hasDivider) markAsRead()
            recyclerView.post {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return@post
                val firstVisible = lm.findFirstVisibleItemPosition()
                isViewingOlder = firstVisible > PAGE_DOWN_SCROLL_THRESHOLD
                updatePageDownVisibility()
            }
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
        if (emojiViewVisible) {
            dismissEmojiSilently()
        }
    }

    override fun onBackPressed(): Boolean {
        if (emojiSearchExpanded) {
            collapseEmojiSearch()
            return false
        }
        if (emojiViewVisible) {
            hideEmojiView()
            return false
        }
        return super.onBackPressed()
    }

    private fun saveScrollPosition() {
        Log.d(TAG, "saveScrollPosition: called firstLoad=$firstLoad rvInit=${::recyclerView.isInitialized}")
        if (firstLoad || !::recyclerView.isInitialized) return

        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val position = lm.findFirstVisibleItemPosition()
        Log.d(TAG, "saveScrollPosition: position=$position isViewingOlder=$isViewingOlder hasMoreBottom=$hasMoreBottom")
        if (position == RecyclerView.NO_POSITION) return
        val prefs = getParentActivity()?.getSharedPreferences(SCROLL_PREFS, android.content.Context.MODE_PRIVATE)
            ?: return

        if (position <= PAGE_DOWN_SCROLL_THRESHOLD && !hasMoreBottom) {
            Log.d(TAG, "saveScrollPosition: atBottom (pos=$position threshold=$PAGE_DOWN_SCROLL_THRESHOLD hasMoreBottom=$hasMoreBottom)")
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
            Log.d(TAG, "saveScrollPosition: mid=$messageId offset=$offset")
            prefs.edit()
                .putLong("mid_$channelId", messageId)
                .putInt("off_$channelId", offset)
                .putBoolean("bot_$channelId", false)
                .commit()
        } else {
            val fallbackMsg = messages.firstOrNull()
            if (fallbackMsg != null && (isViewingOlder || hasMoreBottom)) {
                Log.d(TAG, "saveScrollPosition: fallback mid=${fallbackMsg.id}")
                prefs.edit()
                    .putLong("mid_$channelId", fallbackMsg.id)
                    .putInt("off_$channelId", Int.MAX_VALUE)
                    .putBoolean("bot_$channelId", false)
                    .commit()
            } else {
                Log.d(TAG, "saveScrollPosition: no messageId found, isViewingOlder=$isViewingOlder hasMoreBottom=$hasMoreBottom msgs=${messages.size}")
            }
        }
    }

    private fun showEmojiView() {
        if (emojiView == null) createEmojiView()
        val ev = emojiView!!
        ev.animate().cancel()
        ev.translationY = 0f
        ev.visibility = View.VISIBLE
        emojiViewVisible = true

        val panelHeight = SharedConfig.getEmojiPanelHeight()
        ev.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, panelHeight, Gravity.BOTTOM
        )
        emojiPadding = panelHeight
        sizeNotifierRoot.setEmojiKeyboardHeight(panelHeight)
        sizeNotifierRoot.requestLayout()

        ev.translationY = panelHeight.toFloat()
        ev.animate()
            .translationY(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        AndroidUtilities.hideKeyboard(inputField)
        ev.onOpen()
        updateEmojiButtonIcon(showingEmoji = true)
    }

    private fun openKeyboardFromEmoji() {
        inputField.requestFocus()
        AndroidUtilities.showKeyboard(inputField)
        updateEmojiButtonIcon(showingEmoji = false)
    }

    private fun dismissEmojiSilently() {
        emojiSearchExpanded = false
        searchKeyboardWasVisible = false
        sizeNotifierRoot.isSearchExpanded = false
        emojiViewVisible = false
        emojiView?.visibility = View.GONE
        emojiPadding = 0
        sizeNotifierRoot.setEmojiKeyboardHeight(0)
        updateEmojiButtonIcon(showingEmoji = false)
    }

    private fun hideEmojiView(animated: Boolean = true) {
        emojiSearchExpanded = false
        searchKeyboardWasVisible = false
        sizeNotifierRoot.isSearchExpanded = false
        emojiViewVisible = false
        emojiView?.clearSearchFocus()

        emojiPadding = 0
        sizeNotifierRoot.setEmojiKeyboardHeight(0)
        sizeNotifierRoot.requestLayout()
        updateEmojiButtonIcon(showingEmoji = false)

        val ev = emojiView ?: return
        ev.animate().cancel()
        if (animated) {
            val panelHeight = ev.height.toFloat().coerceAtLeast(1f)
            ev.animate()
                .translationY(panelHeight)
                .setDuration(200)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    ev.visibility = View.GONE
                    ev.translationY = 0f
                }
                .start()
        } else {
            ev.visibility = View.GONE
            ev.translationY = 0f
        }
    }

    private class EmojiTokenSpan

    private fun deleteEmojiTokenAtCursor(): Boolean {
        val editable = inputField.text ?: return false
        val sel = inputField.selectionStart
        if (sel <= 0 || sel != inputField.selectionEnd) return false

        val spans = editable.getSpans(sel - 1, sel, EmojiTokenSpan::class.java)
        if (spans.isEmpty()) return false

        val span = spans[0]
        var start = editable.getSpanStart(span)
        var end = editable.getSpanEnd(span)
        if (end < editable.length && editable[end] == ' ') end++
        if (start < 0) start = 0
        val token = editable.subSequence(editable.getSpanStart(span), editable.getSpanEnd(span)).toString()
        editable.removeSpan(span)
        editable.delete(start, end)
        emojiObjPicked.remove(token)
        return true
    }

    private fun expandEmojiSearch() {
        if (emojiSearchExpanded || !emojiViewVisible) return
        emojiSearchExpanded = true
        searchKeyboardWasVisible = false
        sizeNotifierRoot.isSearchExpanded = true
        sizeNotifierRoot.requestLayout()
    }

    private fun collapseEmojiSearch() {
        if (!emojiSearchExpanded) return
        emojiSearchExpanded = false
        searchKeyboardWasVisible = false
        sizeNotifierRoot.isSearchExpanded = false
        emojiView?.clearSearchFocus()
        AndroidUtilities.hideKeyboard(emojiView)
        if (!emojiViewVisible) return
        val panelHeight = SharedConfig.getEmojiPanelHeight()
        emojiView?.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, panelHeight, Gravity.BOTTOM
        )
        emojiPadding = panelHeight
        sizeNotifierRoot.setEmojiKeyboardHeight(panelHeight)
        sizeNotifierRoot.requestLayout()
    }

    private fun updateEmojiButtonIcon(showingEmoji: Boolean) {
        if (showingEmoji) {
            emojiButton.setImageDrawable(
                MezonIcon.keyboardIcon.getDrawable(getContext()!!).also {
                    it.colorFilter = PorterDuffColorFilter(
                        themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary), PorterDuff.Mode.SRC_IN
                    )
                }
            )
        } else {
            emojiButton.setImageResource(R.drawable.ic_emoji_icon)
            emojiButton.setColorFilter(PorterDuffColorFilter(
                themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary), PorterDuff.Mode.SRC_IN
            ))
        }
    }

    private fun createEmojiView() {
        val ctx = getContext() ?: return
        emojiView = EmojiView(ctx, themeColors).apply {
            init(emojiController)
            delegate = object : EmojiView.EmojiViewDelegate {
                override fun onEmojiSelected(emoji: EmojiItem) {
                    val editable = inputField.text ?: return
                    val cursor = inputField.selectionEnd.coerceAtLeast(0)
                    val cleanName = emoji.shortname.replace(":", "")
                    val token = ":$cleanName:"
                    val insertText = "$token "
                    emojiObjPicked[token] = emoji.id
                    editable.insert(cursor, insertText)
                    val spanStart = cursor
                    val spanEnd = cursor + token.length
                    editable.setSpan(
                        EmojiTokenSpan(),
                        spanStart, spanEnd,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    editable.setSpan(
                        android.text.style.ForegroundColorSpan(0xFF5A62F4.toInt()),
                        spanStart, spanEnd,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    editable.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        spanStart, spanEnd,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    inputField.setSelection(cursor + insertText.length)
                }

                override fun onStickerSelected(sticker: StickerItem, isAudio: Boolean) {
                    if (sticker.isForSale && sticker.src.isBlank()) return
                    val url = resolveStickerSourceUrl(sticker.id, sticker.src)
                    if (url.isBlank()) return
                    val filetype = if (isAudio) "audio/mpeg" else "image/gif"
                    val references = buildReplyReferences()
                    chatController.sendDirectAttachment(
                        channelId, clanId, channelType, resolveChannelPrivate(),
                        url, filetype, sticker.id, references
                    )
                    clearReplyState()
                    hideEmojiView()
                }

                override fun onGifSelected(gifUrl: String) {
                    val references = buildReplyReferences()
                    chatController.sendDirectAttachment(
                        channelId, clanId, channelType, resolveChannelPrivate(),
                        gifUrl, "image/gif", references = references
                    )
                    clearReplyState()
                    hideEmojiView()
                }

                override fun onBackspace() {
                    inputField.dispatchKeyEvent(
                        android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL)
                    )
                }

                override fun onSearchFocusChanged(focused: Boolean) {
                    if (focused) {
                        expandEmojiSearch()
                    } else {
                        collapseEmojiSearch()
                    }
                }

                override fun onDismissRequested() {
                    hideEmojiView(animated = false)
                }
            }
        }
        rootView.addView(emojiView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM
        ))
    }

    override fun onFragmentDestroy() {
        notificationCenter.removePostponeNotificationsCallback(postponeNewMessagesCallback)
        notificationCenter.onAnimationFinish(transitionAnimationIndex)
        saveScrollPosition()
        if (::recyclerView.isInitialized) cancelPendingScroll()
        cancelPendingLoading()
        mainHandler.removeCallbacks(markVisibleRunnable)
        markVisibleAsRead()
        flushPendingSeen()
        dialogsController.clearCurrentChannel()
        if (clanId != 0L) channelController.clearCurrentChannel()
        messages.clear()
        messagesDict.clear()
        for (t in pendingAttachmentThumbTasks) ThumbnailCache.cancel(t)
        pendingAttachmentThumbTasks.clear()
        pendingAttachments.clear()
        replyingToMessage = null
        editingMessage = null
        mentionTrackers.clear()
        mentionsPopup = null
        mentionsAdapter = null
        pendingHighlightMessageId = 0L
        chatAdjustPanHelper?.onDetach()
        chatAdjustPanHelper = null
        emojiView = null
        emojiObjPicked.clear()
        super.onFragmentDestroy()
    }

    private fun refreshUI() {
        if (messages.isNotEmpty()) showMessages()
        else if (isLoading) showLoading()
        else showEmpty()
    }

    private fun showLoading() {
        Log.d(TAG, "showLoading: recyclerView→INVISIBLE, spinner deferred 150ms")
        recyclerView.visibility = View.INVISIBLE
        errorView.visibility = View.GONE
        if (!showLoadingPending) {
            showLoadingPending = true
            mainHandler.postDelayed(showLoadingRunnable, 150)
        }
    }

    private fun showError(message: String) {
        cancelPendingLoading()
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.INVISIBLE
        errorView.visibility = View.VISIBLE
        errorView.text = message
    }

    private fun showMessages() {
        cancelPendingLoading()
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        val vis = if (needScrollRestore) View.INVISIBLE else View.VISIBLE
        Log.d(TAG, "showMessages: msgs=${messages.size} recyclerView→${if (vis == View.VISIBLE) "VISIBLE" else "INVISIBLE"} needScrollRestore=$needScrollRestore")
        recyclerView.visibility = vis
        adapter.showLoadingUp = hasMoreTop
        adapter.showLoadingDown = hasMoreBottom
        adapter.notifyMessagesUpdated()
        updateUnreadDividerPosition()
    }

    private fun showEmpty() {
        cancelPendingLoading()
        loadingView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        adapter.notifyMessagesUpdated()
    }

    private fun cancelPendingLoading() {
        if (showLoadingPending) {
            mainHandler.removeCallbacks(showLoadingRunnable)
            showLoadingPending = false
        }
    }

    private fun forceScrollToBottom() {
        unreadDecoration.clear()
        cancelPendingScroll()
        Log.d(TAG, "forceScrollToBottom: itemCount=${adapter.itemCount} recyclerVisibility=${recyclerView.visibility}")
        val r = Runnable {
            Log.d(TAG, "forceScrollToBottom: scrollToPosition(0) executed")
            recyclerView.scrollToPosition(0)
        }
        pendingBottomScroll = r
        recyclerView.post(r)
    }

    private fun cancelPendingScroll() {
        pendingBottomScroll?.let { recyclerView.removeCallbacks(it) }
        pendingBottomScroll = null
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
        cancelPendingScroll()
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
            adapter.notifyMessagesUpdated()
            recyclerView.visibility = View.INVISIBLE
            firstLoad = true
            Log.d(TAG, "jumpToPresent: cleared list, calling loadMessages forceRefresh=true")
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
        if (!initialApiDone) return
        val shouldShow = isViewingOlder || hasMoreBottom
        pageDownButton.show(shouldShow)
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

    private fun trimViewportNewest() {
        while (messages.size > VIEWPORT_LIMIT) {
            val removed = messages.removeAt(0)
            messagesDict.delete(removed.id)
            hasMoreBottom = true
        }
    }

    private fun updateUnreadDividerPosition() {
        if (!hasUnread || dividerSeenMessageId == 0L || messages.isEmpty()) {
            unreadDecoration.clear()
            return
        }
        val seenIdx = messages.indexOfFirst { it.id == dividerSeenMessageId }
        if (seenIdx < 0) {
            val oldestId = messages.lastOrNull()?.id ?: 0L
            if (oldestId > dividerSeenMessageId) {
                unreadDecoration.firstUnreadAdapterPosition = adapter.messagesStartRow + messages.size - 1
            } else {
                unreadDecoration.clear()
            }
            return
        }
        if (seenIdx == 0) {
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
        if (!hasUnread || dividerSeenMessageId == 0L) {
            if (needScrollRestore) {
                recyclerView.visibility = View.VISIBLE
                needScrollRestore = false
            }
            return
        }
        cancelPendingScroll()
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
        } else if (seenIdx < 0 && messages.isNotEmpty()) {
            val oldestId = messages.last().id
            if (oldestId > dividerSeenMessageId) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                lm.scrollToPositionWithOffset(adapter.messagesStartRow + messages.size - 1, recyclerView.height / 2)
            }
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
        if (channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP) {
            return true
        }
        if (clanId != 0L) {
            return channelController.findChannelById(channelId)?.isPrivate ?: false
        }
        return false
    }

    private fun resolveMentionMembers(): List<ClanMember> {
        if (clanId == 0L) return emptyList()
        val ch = channelController.findChannelById(channelId)
        if (ch != null && (ch.isPrivate || ch.parentId != 0L)) {
            val targetChannelId = if (ch.parentId != 0L) ch.parentId else channelId
            val channelMembers = userClanController.getChannelMembers(targetChannelId)
            if (channelMembers.isNotEmpty()) return channelMembers
        }
        return userClanController.getClanMembers(clanId)
    }

    private fun sendMessage() {
        val text = inputField.text?.toString()?.trim() ?: ""
        if (text.isBlank() && pendingAttachments.isEmpty()) return

        val editMsg = editingMessage
        if (editMsg != null) {
            val isPrivate = resolveChannelPrivate()
            chatController.editMessage(channelId, clanId, channelType, isPrivate, editMsg.id, text)
            clearEditState()
            return
        }

        val isPrivate = resolveChannelPrivate()
        val references = buildReplyReferences()

        val mdResult = parseMarkdownAndStrip(text)
        val cleanedText = mdResult.cleanedText
        val mdMarkers = mdResult.markers.ifEmpty { null }

        val mentions = if (mentionTrackers.isNotEmpty()) {
            mentionTrackers.map { m ->
                MentionData(
                    userId = m.userId,
                    roleId = m.roleId,
                    startOffset = mdResult.adjustOffset(m.startOffset),
                    endOffset = mdResult.adjustOffset(m.endOffset)
                )
            }
        } else null
        Log.d(TAG, "sendMessage channelId=$channelId clanId=$clanId channelType=$channelType isPrivate=$isPrivate textLen=${cleanedText.length} attachments=${pendingAttachments.size} hasReply=${references != null} mdMarkers=${mdMarkers?.size ?: 0}")

        if (pendingAttachments.isNotEmpty()) {
            val ctx = getContext() ?: return
            chatController.sendMessageWithAttachments(
                channelId, clanId, channelType, isPrivate, cleanedText,
                ArrayList(pendingAttachments),
                ctx.contentResolver,
                references
            )
            clearPendingAttachments()
        } else {
            val emojiMarkers = buildEmojiMarkers(cleanedText)
            chatController.sendMessage(channelId, clanId, channelType, isPrivate, cleanedText, references, mentions, emojiMarkers, mdMarkers)
        }
        inputField.text?.clear()
        emojiObjPicked.clear()
        mentionTrackers.clear()
        clearReplyState()
    }

    private fun buildEmojiMarkers(text: String): List<EmojiMarker>? {
        if (emojiObjPicked.isEmpty()) return null
        val markers = ArrayList<EmojiMarker>()
        for ((shortname, emojiId) in emojiObjPicked) {
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf(shortname, searchFrom)
                if (idx < 0) break
                markers.add(EmojiMarker(emojiId, idx, idx + shortname.length))
                searchFrom = idx + shortname.length
            }
        }
        return markers.ifEmpty { null }
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

    private fun showAdvancedFunctionMenu() {
        val ctx = getContext() ?: return
        val alert = AdvancedAttachAlert(ctx, themeColors)
        alert.advancedDelegate = object : AdvancedAttachAlert.AdvancedAttachAlertDelegate {
            override fun onLocationSelected() {
                requestLocationAndSend()
            }
            override fun onFilesSelected() {
                showAttachmentPicker()
            }
        }
        alert.setDrawNavigationBar(true)
        alert.show()
    }

    private fun requestLocationAndSend() {
        val activity = getParentActivity() ?: return
        val ctx = getContext() ?: return

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fetchCurrentLocationAndSend()
            return
        }

        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            com.mezon.mobile.core.AlertDialog.Builder(activity)
                .setTitle(getString(R.string.share_location_title, ""))
                .setMessage(getString(R.string.permission_no_location))
                .setPositiveButton(getString(R.string.common_ok)) { _, _ ->
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQUEST_CODE_LOCATION_PERMISSION
                    )
                }
                .setNegativeButton(getString(R.string.permission_not_now), null)
                .create()
                .show()
            return
        }

        activity.requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQUEST_CODE_LOCATION_PERMISSION
        )
    }

    private fun showOpenLocationSettingsDialog() {
        val activity = getParentActivity() ?: return
        com.mezon.mobile.core.AlertDialog.Builder(activity)
            .setTitle(getString(R.string.share_location_title, ""))
            .setMessage(getString(R.string.permission_no_location))
            .setPositiveButton(getString(R.string.permission_open_settings)) { _, _ ->
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", activity.packageName, null)
                    )
                    activity.startActivity(intent)
                } catch (_: Exception) {}
            }
            .setNegativeButton(getString(R.string.permission_not_now), null)
            .create()
            .show()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun fetchCurrentLocationAndSend() {
        val ctx = getContext() ?: return
        val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return

        val lastKnown = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

        if (lastKnown != null) {
            showLocationConfirmDialog(lastKnown.latitude, lastKnown.longitude)
            return
        }

        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                locationManager.removeUpdates(this)
                showLocationConfirmDialog(location.latitude, location.longitude)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                locationManager.requestSingleUpdate(android.location.LocationManager.GPS_PROVIDER, listener, null)
            } else if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestSingleUpdate(android.location.LocationManager.NETWORK_PROVIDER, listener, null)
            } else {
                android.widget.Toast.makeText(ctx, R.string.permission_no_location, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location", e)
        }
    }

    private fun showLocationConfirmDialog(latitude: Double, longitude: Double) {
        val activity = getParentActivity() ?: return
        val channelName = arguments?.getString(ARG_CHANNEL_NAME).orEmpty()
        val alertDialog = com.mezon.mobile.core.AlertDialog.Builder(activity)
            .setTitle(getString(R.string.share_location_title, channelName))
            .setMessage(getString(R.string.share_location_coordinate, latitude, longitude))
            .setPositiveButton(getString(R.string.share_location_send)) { _, _ ->
                chatController.sendLocation(
                    channelId, clanId, channelType, resolveChannelPrivate(), latitude, longitude
                )
            }
            .setNegativeButton(getString(R.string.share_location_cancel), null)
            .create()
        alertDialog.show()
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
        for (t in pendingAttachmentThumbTasks) ThumbnailCache.cancel(t)
        pendingAttachmentThumbTasks.clear()
        strip.removeAllViews()

        if (pendingAttachments.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE

        val ctx = getContext() ?: return
        val resolver = ctx.contentResolver
        val thumbSize = LayoutHelper.dp(48f)
        val margin = LayoutHelper.dp(4f)

        for (i in pendingAttachments.indices) {
            val item = pendingAttachments[i]
            val container = FrameLayout(ctx)
            val bindId = item.id

            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                tag = bindId
            }
            val cached = ThumbnailCache.get(bindId)
            if (cached != null) {
                thumb.setImageBitmap(cached)
            } else {
                val task = ThumbnailCache.load(resolver, item, object : ThumbnailCache.Callback {
                    override fun onThumbnailLoaded(id: Long, bitmap: Bitmap) {
                        if (thumb.tag == bindId) thumb.setImageBitmap(bitmap)
                    }
                })
                if (task != null) pendingAttachmentThumbTasks.add(task)
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
        if (requestCode == REQUEST_CODE_LOCATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchCurrentLocationAndSend()
            } else {
                val activity = getParentActivity() ?: return
                val permanentlyDenied = !activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                if (permanentlyDenied) {
                    showOpenLocationSettingsDialog()
                }
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
                setReplyState(msg)
            }
            MessageActionBottomSheet.ActionType.EditMessage -> {
                setEditState(msg)
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

    private fun setReplyState(msg: MessageEntity) {
        replyingToMessage = msg
        val label = "${getString(R.string.message_chatbox_replying_to)} ${msg.senderName}"
        replyNameView?.text = label
        replyBar?.visibility = View.VISIBLE
        inputField.requestFocus()
        AndroidUtilities.showKeyboard(inputField)
    }

    private fun clearReplyState() {
        replyingToMessage = null
        replyBar?.visibility = View.GONE
        replyNameView?.text = ""
    }

    private fun setEditState(msg: MessageEntity) {
        clearReplyState()
        editingMessage = msg
        editNameView?.text = getString(R.string.message_chatbox_editing)
        editBar?.visibility = View.VISIBLE
        val text = parseContentText(msg.content)
        inputField.setText(text)
        inputField.setSelection(text.length)
        inputField.requestFocus()
        AndroidUtilities.showKeyboard(inputField)
    }

    private fun clearEditState() {
        editingMessage = null
        editBar?.visibility = View.GONE
        editNameView?.text = ""
        inputField.text?.clear()
    }

    private fun applyRealId(tempId: Long, realId: Long) {
        val idx = messages.indexOfFirst { it.id == tempId }
        if (idx < 0) {
            Log.d(TAG, "applyRealId tempId=$tempId not found")
            return
        }
        val old = messages[idx]
        messagesDict.delete(tempId)
        val updated = old.copy(id = realId, sendState = MessageEntity.SEND_STATE_SENT)
        messages[idx] = updated
        messagesDict.put(realId, updated)
        Log.d(TAG, "applyRealId tempId=$tempId → realId=$realId")
        if (fragmentView == null) return
        for (i in 0 until recyclerView.childCount) {
            val cell = recyclerView.getChildAt(i) as? ChatMessageCell ?: continue
            if (cell.messageEntity?.id == tempId) {
                cell.update(NotificationCenter.UPDATE_MASK_SEND_STATE, updated)
                break
            }
        }
    }

    private fun markMessageSent(tempId: Long) {
        val idx = messages.indexOfFirst { it.id == tempId }
        if (idx < 0) return
        val old = messages[idx]
        val updated = old.copy(sendState = MessageEntity.SEND_STATE_SENT)
        messages[idx] = updated
        messagesDict.put(tempId, updated)
        if (fragmentView == null) return
        for (i in 0 until recyclerView.childCount) {
            val cell = recyclerView.getChildAt(i) as? ChatMessageCell ?: continue
            if (cell.messageEntity?.id == tempId) {
                cell.update(NotificationCenter.UPDATE_MASK_SEND_STATE, updated)
                break
            }
        }
    }

    private fun buildReplyReferences(): List<com.mezon.mezon.api.MessageRef>? {
        val target = replyingToMessage ?: return null
        val ref = com.mezon.mezon.api.messageRef {
            messageId = 0L
            messageRefId = target.id
            refType = 0
            messageSenderId = target.senderId
            messageSenderUsername = target.senderName
            messageSenderAvatar = target.senderAvatar
            messageSenderDisplayName = target.senderName
            content = target.content
            hasAttachment = target.hasMedia || target.isFileAttachment
        }
        return listOf(ref)
    }

    private var pendingHighlightMessageId = 0L

    private fun scrollToReplyMessage(messageId: Long) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            scrollToAndHighlight(idx)
        } else {
            Log.d(TAG, "Reply message $messageId not in list, calling loadMessagesAround")
            pendingHighlightMessageId = messageId
            chatController.loadMessagesAround(channelId, clanId, messageId, requireExactAnchor = true)
        }
    }

    private fun scrollToAndHighlight(idx: Int) {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val adapterPos = adapter.messagesStartRow + idx
        lm.scrollToPositionWithOffset(adapterPos, recyclerView.height / 3)
        recyclerView.post {
            val vh = recyclerView.findViewHolderForAdapterPosition(adapterPos)
            (vh?.itemView as? ChatMessageCell)?.setHighlight()
        }
    }

    private fun checkMentionTrigger() {
        val text = inputField.text?.toString() ?: ""
        val cursor = inputField.selectionStart
        if (cursor <= 0 || text.isEmpty()) {
            hideMentionsPopup()
            return
        }

        var query: String? = null
        var atPos = -1
        for (a in (cursor - 1) downTo 0) {
            if (a >= text.length) continue
            val ch = text[a]
            if (ch == '@') {
                if (a == 0 || text[a - 1] == ' ' || text[a - 1] == '\n') {
                    atPos = a
                    query = text.substring(a + 1, cursor)
                    break
                }
            }
            if (ch == ' ' || ch == '\n') break
        }

        if (query != null && atPos >= 0) {
            mentionAtPosition = atPos
            mentionQueryLength = query.length + 1
            val members = resolveMentionMembers()
            mentionsAdapter?.search(query, members)
            if ((mentionsAdapter?.itemCount ?: 0) > 0) {
                mentionsPopup?.updateVisibility(true)
            } else {
                hideMentionsPopup()
            }
        } else {
            hideMentionsPopup()
        }
    }

    private fun hideMentionsPopup() {
        mentionsPopup?.updateVisibility(false)
        mentionAtPosition = -1
        mentionQueryLength = 0
    }

    private fun onMentionSelected(item: MentionSuggestionItem) {
        val editable = inputField.text ?: return
        val atPos = mentionAtPosition
        if (atPos < 0) return

        val replaceEnd = minOf(atPos + mentionQueryLength, editable.length)

        when (item) {
            is MentionSuggestionItem.Here -> {
                val mentionText = "@here "
                editable.replace(atPos, replaceEnd, mentionText)
                val spanStart = atPos
                val spanEnd = atPos + mentionText.trimEnd().length
                editable.setSpan(
                    android.text.style.ForegroundColorSpan(0xFF5865F2.toInt()),
                    spanStart, spanEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                inputField.setSelection(atPos + mentionText.length)
                mentionTrackers.add(MentionData(
                    userId = ChatController.ID_MENTION_HERE,
                    startOffset = spanStart,
                    endOffset = spanEnd
                ))
            }
            is MentionSuggestionItem.Member -> {
                val member = item.member
                val displayName = member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
                val mentionText = "@$displayName "
                editable.replace(atPos, replaceEnd, mentionText)
                val spanStart = atPos
                val spanEnd = atPos + mentionText.trimEnd().length
                editable.setSpan(
                    android.text.style.ForegroundColorSpan(0xFF5865F2.toInt()),
                    spanStart, spanEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                inputField.setSelection(atPos + mentionText.length)
                mentionTrackers.add(MentionData(
                    userId = member.userId.toString(),
                    startOffset = spanStart,
                    endOffset = spanEnd
                ))
            }
        }
        hideMentionsPopup()
    }
}
