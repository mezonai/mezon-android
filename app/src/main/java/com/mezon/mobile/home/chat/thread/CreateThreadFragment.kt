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
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.chat.AttachmentPickerItem
import com.mezon.mobile.home.chat.ChatAttachAlert
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
import com.mezon.mobile.util.parseContentText
import com.mezon.mobile.util.parseMarkdownAndStrip
import com.mezon.mobile.util.resolveStickerSourceUrl
import com.mezon.mobile.util.restoreInputFromContent
import com.mezon.mezon.api.messageRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreateThreadFragment : BaseFragment() {

    companion object {
        private const val LOG_TAG = "CreateThread"
        private const val ARG_PARENT_CHANNEL_ID = "parentChannelId"
        private const val ARG_PARENT_LABEL = "parentLabel"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_SEED_MESSAGE_ID = "seedMessageId"
        private const val REQUEST_CODE_PICK_FILE = 1012
        private val RN_INPUT_BG = 0xFF1E1F22.toInt()
        private val RN_INPUT_BORDER = 0xFF43464B.toInt()
        private val RN_THREAD_LABEL = 0xFFF1F2F4.toInt()
        private val RN_MESSAGE_RULE = 0xFF676B73.toInt()

        fun newInstance(
            parentChannelId: Long,
            parentLabel: String,
            clanId: Long,
            seedMessageId: Long = 0L
        ): CreateThreadFragment = CreateThreadFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_PARENT_CHANNEL_ID, parentChannelId)
                putString(ARG_PARENT_LABEL, parentLabel)
                putLong(ARG_CLAN_ID, clanId)
                if (seedMessageId != 0L) putLong(ARG_SEED_MESSAGE_ID, seedMessageId)
            }
        }
    }

    private var parentChannelId = 0L
    private var parentLabel = ""
    private var clanId = 0L
    private var seedMessageId = 0L

    private lateinit var chatController: ChatController
    private lateinit var channelController: ChannelController
    private lateinit var sessionManager: SessionManager
    private lateinit var mezonApi: MezonApi
    private lateinit var memberResolver: MemberResolver
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher

    private var nameField: EditText? = null
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
    private lateinit var appScope: CoroutineScope

    private lateinit var composerField: EditText
    private var attachButton: ImageButton? = null
    private var emojiButton: ImageButton? = null
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
    private val pendingAttachmentThumbTasks = ArrayList<Runnable?>()
    private val mentionTrackers = mutableListOf<MentionData>()
    private val hashtagTrackers = mutableListOf<HashtagData>()
    private val emojiObjPicked = linkedMapOf<String, String>()
    private var currentTrigger: InputSuggestionsController.TriggerState = InputSuggestionsController.TriggerState.NONE
    private var mediaPermissionDeniedOnce = false
    private var pendingStickerSend: PendingStickerSend? = null

    private data class PendingStickerSend(val url: String, val filetype: String, val filename: String?)

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

    private val fromMessageFlow: Boolean get() = seedMessageId != 0L

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        parentChannelId = arguments?.getLong(ARG_PARENT_CHANNEL_ID) ?: 0L
        parentLabel = arguments?.getString(ARG_PARENT_LABEL) ?: ""
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        seedMessageId = arguments?.getLong(ARG_SEED_MESSAGE_ID) ?: 0L
        permissionPolicy.ensurePermissionChecker(
            listOf(PermissionPolicy.CLAN_OWNER, PermissionPolicy.MANAGE_THREAD, PermissionPolicy.MANAGE_CHANNEL),
            parentChannelId,
            clanId
        )
        return true
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        chatController = entryPoint.chatController()
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

        if (fromMessageFlow) {
            privateRow?.visibility = View.GONE
            titleSub?.visibility = View.VISIBLE
            titleSub?.text = parentLabel
            channelIconSlot?.visibility = View.GONE
            titleMain?.text = getString(R.string.create_thread_new_title)
            fragmentScope.launch {
                val msg = chatController.getMessageById(parentChannelId, seedMessageId)
                withContext(mainDispatcher) {
                    seedMessage = msg
                    bindPreview(msg)
                }
            }
        } else {
            titleSub?.visibility = View.GONE
            channelIconSlot?.visibility = View.VISIBLE
            titleMain?.text = parentLabel
            updateHeaderChannelIcon()
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
            setImageDrawable(MezonIcon.threadIcon.getDrawable(context))
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
        val label = TextView(context).apply {
            text = getString(R.string.create_thread_name_label)
            setTextColor(RN_THREAD_LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        col.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 5f))

        nameField = EditText(context).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(themeColors.textDisabled)
            hint = getString(R.string.create_thread_name_placeholder)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                setColor(RN_INPUT_BG)
                cornerRadius = LayoutHelper.dpf(6f)
                setStroke(LayoutHelper.dp(1), RN_INPUT_BORDER)
            }
            val p = LayoutHelper.dp(10)
            setPadding(p, 0, p, 0)
            minHeight = LayoutHelper.dp(40)
            gravity = Gravity.CENTER_VERTICAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
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

    private fun bindPreview(msg: MessageEntity?) {
        val box = previewBox ?: return
        val ctx = box.context
        box.removeAllViews()
        if (msg == null || !fromMessageFlow) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val topPad = LayoutHelper.dp(20)
        box.setPadding(0, topPad, 0, topPad)

        val lineColor = themeColors.createClanCameraGray
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
        val body = TextView(ctx).apply {
            text = parseContentText(msg.content).ifBlank { msg.content }
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        textCol.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 0f))
        inner.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        box.addView(inner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val bottomLine = View(ctx).apply {
            setBackgroundColor(lineColor)
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(1))
        }
        box.addView(bottomLine)
        applySeedToComposer(msg)
    }

    private fun parentChannelType(): Int =
        channelController.findChannelById(parentChannelId, clanId)?.type ?: CHANNEL_TYPE_CHANNEL

    private fun applySeedToComposer(msg: MessageEntity?) {
        if (msg == null || !fromMessageFlow) return
        val restored = restoreInputFromContent(msg.content)
        if (restored.rawText.isBlank()) return
        mentionTrackers.clear()
        hashtagTrackers.clear()
        emojiObjPicked.clear()
        mentionTrackers.addAll(restored.mentions)
        hashtagTrackers.addAll(restored.hashtags)
        emojiObjPicked.putAll(restored.emojis)
        composerField.setText(restored.rawText)
        applyComposerHighlightSpans()
        composerField.setSelection(composerField.text?.length ?: 0)
        updateSendButtonState()
        scrollFormToComposerArea()
    }

    private fun applyComposerHighlightSpans() {
        val editable = composerField.text ?: return
        val len = editable.length
        for (m in mentionTrackers) {
            if (m.startOffset < 0 || m.endOffset > len || m.startOffset >= m.endOffset) continue
            val color = if (m.roleId.isNotBlank()) themeColors.textRoleLink else themeColors.textLink
            editable.setSpan(
                ForegroundColorSpan(color),
                m.startOffset, m.endOffset,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            editable.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                m.startOffset, m.endOffset,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        for (h in hashtagTrackers) {
            if (h.startOffset < 0 || h.endOffset > len || h.startOffset >= h.endOffset) continue
            editable.setSpan(
                HashtagSpan(h.channelId, themeColors.textLink),
                h.startOffset, h.endOffset,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            editable.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                h.startOffset, h.endOffset,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        for ((shortname, _) in emojiObjPicked) {
            var searchFrom = 0
            while (searchFrom < editable.length) {
                val idx = editable.indexOf(shortname, searchFrom)
                if (idx < 0) break
                val end = idx + shortname.length
                editable.setSpan(EmojiTokenSpan(), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                editable.setSpan(ForegroundColorSpan(0xFF5A62F4.toInt()), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                editable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                searchFrom = end
            }
        }
    }

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

        outer.addView(View(context).apply { setBackgroundColor(RN_MESSAGE_RULE) }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1))

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
        val alert = ChatAttachAlert(ctx, mediaController, themeColors)
        alert.attachDelegate = object : ChatAttachAlert.ChatAttachAlertDelegate {
            override fun onAttachmentsSelected(items: List<AttachmentPickerItem>) {
                pendingAttachments.addAll(items)
                updateAttachmentPreview()
                updateSendButtonState()
            }

            override fun onFilesRequested() {
                launchDocumentPicker()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE_PICK_FILE || resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = getContext() ?: return
        val item = AttachmentPickerItem.fromDocumentUri(ctx, uri) ?: return
        val maxSize = if (item.mimeType.startsWith("image/")) {
            AttachmentPickerItem.IMAGE_MAX_FILE_SIZE
        } else {
            AttachmentPickerItem.MAX_FILE_SIZE
        }
        if (item.size > maxSize) {
            val limitText = FileUtils.formatFileSize(maxSize)
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.file_too_large, limitText))
            return
        }
        pendingAttachments.add(item)
        updateAttachmentPreview()
        updateSendButtonState()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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
        sendButton?.visibility = if (hasText || hasAttachments || hasSticker) View.VISIBLE else View.GONE
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
        val atHere = "(?<!\\w)@here(?!\\w)".toRegex()
        for (match in atHere.findAll(cleanedText)) {
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

    private fun buildEmojiMarkers(text: String): List<com.mezon.mobile.util.EmojiMarker>? {
        if (emojiObjPicked.isEmpty()) return null
        val markers = ArrayList<com.mezon.mobile.util.EmojiMarker>()
        for ((shortname, emojiId) in emojiObjPicked) {
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

    private fun buildSeedRefs(seed: MessageEntity?): List<com.mezon.mezon.api.MessageRef>? {
        val s = seed ?: return null
        return listOf(
            messageRef {
                messageId = 0L
                messageRefId = s.id
                refType = 0
                messageSenderId = s.senderId
                messageSenderUsername = s.senderName
                messageSenderAvatar = s.senderAvatar
                messageSenderDisplayName = s.senderName
                content = s.content
                hasAttachment = s.hasMedia || s.isFileAttachment
            }
        )
    }

    private fun sendComposerToThread(
        threadChannelId: Long,
        threadPrivate: Boolean,
        seed: MessageEntity?
    ) {
        val rawInput = composerField.text?.toString() ?: ""
        val text = rawInput.trim()
        val refs = buildSeedRefs(seed)
        val ctx = getContext() ?: return
        if (text.isBlank() && pendingAttachments.isEmpty()) {
            pendingStickerSend?.let { st ->
                chatController.sendDirectAttachment(
                    threadChannelId, clanId, CHANNEL_TYPE_THREAD, threadPrivate,
                    st.url, st.filetype, st.filename, refs
                )
                pendingStickerSend = null
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
                refs,
                mentions,
                hashtags,
                emojiMarkers
            )
            pendingAttachments.clear()
            updateAttachmentPreview()
        } else {
            chatController.sendMessage(
                threadChannelId, clanId, CHANNEL_TYPE_THREAD, threadPrivate, cleanedText,
                refs, mentions, emojiMarkers, mdMarkers, hashtags
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
                st.url, st.filetype, st.filename, refs
            )
            pendingStickerSend = null
        }
    }

    private fun refreshNameError() {
        val err = validateThreadName(nameField?.text?.toString().orEmpty())
        errorText?.text = err
        nameErrorRow?.visibility = if (err.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun validateThreadName(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 64) return getString(R.string.create_thread_error_name)
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
        return if (fromMessageFlow) {
            permissionPolicy.canCreateThreadFromMessage(parentChannelId, clanId)
        } else {
            permissionPolicy.canCreateThreadFromThreadList(parentChannelId, clanId)
        }
    }

    private fun trySubmit() {
        if (isSubmitting) return
        if (!canCreateThreadForCurrentFlow()) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permissions_no_access))
            return
        }
        val nameErr = validateThreadName(nameField?.text?.toString().orEmpty())
        if (nameErr.isNotEmpty()) {
            errorText?.text = nameErr
            nameErrorRow?.visibility = View.VISIBLE
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, nameErr)
            return
        }
        val threadName = nameField?.text?.toString()?.trim().orEmpty()
        submitProgress?.visibility = View.VISIBLE
        isSubmitting = true
        sendButton?.isEnabled = false
        attachButton?.isEnabled = false

        fragmentScope.launch {
            try {
                if (fromMessageFlow && seedMessageId != 0L && seedMessage == null) {
                    val loaded = chatController.getMessageById(parentChannelId, seedMessageId)
                    seedMessage = loaded
                    withContext(mainDispatcher) { bindPreview(loaded) }
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
                    val seedPlain = parseContentText(seed.content).trim()
                    if (seedPlain.isNotEmpty()) {
                        chatController.sendMessage(
                            desc.channelId,
                            clanId,
                            CHANNEL_TYPE_THREAD,
                            threadPrivate,
                            seedPlain
                        )
                        delay(80)
                    }
                }
                withContext(mainDispatcher) {
                    sendComposerToThread(desc.channelId, threadPrivate, seed)
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
        if (emojiViewVisible) {
            hideEmojiView()
            return false
        }
        return super.onBackPressed()
    }

    override fun onFragmentDestroy() {
        waitingForKeyboardOpen = false
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
