package com.mezon.mobile.home.chat

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.view.TouchDelegate
import android.util.Log
import android.util.LongSparseArray
import android.view.Gravity
import android.view.MotionEvent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.TopicBadgeTracker
import com.mezon.mobile.home.TopicController
import com.mezon.mobile.home.chat.thread.CreateThreadFragment
import com.mezon.mobile.home.chat.CreateThreadSeedStash
import com.mezon.mobile.home.chat.thread.ThreadListFragment
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.LOAD_TYPE_INITIAL
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.friends.FRIEND_STATE_BLOCKED
import com.mezon.mobile.home.friends.FRIEND_STATE_FRIEND
import com.mezon.mobile.home.friends.FRIEND_STATE_INVITE_SENT
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.call.CallFragment
import com.mezon.mobile.util.ShareContactData
import com.mezon.mobile.util.isShareContactMessage
import com.mezon.mobile.home.clans.CLAN_CREATE_LIMIT
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClanRole
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.clans.UserDisplayRole
import com.mezon.mobile.home.clans.isEveryoneRole
import com.mezon.mobile.home.chat.input.InputSuggestionItem
import com.mezon.mobile.home.chat.input.InputSuggestionsAdapter
import com.mezon.mobile.home.chat.input.InputSuggestionsController
import com.mezon.mobile.home.chat.input.InputSuggestionsPopup
import com.mezon.mobile.home.chat.input.VoiceRecorder
import com.mezon.mobile.home.chat.input.VoiceRecordingOverlay
import com.mezon.mobile.home.sharing.SharingFragment
import com.mezon.mobile.home.sharing.SharingPayload
import com.mezon.mobile.home.clans.ChannelItemCell
import com.mezon.mobile.home.clans.CHANNEL_TYPE_APP
import com.mezon.mobile.home.clans.CHANNEL_TYPE_STREAMING
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.clans.VoiceMemberDisplay
import com.mezon.mobile.home.clans.channelapp.ChannelAppController
import com.mezon.mobile.home.clans.channelapp.ChannelAppFragment
import com.mezon.mobile.home.voice.JoinVoiceBottomSheet
import com.mezon.mobile.home.voice.VoiceController
import com.mezon.mobile.wallet.WalletController
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.wallet.SendTokenFragment
import com.mezon.mobile.MainActivity
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.sanitizeServerMessageId as sanitizeProvisionalId
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.ColoredImageSpan
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.PageDownButton
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.FileUtils
import com.mezon.mobile.util.EmojiMarker
import com.mezon.mobile.util.HashtagData
import com.mezon.mobile.util.MentionData
import com.mezon.mobile.util.parseContentText
import com.mezon.mobile.util.parseMarkdownAndStrip
import com.mezon.mobile.util.restoreInputFromContent
import com.mezon.mobile.util.resolveStickerSourceUrl
import com.mezon.mobile.util.firstReferenceMessageId
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.util.InputOgpFetcher
import com.mezon.mobile.util.InputOgpPreview
import com.mezon.mobile.util.OgpMarker
import com.mezon.mobile.util.PresignFinishContent
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.home.chat.poll.ChatPollBridge
import com.mezon.mobile.home.chat.poll.CreatePollFragment
import com.mezon.mobile.home.chat.poll.ParsedPoll
import com.mezon.mobile.home.chat.poll.PollDetailModal
import com.mezon.mobile.home.chat.poll.PollLocalState
import com.mezon.mobile.home.chat.poll.PollSubmitPayload
import com.mezon.mobile.home.chat.poll.PollPrimaryIntent
import com.mezon.mobile.home.chat.poll.PollTap
import com.mezon.mobile.home.chat.poll.PollVotePersistence
import com.mezon.mobile.home.chat.poll.resolvePollPrimaryIntent
import com.mezon.mobile.home.chat.poll.canCreatePoll
import com.mezon.mobile.home.chat.poll.mergePollFromGetResponse
import com.mezon.mobile.home.chat.poll.parsePollContent
import com.mezon.mobile.home.chat.poll.votedAnswerIndices
import com.mezon.mobile.home.call.CallManager
import com.mezon.mobile.home.call.CallPermissionUi
import com.mezon.mobile.home.call.parseCallLogMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.mezon.mobile.core.SizeNotifierFrameLayout
import com.mezon.mobile.home.chat.emoji.EmojiView
import com.mezon.mobile.util.EmbedFormUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ChatFragment"
private const val FORWARD_NEARBY_WINDOW_SECONDS = 10 * 60L

/** Soft realtime when BE does not push ChatUpdate for every poll vote — GetPoll on visible rows. */
private const val POLL_TALLY_TICK_MS = 15_000L
private const val POLL_TALLY_MIN_GAP_MS = 12_000L
private const val POLL_TALLY_MAX_PER_TICK = 8
private const val TOPIC_BADGE_HYDRATE_DEBOUNCE_MS = 500L
private val AT_HERE_INPUT_REGEX = Regex("(?<!\\w)@here(?!\\w)")

open class ChatFragment : BaseFragment() {

    companion object {
        internal const val ARG_CHANNEL_ID = "channelId"
        internal const val ARG_CHANNEL_NAME = "channelName"
        internal const val ARG_CLAN_ID = "clanId"
        internal const val ARG_CHANNEL_TYPE = "channelType"
        internal const val ARG_CHANNEL_PRIVATE = "channelPrivate"
        internal const val ARG_CHANNEL_AGE_RESTRICTED = "channelAgeRestricted"
        internal const val ARG_TOPIC_ID = "topicId"
        internal const val ARG_ROOT_MESSAGE_ID = "rootMessageId"
        private const val ARG_PARENT_ID = "parentId"
        private const val ARG_MESSAGE_ID = "message_id"
        private const val ARG_FORCE_LATEST = "force_latest"
        internal const val ARG_OPENED_FROM_NOTIFICATION = "opened_from_notification"
        private const val VIEWPORT_LIMIT = 300
        private const val PAGE_DOWN_SCROLL_THRESHOLD = 2
        private const val REQUEST_CODE_LOCATION_PERMISSION = 1002
        private const val REQUEST_CODE_PICK_FILE = 1005
        private const val REQUEST_CODE_RECORD_AUDIO = 1003
        private const val MAX_LENGTH_MESSAGE_BUZZ = 160
        private const val VOICE_LONG_PRESS_DELAY_MS = 400L
        private const val VOICE_CANCEL_SLIDE_DP = 100f
        private const val GIVE_COFFEE_AMOUNT_HUMAN = "10000"
        private const val GIVE_COFFEE_AMOUNT_DISPLAY = "10,000"
        private const val GIVE_COFFEE_NOTE = "givecoffee"
        private const val GIVE_COFFEE_SEPARATOR = " | "
        private const val GIVE_COFFEE_EMOJI_ID = 7280417126303261185L
        private const val GIVE_COFFEE_EMOJI = ":coffee:"
        private const val LOADING_INDICATOR_DELAY_MS = 300L
        private val ANONYMOUS_USER_ID = BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: 0L
        private const val REQUEST_CALL_PERMISSIONS = 9002
        private const val MENU_DM_VOICE_CALL = 8801
        private const val DM_HEADER_CALL_ICON_DP = 22f
        private val INPUT_OGP_IMAGE_SIZE = LayoutHelper.dp(40f)
        private val INPUT_OGP_BAR_CORNER = LayoutHelper.dp(12f).toFloat()
        private val INPUT_OGP_URL_REGEX = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
        private const val INPUT_OGP_DEBOUNCE_MS = 80L
        private const val INPUT_OGP_CACHE_MAX = 32
        private const val INPUT_OGP_SEND_WAIT_MS = 400L
        private val inputOgpCache = object : LinkedHashMap<String, InputOgpPreview>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, InputOgpPreview>): Boolean = size > INPUT_OGP_CACHE_MAX
        }

        fun newInstance(
            channelId: Long,
            channelName: String,
            clanId: Long = 0L,
            channelType: Int = 0,
            messageId: Long = 0L,
            forceLatest: Boolean = false,
            isChannelPrivate: Boolean = false,
            isChannelAgeRestricted: Boolean = false,
            parentId: Long = 0L,
            openedFromNotification: Boolean = false
        ): ChatFragment = ChatFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putString(ARG_CHANNEL_NAME, channelName)
                putLong(ARG_CLAN_ID, clanId)
                putInt(ARG_CHANNEL_TYPE, channelType)
                if (isChannelPrivate) putBoolean(ARG_CHANNEL_PRIVATE, true)
                if (isChannelAgeRestricted) putBoolean(ARG_CHANNEL_AGE_RESTRICTED, true)
                if (parentId != 0L) putLong(ARG_PARENT_ID, parentId)
                if (messageId != 0L) putLong(ARG_MESSAGE_ID, messageId)
                if (forceLatest) putBoolean(ARG_FORCE_LATEST, true)
                if (openedFromNotification) putBoolean(ARG_OPENED_FROM_NOTIFICATION, true)
            }
        }
    }

    private lateinit var chatController: ChatController
    private lateinit var dialogsController: DialogsController
    private lateinit var channelController: ChannelController
    private lateinit var mediaController: MediaController
    private lateinit var channelGalleryController: com.mezon.mobile.home.ChannelGalleryController
    private lateinit var audioPlayerController: AudioPlayerController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var pinMessageController: com.mezon.mobile.home.PinMessageController
    private lateinit var walletController: WalletController
    private lateinit var accountController: AccountController
    private lateinit var callController: com.mezon.mobile.home.call.CallController
    private lateinit var callManager: CallManager
    private lateinit var friendController: FriendController

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
    private var channelAppHotbar: LinearLayout? = null
    private lateinit var inputWrapper: FrameLayout
    private lateinit var pageDownButton: PageDownButton
    private lateinit var unreadDecoration: UnreadDividerDecoration
    private var attachmentPreviewStrip: LinearLayout? = null
    private var attachmentPreviewScroll: HorizontalScrollView? = null

    private lateinit var emojiController: EmojiController
    private lateinit var anonymousController: com.mezon.mobile.home.AnonymousController
    private var anonymousIndicator: ImageView? = null
    private var emojiView: EmojiView? = null
    private var emojiViewVisible = false
    private var emojiPadding = 0
    private var emojiSearchExpanded = false
    private var searchKeyboardWasVisible = false
    private val emojiObjPicked = HashMap<String, String>()
    private lateinit var sizeNotifierRoot: SizeNotifierFrameLayout

    private val pendingAttachments = ArrayList<AttachmentPickerItem>()
    private var mediaPermissionDeniedOnce = false
    private var locationPermissionAskedBefore = false
    private val pendingAttachmentThumbTasks = ArrayList<Runnable?>()
    private var attachmentProgressReloadRunnable: Runnable? = null
    private var buzzMediaPlayer: android.media.MediaPlayer? = null

    private var voiceRecorder: VoiceRecorder? = null
    private var voiceOverlay: VoiceRecordingOverlay? = null
    private var voiceTouchDownX = 0f
    private var voiceIsRecording = false
    private var voiceCancelled = false
    private var voiceLongPressFired = false
    private val voiceLongPressRunnable = Runnable { onVoiceLongPressFired() }

    private var channelId = 0L
    private var topicId = 0L
    private var rootMessageId = 0L
    private var topicRootHeader: TopicRootHeaderView? = null
    private var activePhotoViewer: PhotoViewer? = null
    private var photoViewerSelectedUrl = ""
    private var cachedTopicRootMessage: MessageEntity? = null
    private val messageListKey: Long
        get() = if (topicId != 0L) topicId else channelId
    private val isTopicMode: Boolean
        get() = topicId != 0L
    private var channelName = ""
    private var lastWelcomeAvatarUrl = ""
    private var lastWelcomeAvatarId = 0L
    private var lastWelcomePlaceholderKey = ""
    private var lastWelcomePeerUsername = ""
    private var lastWelcomeChannelName = ""
    private var clanId = 0L
    private var chatCachedCreatorId: Long? = null
    private var displayRoleCacheRefreshJob: Job? = null
    private var pendingDisplayRoleUiRefresh = false
    private var channelType = 0
    private var routeChannelPrivate = false
    private var routeChannelAgeRestricted = false
    private var routeParentId = 0L
    private var forceLatest = false
    private var openedFromNotification = false
    private var startLoadFromMessageId = 0L
    private var startLoadFromMessageOffset = Int.MAX_VALUE
    private var pausedOnLastMessage = false
    private var needScrollRestore = false
    private var isLoading = false
    private var isLoadingMore = false
    private var hasMoreTop = false
    private var hasMoreBottom = false
    private var isViewingOlder = false
    private var scrollingManually = false
    private var pendingPartialUpdateMask = 0
    private var pendingFullVisibleUpdate = false
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
    private var waitingForKeyboardOpen = false
    private var lastResumeTime = 0L
    private val openKeyboardRunnable = object : Runnable {
        override fun run() {
            if (!waitingForKeyboardOpen || isPaused) return
            AndroidUtilities.showKeyboard(inputField)
            AndroidUtilities.runOnUIThread(this, 100)
        }
    }

    private val sentByApiRealIds = HashSet<Long>()

    private var replyingToMessage: MessageEntity? = null
    private var replyBar: LinearLayout? = null
    private var replyNameView: TextView? = null
    private var replyCloseButton: ImageButton? = null

    private var editingMessage: MessageEntity? = null
    private var editBar: LinearLayout? = null
    private var editNameView: TextView? = null
    private var editCloseButton: ImageButton? = null
    private var inputOgpBar: LinearLayout? = null
    private var inputOgpImage: ImageView? = null
    private var inputOgpTitle: TextView? = null
    private var inputOgpDesc: TextView? = null
    private var inputOgpClose: ImageButton? = null
    private var inputOgpUrl: String? = null
    private var inputOgpPreviewData: InputOgpPreview? = null
    private var dismissedInputOgpUrl: String? = null
    private var failedInputOgpUrl: String? = null
    private var inputOgpFetchJob: Job? = null
    private var inputOgpImageLoad: MezonImageLoader.Cancellable = MezonImageLoader.Cancellable.EMPTY
    private var inputOgpThumbnailUrl: String? = null
    private var awaitingOgpForSend = false

    private lateinit var userClanController: UserClanController
    private lateinit var userController: com.mezon.mobile.home.profile.UserController
    private lateinit var memberResolver: MemberResolver
    private lateinit var topicController: TopicController
    private lateinit var topicBadgeTracker: TopicBadgeTracker
    private var topicBadgeHydrateJob: Job? = null
    private lateinit var roleController: RoleController
    private lateinit var searchController: com.mezon.mobile.search.SearchController
    private lateinit var voiceController: VoiceController
    private lateinit var channelAppController: ChannelAppController
    private lateinit var clansController: ClansController
    private lateinit var mezonApi: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var appScope: CoroutineScope
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher
    private lateinit var imageClipboardCoordinator: ImageClipboardCoordinator
    private var pasteImagePopup: PopupWindow? = null
    private var suggestionsPopup: InputSuggestionsPopup? = null
    private var suggestionsAdapter: InputSuggestionsAdapter? = null
    private val mentionTrackers = mutableListOf<MentionData>()
    private val hashtagTrackers = mutableListOf<HashtagData>()
    private var suppressInputTrackerMutation = false
    private var systemMessageMemberIds: Set<String> = emptySet()
    private var currentTrigger: InputSuggestionsController.TriggerState = InputSuggestionsController.TriggerState.NONE

    private var slidingView: ChatMessageCell? = null
    private var maybeStartTrackingSlidingView = false
    private var startedTrackingSlidingView = false
    private var startedTrackingX = 0
    private var startedTrackingY = 0
    private var startedTrackingPointerId = -1

    private val messages = ArrayList<MessageEntity>()
    private val messagesDict = LongSparseArray<MessageEntity>()
    private val pollStates = mutableMapOf<Long, PollLocalState>()
    private val createPollInFlight = AtomicBoolean(false)
    private var pollTallyRefreshJob: Job? = null
    private val pollTallyLastRequestedAtMs = ConcurrentHashMap<Long, Long>()
    private var transitionAnimationIndex = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var showLoadingPending = false
    private val showLoadingRunnable = Runnable {
        showLoadingPending = false
        if (isLoading && messages.isEmpty() && fragmentView != null) {
            recyclerView.visibility = View.INVISIBLE
            errorView.visibility = View.GONE
            loadingView.visibility = View.VISIBLE
        }
    }
    private var pendingSeenMessageId = 0L
    private var pendingSeenTimestamp = 0
    private var pendingBadgeCount = 0
    private val markVisibleRunnable = Runnable { flushPendingSeen() }
    private var inviteJoinPendingClanId = 0L
    private var inviteJoinClansObserver: NotificationCenter.NotificationCenterDelegate? = null
    private var inviteJoinTimeout: Runnable? = null

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
        topicId = arguments?.getLong(ARG_TOPIC_ID) ?: 0L
        rootMessageId = arguments?.getLong(ARG_ROOT_MESSAGE_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME) ?: ""
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        chatCachedCreatorId = null
        channelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: 0
        routeChannelPrivate = arguments?.getBoolean(ARG_CHANNEL_PRIVATE) ?: false
        routeChannelAgeRestricted = arguments?.getBoolean(ARG_CHANNEL_AGE_RESTRICTED) ?: false
        routeParentId = arguments?.getLong(ARG_PARENT_ID) ?: 0L
        forceLatest = arguments?.getBoolean(ARG_FORCE_LATEST) ?: false
        openedFromNotification = arguments?.getBoolean(ARG_OPENED_FROM_NOTIFICATION) ?: false
        if (clanId != 0L) {
            val cachedEntity = channelController.findChannelById(channelId, clanId)
                ?: channelController.findChannelById(channelId)
            val cachedName = cachedEntity?.channelLabel
            if (!cachedName.isNullOrBlank()) {
                channelName = cachedName
            }
        }
        startLoadFromMessageId = 0L
        startLoadFromMessageOffset = Int.MAX_VALUE
        needScrollRestore = false

        val readStateChannelId = if (isTopicMode && topicId != 0L) topicId else channelId
        if (clanId == 0L) {
            val dm = dialogsController.getDialog(readStateChannelId)
            lastSeenMessageId = dm?.lastSeenMessageId ?: 0L
            lastSentMessageId = dm?.lastSentMessageId ?: 0L
        } else {
            val ch = channelController.findChannelById(readStateChannelId)
            lastSeenMessageId = ch?.lastSeenMessageId ?: 0L
            lastSentMessageId = ch?.lastSentMessageId ?: 0L
        }
        lastSeenMessageId = sanitizeProvisionalId(lastSeenMessageId)
        lastSentMessageId = sanitizeProvisionalId(lastSentMessageId)
        dividerSeenMessageId = lastSeenMessageId
        val isSeenUpToDate = lastSentMessageId == 0L || lastSeenMessageId >= lastSentMessageId
        hasUnread = !isSeenUpToDate && lastSeenMessageId != 0L

        if (clanId != 0L) {
            userClanController.loadClanMembers(clanId)
            roleController.loadRolesForClan(clanId)
            appScope.launch(ioDispatcher) {
                roleController.hydrateLocalPermissionSnapshotForClan(clanId)
            }
            val ch = channelController.findChannelById(channelId)
            val effectiveParentId = ch?.parentId ?: routeParentId
            val effectivePrivate = ch?.isPrivate ?: routeChannelPrivate
            val shouldLoadScopedMembers =
                channelType == CHANNEL_TYPE_THREAD || effectivePrivate || effectiveParentId != 0L
            if (shouldLoadScopedMembers) {
                if (effectiveParentId != 0L) {
                    userClanController.loadChannelMembers(clanId, effectiveParentId, CHANNEL_TYPE_CHANNEL)
                    Log.d(TAG, "onFragmentCreate loadChannelMembers parent clanId=$clanId parentId=$effectiveParentId")
                }
                userClanController.loadChannelMembers(clanId, channelId, CHANNEL_TYPE_CHANNEL)
                Log.d(TAG, "onFragmentCreate loadChannelMembers channel clanId=$clanId channelId=$channelId")
            }
        } else if (channelType == CHANNEL_TYPE_GROUP) {
            dialogsController.loadDmParticipants(channelId)
        }
        if (clanId != 0L && !isTopicMode) {
            topicController.loadTopics(clanId)
        }
        if (isTopicMode) {
            loadTopicRootHeaderMessage()
        }
        refreshPermissionGates()
        Log.d(TAG, "onFragmentCreate: startLoadFromMessageId=$startLoadFromMessageId forceLatest=$forceLatest channelId=$channelId")
        observe(NotificationCenter.channelPermissionOverridesDidLoad) { _, _, args ->
            val changedChannelId = args.getOrNull(0) as? Long ?: return@observe
            if (changedChannelId == channelId) refreshPermissionGates()
        }
        observe(NotificationCenter.channelPermissionsDidLoad) { _, _, args ->
            val changedChannelId = args.getOrNull(0) as? Long ?: return@observe
            if (changedChannelId == channelId) refreshPermissionGates()
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            val changedClanId = args.getOrNull(0) as? Long ?: return@observe
            if (changedClanId == clanId || changedClanId == clansController.selectedClanId.value) refreshPermissionGates()
            if (changedClanId == clanId) {
                refreshChatDisplayRoleCache(refreshUi = !isPaused)
            }
        }
        observe(NotificationCenter.selectedClanChanged) { _, _, _ ->
            if (isPaused) return@observe
            if (clanId == 0L) refreshPermissionGates()
        }
        observe(NotificationCenter.notificationsDidLoad) { _, _, args ->
            if (isPaused || isTopicMode || clanId == 0L || fragmentView == null) return@observe
            val category = args.getOrNull(0) as? Int ?: return@observe
            if (category != com.mezon.mobile.home.notifications.NOTIF_CATEGORY_MENTIONS) return@observe
            requestTopicBadgeHydrate(TOPIC_BADGE_HYDRATE_DEBOUNCE_MS)
        }
        observe(NotificationCenter.messagesDidLoad) { _, _, args ->
            if (args.size < 5) return@observe
            val eventKey = args[0] as? Long ?: return@observe
            if (eventKey != messageListKey) return@observe
            @Suppress("UNCHECKED_CAST")
            val loadedMessages = args[1] as? ArrayList<MessageEntity> ?: return@observe
            val moreTop = args[2] as? Boolean ?: false
            val moreBottom = args[3] as? Boolean ?: false
            val isCache = args[4] as? Boolean ?: false
            val serverLastSeenId = args.getOrNull(5) as? Long ?: 0L
            val loadType = args.getOrNull(6) as? Int ?: LOAD_TYPE_INITIAL
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
                sortMessagesByIdDesc(newMessages)
                newRowsCount = newMessages.size

                if (direction == 1) {
                    messages.addAll(newMessages)
                    sortMessagesByIdDesc(messages)
                    hasMoreTop = moreTop
                    trimViewportNewest()
                } else {
                    messages.addAll(0, newMessages)
                    sortMessagesByIdDesc(messages)
                    val newestReadableId = newestReadStateMessageId()
                    hasMoreBottom = if (lastSentMessageId != 0L && newestReadableId != 0L) {
                        newestReadableId < lastSentMessageId
                    } else {
                        moreBottom
                    }
                    trimViewportOldest()
                    if (!hasMoreBottom) {
                        isViewingOlder = false
                    }
                    updatePageDownVisibility()
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
                    updateUnreadDividerPosition()

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
                    updateUnreadDividerPosition()
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
                    sortMessagesByIdDesc(all)
                    messages.addAll(all)
                    pruneEmbedFormState()
                }
                hasMoreTop = moreTop
                hasMoreBottom = moreBottom
            } else {
                if (loadedMessages.isEmpty() && messages.isNotEmpty()) {
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
                    val loadedIds = loadedMessages.asSequence().map { it.id }.toHashSet()
                    val outgoingPending = messages.filter {
                        it.isMe && (it.isSending || (it.isPollMessage && it.id !in loadedIds))
                    }

                    if (hasOverlap) {
                        for (m in loadedMessages) messagesDict.put(m.id, m)
                    } else {
                        messagesDict.clear()
                        for (m in loadedMessages) messagesDict.put(m.id, m)
                    }
                    for (m in outgoingPending) {
                        if (messagesDict.get(m.id) == null) messagesDict.put(m.id, m)
                    }
                    for (m in chatController.getActivePendingAttachmentMessages(messageListKey)) {
                        messagesDict.put(m.id, m)
                    }
                    messages.clear()
                    val all = ArrayList<MessageEntity>(messagesDict.size())
                    for (i in 0 until messagesDict.size()) all.add(messagesDict.valueAt(i))
                    sortMessagesByIdDesc(all)
                    messages.addAll(all)
                    pruneEmbedFormState()
                    hasMoreTop = moreTop
                    hasMoreBottom = moreBottom

                    hasUnread = savedHasUnread
                    lastSeenMessageId = savedLastSeen
                }
            }

            val fromStore = chatController.getLastMessageId(messageListKey)
            lastSentMessageId = maxOf(lastSentMessageId, fromStore)

            val newestReadableInList = newestReadStateMessageId()
            if (newestReadableInList != 0L) {
                lastSentMessageId = maxOf(lastSentMessageId, newestReadableInList)
            }

            if (!jumpingToPresent && !isCache) {
                if (!hasUnread && lastSentMessageId != 0L && lastSeenMessageId != 0L
                    && lastSeenMessageId < lastSentMessageId) {
                    hasUnread = true
                    if (dividerSeenMessageId == 0L) dividerSeenMessageId = lastSeenMessageId
                    Log.d(TAG, "hasUnread re-evaluated to TRUE: lastSeen=$lastSeenMessageId < lastSent=$lastSentMessageId dividerSeen=$dividerSeenMessageId")
                }

                if (lastSentMessageId != 0L && newestReadableInList != 0L) {
                    if (newestReadableInList < lastSentMessageId) {
                        hasMoreBottom = true
                    }
                }
            }

            if (loadType == LOAD_TYPE_INITIAL && !moreBottom) {
                hasMoreBottom = false
            }

            if (!hasUnread && lastSeenMessageId != 0L && messages.isNotEmpty()) {
                val newestInList = newestReadStateMessageId()
                if (newestInList > lastSeenMessageId && messages.any { it.id == lastSeenMessageId }) {
                    hasUnread = true
                }
            }

            val wasFirstLoad = firstLoad
            if (loadType == LOAD_TYPE_INITIAL && wasFirstLoad) {
                firstLoad = false
                notificationCenter.removePostponeNotificationsCallback(postponeNewMessagesCallback)
            }

            if (fragmentView != null) {

                if (wasFirstLoad) {
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
                    hasMoreBottom = false
                    isViewingOlder = false
                    Log.d(TAG, "jumpToPresent: API done, msgs=${messages.size}, showing list + scrollToBottom")
                    showMessages()
                    forceScrollToBottom()
                    markAsRead()
                    updatePageDownVisibility()
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
                    refreshThreadWelcomeCreator()

                    if (wasFirstLoad && !isTopicMode && clanId != 0L) {
                        requestTopicBadgeHydrate(0L)
                    }

                    if (pendingHighlightMessageId != 0L) {
                        val highlightId = pendingHighlightMessageId
                        pendingHighlightMessageId = 0L
                        val hIdx = messages.indexOfFirst { it.id == highlightId }
                        Log.d(TAG, "pendingHighlight: id=$highlightId idx=$hIdx msgsSize=${messages.size}")
                        if (hIdx >= 0) {
                            val newestInList = newestReadStateMessageId()
                            if (lastSentMessageId != 0L && newestInList < lastSentMessageId) {
                                isViewingOlder = true
                                hasMoreBottom = true
                            }
                            updatePageDownVisibility()
                            recyclerView.post { scrollToAndHighlight(hIdx) }
                        } else if (!isCache) {
                            scrollToReplyMessage(highlightId)
                        }
                    } else if (forceLatest && wasFirstLoad) {
                        forceScrollToBottom()
                        markAsRead()
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
            if (args.size < 2) return@observe
            val eventChannelId = args[0] as? Long ?: return@observe
            if (eventChannelId != messageListKey) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "didReceiveNewMessages skip channel mismatch current=$channelId event=$eventChannelId"
                    )
                }
                return@observe
            }
            val entity = args[1] as? MessageEntity ?: return@observe
            if (BuildConfig.DEBUG) {
                val refId = debugReferencedMessageId(entity.content)
                val refIdx = if (refId != 0L) messages.indexOfFirst { it.id == refId } else -1
                val refMsg = if (refIdx >= 0) messages[refIdx] else if (refId != 0L) messagesDict.get(refId) else null
                Log.d(
                    TAG,
                    "didReceiveNewMessages id=${entity.id} channel=${entity.channelId} code=${entity.code} " +
                        "ts=${entity.timestampSeconds} ref=$refId refIdx=$refIdx refTs=${refMsg?.timestampSeconds ?: 0L} " +
                        "dict=${messagesDict.get(entity.id) != null} isMe=${entity.isMe} isSending=${entity.isSending} " +
                        "isViewingOlder=$isViewingOlder hasMoreBottom=$hasMoreBottom paused=$isPaused " +
                        "first=${debugMessageAt(0)} last=${debugMessageAt(messages.lastIndex)} " +
                        "content=${debugMessagePreview(entity.content)}"
                )
            }
            if (entity.isSending) {
                val insertIndex = insertSendingOptimisticMessage(entity)
                if (fragmentView != null) {
                    if (messages.size == 1) {
                        refreshUI()
                    } else {
                        cancelPendingLoading()
                        loadingView.visibility = View.GONE
                        errorView.visibility = View.GONE
                        if (recyclerView.visibility != View.VISIBLE && !needScrollRestore) {
                            recyclerView.visibility = View.VISIBLE
                        }
                        adapter.notifyMessageInsertedAt(insertIndex)
                        updateUnreadDividerPosition()
                    }
                }
                if (isViewingOlder || hasMoreBottom) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "didReceiveNewMessages sending jumpToPresent id=${entity.id} index=$insertIndex"
                        )
                    }
                    jumpToPresent()
                } else if (fragmentView != null) {
                    forceScrollToBottom()
                }
                return@observe
            }
            if (mergeDuplicateIncomingMessage(entity)) {
                sentByApiRealIds.remove(entity.id)
                return@observe
            }

            if (entity.isMe) {
                val pendingIdx = findPendingSelfEchoIndex(entity)
                if (pendingIdx >= 0) {
                    val pending = messages[pendingIdx]
                    applyRealId(pending.id, entity.id, entity)
                    return@observe
                }
            }

            if (entity.senderId == ANONYMOUS_USER_ID) {
                val pendingIdx = messages.indexOfFirst {
                    it.isSending && it.senderId == ANONYMOUS_USER_ID && it.content == entity.content
                }
                if (pendingIdx >= 0) {
                    val old = messages[pendingIdx]
                    messagesDict.delete(old.id)
                    messages[pendingIdx] = entity
                    messagesDict.put(entity.id, entity)
                    if (fragmentView != null) refreshUI()
                    return@observe
                }
            }

            if (mergeDuplicateIncomingMessage(entity)) return@observe
            if (entity.id > lastSentMessageId) lastSentMessageId = entity.id

            if (isViewingOlder) {
                newUnreadCount++
                hasMoreBottom = true
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "didReceiveNewMessages buffer because viewingOlder id=${entity.id} unread=$newUnreadCount"
                    )
                }
                if (::pageDownButton.isInitialized) {
                    pageDownButton.setUnreadCount(newUnreadCount)
                    pageDownButton.show(true)
                }
                return@observe
            }
            messagesDict.put(entity.id, entity)
            val insertIndex = insertIndexForMessage(entity)
            if (referencedEmbedResponseInsertIndex(entity) >= 0 && BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "didReceiveNewMessages anchored embed response id=${entity.id} " +
                        "ref=${referencedMessageId(entity.content)} index=$insertIndex"
                )
            }
            messages.add(insertIndex, entity)
            refreshThreadWelcomeCreator()
            val trimmed = trimViewportOldest()
            if (fragmentView != null) {
                if (messages.size == 1 || trimmed) {
                    refreshUI()
                } else {
                    cancelPendingLoading()
                    loadingView.visibility = View.GONE
                    errorView.visibility = View.GONE
                    if (recyclerView.visibility != View.VISIBLE && !needScrollRestore) {
                        recyclerView.visibility = View.VISIBLE
                    }
                    adapter.notifyMessageInsertedAt(insertIndex)
                    updateUnreadDividerPosition()
                }
                if (entity.isMe) forceScrollToBottom() else scrollToBottom()
            }
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "didReceiveNewMessages inserted id=${entity.id} ts=${entity.timestampSeconds} " +
                        "ref=${debugReferencedMessageId(entity.content)} index=$insertIndex size=${messages.size} " +
                        "prev=${debugMessageAt(insertIndex - 1)} self=${debugMessageAt(insertIndex)} " +
                        "next=${debugMessageAt(insertIndex + 1)}"
                )
            }
            if (!isPaused) markAsRead()
        }
        observe(NotificationCenter.pendingMessageSent) { _, _, args ->
            if (args.size < 3 || args[0] != messageListKey) return@observe
            val tempId = args[1] as? Long ?: return@observe
            val apiRealId = args[2] as? Long ?: return@observe
            Log.d(TAG, "pendingMessageSent tempId=$tempId apiRealId=$apiRealId")
            if (apiRealId != 0L) {
                sentByApiRealIds.add(apiRealId)
                applyRealId(tempId, apiRealId)
            } else {
                markMessageSent(tempId)
            }
        }

        observe(NotificationCenter.pendingMessageError) { _, _, args ->
            if (args.size < 2 || args[0] != messageListKey) return@observe
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

        observe(NotificationCenter.attachmentUploadProgress) { _, _, args ->
            if (isPaused || fragmentView == null) return@observe
            val key = args.firstOrNull() as? String ?: return@observe
            if (!messageListHasPendingUploadKey(key)) return@observe
            if (attachmentProgressReloadRunnable != null) return@observe
            val work = Runnable {
                attachmentProgressReloadRunnable = null
                updateVisibleRows(NotificationCenter.UPDATE_MASK_UPLOAD_PROGRESS)
            }
            attachmentProgressReloadRunnable = work
            AndroidUtilities.runOnUIThread(work, 250L)
        }

        observe(NotificationCenter.attachmentUploadFinished) { _, _, args ->
            if (isPaused || fragmentView == null) return@observe
            val cdnUrl = args.firstOrNull() as? String ?: return@observe
            if (!messageListHasAttachmentUrl(cdnUrl)) return@observe
            updateVisibleRows(NotificationCenter.UPDATE_MASK_UPLOAD_PROGRESS)
        }

        observe(NotificationCenter.messageDidUpdate) { _, _, args ->
            if (args.size < 2) return@observe
            val eventKey = args[0] as? Long ?: return@observe
            val updateEntity = args[1] as? MessageEntity ?: return@observe
            val idx = messages.indexOfFirst { it.id == updateEntity.id }
            if (idx < 0) {
                if (eventKey != messageListKey) return@observe
                return@observe
            }
            if (idx >= 0) {
                val existing = messages[idx]
                val newContent = updateEntity.content.takeIf { it.isNotBlank() } ?: existing.content
                val mask = if (args.size >= 3) args[2] as? Int ?: NotificationCenter.UPDATE_MASK_MESSAGE_TEXT else NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
                val merged = when {
                    (mask and NotificationCenter.UPDATE_MASK_TOPIC) != 0 -> existing.copy(
                        content = newContent,
                        updateTimeSeconds = updateEntity.updateTimeSeconds,
                        hideEditted = updateEntity.hideEditted,
                        code = updateEntity.code,
                        topicId = updateEntity.topicId,
                        topicCreatorId = updateEntity.topicCreatorId,
                        rplCount = updateEntity.rplCount,
                        lastSentSeconds = updateEntity.lastSentSeconds
                    )
                    (mask and NotificationCenter.UPDATE_MASK_ATTACHMENTS) != 0 -> existing.copy(
                        content = newContent,
                        updateTimeSeconds = updateEntity.updateTimeSeconds,
                        hideEditted = updateEntity.hideEditted,
                        code = updateEntity.code,
                        attachmentUrl = updateEntity.attachmentUrl,
                        attachmentThumb = updateEntity.attachmentThumb,
                        attachmentWidth = updateEntity.attachmentWidth,
                        attachmentHeight = updateEntity.attachmentHeight,
                        attachmentFilename = updateEntity.attachmentFilename,
                        attachmentFiletype = updateEntity.attachmentFiletype,
                        attachmentSize = updateEntity.attachmentSize,
                        attachmentDuration = updateEntity.attachmentDuration,
                        extraAttachmentsJson = updateEntity.extraAttachmentsJson,
                        messageType = updateEntity.messageType,
                        isError = updateEntity.isError,
                        sendState = if (updateEntity.sendState != MessageEntity.SEND_STATE_SENDING) {
                            updateEntity.sendState
                        } else {
                            existing.sendState
                        },
                    )
                    else -> existing.copy(
                        content = newContent,
                        updateTimeSeconds = updateEntity.updateTimeSeconds,
                        hideEditted = updateEntity.hideEditted,
                        code = updateEntity.code
                    )
                }
                messages[idx] = merged
                messagesDict.put(merged.id, merged)
                if (!hasEmbedControlPayload(merged.content)) EmbedFormUtil.clearMessage(merged.id)
                if (merged.isPollMessage) {
                    pollStates[merged.id] = (pollStates[merged.id] ?: PollLocalState()).copy(
                        optimisticMyIndices = null,
                        displayMergedPoll = null
                    )
                }
                if (fragmentView != null) {
                    adapter.notifyMessageChangedAt(idx)
                }
            }
        }

        observe(NotificationCenter.messageDidDelete) { _, _, args ->
            if (args.size < 2 || args[0] != messageListKey) return@observe
            val messageId = args[1] as? Long ?: return@observe
            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                messages.removeAt(idx)
                messagesDict.delete(messageId)
                EmbedFormUtil.clearMessage(messageId)
                pollStates.remove(messageId)
                if (fragmentView != null) refreshUI()
                if (fragmentView != null) {
                    if (messages.isEmpty()) {
                        refreshUI()
                    } else {
                        adapter.notifyMessageRemovedAt(idx)
                        updateUnreadDividerPosition()
                    }
                }
            }
        }

        observe(NotificationCenter.messagesLoadError) { _, _, args ->
            if (args.isNotEmpty() && args[0] == messageListKey) {
                isLoading = false
                isLoadingMore = false
                if (fragmentView != null && messages.isEmpty()) {
                    showError(args.getOrNull(1) as? String ?: "Failed to load")
                } else if (fragmentView != null) {
                    refreshUI()
                }
            }
        }

        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            if (fragmentView == null || isPaused || isTopicMode) return@observe
            val changedClanId = args.firstOrNull() as? Long ?: return@observe
            if (changedClanId != clanId || clanId == 0L) return@observe
            refreshClanHeaderFromChannel()
        }

        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            if (
                clanId == 0L &&
                (channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP) &&
                (
                    (mask and NotificationCenter.UPDATE_MASK_CHAT_NAME) != 0 ||
                        (mask and NotificationCenter.UPDATE_MASK_CHAT_AVATAR) != 0
                    )
            ) {
                refreshDmHeaderTitleFromDialog()
                refreshWelcomeFromDialog()
            }
            if (
                clanId != 0L &&
                !isTopicMode &&
                (mask and NotificationCenter.UPDATE_MASK_CHAT_NAME) != 0
            ) {
                val targetChannelId = args.getOrNull(1) as? Long
                val targetClanId = args.getOrNull(2) as? Long
                val sameChannel = targetChannelId == null || targetChannelId == 0L || targetChannelId == channelId
                val sameClan = targetClanId == null || targetClanId == 0L || targetClanId == clanId
                if (sameChannel && sameClan) {
                    refreshClanHeaderFromChannel()
                }
            }
            updateVisibleRows(mask)
        }

        observe(NotificationCenter.topicsNeedReload) { _, _, _ ->
            if (isPaused || isTopicMode || fragmentView == null) return@observe
            updateVisibleRows(NotificationCenter.UPDATE_MASK_TOPIC)
        }

        observe(NotificationCenter.audioPlaybackStateChanged) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val messageId = args.getOrNull(0) as? Long ?: return@observe
            val isPlaying = args.getOrNull(1) as? Boolean ?: false
            val isLoading = args.getOrNull(2) as? Boolean ?: false
            val positionMs = args.getOrNull(3) as? Long ?: 0L
            val durationMs = args.getOrNull(4) as? Long ?: 0L
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i) as? ChatMessageCell ?: continue
                child.applyAudioPlayback(messageId, isPlaying, isLoading, positionMs, durationMs)
            }
        }

        observe(NotificationCenter.reactionDidUpdate) { _, _, args ->
            if (isPaused || fragmentView == null) return@observe
            if (args.size < 7) return@observe
            val eventChannelId = args[0] as? Long ?: return@observe
            if (eventChannelId != messageListKey) return@observe
            val messageId = args[1] as? Long ?: return@observe
            val emojiId = args[2] as? Long ?: return@observe
            val emoji = args[3] as? String ?: return@observe
            val senderId = args[4] as? Long ?: return@observe
            val count = args[5] as? Int ?: return@observe
            val actionRemove = args[6] as? Boolean ?: return@observe

            val idx = messages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val old = messages[idx]
                val updatedJson = applyReactionEvent(old.reactionsJson, emojiId, emoji, senderId, count, actionRemove)
                val updated = old.copy(reactionsJson = updatedJson)
                messages[idx] = updated
                messagesDict.put(messageId, updated)
                updateVisibleRows(NotificationCenter.UPDATE_MASK_REACTIONS)
            }
        }

        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            rootView.setBackgroundColor(themeColors.chatBackground)
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
            inputOgpBar?.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = INPUT_OGP_BAR_CORNER
            }
            inputOgpImage?.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.surface)
                cornerRadius = LayoutHelper.dp(8f).toFloat()
            }
            inputOgpTitle?.setTextColor(themeColors.onSurface)
            inputOgpDesc?.setTextColor(themeColors.onSurfaceVariant)
            inputOgpClose?.let { btn ->
                val d = MezonIcon.closeSmallBold.getDrawable(btn.context)
                d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
                btn.setImageDrawable(d)
            }
            replyBar?.setBackgroundColor(themeColors.surface)
            replyNameView?.setTextColor(themeColors.onSurface)
            replyCloseButton?.let { btn ->
                val d = MezonIcon.closeSmallBold.getDrawable(btn.context)
                d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
                btn.setImageDrawable(d)
            }
            editBar?.setBackgroundColor(themeColors.surface)
            editNameView?.setTextColor(themeColors.onSurface)
            suggestionsPopup?.applyColors()
            editCloseButton?.let { btn ->
                val d = MezonIcon.closeSmallBold.getDrawable(btn.context)
                d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
                btn.setImageDrawable(d)
            }
            actionBar?.applyTheme()
            val ent = channelController.findChannelById(channelId)
            val clanThread = channelType != CHANNEL_TYPE_DM && channelType != CHANNEL_TYPE_GROUP &&
                clanId != 0L && ent?.isThread == true
            if (clanThread) {
                actionBar?.setSubtitleColor(themeColors.onSurfaceVariant)
            }
            pageDownButton.applyColors()
            unreadDecoration.applyColors()
            adapter.notifyDataSetChanged()
        }

        observe(NotificationCenter.appDidReconnect) { _, _, _ ->
            if (isPaused) return@observe
            Log.d(TAG, "appDidReconnect: reloading messages for channel $channelId")
            chatController.loadMessages(channelId, clanId, forceRefresh = true, preferHttp = false, topicId = topicId)
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

        observe(NotificationCenter.buzzMessageReceived) { _, _, args ->
            val buzzChannelId = args.firstOrNull() as? Long ?: return@observe
            if (buzzChannelId != channelId) return@observe
            playBuzzSound()
        }

        observe(NotificationCenter.anonymousModeChanged) { _, _, args ->
            val changedClanId = args.firstOrNull() as? Long ?: return@observe
            if (changedClanId != clanId) return@observe
            val isAnon = anonymousController.isAnonymous(clanId)
            anonymousIndicator?.visibility = if (isAnon) View.VISIBLE else View.GONE
        }

        observe(NotificationCenter.channelMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val loadedChannelId = args.firstOrNull() as? Long ?: return@observe
            val ch = channelController.findChannelById(channelId)
            val targetChannelId = if (ch?.parentId != 0L && ch?.parentId != null) ch.parentId else channelId
            if (loadedChannelId == targetChannelId) {
                checkSuggestionTrigger()
            }
        }

        observe(NotificationCenter.closeChats) { _, _, args ->
            val removedChannelId = args.firstOrNull() as? Long ?: 0L
            if (removedChannelId != 0L && removedChannelId != channelId) return@observe
            finishFragment()
        }

        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            val loadedClanId = args.firstOrNull() as? Long ?: return@observe
            if (loadedClanId == clanId) {
                if (!isPaused) checkSuggestionTrigger()
                refreshChatDisplayRoleCache(refreshUi = !isPaused)
            }
        }

        observe(NotificationCenter.searchChannelsDidLoad) { _, _, _ ->
            if (isPaused) return@observe
            if (currentTrigger.mode == InputSuggestionsController.Mode.HASHTAG) {
                checkSuggestionTrigger()
            }
            val isDmLike = channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP
            if (isDmLike || clanId == 0L) {
                adapter.notifyDataSetChanged()
            }
        }

        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            Log.d("DmCallMenu", "dialogsNeedReload fired isPaused=$isPaused clanId=$clanId channelType=$channelType actionBar=${actionBar != null}")
            if (isPaused) return@observe
            if (clanId == 0L && channelType == CHANNEL_TYPE_GROUP) {
                refreshPermissionGates()
            }
            if (clanId == 0L && (channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP)) {
                refreshDmHeaderTitleFromDialog()
                refreshWelcomeFromDialog()
            }
            if (clanId != 0L || channelType != CHANNEL_TYPE_DM) return@observe
            actionBar?.let { setupDmHeaderCallMenu(it) }
        }

        observe(NotificationCenter.jumpToMessage) { _, _, args ->
            val targetChannelId = args.getOrNull(0) as? Long ?: return@observe
            val targetMessageId = args.getOrNull(1) as? Long ?: return@observe
            if (targetChannelId == channelId) {
                pendingJumpMessageId = targetMessageId
            }
        }

        observe(NotificationCenter.channelGalleryDidLoad) { _, _, args ->
            val cid = args.getOrNull(0) as? Long ?: return@observe
            if (cid != channelId || activePhotoViewer == null) return@observe
            refreshActivePhotoViewerGallery()
        }

        notificationCenter.addPostponeNotificationsCallback(postponeNewMessagesCallback)

        isLoading = true
        chatController.loadMessages(channelId, clanId, forceRefresh = true, preferHttp = openedFromNotification, topicId = topicId)
        return true
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        chatController = entryPoint.chatController()
        dialogsController = entryPoint.dialogsController()
        channelController = entryPoint.channelController()
        mediaController = entryPoint.mediaController()
        channelGalleryController = entryPoint.channelGalleryController()
        audioPlayerController = entryPoint.audioPlayerController()
        userClanController = entryPoint.userClanController()
        userController = entryPoint.userController()
        memberResolver = entryPoint.memberResolver()
        roleController = entryPoint.roleController()
        permissionPolicy = entryPoint.permissionPolicy()
        searchController = entryPoint.searchController()
        callController = entryPoint.callController()
        callManager = entryPoint.callManager()
        friendController = entryPoint.friendController()
        emojiController = entryPoint.emojiController()
        anonymousController = entryPoint.anonymousController()
        pinMessageController = entryPoint.pinMessageController()
        topicController = entryPoint.topicController()
        topicBadgeTracker = entryPoint.topicBadgeTracker()
        voiceController = entryPoint.voiceController()
        channelAppController = entryPoint.channelAppController()
        clansController = entryPoint.clansController()
        mezonApi = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        walletController = entryPoint.walletController()
        accountController = entryPoint.accountController()
        appScope = entryPoint.applicationScope()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
        imageClipboardCoordinator = entryPoint.imageClipboardCoordinator()
    }

    override fun createView(context: Context): View {
        sizeNotifierRoot = SizeNotifierFrameLayout(context, parentLayout)
        sizeNotifierRoot.setBackgroundColor(themeColors.chatBackground)
        rootView = sizeNotifierRoot

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val chatActionBar = ActionBarView(context, themeColors).apply {
            setBackClickListener {
                if (emojiViewVisible) {
                    hideEmojiView()
                } else {
                    finishFragment()
                }
            }
            setTitleOnClickListener {
                val channelEntity = channelController.findChannelById(channelId)
                val infoPrivate = channelEntity?.isPrivate ?: routeChannelPrivate
                val infoParentId = channelEntity?.parentId ?: routeParentId
                presentFragment(
                    com.mezon.mobile.home.chat.channelinfo.ChannelInfoFragment.newInstance(
                        channelId = channelId,
                        channelName = channelName,
                        clanId = clanId,
                        channelType = channelType,
                        isChannelPrivate = infoPrivate,
                        parentId = infoParentId
                    )
                )
            }
            val entity = channelController.findChannelById(channelId)
            val isDmHeader = channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP
            val clanChannelHeader = !isDmHeader && clanId != 0L
            if (clanChannelHeader) {
                val iconEnum = resolveChannelIcon(entity)
                val iconPx = channelTitleIconSizePx()
                val d = channelTitleIconDrawable(context, iconEnum)
                setTitleStartIcon(d, iconPx, LayoutHelper.dp(6))
                setTitle(channelName)
                setSubtitleStartPadding(0)
                setSubtitleColor(themeColors.onSurfaceVariant)
                if (entity?.parentId != null && entity.parentId != 0L) {
                    val parent = channelController.findChannelById(entity.parentId)
                    if (parent != null) setSubtitle(parent.channelLabel)
                    else setSubtitle(null)
                } else if (channelType == CHANNEL_TYPE_THREAD || routeParentId != 0L) {
                    val parent = channelController.findChannelById(routeParentId)
                    if (parent != null) setSubtitle(parent.channelLabel)
                    else setSubtitle(null)
                } else {
                    setSubtitle(null)
                }
            } else {
                setTitleStartIcon(null, 0, 0)
                setTitle(buildChannelTitle(context))
                setSubtitleStartPadding(0)
                if (entity != null && entity.isThread && entity.parentId != 0L) {
                    val parent = channelController.findChannelById(entity.parentId)
                    if (parent != null) setSubtitle(parent.channelLabel)
                }
            }
            post {
                val tv = getTitleTextView() ?: return@post
                val backWidth = LayoutHelper.dp(54)
                val rect = Rect(backWidth, 0, width, height)
                touchDelegate = TouchDelegate(rect, tv)
            }
            setupDmHeaderCallMenu(this)
            if (isTopicMode) {
                setTitle(getString(R.string.topic_discussion))
                setSubtitle(null)
                val iconPx = channelTitleIconSizePx()
                val topicIcon = MezonIcon.notificationTabTopic.getDrawable(context)
                setTitleStartIcon(topicIcon, iconPx, LayoutHelper.dp(6))
                setTitleOnClickListener(null)
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
            setItemViewCacheSize(8)
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
            setOnClickListener { onPageDownClicked() }
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

        suggestionsAdapter = InputSuggestionsAdapter(themeColors) { item -> onSuggestionSelected(item) }
        suggestionsPopup = InputSuggestionsPopup(context, themeColors).apply {
            recyclerView.adapter = suggestionsAdapter
        }
        contentFrame.addView(
            suggestionsPopup,
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

        channelAppHotbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(6f), LayoutHelper.dp(10f), LayoutHelper.dp(6f))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = if (channelType == CHANNEL_TYPE_APP) View.VISIBLE else View.GONE
            fun channelAppHotbarButton(label: String, onClick: (View) -> Unit): TextView {
                val pillBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(themeColors.tertiary)
                    cornerRadius = LayoutHelper.dp(10f).toFloat()
                }
                val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt())
                    cornerRadius = LayoutHelper.dp(10f).toFloat()
                }
                val rippleColor = android.content.res.ColorStateList.valueOf(themeColors.onSurface and 0x1AFFFFFF)
                return TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(themeColors.onSurface)
                    textSize = 14f
                    background = android.graphics.drawable.RippleDrawable(rippleColor, pillBg, rippleMask)
                    setPadding(0, LayoutHelper.dp(10f), 0, LayoutHelper.dp(10f))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener(onClick)
                }
            }
            val gapBetween = LayoutHelper.dp(10f)
            addView(
                channelAppHotbarButton(getString(R.string.channel_app_launch)) { openChannelAppFromHotbar() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    bottomMargin = LayoutHelper.dp(6f)
                    marginEnd = gapBetween
                }
            )
            addView(
                channelAppHotbarButton(getString(R.string.channel_app_help)) { },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    bottomMargin = LayoutHelper.dp(6f)
                }
            )
        }
        innerLayout.addView(channelAppHotbar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        inputOgpBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(8f), LayoutHelper.dp(10f), LayoutHelper.dp(8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = INPUT_OGP_BAR_CORNER
            }
        }
        innerLayout.addView(
            inputOgpBar,
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                0f,
                Gravity.CENTER_VERTICAL,
                8f,
                0f,
                8f,
                6f
            )
        )

        inputOgpImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.surface)
                cornerRadius = LayoutHelper.dp(8f).toFloat()
            }
            background = shape
            clipToOutline = true
        }
        inputOgpBar?.addView(
            inputOgpImage,
            LinearLayout.LayoutParams(INPUT_OGP_IMAGE_SIZE, INPUT_OGP_IMAGE_SIZE).apply {
                marginEnd = LayoutHelper.dp(10f)
            }
        )

        val inputOgpTextWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        inputOgpBar?.addView(
            inputOgpTextWrap,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        inputOgpTitle = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        inputOgpTextWrap.addView(inputOgpTitle)

        inputOgpDesc = TextView(context).apply {
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        inputOgpTextWrap.addView(inputOgpDesc)

        inputOgpClose = ImageButton(context).apply {
            val drawable = MezonIcon.closeSmallBold.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f))
            setOnClickListener {
                dismissedInputOgpUrl = inputOgpUrl
                clearInputOgpPreview()
            }
        }
        inputOgpBar?.addView(
            inputOgpClose,
            LinearLayout.LayoutParams(INPUT_OGP_IMAGE_SIZE, INPUT_OGP_IMAGE_SIZE).apply {
                marginStart = LayoutHelper.dp(6f)
            }
        )

        inputBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(themeColors.surface)
            setPadding(LayoutHelper.dp(8f), LayoutHelper.dp(10f), LayoutHelper.dp(8f), LayoutHelper.dp(10f))
            clipChildren = false
            clipToPadding = false
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
        inputBar.addView(advancedFunctionButton, LayoutHelper.createLinear(40, 40, gravity = Gravity.BOTTOM, leftMargin = 8f))

        inputWrapper = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        inputBar.addView(inputWrapper, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.BOTTOM, 8f, 0f, 8f, 0f))

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

        anonymousIndicator = ImageView(context).apply {
            setImageDrawable(MezonIcon.anonymous.getDrawable(context))
            scaleType = ImageView.ScaleType.CENTER
            rotation = 45f
            visibility = if (anonymousController.isAnonymous(clanId)) View.VISIBLE else View.GONE
        }
        inputWrapper.addView(anonymousIndicator, FrameLayout.LayoutParams(
            LayoutHelper.dp(20f), LayoutHelper.dp(20f),
            Gravity.END or Gravity.TOP
        ).apply {
            rightMargin = -LayoutHelper.dp(10f)
            topMargin = -LayoutHelper.dp(10f)
        })

        voiceOverlay = VoiceRecordingOverlay(context, themeColors).apply {
            setSlideToCancelText(getString(R.string.voice_record_slide_to_cancel))
        }
        inputWrapper.addView(voiceOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val sendBtnPad = LayoutHelper.dp(11f)
        sendButton = ImageButton(context).apply {
            val drawable = MezonIcon.sendMessageIcon.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(sendBtnPad, sendBtnPad, sendBtnPad, sendBtnPad)
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
            setOnTouchListener { v, event -> handleMicTouchEvent(v, event) }
        }
        inputBar.addView(micButton, LayoutHelper.createLinear(40, 40, gravity = Gravity.BOTTOM))

        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (suppressInputTrackerMutation) return
                adjustMentionTrackersForChange(start, count, after)
                adjustHashtagTrackersForChange(start, count, after)
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressInputTrackerMutation) return
                pruneMentionTrackersAgainstText()
                pruneHashtagTrackersAgainstText()
                updateSendButtonState()
                updateInputOgpPreview(s?.toString().orEmpty())
                checkSuggestionTrigger()
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

        setupPasteImageLongPress(context)

        adapter = ChatAdapter(themeColors, messages, channelName, cellDelegate = object : ChatMessageCell.ChatMessageCellDelegate {
            override fun didClickMedia(cell: ChatMessageCell, msg: MessageEntity, attachmentIndex: Int) {
                val allMedia = msg.allImageAttachments
                val att = allMedia.getOrNull(attachmentIndex) ?: allMedia.firstOrNull() ?: return
                val url = att.url
                if (url.isEmpty()) return

                val isVideo = att.filetype.startsWith("video/")
                val thumbBmp = cell.getMediaBitmap(attachmentIndex)

                if (isVideo) {
                    VideoPlayerDialog(context).play(url)
                } else {
                    val seed = allMedia.filter { !it.filetype.startsWith("video/") && it.url.isNotEmpty() }.map { it.url }
                    openChannelPhotoViewer(context, url, thumbBmp, seed)
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
                        MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.INFO, "Downloading $filename")
                    } catch (_: Exception) {}
                }
            }
            override fun didLongPress(cell: ChatMessageCell, msg: MessageEntity) {
                if (msg.isCallLogMessage()) return
                showMessageActionSheet(msg)
            }
            override fun didClickAvatar(cell: ChatMessageCell, msg: MessageEntity) {
                if (msg.senderId == ANONYMOUS_USER_ID) return
                showUserProfile(msg)
            }
            override fun didPressReply(cell: ChatMessageCell, replyMessageId: Long) {
                scrollToReplyMessage(replyMessageId)
            }
            override fun didTapReaction(cell: ChatMessageCell, msg: MessageEntity, group: ReactionGroup) {
                handleReactionTap(msg, group)
            }
            override fun didLongPressReaction(cell: ChatMessageCell, msg: MessageEntity, group: ReactionGroup) {
                showReactionDetailSheet(msg, group.emojiId)
            }
            override fun didTapAddReaction(cell: ChatMessageCell, msg: MessageEntity) {
                showReactionEmojiPicker(msg)
            }
            override fun didClickHashtag(cell: ChatMessageCell, channelId: String?) {
                navigateToChannelFromHashtag(channelId)
            }
            override fun didClickMention(cell: ChatMessageCell, userId: String?, roleId: String?) {
                if (!roleId.isNullOrBlank() && roleId != "0") return
                val uidStr = userId ?: return
                if (uidStr == ChatController.ID_MENTION_HERE) return
                val uid = uidStr.toLongOrNull() ?: return
                showUserProfileFromMentionUserId(uid)
            }
            override fun didTapAudio(cell: ChatMessageCell, msg: MessageEntity) {
                val url = msg.attachmentUrl
                if (url.isBlank()) return
                if (msg.isSending) return
                audioPlayerController.toggle(msg.id, url, msg.attachmentDuration)
            }
            override fun didClickInviteJoin(cell: ChatMessageCell, msg: MessageEntity, inviteId: Long) {
                onLinkInviteJoinClicked(inviteId)
            }
            override fun isDmPeerBlockedForCallLog(): Boolean {
                if (clanId != 0L || channelType != CHANNEL_TYPE_DM) return false
                val myId = chatController.getCurrentUserId()
                val other = dialogsController.getParticipants(channelId).firstOrNull { it.userId != myId }
                    ?: return false
                return friendController.isUserBlocked(other.userId)
            }
            override fun didTapCallLogCallBack(cell: ChatMessageCell, msg: MessageEntity) {
                val parsed = parseCallLogMessage(msg.content)
                Log.d(
                    TAG,
                    "callLogCallback tap msgId=${msg.id} channelId=$channelId clanId=$clanId " +
                        "senderId=${msg.senderId} isMe=${msg.isMe} parsedType=${parsed?.callLogType}"
                )
                val peer = resolveDmCallPeerForCallback(msg) ?: run {
                    if (channelType == CHANNEL_TYPE_DM && clanId == 0L) {
                        dialogsController.loadDmParticipants(channelId)
                        MezonToast.show(
                            this@ChatFragment,
                            ToastOverlay.ToastType.INFO,
                            getString(R.string.common_loading_data)
                        )
                        Log.w(TAG, "callLogCallback unresolved peer loadDmParticipants msgId=${msg.id} channelId=$channelId")
                    } else {
                        Log.w(
                            TAG,
                            "callLogCallback unresolved peer msgId=${msg.id} channelType=$channelType clanId=$clanId"
                        )
                    }
                    return
                }
                Log.d(TAG, "callLogCallback resolved peerId=${peer.userId} msgId=${msg.id}")
                requestCallPermissions(needsCamera = false, reason = "callLogCallback") {
                    Log.d(TAG, "callLogCallback permissions ok startCall peerId=${peer.userId} msgId=${msg.id}")
                    runOutgoingCallAfterFullScreenIntentPrompt(
                        {
                            callController.startCall(
                                peer.userId,
                                peer.displayName,
                                peer.avatarUrl,
                                channelId,
                                clanId,
                                channelType,
                                resolveChannelPrivate(),
                                isVideo = false,
                                peerUsername = peer.username
                            )
                            presentFragment(com.mezon.mobile.home.call.CallFragment())
                        }
                    )
                }
            }
            override fun didClickEmbedComponentButton(cell: ChatMessageCell, msg: MessageEntity, buttonId: String) {
                submitEmbedComponentButton(msg, buttonId)
            }
            override fun didTapShareContactProfile(cell: ChatMessageCell, msg: MessageEntity, data: ShareContactData) {
                showShareContactProfile(data)
            }
            override fun didTapShareContactMessage(cell: ChatMessageCell, msg: MessageEntity, data: ShareContactData) {
                openShareContactDm(data)
            }
            override fun didTapShareContactCall(cell: ChatMessageCell, msg: MessageEntity, data: ShareContactData) {
                startShareContactCall(data)
            }
        })

        adapter.sendTokenDelegate = object : SendTokenMessageCell.Delegate {
            override fun onMezonTransferClick() {
                presentFragment(SendTokenFragment.newInstance())
            }
        }
        adapter.systemMessageMentionGate = gate@{ uid, _, segment ->
            val seg = segment.trim()
            if (seg.equals("@here", ignoreCase = true)) return@gate true
            if (uid == ChatController.ID_MENTION_HERE) return@gate true
            if (uid.isNullOrEmpty()) return@gate false
            systemMessageMemberIds.contains(uid)
        }
        adapter.systemMessageDelegate = object : SystemMessageCell.Delegate {
            override fun onOpenThread(threadChannelId: Long, threadTitle: String) {
                val entity = channelController.findChannelById(threadChannelId, 0L)
                    ?: searchController.findChannelById(threadChannelId)
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "system_msg_open_thread id=$threadChannelId entity=${entity != null} clanId=$clanId"
                    )
                }
                if (entity != null) {
                    openChannelEntity(entity)
                } else {
                    if (!searchController.hasChannels()) searchController.loadChannels()
                    (getParentActivity() as? MainActivity)?.openChat(
                        threadChannelId,
                        threadTitle,
                        clanId,
                        CHANNEL_TYPE_THREAD
                    )
                }
            }

            override fun onSeeAllThreads() {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "system_msg_see_all_threads channelId=$channelId clanId=$clanId")
                }
                presentFragment(ThreadListFragment.newInstance(channelId, channelName, clanId))
            }

            override fun onMentionClick(userId: String?, roleId: String?) {
                if (!roleId.isNullOrBlank() && roleId != "0") return
                val uidStr = userId ?: return
                if (uidStr == ChatController.ID_MENTION_HERE) return
                val uid = uidStr.toLongOrNull() ?: return
                showUserProfileFromMentionUserId(uid)
            }

            override fun onJumpToPinnedMessage(messageRefId: Long) {
                if (messageRefId == 0L) return
                scrollToReplyMessage(messageRefId)
            }

            override fun onSeeAllPins() {
                val channelEntity = channelController.findChannelById(channelId)
                val infoPrivate = channelEntity?.isPrivate ?: resolveChannelPrivate()
                val infoParentId = channelEntity?.parentId ?: routeParentId
                presentFragment(
                    com.mezon.mobile.home.chat.channelinfo.ChannelInfoFragment.newInstance(
                        channelId = channelId,
                        channelName = channelName,
                        clanId = clanId,
                        channelType = channelType,
                        isChannelPrivate = infoPrivate,
                        parentId = infoParentId,
                        initialTabIndex = com.mezon.mobile.home.chat.channelinfo.ChannelInfoFragment.TAB_INDEX_PINS
                    )
                )
            }
        }
        adapter.loadLinkInvitePreview = { id -> mezonApi.getLinkInvitePreview(id) }
        adapter.channelType = channelType
        adapter.clanId = clanId
        adapter.isChannelPrivate = resolveChannelPrivate()
        adapter.isChannelAgeRestricted = resolveChannelAgeRestricted()
        adapter.currentUserId = StartupCache.userId
        adapter.displayRoleResolver = chatDisplayRoleResolver()
        adapter.onTopicClick = { tid, rootId ->
            if (!isTopicMode) openTopicDiscussion(tid, rootId)
        }
        adapter.topicButtonEnabled = !isTopicMode
        if (isTopicMode) {
            adapter.setShowTopicRootHeader(true)
            adapter.onTopicRootHeaderReady = { topicRootHeader = it }
            cachedTopicRootMessage?.let { msg ->
                adapter.topicRootMessage = msg
                adapter.notifyTopicRootHeaderChanged()
            }
        }
        adapter.topicCreatorResolver = { creatorId ->
            memberResolver.resolveMember(creatorId, clanId, channelId, channelType)?.let { member ->
                val name = member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
                val avatar = member.clanAvatar.ifBlank { member.avatarUrl }
                name to avatar
            }
        }
        adapter.topicLastMessageIdResolver = { tid ->
            chatController.getLastMessageId(tid).takeIf { it != 0L }
                ?: topicController.findTopic(tid)?.lastSentMessageId?.takeIf { it != 0L }
                ?: 0L
        }
        adapter.topicBadgeResolver = { tid -> topicBadgeTracker.getTopicBadge(tid) }
        adapter.systemMessageCreatorResolver = { creatorId ->
            memberResolver.resolveMember(creatorId, clanId, channelId, channelType)?.let { member ->
                member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
            }.orEmpty()
        }
        if (!isTopicMode && clanId != 0L) {
            requestTopicBadgeHydrate(0L)
        }
        refreshWelcomeFromDialog()
        refreshChatDisplayRoleCache()
        adapter.pollBridge = object : ChatPollBridge {
            override fun getLocalState(messageId: Long): PollLocalState {
                val base = pollStates[messageId] ?: PollLocalState()
                if (base.optimisticMyIndices != null) return base
                val msg = messagesDict.get(messageId)
                val parsed = msg?.takeIf { it.isPollMessage }?.let { parsePollContent(it.content) }
                if (parsed != null && votedAnswerIndices(parsed, currentUserIdLong()).isNotEmpty()) return base
                val eff = effectivePollMyAnswerIndices(messageId)
                return if (eff.isEmpty()) base else base.copy(optimisticMyIndices = eff)
            }
            override fun stateFingerprint(messageId: Long) = getLocalState(messageId).fingerprint()
            override fun pollForLayout(messageId: Long, contentParsed: ParsedPoll): ParsedPoll {
                val snap = pollStates[messageId]?.displayMergedPoll ?: return contentParsed
                return contentParsed.copy(
                    countsByIndex = snap.countsByIndex,
                    totalVotes = snap.totalVotes.coerceAtLeast(0),
                    voterDetails = if (snap.voterDetails.isNotEmpty()) snap.voterDetails else contentParsed.voterDetails
                )
            }
            override fun onPollTap(msg: MessageEntity, parsed: ParsedPoll, tap: PollTap) {
                handleChatPollTap(msg, parsed, tap)
            }
        }
        adapter.shareContactOnlineResolver = { userId ->
            friendController.friends.value.find { it.user.id == userId }?.user?.online == true ||
                userClanController.getUserById(userId)?.isOnline == true
        }
        refreshSystemMessageMemberGateCache()
        recyclerView.adapter = adapter

        setupSwipeInterceptor()

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING, RecyclerView.SCROLL_STATE_SETTLING -> {
                        scrollingManually = true
                        for (i in 0 until rv.childCount) {
                            (rv.getChildAt(i) as? ChatMessageCell)?.stopHeavyOperations()
                        }
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        scrollingManually = false
                        for (i in 0 until rv.childCount) {
                            val child = rv.getChildAt(i) as? ChatMessageCell ?: continue
                            child.startHeavyOperations()
                            updateCellVisibility(rv, child)
                        }
                        markVisibleAsRead()
                        refreshVisiblePollTalliesFromServer()
                        if (pendingFullVisibleUpdate) {
                            pendingFullVisibleUpdate = false
                            pendingPartialUpdateMask = 0
                            updateVisibleRows(0)
                        } else if (pendingPartialUpdateMask != 0) {
                            val mask = pendingPartialUpdateMask
                            pendingPartialUpdateMask = 0
                            updateVisibleRows(mask)
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
                    if (isViewingOlder) pausedOnLastMessage = false
                    updatePageDownVisibility()
                    if (!isViewingOlder && !hasMoreBottom) markAsRead()
                }

                if (!isLoadingMore && hasMoreTop && messages.size >= 10 && !isOldestMessageEndOfHistory()) {
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val totalCount = adapter.itemCount
                    if (totalCount > 0 && lastVisible >= totalCount - 5) {
                        val oldest = messages.lastOrNull()?.id ?: return
                        isLoadingMore = true
                        chatController.loadMoreTop(channelId, clanId, oldest, topicId = topicId)
                    }
                }

                if (!isLoadingMore && hasMoreBottom && messages.size >= 10) {
                    if (firstVisible <= 3) {
                        val newest = newestReadStateMessageId()
                        if (newest == 0L) return
                        isLoadingMore = true
                        chatController.loadMoreBottom(channelId, clanId, newest, topicId = topicId)
                    }
                }
            }
        })

        chatAdjustPanHelper = object : com.mezon.mobile.core.AdjustPanLayoutHelper(rootView) {
            override fun heightAnimationEnabled(): Boolean {
                val layout = parentLayout
                if (layout == null) return false
                if (android.os.SystemClock.elapsedRealtime() - lastResumeTime < 250) return false
                if ((this@ChatFragment == layout.getLastFragment() && layout.isTransitionAnimationInProgress()) || isPaused) return false
                return true
            }
            override fun onTransitionStart(keyboardVisible: Boolean, contentHeight: Int) {
                if (!keyboardVisible) recyclerView.stopScroll()
            }
            override fun onPanTranslationUpdate(y: Float, progress: Float, keyboardVisible: Boolean) {
                actionBar?.translationY = y
                inputBar.translationY = y
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
                    if (waitingForKeyboardOpen) {
                        waitingForKeyboardOpen = false
                        AndroidUtilities.cancelRunOnUIThread(openKeyboardRunnable)
                    }
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
        refreshUI()
        refreshPermissionGates()
        return rootView
    }

    override fun onResume() {
        super.onResume()
        lastResumeTime = android.os.SystemClock.elapsedRealtime()
        if (clanId == 0L) {
            refreshDmHeaderTitleFromDialog()
            refreshWelcomeFromDialog()
        } else if (!isTopicMode) {
            refreshClanHeaderFromChannel()
        }
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        actionBar?.post {
            if (clanId == 0L) {
                refreshDmHeaderTitleFromDialog()
                refreshWelcomeFromDialog()
            } else if (!isTopicMode) {
                refreshClanHeaderFromChannel()
            }
        }
        if (pendingDisplayRoleUiRefresh) {
            pendingDisplayRoleUiRefresh = false
            refreshChatSenderRoleRows()
        }
        refreshSystemMessageMemberGateCache()
        if (emojiViewVisible || (emojiView != null && emojiView!!.visibility == View.VISIBLE)) {
            dismissEmojiSilently()
        }
        pausedOnLastMessage = false
        if (!initialApiDone) initialApiDone = true
        dialogsController.setCurrentChannel(channelId)
        if (clanId != 0L) {
            channelController.setCurrentChannel(channelId)
            if (isTopicMode) {
                channelController.setCurrentTopic(topicId)
                channelController.markChannelAsRead(topicId, seenMessageId = lastSeenMessageId)
                if (lastSeenMessageId != 0L) {
                    val topicSeenTs = channelController.findChannelById(topicId)?.lastSeenMessageTs?.toInt() ?: 0
                    chatController.updateLastSeenMessage(
                        channelId,
                        clanId,
                        channelType,
                        lastSeenMessageId,
                        topicSeenTs,
                        badgeCount = 0,
                        applyLocal = false
                    )
                }
            } else {
                channelController.markChannelAsRead(channelId, seenMessageId = lastSeenMessageId)
                channelController.clearCurrentTopic()
                refreshTopicRootRowsFromCache()
                updateVisibleRows(NotificationCenter.UPDATE_MASK_TOPIC)
            }
        }

        if (pendingJumpMessageId != 0L) {
            val jumpId = pendingJumpMessageId
            pendingJumpMessageId = 0L
            if (messages.isEmpty() || firstLoad || isLoading) {
                pendingHighlightMessageId = jumpId
            } else {
                scrollToReplyMessage(jumpId)
            }
            mainHandler.post { refreshPollSnapshotsForStoredVotes() }
            startVisiblePollTallyRefreshLoop()
            if (::recyclerView.isInitialized) {
                recyclerView.post { refreshVisiblePollTalliesFromServer() }
            }
            return
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
            chatController.loadMessages(channelId, clanId, forceRefresh = true, preferHttp = openedFromNotification, topicId = topicId)
        }
        mainHandler.post { refreshPollSnapshotsForStoredVotes() }
        startVisiblePollTallyRefreshLoop()
        if (::recyclerView.isInitialized) {
            recyclerView.post { refreshVisiblePollTalliesFromServer() }
        }
    }

    override fun onPause() {
        super.onPause()
        if (clanId != 0L) channelController.clearCurrentTopic()
        waitingForKeyboardOpen = false
        AndroidUtilities.cancelRunOnUIThread(openKeyboardRunnable)
        AndroidUtilities.cancelRunOnUIThread(showKeyboardFromEmojiRunnable)
        if (::recyclerView.isInitialized) recyclerView.stopScroll()
        if (emojiViewVisible) {
            dismissEmojiSilently()
        }
        if (voiceIsRecording) {
            mainHandler.removeCallbacks(voiceLongPressRunnable)
            cancelVoiceRecording(showToast = false)
        }
        if (::audioPlayerController.isInitialized) {
            audioPlayerController.stop()
        }
        stopVisiblePollTallyRefreshLoop()
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

    private fun showEmojiView() {
        dismissPasteImagePopup()
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

    private val showKeyboardFromEmojiRunnable = Runnable {
        inputField.requestFocus()
        waitingForKeyboardOpen = true
        AndroidUtilities.cancelRunOnUIThread(openKeyboardRunnable)
        AndroidUtilities.showKeyboard(inputField)
        AndroidUtilities.runOnUIThread(openKeyboardRunnable, 100)
    }

    private fun openKeyboardFromEmoji() {
        updateEmojiButtonIcon(showingEmoji = false)
        AndroidUtilities.cancelRunOnUIThread(showKeyboardFromEmojiRunnable)
        if (emojiViewVisible) {
            AndroidUtilities.runOnUIThread(showKeyboardFromEmojiRunnable, 200)
        } else {
            showKeyboardFromEmojiRunnable.run()
        }
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
        dismissPasteImagePopup()
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
                    if (!ensureCanSendMessageOrNotify()) return
                    if (sticker.isForSale && sticker.src.isBlank()) return
                    val url = resolveStickerSourceUrl(sticker.id, sticker.src)
                    if (url.isBlank()) return
                    val filetype = if (isAudio) "audio/mpeg" else "image/gif"
                    val references = buildReplyReferences()
                    chatController.sendDirectAttachment(
                        channelId, clanId, channelType, resolveChannelPrivate(),
                        url, filetype, sticker.id, references, topicId = topicId
                    )
                    clearReplyState()
                    hideEmojiView()
                }

                override fun onGifSelected(gifUrl: String) {
                    if (!ensureCanSendMessageOrNotify()) return
                    val references = buildReplyReferences()
                    chatController.sendDirectAttachment(
                        channelId, clanId, channelType, resolveChannelPrivate(),
                        gifUrl, "image/gif", references = references, topicId = topicId
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
        activePhotoViewer?.setOnDismissListener(null)
        activePhotoViewer?.dismiss()
        activePhotoViewer = null
        dismissPasteImagePopup()
        waitingForKeyboardOpen = false
        AndroidUtilities.cancelRunOnUIThread(openKeyboardRunnable)
        AndroidUtilities.cancelRunOnUIThread(showKeyboardFromEmojiRunnable)
        notificationCenter.removePostponeNotificationsCallback(postponeNewMessagesCallback)
        notificationCenter.onAnimationFinish(transitionAnimationIndex)
        if (::recyclerView.isInitialized) cancelPendingScroll()
        cancelPendingLoading()
        displayRoleCacheRefreshJob?.cancel()
        displayRoleCacheRefreshJob = null
        topicBadgeHydrateJob?.cancel()
        topicBadgeHydrateJob = null
        pendingDisplayRoleUiRefresh = false
        mainHandler.removeCallbacks(markVisibleRunnable)
        markVisibleAsRead()
        flushPendingSeen()
        dialogsController.clearCurrentChannel()
        if (clanId != 0L) channelController.clearCurrentChannel()
        messages.clear()
        messagesDict.clear()
        sentByApiRealIds.clear()
        for (t in pendingAttachmentThumbTasks) ThumbnailCache.cancel(t)
        pendingAttachmentThumbTasks.clear()
        attachmentProgressReloadRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        attachmentProgressReloadRunnable = null
        pendingAttachments.clear()
        replyingToMessage = null
        editingMessage = null
        mentionTrackers.clear()
        hashtagTrackers.clear()
        inputOgpFetchJob?.cancel()
        inputOgpFetchJob = null
        InputOgpFetcher.cancelInFlight()
        inputOgpImageLoad.cancel()
        inputOgpImageLoad = MezonImageLoader.Cancellable.EMPTY
        inputOgpBar = null
        inputOgpImage = null
        inputOgpTitle = null
        inputOgpDesc = null
        inputOgpClose = null
        inputOgpUrl = null
        inputOgpPreviewData = null
        inputOgpThumbnailUrl = null
        dismissedInputOgpUrl = null
        failedInputOgpUrl = null
        suggestionsPopup = null
        suggestionsAdapter = null
        EmbedFormUtil.clearAll()
        pendingHighlightMessageId = 0L
        chatAdjustPanHelper?.onDetach()
        chatAdjustPanHelper = null
        emojiView = null
        emojiObjPicked.clear()
        buzzMediaPlayer?.release()
        buzzMediaPlayer = null
        mainHandler.removeCallbacks(voiceLongPressRunnable)
        voiceRecorder?.cancel()
        voiceRecorder = null
        voiceOverlay = null
        inviteJoinClansObserver?.let {
            notificationCenter.removeObserver(it, NotificationCenter.clansDidLoad)
        }
        inviteJoinClansObserver = null
        inviteJoinTimeout?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        inviteJoinTimeout = null
        super.onFragmentDestroy()
    }

    private fun onLinkInviteJoinClicked(inviteId: Long) {
        if (inviteId == 0L) return
        val act = getParentActivity() ?: return
        if (clansController.getClanCount() >= CLAN_CREATE_LIMIT) {
            AlertDialog.Builder(act)
                .setTitle(getString(R.string.clan_create_limit_reached_title))
                .setMessage(getString(R.string.clan_create_limit_reached_message))
                .setPositiveButton(getString(R.string.common_ok), null)
                .show()
            return
        }
        fragmentScope.launch {
            val res = runCatching {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        mezonApi.inviteUserByInviteId(session.apiUrl, session.token, inviteId)
                    }
                }
            }
            withContext(mainDispatcher) {
                res.onSuccess { r ->
                    val cid = r.clanId
                    if (cid != 0L) {
                        navigateToJoinedClanFromChatInvite(cid)
                    } else {
                        MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.ERROR, getString(R.string.discover_join_failed))
                    }
                }.onFailure {
                    MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.ERROR, getString(R.string.discover_join_failed))
                }
            }
        }
    }

    private fun navigateToJoinedClanFromChatInvite(clanId: Long) {
        if (inviteJoinPendingClanId != 0L) return
        inviteJoinPendingClanId = clanId
        val observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                finalizeJoinFromChatInvite(clanId)
            }
        }
        inviteJoinClansObserver = observer
        notificationCenter.addObserver(observer, NotificationCenter.clansDidLoad)
        val timeout = Runnable { finalizeJoinFromChatInvite(clanId) }
        inviteJoinTimeout = timeout
        AndroidUtilities.runOnUIThread(timeout, 2500L)
        clansController.loadClans(force = true)
    }

    private fun finalizeJoinFromChatInvite(clanId: Long) {
        if (inviteJoinPendingClanId != clanId) return
        inviteJoinPendingClanId = 0L
        inviteJoinClansObserver?.let {
            notificationCenter.removeObserver(it, NotificationCenter.clansDidLoad)
        }
        inviteJoinClansObserver = null
        inviteJoinTimeout?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        inviteJoinTimeout = null
        clansController.selectClan(clanId, force = true)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
        popToClansFragmentFromChat()
    }

    private fun popToClansFragmentFromChat() {
        val layout = parentLayout
        if (layout == null) {
            finishFragment()
            return
        }
        val stack = ArrayList(layout.getFragmentStack())
        for (i in stack.size - 2 downTo 0) {
            val f = stack[i]
            if (f is com.mezon.mobile.home.clans.ClansFragment) {
                for (j in stack.size - 2 downTo i + 1) {
                    layout.removeFragmentFromStack(stack[j])
                }
                finishFragment()
                return
            }
        }
        finishFragment()
    }

    private fun selfMessageEchoKey(entity: MessageEntity): String =
        "${entity.code}:${parseContentText(entity.content).trim()}"

    private fun findPendingSelfEchoIndex(entity: MessageEntity): Int {
        if (!entity.isMe) return -1
        val echoKey = selfMessageEchoKey(entity)
        val contentMatch = messages.indexOfFirst { pending ->
            pending.isMe &&
                pending.senderId == entity.senderId &&
                (pending.isSending || pending.sendState == MessageEntity.SEND_STATE_ERROR) &&
                selfMessageEchoKey(pending) == echoKey
        }
        if (contentMatch >= 0) return contentMatch
        return messages.indexOfFirst { it.isSending && it.isMe && it.senderId == entity.senderId }
    }

    private fun insertSendingOptimisticMessage(entity: MessageEntity): Int {
        val existingIndex = messages.indexOfFirst { it.id == entity.id }
        if (existingIndex >= 0) {
            messages[existingIndex] = entity
            messagesDict.put(entity.id, entity)
            return existingIndex
        }
        messagesDict.put(entity.id, entity)
        val insertIndex = insertIndexForMessage(entity)
        messages.add(insertIndex, entity)
        return insertIndex
    }

    private fun refreshUI() {
        if (messages.isNotEmpty()) showMessages()
        else if (isLoading) showLoading()
        else showEmpty()
    }

    private fun showLoading() {
        errorView.visibility = View.GONE
        if (!showLoadingPending) {
            showLoadingPending = true
            mainHandler.postDelayed(showLoadingRunnable, LOADING_INDICATOR_DELAY_MS)
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
        mainHandler.removeCallbacks(showLoadingRunnable)
        showLoadingPending = false
    }

    private fun forceScrollToBottom() {
        unreadDecoration.clear()
        recyclerView.invalidateItemDecorations()
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
        recyclerView.invalidateItemDecorations()
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

    private fun onPageDownClicked() {
        jumpToPresent()
    }

    private fun jumpToPresent() {
        newUnreadCount = 0
        isViewingOlder = false
        hasMoreBottom = false
        hasUnread = false
        needScrollRestore = false
        pausedOnLastMessage = true
        pageDownButton.show(false)
        pageDownButton.setUnreadCount(0)
        unreadDecoration.clear()
        recyclerView.invalidateItemDecorations()

        val latestId = lastSentMessageId
        val alreadyLoaded = latestId != 0L && messagesDict.get(latestId) != null
        if (alreadyLoaded) {
            Log.d(TAG, "jumpToPresent: latest msg $latestId already in list, scrollToBottom")
            adapter.showLoadingDown = false
            adapter.updateRowsSafe()
            forceScrollToBottom()
            markAsRead()
        } else {
            jumpingToPresent = true
            firstLoad = true
            Log.d(TAG, "jumpToPresent: keeping current list until reload completes, loadMessages forceRefresh=true")
            chatController.loadMessages(channelId, clanId, forceRefresh = true, preferHttp = false, topicId = topicId)
        }
    }

    private fun markAsRead() {
        val newest = newestReadStateMessage() ?: return
        if (messagesDict[newest.id] == null) return
        if (clanId != 0L) {
            if (channelController.findChannelById(channelId) == null) return
        } else {
            if (dialogsController.getDialog(channelId) == null) return
        }
        if (newest.id <= lastSeenMessageId) return
        lastSeenMessageId = newest.id
        newUnreadCount = 0
        if (::pageDownButton.isInitialized) pageDownButton.setUnreadCount(0)

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
        val lastPos = lm.findLastVisibleItemPosition()

        val firstMsgIndex = firstPos - adapter.messagesStartRow
        val rawLastMsgIndex = if (lastPos == RecyclerView.NO_POSITION) firstMsgIndex
            else lastPos - adapter.messagesStartRow
        val viewportStart = firstMsgIndex.coerceAtLeast(0)
        val viewportEnd = rawLastMsgIndex.coerceAtMost(messages.size - 1)
        val viewportInBounds = firstMsgIndex < messages.size && viewportEnd >= viewportStart

        var candidateIndex = -1
        val visibleMsg = if (viewportInBounds) {
            var candidate: MessageEntity? = null
            var i = viewportStart
            while (i <= viewportEnd && candidate == null) {
                val msg = messages[i]
                if (msg.canAdvanceReadState()) {
                    candidate = msg
                    candidateIndex = i
                }
                i++
            }
            candidate
        } else {
            newestReadStateMessage()?.also { found ->
                candidateIndex = messages.indexOf(found)
            }
        } ?: return
        if (visibleMsg.id <= lastSeenMessageId) return

        lastSeenMessageId = visibleMsg.id
        val remaining = if (lastSentMessageId != 0L && visibleMsg.id < lastSentMessageId) {
            countNewerReadable(candidateIndex, visibleMsg.id)
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
        val readChannelId = if (isTopicMode) topicId else channelId
        if (isTopicMode) {
            channelController.updateLastSeen(topicId, msgId, ts)
            chatController.updateLastSeenMessage(
                channelId,
                clanId,
                channelType,
                msgId,
                ts,
                badgeCount = 0,
                applyLocal = false
            )
            topicBadgeTracker.resetTopic(topicId)
            return
        }
        chatController.updateLastSeenMessage(
            readChannelId, clanId, channelType,
            msgId, ts, badgeCount = badge
        )
    }

    private fun requestTopicBadgeHydrate(debounceMs: Long) {
        if (isTopicMode || clanId == 0L) return
        topicBadgeHydrateJob?.cancel()
        topicBadgeHydrateJob = appScope.launch(ioDispatcher) {
            if (debounceMs > 0L) delay(debounceMs)
            topicBadgeTracker.hydrateForParentChannel(clanId, channelId)
            withContext(mainDispatcher) {
                if (isPaused || isTopicMode || fragmentView == null) return@withContext
                updateVisibleRows(NotificationCenter.UPDATE_MASK_TOPIC)
            }
        }
    }

    private fun refreshTopicRootRowsFromCache() {
        if (isTopicMode || messages.isEmpty()) return
        val roots = messages.mapIndexedNotNull { index, msg ->
            if (msg.isTopicRootMessage) index to msg else null
        }
        if (roots.isEmpty()) return
        appScope.launch(ioDispatcher) {
            val ids = roots.map { it.second.id }
            val freshMap = chatController.getMessagesByIds(channelId, ids)
            val updates = ArrayList<Pair<Int, MessageEntity>>()
            for ((index, msg) in roots) {
                val fresh = freshMap[msg.id] ?: continue
                if (fresh.rplCount != msg.rplCount ||
                    fresh.lastSentSeconds != msg.lastSentSeconds ||
                    fresh.content != msg.content
                ) {
                    updates.add(index to fresh)
                }
            }
            if (updates.isEmpty()) return@launch
            withContext(mainDispatcher) {
                if (isPaused || isTopicMode || fragmentView == null) return@withContext
                for ((index, fresh) in updates) {
                    if (index < messages.size && messages[index].id == fresh.id) {
                        messages[index] = fresh
                        messagesDict.put(fresh.id, fresh)
                    }
                }
                updateVisibleRows(NotificationCenter.UPDATE_MASK_TOPIC)
            }
        }
    }

    private fun updatePageDownVisibility() {
        if (!initialApiDone) return
        val shouldShow = isViewingOlder || hasMoreBottom
        pageDownButton.show(shouldShow)
    }

    private fun applyInitialUnreadCount() {
        if (!hasUnread || dividerSeenMessageId == 0L) return
        if (newUnreadCount > 0) return
        val count = messages.count { it.id > dividerSeenMessageId }
        val estimate = if (count > 0) count else if (lastSentMessageId != 0L && dividerSeenMessageId < lastSentMessageId) {
            ((lastSentMessageId ushr 22) - (dividerSeenMessageId ushr 22)).toInt().coerceIn(1, 999)
        } else 0
        if (estimate > 0) {
            newUnreadCount = estimate
            if (::pageDownButton.isInitialized) pageDownButton.setUnreadCount(estimate)
        }
    }

    private fun getScrollingOffsetForView(v: android.view.View): Int {
        return recyclerView.measuredHeight - v.bottom - recyclerView.paddingBottom
    }

    private fun trimViewportOldest(): Boolean {
        var trimmed = false
        while (messages.size > VIEWPORT_LIMIT) {
            val removed = messages.removeAt(messages.size - 1)
            messagesDict.delete(removed.id)
            hasMoreTop = true
            trimmed = true
        }
        if (trimmed) pruneEmbedFormState()
        return trimmed
    }

    private fun trimViewportNewest(): Boolean {
        var trimmed = false
        while (messages.size > VIEWPORT_LIMIT) {
            val removed = messages.removeAt(0)
            messagesDict.delete(removed.id)
            hasMoreBottom = true
            trimmed = true
        }
        if (trimmed) pruneEmbedFormState()
        return trimmed
    }

    private fun pruneEmbedFormState() {
        val ids = HashSet<Long>(messages.size)
        for (msg in messages) {
            if (msg.isEphemeral || hasEmbedControlPayload(msg.content)) ids.add(msg.id)
        }
        EmbedFormUtil.retainMessages(ids)
    }

    private fun updateUnreadDividerPosition() {
        val before = unreadDecoration.firstUnreadAdapterPosition
        try {
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
        } finally {
            if (before != unreadDecoration.firstUnreadAdapterPosition) {
                recyclerView.invalidateItemDecorations()
            }
        }
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

    private fun handleChatPollTap(msg: MessageEntity, parsed: ParsedPoll, tap: PollTap) {
        when (tap) {
            is PollTap.ToggleOption -> {
                if (parsed.isClosed) return
                if (pollExpired(parsed)) return
                val st0 = pollStates[msg.id] ?: PollLocalState()
                if (st0.showResultsPreview) return
                if (resolvedVotedList(parsed, msg).isNotEmpty()) return
                val idx = tap.answerIndex
                val nextSel = if (parsed.isMultiple) {
                    val m = st0.selection.toMutableSet()
                    if (!m.add(idx)) m.remove(idx)
                    m
                } else {
                    if (st0.selection.contains(idx)) emptySet() else setOf(idx)
                }
                pollStates[msg.id] = st0.copy(selection = nextSel)
                refreshPollCell(msg.id)
            }
            PollTap.PrimaryAction -> handlePollPrimaryAction(msg, parsed)
            PollTap.ViewDetails, PollTap.FooterStats -> openPollDetailSheet(msg, parsed)
            PollTap.ToggleExpandOptions -> {
                val st = pollStates[msg.id] ?: PollLocalState()
                pollStates[msg.id] = st.copy(optionsExpanded = !st.optionsExpanded)
                refreshPollCell(msg.id)
            }
        }
    }

    private fun pollExpired(parsed: ParsedPoll): Boolean {
        val now = System.currentTimeMillis() / 1000L
        return parsed.expireAtSeconds > 0 && parsed.expireAtSeconds < now
    }

    /**
     * Session optimistic indices, else JSON [voter_details], else [PollVotePersistence].
     * Must match [ChatPollBridge.getLocalState] so the poll card UI keeps "voted" after leaving chat.
     */
    private fun effectivePollMyAnswerIndices(messageId: Long): List<Int> {
        val st = pollStates[messageId] ?: PollLocalState()
        if (st.optimisticMyIndices != null) return st.optimisticMyIndices!!
        val msg = messagesDict.get(messageId)
        val parsed = msg?.takeIf { it.isPollMessage }?.let { parsePollContent(it.content) }
        if (parsed != null) {
            val fromJson = votedAnswerIndices(parsed, currentUserIdLong())
            if (fromJson.isNotEmpty()) return fromJson
        }
        return PollVotePersistence.peek(messageId) ?: emptyList()
    }

    private fun resolvedVotedList(parsed: ParsedPoll, msg: MessageEntity): List<Int> =
        effectivePollMyAnswerIndices(msg.id)

    private fun currentUserIdLong(): Long = StartupCache.userId.toLongOrNull() ?: 0L

    private fun handlePollPrimaryAction(msg: MessageEntity, parsed: ParsedPoll) {
        val expired = pollExpired(parsed)
        val st = pollStates[msg.id] ?: PollLocalState()
        val hasVoted = resolvedVotedList(parsed, msg).isNotEmpty()
        when (
            resolvePollPrimaryIntent(parsed, st, hasVoted, expired)
        ) {
            null -> return
            PollPrimaryIntent.BackToVote -> {
                pollStates[msg.id] = st.copy(showResultsPreview = false)
                refreshPollCell(msg.id)
            }
            PollPrimaryIntent.RemoveVote -> submitPollVote(msg, parsed, emptyList())
            PollPrimaryIntent.CastVote -> submitPollVote(msg, parsed, st.selection.sorted())
            PollPrimaryIntent.ShowResultsPreview -> {
                pollStates[msg.id] = st.copy(showResultsPreview = true)
                refreshPollCell(msg.id)
                requestPollCountsRefresh(msg.id, msg.channelId)
            }
        }
    }

    private fun submitPollVote(msg: MessageEntity, parsed: ParsedPoll, indices: List<Int>) {
        appScope.launch(Dispatchers.Main) {
            try {
                val resp = sessionManager.withAutoRefresh { session ->
                    mezonApi.votePoll(session.apiUrl, session.token, msg.channelId, msg.id, parsed.pollId, indices)
                }
                val my = resp.myAnswerIndicesList.toList()
                val base = pollStates[msg.id] ?: PollLocalState()
                pollStates[msg.id] = base.copy(
                    optimisticMyIndices = my,
                    selection = my.toSet(),
                    showResultsPreview = false
                )
                PollVotePersistence.remember(msg.id, my)
                refreshPollCell(msg.id)

                requestPollCountsRefresh(msg.id, msg.channelId)
            } catch (e: Exception) {
                Log.e(TAG, "votePoll failed", e)
                MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.ERROR, getString(R.string.poll_vote_failed))
            }
        }
    }

    private fun submitEmbedComponentButton(msg: MessageEntity, buttonId: String) {
        if (msg.isSending) return
        val uid = currentUserIdLong()
        appScope.launch(Dispatchers.IO) {
            try {
                sessionManager.withAutoRefresh { session ->
                    mezonApi.messageButtonClick(
                        session.apiUrl,
                        session.token,
                        msg.id,
                        msg.channelId,
                        buttonId,
                        msg.senderId,
                        uid,
                        EmbedFormUtil.buildExtraDataJson(msg.id),
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    MezonToast.show(
                        this@ChatFragment,
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.embed_form_submit_failed),
                    )
                }
            }
        }
    }

    private fun requestPollCountsRefresh(messageId: Long, msgChannelId: Long) {
        if (msgChannelId != channelId) return
        val msgEntity = messagesDict.get(messageId) ?: return
        if (!msgEntity.isPollMessage) return
        val basePoll = parsePollContent(msgEntity.content) ?: return
        appScope.launch(Dispatchers.IO) {
            val merged = try {
                sessionManager.withAutoRefresh { session ->
                    val r = mezonApi.getPoll(session.apiUrl, session.token, msgChannelId, messageId, basePoll.pollId)
                    mergePollFromGetResponse(basePoll, r)
                }
            } catch (e: Exception) {
                Log.w(TAG, "getPoll after vote", e)
                null
            }
            if (merged == null) return@launch
            withContext(Dispatchers.Main) {
                val cur = messagesDict.get(messageId) ?: return@withContext
                if (!cur.isPollMessage) return@withContext
                val uid = currentUserIdLong()
                if (uid != 0L) {
                    val inferred = votedAnswerIndices(merged, uid)
                    val breakdown = merged.voterDetails.isNotEmpty()
                    when {
                        inferred.isNotEmpty() ->
                            PollVotePersistence.remember(messageId, inferred)
                        breakdown ->
                            PollVotePersistence.remember(messageId, emptyList())
                        else -> {}
                    }
                }
                pollStates[messageId] = (pollStates[messageId] ?: PollLocalState()).copy(displayMergedPoll = merged)
                refreshPollCell(messageId)
            }
        }
    }

    /** After re-opening chat or loading history, refill tallies for polls we voted on locally. */
    private fun refreshPollSnapshotsForStoredVotes() {
        if (fragmentView == null || messages.isEmpty()) return
        val seen = mutableSetOf<Long>()
        for (m in messages) {
            if (!m.isPollMessage) continue
            val p = PollVotePersistence.peek(m.id) ?: continue
            if (p.isEmpty()) continue
            if (seen.add(m.id)) requestPollCountsRefresh(m.id, m.channelId)
            if (seen.size >= 32) break
        }
    }

    /**
     * Other users' votes often do not arrive as a WebSocket message with full poll JSON.
     * Refresh [GetPoll] for items on screen on an interval and after scroll settles (soft realtime).
     */
    private fun startVisiblePollTallyRefreshLoop() {
        pollTallyRefreshJob?.cancel()
        pollTallyRefreshJob = appScope.launch {
            while (isActive) {
                delay(POLL_TALLY_TICK_MS)
                if (isPaused) continue
                refreshVisiblePollTalliesFromServer()
            }
        }
    }

    private fun stopVisiblePollTallyRefreshLoop() {
        pollTallyRefreshJob?.cancel()
        pollTallyRefreshJob = null
        pollTallyLastRequestedAtMs.clear()
    }

    private fun refreshVisiblePollTalliesFromServer() {
        if (!::recyclerView.isInitialized || !::adapter.isInitialized) return
        if (isPaused || fragmentView == null) return
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        val now = android.os.SystemClock.elapsedRealtime()
        var quota = POLL_TALLY_MAX_PER_TICK
        for (pos in first..last) {
            if (quota <= 0) break
            val msg = adapter.getMessage(pos) ?: continue
            if (!msg.isPollMessage) continue
            val lastAt = pollTallyLastRequestedAtMs[msg.id] ?: 0L
            if (now - lastAt < POLL_TALLY_MIN_GAP_MS) continue
            pollTallyLastRequestedAtMs[msg.id] = now
            quota--
            requestPollCountsRefresh(msg.id, msg.channelId)
        }
    }

    private fun openPollDetailSheet(msg: MessageEntity, seed: ParsedPoll) {
        val ctx = getContext() ?: return
        val act = getParentActivity()
        if (act != null && (act.isFinishing || act.isDestroyed)) return
        try {
            PollDetailModal(
                context = ctx,
                themeColors = themeColors,
                scope = appScope,
                seedParsed = seed,
                loadPoll = {
                    sessionManager.withAutoRefresh { session ->
                        val r = mezonApi.getPoll(session.apiUrl, session.token, msg.channelId, msg.id, seed.pollId)
                        mergePollFromGetResponse(seed, r)
                    }
                },
                memberResolver = { uid ->
                    try {
                        memberResolver.resolveMember(uid, clanId, channelId, channelType)
                    } catch (e: Exception) {
                        Log.w(TAG, "resolveMember for poll detail", e)
                        null
                    }
                }
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "openPollDetailSheet", e)
            MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.ERROR, getString(R.string.poll_detail_open_failed))
        }
    }

    private fun refreshPollCell(messageId: Long) {
        if (fragmentView == null) return
        val n = recyclerView.childCount
        for (i in 0 until n) {
            val cell = recyclerView.getChildAt(i) as? ChatMessageCell ?: continue
            if (cell.messageEntity?.id == messageId) {
                val updated = messagesDict.get(messageId) ?: continue
                cell.update(0, updated)
                break
            }
        }
    }

    private fun chatClanCreatorId(): Long {
        if (clanId == 0L) return 0L
        val cached = chatCachedCreatorId
        if (cached != null) return cached
        val id = clansController.clans.value.firstOrNull { it.clanId == clanId }?.creatorId ?: 0L
        chatCachedCreatorId = id
        return id
    }

    private fun chatDisplayRoleResolver(): (Long) -> UserDisplayRole? = { userId ->
        if (clanId == 0L) null
        else roleController.resolveHighestDisplayRole(clanId, userId, chatClanCreatorId())
    }

    private fun refreshChatDisplayRoleCache(refreshUi: Boolean = true) {
        if (clanId == 0L) return
        displayRoleCacheRefreshJob?.cancel()
        roleController.invalidateDisplayRoleCache(clanId)
        val creatorId = chatClanCreatorId()
        displayRoleCacheRefreshJob = appScope.launch(ioDispatcher) {
            roleController.refreshDisplayRoleCache(clanId, creatorId)
            withContext(mainDispatcher) {
                displayRoleCacheRefreshJob = null
                if (refreshUi) {
                    refreshChatSenderRoleRows()
                } else {
                    pendingDisplayRoleUiRefresh = true
                }
            }
        }
    }

    private fun refreshChatSenderRoleRows() {
        if (isPaused || fragmentView == null) return
        if (isTopicMode) {
            topicRootHeader?.refreshDisplayRole()
            if (::adapter.isInitialized) adapter.notifyTopicRootHeaderChanged()
        }
        updateVisibleRows(NotificationCenter.UPDATE_MASK_NAME)
    }

    private fun channelGalleryImageUrls(): List<String> =
        channelGalleryController.getItems(channelId)
            .asSequence()
            .filter { !it.isVideo && it.url.isNotEmpty() }
            .map { it.url }
            .distinct()
            .toList()

    private fun buildPhotoViewerUrls(selectedUrl: String, seedUrls: List<String> = emptyList()): List<String> {
        val base = channelGalleryImageUrls()
        if (base.isEmpty()) {
            return when {
                seedUrls.isNotEmpty() -> seedUrls
                selectedUrl.isEmpty() -> emptyList()
                else -> listOf(selectedUrl)
            }
        }
        return if (selectedUrl.isEmpty() || base.contains(selectedUrl)) base else listOf(selectedUrl) + base
    }

    private fun openChannelPhotoViewer(context: Context, url: String, thumbBmp: Bitmap?, seedUrls: List<String>) {
        val viewer = PhotoViewer(context)
        activePhotoViewer = viewer
        photoViewerSelectedUrl = url
        viewer.onCurrentUrlChanged = { photoViewerSelectedUrl = it }
        viewer.onReachedOldestEdge = { channelGalleryController.fetchOlderIfNeeded(channelId, clanId) }
        viewer.setOnDismissListener {
            if (activePhotoViewer === viewer) activePhotoViewer = null
        }
        val initial = buildPhotoViewerUrls(url, seedUrls)
        val idx = initial.indexOf(url).coerceAtLeast(0)
        viewer.show(url, gallery = initial, index = idx, thumbBitmap = thumbBmp)
        if (channelGalleryController.isInitialLoadFinished(channelId)) {
            refreshActivePhotoViewerGallery()
        } else {
            channelGalleryController.ensureLoaded(channelId, clanId)
        }
    }

    private fun refreshActivePhotoViewerGallery() {
        val viewer = activePhotoViewer ?: return
        val urls = buildPhotoViewerUrls(photoViewerSelectedUrl)
        if (urls.isEmpty()) return
        viewer.updateGallery(urls, photoViewerSelectedUrl)
    }

    private fun messageListHasPendingUploadKey(key: String): Boolean {
        if (key.isEmpty()) return false
        for (msg in messages) {
            val filter = PresignFinishContent.PresignFilterContext.from(msg.content, msg.timestampSeconds)
            if (msg.attachmentUrl == key && msg.isAttachmentUploadPending(key, filter)) return true
            for (extra in msg.extraAttachments) {
                if (extra.url == key && msg.isAttachmentUploadPending(extra.url, filter)) return true
            }
        }
        return false
    }

    private fun messageListHasAttachmentUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        for (msg in messages) {
            if (msg.attachmentUrl == url) return true
            if (msg.extraAttachments.any { it.url == url }) return true
        }
        return false
    }

    private fun updateVisibleRows(mask: Int = 0) {
        if (isPaused) return
        if (scrollingManually) {
            if (mask != 0) {
                pendingPartialUpdateMask = pendingPartialUpdateMask or mask
            } else {
                pendingFullVisibleUpdate = true
            }
            return
        }
        val count = recyclerView.childCount
        for (i in 0 until count) {
            when (val child = recyclerView.getChildAt(i)) {
                is ChatMessageCell -> {
                    val msg = child.messageEntity ?: continue
                    val updated = messagesDict.get(msg.id) ?: continue
                    val modelIdx = messages.indexOfFirst { it.id == msg.id }
                    if (modelIdx >= 0) {
                        child.isCombined = adapter.isCombinedAt(modelIdx)
                    }
                    if (mask == 0) {
                        if (updated !== msg) child.update(0, updated)
                    } else {
                        val entityDerivedOnly = mask and (
                            NotificationCenter.UPDATE_MASK_MESSAGE_TEXT or
                                NotificationCenter.UPDATE_MASK_BADGE or
                                NotificationCenter.UPDATE_MASK_READ_DIALOG_MESSAGE
                            ).inv() == 0
                        if (entityDerivedOnly && updated === msg && updated.content == msg.content) continue
                        child.update(mask, updated)
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

    private fun mergeDuplicateIncomingMessage(entity: MessageEntity): Boolean {
        val existing = messagesDict.get(entity.id) ?: return false
        if (existing.content == entity.content &&
            existing.code == entity.code &&
            existing.updateTimeSeconds == entity.updateTimeSeconds &&
            existing.hideEditted == entity.hideEditted &&
            existing.reactionsJson == entity.reactionsJson &&
            existing.sendState == entity.sendState &&
            existing.attachmentUrl == entity.attachmentUrl &&
            existing.extraAttachmentsJson == entity.extraAttachmentsJson &&
            existing.isError == entity.isError
        ) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "didReceiveNewMessages exact duplicate id=${entity.id} code=${entity.code}")
            }
            return true
        }
        val merged = if (entity.isMe) {
            chatController.mergeSelfSentMessageEcho(existing, entity)
        } else {
            entity.copy(sendState = MessageEntity.SEND_STATE_SENT, isError = false)
        }
        val idx = messages.indexOfFirst { it.id == entity.id }
        messagesDict.put(merged.id, merged)
        if (idx >= 0) {
            messages[idx] = merged
        }
        if (fragmentView != null) {
            var mask = 0
            if (merged.content != existing.content) {
                mask = mask or NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
            }
            if (merged.attachmentUrl != existing.attachmentUrl ||
                merged.extraAttachmentsJson != existing.extraAttachmentsJson ||
                merged.messageType != existing.messageType
            ) {
                mask = mask or NotificationCenter.UPDATE_MASK_ATTACHMENTS
            }
            if (merged.sendState != existing.sendState || merged.isError != existing.isError) {
                mask = mask or NotificationCenter.UPDATE_MASK_SEND_STATE
            }
            if (mask == 0) mask = NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
            updateVisibleRows(mask)
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "didReceiveNewMessages merged duplicate id=${entity.id} idx=$idx oldCode=${existing.code} " +
                    "newCode=${entity.code} oldContent=${debugMessagePreview(existing.content)} " +
                    "newContent=${debugMessagePreview(entity.content)}"
            )
        }
        return true
    }

    private fun debugMessagePreview(content: String): String {
        val compact = content.replace('\n', ' ').replace('\r', ' ')
        return if (compact.length > 160) compact.take(160) + "..." else compact
    }

    private fun referencedMessageId(content: String): Long = firstReferenceMessageId(content)

    private fun debugReferencedMessageId(content: String): Long = referencedMessageId(content)

    private fun referencedEmbedResponseInsertIndex(entity: MessageEntity): Int {
        val refId = referencedMessageId(entity.content)
        if (refId == 0L || refId == entity.id) return -1
        val formIndex = messages.indexOfFirst {
            it.id != entity.id &&
                hasEmbedControlPayload(it.content) &&
                it.content.contains(refId.toString())
        }
        if (formIndex >= 0 && looksLikeEmbedActionResponse(entity.content)) return formIndex
        val refIndex = messages.indexOfFirst { it.id == refId }
        if (refIndex < 0) return -1
        val referenced = messages[refIndex]
        if (!hasEmbedControlPayload(referenced.content) && !looksLikeEmbedActionResponse(entity.content)) return -1
        return refIndex
    }

    private fun MessageEntity.canAdvanceReadState(): Boolean {
        return id > 0L &&
            !isUnreadDivider &&
            !isEphemeral &&
            !isSending &&
            (!isError || hasPartialAttachmentUploadFailure)
    }

    private fun sortMessagesByIdDesc(list: MutableList<MessageEntity>) {
        list.sortByDescending { it.id }
    }

    private fun isOldestMessageEndOfHistory(): Boolean {
        val oldest = messages.lastOrNull() ?: return false
        return oldest.isWelcomeMessage || oldest.code == MessageEntity.CODE_FIRST_MESSAGE
    }

    private fun newestReadStateMessage(): MessageEntity? {
        return messages.firstOrNull { it.canAdvanceReadState() && messagesDict.get(it.id) != null }
    }

    private fun newestReadStateMessageId(): Long {
        return newestReadStateMessage()?.id ?: 0L
    }

    private fun insertIndexForMessage(entity: MessageEntity): Int {
        referencedEmbedResponseInsertIndex(entity).takeIf { it >= 0 }?.let { return it }
        if (messages.isEmpty() || entity.id >= messages.first().id) return 0
        val pos = messages.indexOfFirst { entity.id > it.id }
        return if (pos >= 0) pos else messages.size
    }

    private fun countNewerReadable(belowIndex: Int, threshold: Long): Int {
        if (belowIndex <= 0) return 0
        val upper = belowIndex.coerceAtMost(messages.size)
        var count = 0
        for (i in 0 until upper) {
            val m = messages[i]
            if (m.canAdvanceReadState() && m.id > threshold) count++
        }
        return count
    }

    private fun hasEmbedControlPayload(content: String): Boolean {
        return content.contains("\"components\"") || (content.contains("\"embed\"") && content.contains("\"fields\""))
    }

    private fun looksLikeEmbedActionResponse(content: String): Boolean {
        if (!content.contains("\"references\"") ||
            !content.contains("\"message_id\"") ||
            !content.contains("\"type\":\"pre\"")
        ) return false
        return try {
            val obj = org.json.JSONObject(content)
            val refs = obj.optJSONArray("references") ?: return false
            var hasZeroRef = false
            for (i in 0 until refs.length()) {
                val r = refs.optJSONObject(i) ?: continue
                val mid = r.opt("message_id")
                if (mid == "0" || mid == 0 || mid == 0L) {
                    hasZeroRef = true
                    break
                }
            }
            if (!hasZeroRef) return false
            val mk = obj.optJSONArray("mk") ?: return false
            for (i in 0 until mk.length()) {
                if (mk.optJSONObject(i)?.optString("type") == "pre") return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun debugMessageAt(index: Int): String {
        if (index !in messages.indices) return "none"
        val msg = messages[index]
        return "${msg.id}/${msg.timestampSeconds}/${msg.code}"
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

    private data class CallPeer(
        val userId: Long,
        val displayName: String,
        val username: String,
        val avatarUrl: String?
    )

    private fun resolveDmCallPeerForCallback(msg: MessageEntity): CallPeer? {
        val myId = chatController.getCurrentUserId()
        val parts = dialogsController.getParticipants(channelId)
        val o = parts.firstOrNull { it.userId != myId }
        if (o != null) {
            val name = o.displayName.ifBlank { o.username.ifBlank { "User" } }
            Log.d(TAG, "resolveDmCallPeerForCallback peer=participants userId=${o.userId} msgId=${msg.id}")
            return CallPeer(o.userId, name, o.username, o.avatarUrl.ifBlank { null })
        }
        if (msg.senderId != myId) {
            Log.d(TAG, "resolveDmCallPeerForCallback peer=senderId userId=${msg.senderId} msgId=${msg.id}")
            return CallPeer(
                msg.senderId,
                msg.senderName.ifBlank { "User" },
                msg.senderUsername,
                msg.senderAvatar.ifBlank { null }
            )
        }
        val dm = dialogsController.getDialog(channelId)
        if (dm != null && dm.otherUserId != 0L && dm.otherUserId != myId) {
            val name = dm.displayName.ifBlank { dm.label.ifBlank { "User" } }
            Log.d(
                TAG,
                "resolveDmCallPeerForCallback peer=dialog otherUserId=${dm.otherUserId} msgId=${msg.id}"
            )
            return CallPeer(dm.otherUserId, name, dm.username, dm.avatarUrl.ifBlank { null })
        }
        Log.w(
            TAG,
            "resolveDmCallPeerForCallback no peer msgId=${msg.id} channelId=$channelId myId=$myId senderId=${msg.senderId} " +
                "participantsSize=${parts.size} dmOther=${dm?.otherUserId}"
        )
        return null
    }

    private fun isDmSelfOnlyChat(): Boolean {
        if (channelType != CHANNEL_TYPE_DM || clanId != 0L) return false
        val myId = chatController.getCurrentUserId()
        if (myId == 0L) return false
        val participants = dialogsController.getParticipants(channelId)
        if (participants.isNotEmpty()) {
            return participants.all { it.userId == myId }
        }
        val dm = dialogsController.getDialog(channelId) ?: return false
        if (dm.otherUserId == 0L) return false
        return dm.otherUserId == myId
    }

    private fun refreshDmHeaderTitleFromDialog() {
        if (clanId != 0L) return
        if (channelType != CHANNEL_TYPE_DM && channelType != CHANNEL_TYPE_GROUP) return
        val dm = dialogsController.getDialog(channelId) ?: return
        val nextName = dm.displayName.ifBlank { dm.label }.ifBlank { channelName }
        if (nextName.isBlank()) return
        if (nextName != channelName) {
            channelName = nextName
        }
        actionBar?.setTitle(nextName)
    }

    private fun refreshClanHeaderFromChannel() {
        if (clanId == 0L || isTopicMode) return
        val entity = channelController.findChannelById(channelId, clanId)
            ?: channelController.findChannelById(channelId)
        val nextName = entity?.channelLabel?.ifBlank { channelName } ?: channelName
        if (nextName.isBlank()) return
        val channelNameChanged = nextName != channelName
        if (channelNameChanged) {
            channelName = nextName
        }
        if (::adapter.isInitialized) {
            val adapterNameChanged = adapter.channelName != nextName
            adapter.channelName = nextName
            if (channelNameChanged || adapterNameChanged) {
                adapter.notifyChannelNameDependentCellsChanged()
            }
        }
        val bar = actionBar as? ActionBarView ?: return
        val iconEnum = resolveChannelIcon(entity)
        val iconPx = channelTitleIconSizePx()
        val d = channelTitleIconDrawable(bar.context, iconEnum)
        bar.setTitleStartIcon(d, iconPx, LayoutHelper.dp(6))
        bar.setTitle(channelName)
        bar.setSubtitleStartPadding(0)
        bar.setSubtitleColor(themeColors.onSurfaceVariant)
        if (entity?.parentId != null && entity.parentId != 0L) {
            val parent = channelController.findChannelById(entity.parentId)
            if (parent != null) {
                bar.setSubtitle(parent.channelLabel)
            } else {
                bar.setSubtitle(null)
            }
        } else if (channelType == CHANNEL_TYPE_THREAD || routeParentId != 0L) {
            val parent = channelController.findChannelById(routeParentId)
            if (parent != null) {
                bar.setSubtitle(parent.channelLabel)
            } else {
                bar.setSubtitle(null)
            }
        } else {
            bar.setSubtitle(null)
        }
        bar.requestLayout()
    }

    private fun refreshThreadWelcomeCreator() {
        if (channelType != CHANNEL_TYPE_THREAD || !::adapter.isInitialized) return
        val creator = messages.lastOrNull { it.isNormalMessage && it.senderId != 0L }
        val nextName = if (creator != null) {
            resolveCreatorDisplayName(creator.senderId, creator.senderName)
        } else {
            ""
        }
        val sanitized = if (nextName.equals("system", ignoreCase = true)) "" else nextName
        if (sanitized != adapter.welcomeCreatorName) {
            adapter.welcomeCreatorName = sanitized
            adapter.notifyWelcomeCellChanged()
        }
    }

    private fun resolveCreatorDisplayName(userId: Long, fallbackName: String): String {
        if (userId == 0L) return fallbackName
        val member = if (clanId != 0L) {
            memberResolver.resolveClanScopedMember(userId, clanId, channelId, channelType)
        } else {
            memberResolver.resolveMember(userId, clanId, channelId, channelType)
        }
        val clanNick = member?.clanNick?.trim().orEmpty()
        if (clanNick.isNotBlank()) return clanNick
        val fromMessage = fallbackName.trim()
        if (fromMessage.isNotBlank()) return fromMessage
        val memberDisplay = member?.displayName?.trim().orEmpty()
        if (memberDisplay.isNotBlank()) return memberDisplay
        if (userId == userController.userId) {
            val self = userController.displayName.trim()
            if (self.isNotBlank()) return self
        }
        val global = userClanController.getUserById(userId)?.displayName?.trim().orEmpty()
        if (global.isNotBlank()) return global
        return fallbackName
    }

    private fun refreshWelcomeFromDialog() {
        if (clanId != 0L || !::adapter.isInitialized) return
        if (channelType != CHANNEL_TYPE_DM && channelType != CHANNEL_TYPE_GROUP) return
        val dm = dialogsController.getDialog(channelId)
        val nextChannelName = if (channelType == CHANNEL_TYPE_DM) {
            dm?.displayName?.ifBlank { dm.label }?.ifBlank { channelName } ?: channelName
        } else if (channelType == CHANNEL_TYPE_GROUP) {
            dm?.displayName?.ifBlank { dm.label }?.ifBlank { channelName } ?: channelName
        } else {
            adapter.channelName
        }
        val nextAvatarUrl = dm?.avatarUrl.orEmpty()
        val nextAvatarId = if (channelType == CHANNEL_TYPE_DM) {
            dm?.otherUserId?.takeIf { it != 0L } ?: channelId
        } else {
            channelId
        }
        val nextPlaceholderKey = dm?.avatarPlaceholderKey() ?: channelName
        val nextPeerUsername = if (channelType == CHANNEL_TYPE_DM) dm?.username.orEmpty() else ""
        if (nextAvatarUrl == lastWelcomeAvatarUrl &&
            nextAvatarId == lastWelcomeAvatarId &&
            nextPlaceholderKey == lastWelcomePlaceholderKey &&
            nextPeerUsername == lastWelcomePeerUsername &&
            nextChannelName == lastWelcomeChannelName
        ) {
            return
        }
        lastWelcomeAvatarUrl = nextAvatarUrl
        lastWelcomeAvatarId = nextAvatarId
        lastWelcomePlaceholderKey = nextPlaceholderKey
        lastWelcomePeerUsername = nextPeerUsername
        lastWelcomeChannelName = nextChannelName
        if ((channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP) && nextChannelName.isNotEmpty()) {
            adapter.channelName = nextChannelName
        }
        adapter.welcomeAvatarUrl = nextAvatarUrl
        adapter.welcomeAvatarId = nextAvatarId
        adapter.welcomePlaceholderKey = nextPlaceholderKey
        adapter.welcomePeerUsername = nextPeerUsername
        adapter.notifyWelcomeCellChanged()
    }

    private fun dmHeaderCallOtherUserId(): Long? {
        val myId = chatController.getCurrentUserId()
        dialogsController.getParticipants(channelId).firstOrNull { it.userId != myId }?.let { return it.userId }
        val dm = dialogsController.getDialog(channelId) ?: return null
        if (dm.otherUserId != 0L && dm.otherUserId != myId) return dm.otherUserId
        return null
    }

    private fun resolveDmCallPeerForHeader(): CallPeer? {
        val myId = chatController.getCurrentUserId()
        val o = dialogsController.getParticipants(channelId).firstOrNull { it.userId != myId }
        if (o != null) {
            val name = o.displayName.ifBlank { o.username.ifBlank { "User" } }
            return CallPeer(o.userId, name, o.username, o.avatarUrl.ifBlank { null })
        }
        val dm = dialogsController.getDialog(channelId) ?: return null
        if (dm.otherUserId == 0L || dm.otherUserId == myId) return null
        val name = dm.displayName.ifBlank { dm.label.ifBlank { "User" } }
        return CallPeer(dm.otherUserId, name, dm.username, dm.avatarUrl.ifBlank { null })
    }

    private fun setupDmHeaderCallMenu(chatActionBar: ActionBarView) {
        val participants = dialogsController.getParticipants(channelId)
        val dmCached = dialogsController.getDialog(channelId)
        Log.d(
            "DmCallMenu",
            "enter channelId=$channelId clanId=$clanId channelType=$channelType " +
                "participants=${participants.size} dmCached=${dmCached != null} " +
                "dmOtherUserId=${dmCached?.otherUserId} myId=${chatController.getCurrentUserId()}"
        )
        if (clanId != 0L || channelType != CHANNEL_TYPE_DM) {
            Log.d("DmCallMenu", "skip not-DM clanId=$clanId channelType=$channelType")
            return
        }
        if (isDmSelfOnlyChat()) {
            Log.d("DmCallMenu", "skip self-only-chat channelId=$channelId")
            return
        }
        if (participants.isEmpty()) {
            dialogsController.loadDmParticipants(channelId)
        }
        val otherId = dmHeaderCallOtherUserId()
        if (otherId != null && friendController.isUserBlocked(otherId)) {
            Log.d("DmCallMenu", "skip peer-blocked otherId=$otherId")
            return
        }
        Log.d("DmCallMenu", "proceed otherId=$otherId")
        chatActionBar.setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> {
                        if (emojiViewVisible) {
                            hideEmojiView()
                        } else {
                            finishFragment()
                        }
                    }
                    MENU_DM_VOICE_CALL -> {
                        val peer = resolveDmCallPeerForHeader() ?: run {
                            dialogsController.loadDmParticipants(channelId)
                            MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.INFO, getString(R.string.common_loading_data))
                            return
                        }
                        requestCallPermissions(needsCamera = false) {
                            runOutgoingCallAfterFullScreenIntentPrompt(
                                {
                                    callController.startCall(
                                        peer.userId,
                                        peer.displayName,
                                        peer.avatarUrl,
                                        channelId,
                                        clanId,
                                        channelType,
                                        resolveChannelPrivate(),
                                        isVideo = false,
                                        peerUsername = peer.username
                                    )
                                    presentFragment(com.mezon.mobile.home.call.CallFragment())
                                }
                            )
                        }
                    }
                }
            }
        })
        val menu = chatActionBar.createMenu()
        if (menu.getItem(MENU_DM_VOICE_CALL) != null) {
            Log.d("DmCallMenu", "menu item already exists — only refresh color")
            chatActionBar.setItemsColor(themeColors.onSurface)
            return
        }
        Log.d("DmCallMenu", "adding new MENU_DM_VOICE_CALL item iconRes=${MezonIcon.phoneCallIcon.resId}")
        val callMenuItem = menu.addItem(MENU_DM_VOICE_CALL, MezonIcon.phoneCallIcon.resId)
        callMenuItem.contentDescription = getString(R.string.user_profile_voice_call)
        val callItemLp = callMenuItem.layoutParams as LinearLayout.LayoutParams
        callItemLp.height = LayoutHelper.MATCH_PARENT
        callItemLp.gravity = Gravity.CENTER_VERTICAL
        callMenuItem.layoutParams = callItemLp
        val callIconPx = LayoutHelper.dp(DM_HEADER_CALL_ICON_DP)
        callMenuItem.iconView.scaleType = ImageView.ScaleType.FIT_CENTER
        callMenuItem.iconView.layoutParams = FrameLayout.LayoutParams(callIconPx, callIconPx, Gravity.CENTER)
        chatActionBar.setItemsColor(themeColors.onSurface)
        Log.d(
            "DmCallMenu",
            "DONE menuChildCount=${menu.childCount} menuVis=${menu.visibility} " +
                "callItemVis=${callMenuItem.visibility} actionBarParent=${chatActionBar.parent != null} " +
                "actionBarW=${chatActionBar.width} actionBarH=${chatActionBar.height} " +
                "themeOnSurface=${themeColors.onSurface}"
        )
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

    private fun resolveChannelAgeRestricted(): Boolean {
        if (clanId != 0L) {
            return channelController.findChannelById(channelId)?.isAgeRestricted ?: routeChannelAgeRestricted
        }
        return false
    }

    private fun resolveMentionMembers(): List<ClanMember> {
        return memberResolver.resolveMentionMembers(clanId, channelId, channelType)
    }

    private fun refreshSystemMessageMemberGateCache() {
        systemMessageMemberIds = resolveMentionMembers().map { it.userId.toString() }.toSet()
    }

    private fun adjustMentionTrackersForChange(editStart: Int, removedLen: Int, addedLen: Int) {
        if (mentionTrackers.isEmpty()) return
        val delta = addedLen - removedLen
        val updated = mentionTrackers.mapNotNull { m ->
            val s = m.startOffset
            val e = m.endOffset
            when {
                editStart + removedLen <= s -> m.copy(startOffset = s + delta, endOffset = e + delta)
                editStart >= e -> m
                else -> null
            }
        }
        mentionTrackers.clear()
        mentionTrackers.addAll(updated)
    }

    private fun pruneMentionTrackersAgainstText() {
        val ed = inputField.text ?: return
        val it = mentionTrackers.iterator()
        while (it.hasNext()) {
            val m = it.next()
            val s = m.startOffset
            val e = m.endOffset
            if (s < 0 || e > ed.length || s >= e) {
                it.remove()
                continue
            }
            if (ed.subSequence(s, e).toString() != m.display) {
                it.remove()
            }
        }
    }

    private fun adjustHashtagTrackersForChange(editStart: Int, removedLen: Int, addedLen: Int) {
        if (hashtagTrackers.isEmpty()) return
        val delta = addedLen - removedLen
        val updated = hashtagTrackers.mapNotNull { h ->
            val s = h.startOffset
            val e = h.endOffset
            when {
                editStart + removedLen <= s -> h.copy(startOffset = s + delta, endOffset = e + delta)
                editStart >= e -> h
                else -> null
            }
        }
        hashtagTrackers.clear()
        hashtagTrackers.addAll(updated)
    }

    private fun pruneHashtagTrackersAgainstText() {
        val ed = inputField.text ?: return
        val it = hashtagTrackers.iterator()
        while (it.hasNext()) {
            val h = it.next()
            val s = h.startOffset
            val e = h.endOffset
            if (s < 0 || e > ed.length || s >= e) {
                it.remove()
                continue
            }
            if (s >= ed.length || ed[s] != '#') {
                it.remove()
            }
        }
    }

    private fun hashtagOffsetsForTrimmed(raw: String, h: HashtagData): HashtagData? {
        val leading = raw.indexOfFirst { !it.isWhitespace() }
        if (leading < 0) return null
        val lastNonWs = raw.indexOfLast { !it.isWhitespace() }
        val endExclusive = lastNonWs + 1
        val s = h.startOffset
        val e = h.endOffset
        if (s < leading || e > endExclusive || s >= e) return null
        return h.copy(startOffset = s - leading, endOffset = e - leading)
    }

    private fun mentionOffsetsForTrimmed(raw: String, trimmed: String, m: MentionData): MentionData? {
        if (trimmed.isEmpty()) return null
        val leading = raw.indexOfFirst { !it.isWhitespace() }
        if (leading < 0) return null
        val lastNonWs = raw.indexOfLast { !it.isWhitespace() }
        val endExclusive = lastNonWs + 1
        val s = m.startOffset
        val e = m.endOffset
        if (s < leading || e > endExclusive || s >= e) return null
        return m.copy(startOffset = s - leading, endOffset = e - leading)
    }

    private fun sendMessage() {
        dismissPasteImagePopup()
        val rawInput = inputField.text?.toString() ?: ""
        val text = rawInput.trim()
        val editMsg = editingMessage
        val preservingShareContactEmbed = editMsg != null &&
            isShareContactMessage(editMsg.code, editMsg.content)
        if (text.isBlank() && pendingAttachments.isEmpty() && !preservingShareContactEmbed) return
        if (editMsg == null && !canSendMessageInCurrentChannel()) {
            refreshPermissionGates()
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.message_no_send_permission))
            return
        }

        if (editMsg == null && !awaitingOgpForSend && inputOgpPreviewData == null) {
            val pendingUrl = extractFirstInputUrl(text)
            val fetchInFlight = inputOgpFetchJob?.isActive == true
            if (pendingUrl != null && fetchInFlight && inputOgpUrl == pendingUrl &&
                dismissedInputOgpUrl != pendingUrl && failedInputOgpUrl != pendingUrl &&
                inputOgpCache[pendingUrl] == null
            ) {
                awaitingOgpForSend = true
                fragmentScope.launch(mainDispatcher) {
                    try {
                        val finished = withTimeoutOrNull(INPUT_OGP_SEND_WAIT_MS) {
                            inputOgpFetchJob?.join()
                            true
                        }
                        if (finished == null) inputOgpFetchJob?.cancel()
                    } finally {
                        awaitingOgpForSend = false
                        sendMessage()
                    }
                }
                return
            }
        }

        val isPrivate = resolveChannelPrivate()
        val references = buildReplyReferences()

        val mdResult = parseMarkdownAndStrip(text)
        val cleanedText = mdResult.cleanedText
        val mdMarkers = mdResult.markers.ifEmpty { null }
        val ogpPreview = inputOgpPreviewData
            ?: extractFirstInputUrl(cleanedText)?.let { inputOgpCache[it] }
        val ogpMarker = buildInputOgpMarker(cleanedText, ogpPreview)
        val filteredMdMarkers = if (ogpMarker == null) mdMarkers else {
            mdMarkers
                ?.filterNot {
                    it.type == "lk" &&
                        it.s < ogpMarker.e &&
                        ogpMarker.s < it.e
                }
                ?.ifEmpty { null }
        }

        val fromTrackers = mentionTrackers.mapNotNull { m ->
            val inTrimmed = mentionOffsetsForTrimmed(rawInput, text, m) ?: return@mapNotNull null
            val s = mdResult.adjustOffset(inTrimmed.startOffset)
            val e = mdResult.adjustOffset(inTrimmed.endOffset)
            if (s < 0 || e > cleanedText.length || s >= e) return@mapNotNull null
            if (cleanedText.substring(s, e) != inTrimmed.display) return@mapNotNull null
            MentionData(
                userId = inTrimmed.userId,
                roleId = inTrimmed.roleId,
                display = inTrimmed.display,
                startOffset = s,
                endOffset = e
            )
        }
        val mergedMentions = mergeAtHereMentionsFromText(cleanedText, fromTrackers)
        val mentions = mergedMentions.ifEmpty { null }

        val hashtagsFromTrackers = hashtagTrackers.mapNotNull { h ->
            val inTrimmed = hashtagOffsetsForTrimmed(rawInput, h) ?: return@mapNotNull null
            val s = mdResult.adjustOffset(inTrimmed.startOffset)
            val e = mdResult.adjustOffset(inTrimmed.endOffset)
            if (s < 0 || e > cleanedText.length || s >= e) return@mapNotNull null
            if (s >= cleanedText.length || cleanedText[s] != '#') return@mapNotNull null
            HashtagData(
                channelId = inTrimmed.channelId,
                startOffset = s,
                endOffset = e,
                clanId = inTrimmed.clanId
            )
        }
        val hashtags = hashtagsFromTrackers.ifEmpty { null }

        if (editMsg != null) {
            val emojiMarkers = buildEmojiMarkers(cleanedText)
            chatController.editMessage(
                channelId, clanId, channelType, isPrivate, editMsg.id,
                cleanedText, mentions, emojiMarkers, filteredMdMarkers, hashtags,
                existingMessage = editMsg,
                topicId = topicId,
            )
            clearEditState()
            return
        }

        Log.d(TAG, "sendMessage channelId=$channelId clanId=$clanId channelType=$channelType isPrivate=$isPrivate textLen=${cleanedText.length} attachments=${pendingAttachments.size} hasReply=${references != null} mdMarkers=${filteredMdMarkers?.size ?: 0} ogp=${ogpMarker != null} hashtags=${hashtags?.size ?: 0}")

        if (pendingAttachments.isNotEmpty()) {
            val ctx = getContext() ?: return
            val emojiMarkers = buildEmojiMarkers(cleanedText)
            chatController.sendMessageWithAttachments(
                channelId, clanId, channelType, isPrivate, cleanedText,
                ArrayList(pendingAttachments),
                ctx.contentResolver,
                references,
                mentions,
                hashtags,
                emojiMarkers,
                ogpMarker,
                topicId = topicId
            )
            clearPendingAttachments()
        } else {
            val emojiMarkers = buildEmojiMarkers(cleanedText)
            chatController.sendMessage(
                channelId, clanId, channelType, isPrivate, cleanedText,
                references, mentions, emojiMarkers, filteredMdMarkers, ogpMarker, hashtags,
                topicId = topicId
            )
        }
        inputField.text?.clear()
        emojiObjPicked.clear()
        mentionTrackers.clear()
        hashtagTrackers.clear()
        dismissedInputOgpUrl = null
        failedInputOgpUrl = null
        clearInputOgpPreview()
        clearReplyState()
    }

    private fun setupPasteImageLongPress(ctx: Context) {
        inputField.setOnLongClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (imageClipboardCoordinator.clipboardLooksLikeImage(ctx, cm)) {
                showPasteImageTooltip(ctx)
                true
            } else {
                false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            inputField.customInsertionActionModeCallback = object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    if (imageClipboardCoordinator.clipboardLooksLikeImage(ctx, cm)) {
                        return false
                    }
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

                override fun onDestroyActionMode(mode: ActionMode) {}
            }
        }
    }

    private fun dismissPasteImagePopup() {
        pasteImagePopup?.dismiss()
        pasteImagePopup = null
    }

    private fun showPasteImageTooltip(ctx: Context) {
        if (fragmentView == null) return
        dismissPasteImagePopup()
        val content = PasteImagePasteTooltipContent(ctx, themeColors) {
            tryPasteImageFromClipboard(ctx)
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = true
            isFocusable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = LayoutHelper.dpf(24f)
            }
        }
        pasteImagePopup = popup
        val offsetY = -(content.measuredHeight + LayoutHelper.dp(50f))
        popup.showAsDropDown(inputField, 0, offsetY)
    }

    private fun tryPasteImageFromClipboard(ctx: Context) {
        dismissPasteImagePopup()
        if (!ensureCanSendMessageOrNotify()) return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val uri = imageClipboardCoordinator.resolvePasteImageUri(ctx, cm)
        if (uri == null) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.message_paste_failed))
            return
        }
        appScope.launch {
            val item = imageClipboardCoordinator.duplicateClipUriToAttachment(ctx, uri)
            withContext(mainDispatcher) {
                if (item == null) {
                    MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.ERROR, getString(R.string.message_paste_failed))
                    return@withContext
                }
                pendingAttachments.add(item)
                updateAttachmentPreview()
                updateSendButtonState()
            }
        }
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
        if (!ensureCanSendMessageOrNotify()) return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        openMediaAttachAlert()
    }

    private fun openMediaAttachAlert() {
        if (!hasMediaPermission()) {
            if (mediaPermissionDeniedOnce && !shouldShowMediaPermissionRationale()) {
                showOpenMediaSettingsDialog()
                return
            }
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

    private fun shouldShowMediaPermissionRationale(): Boolean {
        val activity = getParentActivity() ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES) ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun computeMediaPermissionGrantedFromResult(
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (grantResults.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            var imagesOk = false
            var videoOk = false
            for (i in permissions.indices) {
                if (i >= grantResults.size) break
                when (permissions[i]) {
                    Manifest.permission.READ_MEDIA_IMAGES ->
                        if (grantResults[i] == PackageManager.PERMISSION_GRANTED) imagesOk = true
                    Manifest.permission.READ_MEDIA_VIDEO ->
                        if (grantResults[i] == PackageManager.PERMISSION_GRANTED) videoOk = true
                }
            }
            return imagesOk || videoOk
        }
        return grantResults[0] == PackageManager.PERMISSION_GRANTED
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
        dismissPasteImagePopup()
        val ctx = getContext() ?: return
        val preselected = pendingAttachments.filter { !it.isFileType }
        val alert = ChatAttachAlert(ctx, mediaController, themeColors, preselected)
        alert.attachDelegate = object : ChatAttachAlert.ChatAttachAlertDelegate {
            override fun canSelectMore(): Boolean {
                return pendingAttachments.size < AttachmentPickerItem.GALLERY_MAX_SELECTION
            }

            override fun onSelectionChanged(item: AttachmentPickerItem, selected: Boolean) {
                if (selected) {
                    if (pendingAttachments.any { it.id == item.id }) return
                    pendingAttachments.add(item)
                } else {
                    pendingAttachments.removeAll { it.id == item.id }
                }
                updateAttachmentPreview()
                updateSendButtonState()
            }

            override fun onFilesRequested() {
                if (!ensureCanSendMessageOrNotify()) return
                launchDocumentPicker()
            }

            override fun onSendRequested() {
                if (pendingAttachments.isEmpty()) return
                updateAttachmentPreview()
                updateSendButtonState()
            }
        }
        alert.setDrawNavigationBar(true)
        alert.show()
    }

    private fun openCreatePollScreen() {
        if (!canCreatePoll(channelType)) return
        val frag = CreatePollFragment.newInstance(channelId, clanId)
        frag.onPollSubmit = { payload -> submitCreatePoll(payload) }
        if (!presentFragment(frag)) {
            presentFragment(frag, removeLast = false, forceWithoutAnimation = true)
        }
    }

    private suspend fun submitCreatePoll(payload: PollSubmitPayload): Boolean {
        if (!createPollInFlight.compareAndSet(false, true)) return false
        return try {
            val response = withContext(ioDispatcher) {
                sessionManager.withAutoRefresh { session ->
                    mezonApi.createPoll(
                        session.apiUrl,
                        session.token,
                        channelId,
                        clanId,
                        payload.question,
                        answerLabels = payload.answers,
                        payload.expireHours,
                        payload.pollType
                    )
                }
            }
            if (response.messageId == 0L) {
                Log.e(TAG, "createPoll: missing message_id pollId=${response.pollId}")
                withContext(mainDispatcher) {
                    MezonToast.show(
                        this@ChatFragment,
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.poll_create_failed)
                    )
                }
                return false
            }
            withContext(mainDispatcher) {
                chatController.publishCreatedPollMessage(channelId, clanId, channelType, response)
            }
            appScope.launch(ioDispatcher) {
                var confirmed = chatController.awaitChannelMessage(channelId, response.messageId)
                if (!confirmed) {
                    confirmed = chatController.reloadChannelMessageIfMissing(
                        channelId, clanId, response.messageId
                    )
                }
                if (!confirmed) {
                    Log.w(TAG, "createPoll: no ChannelMessage yet id=${response.messageId}; optimistic shown")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "createPoll failed", e)
            withContext(mainDispatcher) {
                MezonToast.show(
                    this@ChatFragment,
                    ToastOverlay.ToastType.ERROR,
                    getString(R.string.poll_create_failed)
                )
            }
            false
        } finally {
            createPollInFlight.set(false)
        }
    }

    private fun showAdvancedFunctionMenu() {
        dismissPasteImagePopup()
        if (!ensureCanSendMessageOrNotify()) return
        val ctx = getContext() ?: return
        val isAnon = anonymousController.isAnonymous(clanId)
        val alert = AdvancedAttachAlert(
            ctx,
            themeColors,
            clanId,
            isAnon,
            showCreatePoll = canCreatePoll(channelType)
        )
        alert.advancedDelegate = object : AdvancedAttachAlert.AdvancedAttachAlertDelegate {
            override fun onLocationSelected() {
                if (!ensureCanSendMessageOrNotify()) return
                requestLocationAndSend()
            }
            override fun onFilesSelected() {
                if (!ensureCanSendMessageOrNotify()) return
                launchDocumentPicker()
            }
            override fun onBuzzSelected() {
                if (!ensureCanSendMessageOrNotify()) return
                showBuzzConfirmDialog()
            }
            override fun onAnonymousToggled() {
                anonymousController.toggleAnonymous(clanId)
            }
            override fun onCreatePollRequested() {
                openCreatePollScreen()
            }
            override fun onShareContactSelected() {
                if (!ensureCanSendMessageOrNotify()) return
                presentFragment(
                    ShareContactFragment.newInstance(channelId, clanId, channelType, resolveChannelPrivate())
                )
            }
        }
        alert.setDrawNavigationBar(true)
        alert.show()
    }

    private fun launchDocumentPicker() {
        if (!ensureCanSendMessageOrNotify()) return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.advanced_files)),
            REQUEST_CODE_PICK_FILE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE_PICK_FILE || resultCode != android.app.Activity.RESULT_OK) return
        if (!ensureCanSendMessageOrNotify()) return
        val uri = data?.data ?: return
        val ctx = getContext() ?: return
        val item = AttachmentPickerItem.fromDocumentUri(ctx, uri) ?: return

        val maxSize = AttachmentPickerItem.maxFileSizeBytes(item.mimeType)
        if (item.size > maxSize) {
            val limitText = FileUtils.formatFileSize(maxSize)
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.file_too_large, limitText))
            return
        }

        pendingAttachments.add(item)
        updateAttachmentPreview()
        updateSendButtonState()
    }

    private fun requestLocationAndSend() {
        if (!ensureCanSendMessageOrNotify()) return
        val activity = getParentActivity() ?: return
        val ctx = getContext() ?: return

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fetchCurrentLocationAndSend()
            return
        }

        val canShowRationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)

        if (!canShowRationale && locationPermissionAskedBefore) {
            showOpenLocationSettingsDialog()
            return
        }

        if (canShowRationale) {
            com.mezon.mobile.core.AlertDialog.Builder(activity)
                .setTitle(getString(R.string.share_location_title, ""))
                .setMessage(getString(R.string.permission_no_location))
                .setPositiveButton(getString(R.string.common_ok)) { _, _ ->
                    locationPermissionAskedBefore = true
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

        locationPermissionAskedBefore = true
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
                MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.permission_no_location))
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
                if (!ensureCanSendMessageOrNotify()) return@setPositiveButton
                chatController.sendLocation(
                    channelId, clanId, channelType, resolveChannelPrivate(), latitude, longitude
                )
            }
            .setNegativeButton(getString(R.string.share_location_cancel), null)
            .create()
        alertDialog.show()
    }

    private fun playBuzzSound() {
        try {
            buzzMediaPlayer?.release()
            val ctx = getContext() ?: return
            buzzMediaPlayer = android.media.MediaPlayer.create(ctx, R.raw.buzz)?.apply {
                setOnCompletionListener { mp -> mp.release(); buzzMediaPlayer = null }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play buzz sound", e)
        }
    }

    private fun showBuzzConfirmDialog() {
        val activity = getParentActivity() ?: return
        val inputView = EditText(activity).apply {
            setText(getString(R.string.buzz_default_text))
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_LENGTH_MESSAGE_BUZZ))
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(12f))
            setTextColor(themeColors.onSurface)
            setHintTextColor(themeColors.onSurfaceVariant)
        }
        com.mezon.mobile.core.AlertDialog.Builder(activity)
            .setTitle(getString(R.string.buzz_dialog_title))
            .setView(inputView)
            .setPositiveButton(getString(R.string.buzz_dialog_send)) { _, _ ->
                if (!ensureCanSendMessageOrNotify()) return@setPositiveButton
                val buzzText = inputView.text?.toString()?.trim().orEmpty()
                if (buzzText.isNotBlank()) {
                    chatController.sendBuzzMessage(
                        channelId, clanId, channelType, resolveChannelPrivate(), buzzText
                    )
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .create()
            .show()
    }

    private fun updateSendButtonState() {
        val hasText = inputField.text?.isNotBlank() == true
        val hasAttachments = pendingAttachments.isNotEmpty()
        val canSend = editingMessage != null || canSendMessageInCurrentChannel()
        val showSend = canSend && (hasText || hasAttachments)
        sendButton.visibility = if (showSend) View.VISIBLE else View.GONE
        attachButton.isEnabled = canSend
        attachButton.alpha = if (canSend) 1f else 0.45f
        advancedFunctionButton.isEnabled = canSend
        advancedFunctionButton.alpha = if (canSend) 1f else 0.45f
        emojiButton.isEnabled = canSend
        emojiButton.alpha = if (canSend) 1f else 0.45f
        inputField.isEnabled = canSend
        inputField.alpha = if (canSend) 1f else 0.6f
        if (voiceIsRecording) {
            micButton.visibility = View.VISIBLE
        } else {
            micButton.visibility = if (canSend && !showSend) View.VISIBLE else View.GONE
        }
    }

    private fun updateInputOgpPreview(rawText: String) {
        val candidate = extractFirstInputUrl(rawText)
        if (candidate == null) {
            inputOgpUrl = null
            dismissedInputOgpUrl = null
            failedInputOgpUrl = null
            clearInputOgpPreview()
            return
        }
        if (dismissedInputOgpUrl != null && dismissedInputOgpUrl == candidate) {
            return
        }
        if (failedInputOgpUrl != null && failedInputOgpUrl == candidate) {
            return
        }
        if (candidate == inputOgpUrl && (inputOgpPreviewData != null || inputOgpFetchJob?.isActive == true)) {
            return
        }
        inputOgpUrl = candidate
        val cached = inputOgpCache[candidate]
        if (cached != null) {
            inputOgpFetchJob?.cancel()
            inputOgpFetchJob = null
            commitInputOgpForSend(cached)
            return
        }
        showInputOgpLoading(candidate)
        inputOgpFetchJob?.cancel()
        InputOgpFetcher.cancelInFlight()
        val debounceMs = if (rawText.trim() == candidate) 0L else INPUT_OGP_DEBOUNCE_MS
        inputOgpFetchJob = fragmentScope.launch(ioDispatcher) {
            if (debounceMs > 0L) delay(debounceMs)
            if (!isActive || inputOgpUrl != candidate) return@launch
            val preview = InputOgpFetcher.fetch(
                candidate,
                onTextReady = { partial -> runInputOgpOnMain(candidate) { showInputOgpText(partial) } },
                onSendReady = { ready -> runInputOgpOnMain(candidate) { commitInputOgpForSend(ready) } }
            )
            if (!isActive || inputOgpUrl != candidate) return@launch
            if (preview == null) {
                withContext(mainDispatcher) {
                    if (inputOgpUrl != candidate) return@withContext
                    failedInputOgpUrl = candidate
                    clearInputOgpPreview()
                }
                return@launch
            }
            inputOgpCache[candidate] = preview
            withContext(mainDispatcher) {
                if (inputOgpUrl != candidate) return@withContext
                val current = inputOgpPreviewData
                if (current == null || (current.imageUrl.isBlank() && preview.imageUrl.isNotBlank())) {
                    commitInputOgpForSend(preview)
                }
            }
        }
    }

    private fun runInputOgpOnMain(url: String, block: () -> Unit) {
        fragmentScope.launch(mainDispatcher) {
            if (inputOgpUrl != url) return@launch
            block()
        }
    }

    private fun showInputOgpLoading(url: String) {
        inputOgpBar?.visibility = View.VISIBLE
        inputOgpTitle?.text = getString(R.string.common_loading_data)
        inputOgpDesc?.text = url
        inputOgpImageLoad.cancel()
        inputOgpImageLoad = MezonImageLoader.Cancellable.EMPTY
        inputOgpImage?.setImageDrawable(null)
        inputOgpImage?.visibility = View.GONE
    }

    private fun clearInputOgpPreview() {
        inputOgpFetchJob?.cancel()
        inputOgpFetchJob = null
        InputOgpFetcher.cancelInFlight()
        inputOgpPreviewData = null
        inputOgpThumbnailUrl = null
        inputOgpImageLoad.cancel()
        inputOgpImageLoad = MezonImageLoader.Cancellable.EMPTY
        inputOgpImage?.setImageDrawable(null)
        inputOgpImage?.visibility = View.GONE
        inputOgpTitle?.text = ""
        inputOgpDesc?.text = ""
        inputOgpBar?.visibility = View.GONE
    }

    private fun commitInputOgpForSend(preview: InputOgpPreview) {
        inputOgpPreviewData = preview
        inputOgpCache[preview.url] = preview
        showInputOgpText(preview)
        loadInputOgpThumbnail(preview.imageUrl)
    }

    private fun showInputOgpText(preview: InputOgpPreview) {
        inputOgpBar?.visibility = View.VISIBLE
        inputOgpTitle?.text = preview.title.ifBlank { preview.url }
        inputOgpDesc?.text = preview.description.ifBlank { preview.url }
    }

    private fun loadInputOgpThumbnail(imageUrl: String) {
        if (imageUrl.isBlank() || imageUrl == inputOgpThumbnailUrl) return
        val imgView = inputOgpImage ?: return
        inputOgpThumbnailUrl = imageUrl
        val requestUrl = createImgproxyUrl(imageUrl, INPUT_OGP_IMAGE_SIZE, INPUT_OGP_IMAGE_SIZE, "fill")
        val targetUrl = requestUrl.ifBlank { imageUrl }
        inputOgpImageLoad.cancel()
        inputOgpImageLoad = MezonImageLoader.Cancellable.EMPTY
        val loader = MezonImageLoader.getInstance(imgView.context)
        inputOgpImageLoad = loader.load(
            targetUrl,
            INPUT_OGP_IMAGE_SIZE,
            INPUT_OGP_IMAGE_SIZE,
            onSuccess = { bmp ->
                if (inputOgpThumbnailUrl != imageUrl) return@load
                imgView.setImageBitmap(bmp)
                imgView.visibility = View.VISIBLE
            },
            onError = {
                if (inputOgpThumbnailUrl != imageUrl) return@load
                imgView.setImageDrawable(null)
                imgView.visibility = View.GONE
            }
        )
    }

    private fun extractFirstInputUrl(rawText: String): String? {
        val match = INPUT_OGP_URL_REGEX.find(rawText) ?: return null
        var candidate = match.value.trim()
        while (candidate.isNotEmpty() && candidate.last() in ".,;:!?)]}\\\"") {
            candidate = candidate.dropLast(1)
        }
        return candidate.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun buildInputOgpMarker(cleanedText: String, preview: InputOgpPreview?): OgpMarker? {
        val p = preview ?: return null
        if (cleanedText.isBlank()) return null
        val targetUrl = p.url.trim()
        if (targetUrl.isEmpty()) return null
        val startByExact = cleanedText.indexOf(targetUrl)
        val match = INPUT_OGP_URL_REGEX.find(cleanedText)
        val start = when {
            startByExact >= 0 -> startByExact
            match != null -> match.range.first
            else -> -1
        }
        if (start < 0) return null
        val urlInText = if (startByExact >= 0) targetUrl else {
            var detected = match!!.value.trim()
            while (detected.isNotEmpty() && detected.last() in ".,;:!?)]}\\\"") {
                detected = detected.dropLast(1)
            }
            detected
        }
        if (urlInText.isEmpty()) return null
        val end = (start + urlInText.length).coerceAtMost(cleanedText.length)
        if (end <= start) return null
        val title = p.title.trim()
        val description = p.description.trim()
        val image = p.imageUrl.trim()
        if (title.isEmpty() || (description.isEmpty() && image.isEmpty())) return null
        return OgpMarker(
            s = start,
            e = end,
            index = start,
            title = title,
            description = description,
            image = image
        )
    }

    @SuppressWarnings("ClickableViewAccessibility")
    private fun handleMicTouchEvent(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                voiceTouchDownX = event.rawX
                voiceLongPressFired = false
                voiceCancelled = false
                mainHandler.removeCallbacks(voiceLongPressRunnable)
                mainHandler.postDelayed(voiceLongPressRunnable, VOICE_LONG_PRESS_DELAY_MS)
                v.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!voiceLongPressFired) return true
                if (voiceCancelled) return true
                val dx = event.rawX - voiceTouchDownX
                updateVoiceButtonTranslation(dx)
                val cancelThreshold = -LayoutHelper.dp(VOICE_CANCEL_SLIDE_DP).toFloat()
                val progress = (-dx / LayoutHelper.dp(VOICE_CANCEL_SLIDE_DP).toFloat()).coerceAtLeast(0f)
                voiceOverlay?.setSlideProgress(progress)
                if (dx <= cancelThreshold) {
                    cancelVoiceRecording(showToast = true)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(voiceLongPressRunnable)
                v.parent?.requestDisallowInterceptTouchEvent(false)
                resetMicButtonTransform()
                if (!voiceLongPressFired) {
                    showHoldToRecordHint()
                } else if (!voiceCancelled) {
                    finishVoiceRecording()
                }
                voiceLongPressFired = false
                return true
            }
        }
        return false
    }

    private fun updateVoiceButtonTranslation(dx: Float) {
        val clampedDx = dx.coerceAtMost(0f)
        micButton.translationX = clampedDx
    }

    private fun resetMicButtonTransform() {
        micButton.animate()
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150)
            .withEndAction { applyRecordingMicStyle(false) }
            .start()
    }

    private fun applyRecordingMicStyle(recording: Boolean) {
        val bg = micButton.background as? android.graphics.drawable.GradientDrawable ?: return
        if (recording) {
            bg.setColor(themeColors.blurple)
            val d = MezonIcon.microphoneIcon.getDrawable(micButton.context)
            d.colorFilter = PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
            micButton.setImageDrawable(d)
        } else {
            bg.setColor(themeColors.tertiary)
            val d = MezonIcon.microphoneIcon.getDrawable(micButton.context)
            d.colorFilter = PorterDuffColorFilter(
                themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary),
                PorterDuff.Mode.SRC_IN
            )
            micButton.setImageDrawable(d)
        }
    }

    private fun onVoiceLongPressFired() {
        voiceLongPressFired = true
        if (!ensureCanSendMessageOrNotify()) {
            voiceLongPressFired = false
            return
        }
        val ctx = getContext() ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            voiceLongPressFired = false
            requestRecordAudioPermission()
            return
        }
        startVoiceRecording()
    }

    private fun requestRecordAudioPermission() {
        val activity = getParentActivity() ?: return
        activity.requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_CODE_RECORD_AUDIO
        )
    }

    private fun startVoiceRecording() {
        val ctx = getContext() ?: return
        val recorder = VoiceRecorder(ctx)
        if (!recorder.start()) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.voice_record_failed))
            return
        }
        voiceRecorder = recorder
        voiceIsRecording = true
        voiceCancelled = false
        applyRecordingMicStyle(true)
        micButton.animate().scaleX(1.3f).scaleY(1.3f).setDuration(150).start()
        inputField.visibility = View.INVISIBLE
        emojiButton.visibility = View.INVISIBLE
        anonymousIndicator?.visibility = View.INVISIBLE
        voiceOverlay?.show()
        try { micButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
    }

    private fun finishVoiceRecording() {
        val recorder = voiceRecorder
        val ctx = getContext()
        voiceRecorder = null
        if (!voiceIsRecording || recorder == null || ctx == null) {
            teardownVoiceUi()
            return
        }
        val elapsed = recorder.elapsedMs()
        if (elapsed < VoiceRecorder.MIN_RECORD_MS) {
            mainHandler.postDelayed({
                completeVoiceRecording(recorder, ctx)
            }, VoiceRecorder.MIN_RECORD_MS - elapsed)
        } else {
            completeVoiceRecording(recorder, ctx)
        }
    }

    private fun completeVoiceRecording(recorder: VoiceRecorder, ctx: Context) {
        val result = recorder.stop()
        teardownVoiceUi()
        if (result == null) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.voice_record_failed))
            return
        }
        if (result.durationMs < VoiceRecorder.MIN_RECORD_MS) {
            try { result.file.delete() } catch (_: Exception) {}
            MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.voice_record_too_short))
            return
        }
        sendVoiceRecording(result.file, result.durationMs)
    }

    private fun cancelVoiceRecording(showToast: Boolean) {
        val ctx = getContext()
        voiceRecorder?.cancel()
        voiceRecorder = null
        voiceCancelled = true
        teardownVoiceUi()
        if (showToast) {
            MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.voice_record_cancelled))
        }
    }

    private fun teardownVoiceUi() {
        voiceIsRecording = false
        voiceOverlay?.hide()
        inputField.visibility = View.VISIBLE
        emojiButton.visibility = View.VISIBLE
        if (anonymousController.isAnonymous(clanId)) {
            anonymousIndicator?.visibility = View.VISIBLE
        }
        micButton.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(150)
            .withEndAction { applyRecordingMicStyle(false) }
            .start()
        updateSendButtonState()
    }

    private fun sendVoiceRecording(file: java.io.File, durationMs: Long) {
        if (!ensureCanSendMessageOrNotify()) {
            try { file.delete() } catch (_: Exception) {}
            return
        }
        val ctx = getContext() ?: return
        val durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)
        val item = AttachmentPickerItem(
            id = file.absolutePath.hashCode().toLong(),
            uri = android.net.Uri.fromFile(file),
            path = file.absolutePath,
            filename = file.name,
            mimeType = VoiceRecorder.MIME_TYPE,
            width = 0,
            height = 0,
            size = file.length(),
            duration = durationSec,
            isVideo = false
        )
        chatController.sendMessageWithAttachments(
            channelId, clanId, channelType, resolveChannelPrivate(),
            "",
            arrayListOf(item),
            ctx.contentResolver,
            buildReplyReferences(),
            null,
            null,
            null,
            topicId = topicId
        )
        clearReplyState()
    }

    private fun showHoldToRecordHint() {
        MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.voice_record_hint))
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

            if (item.isFileType) {
                buildFilePreviewItem(ctx, container, item, thumbSize)
            } else {
                buildMediaPreviewItem(ctx, container, item, resolver, thumbSize)
            }

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

    private fun buildMediaPreviewItem(
        ctx: Context,
        container: FrameLayout,
        item: AttachmentPickerItem,
        resolver: android.content.ContentResolver,
        thumbSize: Int
    ) {
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
    }

    private fun buildFilePreviewItem(
        ctx: Context,
        container: FrameLayout,
        item: AttachmentPickerItem,
        thumbSize: Int
    ) {
        val fileContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(themeColors.secondaryLight)
        }

        val iconSize = LayoutHelper.dp(24f)
        val icon = ImageView(ctx).apply {
            setImageDrawable(MezonIcon.fileIconNew.getDrawable(ctx))
        }
        fileContainer.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val nameLabel = TextView(ctx).apply {
            text = item.filename
            setTextColor(themeColors.onSurface)
            textSize = 9f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(2f), LayoutHelper.dp(1f), LayoutHelper.dp(2f), 0)
        }
        fileContainer.addView(nameLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val subtype = item.mimeType.substringAfterLast('/', "").uppercase()
        if (subtype.isNotEmpty()) {
            val typeLabel = TextView(ctx).apply {
                text = subtype
                setTextColor(themeColors.onSurfaceVariant)
                textSize = 8f
                maxLines = 1
                gravity = Gravity.CENTER
            }
            fileContainer.addView(typeLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        container.addView(fileContainer, FrameLayout.LayoutParams(thumbSize, thumbSize))
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
            if (computeMediaPermissionGrantedFromResult(permissions, grantResults)) {
                mediaPermissionDeniedOnce = false
                openMediaAttachAlert()
            } else {
                mediaPermissionDeniedOnce = true
                if (!shouldShowMediaPermissionRationale()) {
                    showOpenMediaSettingsDialog()
                }
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
        if (requestCode == REQUEST_CODE_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showHoldToRecordHint()
            } else {
                val activity = getParentActivity() ?: return
                val permanentlyDenied = !activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.RECORD_AUDIO
                )
                if (permanentlyDenied) {
                    showOpenAudioSettingsDialog()
                }
            }
        }
        if (requestCode == REQUEST_CALL_PERMISSIONS) {
            val msgRes = CallPermissionUi.permissionResultMessageForCall(
                permissions,
                grantResults,
                pendingCallPermissionRequestIncludedCamera
            )
            val audioGranted = permissions.indices.any { i ->
                permissions[i] == Manifest.permission.RECORD_AUDIO &&
                    grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            }
            val cameraGranted = permissions.indices.any { i ->
                permissions[i] == Manifest.permission.CAMERA &&
                    grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            }
            val needCamera = pendingCallPermissionRequestIncludedCamera
            val ok = audioGranted && (!needCamera || cameraGranted)
            if (ok) {
                pendingCallPermissionCallback?.invoke()
            } else {
                Log.w(TAG, "requestCallPermissions denied results=${grantResults.contentToString()}")
                val activity = getParentActivity()
                if (msgRes != null) {
                    MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(msgRes))
                }
                if (activity != null) {
                    if (!audioGranted) {
                        CallPermissionUi.showMicOrCameraDeniedFeedback(
                            activity,
                            Manifest.permission.RECORD_AUDIO,
                            R.string.permission_no_audio
                        )
                    } else if (needCamera && !cameraGranted) {
                        CallPermissionUi.showMicOrCameraDeniedFeedback(
                            activity,
                            Manifest.permission.CAMERA,
                            R.string.permission_no_camera
                        )
                    }
                }
            }
            pendingCallPermissionCallback = null
            pendingCallPermissionRequestIncludedCamera = false
        }
    }

    private fun showOpenMediaSettingsDialog() {
        val activity = getParentActivity() ?: return
        com.mezon.mobile.core.AlertDialog.Builder(activity)
            .setTitle(getString(R.string.common_settings))
            .setMessage(getString(R.string.permission_no_storage))
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

    private fun showOpenAudioSettingsDialog() {
        val activity = getParentActivity() ?: return
        com.mezon.mobile.core.AlertDialog.Builder(activity)
            .setTitle(getString(R.string.common_settings))
            .setMessage(getString(R.string.permission_no_audio))
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

    private fun handleReactionTap(msg: MessageEntity, group: ReactionGroup) {
        chatController.sendReaction(
            channelId, clanId, channelType, resolveChannelPrivate(),
            msg.id, group.emojiId, group.emoji,
            1, actionDelete = false, msg.senderId
        )
    }

    private fun showReactionEmojiPicker(msg: MessageEntity) {
        val ctx = getContext() ?: return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val sheet = ReactionEmojiPickerSheet(ctx, themeColors, emojiController, notificationCenter) { emojiId, emojiShortname ->
            chatController.sendReaction(
                channelId, clanId, channelType, resolveChannelPrivate(),
                msg.id, emojiId, emojiShortname,
                1, actionDelete = false, msg.senderId
            )
        }
        sheet.show()
    }

    private fun showReactionDetailSheet(msg: MessageEntity, selectedEmojiId: Long) {
        val ctx = getContext() ?: return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val userId = chatController.getCurrentUserId()
        val sheet = ReactionDetailBottomSheet(
            context = ctx,
            message = msg,
            selectedEmojiId = selectedEmojiId,
            currentUserId = userId,
            themeColors = themeColors,
            memberResolver = { senderId ->
                memberResolver.resolveMember(senderId, clanId, channelId, channelType)
            },
            onRemoveReaction = { emojiId, emoji, count ->
                chatController.sendReaction(
                    channelId, clanId, channelType, resolveChannelPrivate(),
                    msg.id, emojiId, emoji,
                    count, actionDelete = true, msg.senderId
                )
            }
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }

    private fun canShowCreateThreadInMessageMenu(): Boolean {
        if (isTopicMode) return false
        if (clanId == 0L) return false
        if (channelType != CHANNEL_TYPE_CHANNEL) return false
        if (routeParentId != 0L) return false
        if (channelType == CHANNEL_TYPE_STREAMING) return false
        if (channelType == CHANNEL_TYPE_APP) return false
        return true
    }

    private fun refreshPermissionGates() {
        if (channelId != 0L) {
            permissionPolicy.ensurePermissionChecker(
                listOf(
                    PermissionPolicy.CLAN_OWNER,
                    PermissionPolicy.MANAGE_CHANNEL,
                    PermissionPolicy.SEND_MESSAGE,
                    PermissionPolicy.DELETE_MESSAGE,
                    PermissionPolicy.MANAGE_THREAD
                ),
                channelId,
                clanId
            )
        }
        if (::sendButton.isInitialized) {
            updateSendButtonState()
        }
    }

    private fun canSendMessageInCurrentChannel(): Boolean {
        if (channelId == 0L) return false
        if (clanId == 0L || channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP) return true
        if (!permissionPolicy.hasCachedChannelUserPermissions(channelId)) return true
        if (permissionPolicy.checkPermission(PermissionPolicy.SEND_MESSAGE, channelId, clanId)) return true
        if (isTopicMode && permissionPolicy.canCreateThreadFromMessage(channelId, clanId)) return true
        return false
    }

    private fun ensureCanSendMessageOrNotify(): Boolean {
        if (canSendMessageInCurrentChannel()) return true
        refreshPermissionGates()
        MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.message_no_send_permission))
        return false
    }

    private fun isProtectedTopicDeleteMessage(msg: MessageEntity): Boolean {
        if (msg.code == MessageEntity.CODE_TOPIC) return true
        if (msg.isTopicRootMessage) return true
        val hasTopicPayload = runCatching { org.json.JSONObject(msg.content).has("tp") }.getOrDefault(false)
        if (hasTopicPayload) return true
        if (isTopicMode) {
            if (rootMessageId != 0L && msg.id == rootMessageId) return true
            val firstMessageId = messages.filterNot { it.isUnreadDivider }.minOfOrNull { it.id } ?: return false
            return msg.id == firstMessageId
        }
        val isThread = channelType == CHANNEL_TYPE_THREAD || routeParentId != 0L
        if (!isThread) return false
        val firstMessageId = messages.filterNot { it.isUnreadDivider }.minOfOrNull { it.id } ?: return false
        return msg.id == firstMessageId
    }

    private fun canDeleteMessageInCurrentChannel(msg: MessageEntity, isMyMessage: Boolean): Boolean {
        if (isProtectedTopicDeleteMessage(msg)) return false
        if (isMyMessage) return true
        if (clanId == 0L) {
            if (channelType == CHANNEL_TYPE_GROUP) {
                val creatorId = dialogsController.getDialog(channelId)?.groupCreatorId ?: 0L
                if (creatorId != 0L && creatorId == chatController.getCurrentUserId()) return true
            }
            return false
        }
        return permissionPolicy.checkPermission(PermissionPolicy.DELETE_MESSAGE, channelId, clanId)
    }

    private fun canManageThreadInCurrentChannel(): Boolean {
        if (!canShowCreateThreadInMessageMenu()) return false
        return permissionPolicy.canCreateThreadFromMessage(channelId, clanId)
    }

    private fun canShowTopicDiscussionInMessageMenu(msg: MessageEntity): Boolean {
        if (isTopicMode) return false
        if (clanId == 0L) return false
        if (channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP) return false
        if (channelType == CHANNEL_TYPE_STREAMING || channelType == CHANNEL_TYPE_APP || channelType == CHANNEL_TYPE_VOICE) return false
        if (msg.effectiveTopicId != 0L) return false
        if (msg.isPollMessage) return false
        if (msg.code == MessageEntity.CODE_TOPIC) return false
        if (msg.code == MessageEntity.CODE_CREATE_THREAD) return false
        if (msg.code == MessageEntity.CODE_CREATE_PIN) return false
        if (msg.code == MessageEntity.CODE_MESSAGE_BUZZ) return false
        if (msg.code == MessageEntity.CODE_AUDIT_LOG) return false
        if (msg.code == MessageEntity.CODE_WELCOME) return false
        if (msg.code == MessageEntity.CODE_UPCOMING_EVENT) return false
        return canSendMessageInCurrentChannel() || canManageThreadInCurrentChannel()
    }

    private fun showMessageActionSheet(msg: MessageEntity) {
        val ctx = getContext() ?: return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val userId = chatController.getCurrentUserId()
        val isMyMessage = msg.senderId == userId
        val showEditMessage = msg.canEditMessage(userId)
        val allMedia = msg.allImageAttachments
        val hasMedia = allMedia.isNotEmpty() ||
            msg.attachmentUrl.isNotEmpty() && (msg.attachmentFiletype.startsWith("image/") || msg.attachmentFiletype.startsWith("video/"))
        val hasImage = allMedia.any { it.filetype.startsWith("image/") }
        val allowFwd = !msg.isPollMessage

        val sheet = MessageActionBottomSheet(
            context = ctx,
            message = msg,
            isMyMessage = isMyMessage,
            isDM = clanId == 0L,
            isPinned = pinMessageController.isPinned(channelId, msg.id),
            canDeleteMessage = canDeleteMessageInCurrentChannel(msg, isMyMessage),
            canManageThread = canManageThreadInCurrentChannel(),
            hasMedia = hasMedia,
            hasImage = hasImage,
            showForwardSingle = allowFwd,
            showForwardAllNearby = allowFwd && collectForwardNearbyMessages(msg).size > 1,
            showEditMessage = showEditMessage,
            showTopicDiscussion = canShowTopicDiscussionInMessageMenu(msg),
            showPinActions = !isTopicMode,
            showResend = msg.sendState == MessageEntity.SEND_STATE_ERROR && isMyMessage,
            listener = object : MessageActionBottomSheet.MessageActionListener {
                override fun onActionSelected(action: MessageActionBottomSheet.ActionType, message: MessageEntity) {
                    handleMessageAction(action, message)
                }
                override fun onReactionSelected(emojiId: Long, emoji: String, message: MessageEntity) {
                    chatController.sendReaction(
                        channelId, clanId, channelType, resolveChannelPrivate(),
                        message.id, emojiId, emoji, 1, actionDelete = false, message.senderId
                    )
                }
                override fun onOpenEmojiPicker(message: MessageEntity) {
                    showReactionEmojiPicker(message)
                }
            }
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }

    private fun openForwardScreen(msg: MessageEntity) {
        ForwardNavigationStash.pendingMessages = ArrayList<MessageEntity>().apply { add(msg) }
        presentFragment(SharingFragment(SharingPayload.ForwardFromChat(channelId, clanId, channelType)))
    }

    private fun openForwardAllNearbyScreen(msg: MessageEntity) {
        ForwardNavigationStash.pendingMessages = ArrayList(collectForwardNearbyMessages(msg))
        presentFragment(SharingFragment(SharingPayload.ForwardFromChat(channelId, clanId, channelType)))
    }

    private fun collectForwardNearbyMessages(msg: MessageEntity): List<MessageEntity> {
        val index = messages.indexOfFirst { it.id == msg.id }
        if (index < 0) return listOf(msg)
        var newestIndex = index
        while (newestIndex > 0 && canForwardTogether(messages[newestIndex - 1], messages[newestIndex], msg.senderId)) {
            newestIndex--
        }
        var oldestIndex = index
        while (oldestIndex < messages.lastIndex && canForwardTogether(messages[oldestIndex], messages[oldestIndex + 1], msg.senderId)) {
            oldestIndex++
        }
        val result = ArrayList<MessageEntity>(oldestIndex - newestIndex + 1)
        for (i in oldestIndex downTo newestIndex) {
            result.add(messages[i])
        }
        return result
    }

    private fun canForwardTogether(newer: MessageEntity, older: MessageEntity, senderId: Long): Boolean {
        if (newer.senderId != senderId || older.senderId != senderId) return false
        if (newer.isPollMessage || older.isPollMessage) return false
        if (newer.timestampSeconds <= 0L || older.timestampSeconds <= 0L) return false
        return kotlin.math.abs(newer.timestampSeconds - older.timestampSeconds) <= FORWARD_NEARBY_WINDOW_SECONDS
    }

    private fun openProfileDm(userId: Long, displayName: String, username: String) {
        if (userId == 0L) return
        fragmentScope.launch {
            val dmId = withContext(ioDispatcher) { dialogsController.getOrCreateDm(userId) }
            withContext(mainDispatcher) {
                if (dmId == 0L) {
                    MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.ERROR, getString(R.string.contact_shared_error))
                    return@withContext
                }
                (getParentActivity() as? MainActivity)?.openChat(
                    dmId,
                    displayName.ifBlank { username },
                    0L,
                    CHANNEL_TYPE_DM
                )
            }
        }
    }

    private fun startProfileVoiceCall(
        userId: Long,
        displayName: String,
        username: String,
        avatarUrl: String
    ) {
        if (userId == 0L) return
        val myId = chatController.getCurrentUserId()
        if (userId == myId) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.cannot_call_yourself))
            return
        }
        if (friendController.isUserBlocked(userId)) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.no_permission_call_blocked))
            return
        }
        if (callController.isCallSessionActive()) return
        requestCallPermissions(needsCamera = false, reason = "profileVoiceCall") {
            runOutgoingCallAfterFullScreenIntentPrompt {
                fragmentScope.launch {
                    val dmId = withContext(ioDispatcher) { dialogsController.getOrCreateDm(userId) }
                    withContext(mainDispatcher) {
                        if (dmId == 0L) {
                            MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.ERROR, getString(R.string.contact_shared_error))
                            return@withContext
                        }
                        callController.startCall(
                            userId,
                            displayName.ifBlank { username }.ifBlank { "Unknown" },
                            avatarUrl.ifBlank { null },
                            dmId,
                            0L,
                            CHANNEL_TYPE_DM,
                            false,
                            isVideo = false,
                            peerUsername = username
                        )
                        presentFragment(CallFragment())
                    }
                }
            }
        }
    }

    private fun sendProfileFriendRequest(userId: Long, username: String) {
        if (userId == 0L || userId == chatController.getCurrentUserId()) return
        if (friendController.isUserBlocked(userId)) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.friends_toast_blocked_user))
            return
        }
        when (friendController.findFriendByUserId(userId)?.state) {
            FRIEND_STATE_FRIEND -> {
                MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.friends_toast_already_friend))
                return
            }
            FRIEND_STATE_INVITE_SENT -> {
                MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.friends_toast_wait_accept))
                return
            }
            FRIEND_STATE_BLOCKED -> {
                MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.friends_toast_blocked_user))
                return
            }
            else -> Unit
        }
        friendController.sendFriendRequest(userId, username) { success ->
            val type = if (success) ToastOverlay.ToastType.SUCCESS else ToastOverlay.ToastType.ERROR
            val message = getString(
                if (success) R.string.friends_toast_send_success else R.string.friends_toast_send_fail
            )
            MezonToast.show(this, type, message)
        }
    }

    private fun showShareContactProfile(data: ShareContactData) {
        val ctx = getContext() ?: return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val currentUserId = chatController.getCurrentUserId()
        val sheet = UserProfileBottomSheet(
            context = ctx,
            userId = data.userId,
            displayName = data.displayName,
            username = data.username,
            avatarUrl = data.avatarUrl,
            aboutMe = null,
            memberSince = null,
            isOwnProfile = data.userId == currentUserId,
            isDM = true,
            listener = object : UserProfileBottomSheet.UserProfileListener {
                override fun onSendMessage(userId: Long) {
                    openProfileDm(data.userId, data.displayName, data.username)
                }
                override fun onVoiceCall(userId: Long) {
                    startProfileVoiceCall(data.userId, data.displayName, data.username, data.avatarUrl)
                }
                override fun onAddFriend(userId: Long) {
                    sendProfileFriendRequest(data.userId, data.username)
                }
                override fun onTransferFunds(userId: Long) {
                    openProfileTransferFunds(data.userId, data.username)
                }
            }
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }

    private fun openShareContactDm(data: ShareContactData) {
        openProfileDm(data.userId, data.displayName, data.username)
    }

    private fun startShareContactCall(data: ShareContactData) {
        startProfileVoiceCall(data.userId, data.displayName, data.username, data.avatarUrl)
    }

    private fun profileRolesFor(member: ClanMember?): List<UserProfileBottomSheet.UserProfileRole> {
        if (member == null || clanId == 0L || member.roleIds.isEmpty()) return emptyList()
        return roleController.profileRoleChipsForMember(clanId, member.roleIds).map { chip ->
            UserProfileBottomSheet.UserProfileRole(
                id = chip.roleId,
                title = chip.title,
                color = chip.color,
                iconUrl = chip.iconUrl,
            )
        }
    }

    private fun showUserProfile(msg: MessageEntity) {

        val ctx = getContext() ?: return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val currentUserId = chatController.getCurrentUserId()
        val isOwnProfile = msg.senderId == currentUserId
        val member = if (msg.senderId != 0L) {
            memberResolver.resolveMember(msg.senderId, clanId, channelId, channelType)
        } else {
            null
        }
        val fallbackName = msg.senderName.ifBlank { "Unknown" }
        val displayName = when {
            member != null -> {
                val nick = member.clanNick.trim()
                when {
                    nick.isNotEmpty() -> nick
                    member.displayName.isNotBlank() -> member.displayName
                    else -> member.username.ifBlank { fallbackName }
                }
            }
            else -> fallbackName
        }
        val usernameLine = when {
            member != null -> {
                val u = member.username.trim()
                when {
                    u.isNotEmpty() -> u
                    member.displayName.isNotBlank() -> member.displayName
                    else -> msg.senderName
                }
            }
            else -> msg.senderName
        }
        val avatarForUi = when {
            member != null -> {
                val ca = member.clanAvatar.trim()
                if (ca.isNotEmpty()) ca else member.avatarUrl.ifBlank { msg.senderAvatar }
            }
            else -> msg.senderAvatar
        }

        val sheet = UserProfileBottomSheet(
            context = ctx,
            userId = msg.senderId,
            displayName = displayName,
            username = usernameLine,
            avatarUrl = avatarForUi,
            aboutMe = null,
            memberSince = null,
            isOwnProfile = isOwnProfile,
            isDM = clanId == 0L,
            isWebhook = clanId != 0L && member == null,
            roles = profileRolesFor(member),
            listener = object : UserProfileBottomSheet.UserProfileListener {
                override fun onSendMessage(userId: Long) {
                    openProfileDm(msg.senderId, displayName, usernameLine)
                }
                override fun onVoiceCall(userId: Long) {
                    startProfileVoiceCall(msg.senderId, displayName, usernameLine, avatarForUi)
                }
                override fun onAddFriend(userId: Long) {
                    sendProfileFriendRequest(msg.senderId, usernameLine)
                }
                override fun onTransferFunds(userId: Long) {
                    openProfileTransferFunds(msg.senderId, usernameLine)
                }
            }
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }

    private fun showUserProfileFromMentionUserId(userId: Long) {
        val ctx = getContext() ?: return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val currentUserId = chatController.getCurrentUserId()
        val member = memberResolver.resolveMember(userId, clanId, channelId, channelType)
        val displayName = when {
            member != null -> {
                val nick = member.clanNick.trim()
                when {
                    nick.isNotEmpty() -> nick
                    member.displayName.isNotBlank() -> member.displayName
                    else -> member.username.ifBlank { "Unknown" }
                }
            }
            else -> "Unknown"
        }
        val usernameLine = when {
            member != null -> {
                val u = member.username.trim()
                when {
                    u.isNotEmpty() -> u
                    member.displayName.isNotBlank() -> member.displayName
                    else -> ""
                }
            }
            else -> ""
        }
        val avatarForUi = when {
            member != null -> {
                val ca = member.clanAvatar.trim()
                if (ca.isNotEmpty()) ca else member.avatarUrl
            }
            else -> ""
        }
        val sheet = UserProfileBottomSheet(
            context = ctx,
            userId = userId,
            displayName = displayName,
            username = usernameLine,
            avatarUrl = avatarForUi,
            aboutMe = null,
            memberSince = null,
            isOwnProfile = userId == currentUserId,
            isDM = clanId == 0L,
            roles = profileRolesFor(member),
            listener = object : UserProfileBottomSheet.UserProfileListener {
                override fun onSendMessage(userId: Long) {
                    openProfileDm(userId, displayName, usernameLine)
                }
                override fun onVoiceCall(userId: Long) {
                    startProfileVoiceCall(userId, displayName, usernameLine, avatarForUi)
                }
                override fun onAddFriend(userId: Long) {
                    sendProfileFriendRequest(userId, usernameLine)
                }
                override fun onTransferFunds(userId: Long) {
                    openProfileTransferFunds(userId, usernameLine)
                }
            }
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }

    private fun handleMessageAction(action: MessageActionBottomSheet.ActionType, msg: MessageEntity) {
        when (action) {
            MessageActionBottomSheet.ActionType.ResendMessage -> {
                resendFailedMessage(msg)
            }
            MessageActionBottomSheet.ActionType.Reply -> {
                setReplyState(msg)
            }
            MessageActionBottomSheet.ActionType.EditMessage -> {
                setEditState(msg)
            }
            MessageActionBottomSheet.ActionType.CopyText -> {
                val ctx = getContext() ?: return
                val plainText = parseContentText(msg.content)
                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", plainText))
                MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.message_toast_copy_text))
            }
            MessageActionBottomSheet.ActionType.ForwardMessage -> {
                openForwardScreen(msg)
            }
            MessageActionBottomSheet.ActionType.ForwardAllNearby -> {
                openForwardAllNearbyScreen(msg)
            }
            MessageActionBottomSheet.ActionType.PinMessage -> {
                if (isTopicMode) return
                showPinConfirmation(msg, isUnpin = false)
            }
            MessageActionBottomSheet.ActionType.UnPinMessage -> {
                if (isTopicMode) return
                showPinConfirmation(msg, isUnpin = true)
            }
            MessageActionBottomSheet.ActionType.DeleteMessage -> {
                showDeleteConfirmation(msg)
            }
            MessageActionBottomSheet.ActionType.CreateThread -> {
                if (isTopicMode) return
                if (!canManageThreadInCurrentChannel()) {
                    refreshPermissionGates()
                    MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permissions_no_access))
                    return
                }
                CreateThreadSeedStash.pendingSeedMessage = msg
                presentFragment(
                    CreateThreadFragment.newInstance(
                        channelId,
                        channelName,
                        clanId,
                        seedMessageId = msg.id
                    )
                )
            }
            MessageActionBottomSheet.ActionType.TopicDiscussion -> {
                if (!canShowTopicDiscussionInMessageMenu(msg)) return
                if (anonymousController.isAnonymous(clanId)) {
                    MezonToast.show(
                        this,
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.advanced_anonymous_off)
                    )
                    return
                }
                CreateThreadSeedStash.pendingSeedMessage = msg
                presentFragment(
                    CreateThreadFragment.newInstance(
                        channelId,
                        channelName,
                        clanId,
                        seedMessageId = msg.id,
                        useTopicFlow = true
                    )
                )
            }
            MessageActionBottomSheet.ActionType.MarkUnRead -> {
                getContext() ?: return
                MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.feature_coming_soon))
            }
            MessageActionBottomSheet.ActionType.SaveMedia -> {
                getContext() ?: return
                MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.feature_coming_soon))
            }
            MessageActionBottomSheet.ActionType.CopyMediaLink -> {
                val url = msg.attachmentUrl
                if (url.isNotEmpty()) {
                    val ctx = getContext() ?: return
                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("media_url", url))
                    MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.action_copy_link))
                }
            }
            MessageActionBottomSheet.ActionType.CopyImage -> {
                val ctx = getContext() ?: return
                val url = imageClipboardCoordinator.resolvePrimaryImageUrlForCopy(msg)
                if (url.isNullOrBlank()) {
                    MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.message_toast_copy_image_failed))
                    return
                }
                appScope.launch {
                    val ok = imageClipboardCoordinator.copyRemoteUrlToClipboard(ctx, url, null)
                    withContext(mainDispatcher) {
                        if (ok) {
                            MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.INFO, getString(R.string.message_toast_copy_image_done))
                        } else {
                            MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.ERROR, getString(R.string.message_toast_copy_image_failed))
                        }
                    }
                }
            }
            MessageActionBottomSheet.ActionType.ShareImage -> {
                getContext() ?: return
                MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.feature_coming_soon))
            }
            MessageActionBottomSheet.ActionType.Report -> {
                showReportMessageSheet(msg)
            }
            MessageActionBottomSheet.ActionType.GiveACoffee -> {
                handleGiveCoffee(msg)
            }
        }
    }

    private fun showReportMessageSheet(msg: MessageEntity) {
        val ctx = getContext() ?: return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val sheet = ReportMessageBottomSheet(
            context = ctx,
            messageId = msg.id,
            mezonApi = mezonApi,
            sessionManager = sessionManager,
            applicationScope = appScope,
            ioDispatcher = ioDispatcher,
            mainDispatcher = mainDispatcher
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }

    private fun showPinConfirmation(msg: MessageEntity, isUnpin: Boolean) {
        val activity = getParentActivity() ?: return
        val titleRes = if (isUnpin) R.string.unpin_message_confirm_title else R.string.pin_message_confirm_title
        val descRes = if (isUnpin) R.string.unpin_message_confirm_description else R.string.pin_message_confirm_description
        com.mezon.mobile.core.AlertDialog.Builder(activity)
            .setTitle(getString(titleRes))
            .setMessage(getString(descRes))
            .setPositiveButton(getString(R.string.common_yes)) { _, _ ->
                if (isUnpin) {
                    pinMessageController.unpinMessage(channelId, clanId, msg.id)
                } else {
                    val content = msg.content
                    val attachment = msg.buildAttachmentJson()
                    val createdTime = if (msg.timestampSeconds > 0)
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date(msg.timestampSeconds * 1000))
                    else ""
                    pinMessageController.pinMessage(
                        channelId = channelId,
                        clanId = clanId,
                        channelType = channelType,
                        isChannelPrivate = resolveChannelPrivate(),
                        messageId = msg.id,
                        senderAvatar = msg.senderAvatar,
                        senderId = msg.senderId.toString(),
                        senderUsername = msg.senderName,
                        messageContent = content,
                        messageAttachment = attachment,
                        messageCreatedTime = createdTime
                    )
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .create()
            .show()
    }

    private fun showDeleteConfirmation(msg: MessageEntity) {
        val ctx = getContext() ?: return
        val builder = android.app.AlertDialog.Builder(ctx)
        builder.setTitle(R.string.message_delete_title)
        builder.setMessage(R.string.message_delete_description)
        builder.setPositiveButton(R.string.common_delete) { _, _ ->
            chatController.deleteMessage(channelId, clanId, channelType, resolveChannelPrivate(), msg.id, topicId = topicId)
        }
        builder.setNegativeButton(R.string.common_cancel, null)
        builder.show()
    }

    private fun handleGiveCoffee(msg: MessageEntity) {
        getContext() ?: return
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        val senderId = chatController.getCurrentUserId()
        if (senderId == 0L) return

        if (msg.senderId == senderId) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.give_coffee_error_self))
            return
        }

        if (!walletController.isReadyToSendTransaction()) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.give_coffee_error_wallet_not_ready))
            return
        }

        val receiverId = msg.senderId.toString()
        val senderUsername = userController.displayName.ifBlank { userController.username }
        val amountText = GIVE_COFFEE_AMOUNT_DISPLAY
        val tokensSentTitle = getString(R.string.tokens_sent_title, amountText)
        val giveCoffeeAction = getString(R.string.give_coffee_action)
        val messageTextForDm = "$tokensSentTitle$GIVE_COFFEE_SEPARATOR$giveCoffeeAction"

        appScope.launch {
            try {
                val session = sessionManager.sessionFlow.first() ?: return@launch

                val sendResult = walletController.sendTokenTransfer(
                    senderId = senderId.toString(),
                    receiverId = receiverId,
                    receiverMmnAddress = null,
                    amountHuman = GIVE_COFFEE_AMOUNT_HUMAN,
                    note = GIVE_COFFEE_NOTE,
                    senderUsername = senderUsername
                )

                if (sendResult.isFailure) {
                    val errorMsg = sendResult.exceptionOrNull()?.message
                        ?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.give_coffee_error_send)
                    withContext(mainDispatcher) {
                        MezonToast.show(this@ChatFragment, ToastOverlay.ToastType.ERROR, errorMsg)
                    }
                    return@launch
                }

                val amountRaw = java.math.BigInteger(GIVE_COFFEE_AMOUNT_HUMAN).multiply(java.math.BigInteger.valueOf(1_000_000L))
                accountController.reduceBalanceLocally(amountRaw)
                walletController.reduceBalanceLocally(amountRaw)

                chatController.sendReaction(
                    channelId = channelId,
                    clanId = clanId,
                    channelType = channelType,
                    isChannelPrivate = resolveChannelPrivate(),
                    messageId = msg.id,
                    emojiId = GIVE_COFFEE_EMOJI_ID,
                    emoji = GIVE_COFFEE_EMOJI,
                    count = 1,
                    actionDelete = false,
                    messageSenderId = msg.senderId
                )

                val dmChannelId = withContext(ioDispatcher) {
                    dialogsController.getOrCreateDm(msg.senderId)
                }
                if (dmChannelId == 0L) {
                    withContext(mainDispatcher) {
                        MezonToast.show(
                            this@ChatFragment,
                            ToastOverlay.ToastType.ERROR,
                            getString(R.string.give_coffee_error_dm_channel)
                        )
                    }
                    return@launch
                }

                val content = org.json.JSONObject().apply {
                    put("t", messageTextForDm)
                    put("mk", org.json.JSONArray())
                }.toString()
                Log.d(TAG, "handleGiveCoffee: sending DM to channelId=$dmChannelId code=${MessageEntity.CODE_SEND_TOKEN} content=$content")
                val request = com.mezon.mezon.rtapi.channelMessageSend {
                    this.clanId = 0L
                    this.channelId = dmChannelId
                    this.mode = com.mezon.mobile.network.STREAM_MODE_DM
                    this.isPublic = false
                    this.content = content
                    this.code = MessageEntity.CODE_SEND_TOKEN
                }
                val dmNotificationResult = runCatching {
                    withContext(ioDispatcher) {
                        mezonApi.sendChannelMessage(session.apiUrl, session.token, request)
                    }
                }
                if (dmNotificationResult.isFailure) {
                    Log.e(TAG, "handleGiveCoffee: failed to send DM notification", dmNotificationResult.exceptionOrNull())
                    withContext(mainDispatcher) {
                        MezonToast.show(
                            this@ChatFragment,
                            ToastOverlay.ToastType.ERROR,
                            getString(R.string.give_coffee_error_dm_notification)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleGiveCoffee failed", e)
                withContext(mainDispatcher) {
                    MezonToast.show(
                        this@ChatFragment,
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.give_coffee_error_send)
                    )
                }
            }
        }
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
        mentionTrackers.clear()
        hashtagTrackers.clear()
        emojiObjPicked.clear()
        editingMessage = msg
        editNameView?.text = getString(R.string.message_chatbox_editing)
        editBar?.visibility = View.VISIBLE
        val restored = restoreInputFromContent(msg.content)
        suppressInputTrackerMutation = true
        try {
            inputField.setText(restored.rawText)
            mentionTrackers.addAll(restored.mentions)
            hashtagTrackers.addAll(restored.hashtags)
            emojiObjPicked.putAll(restored.emojis)
            applyEditHighlightSpans(restored)
        } finally {
            suppressInputTrackerMutation = false
        }
        inputField.setSelection(inputField.text?.length ?: 0)
        inputField.requestFocus()
        AndroidUtilities.showKeyboard(inputField)
    }

    private fun applyEditHighlightSpans(restored: com.mezon.mobile.util.RestoredInputContent) {
        val editable = inputField.text ?: return
        val len = editable.length
        for (m in restored.mentions) {
            if (m.startOffset < 0 || m.endOffset > len || m.startOffset >= m.endOffset) continue
            val color = if (m.roleId.isNotBlank()) themeColors.textRoleLink else themeColors.textLink
            editable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                m.startOffset, m.endOffset,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            editable.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                m.startOffset, m.endOffset,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        for (h in restored.hashtags) {
            if (h.startOffset < 0 || h.endOffset > len || h.startOffset >= h.endOffset) continue
            editable.setSpan(
                HashtagSpan(h.channelId, themeColors.textLink),
                h.startOffset, h.endOffset,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            editable.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                h.startOffset, h.endOffset,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        for ((shortname, _) in restored.emojis) {
            var searchFrom = 0
            while (searchFrom < editable.length) {
                val idx = editable.indexOf(shortname, searchFrom)
                if (idx < 0) break
                val end = idx + shortname.length
                editable.setSpan(
                    EmojiTokenSpan(),
                    idx, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                editable.setSpan(
                    android.text.style.ForegroundColorSpan(0xFF5A62F4.toInt()),
                    idx, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                editable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    idx, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                searchFrom = end
            }
        }
    }

    private fun clearEditState() {
        editingMessage = null
        editBar?.visibility = View.GONE
        editNameView?.text = ""
        mentionTrackers.clear()
        hashtagTrackers.clear()
        emojiObjPicked.clear()
        inputField.text?.clear()
    }

    private fun applyRealId(tempId: Long, realId: Long, echoEntity: MessageEntity? = null) {
        if (tempId == realId) {
            markMessageSent(tempId)
            return
        }
        val idx = messages.indexOfFirst { it.id == tempId }
        if (idx < 0) {
            if (messagesDict.get(realId) != null) return
            Log.d(TAG, "applyRealId tempId=$tempId not found")
            return
        }

        if (messagesDict.get(realId) != null) {
            Log.d(TAG, "applyRealId tempId=$tempId realId=$realId already present, dropping optimistic")
            messagesDict.delete(tempId)
            messages.removeAt(idx)
            if (fragmentView != null) {
                adapter.notifyMessageRemovedAt(idx)
                updateUnreadDividerPosition()
            }
            return
        }

        val old = messages[idx]
        messagesDict.delete(tempId)
        val pendingEntity = chatController.takePendingAttachmentEntityForTempId(tempId)
        val updated = when {
            pendingEntity != null -> pendingEntity.copy(
                id = realId,
                sendState = MessageEntity.SEND_STATE_SENT,
                content = if (pendingEntity.content.isNotBlank()) pendingEntity.content else old.content,
                senderId = if (pendingEntity.senderId != 0L) pendingEntity.senderId else old.senderId,
                senderName = pendingEntity.senderName.ifBlank { old.senderName },
                senderUsername = pendingEntity.senderUsername.ifBlank { old.senderUsername },
                senderAvatar = pendingEntity.senderAvatar.ifBlank { old.senderAvatar },
                timestampSeconds = old.timestampSeconds,
            )
            echoEntity != null -> chatController.mergeSelfSentMessageEcho(old, echoEntity)
            chatController.isIncrementalAttachmentJobActive(realId) -> old.copy(
                id = realId,
                sendState = MessageEntity.SEND_STATE_SENT,
            )
            else -> old.copy(id = realId, sendState = MessageEntity.SEND_STATE_SENT)
        }
        messages[idx] = updated
        messagesDict.put(realId, updated)
        Log.d(TAG, "applyRealId tempId=$tempId → realId=$realId")
        if (fragmentView == null) return
        val cellMask = when {
            pendingEntity != null || echoEntity != null ->
                NotificationCenter.UPDATE_MASK_SEND_STATE or NotificationCenter.UPDATE_MASK_ATTACHMENTS or
                    NotificationCenter.UPDATE_MASK_MESSAGE_TEXT
            chatController.isIncrementalAttachmentJobActive(realId) ->
                NotificationCenter.UPDATE_MASK_SEND_STATE or NotificationCenter.UPDATE_MASK_ATTACHMENTS
            else -> NotificationCenter.UPDATE_MASK_SEND_STATE
        }
        for (i in 0 until recyclerView.childCount) {
            val cell = recyclerView.getChildAt(i) as? ChatMessageCell ?: continue
            if (cell.messageEntity?.id == tempId || cell.messageEntity?.id == realId) {
                cell.update(cellMask, updated)
                break
            }
        }
    }

    private fun resendFailedMessage(msg: MessageEntity) {
        if (msg.sendState != MessageEntity.SEND_STATE_ERROR || !msg.isMe) return
        val idx = messages.indexOfFirst { it.id == msg.id }
        if (idx < 0) return
        val updated = msg.copy(sendState = MessageEntity.SEND_STATE_SENDING, isError = false)
        messages[idx] = updated
        messagesDict.put(updated.id, updated)
        if (fragmentView != null) {
            updateVisibleRows(NotificationCenter.UPDATE_MASK_SEND_STATE)
        }
        chatController.resendFailedMessage(
            channelId,
            clanId,
            channelType,
            resolveChannelPrivate(),
            msg,
        )
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
            messageSenderUsername = target.senderUsername.ifBlank { target.senderName }
            messageSenderAvatar = target.senderAvatar
            messageSenderClanNick = if (clanId != 0L) target.senderName else ""
            messageSenderDisplayName = target.senderName
            content = target.content
            hasAttachment = target.hasMedia || target.isFileAttachment
        }
        return listOf(ref)
    }

    private var pendingHighlightMessageId = 0L
    private var pendingJumpMessageId = 0L

    private fun scrollToReplyMessage(messageId: Long) {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            scrollToAndHighlight(idx)
        } else {
            Log.d(TAG, "Reply message $messageId not in list, calling loadMessagesAround")
            pendingHighlightMessageId = messageId
            chatController.loadMessagesAround(
                channelId,
                clanId,
                messageId,
                requireExactAnchor = true,
                preferHttp = openedFromNotification,
                topicId = topicId
            )
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

    private fun checkSuggestionTrigger() {
        val text = inputField.text?.toString() ?: ""
        val cursor = inputField.selectionStart
        if (cursor <= 0 || text.isEmpty()) {
            hideSuggestionsPopup()
            return
        }

        val trigger = InputSuggestionsController.detect(text, cursor)
        if (trigger.mode == InputSuggestionsController.Mode.NONE) {
            hideSuggestionsPopup()
            return
        }

        val items: List<InputSuggestionItem> = when (trigger.mode) {
            InputSuggestionsController.Mode.MENTION -> buildMentionSuggestions(trigger.keyword)
            InputSuggestionsController.Mode.HASHTAG -> buildHashtagSuggestions(trigger.keyword)
            InputSuggestionsController.Mode.EMOJI -> buildEmojiSuggestions(trigger.keyword)
            else -> emptyList()
        }

        if (items.isEmpty()) {
            hideSuggestionsPopup()
            return
        }

        currentTrigger = trigger
        suggestionsAdapter?.submit(items)
        suggestionsPopup?.updateVisibility(true)
    }

    private fun hideSuggestionsPopup() {
        suggestionsPopup?.updateVisibility(false)
        suggestionsAdapter?.clear()
        currentTrigger = InputSuggestionsController.TriggerState.NONE
    }

    private fun buildMentionSuggestions(keyword: String): List<InputSuggestionItem> {
        val members = resolveMentionMembers()
        val isChannelOrThread = channelType != CHANNEL_TYPE_DM && channelType != CHANNEL_TYPE_GROUP
        val roles = if (isChannelOrThread && clanId != 0L) {
            roleController.getRoles(clanId).also {
                if (it.isEmpty()) roleController.loadRolesForClan(clanId)
            }
        } else emptyList()
        val includeHere = channelType != CHANNEL_TYPE_DM
        val ctx = InputSuggestionsController.MentionContext(
            members = members,
            roles = roles,
            includeHere = includeHere,
            includeRoles = isChannelOrThread
        )
        return InputSuggestionsController.buildMentionItems(keyword, ctx)
    }

    private fun buildHashtagSuggestions(keyword: String): List<InputSuggestionItem> {
        val isDmLike = channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP
        val channels = if (isDmLike || clanId == 0L) {
            val cached = searchController.getChannels()
            if (cached.isEmpty()) searchController.loadChannels()
            cached
        } else {
            channelController.getChannels(clanId)
        }
        return InputSuggestionsController.buildChannelItems(keyword, channels)
    }

    private fun buildEmojiSuggestions(keyword: String): List<InputSuggestionItem> {
        val snapshot = synchronized(emojiController) { emojiController.emojis.toList() }
        return InputSuggestionsController.buildEmojiItems(keyword, snapshot)
    }

    private fun mergeAtHereMentionsFromText(cleanedText: String, existing: List<MentionData>): List<MentionData> {
        val result = existing.toMutableList()
        if (cleanedText.isEmpty()) return result
        for (match in AT_HERE_INPUT_REGEX.findAll(cleanedText)) {
            val s = match.range.first
            val e = match.range.last + 1
            if (result.any { mentionIntervalsOverlap(it.startOffset, it.endOffset, s, e) }) continue
            result.add(
                MentionData(
                    userId = ChatController.ID_MENTION_HERE,
                    display = "@here",
                    startOffset = s,
                    endOffset = e
                )
            )
        }
        return result
    }

    private fun mentionIntervalsOverlap(a0: Int, a1: Int, b0: Int, b1: Int): Boolean =
        a0 < b1 && b0 < a1

    private fun onSuggestionSelected(item: InputSuggestionItem) {
        val editable = inputField.text ?: return
        val trigger = currentTrigger
        if (trigger.mode == InputSuggestionsController.Mode.NONE || trigger.triggerPos < 0) return

        val triggerPos = trigger.triggerPos
        val replaceEnd = minOf(triggerPos + trigger.queryLen, editable.length)

        when (item) {
            is InputSuggestionItem.Here -> {
                insertMentionToken(editable, triggerPos, replaceEnd, "@here", ChatController.ID_MENTION_HERE, "", themeColors.textLink)
            }
            is InputSuggestionItem.Member -> {
                val member = item.member
                val displayName = member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
                insertMentionToken(editable, triggerPos, replaceEnd, "@$displayName", member.userId.toString(), "", themeColors.textLink)
            }
            is InputSuggestionItem.Role -> {
                val role = item.role
                val color = if (role.color != 0) role.color else themeColors.textRoleLink
                insertMentionToken(editable, triggerPos, replaceEnd, "@${role.title}", "", role.roleId.toString(), color)
            }
            is InputSuggestionItem.Channel -> {
                insertHashtagToken(editable, triggerPos, replaceEnd, item.entity)
            }
            is InputSuggestionItem.Emoji -> {
                insertEmojiToken(editable, triggerPos, replaceEnd, item.item)
            }
        }
        hideSuggestionsPopup()
    }

    private fun insertMentionToken(
        editable: android.text.Editable,
        start: Int,
        end: Int,
        tokenText: String,
        userId: String,
        roleId: String,
        color: Int
    ) {
        val insertText = "$tokenText "
        editable.replace(start, end, insertText)
        val spanStart = start
        val spanEnd = start + tokenText.length
        editable.setSpan(
            android.text.style.ForegroundColorSpan(color),
            spanStart, spanEnd,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        editable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            spanStart, spanEnd,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        inputField.setSelection(start + insertText.length)
        mentionTrackers.add(
            MentionData(
                userId = userId,
                roleId = roleId,
                display = tokenText,
                startOffset = spanStart,
                endOffset = spanEnd
            )
        )
    }

    private fun insertHashtagToken(
        editable: android.text.Editable,
        start: Int,
        end: Int,
        entity: ClanChannelEntity
    ) {
        val tokenText = "#${entity.channelLabel}"
        val insertText = "$tokenText "
        editable.replace(start, end, insertText)
        val spanStart = start
        val spanEnd = start + tokenText.length
        editable.setSpan(
            HashtagSpan(entity.channelId.toString(), themeColors.textLink),
            spanStart, spanEnd,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        editable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            spanStart, spanEnd,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        inputField.setSelection(start + insertText.length)
        hashtagTrackers.add(
            HashtagData(
                channelId = entity.channelId.toString(),
                startOffset = spanStart,
                endOffset = spanEnd,
                clanId = entity.clanId.toString()
            )
        )
    }

    private fun insertEmojiToken(
        editable: android.text.Editable,
        start: Int,
        end: Int,
        emoji: EmojiItem
    ) {
        val cleanName = emoji.shortname.replace(":", "")
        val token = ":$cleanName:"
        val insertText = "$token "
        editable.replace(start, end, insertText)
        emojiObjPicked[token] = emoji.id
        val spanStart = start
        val spanEnd = start + token.length
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
        inputField.setSelection(start + insertText.length)
    }

    private fun channelTitleIconSizePx(): Int = LayoutHelper.dp(20)

    private fun channelTitleIconDrawable(context: Context, iconEnum: MezonIcon): Drawable {
        val drawable = iconEnum.getDrawable(context, themeColors)
        if (!iconEnum.shouldKeepOriginalFill()) {
            drawable.colorFilter = PorterDuffColorFilter(themeColors.textStrong, PorterDuff.Mode.SRC_IN)
        }
        return drawable
    }

    private fun buildChannelTitle(context: Context): CharSequence {
        val isDmChannel = channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP
        if (isDmChannel || clanId == 0L) return channelName

        val entity = channelController.findChannelById(channelId)
        val iconEnum = resolveChannelIcon(entity)
        val isThread = entity?.isThread == true || channelType == CHANNEL_TYPE_THREAD || routeParentId != 0L

        val iconSize = channelTitleIconSizePx()
        val span = ColoredImageSpan(iconEnum.getDrawable(context, themeColors), ColoredImageSpan.ALIGN_CENTER)
        span.setSize(iconSize)
        if (isThread) {
            span.usePaintColor = false
        } else {
            span.overrideColor = themeColors.textStrong
        }

        val text = SpannableString("\u200B $channelName")
        text.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    private fun resolveChannelIcon(entity: com.mezon.mobile.home.clans.ClanChannelEntity?): MezonIcon {
        val isAgeRestricted = entity?.isAgeRestricted ?: resolveChannelAgeRestricted()
        if (entity == null) {
            if (channelType == CHANNEL_TYPE_THREAD || routeParentId != 0L) {
                return ChannelItemCell.resolveChannelIcon(CHANNEL_TYPE_THREAD, routeChannelPrivate, isAgeRestricted)
            }
            if (channelType == CHANNEL_TYPE_CHANNEL) {
                return ChannelItemCell.resolveChannelIcon(CHANNEL_TYPE_CHANNEL, routeChannelPrivate, isAgeRestricted)
            }
            return ChannelItemCell.resolveChannelIcon(channelType, routeChannelPrivate, isAgeRestricted)
        }
        val type = if (entity.isThread) CHANNEL_TYPE_THREAD else entity.type
        return ChannelItemCell.resolveChannelIcon(type, entity.isPrivate, isAgeRestricted)
    }

    private fun navigateToChannelFromHashtag(channelIdStr: String?) {
        val cid = channelIdStr?.toLongOrNull() ?: return
        if (cid == 0L) return
        val entity = channelController.findChannelById(cid, 0L)
            ?: searchController.findChannelById(cid)
        if (entity == null) {
            if (!searchController.hasChannels()) searchController.loadChannels()
            return
        }
        openChannelEntity(entity)
    }

    private fun openChannelAppFromHotbar() {
        hideEmojiView()
        AndroidUtilities.hideKeyboard(inputField)
        appScope.launch(ioDispatcher) {
            val app = channelAppController.ensureAppLoaded(channelId, clanId)
            withContext(mainDispatcher) {
                if (isPaused) return@withContext
                if (app == null) {
                    MezonToast.show(
                        this@ChatFragment,
                        ToastOverlay.ToastType.INFO,
                        getString(R.string.channel_app_launch_unavailable)
                    )
                    return@withContext
                }
                presentFragment(
                    ChannelAppFragment.newInstance(
                        channelId = app.channelId,
                        clanId = if (app.clanId != 0L) app.clanId else clanId,
                        appId = app.appId,
                        appUrl = app.appUrl,
                        appName = app.appName
                    )
                )
            }
        }
    }

    private fun openChannelEntity(entity: ClanChannelEntity) {
        val targetClanId = if (entity.clanId != 0L) entity.clanId else clanId
        when (entity.type) {
            CHANNEL_TYPE_VOICE, CHANNEL_TYPE_STREAMING -> {
                showJoinVoiceBottomSheet(entity, targetClanId)
            }
            CHANNEL_TYPE_APP -> {
                val app = channelAppController.findByChannelId(entity.channelId)
                if (app != null) {
                    presentFragment(
                        ChannelAppFragment.newInstance(
                            channelId = app.channelId,
                            clanId = if (app.clanId != 0L) app.clanId else targetClanId,
                            appId = app.appId,
                            appUrl = app.appUrl,
                            appName = app.appName
                        )
                    )
                } else {
                    channelAppController.loadAppsForClan(targetClanId)
                }
            }
            else -> {
                val resolvedType = if (entity.isThread) CHANNEL_TYPE_THREAD else entity.type
                (getParentActivity() as? MainActivity)?.openChat(
                    entity.channelId,
                    entity.channelLabel,
                    targetClanId,
                    resolvedType
                )
            }
        }
    }

    private fun showJoinVoiceBottomSheet(channel: ClanChannelEntity, targetClanId: Long) {
        val activity = getParentActivity() ?: return
        val memberIds = voiceController.getVoiceMembersForChannel(channel.channelId, targetClanId)
        val clanMembers = userClanController.getClanMembers(targetClanId)
        val memberMap = HashMap<Long, ClanMember>(clanMembers.size)
        for (m in clanMembers) memberMap[m.userId] = m
        val displays = memberIds.map { uid ->
            val m = memberMap[uid]
            val name = m?.clanNick?.ifEmpty { null } ?: m?.displayName?.ifEmpty { null } ?: m?.username ?: "User"
            val username = m?.username.orEmpty()
            val avatar = m?.clanAvatar?.ifEmpty { null } ?: m?.avatarUrl
            VoiceMemberDisplay(uid, name, username, avatar)
        }
        val sheet = JoinVoiceBottomSheet(
            activity,
            themeColors,
            channel.channelLabel,
            channel.channelId,
            targetClanId,
            displays,
            channel.unreadCount
        )
        sheet.onJoinVoice = {
            (activity as? MainActivity)?.showVoiceRoom(
                channel.channelId, targetClanId, channel.channelLabel
            )
        }
        sheet.onOpenChat = {
            (activity as? MainActivity)?.openChat(
                channel.channelId,
                channel.channelLabel,
                targetClanId,
                channel.type
            )
        }
        sheet.show()
    }

    private var pendingCallPermissionCallback: (() -> Unit)? = null
    private var pendingCallPermissionRequestIncludedCamera = false

    private fun requestCallPermissions(
        needsCamera: Boolean = true,
        reason: String = "unspecified",
        onGranted: () -> Unit
    ) {
        val activity = getParentActivity()
        if (activity == null) {
            Log.w(TAG, "requestCallPermissions skipped no activity reason=$reason")
            onGranted()
            return
        }
        val needed = mutableListOf<String>()
        if (androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (needsCamera &&
            androidx.core.content.ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) {
            Log.d(TAG, "requestCallPermissions already granted reason=$reason")
            pendingCallPermissionRequestIncludedCamera = false
            runOutgoingCallAfterFullScreenIntentPrompt(onGranted)
        } else {
            Log.d(TAG, "requestCallPermissions prompting reason=$reason perms=$needed")
            pendingCallPermissionRequestIncludedCamera =
                needed.contains(android.Manifest.permission.CAMERA)
            pendingCallPermissionCallback = { runOutgoingCallAfterFullScreenIntentPrompt(onGranted) }
            androidx.core.app.ActivityCompat.requestPermissions(activity, needed.toTypedArray(), REQUEST_CALL_PERMISSIONS)
        }
    }

    private fun runOutgoingCallAfterFullScreenIntentPrompt(startCall: () -> Unit) {
        val act = getParentActivity()
        if (!callManager.needsFullScreenIntentSettings() || act == null) {
            startCall()
            return
        }
        AlertDialog.Builder(act)
            .setTitle(getString(R.string.call_full_screen_intent_title))
            .setMessage(getString(R.string.call_full_screen_intent_message))
            .setPositiveButton(getString(R.string.call_full_screen_intent_open_settings)) { d, _ ->
                callManager.launchFullScreenIntentSettings(act)
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.call_full_screen_intent_start_call_anyway)) { d, _ ->
                d.dismiss()
                startCall()
            }
            .show()
    }

    private fun openTopicDiscussion(topicId: Long, rootMessageId: Long) {
        if (topicId == 0L || rootMessageId == 0L) return
        presentFragment(
            TopicFragment.newInstance(
                topicId = topicId,
                rootMessageId = rootMessageId,
                clanId = clanId,
                parentChannelId = channelId,
                channelType = channelType,
                isChannelPrivate = resolveChannelPrivate()
            )
        )
    }

    private fun loadTopicRootHeaderMessage() {
        if (!isTopicMode || rootMessageId == 0L) return
        appScope.launch(ioDispatcher) {
            chatController.reloadChannelMessageIfMissing(channelId, clanId, rootMessageId)
            var root = chatController.getMessageById(channelId, rootMessageId)
            if (root == null) {
                val detail = topicController.fetchTopicDetail(topicId)
                if (detail != null) {
                    root = MessageEntity(
                        id = detail.messageId,
                        channelId = channelId,
                        senderId = detail.creatorId,
                        senderName = "",
                        senderUsername = "",
                        senderAvatar = "",
                        content = detail.content,
                        timestampSeconds = detail.createTimeSeconds,
                        code = MessageEntity.CODE_TOPIC,
                        topicId = topicId,
                        topicCreatorId = detail.creatorId
                    )
                }
            }
            val base = root ?: return@launch
            val creatorId = base.topicCreatorId.takeIf { it != 0L } ?: base.senderId
            val member = memberResolver.resolveMember(creatorId, clanId, channelId, channelType)
            val resolved = if (member != null) {
                base.copy(
                    senderId = creatorId,
                    senderName = member.clanNick.ifBlank { member.displayName.ifBlank { member.username } },
                    senderUsername = member.username,
                    senderAvatar = member.clanAvatar.ifBlank { member.avatarUrl }
                )
            } else {
                base
            }
            withContext(mainDispatcher) {
                cachedTopicRootMessage = resolved
                if (::adapter.isInitialized) {
                    adapter.topicRootMessage = resolved
                    adapter.notifyTopicRootHeaderChanged()
                }
            }
        }
    }

}
