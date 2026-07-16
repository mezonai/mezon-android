package com.mezon.mobile.home.chat.thread

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.AnonymousController
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.TopicController
import com.mezon.mobile.home.chat.TopicFragment
import android.content.pm.PackageManager
import com.mezon.mobile.home.chat.AttachmentPickerItem
import com.mezon.mobile.home.chat.input.VoiceRecorder
import com.mezon.mobile.home.chat.input.VoiceRecordingOverlay
import com.mezon.mobile.home.chat.ChatAttachAlert
import com.mezon.mobile.home.chat.CameraPhotoCapture
import com.mezon.mobile.home.chat.CameraPhotoReviewDialog
import com.mezon.mobile.home.chat.CameraPermissionPrompt
import com.mezon.mobile.home.chat.EmojiItem
import com.mezon.mobile.home.chat.emoji.EmojiView
import com.mezon.mobile.home.chat.HashtagSpan
import com.mezon.mobile.home.chat.ImageClipboardCoordinator
import com.mezon.mobile.home.chat.MediaController
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.chat.PasteImagePasteTooltipContent
import com.mezon.mobile.home.chat.StickerItem
import com.mezon.mobile.home.chat.ThumbnailCache
import com.mezon.mobile.home.chat.input.InputSuggestionItem
import com.mezon.mobile.home.chat.input.InputSuggestionsAdapter
import com.mezon.mobile.home.chat.input.InputSuggestionsController
import com.mezon.mobile.home.chat.input.InputSuggestionsPopup
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.toClanChannelEntity
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.search.SearchController
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.SwitchView
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.util.FileUtils
import com.mezon.mobile.util.HashtagData
import com.mezon.mobile.util.MentionData
import com.mezon.mobile.util.isEmbedOrComponentsPayload
import com.mezon.mobile.util.parseContentPreview
import com.mezon.mobile.util.parseMarkdownAndStrip
import com.mezon.mobile.home.chat.AttachmentInfo
import com.mezon.mobile.home.chat.CreateThreadSeedStash
import com.mezon.mobile.home.chat.isMediaAttachment
import com.mezon.mobile.home.sharing.ForwardPreviewThumbHost
import com.mezon.mobile.util.resolveStickerSourceUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AT_HERE_INPUT_REGEX = Regex("(?<!\\w)@here(?!\\w)")

class CreateThreadFragment : BaseFragment() {

    companion object {
        private const val LOG_TAG = "CreateThread"
        private const val ARG_PARENT_CHANNEL_ID = "parentChannelId"
        private const val ARG_PARENT_LABEL = "parentLabel"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_SEED_MESSAGE_ID = "seedMessageId"
        private const val ARG_USE_TOPIC_FLOW = "useTopicFlow"
        private const val REQUEST_CODE_PICK_FILE = 1012
        private const val REQUEST_CODE_RECORD_AUDIO = 1013
        private const val REQUEST_CODE_TAKE_PHOTO = 1014
        private const val REQUEST_CODE_CAMERA_PERMISSION = 1015
        private const val VOICE_LONG_PRESS_DELAY_MS = 400L
        private const val VOICE_CANCEL_SLIDE_DP = 100f

        fun newInstance(
            parentChannelId: Long,
            parentLabel: String,
            clanId: Long,
            seedMessageId: Long = 0L,
            useTopicFlow: Boolean = false
        ): CreateThreadFragment = CreateThreadFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_PARENT_CHANNEL_ID, parentChannelId)
                putString(ARG_PARENT_LABEL, parentLabel)
                putLong(ARG_CLAN_ID, clanId)
                if (seedMessageId != 0L) putLong(ARG_SEED_MESSAGE_ID, seedMessageId)
                if (useTopicFlow) putBoolean(ARG_USE_TOPIC_FLOW, true)
            }
        }
    }

    private var parentChannelId = 0L
    private var parentLabel = ""
    private var clanId = 0L
    private var seedMessageId = 0L
    private var useTopicFlow = false

    private lateinit var chatController: ChatController
    private lateinit var topicController: TopicController
    private lateinit var channelController: ChannelController
    private lateinit var sessionManager: SessionManager
    private lateinit var mezonApi: MezonApi
    private lateinit var memberResolver: MemberResolver
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher

    private var nameField: EditText? = null
    private var nameBlock: LinearLayout? = null
    private var nameErrorRow: LinearLayout? = null
    private var errorText: TextView? = null
    private var privateRow: LinearLayout? = null
    private var previewBox: LinearLayout? = null
    private var sendButton: ImageButton? = null
    private var submitProgress: ProgressBar? = null
    private var privateSwitch: SwitchView? = null
    private var titleMain: TextView? = null
    private var titleSub: TextView? = null
    private var channelIconSlot: FrameLayout? = null

    private lateinit var emojiController: EmojiController
    private lateinit var mediaController: MediaController
    private lateinit var imageClipboardCoordinator: ImageClipboardCoordinator
    private lateinit var searchController: SearchController
    private lateinit var roleController: RoleController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var anonymousController: AnonymousController
    private lateinit var appScope: CoroutineScope

    private lateinit var composerField: EditText
    private var attachButton: ImageButton? = null
    private var emojiButton: ImageButton? = null
    private var micButton: ImageButton? = null
    private var inputWrapper: FrameLayout? = null
    private var attachmentPreviewScroll: HorizontalScrollView? = null
    private var attachmentPreviewStrip: LinearLayout? = null
    private var suggestionsPopup: InputSuggestionsPopup? = null
    private var suggestionsAdapter: InputSuggestionsAdapter? = null
    private var formScroll: ScrollView? = null
    private var emojiView: EmojiView? = null
    private var emojiHost: FrameLayout? = null
    private var pasteImagePopup: PopupWindow? = null
    private var pasteInputGesture: GestureDetectorCompat? = null
    private var emojiViewVisible = false
    private var waitingForKeyboardOpen = false
    private val pendingAttachments = ArrayList<AttachmentPickerItem>()
    private var pendingCameraCapture: CameraPhotoCapture? = null
    private var cameraSourceAlert: ChatAttachAlert? = null
    private var activeCameraReview: CameraPhotoReviewDialog? = null
    private val pendingAttachmentThumbTasks = ArrayList<Runnable?>()
    private val mentionTrackers = mutableListOf<MentionData>()
    private val hashtagTrackers = mutableListOf<HashtagData>()
    private val emojiObjPicked = linkedMapOf<String, String>()
    private var currentTrigger: InputSuggestionsController.TriggerState = InputSuggestionsController.TriggerState.NONE
    private var mediaPermissionDeniedOnce = false
    private var pendingStickerSend: PendingStickerSend? = null

    private data class PendingStickerSend(val url: String, val filetype: String, val filename: String?)

    private data class TopicComposerPayload(
        val rawInput: String,
        val attachments: ArrayList<AttachmentPickerItem>,
        val sticker: PendingStickerSend?,
        val mentions: List<MentionData>,
        val hashtags: List<HashtagData>,
        val emojiEntries: LinkedHashMap<String, String>
    )

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var voiceRecorder: VoiceRecorder? = null
    private var voiceOverlay: VoiceRecordingOverlay? = null
    private var voiceIsRecording = false
    private var voiceCancelled = false
    private var voiceLongPressFired = false
    private var voiceTouchDownX = 0f
    private val voiceLongPressRunnable = Runnable { onVoiceLongPressFired() }
    private var voiceCompleteRunnable: Runnable? = null

    private val openKeyboardRunnable = object : Runnable {
        override fun run() {
            if (!waitingForKeyboardOpen || isPaused) return
            AndroidUtilities.showKeyboard(composerField)
            AndroidUtilities.runOnUIThread(this, 100)
        }
    }

    private val showKeyboardFromEmojiRunnable = Runnable {
        composerField.requestFocus()
        waitingForKeyboardOpen = true
        AndroidUtilities.cancelRunOnUIThread(openKeyboardRunnable)
        AndroidUtilities.showKeyboard(composerField)
        AndroidUtilities.runOnUIThread(openKeyboardRunnable, 100)
    }

    private var seedMessage: MessageEntity? = null
    private var isSubmitting = false
    private var nameTouched = false
    private var submitAttempted = false

    private val fromTopicFlow: Boolean get() = useTopicFlow && seedMessageId != 0L
    private val hasSeedMessage: Boolean get() = seedMessageId != 0L

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        parentChannelId = arguments?.getLong(ARG_PARENT_CHANNEL_ID) ?: 0L
        parentLabel = arguments?.getString(ARG_PARENT_LABEL) ?: ""
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        seedMessageId = arguments?.getLong(ARG_SEED_MESSAGE_ID) ?: 0L
        useTopicFlow = arguments?.getBoolean(ARG_USE_TOPIC_FLOW) == true
        permissionPolicy.ensurePermissionChecker(
            listOf(PermissionPolicy.CLAN_OWNER, PermissionPolicy.MANAGE_THREAD, PermissionPolicy.MANAGE_CHANNEL),
            parentChannelId,
            clanId
        )
        return true
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        chatController = entryPoint.chatController()
        topicController = entryPoint.topicController()
        channelController = entryPoint.channelController()
        sessionManager = entryPoint.sessionManager()
        mezonApi = entryPoint.mezonApi()
        memberResolver = entryPoint.memberResolver()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
        emojiController = entryPoint.emojiController()
        mediaController = entryPoint.mediaController()
        imageClipboardCoordinator = entryPoint.imageClipboardCoordinator()
        searchController = entryPoint.searchController()
        roleController = entryPoint.roleController()
        permissionPolicy = entryPoint.permissionPolicy()
        anonymousController = entryPoint.anonymousController()
        appScope = entryPoint.applicationScope()
    }

    override fun createView(context: Context): View {
        val root = FrameLayout(context)

        root.background = ColorDrawable(themeColors.chatBackground)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isBaselineAligned = false
            clipChildren = false
        }

        column.addView(buildHeader(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val contentFrame = FrameLayout(context).apply {
            clipChildren = false
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        formScroll = scroll

        val scrollInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val topBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val h = LayoutHelper.dp(20)
            setPadding(h, h, h, h)
        }
        topBlock.addView(buildThreadIconRow(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        topBlock.addView(buildNameField(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        topBlock.addView(buildPreviewBox(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        scrollInner.addView(topBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        scrollInner.addView(
            View(context),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f)
        )
        scrollInner.addView(buildPrivateRow(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        scroll.addView(
            scrollInner,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        contentFrame.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        suggestionsAdapter = InputSuggestionsAdapter(themeColors) { onSuggestionSelected(it) }
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

        column.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        column.addView(
            buildMessageComposerSection(context),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        submitProgress = ProgressBar(context).apply { visibility = View.GONE }
        root.addView(submitProgress, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        fragmentView = root

        if (fromTopicFlow) {
            nameBlock?.visibility = View.GONE
            privateRow?.visibility = View.GONE
            titleSub?.visibility = View.VISIBLE
            titleSub?.text = parentLabel
            channelIconSlot?.visibility = View.VISIBLE
            updateHeaderTopicIcon()
            titleMain?.text = getString(R.string.topic_discussion)
            loadSeedMessageForPreview()
        } else {
            titleSub?.visibility = View.GONE
            channelIconSlot?.visibility = View.VISIBLE
            titleMain?.text = parentLabel
            updateHeaderChannelIcon()
            if (hasSeedMessage) {
                loadSeedMessageForPreview()
            }
        }

        refreshNameError()

        return root
    }

    private fun buildHeader(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(themeColors.chatBackground)
            val padH = LayoutHelper.dp(8)
            val padV = LayoutHelper.dp(10)
            setPadding(padH, AndroidUtilities.statusBarHeight + padV, padH, padV)
        }

        val back = ImageView(context).apply {
            val d = MezonIcon.chevronSmallLeftIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.textStrong, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
            setOnClickListener { finishFragment() }
        }
        row.addView(back, LayoutHelper.createLinear(36, 36, 0f, Gravity.CENTER_VERTICAL))

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padStart = LayoutHelper.dp(4)
            setPadding(padStart, 0, 0, 0)
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        channelIconSlot = FrameLayout(context).apply {
            val size = LayoutHelper.dp(24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = LayoutHelper.dp(6)
            }
        }
        titleRow.addView(channelIconSlot)

        titleMain = TextView(context).apply {
            setTextColor(themeColors.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleRow.addView(titleMain, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        val chevron = ImageView(context).apply {
            val d = MezonIcon.chevronSmallRightIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        titleRow.addView(chevron, LayoutHelper.createLinear(14, 14, 0f, Gravity.CENTER_VERTICAL))

        titleCol.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        titleSub = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        titleCol.addView(titleSub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        row.addView(titleCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        return row
    }

    private fun updateHeaderChannelIcon() {
        val slot = channelIconSlot ?: return
        val ctx = slot.context
        slot.removeAllViews()
        val parentMeta = channelController.findChannelById(parentChannelId, clanId)
        val icon = when {
            parentMeta?.type == CHANNEL_TYPE_CHANNEL && parentMeta.isAgeRestricted -> MezonIcon.channelTextWarning
            parentMeta?.isPrivate == true && parentMeta.type == CHANNEL_TYPE_CHANNEL -> MezonIcon.channelTextLock
            else -> MezonIcon.channelText
        }
        val iv = ImageView(ctx).apply {
            val d = icon.getDrawable(ctx)
            if (icon == MezonIcon.channelTextLock || icon == MezonIcon.threadLockIcon) {
            } else {
                d.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            }
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        slot.addView(iv, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
    }

    private fun updateHeaderTopicIcon() {
        val slot = channelIconSlot ?: return
        val ctx = slot.context
        slot.removeAllViews()
        val iv = ImageView(ctx).apply {
            val d = MezonIcon.notificationTabTopic.getDrawable(ctx)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        slot.addView(iv, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
    }

    private fun resolveHostMainActivity(): MainActivity? {
        (getParentActivity() as? MainActivity)?.let { return it }
        val starts = ArrayList<Context?>()
        fragmentView?.context?.let { starts.add(it) }
        if (::composerField.isInitialized) starts.add(composerField.context)
        for (start in starts) {
            var c: Context? = start
            while (c is ContextWrapper) {
                if (c is MainActivity) return c
                c = c.baseContext
            }
        }
        return null
    }

    private fun buildThreadIconRow(context: Context): FrameLayout {
        val wrap = FrameLayout(context).apply {
            setPadding(0, 0, 0, LayoutHelper.dp(8))
        }
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
        }
        val icon = ImageView(context).apply {
            val iconType = if (fromTopicFlow) MezonIcon.notificationTabTopic else MezonIcon.threadIcon
            setImageDrawable(iconType.getDrawable(context))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val slot = LayoutHelper.dp(26)
        val iconSize = LayoutHelper.dp(15)
        circle.addView(icon, LayoutHelper.createFrame(iconSize, iconSize, Gravity.CENTER))
        wrap.addView(circle, LayoutHelper.createFrame(slot, slot, Gravity.START))
        return wrap
    }

    private fun buildNameField(context: Context): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        nameBlock = col
        val label = TextView(context).apply {
            text = getString(R.string.create_thread_name_label)
            setTextColor(themeColors.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        col.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 5f))

        nameField = EditText(context).apply {
            setTextColor(themeColors.onSurface)
            setHintTextColor(themeColors.textDisabled)
            hint = getString(R.string.create_thread_name_placeholder)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dpf(6f)
                setStroke(LayoutHelper.dp(1), themeColors.outlineVariant)
            }
            val p = LayoutHelper.dp(10)
            setPadding(p, 0, p, 0)
            minHeight = LayoutHelper.dp(40)
            gravity = Gravity.CENTER_VERTICAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    nameTouched = true
                    refreshNameError()
                }
            })
        }
        col.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 20f))

        val errRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        nameErrorRow = errRow
        val errIcon = ImageView(context).apply {
            val d = MezonIcon.circleExlaimionIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.error, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        errRow.addView(
            errIcon,
            LayoutHelper.createLinear(LayoutHelper.dp(12), LayoutHelper.dp(12), 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 6f, 0f)
        )
        errorText = TextView(context).apply {
            setTextColor(themeColors.error)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        errRow.addView(errorText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
        col.addView(errRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 6f, 20f, 0f))

        return col
    }

    private fun buildPrivateRow(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = LayoutHelper.dp(10)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(themeColors.channelPanelBg)
            }
        }
        privateRow = row

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val t1 = TextView(context).apply {
            text = getString(R.string.create_thread_private_title)
            setTextColor(themeColors.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        info.addView(t1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val t2 = TextView(context).apply {
            text = getString(R.string.create_thread_private_desc)
            setTextColor(themeColors.colorText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        info.addView(t2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 0f))
        row.addView(info, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 20f, 0f))

        privateSwitch = SwitchView(context, themeColors).apply {
            setChecked(false, animated = false)
        }
        row.addView(privateSwitch, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))

        return row
    }

    private fun buildPreviewBox(context: Context): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        previewBox = box
        return box
    }

    private fun loadSeedMessageForPreview() {
        if (seedMessageId == 0L) return
        val stashed = CreateThreadSeedStash.takeSeedMessage(seedMessageId)
        if (stashed != null) {
            seedMessage = stashed
            bindPreview(stashed)
            return
        }
        fragmentScope.launch {
            var msg = chatController.getMessageById(parentChannelId, seedMessageId)
            if (msg == null) {
                chatController.reloadChannelMessageIfMissing(parentChannelId, clanId, seedMessageId)
                msg = chatController.getMessageById(parentChannelId, seedMessageId)
            }
            withContext(mainDispatcher) {
                seedMessage = msg
                bindPreview(msg)
            }
        }
    }

    private fun bindPreview(msg: MessageEntity?) {
        val box = previewBox ?: return
        val ctx = box.context
        box.removeAllViews()
        if (msg == null || !hasSeedMessage) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val topPad = LayoutHelper.dp(20)
        box.setPadding(0, topPad, 0, topPad)

        val lineColor = themeColors.outlineVariant
        val topLine = View(ctx).apply {
            setBackgroundColor(lineColor)
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(1))
        }
        box.addView(topLine)

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            val p = LayoutHelper.dp(12)
            setPadding(p, LayoutHelper.dp(16), p, LayoutHelper.dp(16))
        }

        val avatar = AvatarView(ctx).apply {
            setSizeDp(40)
            val m = memberResolver.resolveMember(msg.senderId, clanId, parentChannelId, parentChannelType())
            setInfo(msg.senderId, m?.username ?: msg.senderUsername)
            val url = m?.clanAvatar?.ifBlank { m.avatarUrl } ?: msg.senderAvatar
            if (url.isNotBlank()) setImageUrl(url)
        }
        inner.addView(avatar, LayoutHelper.createLinear(40, 40, 0f, Gravity.TOP, 0f, 0f, 12f, 0f))

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val nameTv = TextView(ctx).apply {
            val m = memberResolver.resolveMember(msg.senderId, clanId, parentChannelId, parentChannelType())
            text = m?.clanNick?.ifBlank { m.displayName.ifBlank { m.username } } ?: msg.senderName
            setTextColor(themeColors.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        textCol.addView(nameTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val previewText = seedPreviewText(msg)
        val body = TextView(ctx).apply {
            text = previewText
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            visibility = if (previewText.isEmpty()) View.GONE else View.VISIBLE
        }
        textCol.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 0f))
        val mediaAttachments = seedMediaAttachments(msg)
        if (mediaAttachments.isNotEmpty()) {
            val mediaRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val thumbSize = LayoutHelper.dp(56)
            val thumbHost = ForwardPreviewThumbHost(ctx)
            mediaRow.addView(thumbHost, LayoutHelper.createLinear(thumbSize, thumbSize, 0f, Gravity.START, 0f, 4f, 8f, 0f))
            val first = mediaAttachments[0]
            thumbHost.bind(first.url, first.thumb.takeIf { it.isNotBlank() } ?: first.url)
            val extra = mediaAttachments.size - 1
            if (extra > 0) {
                val plus = TextView(ctx).apply {
                    text = getString(R.string.forward_thumb_more, extra)
                    setTextColor(themeColors.onSurfaceVariant)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
                mediaRow.addView(plus, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            }
            textCol.addView(mediaRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 0f))
        } else {
            val attachmentSummary = seedAttachmentSummary(msg)
            if (attachmentSummary.isNotEmpty() && previewText.isEmpty()) {
                val summary = TextView(ctx).apply {
                    text = attachmentSummary
                    setTextColor(themeColors.onSurface)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }
                textCol.addView(summary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 0f))
            }
        }
        inner.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        box.addView(inner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val bottomLine = View(ctx).apply {
            setBackgroundColor(lineColor)
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(1))
        }
        box.addView(bottomLine)
    }

    private fun seedPreviewText(msg: MessageEntity): String {
        val text = parseContentPreview(msg.content).trim()
        if (text.isNotEmpty()) return text
        if (seedMediaAttachments(msg).isNotEmpty()) return ""
        return seedAttachmentSummary(msg)
    }

    private fun seedMediaAttachments(msg: MessageEntity): List<AttachmentInfo> {
        if (!msg.hasAnyMedia) return emptyList()
        val withUrl = msg.allAttachmentsInfo.filter { it.url.isNotEmpty() }
        if (withUrl.isEmpty()) return emptyList()
        val media = withUrl.filter { isMediaAttachment(it.filetype, it.url) }
        return media.ifEmpty { withUrl.take(1) }
    }

    private fun seedAttachmentSummary(msg: MessageEntity): String {
        val all = msg.allAttachmentsInfo.filter { it.url.isNotEmpty() }
        if (all.isEmpty()) {
            if (isEmbedOrComponentsPayload(msg.content)) return getString(R.string.message_attachment_file)
            return ""
        }
        var img = 0
        var vid = 0
        var fil = 0
        for (a in all) {
            when {
                a.filetype.startsWith("image/", ignoreCase = true) ||
                    a.filetype.contains("gif", ignoreCase = true) ||
                    a.url.contains("tenor.com", ignoreCase = true) -> img++
                a.filetype.startsWith("video/", ignoreCase = true) -> vid++
                else -> fil++
            }
        }
        val parts = ArrayList<String>(3)
        if (img > 0) {
            parts.add(
                if (img == 1) getString(R.string.forward_meta_photo, img)
                else getString(R.string.forward_meta_photos, img)
            )
        }
        if (vid > 0) {
            parts.add(
                if (vid == 1) getString(R.string.forward_meta_video, vid)
                else getString(R.string.forward_meta_videos, vid)
            )
        }
        if (fil > 0) {
            parts.add(
                if (fil == 1) getString(R.string.forward_meta_file, fil)
                else getString(R.string.forward_meta_files, fil)
            )
        }
        return parts.joinToString(", ")
    }

    private fun parentChannelType(): Int =
        channelController.findChannelById(parentChannelId, clanId)?.type ?: CHANNEL_TYPE_CHANNEL

    private fun buildMessageComposerSection(context: Context): LinearLayout {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.surface)
            clipChildren = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val extra = AndroidUtilities.navigationBarHeight.coerceAtLeast(nav.bottom)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, extra)
            insets
        }

        outer.addView(View(context).apply { setBackgroundColor(themeColors.outlineVariant) }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1))

        attachmentPreviewScroll = HorizontalScrollView(context).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            val pad = LayoutHelper.dp(8)
            setPadding(pad, pad, pad, 0)
        }
        attachmentPreviewStrip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        attachmentPreviewScroll!!.addView(
            attachmentPreviewStrip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LayoutHelper.dp(56f))
        )
        outer.addView(attachmentPreviewScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val inputBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            val p = LayoutHelper.dp(8)
            setPadding(p, p, p, p + LayoutHelper.dp(4))
        }

        val btnPad = LayoutHelper.dp(8f)
        attachButton = ImageButton(context).apply {
            val drawable = MezonIcon.plusLargeIcon.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(
                themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary), PorterDuff.Mode.SRC_IN
            )
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(btnPad, btnPad, btnPad, btnPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.tertiary)
            }
            setOnClickListener { showAttachmentPicker() }
        }
        inputBar.addView(attachButton, LayoutHelper.createLinear(40, 40, gravity = Gravity.BOTTOM))

        inputWrapper = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        inputBar.addView(inputWrapper, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.BOTTOM, 8f, 0f, 8f, 0f))

        composerField = EditText(context).apply {
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
            background = GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dp(20f).toFloat()
            }
            setPadding(LayoutHelper.dp(20f), LayoutHelper.dp(8f), LayoutHelper.dp(40f), LayoutHelper.dp(12f))
        }
        inputWrapper!!.addView(
            composerField,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )

        emojiButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_emoji_icon)
            setColorFilter(
                PorterDuffColorFilter(
                    themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary),
                    PorterDuff.Mode.SRC_IN
                )
            )
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener {
                if (emojiViewVisible) openKeyboardFromEmoji() else showEmojiView()
            }
        }
        inputWrapper!!.addView(
            emojiButton,
            FrameLayout.LayoutParams(LayoutHelper.dp(24f), LayoutHelper.dp(24f), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = LayoutHelper.dp(8f)
                bottomMargin = LayoutHelper.dp(8f)
            }
        )

        if (fromTopicFlow) {
            voiceOverlay = VoiceRecordingOverlay(context, themeColors).apply {
                setSlideToCancelText(getString(R.string.voice_record_slide_to_cancel))
            }
            inputWrapper!!.addView(
                voiceOverlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        if (fromTopicFlow) {
            micButton = ImageButton(context).apply {
                val drawable = MezonIcon.microphoneIcon.getDrawable(context)
                drawable.colorFilter = PorterDuffColorFilter(
                    themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary),
                    PorterDuff.Mode.SRC_IN
                )
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(btnPad, btnPad, btnPad, btnPad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themeColors.tertiary)
                }
                setOnTouchListener { v, event -> handleMicTouchEvent(v, event) }
            }
            inputBar.addView(micButton, LayoutHelper.createLinear(40, 40, gravity = Gravity.BOTTOM))
        }

        sendButton = ImageButton(context).apply {
            val drawable = MezonIcon.sendMessageIcon.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val sendBtnPad = LayoutHelper.dp(11f)
            setPadding(sendBtnPad, sendBtnPad, sendBtnPad, sendBtnPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.blurple)
            }
            visibility = View.GONE
            setOnClickListener { trySubmit() }
        }
        inputBar.addView(sendButton, LayoutHelper.createLinear(40, 40, gravity = Gravity.BOTTOM))

        outer.addView(inputBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        emojiHost = FrameLayout(context).apply { visibility = View.GONE }
        outer.addView(emojiHost, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0))

        composerField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                adjustMentionTrackersForChange(start, count, after)
                adjustHashtagTrackersForChange(start, count, after)
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pruneMentionTrackersAgainstText()
                pruneHashtagTrackersAgainstText()
                updateSendButtonState()
                checkSuggestionTrigger()
            }
        })

        composerField.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                deleteEmojiTokenAtCursor()
            } else false
        }

        composerField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                trySubmit()
                true
            } else false
        }

        setupPasteImageLongPress(context)
        updateSendButtonState()
        return outer
    }

    private fun showAttachmentPicker() {
        val activity = getParentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
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
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
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
                        if (grantResults[i] == android.content.pm.PackageManager.PERMISSION_GRANTED) imagesOk = true
                    Manifest.permission.READ_MEDIA_VIDEO ->
                        if (grantResults[i] == android.content.pm.PackageManager.PERMISSION_GRANTED) videoOk = true
                }
            }
            return imagesOk || videoOk
        }
        return grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
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
        cameraSourceAlert = alert
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
                launchDocumentPicker()
            }

            override fun onCameraRequested() {
                if (!canSelectMore()) {
                    android.widget.Toast.makeText(
                        ctx,
                        "Maximum ${AttachmentPickerItem.GALLERY_MAX_SELECTION} items",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                requestCameraPhoto()
            }

            override fun onDismissed() {
                if (cameraSourceAlert === alert) cameraSourceAlert = null
            }
        }
        alert.setDrawNavigationBar(true)
        alert.show()
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

    private fun launchDocumentPicker() {
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

    private fun requestCameraPhoto() {
        val activity = getParentActivity() ?: return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA_PERMISSION)
            return
        }
        launchCameraPhoto()
    }

    private fun launchCameraPhoto(): Boolean {
        val ctx = getContext() ?: return false
        val capture = CameraPhotoCapture.create(ctx)
        if (capture == null) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.camera_not_available))
            return false
        }
        pendingCameraCapture?.discard()
        pendingCameraCapture = capture
        try {
            startActivityForResult(capture.intent, REQUEST_CODE_TAKE_PHOTO)
            return true
        } catch (_: android.content.ActivityNotFoundException) {
            pendingCameraCapture = null
            capture.discard()
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.camera_not_available))
            return false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_TAKE_PHOTO) {
            val capture = pendingCameraCapture
            pendingCameraCapture = null
            if (resultCode != android.app.Activity.RESULT_OK) {
                capture?.discard()
                activeCameraReview?.dismiss()
                activeCameraReview = null
                return
            }
            val item = capture?.toAttachment()
            if (item == null) {
                capture?.discard()
                MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.camera_capture_failed))
                return
            }
            showCameraPhotoReview(capture, item)
            return
        }
        if (requestCode != REQUEST_CODE_PICK_FILE || resultCode != android.app.Activity.RESULT_OK) return
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

    private fun showCameraPhotoReview(capture: CameraPhotoCapture, item: AttachmentPickerItem) {
        val ctx = getContext() ?: run {
            capture.discard()
            return
        }
        lateinit var review: CameraPhotoReviewDialog
        review = CameraPhotoReviewDialog(
            context = ctx,
            capture = capture,
            onRetake = {
                launchCameraPhoto().also { launched ->
                    if (launched) capture.discard()
                }
            },
            onUsePhoto = {
                if (activeCameraReview === review) activeCameraReview = null
                if (pendingAttachments.none { it.id == item.id }) pendingAttachments.add(item)
                cameraSourceAlert?.dismissWithoutAnimation()
                updateAttachmentPreview()
                updateSendButtonState()
            },
            onCancelReview = {
                if (activeCameraReview === review) activeCameraReview = null
                capture.discard()
            }
        )
        val previousReview = activeCameraReview
        activeCameraReview = review
        review.show()
        previousReview?.dismiss()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                launchCameraPhoto()
            } else {
                getContext()?.let(CameraPermissionPrompt::show)
            }
            return
        }
        if (requestCode == REQUEST_CODE_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onVoiceLongPressFired()
            }
            return
        }
        if (requestCode == ChatAttachAlert.REQUEST_CODE_MEDIA_PERMISSION) {
            if (computeMediaPermissionGrantedFromResult(permissions, grantResults)) {
                mediaPermissionDeniedOnce = false
                openAttachAlert()
            } else {
                mediaPermissionDeniedOnce = true
                if (!shouldShowMediaPermissionRationale()) {
                    showOpenMediaSettingsDialog()
                }
            }
        }
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
                drawable.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                setImageDrawable(drawable)
                setBackgroundColor(0x80000000.toInt())
                setPadding(LayoutHelper.dp(2f), LayoutHelper.dp(2f), LayoutHelper.dp(2f), LayoutHelper.dp(2f))
                setOnClickListener {
                    pendingAttachments.removeAt(i)
                    updateAttachmentPreview()
                    updateSendButtonState()
                }
            }
            container.addView(
                closeBtn,
                FrameLayout.LayoutParams(LayoutHelper.dp(18f), LayoutHelper.dp(18f), Gravity.TOP or Gravity.END)
            )
            strip.addView(
                container,
                LinearLayout.LayoutParams(thumbSize, thumbSize).apply { rightMargin = margin }
            )
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
        fileContainer.addView(
            icon,
            LinearLayout.LayoutParams(iconSize, iconSize).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        val nameLabel = TextView(ctx).apply {
            text = item.filename
            setTextColor(themeColors.onSurface)
            textSize = 9f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(2f), LayoutHelper.dp(1f), LayoutHelper.dp(2f), 0)
        }
        fileContainer.addView(
            nameLabel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        container.addView(fileContainer, FrameLayout.LayoutParams(thumbSize, thumbSize))
    }

    private fun updateSendButtonState() {
        val hasText = composerField.text?.isNotBlank() == true
        val hasAttachments = pendingAttachments.isNotEmpty()
        val hasSticker = pendingStickerSend != null
        val showSend = hasText || hasAttachments || hasSticker
        sendButton?.visibility = if (showSend) View.VISIBLE else View.GONE
        if (fromTopicFlow && micButton != null) {
            if (voiceIsRecording) {
                micButton?.visibility = View.VISIBLE
            } else {
                micButton?.visibility = if (!showSend) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showEmojiView() {
        dismissPasteImagePopup()
        val host = emojiHost ?: return
        val ctx = getContext() ?: return
        if (emojiView == null) createEmojiView(ctx)
        val ev = emojiView ?: return
        ev.animate().cancel()
        ev.translationY = 0f
        ev.visibility = View.VISIBLE
        emojiViewVisible = true
        val panelHeight = SharedConfig.getEmojiPanelHeight()
        host.visibility = View.VISIBLE
        host.layoutParams = LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, panelHeight)
        host.requestLayout()
        (host.parent as? View)?.requestLayout()
        ev.translationY = panelHeight.toFloat()
        ev.animate()
            .translationY(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        AndroidUtilities.hideKeyboard(composerField)
        ev.onOpen()
        updateEmojiButtonIcon(true)
    }

    private fun openKeyboardFromEmoji() {
        updateEmojiButtonIcon(false)
        AndroidUtilities.cancelRunOnUIThread(openKeyboardRunnable)
        AndroidUtilities.cancelRunOnUIThread(showKeyboardFromEmojiRunnable)
        if (emojiViewVisible) {
            hideEmojiView(animated = false)
        }
        showKeyboardFromEmojiRunnable.run()
    }

    private fun hideEmojiView(animated: Boolean = true) {
        dismissPasteImagePopup()
        emojiViewVisible = false
        val host = emojiHost
        updateEmojiButtonIcon(false)
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
                    host?.visibility = View.GONE
                    host?.layoutParams = LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0)
                }
                .start()
        } else {
            ev.visibility = View.GONE
            ev.translationY = 0f
            host?.visibility = View.GONE
            host?.layoutParams = LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0)
        }
    }

    private fun createEmojiView(ctx: Context) {
        emojiView = EmojiView(ctx, themeColors).apply {
            init(emojiController)
            delegate = object : EmojiView.EmojiViewDelegate {
                override fun onEmojiSelected(emoji: EmojiItem) {
                    val editable = composerField.text ?: return
                    val cursor = composerField.selectionEnd.coerceAtLeast(0)
                    val cleanName = emoji.shortname.replace(":", "")
                    val token = ":$cleanName:"
                    val insertText = "$token "
                    emojiObjPicked[token] = emoji.id
                    editable.insert(cursor, insertText)
                    val spanStart = cursor
                    val spanEnd = cursor + token.length
                    editable.setSpan(EmojiTokenSpan(), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    editable.setSpan(ForegroundColorSpan(0xFF5A62F4.toInt()), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    editable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    composerField.setSelection(cursor + insertText.length)
                }

                override fun onStickerSelected(sticker: StickerItem, isAudio: Boolean) {
                    if (sticker.isForSale && sticker.src.isBlank()) return
                    val url = resolveStickerSourceUrl(sticker.id, sticker.src)
                    if (url.isBlank()) return
                    val filetype = if (isAudio) "audio/mpeg" else "image/gif"
                    pendingStickerSend = PendingStickerSend(url, filetype, sticker.id.toString())
                    hideEmojiView(animated = false)
                    updateSendButtonState()
                }

                override fun onGifSelected(gifUrl: String) {
                    if (gifUrl.isBlank()) return
                    pendingStickerSend = PendingStickerSend(gifUrl, "image/gif", null)
                    hideEmojiView(animated = false)
                    updateSendButtonState()
                }

                override fun onBackspace() {
                    composerField.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                }

                override fun onSearchFocusChanged(focused: Boolean) {}

                override fun onDismissRequested() {
                    hideEmojiView(animated = false)
                }
            }
        }
        emojiHost?.addView(
            emojiView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    private fun updateEmojiButtonIcon(showingEmoji: Boolean) {
        val btn = emojiButton ?: return
        val ctx = getContext() ?: return
        if (showingEmoji) {
            btn.setImageDrawable(
                MezonIcon.keyboardIcon.getDrawable(ctx).also {
                    it.colorFilter = PorterDuffColorFilter(
                        themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary),
                        PorterDuff.Mode.SRC_IN
                    )
                }
            )
        } else {
            btn.setImageResource(R.drawable.ic_emoji_icon)
            btn.setColorFilter(
                PorterDuffColorFilter(
                    themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary),
                    PorterDuff.Mode.SRC_IN
                )
            )
        }
    }

    private fun deleteEmojiTokenAtCursor(): Boolean {
        val editable = composerField.text ?: return false
        val sel = composerField.selectionStart
        if (sel <= 0 || sel != composerField.selectionEnd) return false
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

    private fun setupPasteImageLongPress(ctx: Context) {
        pasteInputGesture = GestureDetectorCompat(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onLongPress(e: MotionEvent) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (!imageClipboardCoordinator.clipboardLooksLikeImage(ctx, cm)) return
                showPasteImageTooltip(ctx)
            }
        })
        composerField.setOnTouchListener { _, ev ->
            pasteInputGesture?.onTouchEvent(ev)
            false
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
        popup.showAsDropDown(composerField, 0, offsetY)
    }

    private fun tryPasteImageFromClipboard(ctx: Context) {
        dismissPasteImagePopup()
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
                    MezonToast.show(this@CreateThreadFragment, ToastOverlay.ToastType.ERROR, getString(R.string.message_paste_failed))
                    return@withContext
                }
                pendingAttachments.add(item)
                updateAttachmentPreview()
                updateSendButtonState()
            }
        }
    }

    private fun checkSuggestionTrigger() {
        val text = composerField.text?.toString() ?: ""
        val cursor = composerField.selectionStart
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
        composerField.post {
            composerField.requestFocus()
        }
    }

    private fun scrollFormToComposerArea() {
        val sv = formScroll ?: return
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideSuggestionsPopup() {
        suggestionsPopup?.updateVisibility(false)
        suggestionsAdapter?.clear()
        currentTrigger = InputSuggestionsController.TriggerState.NONE
    }

    private fun buildMentionSuggestions(keyword: String): List<InputSuggestionItem> {
        val members = memberResolver.resolveMentionMembers(clanId, parentChannelId, parentChannelType())
        val pt = parentChannelType()
        val isChannelOrThread = pt != CHANNEL_TYPE_DM && pt != CHANNEL_TYPE_GROUP
        val roles = if (isChannelOrThread && clanId != 0L) {
            roleController.getRoles(clanId).also {
                if (it.isEmpty()) roleController.loadRolesForClan(clanId)
            }
        } else emptyList()
        val includeHere = pt != CHANNEL_TYPE_DM
        val ctx = InputSuggestionsController.MentionContext(
            members = members,
            roles = roles,
            includeHere = includeHere,
            includeRoles = isChannelOrThread
        )
        return InputSuggestionsController.buildMentionItems(keyword, ctx)
    }

    private fun buildHashtagSuggestions(keyword: String): List<InputSuggestionItem> {
        val pt = parentChannelType()
        val isDmLike = pt == CHANNEL_TYPE_DM || pt == CHANNEL_TYPE_GROUP
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
        val editable = composerField.text ?: return
        val trigger = currentTrigger
        if (trigger.mode == InputSuggestionsController.Mode.NONE || trigger.triggerPos < 0) return
        val triggerPos = trigger.triggerPos
        val replaceEnd = minOf(triggerPos + trigger.queryLen, editable.length)
        when (item) {
            is InputSuggestionItem.Here ->
                insertMentionToken(editable, triggerPos, replaceEnd, "@here", ChatController.ID_MENTION_HERE, "", themeColors.textLink)
            is InputSuggestionItem.Member -> {
                val member = item.member
                val displayName = member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
                insertMentionToken(
                    editable, triggerPos, replaceEnd, "@$displayName",
                    member.userId.toString(), "", themeColors.textLink
                )
            }
            is InputSuggestionItem.Role -> {
                val role = item.role
                val color = if (role.color != 0) role.color else themeColors.textRoleLink
                insertMentionToken(editable, triggerPos, replaceEnd, "@${role.title}", "", role.roleId.toString(), color)
            }
            is InputSuggestionItem.Channel -> insertHashtagToken(editable, triggerPos, replaceEnd, item.entity)
            is InputSuggestionItem.Emoji -> insertEmojiToken(editable, triggerPos, replaceEnd, item.item)
        }
        hideSuggestionsPopup()
    }

    private fun insertMentionToken(
        editable: Editable,
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
        editable.setSpan(ForegroundColorSpan(color), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        editable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        composerField.setSelection(start + insertText.length)
        mentionTrackers.add(MentionData(userId = userId, roleId = roleId, display = tokenText, startOffset = spanStart, endOffset = spanEnd))
    }

    private fun insertHashtagToken(
        editable: Editable,
        start: Int,
        end: Int,
        entity: ClanChannelEntity
    ) {
        val tokenText = "#${entity.channelLabel}"
        val insertText = "$tokenText "
        editable.replace(start, end, insertText)
        val spanStart = start
        val spanEnd = start + tokenText.length
        editable.setSpan(HashtagSpan(entity.channelId.toString(), themeColors.textLink), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        editable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        composerField.setSelection(start + insertText.length)
        hashtagTrackers.add(HashtagData(channelId = entity.channelId.toString(), startOffset = spanStart, endOffset = spanEnd, clanId = entity.clanId.toString()))
    }

    private fun insertEmojiToken(
        editable: Editable,
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
        editable.setSpan(EmojiTokenSpan(), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        editable.setSpan(ForegroundColorSpan(0xFF5A62F4.toInt()), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        editable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        composerField.setSelection(start + insertText.length)
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
        val ed = composerField.text ?: return
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
        val ed = composerField.text ?: return
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

    private fun buildEmojiMarkers(text: String, emojiSource: Map<String, String> = emojiObjPicked): List<com.mezon.mobile.util.EmojiMarker>? {
        if (emojiSource.isEmpty()) return null
        val markers = ArrayList<com.mezon.mobile.util.EmojiMarker>()
        for ((shortname, emojiId) in emojiSource) {
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf(shortname, searchFrom)
                if (idx < 0) break
                markers.add(com.mezon.mobile.util.EmojiMarker(emojiId, idx, idx + shortname.length))
                searchFrom = idx + shortname.length
            }
        }
        return markers.ifEmpty { null }
    }

    private fun sendComposerToThread(
        threadChannelId: Long,
        threadPrivate: Boolean
    ) {
        val rawInput = composerField.text?.toString() ?: ""
        val text = rawInput.trim()
        val ctx = getContext() ?: return
        if (text.isBlank() && pendingAttachments.isEmpty()) {
            pendingStickerSend?.let { st ->
                chatController.sendDirectAttachment(
                    threadChannelId, clanId, CHANNEL_TYPE_THREAD, threadPrivate,
                    st.url, st.filetype, st.filename, null
                )
                pendingStickerSend = null
                return
            }
            return
        }
        val mdResult = parseMarkdownAndStrip(text)
        val cleanedText = mdResult.cleanedText
        val mdMarkers = mdResult.markers.ifEmpty { null }
        val fromTrackers = mentionTrackers.mapNotNull { m ->
            val inTrimmed = mentionOffsetsForTrimmed(rawInput, text, m) ?: return@mapNotNull null
            val s = mdResult.adjustOffset(inTrimmed.startOffset)
            val e = mdResult.adjustOffset(inTrimmed.endOffset)
            if (s < 0 || e > cleanedText.length || s >= e) return@mapNotNull null
            if (cleanedText.substring(s, e) != inTrimmed.display) return@mapNotNull null
            MentionData(inTrimmed.userId, inTrimmed.roleId, inTrimmed.display, s, e)
        }
        val mergedMentions = mergeAtHereMentionsFromText(cleanedText, fromTrackers)
        val mentions = mergedMentions.ifEmpty { null }
        val hashtagsFromTrackers = hashtagTrackers.mapNotNull { h ->
            val inTrimmed = hashtagOffsetsForTrimmed(rawInput, h) ?: return@mapNotNull null
            val s = mdResult.adjustOffset(inTrimmed.startOffset)
            val e = mdResult.adjustOffset(inTrimmed.endOffset)
            if (s < 0 || e > cleanedText.length || s >= e) return@mapNotNull null
            if (s >= cleanedText.length || cleanedText[s] != '#') return@mapNotNull null
            HashtagData(inTrimmed.channelId, s, e, inTrimmed.clanId)
        }
        val hashtags = hashtagsFromTrackers.ifEmpty { null }
        val emojiMarkers = buildEmojiMarkers(cleanedText)
        if (pendingAttachments.isNotEmpty()) {
            chatController.sendMessageWithAttachments(
                threadChannelId, clanId, CHANNEL_TYPE_THREAD, threadPrivate, cleanedText,
                ArrayList(pendingAttachments),
                ctx.contentResolver,
                null,
                mentions,
                hashtags,
                emojiMarkers
            )
            pendingAttachments.clear()
            updateAttachmentPreview()
        } else {
            chatController.sendMessage(
                threadChannelId, clanId, CHANNEL_TYPE_THREAD, threadPrivate, cleanedText,
                null, mentions, emojiMarkers, mdMarkers, null, hashtags
            )
        }
        composerField.text?.clear()
        emojiObjPicked.clear()
        mentionTrackers.clear()
        hashtagTrackers.clear()
        updateSendButtonState()
        pendingStickerSend?.let { st ->
            chatController.sendDirectAttachment(
                threadChannelId, clanId, CHANNEL_TYPE_THREAD, threadPrivate,
                st.url, st.filetype, st.filename, null
            )
            pendingStickerSend = null
        }
    }

    private fun sendComposerToTopic(topicId: Long, payload: TopicComposerPayload? = null) {
        val captured = payload ?: captureTopicComposerPayload() ?: return
        val rawInput = captured.rawInput
        val text = rawInput.trim()
        val ctx = getContext() ?: return
        val parentType = parentChannelType()
        val parentPrivate = channelController.findChannelById(parentChannelId, clanId)?.isPrivate == true
        if (text.isBlank() && captured.attachments.isEmpty()) {
            captured.sticker?.let { st ->
                chatController.sendDirectAttachment(
                    parentChannelId,
                    clanId,
                    parentType,
                    parentPrivate,
                    st.url,
                    st.filetype,
                    st.filename,
                    null,
                    topicId = topicId
                )
            }
            if (payload == null) {
                pendingStickerSend = null
            }
            return
        }
        val mdResult = parseMarkdownAndStrip(text)
        val cleanedText = mdResult.cleanedText
        val mdMarkers = mdResult.markers.ifEmpty { null }
        val fromTrackers = captured.mentions.mapNotNull { m ->
            val inTrimmed = mentionOffsetsForTrimmed(rawInput, text, m) ?: return@mapNotNull null
            val s = mdResult.adjustOffset(inTrimmed.startOffset)
            val e = mdResult.adjustOffset(inTrimmed.endOffset)
            if (s < 0 || e > cleanedText.length || s >= e) return@mapNotNull null
            if (cleanedText.substring(s, e) != inTrimmed.display) return@mapNotNull null
            MentionData(inTrimmed.userId, inTrimmed.roleId, inTrimmed.display, s, e)
        }
        val mergedMentions = mergeAtHereMentionsFromText(cleanedText, fromTrackers)
        val mentions = mergedMentions.ifEmpty { null }
        val hashtagsFromTrackers = captured.hashtags.mapNotNull { h ->
            val inTrimmed = hashtagOffsetsForTrimmed(rawInput, h) ?: return@mapNotNull null
            val s = mdResult.adjustOffset(inTrimmed.startOffset)
            val e = mdResult.adjustOffset(inTrimmed.endOffset)
            if (s < 0 || e > cleanedText.length || s >= e) return@mapNotNull null
            if (s >= cleanedText.length || cleanedText[s] != '#') return@mapNotNull null
            HashtagData(inTrimmed.channelId, s, e, inTrimmed.clanId)
        }
        val hashtags = hashtagsFromTrackers.ifEmpty { null }
        val emojiMarkers = buildEmojiMarkers(cleanedText, captured.emojiEntries)
        if (captured.attachments.isNotEmpty()) {
            chatController.sendMessageWithAttachments(
                parentChannelId,
                clanId,
                parentType,
                parentPrivate,
                cleanedText,
                captured.attachments,
                ctx.contentResolver,
                null,
                mentions,
                hashtags,
                emojiMarkers,
                topicId = topicId
            )
        } else {
            chatController.sendMessage(
                parentChannelId,
                clanId,
                parentType,
                parentPrivate,
                cleanedText,
                null,
                mentions,
                emojiMarkers,
                mdMarkers,
                null,
                hashtags,
                topicId = topicId
            )
        }
        if (payload == null) {
            composerField.text?.clear()
            emojiObjPicked.clear()
            mentionTrackers.clear()
            hashtagTrackers.clear()
            pendingAttachments.clear()
            updateAttachmentPreview()
            updateSendButtonState()
            pendingStickerSend = null
        }
        captured.sticker?.let { st ->
            chatController.sendDirectAttachment(
                parentChannelId,
                clanId,
                parentType,
                parentPrivate,
                st.url,
                st.filetype,
                st.filename,
                null,
                topicId = topicId
            )
        }
    }

    private fun captureTopicComposerPayload(): TopicComposerPayload? {
        val rawInput = composerField.text?.toString() ?: ""
        val text = rawInput.trim()
        val hasAttachments = pendingAttachments.isNotEmpty()
        val hasSticker = pendingStickerSend != null
        if (text.isBlank() && !hasAttachments && !hasSticker) return null
        return TopicComposerPayload(
            rawInput = rawInput,
            attachments = ArrayList(pendingAttachments),
            sticker = pendingStickerSend,
            mentions = ArrayList(mentionTrackers),
            hashtags = ArrayList(hashtagTrackers),
            emojiEntries = LinkedHashMap(emojiObjPicked)
        )
    }

    private fun refreshNameError() {
        val raw = nameField?.text?.toString().orEmpty()
        val err = validateThreadName(raw)
        val shouldShow = err.isNotEmpty() && (submitAttempted || nameTouched || raw.trim().isNotEmpty())
        errorText?.text = err
        nameErrorRow?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun validateThreadName(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 64) return getString(R.string.create_thread_error_name)
        if (t.length <= 3) return getString(R.string.create_thread_error_too_short)
        val first = t.first()
        if (first == '_' || first == '-' || first.isWhitespace()) {
            return getString(R.string.create_thread_error_name)
        }
        for (ch in t) {
            val ok = ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch.isWhitespace()
            if (!ok) return getString(R.string.create_thread_error_name)
        }
        return ""
    }

    private fun canCreateThreadForCurrentFlow(): Boolean {
        if (fromTopicFlow && anonymousController.isAnonymous(clanId)) return false
        return if (fromTopicFlow) {
            true
        } else {
            permissionPolicy.canCreateThreadFromThreadList(parentChannelId, clanId)
        }
    }

    private fun trySubmit() {
        if (isSubmitting) return
        if (fromTopicFlow && anonymousController.isAnonymous(clanId)) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permissions_no_access))
            return
        }
        if (!canCreateThreadForCurrentFlow()) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permissions_no_access))
            return
        }
        submitAttempted = true
        if (!fromTopicFlow) {
            val nameErr = validateThreadName(nameField?.text?.toString().orEmpty())
            if (nameErr.isNotEmpty()) {
                errorText?.text = nameErr
                nameErrorRow?.visibility = View.VISIBLE
                MezonToast.show(this, ToastOverlay.ToastType.ERROR, nameErr)
                return
            }
        }
        val threadName = nameField?.text?.toString()?.trim().orEmpty()
        submitProgress?.visibility = View.VISIBLE
        isSubmitting = true
        sendButton?.isEnabled = false
        attachButton?.isEnabled = false

        fragmentScope.launch {
            try {
                if (hasSeedMessage && seedMessage == null) {
                    var loaded = chatController.getMessageById(parentChannelId, seedMessageId)
                    if (loaded == null) {
                        chatController.reloadChannelMessageIfMissing(parentChannelId, clanId, seedMessageId)
                        loaded = chatController.getMessageById(parentChannelId, seedMessageId)
                    }
                    seedMessage = loaded
                    withContext(mainDispatcher) { bindPreview(loaded) }
                }
                if (fromTopicFlow) {
                    val createdTopic = withContext(ioDispatcher) {
                        topicController.createTopic(
                            clanId = clanId,
                            parentChannelId = parentChannelId,
                            messageId = seedMessageId,
                            rootMessage = seedMessage
                        )
                    } ?: throw IllegalStateException("create topic failed")
                    val topicRootMessageId = createdTopic.messageId.takeIf { it != 0L } ?: seedMessageId
                    val composerPayload = captureTopicComposerPayload()
                    withContext(mainDispatcher) {
                        submitProgress?.visibility = View.GONE
                        isSubmitting = false
                        sendButton?.isEnabled = true
                        attachButton?.isEnabled = true
                        if (composerPayload != null) {
                            sendComposerToTopic(createdTopic.id, composerPayload)
                            composerField.text?.clear()
                            emojiObjPicked.clear()
                            mentionTrackers.clear()
                            hashtagTrackers.clear()
                            pendingAttachments.clear()
                            pendingStickerSend = null
                            updateAttachmentPreview()
                            updateSendButtonState()
                        }
                        presentFragment(
                            TopicFragment.newInstance(
                                topicId = createdTopic.id,
                                rootMessageId = topicRootMessageId,
                                clanId = clanId,
                                parentChannelId = parentChannelId,
                                channelType = parentChannelType(),
                                isChannelPrivate = channelController.findChannelById(parentChannelId, clanId)?.isPrivate == true
                            ),
                            removeLast = true
                        )
                    }
                    return@launch
                }
                val parentMeta = channelController.findChannelById(parentChannelId, clanId)
                val categoryId = parentMeta?.categoryId ?: 0L
                val categoryOrder = parentMeta?.categoryOrder ?: 0
                val isPrivate = privateSwitch?.isChecked() == true
                val desc = withContext(ioDispatcher) {
                    sessionManager.withAutoRefresh { session ->
                        mezonApi.createChannelDesc(
                            apiUrl = session.apiUrl,
                            token = session.token,
                            type = CHANNEL_TYPE_THREAD,
                            userIds = emptyList(),
                            clanId = clanId,
                            channelPrivate = if (isPrivate) 1 else 0,
                            channelLabel = threadName,
                            categoryId = categoryId,
                            parentId = parentChannelId
                        )
                    }
                }
                val entity = desc.toClanChannelEntity().copy(categoryOrder = categoryOrder)
                channelController.upsertChannel(entity)
                chatController.openChannel(
                    desc.channelId,
                    clanId,
                    CHANNEL_TYPE_THREAD,
                    entity.isPrivate,
                    parentChannelId
                )
                delay(120)
                val threadPrivate = entity.isPrivate
                val seed = seedMessage
                if (seed != null) {
                    val seedOk = withContext(ioDispatcher) {
                        chatController.sendThreadSeedMessage(
                            desc.channelId,
                            clanId,
                            CHANNEL_TYPE_THREAD,
                            threadPrivate,
                            seed
                        )
                    }
                    if (!seedOk) throw IllegalStateException("seed message send failed")
                    delay(80)
                }
                withContext(mainDispatcher) {
                    sendComposerToThread(desc.channelId, threadPrivate)
                }
                delay(80)
                withContext(mainDispatcher) {
                    submitProgress?.visibility = View.GONE
                    isSubmitting = false
                    sendButton?.isEnabled = true
                    attachButton?.isEnabled = true
                    val main = resolveHostMainActivity()
                    val newChannelId = desc.channelId
                    val newLabel = desc.channelLabel.ifBlank { threadName }
                    if (main == null) {
                        Log.w(LOG_TAG, "nav: MainActivity null after create channelId=$newChannelId")
                    } else {
                        Log.d(LOG_TAG, "nav: openChat replaceLast channelId=$newChannelId")
                        main.openChat(
                            newChannelId,
                            newLabel,
                            clanId,
                            CHANNEL_TYPE_THREAD,
                            replaceLastFragment = true
                        )
                        Log.d(LOG_TAG, "nav: openChat executed channelId=$newChannelId")
                    }
                }
            } catch (_: Exception) {
                withContext(mainDispatcher) {
                    submitProgress?.visibility = View.GONE
                    isSubmitting = false
                    sendButton?.isEnabled = true
                    attachButton?.isEnabled = true
                    MezonToast.show(
                        this@CreateThreadFragment,
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.create_thread_failed_title),
                        getString(R.string.create_thread_failed_message)
                    )
                }
            }
        }
    }

    override fun onBackPressed(): Boolean {
        if (voiceIsRecording) {
            mainHandler.removeCallbacks(voiceLongPressRunnable)
            cancelVoiceRecording(showToast = false)
            return true
        }
        if (emojiViewVisible) {
            hideEmojiView()
            return false
        }
        return super.onBackPressed()
    }

    private fun handleMicTouchEvent(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
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
        micButton?.translationX = clampedDx
    }

    private fun resetMicButtonTransform() {
        micButton?.animate()
            ?.translationX(0f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(150)
            ?.withEndAction { applyRecordingMicStyle(false) }
            ?.start()
    }

    private fun applyRecordingMicStyle(recording: Boolean) {
        val btn = micButton ?: return
        val bg = btn.background as? GradientDrawable ?: return
        if (recording) {
            bg.setColor(themeColors.blurple)
            val d = MezonIcon.microphoneIcon.getDrawable(btn.context)
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            btn.setImageDrawable(d)
        } else {
            bg.setColor(themeColors.tertiary)
            val d = MezonIcon.microphoneIcon.getDrawable(btn.context)
            d.colorFilter = PorterDuffColorFilter(
                themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_icon_secondary),
                PorterDuff.Mode.SRC_IN
            )
            btn.setImageDrawable(d)
        }
    }

    private fun onVoiceLongPressFired() {
        voiceLongPressFired = true
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
        getParentActivity()?.requestPermissions(
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
        micButton?.animate()?.scaleX(1.3f)?.scaleY(1.3f)?.setDuration(150)?.start()
        composerField.visibility = View.INVISIBLE
        emojiButton?.visibility = View.INVISIBLE
        voiceOverlay?.show()
        try { micButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
    }

    private fun finishVoiceRecording() {
        val recorder = voiceRecorder
        val ctx = getContext()
        if (!voiceIsRecording || recorder == null || ctx == null) {
            voiceRecorder = null
            teardownVoiceUi()
            return
        }
        val elapsed = recorder.elapsedMs()
        if (elapsed < VoiceRecorder.MIN_RECORD_MS) {
            val runnable = Runnable { completeVoiceRecording(recorder, ctx) }
            voiceCompleteRunnable = runnable
            mainHandler.postDelayed(runnable, VoiceRecorder.MIN_RECORD_MS - elapsed)
        } else {
            completeVoiceRecording(recorder, ctx)
        }
    }

    private fun completeVoiceRecording(recorder: VoiceRecorder, ctx: Context) {
        voiceCompleteRunnable = null
        voiceRecorder = null
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
        addVoiceRecordingAttachment(result.file, result.durationMs)
    }

    private fun cancelVoiceRecording(showToast: Boolean) {
        voiceCompleteRunnable?.let { mainHandler.removeCallbacks(it) }
        voiceCompleteRunnable = null
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
        composerField.visibility = View.VISIBLE
        emojiButton?.visibility = View.VISIBLE
        micButton?.animate()?.scaleX(1f)?.scaleY(1f)?.translationX(0f)?.setDuration(150)
            ?.withEndAction { applyRecordingMicStyle(false) }
            ?.start()
        updateSendButtonState()
    }

    private fun addVoiceRecordingAttachment(file: java.io.File, durationMs: Long) {
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
        pendingAttachments.add(item)
        updateAttachmentPreview()
        updateSendButtonState()
        if (fromTopicFlow && composerField.text.isNullOrBlank() && pendingStickerSend == null) {
            trySubmit()
        }
    }

    private fun showHoldToRecordHint() {
        MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.voice_record_hint))
    }

    override fun onFragmentDestroy() {
        waitingForKeyboardOpen = false
        mainHandler.removeCallbacks(voiceLongPressRunnable)
        voiceCompleteRunnable?.let { mainHandler.removeCallbacks(it) }
        voiceCompleteRunnable = null
        voiceRecorder?.cancel()
        voiceRecorder = null
        AndroidUtilities.cancelRunOnUIThread(openKeyboardRunnable)
        AndroidUtilities.cancelRunOnUIThread(showKeyboardFromEmojiRunnable)
        dismissPasteImagePopup()
        for (t in pendingAttachmentThumbTasks) ThumbnailCache.cancel(t)
        pendingAttachmentThumbTasks.clear()
        pendingAttachments.clear()
        mentionTrackers.clear()
        hashtagTrackers.clear()
        suggestionsPopup = null
        suggestionsAdapter = null
        emojiView = null
        emojiObjPicked.clear()
        formScroll = null
        super.onFragmentDestroy()
    }

    private inner class EmojiTokenSpan
}
