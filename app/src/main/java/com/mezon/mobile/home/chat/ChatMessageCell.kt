package com.mezon.mobile.home.chat

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.FileUtils
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.util.getEmojiDirectUrl
import com.mezon.mobile.util.getEmojiUrl
import com.mezon.mobile.util.MentionColors
import com.mezon.mobile.util.buildPlainTextWithHeadings
import com.mezon.mobile.util.formatEmbedRichText
import com.mezon.mobile.util.OgpData
import com.mezon.mobile.util.formatRelativeTime
import com.mezon.mobile.util.isRawMessage
import com.mezon.mobile.util.parseContentPreview
import com.mezon.mobile.util.isEmbedOrComponentsPayload
import com.mezon.mobile.util.messageHasExplicitTextBody
import com.mezon.mobile.util.parseContentText
import com.mezon.mobile.util.ShareContactData
import com.mezon.mobile.util.isShareContactMessage
import com.mezon.mobile.util.parseShareContactData
import com.mezon.mobile.util.parseContentToSpannable
import com.mezon.mobile.home.chat.poll.ChatPollBridge
import com.mezon.mobile.home.chat.poll.ParsedPoll
import com.mezon.mobile.home.chat.poll.PollLocalState
import com.mezon.mobile.home.chat.poll.PollMessageLayout
import com.mezon.mobile.home.chat.poll.parsePollContent
import com.mezon.mobile.home.call.CallLogMessageType
import com.mezon.mobile.home.call.ParsedCallLogMessage
import com.mezon.mobile.home.call.parseCallLogMessage
import com.mezon.mobile.home.clans.UserDisplayRole
import com.mezon.mobile.home.messages.EmbedButtonHit
import com.mezon.mobile.home.messages.EmbedInteractiveGeometry
import com.mezon.mobile.home.messages.EmbedMessageRenderer
import com.mezon.mobile.home.messages.EmbedSelectOptionSheet
import com.mezon.mobile.home.messages.EphemeralMessageUi
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.ui.cells.EditTextBoldCursor
import com.mezon.mobile.util.EmbedFormUtil
import com.mezon.mobile.util.EmbedInputComponentSpec
import com.mezon.mobile.util.EmbedRadioOptionSpec
import com.mezon.mobile.util.EmbedRadioSpec
import com.mezon.mobile.util.EmbedSelectOptionSpec
import com.mezon.mobile.util.EmbedSelectSpec
import com.mezon.mobile.util.parseOgpData
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.TextPaint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.max

class ChatMessageCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {
    private val embedMessage = EmbedMessageRenderer(this, { theme }).also {
        it.onAfterDraw = { scheduleEmbedInteractiveSync() }
    }
    private val embedInputSlots = mutableListOf<EmbedInputSlot>()
    private val embedSelectSlots = mutableListOf<EmbedSelectSlot>()
    private val embedRadioSlots = mutableListOf<EmbedRadioSlot>()
    private var hasEmbedContent = false
    private var embedInteractiveViewsVisible = false
    private var embedInteractiveAppliedHash = 0
    private var embedInteractiveAppliedMessageId = 0L
    private var embedInteractivePostedHash = 0
    private var embedInteractivePostedMessageId = 0L

    private val ephemeralIndicatorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(12f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }
    private val embedInputBackground = GradientDrawable()
    private val embedSelectBackground = GradientDrawable()
    private val embedSelectChevronDrawable: Drawable

    init {
        val chevronSz = LayoutHelper.dp(18)
        embedSelectChevronDrawable = MezonIcon.chevronDownSmallIcon.getDrawable(context).mutate()
        embedSelectChevronDrawable.setBounds(0, 0, chevronSz, chevronSz)
    }

    var hasMentionHighlight: Boolean = false
    private var highlightProgress = 0f

    var messageEntity: MessageEntity? = null
        private set
    private val linkInviteBlock = LinkInviteBlock(this, theme) { messageEntity?.id }
    private val pollLayoutHelper = PollMessageLayout(context)
    private var pollParsed: ParsedPoll? = null
    private val pollHitRect = RectF()
    private var pollCardDrawTopY = Float.NaN
    private var hasPollCard = false
    var pollBridge: ChatPollBridge? = null
    var shareContactOnlineResolver: ((Long) -> Boolean)? = null
    private val shareContactLayout = ShareContactCardLayout(context).also {
        it.invalidateCallback = { invalidate() }
    }
    private var shareContactParsed: ShareContactData? = null
    private var hasShareContactCard = false
    private val shareContactHitRect = RectF()
    private var shareContactCardDrawTopY = Float.NaN
    private var pressedShareContactAction = ShareContactHit.None
    var loadLinkInvitePreview: (suspend (Long) -> com.mezon.mobile.network.LinkInvitePreview?)?
        get() = linkInviteBlock.loadLinkInvitePreview
        set(v) {
            linkInviteBlock.loadLinkInvitePreview = v
        }
    var isCombined: Boolean = false
    var isInPinMode: Boolean = false

    private val avatarDrawable = AvatarDrawable()
    private var currentAvatarUrl: String? = null
    private var measuredCellHeight = LayoutHelper.dp(60)

    private var contentLayout: StaticLayout? = null
    private var contentLayoutLeft = 0
    private var contentLayoutTop = 0
    private var senderLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null
    private var durationLayout: StaticLayout? = null
    private var replyNameLayout: StaticLayout? = null
    private var replyTextLayout: StaticLayout? = null
    private var forwardLayout: StaticLayout? = null
    private var editedLayout: StaticLayout? = null
    private var fileNameLayout: StaticLayout? = null
    private var fileSizeLayout: StaticLayout? = null
    private var extraFileLayouts: List<Pair<StaticLayout?, StaticLayout?>> = emptyList()
    private var ephemeralLayout: StaticLayout? = null
    private val ephemeralDecorRect = RectF()
    private var hasEphemeralDecor = false
    private var ephemeralIconDrawable: Drawable? = null
    private var errorLayout: StaticLayout? = null
    private var hasReply = false
    private var parsedContent: String = ""
    private var timeText: String = ""
    var channelType: Int = 0
    var clanId: Long = 0L
    var isChannelPrivate: Boolean = false
    var displayRoleResolver: ((Long) -> UserDisplayRole?)? = null

    private var hasCallLogCard = false
    private var callLogParsed: ParsedCallLogMessage? = null
    private var callLogTitleLayout: StaticLayout? = null
    private var callLogDescLayout: StaticLayout? = null
    private var callLogShowCallback = false
    private val callLogCardRect = RectF()
    private val callLogCallbackRect = RectF()
    private var callLogCardHeight = 0
    private var callLogInnerWidth = 0
    private var callLogIconEnum = MezonIcon.callLogOutgoing
    private var callLogIconTint = 0
    private var callLogTitleIsRed = false
    private val callLogTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val callLogDescPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val callLogCallbackPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val callLogCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val callLogDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawPhotoImage = false
    private var drawFileAttachment = false
    private var drawAudioAttachment = false
    private var drawForwardHeader = false
    private var drawEdited = false
    private var drawEphemeral = false
    private var drawError = false
    private var drawSending = false
    private var fileIconDrawable: Drawable? = null
    private val fileRoundRect = RectF()
    private var fileRowWidth = 0

    private var audioTimeLayout: StaticLayout? = null
    private var audioDurationSec: Int = 0
    private var audioPillWidth: Int = 0
    private var audioBlockLeft = 0f
    private var audioBlockTop = 0f
    private var audioBlockRight = 0f
    private var audioBlockBottom = 0f
    private val audioRoundRect = RectF()
    private var audioIsPlaying = false
    private var audioIsLoading = false
    private var audioPositionMs: Long = 0
    private var audioDurationMs: Long = 0
    private var audioWaveTimeMs: Long = 0
    private var audioLastFrameTimeMs: Long = 0
    private val audioWavePath = Path()

    private val photoImage = ImageReceiver(this)
    private val extraPhotoImages = arrayOf(ImageReceiver(this), ImageReceiver(this), ImageReceiver(this))
    private val allReceivers = arrayOf(photoImage, extraPhotoImages[0], extraPhotoImages[1], extraPhotoImages[2])
    private val ogpImage = ImageReceiver(this)
    private val shimmerEffect = ShimmerEffect()
    private var photoWidth = 0
    private var photoHeight = 0
    private var mediaGridCount = 0
    private var mediaGridTotalH = 0
    private val playTriPath = Path()
    private val connectorPath = Path()
    private val tmpRect = RectF()
    private var ogpData: OgpData? = null
    private var ogpTitleLayout: StaticLayout? = null
    private var ogpDescLayout: StaticLayout? = null
    private var ogpImageW = 0
    private var ogpImageH = 0
    private var ogpBlockLeft = 0
    private var ogpBlockTop = 0
    private var ogpBlockRight = 0
    private var ogpBlockBottom = 0

    private var pressedOnInviteJoin = false

    private var pressedOnEmbed = false
    private var pressedEmbedButtonHit: EmbedButtonHit? = null

    private var cachedContentW = 0f
    private var cachedSenderW = 0f
    private var cachedTimeW = 0f
    private var cachedReplyNameW = 0f
    private var cachedReplyTextW = 0f
    private var cachedOgpTitleW = 0f
    private var cachedOgpDescW = 0f
    private var cachedForwardW = 0f
    private var cachedFileNameW = 0f
    private var cachedFileSizeW = 0f
    private var cachedEphW = 0f
    private var cachedInnerWidth = 0
    private val forwardArrowPath = Path()

    private var reactionGroups: List<ReactionGroup> = emptyList()
    private var reactionCountLayouts: Array<StaticLayout?> = emptyArray()
    private var reactionChipBounds: ArrayList<RectF> = ArrayList()
    private var reactionChipBoundsCount: Int = 0
    private var reactionIsMyFlags: BooleanArray = BooleanArray(0)
    private var reactionRowHeight = 0
    private val reactionChipRect = RectF()
    private var reactionEmojiBitmaps: Array<android.graphics.Bitmap?> = emptyArray()
    private var reactionEmojiCancellables: Array<MezonImageLoader.Cancellable?> = emptyArray()
    private var reactionBitmapLoadToken = 0
    private val reactionAddBounds = RectF()
    private var reactionAddIcon: android.graphics.drawable.Drawable? = null
    var currentUserId: Long = 0L

    private var currentContentPaint = theme.chatContentPaint
    private var currentTimePaint = theme.chatTimePaint
    private val senderPaint get() = theme.chatSenderPaint
    private val senderNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val roleIconBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private var senderRoleIconUrl: String? = null
    private var senderRoleIconBitmap: Bitmap? = null
    private var senderRoleIconCancellable: MezonImageLoader.Cancellable? = null
    private var cachedSenderNameW = 0f
    private var reserveSenderRoleIcon = false
    private var lastSenderDisplayRoleColor: Int? = null
    private var lastSenderDisplayRoleIconUrl: String? = null


    private var attachedToWindow = false
    private var pendingMessage: MessageEntity? = null
    private var avatarCancellable: MezonImageLoader.Cancellable? = null
    private var loggedRichContentForMessageId = Long.MIN_VALUE

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        photoImage.onAttachedToWindow()
        extraPhotoImages.forEach { it.onAttachedToWindow() }
        ogpImage.onAttachedToWindow()
        linkInviteBlock.onAttachedToWindow()
        embedMessage.onAttachedToWindow()
        pendingMessage?.let { msg ->
            pendingMessage = null
            update(0, msg)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        photoImage.onDetachedFromWindow()
        extraPhotoImages.forEach { it.onDetachedFromWindow() }
        ogpImage.onDetachedFromWindow()
        linkInviteBlock.onDetachedFromWindow()
        embedMessage.onDetachedFromWindow()
        avatarCancellable?.cancel()
        avatarCancellable = null
        senderRoleIconCancellable?.cancel()
        senderRoleIconCancellable = null
        reactionEmojiCancellables.forEach { it?.cancel() }
        cancelEmojiLoads()
    }

    private fun cancelEmojiLoads() {
        val text = contentLayout?.text as? android.text.Spanned ?: return
        val spans = text.getSpans(0, text.length, EmojiSpan::class.java)
        for (span in spans) span.cancelLoad()
    }

    fun clearState() {
        messageEntity = null
        pendingMessage = null
        highlightProgress = 0f
        contentLayout = null
        senderLayout = null
        timeLayout = null
        replyNameLayout = null
        replyTextLayout = null
        forwardLayout = null
        editedLayout = null
        fileNameLayout = null
        fileSizeLayout = null
        extraFileLayouts = emptyList()
        ephemeralLayout = null
        ephemeralDecorRect.setEmpty()
        hasEphemeralDecor = false
        ephemeralIconDrawable = null
        errorLayout = null
        ogpTitleLayout = null
        ogpDescLayout = null
        ogpData = null
        linkInviteBlock.clear()
        hasReply = false
        replyRefMessageId = 0L
        replySenderId = 0L
        replySenderAvatarUrl = null
        replyHasAttachment = false
        replyIsDeleted = false
        replyAvatarCancellable?.cancel()
        replyAvatarCancellable = null
        replyAvatarDrawable.setPhoto(null)
        replyAvatarDrawable.setDrawableByInfo(true)
        embedMessage.clear()
        hasEmbedContent = false
        hideEmbedInteractiveViews(force = true)
        clearSenderRoleIcon()
        reserveSenderRoleIcon = false
        cachedSenderNameW = 0f
        lastSenderDisplayRoleColor = null
        lastSenderDisplayRoleIconUrl = null
        drawPhotoImage = false
        drawFileAttachment = false
        drawForwardHeader = false
        drawEdited = false
        drawEphemeral = false
        drawError = false
        drawSending = false
        parsedContent = ""
        hasCallLogCard = false
        callLogParsed = null
        callLogTitleLayout = null
        callLogDescLayout = null
        callLogShowCallback = false
        callLogCardRect.setEmpty()
        callLogCallbackRect.setEmpty()
        callLogCardHeight = 0
        avatarCancellable?.cancel()
        avatarCancellable = null
        currentAvatarUrl = null
        avatarLoadStartTime = 0L
        avatarFallbackVisible = false
        avatarDrawable.setDrawableByInfo(true)
        for (i in videoThumbJobs.indices) {
            videoThumbJobs[i]?.cancel()
            videoThumbJobs[i] = null
        }
        slotIsVideo.fill(false)
        mediaGridCount = 0
        mediaGridTotalH = 0
        gridExtraCount = 0
        extraPhotoImages.forEach { it.recycle() }
        lastBoundId = 0L
        lastBoundContentHash = 0
        lastBoundCombined = false
        cachedMeasuredWidth = 0
        pollParsed = null
        hasPollCard = false
        pollHitRect.setEmpty()
        pollCardDrawTopY = Float.NaN
        shareContactParsed = null
        hasShareContactCard = false
        shareContactHitRect.setEmpty()
        shareContactCardDrawTopY = Float.NaN
        clearShareContactActionPress()
        shareContactLayout.clear()
    }

    private var lastBoundId = 0L
    private var lastBoundContentHash = 0
    private var lastBoundCombined = false

    fun update(mask: Int, newMsg: MessageEntity? = null): Boolean {
        val msg = newMsg ?: messageEntity ?: return false
        var rebuildLayout = false
        var needInvalidate = false

        if (mask == 0) {
            val contentHash = msg.content.hashCode() xor msg.timestampSeconds.hashCode() xor
                msg.code xor (if (msg.isForwarded) 1 else 0) xor
                msg.updateTimeSeconds.hashCode() xor (if (msg.hideEditted) 2 else 0) xor
                (pollBridge?.stateFingerprint(msg.id) ?: 0)
            if (msg.id == lastBoundId && contentHash == lastBoundContentHash && isCombined == lastBoundCombined) {
                return false
            }
            lastBoundId = msg.id
            lastBoundContentHash = contentHash
            lastBoundCombined = isCombined

            if (newMsg != null) messageEntity = newMsg
            parsedContent = parseContentText(msg.content)
            timeText = formatRelativeTime(msg.timestampSeconds)
            drawPhotoImage = msg.hasAnyMedia
            val isAudioAtt = msg.isAudioAttachment && !msg.hasAnyMedia
            drawAudioAttachment = isAudioAtt
            drawFileAttachment = msg.hasFileAttachments && !isAudioAtt
            audioIsPlaying = false
            audioIsLoading = false
            audioPositionMs = 0L
            audioDurationMs = (msg.attachmentDuration * 1000L).coerceAtLeast(0L)
            audioWaveTimeMs = 0L
            audioLastFrameTimeMs = 0L
            drawForwardHeader = msg.isForwarded
            drawEdited = msg.isEdited && !msg.hideEditted
            drawEphemeral = msg.isEphemeral
            drawError = msg.isError
            drawSending = msg.isSending
            hasReply = if (isInPinMode) false else parseReply(msg)
            if (isInPinMode) {
                drawForwardHeader = false
                drawEdited = false
                drawEphemeral = false
                drawError = false
                drawSending = false
            }
            updateColors(msg)
            if (drawPhotoImage) computePhotoSize(msg)
            buildLayouts(msg)
            if (!isCombined) {
                val isAnon = msg.senderId == ANONYMOUS_USER_ID
                val avatarUsername = if (isAnon) "Anonymous" else msg.senderUsername
                avatarDrawable.setInfo(msg.senderId, avatarUsername)
                if (isAnon) loadAnonymousAvatar() else loadAvatar(msg.senderAvatar)
            }
            if (drawPhotoImage) loadPhotoImage(msg) else clearPhotoReceivers()
            if (BuildConfig.DEBUG && drawPhotoImage) {
                Log.d(
                    TAG,
                    "update mask=0 id=${msg.id} sendState=${msg.sendState} drawSending=$drawSending " +
                        "drawPhotoImage=$drawPhotoImage mediaGridCount=$mediaGridCount"
                )
            }
            requestLayout()
            invalidate()
            return true
        }

        if ((mask and NotificationCenter.UPDATE_MASK_MESSAGE_TEXT) != 0) {
            val prevRaw = messageEntity?.content
            val newParsed = parseContentText(msg.content)
            if (newParsed != parsedContent || msg.content != prevRaw) {
                parsedContent = newParsed
                rebuildLayout = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_SEND_STATE) != 0) {
            val prevError = drawError
            drawSending = msg.isSending
            drawError = msg.isError
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "update SEND_STATE id=${msg.id} sendState=${msg.sendState} drawSending=$drawSending drawPhotoImage=$drawPhotoImage"
                )
            }
            if (drawError && !prevError) {
                rebuildLayout = true
            } else {
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_NAME) != 0) {
            val nameChanged = messageEntity?.senderName != msg.senderName
            if (nameChanged || senderDisplayRoleChanged(msg.senderId)) {
                rebuildLayout = true
            }
            val isAnon = msg.senderId == ANONYMOUS_USER_ID
            val avatarUsername = if (isAnon) "Anonymous" else msg.senderUsername
            avatarDrawable.setInfo(msg.senderId, avatarUsername)
            needInvalidate = true
        }

        if ((mask and NotificationCenter.UPDATE_MASK_AVATAR) != 0) {
            if (!isCombined && messageEntity?.senderAvatar != msg.senderAvatar) {
                val isAnon = msg.senderId == ANONYMOUS_USER_ID
                val avatarUsername = if (isAnon) "Anonymous" else msg.senderUsername
                avatarDrawable.setInfo(msg.senderId, avatarUsername)
                if (isAnon) loadAnonymousAvatar() else loadAvatar(msg.senderAvatar)
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_REACTIONS) != 0) {
            val m = newMsg ?: messageEntity ?: return false
            if (newMsg != null) messageEntity = newMsg
            Log.d(TAG, "REACTION update id=${m.id} extraAtt=${m.extraAttachmentsJson.length} drawFile=$drawFileAttachment hasFile=${m.hasFileAttachments}")
            val oldReactionH = reactionRowHeight
            buildReactionLayouts(m, cachedInnerWidth)
            if (oldReactionH != reactionRowHeight) {
                measuredCellHeight = computeHeight(m)
                requestLayout()
            }
            invalidate()
            return true
        }

        if (newMsg != null) messageEntity = newMsg

        if (rebuildLayout) {
            val m = messageEntity ?: return false
            timeText = formatRelativeTime(m.timestampSeconds)
            drawPhotoImage = m.hasAnyMedia
            val isAudioAtt = m.isAudioAttachment && !m.hasAnyMedia
            drawAudioAttachment = isAudioAtt
            drawFileAttachment = m.hasFileAttachments && !isAudioAtt
            drawForwardHeader = m.isForwarded
            drawEdited = m.isEdited && !m.hideEditted
            drawEphemeral = m.isEphemeral
            drawError = m.isError
            drawSending = m.isSending
            updateColors(m)
            buildLayouts(m)
            if (!drawPhotoImage) clearPhotoReceivers()
            requestLayout()
            invalidate()
            return true
        }
        if (needInvalidate) {
            invalidate()
        }
        return false
    }

    private fun clearPhotoReceivers() {
        if (mediaGridCount == 0 && !photoImage.hasImage()) return
        photoImage.recycle()
        for (r in extraPhotoImages) r.recycle()
        mediaGridCount = 0
        mediaGridTotalH = 0
        gridExtraCount = 0
    }

    private fun computePhotoSize(msg: MessageEntity, width: Int = currentWidth()) {
        val screenW = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val isStickerMsg = msg.messageType == MessageEntity.TYPE_GIF &&
            (msg.attachmentFiletype.equals("sticker", true) || msg.attachmentUrl.contains("/stickers/"))
        val rawMaxW = if (isStickerMsg) LayoutHelper.dp(160) else (screenW * 0.65f).toInt()
        val maxW = if (isInPinMode) rawMaxW.coerceAtMost(maxBubbleWidth(width)) else rawMaxW
        val maxH = maxW + LayoutHelper.dp(100)

        val firstMedia = msg.allImageAttachments.firstOrNull()
        var imgW = firstMedia?.width ?: msg.attachmentWidth
        var imgH = firstMedia?.height ?: msg.attachmentHeight
        if (imgW <= 0 || imgH <= 0) {
            if (isStickerMsg) {
                imgW = LayoutHelper.dp(120)
                imgH = LayoutHelper.dp(120)
            } else {
                imgW = LayoutHelper.dp(200)
                imgH = LayoutHelper.dp(150)
            }
        }

        val scale = imgW.toFloat() / maxW
        var w = (imgW / scale).toInt()
        var h = (imgH / scale).toInt()
        if (w == 0) w = LayoutHelper.dp(150)
        if (h == 0) h = LayoutHelper.dp(150)
        if (h > maxH) {
            val s2 = h.toFloat() / maxH
            h = maxH
            w = (w / s2).toInt()
        } else if (h < LayoutHelper.dp(120)) {
            h = LayoutHelper.dp(120)
            val hScale = imgH.toFloat() / h
            if (imgW / hScale < maxW) w = (imgW / hScale).toInt()
        }

        photoWidth = w.coerceAtLeast(LayoutHelper.dp(100))
        photoHeight = h.coerceAtLeast(LayoutHelper.dp(100))
    }

    private val videoThumbJobs = arrayOfNulls<Job>(4)
    private val slotIsVideo = BooleanArray(4)

    private fun bubbleDecodeProxySizePx(slotIndex: Int): Pair<Int, Int> {
        val gapPx = LayoutHelper.dp(2)
        val tw = photoWidth.coerceAtLeast(1)
        val th = photoHeight.coerceAtLeast(1)
        fun capped(cellW: Float, cellH: Float): Pair<Int, Int> {
            val uw = ceil(cellW.coerceAtLeast(1f) * 1.08f).toInt().coerceIn(64, 1200)
            val uh = ceil(cellH.coerceAtLeast(1f) * 1.08f).toInt().coerceIn(64, 1200)
            return uw to uh
        }
        return when (mediaGridCount) {
            1 -> capped(tw.toFloat(), th.toFloat())
            2 -> capped((tw - gapPx) / 2f, th.toFloat())
            3 -> {
                val leftW = (tw - gapPx) * 0.6f
                val rightW = (tw - gapPx).toFloat() - leftW
                val leftH = th.toFloat()
                val rightH = (leftH - gapPx) / 2f
                when (slotIndex) {
                    0 -> capped(leftW, leftH)
                    else -> capped(rightW, rightH)
                }
            }
            else -> {
                val cellW = (tw - gapPx) / 2f
                val cellH = (th - gapPx) / 2f
                capped(cellW, cellH)
            }
        }
    }

    private fun loadPhotoImage(msg: MessageEntity) {
        val allMedia = msg.allImageAttachments
        mediaGridCount = allMedia.size.coerceAtMost(4)
        slotIsVideo.fill(false)

        if (mediaGridCount == 0) return

        for (i in 0 until 4) {
            if (i < mediaGridCount) {
                val att = allMedia[i]
                val pair = bubbleDecodeProxySizePx(i)
                val pw = pair.first
                val ph = pair.second
                val isStickerAttachment = att.filetype.equals("sticker", ignoreCase = true) ||
                    att.url.contains("/stickers/", ignoreCase = true)
                val isAnimated = att.filetype.contains("gif", true) ||
                    att.url.contains("tenor.com", true)
                allReceivers[i].setRoundRadius(MEDIA_RADIUS.toInt())
                allReceivers[i].setRequestedSize(pw, ph)
                val isLocalUri = att.url.startsWith("content://") || att.url.startsWith("file://")
                val isVideo = att.filetype.startsWith("video/", true)
                slotIsVideo[i] = isVideo
                if (isLocalUri && isVideo) {
                    allReceivers[i].recycle()
                    loadLocalVideoThumbnail(att.url, i)
                } else if (isLocalUri) {
                    allReceivers[i].setLocalUri(android.net.Uri.parse(att.url), context)
                } else if (isAnimated || isStickerAttachment) {
                    allReceivers[i].setImage(att.url, att.thumb.ifEmpty { null }, context)
                } else if (isVideo) {
                    val thumb = att.thumb.ifEmpty { null }
                    Log.d(TAG, "video slot=$i hasThumb=${thumb != null} thumb='${att.thumb}' filetype='${att.filetype}' url=${att.url}")
                    if (thumb != null) {
                        allReceivers[i].setImage(createImgproxyUrl(thumb, pw, ph, "fill"), null, context)
                    } else {
                        allReceivers[i].recycle()
                    }
                } else {
                    val mainUrl = createImgproxyUrl(att.url, pw, ph, "fit")
                    val thumbUrl = att.thumb.ifEmpty { null }?.let { createImgproxyUrl(it, pw / 4, ph / 4, "fit") }
                    allReceivers[i].setImage(mainUrl, thumbUrl, context)
                }
            } else {
                allReceivers[i].recycle()
            }
        }

        gridExtraCount = if (allMedia.size > 4) allMedia.size - 4 else 0
        computeMediaGridHeight()
    }

    private fun computeMediaGridHeight() {
        val gap = LayoutHelper.dp(2)
        mediaGridTotalH = when (mediaGridCount) {
            1 -> photoHeight
            2 -> photoHeight
            3, 4 -> {
                val halfH = photoHeight / 2
                halfH + gap + halfH
            }
            else -> photoHeight
        }
    }

    private var spinnerAngle = 0f
    private val spinnerArcRect = RectF()

    private fun videoThumbCacheKey(videoUrl: String): String = "vthumb:$videoUrl"

    private fun loadLocalVideoThumbnail(localUrl: String, slotIndex: Int) {
        val pw = photoWidth.coerceAtLeast(1)
        val ph = photoHeight.coerceAtLeast(1)
        val loader = MezonImageLoader.getInstance(context)
        val key = videoThumbCacheKey(localUrl)
        loader.getBitmapFromMemory(key, pw, ph)?.let {
            allReceivers[slotIndex].setBitmapDirectly(it)
            invalidate()
            return
        }
        videoThumbJobs[slotIndex]?.cancel()
        videoThumbJobs[slotIndex] = VIDEO_THUMB_SCOPE.launch {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                val uri = android.net.Uri.parse(localUrl)
                kotlinx.coroutines.withTimeout(VIDEO_THUMB_TIMEOUT_MS) {
                    retriever.setDataSource(context, uri)
                }
                val frame = retriever.getFrameAtTime(
                    100_000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                Log.d(TAG, "loadLocalVideoThumbnail slot=$slotIndex frame=${if (frame != null) "${frame.width}x${frame.height}" else "NULL"} url=$localUrl")
                if (frame != null) {
                    loader.cacheBitmap(key, pw, ph, frame)
                    withContext(Dispatchers.Main) {
                        allReceivers[slotIndex].setBitmapDirectly(frame)
                        invalidate()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLocalVideoThumbnail FAILED slot=$slotIndex url=$localUrl", e)
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    private fun updateColors(msg: MessageEntity) {
        currentContentPaint = if (msg.code == MessageEntity.CODE_MESSAGE_BUZZ) theme.chatBuzzTextPaint
            else theme.chatContentPaint
        currentTimePaint = theme.chatTimePaint
    }

    private fun syncSenderNamePaintFromTheme() {
        val src = theme.chatSenderPaint
        senderNamePaint.textSize = src.textSize
        senderNamePaint.typeface = src.typeface
        senderNamePaint.isFakeBoldText = src.isFakeBoldText
        senderNamePaint.flags = src.flags
    }

    private fun showSenderDisplayRoleRow(senderId: Long): Boolean {
        return !isCombined && clanId != 0L &&
            (channelType == CHANNEL_TYPE_CHANNEL || channelType == CHANNEL_TYPE_THREAD) &&
            senderId != ANONYMOUS_USER_ID
    }

    private class SenderRoleAppearance(val color: Int, val iconUrl: String)

    private fun senderDisplayRoleAppearance(senderId: Long): SenderRoleAppearance? {
        if (!showSenderDisplayRoleRow(senderId)) return null
        val dr = displayRoleResolver?.invoke(senderId)
        return SenderRoleAppearance(
            color = if (dr != null && dr.color != 0) dr.color else theme.chatSenderPaint.color,
            iconUrl = dr?.iconUrl?.trim().orEmpty()
        )
    }

    private fun rememberSenderDisplayRoleAppearance(appearance: SenderRoleAppearance?) {
        if (appearance == null) {
            lastSenderDisplayRoleColor = null
            lastSenderDisplayRoleIconUrl = null
            return
        }
        lastSenderDisplayRoleColor = appearance.color
        lastSenderDisplayRoleIconUrl = appearance.iconUrl
    }

    private fun senderDisplayRoleChanged(senderId: Long): Boolean {
        val appearance = senderDisplayRoleAppearance(senderId)
        if (appearance == null) {
            return lastSenderDisplayRoleColor != null || lastSenderDisplayRoleIconUrl != null
        }
        return appearance.color != lastSenderDisplayRoleColor ||
            appearance.iconUrl != lastSenderDisplayRoleIconUrl
    }

    private fun clearSenderRoleIcon() {
        senderRoleIconCancellable?.cancel()
        senderRoleIconCancellable = null
        senderRoleIconUrl = null
        senderRoleIconBitmap = null
    }

    private fun loadSenderRoleIcon(url: String) {
        if (url.isBlank()) {
            clearSenderRoleIcon()
            return
        }
        if (url == senderRoleIconUrl && senderRoleIconBitmap != null) return
        senderRoleIconCancellable?.cancel()
        senderRoleIconCancellable = null
        senderRoleIconUrl = url
        senderRoleIconBitmap = null
        val sz = ROLE_ICON_SIZE
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(url, sz, sz)
        if (cached != null) {
            senderRoleIconBitmap = cached
            invalidate()
            return
        }
        senderRoleIconCancellable = loader.load(url, sz, sz, onSuccess = { bmp ->
            if (senderRoleIconUrl == url) {
                senderRoleIconBitmap = bmp
                invalidate()
            }
        }, onError = {
            if (senderRoleIconUrl == url) {
                senderRoleIconBitmap = null
                invalidate()
            }
        })
    }

    private fun drawSenderRoleIconAfterName(canvas: Canvas, contentLeft: Int, yOff: Float, sender: StaticLayout) {
        if (!reserveSenderRoleIcon || senderRoleIconBitmap == null) return
        val ix = contentLeft + cachedSenderNameW + ROLE_ICON_GAP
        val iy = yOff + (sender.height - ROLE_ICON_SIZE) / 2f
        tmpRect.set(ix.toFloat(), iy, (ix + ROLE_ICON_SIZE).toFloat(), (iy + ROLE_ICON_SIZE).toFloat())
        canvas.drawBitmap(senderRoleIconBitmap!!, null, tmpRect, roleIconBitmapPaint)
    }

    private fun currentWidth(): Int {
        if (measuredWidth > 0) return measuredWidth
        if (AndroidUtilities.displaySize.x > 0) return AndroidUtilities.displaySize.x
        return resources.displayMetrics.widthPixels
    }

    private fun maxBubbleWidth(): Int = maxBubbleWidth(currentWidth())

    private fun maxBubbleWidth(width: Int): Int {
        if (isInPinMode) return width - PIN_PAD_H * 2
        return width - PAD_H - AVATAR_SIZE - GAP_AVATAR - BUBBLE_RIGHT_INSET
    }

    private fun buildCallLogLayouts(msg: MessageEntity, textWidth: Int, parsed: ParsedCallLogMessage) {
        val isMe = msg.isMe
        val senderLabel = msg.senderName.ifBlank { " " }
        val titleStr = when (parsed.callLogType) {
            CallLogMessageType.TIMEOUTCALL ->
                if (isMe) context.getString(R.string.message_call_log_outgoing_call) else context.getString(R.string.message_call_log_missed)
            CallLogMessageType.REJECTCALL ->
                if (isMe) context.getString(R.string.message_call_log_receiver_rejected) else context.getString(R.string.message_call_log_you_rejected)
            CallLogMessageType.CANCELCALL ->
                if (isMe) context.getString(R.string.message_call_log_cancel) else context.getString(R.string.message_call_log_missed)
            CallLogMessageType.FINISHCALL ->
                if (isMe) context.getString(R.string.message_call_log_outgoing_call) else context.getString(R.string.message_call_log_incoming_call)
            CallLogMessageType.STARTCALL ->
                if (channelType == CHANNEL_TYPE_GROUP)
                    context.getString(R.string.message_call_log_start_group_call, senderLabel)
                else if (parsed.isVideo)
                    context.getString(R.string.message_call_log_start_video_call, senderLabel)
                else
                    context.getString(R.string.message_call_log_start_audio_call, senderLabel)
            else -> ""
        }
        callLogTitleIsRed = parsed.callLogType == CallLogMessageType.TIMEOUTCALL ||
            parsed.callLogType == CallLogMessageType.REJECTCALL ||
            parsed.callLogType == CallLogMessageType.CANCELCALL

        callLogTitlePaint.typeface = Typeface.DEFAULT_BOLD
        callLogTitlePaint.textSize = LayoutHelper.sp(16f)
        callLogTitlePaint.color = if (callLogTitleIsRed) theme.redStrong else theme.colorText

        callLogDescPaint.typeface = Typeface.DEFAULT
        callLogDescPaint.textSize = LayoutHelper.sp(14f)
        callLogDescPaint.color = theme.textDisabled

        callLogCallbackPaint.typeface = Typeface.DEFAULT_BOLD
        callLogCallbackPaint.textSize = LayoutHelper.sp(14f)
        callLogCallbackPaint.color = theme.textLink
        callLogCallbackPaint.isFakeBoldText = true

        callLogCardBgPaint.style = Paint.Style.FILL
        callLogCardBgPaint.color = theme.border
        callLogDividerPaint.style = Paint.Style.STROKE
        callLogDividerPaint.color = theme.secondaryWeight
        callLogDividerPaint.strokeWidth = LayoutHelper.dp(1f).toFloat()

        val innerContentW = (textWidth - 2 * (CALL_LOG_CARD_MARGIN_H + CALL_LOG_INNER_PAD)).coerceAtLeast(1)
        val titleW = innerContentW.coerceAtLeast(1)
        callLogTitleLayout = if (titleStr.isNotEmpty()) {
            StaticLayout.Builder.obtain(titleStr, 0, titleStr.length, callLogTitlePaint, titleW).build()
        } else null

        val descText = if (parsed.callLogType == CallLogMessageType.FINISHCALL) {
            parsed.tText.ifBlank {
                context.getString(if (parsed.isVideo) R.string.message_call_log_video_call else R.string.message_call_log_audio_call)
            }
        } else {
            context.getString(if (parsed.isVideo) R.string.message_call_log_video_call else R.string.message_call_log_audio_call)
        }
        val descW = (innerContentW - CALL_LOG_ICON_SLOT).coerceAtLeast(1)
        callLogDescLayout = StaticLayout.Builder.obtain(descText, 0, descText.length, callLogDescPaint, descW).build()

        callLogIconEnum = when (parsed.callLogType) {
            CallLogMessageType.TIMEOUTCALL ->
                if (isMe) MezonIcon.callLogOutgoing else MezonIcon.callLogMissed
            CallLogMessageType.REJECTCALL -> MezonIcon.callLogCancel
            CallLogMessageType.CANCELCALL ->
                if (isMe) MezonIcon.callLogCancel else MezonIcon.callLogMissed
            CallLogMessageType.FINISHCALL,
            CallLogMessageType.STARTCALL ->
                if (isMe) MezonIcon.callLogOutgoing else MezonIcon.callLogIncoming
            else -> MezonIcon.callLogOutgoing
        }
        callLogIconTint = when (parsed.callLogType) {
            CallLogMessageType.TIMEOUTCALL ->
                if (isMe) theme.textDisabled else theme.redStrong
            CallLogMessageType.REJECTCALL -> theme.redStrong
            CallLogMessageType.CANCELCALL -> theme.redStrong
            CallLogMessageType.FINISHCALL,
            CallLogMessageType.STARTCALL -> theme.textDisabled
            else -> theme.textDisabled
        }

        val blocked = delegate?.isDmPeerBlockedForCallLog() == true
        val inNoCallback =
            parsed.callLogType == CallLogMessageType.TIMEOUTCALL ||
                parsed.callLogType == CallLogMessageType.FINISHCALL
        callLogShowCallback = !blocked &&
            parsed.callLogType != CallLogMessageType.STARTCALL &&
            (!inNoCallback || !isMe)

        val titleH = callLogTitleLayout?.height ?: 0
        val descH = callLogDescLayout?.height ?: 0
        val fm = callLogCallbackPaint.fontMetrics
        val cbExtra = if (callLogShowCallback) {
            CALL_LOG_CALLBACK_TOP_PAD + (fm.descent - fm.ascent).toInt() + CALL_LOG_CALLBACK_BOTTOM_PAD
        } else 0
        var innerH = CALL_LOG_INNER_PAD * 2
        innerH += titleH
        if (titleH > 0) innerH += CALL_LOG_TITLE_DESC_GAP
        innerH += maxOf(CALL_LOG_ICON_SIZE, descH)
        if (callLogShowCallback) innerH += cbExtra
        callLogCardHeight = CALL_LOG_TOP_MARGIN + CALL_LOG_CARD_MARGIN_V * 2 + innerH
        callLogInnerWidth = textWidth
    }

    private fun drawCallLogCard(canvas: Canvas, contentLeft: Float, yOff: Float): Float {
        val w = callLogInnerWidth.toFloat()
        val outerTop = yOff + CALL_LOG_TOP_MARGIN
        val cardLeft = contentLeft
        val cardTop = outerTop
        val cardRight = cardLeft + w
        val cardBottom = cardTop + callLogCardHeight - CALL_LOG_TOP_MARGIN
        callLogCardRect.set(cardLeft, outerTop, cardRight, yOff + callLogCardHeight)
        canvas.drawRoundRect(cardLeft, cardTop, cardRight, cardBottom, CALL_LOG_CORNER, CALL_LOG_CORNER, callLogCardBgPaint)

        val innerLeft = cardLeft + CALL_LOG_CARD_MARGIN_H + CALL_LOG_INNER_PAD
        var iy = cardTop + CALL_LOG_CARD_MARGIN_V + CALL_LOG_INNER_PAD
        callLogTitleLayout?.let { tl ->
            canvas.save()
            canvas.translate(innerLeft, iy)
            tl.draw(canvas)
            canvas.restore()
            iy += tl.height + CALL_LOG_TITLE_DESC_GAP
        }
        val iconLeft = innerLeft
        val iconTop = iy + CALL_LOG_ICON_TOP_BIAS
        val d = callLogIconEnum.getDrawable(context).mutate()
        d.colorFilter = PorterDuffColorFilter(callLogIconTint, PorterDuff.Mode.SRC_IN)
        canvas.save()
        canvas.translate(iconLeft, iconTop)
        d.setBounds(0, 0, CALL_LOG_ICON_SIZE, CALL_LOG_ICON_SIZE)
        d.draw(canvas)
        canvas.restore()

        val descLeft = iconLeft + CALL_LOG_ICON_SLOT
        callLogDescLayout?.let { dl ->
            canvas.save()
            canvas.translate(descLeft, iy)
            dl.draw(canvas)
            canvas.restore()
        }
        val rowBottom = iy + maxOf(CALL_LOG_ICON_SIZE, callLogDescLayout?.height ?: 0)
        if (callLogShowCallback) {
            val divY = rowBottom + CALL_LOG_INNER_PAD
            canvas.drawLine(
                cardLeft + CALL_LOG_CARD_MARGIN_H,
                divY,
                cardRight - CALL_LOG_CARD_MARGIN_H,
                divY,
                callLogDividerPaint
            )
            val cb = context.getString(R.string.message_call_log_call_back).uppercase()
            val cbLayout = StaticLayout.Builder.obtain(cb, 0, cb.length, callLogCallbackPaint, (w - 2 * CALL_LOG_CARD_MARGIN_H).toInt().coerceAtLeast(1))
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .build()
            var cby = divY + CALL_LOG_CALLBACK_TOP_PAD
            canvas.save()
            canvas.translate(cardLeft + (w - cbLayout.width) / 2f, cby)
            cbLayout.draw(canvas)
            canvas.restore()
            callLogCallbackRect.set(cardLeft, divY, cardRight, cby + cbLayout.height + CALL_LOG_CALLBACK_BOTTOM_PAD)
        } else {
            callLogCallbackRect.setEmpty()
        }
        return yOff + callLogCardHeight
    }

    private fun buildLayouts(msg: MessageEntity) {
        buildLayouts(msg, currentWidth())
    }

    private fun buildLayouts(msg: MessageEntity, width: Int) {
        val bubbleMaxW = maxBubbleWidth(width)
        val bubbleWidth = if (drawPhotoImage) photoWidth else bubbleMaxW
        if (bubbleWidth <= 0) return

        val textWidth = if (drawPhotoImage) photoWidth else bubbleWidth

        val callLogParse = if (!msg.isPollMessage) parseCallLogMessage(msg.content) else null
        hasCallLogCard = callLogParse != null
        if (callLogParse != null) {
            callLogParsed = callLogParse
            buildCallLogLayouts(msg, textWidth, callLogParse)
        } else {
            callLogParsed = null
            callLogTitleLayout = null
            callLogDescLayout = null
            callLogShowCallback = false
            callLogCardHeight = 0
            callLogInnerWidth = 0
            callLogCallbackRect.setEmpty()
        }

        forwardLayout = if (drawForwardHeader) {
            val fwdText = FORWARD_TEXT
            StaticLayout.Builder.obtain(fwdText, 0, fwdText.length, FORWARD_PAINT, textWidth.coerceAtLeast(1))
                .setMaxLines(1)
                .build()
        } else null

        val editedText = EDITED_TEXT
        editedLayout = if (drawEdited) {
            StaticLayout.Builder.obtain(editedText, 0, editedText.length, currentTimePaint, LayoutHelper.dp(60).coerceAtLeast(1))
                .setMaxLines(1)
                .build()
        } else null

        val timeStr = if (drawEdited) "$editedText  $timeText" else timeText
        timeLayout = if (isCombined) null else {
            StaticLayout.Builder.obtain(timeStr, 0, timeStr.length, currentTimePaint, textWidth.coerceAtLeast(1))
                .setMaxLines(1)
                .build()
        }

        pollParsed = if (msg.isPollMessage) parsePollContent(msg.content) else null
        shareContactParsed = if (!hasCallLogCard && !msg.isPollMessage &&
            isShareContactMessage(msg.code, msg.content)
        ) {
            parseShareContactData(msg.content)
        } else {
            null
        }
        hasShareContactCard = shareContactParsed != null
        if (hasShareContactCard) {
            val scData = shareContactParsed!!
            val isOnline = shareContactOnlineResolver?.invoke(scData.userId) == true
            shareContactLayout.prepare(scData, theme, bubbleMaxW, isOnline)
        } else {
            shareContactLayout.clear()
        }
        val hasEmbedPayload = !hasCallLogCard && !hasShareContactCard && isEmbedOrComponentsPayload(msg.content)
        val hasText = !hasCallLogCard && !msg.isPollMessage && !hasShareContactCard &&
            parsedContent.isNotBlank() && parsedContent != "[file]" && parsedContent != "[embed]" &&
            parsedContent != "[contact]" &&
            (!hasEmbedPayload || messageHasExplicitTextBody(msg.content))
        contentLayout = if (hasText) {
            val content = msg.content
            val linkColor = theme.blurple
            val mentionColors = MentionColors(
                theme.textLink,
                theme.midnightBlue,
                theme.textRoleLink,
                theme.darkMossGreen
            )
            val charSeq: CharSequence = if (isRawMessage(content)) {
                buildPlainTextWithHeadings(parsedContent, theme)
            } else {
                parseContentToSpannable(content, linkColor, this, mentionColors, theme)
            }
            val layoutTargetW = textWidth.coerceAtLeast(1)
            val contentLayoutW =
                if ((charSeq as? Spanned)?.getSpans(0, charSeq.length, CodeFenceSpan::class.java)?.isNotEmpty() == true) {
                    (layoutTargetW - CodeFenceSpan.layoutExtraHorizontalShrink()).coerceAtLeast(1)
                } else {
                    layoutTargetW
                }
            val layout = StaticLayout.Builder.obtain(charSeq, 0, charSeq.length, currentContentPaint, contentLayoutW)
                .setLineSpacing(LayoutHelper.dpf(2f), 1f)
                .build()
            val spannedText = charSeq as? Spanned
            if (spannedText != null) {
                val codeFenceSpans = spannedText.getSpans(0, spannedText.length, CodeFenceSpan::class.java)
                for (span in codeFenceSpans) {
                    val spanStart = spannedText.getSpanStart(span)
                    val spanEnd = spannedText.getSpanEnd(span)
                    val firstContent = (spanStart until spanEnd).firstOrNull { spannedText[it] != '\n' } ?: spanStart
                    val lastContent = (spanEnd - 1 downTo spanStart).firstOrNull { spannedText[it] != '\n' }
                        ?: (spanEnd - 1).coerceAtLeast(spanStart)
                    val a = firstContent.coerceAtMost(lastContent)
                    val b = lastContent.coerceAtLeast(firstContent)
                    span.spanFirstLine = layout.getLineForOffset(a)
                    span.spanLastLine = layout.getLineForOffset(b)
                }
            }
            layout
        } else null

        reserveSenderRoleIcon = false
        cachedSenderNameW = 0f
        syncSenderNamePaintFromTheme()
        val showRoleRow = !isCombined && clanId != 0L &&
            (channelType == CHANNEL_TYPE_CHANNEL || channelType == CHANNEL_TYPE_THREAD)

        senderLayout = if (!isCombined) {
            val s = if (msg.senderId == ANONYMOUS_USER_ID) "Anonymous" else msg.senderName
            val senderMaxW = (bubbleMaxW * 0.60f).toInt().coerceAtLeast(1)
            if (showRoleRow) {
                val appearance = senderDisplayRoleAppearance(msg.senderId)
                if (appearance != null) {
                    senderNamePaint.color = appearance.color
                    reserveSenderRoleIcon = appearance.iconUrl.isNotEmpty()
                    if (reserveSenderRoleIcon) loadSenderRoleIcon(appearance.iconUrl) else clearSenderRoleIcon()
                    rememberSenderDisplayRoleAppearance(appearance)
                } else {
                    senderNamePaint.color = theme.chatSenderPaint.color
                    clearSenderRoleIcon()
                    rememberSenderDisplayRoleAppearance(null)
                }
                StaticLayout.Builder.obtain(s, 0, s.length, senderNamePaint, senderMaxW)
                    .setMaxLines(1)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build()
            } else {
                clearSenderRoleIcon()
                rememberSenderDisplayRoleAppearance(null)
                StaticLayout.Builder.obtain(s, 0, s.length, senderPaint, senderMaxW)
                    .setMaxLines(1)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build()
            }
        } else {
            clearSenderRoleIcon()
            rememberSenderDisplayRoleAppearance(null)
            null
        }

        buildReplyLayouts(textWidth)
        buildFileLayouts(msg, textWidth)
        buildAudioLayouts(msg)
        buildEphemeralLayout(msg, textWidth)
        buildErrorLayout(msg, textWidth)

        ogpData = if (!hasCallLogCard && msg.content.contains("\"mk\"") && msg.content.contains("lk_ogp")) {
            parseOgpData(msg.content)
        } else null
        if (ogpData != null) {
            val ogp = ogpData!!
            val ogpTextW = (textWidth * 0.9f).toInt().coerceAtLeast(1)
            val truncTitle = if (ogp.title.length > OGP_MAX_CHARS) ogp.title.substring(0, OGP_MAX_CHARS) else ogp.title
            ogpTitleLayout = StaticLayout.Builder.obtain(truncTitle, 0, truncTitle.length, currentContentPaint, ogpTextW)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            val truncDesc = if (ogp.description.length > OGP_MAX_CHARS) ogp.description.substring(0, OGP_MAX_CHARS) else ogp.description
            ogpDescLayout = StaticLayout.Builder.obtain(truncDesc, 0, truncDesc.length, theme.chatTimePaint, ogpTextW)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            ogpImageW = (textWidth * 0.6f).toInt().coerceAtLeast(LayoutHelper.dp(120))
            ogpImageH = (ogpImageW * 0.6f).toInt().coerceAtLeast(LayoutHelper.dp(80))
            ogpImage.setRoundRadius(OGP_RADIUS.toInt())
            val proxiedImg = createImgproxyUrl(ogp.image, ogpImageW, ogpImageH, "fill")
            ogpImage.setImage(proxiedImg, null, context)
        } else {
            ogpTitleLayout = null
            ogpDescLayout = null
            ogpImageW = 0
            ogpImageH = 0
            ogpImage.setImage(null, null, context)
        }

        if (hasEmbedPayload && !hasShareContactCard) {
            hasEmbedContent = embedMessage.setDataFromContent(msg.content)
            if (hasEmbedContent) {
                resetEmbedInteractiveSync()
                embedMessage.rebuildLayouts(textWidth, context)
            } else {
                hideEmbedInteractiveViews()
            }
        } else {
            if (hasEmbedContent) {
                embedMessage.clear()
                hideEmbedInteractiveViews()
            }
            hasEmbedContent = false
        }
        linkInviteBlock.build(msg, bubbleMaxW) {
            val m = messageEntity ?: return@build
            buildLayouts(m)
            requestLayout()
            invalidate()
        }

        durationLayout = if (msg.messageType == MessageEntity.TYPE_VIDEO && msg.attachmentDuration > 0) {
            val dur = formatDuration(msg.attachmentDuration)
            StaticLayout.Builder.obtain(dur, 0, dur.length, DURATION_PAINT, LayoutHelper.dp(100))
                .setMaxLines(1)
                .build()
        } else null

        val hasCodeFence = contentLayout?.text?.let { cs ->
            cs is android.text.Spanned && cs.getSpans(0, cs.length, com.mezon.mobile.home.chat.CodeFenceSpan::class.java).isNotEmpty()
        } == true

        cachedContentW = when {
            hasCallLogCard -> textWidth.toFloat()
            hasCodeFence -> textWidth.toFloat()
            else -> contentLayout?.let { maxLineWidth(it) } ?: 0f
        }
        cachedSenderW = if (senderLayout != null) {
            val nw = maxLineWidth(senderLayout!!)
            cachedSenderNameW = nw
            if (reserveSenderRoleIcon) nw + ROLE_ICON_GAP + ROLE_ICON_SIZE else nw
        } else {
            cachedSenderNameW = 0f
            0f
        }
        cachedTimeW = timeLayout?.let { it.getLineWidth(0) } ?: 0f
        cachedReplyNameW = replyNameLayout?.let { maxLineWidth(it) } ?: 0f
        cachedReplyTextW = replyTextLayout?.let { maxLineWidth(it) } ?: 0f
        cachedOgpTitleW = ogpTitleLayout?.let { maxLineWidth(it) } ?: 0f
        cachedOgpDescW = ogpDescLayout?.let { maxLineWidth(it) } ?: 0f
        cachedForwardW = forwardLayout?.let { maxLineWidth(it) + FORWARD_ICON_SIZE + FORWARD_ICON_GAP } ?: 0f
        cachedFileNameW = fileNameLayout?.let { maxLineWidth(it) } ?: 0f
        cachedFileSizeW = fileSizeLayout?.let { maxLineWidth(it) } ?: 0f
        cachedEphW = ephemeralLayout?.let {
            maxLineWidth(it) + EphemeralMessageUi.indicatorIconSize() + EphemeralMessageUi.INDICATOR_ICON_GAP
        } ?: 0f

        buildReactionLayouts(msg, textWidth)

        if (msg.isPollMessage && pollParsed != null) {
            val st = pollBridge?.getLocalState(msg.id) ?: PollLocalState()
            val forLayout = pollBridge?.pollForLayout(msg.id, pollParsed!!) ?: pollParsed!!
            pollLayoutHelper.prepare(forLayout, st, currentUserId, theme, bubbleMaxW)
        }

        val replyW = if (hasReply) cachedReplyNameW + cachedReplyTextW + REPLY_AVATAR_SIZE + REPLY_H_GAP * 2 else 0f
        val ogpW = if (ogpData != null) maxOf(cachedOgpTitleW, cachedOgpDescW, ogpImageW.toFloat()) else 0f
        val fileW = if (drawFileAttachment) fileRowWidth.toFloat() else 0f
        val audioW = if (drawAudioAttachment) audioPillWidth.toFloat() else 0f
        val embedW = if (hasEmbedContent) (bubbleMaxW).toFloat() else 0f
        val inviteW = if (linkInviteBlock.isVisible) linkInviteBlock.cachedWidth else 0f
        val shareContactW = if (hasShareContactCard) shareContactLayout.cardWidth.toFloat() else 0f
        cachedInnerWidth = if (drawPhotoImage) {
            photoWidth
        } else if (hasCodeFence) {
            bubbleMaxW
        } else {
            val allW = maxOf(cachedSenderW, cachedContentW, cachedTimeW, replyW, ogpW, cachedForwardW, fileW, audioW, cachedEphW, embedW, inviteW, shareContactW)
            var w = allW.toInt().coerceAtMost(bubbleMaxW)
            if (msg.isPollMessage && pollParsed != null) {
                w = maxOf(w, pollLayoutHelper.cardWidth)
            }
            if (hasShareContactCard) {
                w = maxOf(w, shareContactLayout.cardWidth)
            }
            w
        }

        if (drawEphemeral && !isInPinMode) {
            val bodyH = mainContentStackHeight()
            if (bodyH > 0) {
                val contentLeft = if (isInPinMode) PIN_PAD_H else PAD_H + AVATAR_SIZE + GAP_AVATAR
                val top = yOffsetTopOfMainContent(msg)
                ephemeralDecorRect.set(
                    contentLeft - EphemeralMessageUi.HORIZONTAL_INSET.toFloat(),
                    top,
                    contentLeft + cachedInnerWidth + EphemeralMessageUi.HORIZONTAL_INSET.toFloat(),
                    top + bodyH
                )
                hasEphemeralDecor = true
            } else {
                hasEphemeralDecor = false
            }
        } else {
            hasEphemeralDecor = false
        }

        measuredCellHeight = computeHeight(msg)
        updatedContent = true
        syncPollHitRect()
    }

    private fun verticalOffsetBeforePollCard(): Float {
        val topPad = if (isCombined) COMBINE_PAD_V else PAD_V
        var yOff = topPad.toFloat()
        if (hasReply) yOff += REPLY_ROW_HEIGHT + REPLY_V_GAP
        senderLayout?.let { yOff += it.height + GAP_V_INNER }
        forwardLayout?.let { yOff += it.height + GAP_V_INNER }
        return yOff
    }

    private fun pollHitTestOriginTop(): Float =
        if (!pollCardDrawTopY.isNaN()) pollCardDrawTopY else verticalOffsetBeforePollCard()

    private fun clearShareContactActionPress() {
        if (pressedShareContactAction == ShareContactHit.None) return
        pressedShareContactAction = ShareContactHit.None
        shareContactLayout.setPressedAction(ShareContactHit.None)
    }

    private fun shareContactContentLeft(): Int =
        if (isInPinMode) PIN_PAD_H else PAD_H + AVATAR_SIZE + GAP_AVATAR

    private fun syncShareContactHitRect(contentLeft: Int) {
        if (!hasShareContactCard || shareContactParsed == null || shareContactCardDrawTopY.isNaN()) {
            shareContactHitRect.setEmpty()
            return
        }
        shareContactHitRect.set(
            contentLeft.toFloat(),
            shareContactCardDrawTopY,
            contentLeft + shareContactLayout.cardWidth.toFloat(),
            shareContactCardDrawTopY + shareContactLayout.blockHeight
        )
    }

    private fun syncPollHitRect() {
        val msg = messageEntity
        if (msg == null || !msg.isPollMessage || pollParsed == null) {
            hasPollCard = false
            pollHitRect.setEmpty()
            pollCardDrawTopY = Float.NaN
            return
        }
        val contentLeft = if (isInPinMode) PIN_PAD_H else PAD_H + AVATAR_SIZE + GAP_AVATAR
        val drawTop = if (!pollCardDrawTopY.isNaN()) pollCardDrawTopY else verticalOffsetBeforePollCard()
        val xCard = contentLeft.toFloat()
        hasPollCard = true
        val extraTop = LayoutHelper.dp(10).toFloat()
        pollHitRect.set(
            xCard,
            drawTop - extraTop,
            xCard + pollLayoutHelper.cardWidth,
            drawTop + pollLayoutHelper.blockHeight
        )
    }

    private fun yOffsetTopOfMainContent(msg: MessageEntity): Float {
        val topPad = if (isCombined) COMBINE_PAD_V else PAD_V
        var y = topPad.toFloat()
        if (hasReply) y += REPLY_ROW_HEIGHT + REPLY_V_GAP
        senderLayout?.let { y += it.height + GAP_V_INNER }
        forwardLayout?.let { y += it.height + GAP_V_INNER }
        if (msg.isPollMessage && pollParsed != null) {
            y += pollLayoutHelper.blockHeight + GAP_V_INNER
        }
        if (hasShareContactCard) {
            y += shareContactLayout.blockHeight + GAP_V_INNER
        }
        return y
    }

    private fun mainContentStackHeight(): Int {
        var h = 0
        if (hasCallLogCard) {
            h += callLogCardHeight + GAP_V_INNER
        } else {
            contentLayout?.let {
                h += it.height
                h += if (ogpData != null || linkInviteBlock.isVisible) LINK_INVITE_V_MARGIN else GAP_V_INNER
            }
        }
        if (ogpData != null) {
            h += GAP_V_INNER
            ogpTitleLayout?.let { block -> h += block.height + GAP_V_INNER }
            ogpDescLayout?.let { block -> h += block.height + GAP_V_INNER }
            h += ogpImageH + GAP_V_INNER
        }
        if (linkInviteBlock.isVisible) {
            h += linkInviteBlock.blockHeight + LINK_INVITE_V_MARGIN
        }
        if (drawPhotoImage) {
            val imgH = if (mediaGridCount > 1) mediaGridTotalH else photoHeight
            h += imgH + GAP_V_INNER
        }
        if (drawFileAttachment) {
            fun fileCardH(nameL: StaticLayout?, sizeL: StaticLayout?): Int {
                val textH = (nameL?.height ?: 0) + (sizeL?.height ?: 0)
                val innerH = maxOf(FILE_ICON_SIZE, textH)
                return FILE_ROW_V_PAD * 2 + maxOf(innerH, FILE_ROW_MIN_HEIGHT - FILE_ROW_V_PAD * 2) + GAP_V_INNER
            }
            h += fileCardH(fileNameLayout, fileSizeLayout)
            for ((nl, sl) in extraFileLayouts) h += fileCardH(nl, sl)
        }
        if (drawAudioAttachment) {
            h += AUDIO_PILL_HEIGHT + GAP_V_INNER
        }
        if (hasEmbedContent) {
            h += embedMessage.computeHeight()
        }
        if (drawEphemeral) {
            ephemeralLayout?.let { h += it.height + GAP_V_INNER }
        }
        return h
    }

    private fun computeHeight(msg: MessageEntity): Int {
        val topPad = if (isCombined) COMBINE_PAD_V else PAD_V
        var h = topPad + PAD_BOTTOM

        if (hasReply) {
            h += REPLY_ROW_HEIGHT + REPLY_V_GAP
        }

        senderLayout?.let { h += it.height + GAP_V_INNER }
        forwardLayout?.let { h += it.height + GAP_V_INNER }

        if (msg.isPollMessage && pollParsed != null) {
            h += pollLayoutHelper.blockHeight + GAP_V_INNER
        }

        if (hasShareContactCard) {
            h += shareContactLayout.blockHeight + GAP_V_INNER
        }

        h += mainContentStackHeight()

        if (reactionGroups.isNotEmpty()) {
            h += REACTION_TOP_PAD + reactionRowHeight
        }

        if (drawError) {
            errorLayout?.let { h += it.height + GAP_V_INNER }
        }

        return h
    }

    private fun buildFileLayouts(msg: MessageEntity, textWidth: Int) {
        if (!drawFileAttachment) {
            fileNameLayout = null
            fileSizeLayout = null
            extraFileLayouts = emptyList()
            fileIconDrawable = null
            fileRowWidth = 0
            return
        }
        val cardInnerW = ((textWidth * 0.8f).toInt()).coerceAtLeast(FILE_ICON_SIZE + FILE_ICON_GAP + 1)
        val fileTextW = (cardInnerW - FILE_ROW_H_PAD * 2 - FILE_ICON_SIZE - FILE_ICON_GAP).coerceAtLeast(1)

        val files = msg.allFileAttachments
        val first = files.firstOrNull()
        val name = first?.filename?.ifEmpty { "File" } ?: msg.attachmentFilename.ifEmpty { "File" }
        fileNameLayout = StaticLayout.Builder.obtain(name, 0, name.length, theme.chatFileNamePaint, fileTextW)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val sizeBytes = first?.size?.toLong() ?: msg.attachmentSize.toLong()
        val sizeText = FileUtils.formatFileSize(sizeBytes)
        fileSizeLayout = StaticLayout.Builder.obtain(sizeText, 0, sizeText.length, currentTimePaint, fileTextW)
            .setMaxLines(1)
            .build()

        extraFileLayouts = if (files.size > 1) {
            files.drop(1).map { att ->
                val n = att.filename.ifEmpty { "File" }
                val nl = StaticLayout.Builder.obtain(n, 0, n.length, theme.chatFileNamePaint, fileTextW)
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
                val s = FileUtils.formatFileSize(att.size.toLong())
                val sl = StaticLayout.Builder.obtain(s, 0, s.length, currentTimePaint, fileTextW)
                    .setMaxLines(1).build()
                Pair<StaticLayout?, StaticLayout?>(nl, sl)
            }
        } else emptyList()

        fileRowWidth = cardInnerW
        fileIconDrawable = MezonIcon.fileIconNew.getDrawable(context)
    }

    private fun buildAudioLayouts(msg: MessageEntity) {
        if (!drawAudioAttachment) {
            audioTimeLayout = null
            audioDurationSec = 0
            audioPillWidth = 0
            return
        }
        audioDurationSec = msg.attachmentDuration
        val displayText = audioDisplayTime()
        audioTimeLayout = StaticLayout.Builder
            .obtain(displayText, 0, displayText.length, AUDIO_TIME_PAINT, LayoutHelper.dp(80))
            .setMaxLines(1)
            .build()
        audioPillWidth = AUDIO_PLAY_BTN_SIZE +
            AUDIO_CONTENT_H_PAD * 2 +
            AUDIO_WAVE_WIDTH +
            AUDIO_TIME_GAP +
            LayoutHelper.dp(38)
    }

    private fun audioDisplayTime(): String {
        val remainingMs = when {
            audioIsPlaying && audioDurationMs > 0 -> (audioDurationMs - audioPositionMs).coerceAtLeast(0L)
            audioPositionMs in 1 until audioDurationMs -> (audioDurationMs - audioPositionMs).coerceAtLeast(0L)
            audioDurationMs > 0 -> audioDurationMs
            audioDurationSec > 0 -> audioDurationSec * 1000L
            else -> 0L
        }
        if (remainingMs <= 0L && audioDurationSec <= 0) return "--:--"
        val totalSeconds = (remainingMs / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val sb = StringBuilder(6).append(minutes).append(':')
        if (seconds < 10) sb.append('0')
        sb.append(seconds)
        return sb.toString()
    }

    fun applyAudioPlayback(
        messageId: Long,
        isPlaying: Boolean,
        isLoading: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        val msg = messageEntity ?: return
        if (msg.id != messageId) {
            if (audioIsPlaying || audioIsLoading) {
                audioIsPlaying = false
                audioIsLoading = false
                audioPositionMs = 0L
                rebuildAudioTimeLayout()
                invalidate()
            }
            return
        }
        audioIsPlaying = isPlaying
        audioIsLoading = isLoading
        audioPositionMs = positionMs
        if (durationMs > 0) audioDurationMs = durationMs
        rebuildAudioTimeLayout()
        invalidate()
    }

    private fun rebuildAudioTimeLayout() {
        if (!drawAudioAttachment) return
        val displayText = audioDisplayTime()
        audioTimeLayout = StaticLayout.Builder
            .obtain(displayText, 0, displayText.length, AUDIO_TIME_PAINT, LayoutHelper.dp(80))
            .setMaxLines(1)
            .build()
    }

    private fun buildEphemeralLayout(msg: MessageEntity, textWidth: Int) {
        if (!drawEphemeral) {
            ephemeralLayout = null
            ephemeralIconDrawable = null
            return
        }
        ephemeralIndicatorPaint.color = theme.textDisabled
        val iconW = EphemeralMessageUi.indicatorIconSize()
        val gap = EphemeralMessageUi.INDICATOR_ICON_GAP
        val label = context.getString(R.string.ephemeral_only_visible_to_recipient)
        val textAvail = (textWidth - iconW - gap).coerceAtLeast(1)
        ephemeralLayout = EphemeralMessageUi.buildIndicatorLayout(label, textAvail, ephemeralIndicatorPaint)
        val d = ContextCompat.getDrawable(context, R.drawable.ic_ephemeral_icon_gray)?.mutate()
        d?.setTint(theme.textDisabled)
        ephemeralIconDrawable = d
    }

    private fun buildErrorLayout(msg: MessageEntity, textWidth: Int) {
        if (!drawError) {
            errorLayout = null
            return
        }
        val text = ERROR_TEXT
        errorLayout = StaticLayout.Builder.obtain(text, 0, text.length, ERROR_PAINT, textWidth.coerceAtLeast(1))
            .setMaxLines(1)
            .build()
    }

    private fun parseReply(msg: MessageEntity): Boolean {
        val content = msg.content
        if (!content.contains("\"references\"")) return false
        return try {
            val refIdMatch = REFERENCE_REF_ID_REGEX.find(content)
            val refIdStr = refIdMatch?.groupValues?.getOrNull(1) ?: ""
            replyRefMessageId = refIdStr.toLongOrNull() ?: 0L
            replyIsDeleted = refIdStr == "0" || (refIdStr.isNotEmpty() && replyRefMessageId == 0L)

            if (replyIsDeleted) {
                replySenderName = ""
                replySenderUsername = ""
                replyContent = ""
                replySenderId = 0L
                replyHasAttachment = false
                return true
            }

            val refClanNick = REFERENCE_SENDER_CLAN_NICK_REGEX.find(content)
                ?.groupValues?.getOrNull(1)
                ?.replace("\\\"", "\"")
                ?: ""
            val refDisplayName = REFERENCE_SENDER_REGEX.find(content)
                ?.groupValues?.getOrNull(1)
                ?.replace("\\\"", "\"")
                ?: ""
            val refContentMatch = REFERENCE_CONTENT_REGEX.find(content)
            replySenderUsername = REFERENCE_SENDER_USERNAME_REGEX.find(content)
                ?.groupValues?.getOrNull(1)
                ?.replace("\\\"", "\"")
                ?: ""
            replySenderName = refClanNick.ifBlank {
                refDisplayName.ifBlank { replySenderUsername.ifBlank { "Anonymous" } }
            }
            val rawRefContent = refContentMatch?.groupValues?.getOrNull(1)
                ?.replace("\\n", " ")
                ?.replace("\\\"", "\"")
                ?: ""
            replyContent = parseContentPreview(rawRefContent).take(80)

            val senderIdMatch = REFERENCE_SENDER_ID_REGEX.find(content)
            replySenderId = senderIdMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val isAnonymousReply = replySenderId == ANONYMOUS_USER_ID
            if (isAnonymousReply) {
                replySenderName = "Anonymous"
                replySenderUsername = "Anonymous"
            }
            replyHasAttachment = content.contains("\"has_attachment\":true")

            val avatarMatch = REFERENCE_AVATAR_REGEX.find(content)
            replySenderAvatarUrl = avatarMatch?.groupValues?.getOrNull(1)?.replace("\\/", "/")

            replyAvatarDrawable.setInfo(replySenderId, replySenderUsername.ifBlank { replySenderName })
            if (isAnonymousReply) loadReplyAnonymousAvatar() else loadReplyAvatar(replySenderAvatarUrl ?: "")
            replySenderName.isNotEmpty() || replyContent.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private var replySenderName = ""
    private var replySenderUsername = ""
    private var replyContent = ""
    private var replyRefMessageId = 0L
    private var replySenderId = 0L
    private var replySenderAvatarUrl: String? = null
    private var replyHasAttachment = false
    private var replyIsDeleted = false
    private var replyBlockLeft = 0f
    private var replyBlockTop = 0f
    private var replyBlockRight = 0f
    private var replyBlockBottom = 0f
    private val replyAvatarDrawable = AvatarDrawable()
    private var replyAvatarCancellable: MezonImageLoader.Cancellable? = null

    private fun buildReplyLayouts(textWidth: Int) {
        if (!hasReply) {
            replyNameLayout = null
            replyTextLayout = null
            return
        }
        val availW = (textWidth - REPLY_AVATAR_SIZE - REPLY_H_GAP).coerceAtLeast(1)

        if (replyIsDeleted) {
            replyNameLayout = null
            val deletedText = context.getString(com.mezon.mobile.R.string.message_reply_deleted)
            replyTextLayout = StaticLayout.Builder.obtain(deletedText, 0, deletedText.length, DELETED_REPLY_TEXT_PAINT, availW)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            return
        }

        val nameMaxW = (availW * 0.35f).toInt().coerceAtLeast(1)
        if (replySenderName.isNotEmpty()) {
            replyNameLayout = StaticLayout.Builder.obtain(replySenderName, 0, replySenderName.length, REPLY_NAME_PAINT, nameMaxW)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            replyNameLayout = null
        }

        val nameActualW = replyNameLayout?.let { maxLineWidth(it) }?.toInt() ?: 0
        val contentMaxW = (availW - nameActualW - REPLY_H_GAP).coerceAtLeast(1)
        val displayText = if (replyHasAttachment && replyContent.isBlank()) "tap to see attachment" else replyContent
        if (displayText.isNotEmpty()) {
            replyTextLayout = StaticLayout.Builder.obtain(displayText, 0, displayText.length, REPLY_CONTENT_PAINT, contentMaxW)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            replyTextLayout = null
        }
    }

    private var avatarLoadStartTime = 0L
    private var avatarFallbackVisible = false

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarCancellable?.cancel()
        avatarCancellable = null

        if (url.isNotEmpty()) {
            val proxyUrl = avatarImgproxyUrl(url, AVATAR_SIZE)
            val loader = MezonImageLoader.getInstance(context)
            val cached = loader.getBitmapFromMemory(proxyUrl, AVATAR_SIZE, AVATAR_SIZE)
            if (cached != null) {
                avatarDrawable.setPhoto(cached)
                avatarDrawable.setDrawableByInfo(true)
                avatarFallbackVisible = true
                return
            }

            avatarDrawable.setDrawableByInfo(false)
            avatarFallbackVisible = false
            avatarLoadStartTime = System.currentTimeMillis()

            avatarCancellable = loader.load(proxyUrl, AVATAR_SIZE, AVATAR_SIZE, onSuccess = { bmp ->
                avatarDrawable.setPhoto(bmp)
                avatarDrawable.setDrawableByInfo(true)
                avatarFallbackVisible = true
                invalidate()
            }, onError = {
                avatarDrawable.setDrawableByInfo(true)
                avatarFallbackVisible = true
                invalidate()
            })
        } else {
            avatarDrawable.setPhoto(null)
            avatarDrawable.setDrawableByInfo(true)
            avatarFallbackVisible = true
        }
    }

    private fun loadAnonymousAvatar() {
        currentAvatarUrl = ""
        avatarCancellable?.cancel()
        avatarCancellable = null

        val bgColor = theme.colorAvatarDefault
        val cached = anonymousAvatarBitmaps[bgColor]
            ?: createAnonymousAvatarBitmap(bgColor).also { anonymousAvatarBitmaps[bgColor] = it }
        avatarDrawable.setPhoto(cached)
        avatarDrawable.setDrawableByInfo(true)
        avatarFallbackVisible = true
    }

    private fun createAnonymousAvatarBitmap(bgColor: Int): Bitmap {
        val size = AVATAR_SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.color = bgColor
        c.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)
        val icon = ContextCompat.getDrawable(context, com.mezon.mobile.R.drawable.ic_anonymous_icon)?.mutate()
        if (icon != null) {
            icon.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            val iconSize = (size * 0.5f).toInt()
            val pad = (size - iconSize) / 2
            icon.setBounds(pad, pad, pad + iconSize, pad + iconSize)
            icon.draw(c)
        }
        return bmp
    }

    private fun loadReplyAvatar(url: String) {
        replyAvatarCancellable?.cancel()
        replyAvatarCancellable = null
        if (url.isEmpty()) {
            replyAvatarDrawable.setPhoto(null)
            replyAvatarDrawable.setDrawableByInfo(true)
            return
        }
        val proxyUrl = avatarImgproxyUrl(url, REPLY_AVATAR_SIZE)
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(proxyUrl, REPLY_AVATAR_SIZE, REPLY_AVATAR_SIZE)
        if (cached != null) {
            replyAvatarDrawable.setPhoto(cached)
            replyAvatarDrawable.setDrawableByInfo(true)
            return
        }
        replyAvatarDrawable.setDrawableByInfo(true)
        replyAvatarCancellable = loader.load(proxyUrl, REPLY_AVATAR_SIZE, REPLY_AVATAR_SIZE, onSuccess = { bmp ->
            replyAvatarDrawable.setPhoto(bmp)
            replyAvatarDrawable.setDrawableByInfo(true)
            invalidate()
        }, onError = {
            replyAvatarDrawable.setDrawableByInfo(true)
        })
    }

    private fun loadReplyAnonymousAvatar() {
        replyAvatarCancellable?.cancel()
        replyAvatarCancellable = null
        val bgColor = theme.colorAvatarDefault
        val cached = anonymousAvatarBitmaps[bgColor]
            ?: createAnonymousAvatarBitmap(bgColor).also { anonymousAvatarBitmaps[bgColor] = it }
        replyAvatarDrawable.setPhoto(cached)
        replyAvatarDrawable.setDrawableByInfo(true)
    }

    private fun checkAvatarFallbackTimeout() {
        if (!avatarFallbackVisible && !avatarDrawable.hasPhoto() && avatarLoadStartTime > 0) {
            if (System.currentTimeMillis() - avatarLoadStartTime > 3000L) {
                avatarDrawable.setDrawableByInfo(true)
                avatarFallbackVisible = true
            }
        }
    }

    private var cachedMeasuredWidth = 0

    override fun allowCaching(): Boolean = !embedInteractiveViewsVisible

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val msg = messageEntity
        if (msg != null && w > 0 && w != cachedMeasuredWidth) {
            cachedMeasuredWidth = w
            if (drawPhotoImage) computePhotoSize(msg, w)
            buildLayouts(msg, w)
        }
        setMeasuredDimension(w, measuredCellHeight)
    }

    var delegate: ChatMessageCellDelegate? = null

    interface ChatMessageCellDelegate {
        fun didClickMedia(cell: ChatMessageCell, msg: MessageEntity, attachmentIndex: Int) {}
        fun didClickFile(cell: ChatMessageCell, msg: MessageEntity) {}
        fun didTapAudio(cell: ChatMessageCell, msg: MessageEntity) {}
        fun didClickMention(cell: ChatMessageCell, userId: String?, roleId: String?) {}
        fun didClickHashtag(cell: ChatMessageCell, channelId: String?) {}
        fun didLongPress(cell: ChatMessageCell, msg: MessageEntity) {}
        fun didClickAvatar(cell: ChatMessageCell, msg: MessageEntity) {}
        fun didPressReply(cell: ChatMessageCell, replyMessageId: Long) {}
        fun didTapReaction(cell: ChatMessageCell, msg: MessageEntity, group: ReactionGroup) {}
        fun didLongPressReaction(cell: ChatMessageCell, msg: MessageEntity, group: ReactionGroup) {}
        fun didTapAddReaction(cell: ChatMessageCell, msg: MessageEntity) {}
        fun didClickInviteJoin(cell: ChatMessageCell, msg: MessageEntity, inviteId: Long) {}
        fun didClickEmbedComponentButton(cell: ChatMessageCell, msg: MessageEntity, buttonId: String) {}
        fun didChangeEmbedSelect(cell: ChatMessageCell, msg: MessageEntity, componentId: String, value: String) {}
        fun isDmPeerBlockedForCallLog(): Boolean = false
        fun didTapCallLogCallBack(cell: ChatMessageCell, msg: MessageEntity) {}
        fun didTapShareContactProfile(cell: ChatMessageCell, msg: MessageEntity, data: ShareContactData) {}
        fun didTapShareContactMessage(cell: ChatMessageCell, msg: MessageEntity, data: ShareContactData) {}
        fun didTapShareContactCall(cell: ChatMessageCell, msg: MessageEntity, data: ShareContactData) {}
    }

    private var pressedLink: ClickableSpan? = null
    private var pressedOnMedia = false
    private var pressedMediaIndex = 0
    private var pressedOnOgp = false
    private var pressedOnFile = false
    private var pressedOnAudio = false
    private var pressedOnAvatar = false
    private var pressedOnReply = false
    private var pressedOnCallLogCallback = false
    private var pressedReactionIndex = -1
    private var fileBlockLeft = 0f
    private var fileBlockTop = 0f
    private var fileBlockRight = 0f
    private var fileBlockBottom = 0f

    val isSticker: Boolean
        get() {
            val msg = messageEntity ?: return false
            return msg.messageType == MessageEntity.TYPE_GIF &&
                (msg.attachmentFiletype.equals("sticker", true) ||
                 msg.attachmentUrl.contains("/stickers/"))
        }

    private var startX = 0f
    private var startY = 0f
    private var longPressScheduled = false
    private var longPressHandled = false
    private val longPressRunnable = Runnable {
        longPressScheduled = false
        longPressHandled = true
        val msg = messageEntity ?: return@Runnable
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (pressedReactionIndex >= 0 && pressedReactionIndex < reactionGroups.size) {
            delegate?.didLongPressReaction(this, msg, reactionGroups[pressedReactionIndex])
            pressedReactionIndex = -1
        } else {
            delegate?.didLongPress(this, msg)
        }
        pressedLink = null
        pressedOnMedia = false
        pressedOnOgp = false
        pressedOnFile = false
        pressedOnAudio = false
        pressedOnAvatar = false
        pressedOnReply = false
        pressedOnCallLogCallback = false
        pressedOnInviteJoin = false
        pressedEmbedButtonHit = null
        embedMessage.setPressedButton(null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isInPinMode) return false
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressedLink = null
                pressedOnMedia = false
                pressedOnOgp = false
                pressedOnEmbed = false
                pressedOnFile = false
                pressedOnAudio = false
                pressedOnAvatar = false
                pressedOnReply = false
                pressedOnCallLogCallback = false
                pressedOnInviteJoin = false
                pressedReactionIndex = -1
                pressedEmbedButtonHit = null
                embedMessage.setPressedButton(null)
                clearShareContactActionPress()
                longPressHandled = false
                startX = x
                startY = y

                if (pollParsed != null && messageEntity?.isPollMessage == true) {
                    syncPollHitRect()
                    if (!pollHitRect.isEmpty && pollHitRect.contains(x, y)) {
                        return true
                    }
                }
                if (hasShareContactCard && shareContactParsed != null) {
                    if (!shareContactCardDrawTopY.isNaN()) {
                        val cl = shareContactContentLeft()
                        syncShareContactHitRect(cl)
                        if (!shareContactHitRect.isEmpty && shareContactHitRect.contains(x, y)) {
                            val localX = x - shareContactHitRect.left
                            val localY = y - shareContactHitRect.top
                            val actionHit = shareContactLayout.hitTest(localX, localY)
                            when (actionHit) {
                                ShareContactHit.Call, ShareContactHit.Message -> {
                                    pressedShareContactAction = actionHit
                                    shareContactLayout.setPressedAction(actionHit)
                                    return true
                                }
                                ShareContactHit.Profile -> {
                                    scheduleLongPress()
                                    return true
                                }
                                ShareContactHit.None -> Unit
                            }
                        }
                    }
                }

                if (!isCombined) {
                    var avatarTopPad = PAD_V
                    if (hasReply) avatarTopPad += REPLY_ROW_HEIGHT + REPLY_V_GAP
                    if (x >= PAD_H && x <= PAD_H + AVATAR_SIZE && y >= avatarTopPad && y <= avatarTopPad + AVATAR_SIZE) {
                        pressedOnAvatar = true
                        scheduleLongPress()
                        return true
                    }
                    val nameLeft = PAD_H + AVATAR_SIZE + GAP_AVATAR
                    val nameHeight = senderLayout?.height ?: 0
                    if (nameHeight > 0 && x >= nameLeft && y >= avatarTopPad && y <= avatarTopPad + nameHeight) {
                        pressedOnAvatar = true
                        scheduleLongPress()
                        return true
                    }
                }

                if (hasReply && !replyIsDeleted && replyRefMessageId != 0L &&
                    x >= replyBlockLeft && x <= replyBlockRight && y >= replyBlockTop && y <= replyBlockBottom) {
                    pressedOnReply = true
                    scheduleLongPress()
                    return true
                }

                if (drawFileAttachment && x >= fileBlockLeft && x <= fileBlockRight && y >= fileBlockTop && y <= fileBlockBottom) {
                    pressedOnFile = true
                    scheduleLongPress()
                    return true
                }
                if (drawAudioAttachment && x >= audioBlockLeft && x <= audioBlockRight && y >= audioBlockTop && y <= audioBlockBottom) {
                    pressedOnAudio = true
                    scheduleLongPress()
                    return true
                }
                if (linkInviteBlock.hitTestJoin(x, y)) {
                    pressedOnInviteJoin = true
                    return true
                }
                if (hasCallLogCard && callLogShowCallback && callLogCallbackRect.contains(x, y)) {
                    pressedOnCallLogCallback = true
                    return true
                }
                if (drawPhotoImage) {
                    if (mediaGridCount > 1) {
                        for (i in 0 until mediaGridCount.coerceAtMost(4)) {
                            val r = allReceivers[i]
                            val rx = r.getImageX(); val ry = r.getImageY()
                            if (x >= rx && x <= rx + r.getImageWidth() && y >= ry && y <= ry + r.getImageHeight()) {
                                pressedOnMedia = true
                                pressedMediaIndex = i
                                scheduleLongPress()
                                return true
                            }
                        }
                    } else {
                        val imgX = photoImage.getImageX()
                        val imgY = photoImage.getImageY()
                        if (x >= imgX && x <= imgX + photoImage.getImageWidth() && y >= imgY && y <= imgY + photoImage.getImageHeight()) {
                            pressedOnMedia = true
                            pressedMediaIndex = 0
                            scheduleLongPress()
                            return true
                        }
                    }
                }
                pressedEmbedButtonHit = null
                val data = ogpData
                if (data != null && x >= ogpBlockLeft && x <= ogpBlockRight && y >= ogpBlockTop && y <= ogpBlockBottom) {
                    pressedOnOgp = true
                    scheduleLongPress()
                    return true
                }
                if (hasEmbedContent) {
                    embedMessage.hitTestButton(x, y)?.let { hit ->
                        if (!hit.disabled) {
                            pressedEmbedButtonHit = hit
                            embedMessage.setPressedButton(hit.pressKey)
                            scheduleLongPress()
                            return true
                        }
                    }
                    if (embedMessage.containsTouch(x, y)) {
                        pressedOnEmbed = true
                        scheduleLongPress()
                        return true
                    }
                }
                if (reactionGroups.isNotEmpty()) {
                    val contentLeft = if (isCombined) PAD_H + AVATAR_SIZE + GAP_AVATAR else PAD_H + AVATAR_SIZE + GAP_AVATAR
                    val topPad = if (isCombined) COMBINE_PAD_V else PAD_V
                    var reacBaseY = topPad.toFloat()
                    if (hasReply) reacBaseY += REPLY_ROW_HEIGHT + REPLY_V_GAP
                    senderLayout?.let { reacBaseY += it.height + GAP_V_INNER }
                    forwardLayout?.let { reacBaseY += it.height + GAP_V_INNER }
                    if (messageEntity?.isPollMessage == true && pollParsed != null) {
                        reacBaseY += pollLayoutHelper.blockHeight + GAP_V_INNER
                    }
                    if (hasCallLogCard) {
                        reacBaseY += callLogCardHeight + GAP_V_INNER
                    } else {
                    contentLayout?.let {
                        reacBaseY += it.height + (if (ogpData != null || linkInviteBlock.isVisible) LINK_INVITE_V_MARGIN else GAP_V_INNER)
                    }
                    }
                    if (ogpData != null) {
                        reacBaseY += GAP_V_INNER
                        ogpTitleLayout?.let { reacBaseY += it.height + GAP_V_INNER }
                        ogpDescLayout?.let { reacBaseY += it.height + GAP_V_INNER }
                        reacBaseY += ogpImageH + GAP_V_INNER
                    }
                    if (linkInviteBlock.isVisible) {
                        reacBaseY += linkInviteBlock.blockHeight + LINK_INVITE_V_MARGIN
                    }
                    if (drawPhotoImage) {
                        val imgH = if (mediaGridCount > 1) mediaGridTotalH else photoHeight
                        reacBaseY += imgH + GAP_V_INNER
                    }
                    if (drawFileAttachment) {
                        fun fileCardH2(nl: StaticLayout?, sl: StaticLayout?): Int {
                            val th = (nl?.height ?: 0) + (sl?.height ?: 0)
                            val ih = maxOf(FILE_ICON_SIZE, th)
                            return FILE_ROW_V_PAD * 2 + maxOf(ih, FILE_ROW_MIN_HEIGHT - FILE_ROW_V_PAD * 2) + GAP_V_INNER
                        }
                        reacBaseY += fileCardH2(fileNameLayout, fileSizeLayout).toFloat()
                        for ((nl, sl) in extraFileLayouts) reacBaseY += fileCardH2(nl, sl).toFloat()
                    }
                    if (drawAudioAttachment) reacBaseY += AUDIO_PILL_HEIGHT + GAP_V_INNER
                    if (hasEmbedContent) reacBaseY += embedMessage.computeHeight()
                    if (drawEphemeral) ephemeralLayout?.let { reacBaseY += it.height + GAP_V_INNER }
                    reacBaseY += REACTION_TOP_PAD

                    if (reactionAddBounds.width() > 0) {
                        val ax = contentLeft + reactionAddBounds.left
                        val ay = reacBaseY + reactionAddBounds.top
                        if (x >= ax && x <= ax + reactionAddBounds.width() && y >= ay && y <= ay + REACTION_CHIP_H) {
                            pressedReactionIndex = -2
                            return true
                        }
                    }
                    for (i in 0 until reactionChipBoundsCount) {
                        val b = reactionChipBounds[i]
                        val bx = contentLeft + b.left
                        val by = reacBaseY + b.top
                        if (x >= bx && x <= bx + b.width() && y >= by && y <= by + b.height()) {
                            pressedReactionIndex = i
                            scheduleLongPress()
                            return true
                        }
                    }
                }
                val layout = contentLayout
                if (layout != null) {
                    val text = layout.text
                    if (text is Spannable) {
                        val relX = x - contentLayoutLeft
                        val relY = y - contentLayoutTop
                        if (relX >= 0 && relY >= 0 && relY < layout.height) {
                            val line = layout.getLineForVertical(relY.toInt())
                            if (line in 0 until layout.lineCount) {
                                val offset = layout.getOffsetForHorizontal(line, relX)
                                val spans = text.getSpans(offset, offset + 1, ClickableSpan::class.java)
                                if (spans.isNotEmpty()) {
                                    pressedLink = spans[0]
                                    scheduleLongPress()
                                    return true
                                }
                            }
                        }
                    }
                }
                scheduleLongPress()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedShareContactAction == ShareContactHit.Call ||
                    pressedShareContactAction == ShareContactHit.Message
                ) {
                    if (!shareContactCardDrawTopY.isNaN()) {
                        syncShareContactHitRect(shareContactContentLeft())
                        val stillPressed = if (!shareContactHitRect.isEmpty &&
                            shareContactHitRect.contains(x, y)
                        ) {
                            val localX = x - shareContactHitRect.left
                            val localY = y - shareContactHitRect.top
                            shareContactLayout.hitTest(localX, localY) == pressedShareContactAction
                        } else {
                            false
                        }
                        if (!stillPressed) clearShareContactActionPress()
                    }
                }
                if (longPressScheduled) {
                    val dx = x - startX
                    val dy = y - startY
                    val slop = AndroidUtilities.touchSlop.toFloat()
                    if (dx * dx + dy * dy > slop * slop) {
                        cancelScheduledLongPress()
                        if (pressedEmbedButtonHit != null) {
                            pressedEmbedButtonHit = null
                            embedMessage.setPressedButton(null)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelScheduledLongPress()
                if (longPressHandled) {
                    longPressHandled = false
                    pressedLink = null
                    pressedOnMedia = false
                    pressedOnOgp = false
                    pressedOnEmbed = false
                    pressedOnFile = false
                    pressedOnAudio = false
                    pressedOnAvatar = false
                    pressedOnReply = false
                    pressedOnCallLogCallback = false
                    pressedOnInviteJoin = false
                    pressedReactionIndex = -1
                    pressedEmbedButtonHit = null
                    embedMessage.setPressedButton(null)
                    clearShareContactActionPress()
                    return true
                }
                val pollMsg = messageEntity
                if (pollMsg != null && pollMsg.isPollMessage && pollParsed != null) {
                    syncPollHitRect()
                    if (!pollHitRect.isEmpty && pollHitRect.contains(x, y)) {
                        val tap = pollLayoutHelper.hitTest(x, y, pollHitRect.left, pollHitTestOriginTop())
                        if (tap != null) {
                            pollBridge?.onPollTap(pollMsg, pollParsed!!, tap)
                            return true
                        }
                    }
                }
                val scMsg = messageEntity
                val scData = shareContactParsed
                if (!longPressHandled && scMsg != null && scData != null && hasShareContactCard &&
                    !shareContactHitRect.isEmpty && shareContactHitRect.contains(x, y)
                ) {
                    val localX = x - shareContactHitRect.left
                    val localY = y - shareContactHitRect.top
                    val actionHit = if (pressedShareContactAction != ShareContactHit.None) {
                        pressedShareContactAction
                    } else {
                        shareContactLayout.hitTest(localX, localY)
                    }
                    clearShareContactActionPress()
                    when (actionHit) {
                        ShareContactHit.Profile -> delegate?.didTapShareContactProfile(this, scMsg, scData)
                        ShareContactHit.Message -> delegate?.didTapShareContactMessage(this, scMsg, scData)
                        ShareContactHit.Call -> delegate?.didTapShareContactCall(this, scMsg, scData)
                        ShareContactHit.None -> Unit
                    }
                    return true
                }
                clearShareContactActionPress()
                if (pressedReactionIndex >= 0) {
                    val idx = pressedReactionIndex
                    pressedReactionIndex = -1
                    val msg = messageEntity
                    if (msg != null && idx < reactionGroups.size) {
                        delegate?.didTapReaction(this, msg, reactionGroups[idx])
                    }
                    return true
                }
                if (pressedReactionIndex == -2) {
                    pressedReactionIndex = -1
                    val msg = messageEntity
                    if (msg != null) delegate?.didTapAddReaction(this, msg)
                    return true
                }
                if (pressedOnCallLogCallback) {
                    pressedOnCallLogCallback = false
                    val msg = messageEntity
                    if (msg != null) delegate?.didTapCallLogCallBack(this, msg)
                    return true
                }
                if (pressedOnAvatar) {
                    pressedOnAvatar = false
                    val msg = messageEntity
                    if (msg != null) delegate?.didClickAvatar(this, msg)
                    return true
                }
                if (pressedOnReply) {
                    pressedOnReply = false
                    if (replyRefMessageId != 0L) delegate?.didPressReply(this, replyRefMessageId)
                    return true
                }
                if (pressedOnFile) {
                    pressedOnFile = false
                    val msg = messageEntity
                    if (msg != null) delegate?.didClickFile(this, msg)
                    return true
                }
                if (pressedOnAudio) {
                    pressedOnAudio = false
                    val msg = messageEntity
                    if (msg != null) delegate?.didTapAudio(this, msg)
                    return true
                }
                if (pressedOnMedia) {
                    pressedOnMedia = false
                    val msg = messageEntity
                    if (msg != null) delegate?.didClickMedia(this, msg, pressedMediaIndex)
                    return true
                }
                if (pressedOnOgp) {
                    pressedOnOgp = false
                    ogpData?.let { onLinkClicked(it.url) }
                    return true
                }
                pressedEmbedButtonHit?.let { hit ->
                    pressedEmbedButtonHit = null
                    embedMessage.setPressedButton(null)
                    if (!hit.disabled) {
                        val url = hit.url
                        if (!url.isNullOrEmpty()) onLinkClicked(url)
                        else {
                            val msg = messageEntity
                            if (msg != null) delegate?.didClickEmbedComponentButton(this, msg, hit.buttonId)
                        }
                    }
                    return true
                }
                if (pressedOnEmbed) {
                    pressedOnEmbed = false
                    if (hasEmbedContent) embedMessage.hitTestEmbedCardLink(x, y)?.let { onLinkClicked(it) }
                    return true
                }
                if (pressedOnInviteJoin) {
                    pressedOnInviteJoin = false
                    val msg = messageEntity
                    if (msg != null && linkInviteBlock.activeIdForJoin != 0L) {
                        delegate?.didClickInviteJoin(this, msg, linkInviteBlock.activeIdForJoin)
                    }
                    return true
                }
                pressedLink?.let { span ->
                    pressedLink = null
                    span.onClick(this)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelScheduledLongPress()
                longPressHandled = false
                pressedLink = null
                pressedOnMedia = false
                pressedOnOgp = false
                pressedOnEmbed = false
                pressedOnFile = false
                pressedOnAudio = false
                pressedOnAvatar = false
                pressedOnReply = false
                pressedOnCallLogCallback = false
                pressedOnInviteJoin = false
                pressedEmbedButtonHit = null
                embedMessage.setPressedButton(null)
                clearShareContactActionPress()
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scheduleLongPress() {
        longPressScheduled = true
        handler?.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelScheduledLongPress() {
        if (longPressScheduled) {
            longPressScheduled = false
            handler?.removeCallbacks(longPressRunnable)
        }
    }

    fun getMediaBitmap(index: Int): android.graphics.Bitmap? {
        return allReceivers.getOrNull(index)?.getBitmap()
    }

    fun onMentionClicked(userId: String?, roleId: String?) {
        delegate?.didClickMention(this, userId, roleId)
    }

    fun onHashtagClicked(channelId: String?) {
        delegate?.didClickHashtag(this, channelId)
    }

    fun onLinkClicked(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private var visibleOnScreen = true

    override fun invalidate() {
        if (messageEntity == null) return
        super.invalidate()
    }

    fun setVisibleOnScreen(visible: Boolean, clipTop: Float = 0f, clipBottom: Float = 0f) {
        if (visibleOnScreen == visible) return
        visibleOnScreen = visible
        if (visible) invalidate()

        if (drawPhotoImage && photoHeight > 0) {
            val visibleH = if (visible) (photoHeight - clipTop - clipBottom) else 0f
            val ratio = visibleH / photoHeight
            photoImage.setSkipUpdateFrame(ratio < 0.25f)
            for (i in 0 until mediaGridCount.coerceAtMost(3)) {
                extraPhotoImages[i].setSkipUpdateFrame(ratio < 0.25f)
            }
        }
    }

    fun stopHeavyOperations() {
        photoImage.setAllowStartAnimation(false)
        for (i in extraPhotoImages.indices) extraPhotoImages[i].setAllowStartAnimation(false)
    }

    fun setHighlight() {
        highlightProgress = 1f
        invalidate()
    }

    fun startHeavyOperations() {
        photoImage.setAllowStartAnimation(true)
        for (i in extraPhotoImages.indices) extraPhotoImages[i].setAllowStartAnimation(true)
    }

    override fun onDraw(canvas: Canvas) {
        val msg = messageEntity ?: return
        checkAvatarFallbackTimeout()

        if (hasMentionHighlight) {
            MENTION_BG_PAINT.color = theme.mentionHighlightBg
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), MENTION_BG_PAINT)
            canvas.drawRect(0f, 0f, MENTION_BAR_WIDTH.toFloat(), height.toFloat(), MENTION_BAR_PAINT)
        }

        if (highlightProgress > 0f) {
            val a = (highlightProgress * 0x30).toInt().coerceIn(0, 0xFF)
            HIGHLIGHT_BG_PAINT.color = theme.midnightBlue and 0x00FFFFFF or (a shl 24)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), HIGHLIGHT_BG_PAINT)
            highlightProgress = (highlightProgress - HIGHLIGHT_DECAY_STEP).coerceAtLeast(0f)
            if (highlightProgress > 0f) postInvalidateDelayed(16)
        }

        val alpha = when {
            drawError -> 0.6f
            drawSending -> 0.7f
            else -> 1f
        }
        if (alpha < 1f) {
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())
        }
        if (isSticker && contentLayout == null && !hasReply && !drawForwardHeader) {
            drawStickerOnly(canvas, msg)
            if (hasEmbedContent) {
                embedMessage.discardInteractiveGeometry()
                hideEmbedInteractiveViews()
            }
        } else {
            drawMessageBubble(canvas, msg)
        }
        if (!hasEmbedContent) {
            hideEmbedInteractiveViews()
        }
        if (alpha < 1f) {
            canvas.restore()
        }
        if (drawError) {
            drawErrorText(canvas, msg)
        }
    }

    private fun drawSendingIndicator(canvas: Canvas) {
        val x = (PAD_H + AVATAR_SIZE + GAP_AVATAR).toFloat()
        val topPad = if (isCombined) COMBINE_PAD_V else PAD_V
        val y = (measuredHeight - topPad - SENDING_ICON_SIZE).toFloat()
        val cx = x + SENDING_ICON_SIZE / 2f
        val cy = y + SENDING_ICON_SIZE / 2f
        val r = SENDING_ICON_SIZE / 2f - SENDING_STROKE_W
        SENDING_CIRCLE_PAINT.color = theme.onSurfaceVariant
        canvas.drawCircle(cx, cy, r, SENDING_CIRCLE_PAINT)
        val handLen = r * 0.55f
        canvas.drawLine(cx, cy, cx, cy - handLen, SENDING_HAND_PAINT)
        canvas.drawLine(cx, cy, cx + handLen * 0.7f, cy, SENDING_HAND_PAINT)
    }

    private fun drawAttachmentUploadSpinner(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        tmpRect.set(x, y, x + w, y + h)
        if (radius > 0) {
            canvas.drawRoundRect(tmpRect, radius, radius, SPINNER_OVERLAY_PAINT)
        } else {
            canvas.drawRect(tmpRect, SPINNER_OVERLAY_PAINT)
        }
        val cx = x + w / 2f
        val cy = y + h / 2f
        spinnerArcRect.set(cx - SPINNER_RADIUS, cy - SPINNER_RADIUS, cx + SPINNER_RADIUS, cy + SPINNER_RADIUS)
        canvas.drawArc(spinnerArcRect, spinnerAngle, 270f, false, SPINNER_ARC_PAINT)
        spinnerAngle = (spinnerAngle + 3f) % 360f
        postInvalidateDelayed(33)
    }

    private fun drawStickerOnly(canvas: Canvas, msg: MessageEntity) {
        val topPad = if (isCombined) COMBINE_PAD_V else PAD_V
        val contentLeft = if (isInPinMode) PIN_PAD_H else PAD_H + AVATAR_SIZE + GAP_AVATAR
        var yOff = topPad.toFloat()

        if (!isCombined) {
            avatarDrawable.setBounds(PAD_H, topPad, PAD_H + AVATAR_SIZE, topPad + AVATAR_SIZE)
            avatarDrawable.draw(canvas)
        }

        if (!isCombined) {
            senderLayout?.let { sender ->
                canvas.save()
                canvas.translate(contentLeft.toFloat(), yOff)
                sender.draw(canvas)
                canvas.restore()
                drawSenderRoleIconAfterName(canvas, contentLeft, yOff, sender)

                timeLayout?.let { time ->
                    val timeX = (contentLeft + cachedSenderW + TIME_GAP_LEFT).toFloat()
                        .coerceAtMost((width - TIME_GAP_RIGHT).toFloat())
                    val timeY = yOff + sender.height - time.height
                    canvas.save()
                    canvas.translate(timeX, timeY)
                    time.draw(canvas)
                    canvas.restore()
                }

                yOff += sender.height + GAP_V_INNER
            }
        }

        val imgX = contentLeft.toFloat()
        photoImage.setRoundRadius(0)
        photoImage.setImageCoords(imgX, yOff, photoWidth.toFloat(), photoHeight.toFloat())
        photoImage.draw(canvas)
        if (drawSending) {
            drawAttachmentUploadSpinner(canvas, imgX, yOff, photoWidth.toFloat(), photoHeight.toFloat(), 0f)
        } else if (!photoImage.hasMainImage() && photoImage.shouldAnimateLoadingPlaceholder()) {
            shimmerEffect.draw(canvas, imgX, yOff, imgX + photoWidth, yOff + photoHeight, 0f,
                theme.resolvedMode != com.mezon.mobile.ui.theme.ThemeMode.LIGHT)
            postInvalidateDelayed(32)
        }
    }

    private fun drawMessageBubble(canvas: Canvas, msg: MessageEntity) {
        pollCardDrawTopY = Float.NaN
        val topPad = if (isCombined) COMBINE_PAD_V else PAD_V
        val contentLeft = if (isInPinMode) PIN_PAD_H else PAD_H + AVATAR_SIZE + GAP_AVATAR

        var yOff = topPad.toFloat()

        if (hasReply) {
            drawReplyPreviewRow(canvas, contentLeft.toFloat(), yOff)
            yOff += REPLY_ROW_HEIGHT + REPLY_V_GAP
        }

        if (!isCombined) {
            val avatarTop = yOff.toInt()
            avatarDrawable.setBounds(PAD_H, avatarTop, PAD_H + AVATAR_SIZE, avatarTop + AVATAR_SIZE)
            avatarDrawable.draw(canvas)
        }

        senderLayout?.let { sender ->
            canvas.save()
            canvas.translate(contentLeft.toFloat(), yOff)
            sender.draw(canvas)
            canvas.restore()
            drawSenderRoleIconAfterName(canvas, contentLeft, yOff, sender)

            timeLayout?.let { time ->
                val timeX = (contentLeft + cachedSenderW + TIME_GAP_LEFT).toFloat()
                        .coerceAtMost((width - TIME_GAP_RIGHT).toFloat())
                val timeY = yOff + sender.height - time.height 
                canvas.save()
                canvas.translate(timeX, timeY)
                time.draw(canvas)
                canvas.restore()
            }

            yOff += sender.height + GAP_V_INNER
        }

        forwardLayout?.let {
            drawForwardHeader(canvas, contentLeft.toFloat(), yOff, msg)
            yOff += it.height + GAP_V_INNER
        }

        if (pollParsed != null && msg.isPollMessage) {
            val pl = pollBridge?.pollForLayout(msg.id, pollParsed!!) ?: pollParsed!!
            val st = pollBridge?.getLocalState(msg.id) ?: PollLocalState()
            val xCard = contentLeft.toFloat()
            pollCardDrawTopY = yOff
            pollLayoutHelper.draw(canvas, xCard, yOff, pl, st, currentUserId)
            syncPollHitRect()
            yOff += pollLayoutHelper.blockHeight + GAP_V_INNER
        } else {
            hasPollCard = false
            pollHitRect.setEmpty()
            pollCardDrawTopY = Float.NaN
        }

        if (hasShareContactCard && shareContactParsed != null) {
            val xCard = contentLeft.toFloat()
            shareContactCardDrawTopY = yOff
            shareContactLayout.draw(canvas, xCard, yOff)
            syncShareContactHitRect(contentLeft)
            yOff += shareContactLayout.blockHeight + GAP_V_INNER
        } else if (!hasShareContactCard) {
            shareContactHitRect.setEmpty()
            shareContactCardDrawTopY = Float.NaN
        }

        if (hasCallLogCard) {
            yOff = drawCallLogCard(canvas, contentLeft.toFloat(), yOff) + GAP_V_INNER
        } else if (!hasShareContactCard) {
            if (hasEphemeralDecor) {
                EphemeralMessageUi.drawBubbleBackground(canvas, theme, ephemeralDecorRect)
            }

            contentLayout?.let {
                contentLayoutLeft = contentLeft
                contentLayoutTop = yOff.toInt()
                canvas.save()
                canvas.translate(contentLeft.toFloat(), yOff)
                it.draw(canvas)
                canvas.restore()
                yOff += it.height + (if (ogpData != null || linkInviteBlock.isVisible) LINK_INVITE_V_MARGIN else GAP_V_INNER)
            }
        }

        if (ogpData != null) {
            yOff += GAP_V_INNER
            yOff = drawOgpBlock(canvas, contentLeft.toFloat(), yOff) + GAP_V_INNER
        }

        if (linkInviteBlock.isVisible) {
            yOff = linkInviteBlock.draw(canvas, contentLeft.toFloat(), yOff) + LINK_INVITE_V_MARGIN
        }

        if (drawPhotoImage) {
            val imgX = contentLeft.toFloat()
            if (mediaGridCount <= 1) {
                photoImage.setImageCoords(imgX, yOff, photoWidth.toFloat(), photoHeight.toFloat())
                photoImage.draw(canvas)
                drawMediaOverlays(canvas, msg, imgX, yOff)
                yOff += photoHeight + GAP_V_INNER
            } else {
                yOff = drawMediaGrid(canvas, msg, imgX, yOff)
                yOff += GAP_V_INNER
            }
        }

        if (drawFileAttachment) {
            yOff = drawFileBlock(canvas, contentLeft.toFloat(), yOff)
        }

        if (drawAudioAttachment) {
            yOff = drawAudioBlock(canvas, contentLeft.toFloat(), yOff, msg)
        }

        if (hasEmbedContent) {
            yOff = embedMessage.draw(canvas, contentLeft.toFloat(), yOff, maxBubbleWidth(), shimmerEffect)
        }

        if (drawEphemeral) {
            yOff = drawEphemeralIndicator(canvas, contentLeft.toFloat(), yOff)
        }

        if (reactionGroups.isNotEmpty()) {
            yOff += REACTION_TOP_PAD
            drawReactionRow(canvas, contentLeft.toFloat(), yOff)
            yOff += reactionRowHeight
        }

    }

    private fun drawForwardHeader(canvas: Canvas, x: Float, y: Float, msg: MessageEntity) {
        val layout = forwardLayout ?: return
        val iconSize = FORWARD_ICON_SIZE.toFloat()
        canvas.save()
        canvas.translate(x + iconSize + FORWARD_ICON_GAP, y)
        layout.draw(canvas)
        canvas.restore()

        FORWARD_ARROW_PAINT.color = FORWARD_PAINT.color
        val cx = x + iconSize / 2
        val cy = y + layout.height / 2f
        val half = iconSize * 0.3f
        forwardArrowPath.reset()
        forwardArrowPath.moveTo(cx - half * 0.5f, cy - half)
        forwardArrowPath.lineTo(cx + half * 0.5f, cy)
        forwardArrowPath.lineTo(cx - half * 0.5f, cy + half)
        canvas.drawPath(forwardArrowPath, FORWARD_ARROW_PAINT)
    }

    private fun drawFileBlock(canvas: Canvas, x: Float, y: Float): Float {
        val iconD = fileIconDrawable ?: return y
        var yOff = y
        yOff = drawSingleFileCard(canvas, x, yOff, iconD, fileNameLayout, fileSizeLayout, isFirst = true)
        for ((nl, sl) in extraFileLayouts) {
            yOff = drawSingleFileCard(canvas, x, yOff, iconD, nl, sl, isFirst = false)
        }
        return yOff
    }

    private fun drawSingleFileCard(
        canvas: Canvas, x: Float, y: Float, iconD: Drawable,
        nameLayout: StaticLayout?, sizeLayout: StaticLayout?, isFirst: Boolean
    ): Float {
        val cardW = fileRowWidth.toFloat()
        val textH = (nameLayout?.height ?: 0) + (sizeLayout?.height ?: 0)
        val innerH = maxOf(FILE_ICON_SIZE, textH)
        val cardH = (FILE_ROW_V_PAD * 2 + maxOf(innerH, FILE_ROW_MIN_HEIGHT - FILE_ROW_V_PAD * 2)).toFloat()

        if (isFirst) {
            fileBlockLeft = x
            fileBlockTop = y
            fileBlockRight = x + cardW
            fileBlockBottom = y + cardH
        }

        FILE_CARD_BG_PAINT.color = theme.secondaryLight
        fileRoundRect.set(x, y, x + cardW, y + cardH)
        canvas.drawRoundRect(fileRoundRect, FILE_ROW_RADIUS, FILE_ROW_RADIUS, FILE_CARD_BG_PAINT)

        val innerX = x + FILE_ROW_H_PAD
        val innerY = y + FILE_ROW_V_PAD
        val iconCenterY = innerY + (cardH - FILE_ROW_V_PAD * 2 - FILE_ICON_SIZE) / 2f
        iconD.setBounds(
            innerX.toInt(), iconCenterY.toInt(),
            innerX.toInt() + FILE_ICON_SIZE, iconCenterY.toInt() + FILE_ICON_SIZE
        )
        iconD.draw(canvas)

        val textX = innerX + FILE_ICON_SIZE + FILE_ICON_GAP
        val totalTextH = textH.toFloat()
        var textY = innerY + (cardH - FILE_ROW_V_PAD * 2 - totalTextH) / 2f
        nameLayout?.let {
            canvas.save()
            canvas.translate(textX, textY)
            it.draw(canvas)
            canvas.restore()
            textY += it.height
        }
        sizeLayout?.let {
            canvas.save()
            canvas.translate(textX, textY)
            it.draw(canvas)
            canvas.restore()
        }
        return y + cardH + GAP_V_INNER
    }

    private fun drawAudioBlock(canvas: Canvas, x: Float, y: Float, msg: MessageEntity): Float {
        val pillW = audioPillWidth.toFloat()
        val pillH = AUDIO_PILL_HEIGHT.toFloat()
        val pillAlpha = if (msg.isSending) 0.6f else 1f

        audioBlockLeft = x
        audioBlockTop = y
        audioBlockRight = x + pillW
        audioBlockBottom = y + pillH

        AUDIO_BG_PAINT.color = AUDIO_BG_COLOR
        AUDIO_BG_PAINT.alpha = (255 * pillAlpha).toInt()
        audioRoundRect.set(x, y, x + pillW, y + pillH)
        canvas.drawRoundRect(audioRoundRect, pillH / 2f, pillH / 2f, AUDIO_BG_PAINT)

        val pad = AUDIO_PILL_PAD.toFloat()
        val btnSize = AUDIO_PLAY_BTN_SIZE.toFloat()
        val btnCx = x + pad + btnSize / 2f
        val btnCy = y + pillH / 2f
        AUDIO_PLAY_BG_PAINT.color = AUDIO_PLAY_BTN_COLOR
        AUDIO_PLAY_BG_PAINT.alpha = (255 * pillAlpha).toInt()
        canvas.drawCircle(btnCx, btnCy, btnSize / 2f, AUDIO_PLAY_BG_PAINT)

        AUDIO_PLAY_ICON_PAINT.alpha = (255 * pillAlpha).toInt()
        when {
            audioIsLoading -> drawAudioSpinner(canvas, btnCx, btnCy)
            audioIsPlaying -> drawAudioPauseIcon(canvas, btnCx, btnCy)
            else -> drawAudioPlayIcon(canvas, btnCx, btnCy)
        }

        val waveStart = x + pad + btnSize + AUDIO_CONTENT_H_PAD
        val waveCenterY = y + pillH / 2f
        if (audioIsPlaying) {
            val now = System.currentTimeMillis()
            if (audioLastFrameTimeMs == 0L) audioLastFrameTimeMs = now
            audioWaveTimeMs += (now - audioLastFrameTimeMs)
            audioLastFrameTimeMs = now
        } else {
            audioLastFrameTimeMs = 0L
        }
        drawAudioWave(canvas, waveStart, waveCenterY, pillAlpha)

        val timeLayout = audioTimeLayout
        if (timeLayout != null) {
            val timeX = waveStart + AUDIO_WAVE_WIDTH + AUDIO_TIME_GAP
            val timeY = y + (pillH - timeLayout.height) / 2f
            AUDIO_TIME_PAINT.alpha = (255 * pillAlpha).toInt()
            canvas.save()
            canvas.translate(timeX, timeY)
            timeLayout.draw(canvas)
            canvas.restore()
        }

        if (audioIsPlaying) invalidate()
        return y + pillH + GAP_V_INNER
    }

    private fun drawAudioPlayIcon(canvas: Canvas, cx: Float, cy: Float) {
        val size = AUDIO_PLAY_ICON_SIZE.toFloat()
        audioWavePath.reset()
        audioWavePath.moveTo(cx - size * 0.3f, cy - size * 0.4f)
        audioWavePath.lineTo(cx + size * 0.45f, cy)
        audioWavePath.lineTo(cx - size * 0.3f, cy + size * 0.4f)
        audioWavePath.close()
        canvas.drawPath(audioWavePath, AUDIO_PLAY_ICON_PAINT)
    }

    private fun drawAudioPauseIcon(canvas: Canvas, cx: Float, cy: Float) {
        val size = AUDIO_PLAY_ICON_SIZE.toFloat()
        val barW = size * 0.2f
        val barH = size * 0.8f
        canvas.drawRect(cx - size * 0.3f, cy - barH / 2, cx - size * 0.3f + barW, cy + barH / 2, AUDIO_PLAY_ICON_PAINT)
        canvas.drawRect(cx + size * 0.1f, cy - barH / 2, cx + size * 0.1f + barW, cy + barH / 2, AUDIO_PLAY_ICON_PAINT)
    }

    private fun drawAudioSpinner(canvas: Canvas, cx: Float, cy: Float) {
        val radius = AUDIO_PLAY_ICON_SIZE * 0.45f
        val sweep = 270f
        val angle = ((System.currentTimeMillis() / 3L) % 360L).toFloat()
        AUDIO_SPINNER_PAINT.alpha = AUDIO_PLAY_ICON_PAINT.alpha
        audioRoundRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(audioRoundRect, angle, sweep, false, AUDIO_SPINNER_PAINT)
        invalidate()
    }

    private fun drawAudioWave(canvas: Canvas, left: Float, centerY: Float, alpha: Float) {
        val barCount = AUDIO_WAVE_BAR_COUNT
        val barW = AUDIO_WAVE_BAR_WIDTH.toFloat()
        val gap = AUDIO_WAVE_BAR_GAP.toFloat()
        val minH = AUDIO_WAVE_MIN_HEIGHT.toFloat()
        val maxH = AUDIO_WAVE_MAX_HEIGHT.toFloat()
        AUDIO_WAVE_PAINT.alpha = (255 * alpha).toInt()
        val t = audioWaveTimeMs / 180.0
        var cursorX = left
        for (i in 0 until barCount) {
            val phase = i * 0.55 + t
            val amp = if (audioIsPlaying) {
                (Math.sin(phase) * 0.5 + 0.5).toFloat()
            } else {
                AUDIO_WAVE_STATIC_AMP[i % AUDIO_WAVE_STATIC_AMP.size]
            }
            val h = minH + (maxH - minH) * amp
            val top = centerY - h / 2f
            val bottom = centerY + h / 2f
            audioRoundRect.set(cursorX, top, cursorX + barW, bottom)
            canvas.drawRoundRect(audioRoundRect, barW / 2f, barW / 2f, AUDIO_WAVE_PAINT)
            cursorX += barW + gap
        }
    }

    private fun drawEphemeralIndicator(canvas: Canvas, x: Float, y: Float): Float {
        val layout = ephemeralLayout ?: return y
        EphemeralMessageUi.drawIndicatorRow(
            canvas,
            x,
            y,
            layout,
            ephemeralIconDrawable,
            EphemeralMessageUi.indicatorIconSize(),
            EphemeralMessageUi.INDICATOR_ICON_GAP
        )
        return y + layout.height + GAP_V_INNER
    }

    private fun drawErrorText(canvas: Canvas, msg: MessageEntity) {
        val layout = errorLayout ?: return
        val errorY = measuredCellHeight - PAD_V - layout.height
        val errorX = (PAD_H + AVATAR_SIZE + GAP_AVATAR).toFloat()
        canvas.save()
        canvas.translate(errorX, errorY.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawOgpBlock(canvas: Canvas, left: Float, top: Float): Float {
        val data = ogpData ?: return top
        var y = top
        ogpBlockLeft = left.toInt()
        ogpBlockTop = y.toInt()
        ogpTitleLayout?.let {
            canvas.save()
            canvas.translate(left, y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + GAP_V_INNER
        }
        ogpDescLayout?.let {
            canvas.save()
            canvas.translate(left, y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + GAP_V_INNER
        }
        ogpImage.setImageCoords(left, y, ogpImageW.toFloat(), ogpImageH.toFloat())
        ogpImage.draw(canvas)
        y += ogpImageH
        ogpBlockRight = (left + maxOf(cachedOgpTitleW, cachedOgpDescW, ogpImageW.toFloat())).toInt()
        ogpBlockBottom = y.toInt()
        return y
    }

    private var gridExtraCount = 0

    private fun drawGridCell(canvas: Canvas, slot: Int, x: Float, y: Float, w: Float, h: Float, isDark: Boolean): Boolean {
        var needsRedraw = false
        if (drawSending) {
            drawAttachmentUploadSpinner(canvas, x, y, w, h, MEDIA_RADIUS)
        } else if (!allReceivers[slot].hasMainImage()) {
            if (slotIsVideo[slot]) {
                drawVideoPlaceholder(canvas, x, y, w, h, MEDIA_RADIUS)
            } else if (allReceivers[slot].shouldAnimateLoadingPlaceholder()) {
                shimmerEffect.draw(canvas, x, y, x + w, y + h, MEDIA_RADIUS, isDark)
                needsRedraw = true
            }
        }
        if (slotIsVideo[slot] && !drawSending) {
            drawVideoPlayButton(canvas, x, y, w, h)
        }
        return needsRedraw
    }

    private fun drawMediaGrid(canvas: Canvas, msg: MessageEntity, startX: Float, startY: Float): Float {
        val gap = GRID_GAP
        val totalW = photoWidth.toFloat()
        val isDark = theme.resolvedMode != com.mezon.mobile.ui.theme.ThemeMode.LIGHT
        var needsShimmerRedraw = false

        when (mediaGridCount) {
            2 -> {
                val cellW = (totalW - gap) / 2f
                val cellH = photoHeight.toFloat()
                for (i in 0 until 2) {
                    val x = startX + i * (cellW + gap)
                    allReceivers[i].setImageCoords(x, startY, cellW, cellH)
                    allReceivers[i].draw(canvas)
                    if (drawGridCell(canvas, i, x, startY, cellW, cellH, isDark)) needsShimmerRedraw = true
                }
                if (needsShimmerRedraw) postInvalidateDelayed(16)
                return startY + cellH
            }
            3 -> {
                val leftW = (totalW - gap) * 0.6f
                val rightW = totalW - gap - leftW
                val leftH = photoHeight.toFloat()
                val rightH = (leftH - gap) / 2f

                allReceivers[0].setImageCoords(startX, startY, leftW, leftH)
                allReceivers[0].draw(canvas)
                if (drawGridCell(canvas, 0, startX, startY, leftW, leftH, isDark)) needsShimmerRedraw = true

                val rx = startX + leftW + gap
                for (i in 1 until 3) {
                    val ry = startY + (i - 1) * (rightH + gap)
                    allReceivers[i].setImageCoords(rx, ry, rightW, rightH)
                    allReceivers[i].draw(canvas)
                    if (drawGridCell(canvas, i, rx, ry, rightW, rightH, isDark)) needsShimmerRedraw = true
                }
                if (needsShimmerRedraw) postInvalidateDelayed(16)
                return startY + leftH
            }
            else -> {
                val cellW = (totalW - gap) / 2f
                val cellH = (photoHeight.toFloat() - gap) / 2f
                for (i in 0 until mediaGridCount.coerceAtMost(4)) {
                    val col = i % 2
                    val row = i / 2
                    val x = startX + col * (cellW + gap)
                    val y = startY + row * (cellH + gap)
                    allReceivers[i].setImageCoords(x, y, cellW, cellH)
                    allReceivers[i].draw(canvas)
                    if (drawGridCell(canvas, i, x, y, cellW, cellH, isDark)) needsShimmerRedraw = true
                }
                if (gridExtraCount > 0) {
                    val x = startX + (cellW + gap)
                    val y = startY + (cellH + gap)
                    GRID_OVERLAY_PAINT.color = 0x80000000.toInt()
                    tmpRect.set(x, y, x + cellW, y + cellH)
                    canvas.drawRoundRect(tmpRect, MEDIA_RADIUS, MEDIA_RADIUS, GRID_OVERLAY_PAINT)
                    val text = "+$gridExtraCount"
                    val textY = y + cellH / 2f - (GRID_COUNT_PAINT.descent() + GRID_COUNT_PAINT.ascent()) / 2f
                    canvas.drawText(text, x + cellW / 2f, textY, GRID_COUNT_PAINT)
                }
                if (needsShimmerRedraw) postInvalidateDelayed(16)
                return startY + cellH * 2 + gap
            }
        }
    }

    private fun drawMediaOverlays(canvas: Canvas, msg: MessageEntity, imgX: Float, imgY: Float) {
        val w = photoWidth.toFloat()
        val h = photoHeight.toFloat()
        val isVideo = msg.messageType == MessageEntity.TYPE_VIDEO || slotIsVideo[0]
        if (drawSending) {
            drawAttachmentUploadSpinner(canvas, imgX, imgY, w, h, MEDIA_RADIUS)
        } else if (!photoImage.hasMainImage()) {
            if (isVideo) {
                drawVideoPlaceholder(canvas, imgX, imgY, w, h, MEDIA_RADIUS)
            } else if (photoImage.shouldAnimateLoadingPlaceholder()) {
                shimmerEffect.draw(canvas, imgX, imgY, imgX + w, imgY + h, MEDIA_RADIUS,
                    theme.resolvedMode != com.mezon.mobile.ui.theme.ThemeMode.LIGHT)
                postInvalidateDelayed(32)
            }
        }
        if (isVideo && !drawSending) {
            drawVideoPlayButton(canvas, imgX, imgY, w, h)
            durationLayout?.let { drawDurationBadge(canvas, it, imgX, imgY) }
        }
        if (msg.messageType == MessageEntity.TYPE_GIF) {
            drawGifBadge(canvas, imgX, imgY)
        }
    }

    private fun drawVideoPlaceholder(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        tmpRect.set(x, y, x + w, y + h)
        canvas.drawRoundRect(tmpRect, radius, radius, VIDEO_PLACEHOLDER_PAINT)
    }

    private fun drawVideoPlayButton(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val cx = x + w / 2f
        val cy = y + h / 2f
        val r = (min(w, h) * 0.2f).coerceIn(LayoutHelper.dp(14f).toFloat(), PLAY_BTN_SIZE / 2f)
        canvas.drawCircle(cx, cy, r, PLAY_BG_PAINT)

        playTriPath.reset()
        val triSize = r * 0.7f
        val left = cx - triSize * 0.35f
        val top = cy - triSize * 0.5f
        playTriPath.moveTo(left, top)
        playTriPath.lineTo(left + triSize, cy)
        playTriPath.lineTo(left, cy + triSize * 0.5f)
        playTriPath.close()
        canvas.drawPath(playTriPath, PLAY_ICON_PAINT)
    }

    private fun drawGifBadge(canvas: Canvas, imgX: Float, imgY: Float) {
        val text = "GIF"
        val tw = GIF_BADGE_PAINT.measureText(text)
        val pad = BADGE_PAD
        val bh = GIF_BADGE_PAINT.textSize + pad
        val bw = tw + pad * 2
        val bx = imgX + BADGE_MARGIN
        val by = imgY + photoHeight - bh - BADGE_MARGIN

        tmpRect.set(bx, by, bx + bw, by + bh)
        canvas.drawRoundRect(tmpRect, bh / 2, bh / 2, DURATION_BG_PAINT)
        canvas.drawText(text, bx + pad, by + bh - pad / 2, GIF_BADGE_PAINT)
    }

    private fun drawDurationBadge(canvas: Canvas, layout: StaticLayout, imgX: Float, imgY: Float) {
        val tw = layout.getLineWidth(0)
        val pad = BADGE_PAD
        val bh = layout.height + pad
        val bw = tw + pad * 2
        val bx = imgX + BADGE_MARGIN
        val by = imgY + photoHeight - bh - BADGE_MARGIN

        tmpRect.set(bx, by, bx + bw, by + bh)
        canvas.drawRoundRect(tmpRect, bh / 2, bh / 2, DURATION_BG_PAINT)
        canvas.save()
        canvas.translate(bx + pad, by + (bh - layout.height) / 2)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun maxLineWidth(layout: StaticLayout): Float {
        var max = 0f
        for (i in 0 until layout.lineCount) max = maxOf(max, layout.getLineWidth(i))
        return max
    }

    private fun drawReplyPreviewRow(canvas: Canvas, contentLeft: Float, y: Float) {
        val rowH = REPLY_ROW_HEIGHT.toFloat()
        replyBlockLeft = contentLeft
        replyBlockTop = y
        replyBlockRight = contentLeft + cachedInnerWidth
        replyBlockBottom = y + rowH

        val centerY = y + rowH / 2f

        val connectorX = (PAD_H + AVATAR_SIZE / 2).toFloat()
        val connectorBottom = y + rowH + REPLY_V_GAP - CONNECTOR_GAP
        connectorPath.reset()
        connectorPath.moveTo(connectorX, connectorBottom)
        connectorPath.lineTo(connectorX, centerY + CONNECTOR_RADIUS)
        tmpRect.set(
            connectorX, centerY,
            connectorX + CONNECTOR_RADIUS * 2, centerY + CONNECTOR_RADIUS * 2
        )
        connectorPath.arcTo(tmpRect, 180f, 90f, false)
        connectorPath.lineTo(contentLeft - REPLY_H_GAP, centerY)
        canvas.drawPath(connectorPath, REPLY_CONNECTOR_PAINT)

        val avatarTop = (centerY - REPLY_AVATAR_SIZE / 2f).toInt()

        if (replyIsDeleted) {
            val textX = contentLeft + REPLY_AVATAR_SIZE + REPLY_H_GAP
            val textH = replyTextLayout?.height ?: 0
            val textY = centerY - textH / 2f
            replyTextLayout?.let {
                canvas.save()
                canvas.translate(textX, textY)
                it.draw(canvas)
                canvas.restore()
            }
            return
        }

        replyAvatarDrawable.setBounds(
            contentLeft.toInt(), avatarTop,
            contentLeft.toInt() + REPLY_AVATAR_SIZE, avatarTop + REPLY_AVATAR_SIZE
        )
        replyAvatarDrawable.draw(canvas)

        var textX = contentLeft + REPLY_AVATAR_SIZE + REPLY_H_GAP
        replyNameLayout?.let {
            val textY = centerY - it.height / 2f
            canvas.save()
            canvas.translate(textX, textY)
            it.draw(canvas)
            canvas.restore()
            textX += maxLineWidth(it) + REPLY_H_GAP
        }
        replyTextLayout?.let {
            val textY = centerY - it.height / 2f
            canvas.save()
            canvas.translate(textX, textY)
            it.draw(canvas)
            canvas.restore()
        }
    }

    private fun buildReactionLayouts(msg: MessageEntity, maxWidth: Int) {
        reactionEmojiCancellables.forEach { it?.cancel() }

        if (isInPinMode) {
            reactionGroups = emptyList()
            reactionCountLayouts = emptyArray()
            reactionChipBoundsCount = 0
            reactionIsMyFlags = BooleanArray(0)
            reactionEmojiBitmaps = emptyArray()
            reactionEmojiCancellables = emptyArray()
            reactionRowHeight = 0
            return
        }

        val groups = msg.combineReactions()
        reactionGroups = groups
        if (groups.isEmpty()) {
            reactionCountLayouts = emptyArray()
            reactionChipBoundsCount = 0
            reactionIsMyFlags = BooleanArray(0)
            reactionEmojiBitmaps = emptyArray()
            reactionEmojiCancellables = emptyArray()
            reactionRowHeight = 0
            return
        }

        reactionIsMyFlags = BooleanArray(groups.size) { i ->
            groups[i].senders.any { it.senderId == currentUserId && it.count > 0 }
        }

        REACTION_COUNT_PAINT.color = theme.onSurface
        val countLayouts = Array<StaticLayout?>(groups.size) { i ->
            val txt = groups[i].totalCount.toString()
            StaticLayout.Builder.obtain(txt, 0, txt.length, REACTION_COUNT_PAINT, LayoutHelper.dp(60))
                .setMaxLines(1)
                .build()
        }
        reactionCountLayouts = countLayouts

        val loadToken = ++reactionBitmapLoadToken
        val bitmaps = Array<android.graphics.Bitmap?>(groups.size) { null }
        val cancellables = Array<MezonImageLoader.Cancellable?>(groups.size) { null }
        val loader = MezonImageLoader.getInstance(context)
        var pendingLoads = 0

        fun finishOneLoad() {
            pendingLoads--
            if (pendingLoads <= 0) invalidate()
        }

        for (i in groups.indices) {
            val url = getEmojiUrl(groups[i].emojiId.toString()) ?: continue
            val cached = loader.getBitmapFromMemory(url, REACTION_EMOJI_SIZE, REACTION_EMOJI_SIZE)
            if (cached != null) {
                bitmaps[i] = cached
                continue
            }
            pendingLoads++
            val idx = i
            fun startLoad(loadUrl: String, isRetry: Boolean) {
                cancellables[idx] = loader.load(loadUrl, REACTION_EMOJI_SIZE, REACTION_EMOJI_SIZE,
                    onSuccess = { bmp ->
                        if (loadToken != reactionBitmapLoadToken) return@load
                        bitmaps[idx] = bmp
                        reactionEmojiBitmaps = bitmaps
                        finishOneLoad()
                    },
                    onError = {
                        if (loadToken != reactionBitmapLoadToken) return@load
                        if (!isRetry) {
                            val direct = getEmojiDirectUrl(groups[idx].emojiId.toString())
                            if (direct != null && direct != loadUrl) {
                                startLoad(direct, true)
                                return@load
                            }
                        }
                        finishOneLoad()
                    }
                )
            }
            startLoad(url, false)
        }
        reactionEmojiBitmaps = bitmaps
        reactionEmojiCancellables = cancellables

        var x = 0f
        var y = 0f
        val availW = maxWidth.toFloat()
        var boundsIdx = 0
        for (i in groups.indices) {
            val countW = countLayouts[i]?.let { it.getLineWidth(0) } ?: 0f
            val chipW = REACTION_CHIP_PAD * 2 + REACTION_EMOJI_SIZE + REACTION_EMOJI_MR + countW
            if (x > 0 && x + chipW > availW) {
                x = 0f
                y += REACTION_CHIP_H + REACTION_GAP
            }
            val rect = if (boundsIdx < reactionChipBounds.size) {
                reactionChipBounds[boundsIdx]
            } else {
                val r = RectF()
                reactionChipBounds.add(r)
                r
            }
            rect.set(x, y, x + chipW, y + REACTION_CHIP_H)
            boundsIdx++
            x += chipW + REACTION_GAP
        }
        reactionChipBoundsCount = boundsIdx

        val addChipW = REACTION_ADD_SIZE.toFloat()
        if (x > 0 && x + addChipW > availW) {
            x = 0f
            y += REACTION_CHIP_H + REACTION_GAP
        }
        reactionAddBounds.set(x, y, x + addChipW, y + REACTION_CHIP_H)

        reactionRowHeight = (y + REACTION_CHIP_H).toInt()
    }

    private fun drawReactionRow(canvas: Canvas, startX: Float, startY: Float) {
        val groups = reactionGroups
        val secondaryColor = theme.tertiary
        val myBg = theme.reactionBgColor
        val myBorder = theme.reactionBorderColor

        for (i in groups.indices) {
            if (i >= reactionChipBoundsCount) continue
            val bounds = reactionChipBounds[i]
            val chipX = startX + bounds.left
            val chipY = startY + bounds.top

            val isMyReaction = reactionIsMyFlags.getOrElse(i) { false }
            reactionChipRect.set(chipX, chipY, chipX + bounds.width(), chipY + bounds.height())

            if (isMyReaction) {
                REACTION_BG_PAINT.color = myBg
                canvas.drawRoundRect(reactionChipRect, REACTION_CHIP_RADIUS, REACTION_CHIP_RADIUS, REACTION_BG_PAINT)
                REACTION_BORDER_PAINT.color = myBorder
                canvas.drawRoundRect(reactionChipRect, REACTION_CHIP_RADIUS, REACTION_CHIP_RADIUS, REACTION_BORDER_PAINT)
            } else {
                REACTION_BG_PAINT.color = secondaryColor
                canvas.drawRoundRect(reactionChipRect, REACTION_CHIP_RADIUS, REACTION_CHIP_RADIUS, REACTION_BG_PAINT)
            }

            val emojiX = chipX + REACTION_CHIP_PAD
            val emojiY = chipY + (REACTION_CHIP_H - REACTION_EMOJI_SIZE) / 2f
            val bmp = reactionEmojiBitmaps.getOrNull(i)
            if (bmp != null && !bmp.isRecycled) {
                tmpRect.set(emojiX, emojiY, emojiX + REACTION_EMOJI_SIZE, emojiY + REACTION_EMOJI_SIZE)
                canvas.drawBitmap(bmp, null, tmpRect, null)
            } else {
                tmpRect.set(emojiX, emojiY, emojiX + REACTION_EMOJI_SIZE, emojiY + REACTION_EMOJI_SIZE)
                REACTION_BG_PAINT.color = EMOJI_PLACEHOLDER_COLOR
                canvas.drawRoundRect(tmpRect, EMOJI_PLACEHOLDER_RADIUS, EMOJI_PLACEHOLDER_RADIUS, REACTION_BG_PAINT)
            }

            val countLayout = reactionCountLayouts.getOrNull(i)
            if (countLayout != null) {
                val countX = emojiX + REACTION_EMOJI_SIZE + REACTION_EMOJI_MR
                val countY = chipY + (REACTION_CHIP_H - countLayout.height) / 2f
                canvas.save()
                canvas.translate(countX, countY)
                REACTION_COUNT_PAINT.color = theme.onSurface
                countLayout.draw(canvas)
                canvas.restore()
            }
        }

        val addBounds = reactionAddBounds
        if (addBounds.width() > 0) {
            val addX = startX + addBounds.left
            val addY = startY + addBounds.top + (REACTION_CHIP_H - REACTION_ADD_SIZE) / 2f
            val icon = reactionAddIcon ?: run {
                com.mezon.mobile.ui.cells.MezonIcon.reactionIcon.getDrawable(context).mutate().also {
                    it.colorFilter = android.graphics.PorterDuffColorFilter(0xFF808080.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    reactionAddIcon = it
                }
            }
            icon.setBounds(addX.toInt(), addY.toInt(), (addX + REACTION_ADD_SIZE).toInt(), (addY + REACTION_ADD_SIZE).toInt())
            icon.draw(canvas)
        }
    }

    private fun refreshEmbedInteractiveChrome() {
        embedInputBackground.cornerRadius = LayoutHelper.dpf(12f)
        embedInputBackground.setColor(theme.secondaryLight)
        embedInputBackground.setStroke(LayoutHelper.dp(1), theme.outline)
        embedSelectBackground.cornerRadius = LayoutHelper.dpf(4f)
        embedSelectBackground.setColor(theme.surface)
        embedSelectBackground.setStroke(LayoutHelper.dp(1), theme.outline)
        embedSelectChevronDrawable.colorFilter =
            PorterDuffColorFilter(theme.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
    }

    private fun embedSelectRowForeground(): Drawable? {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            ContextCompat.getDrawable(context, tv.resourceId)
        } else {
            null
        }
    }

    private fun hideEmbedInteractiveViews(force: Boolean = false) {
        resetEmbedInteractiveSync()
        if (!force && !embedInteractiveViewsVisible) return
        for (slot in embedInputSlots) slot.edit.visibility = View.GONE
        for (slot in embedSelectSlots) slot.row.visibility = View.GONE
        for (slot in embedRadioSlots) slot.container.visibility = View.GONE
        embedInteractiveViewsVisible = false
    }

    private fun embedRequiredSuffixStar(text: CharSequence): CharSequence {
        val s = SpannableString("$text *")
        val starStart = s.length - 1
        s.setSpan(ForegroundColorSpan(theme.error), starStart, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun embedSelectOptionDisplayLabel(opt: EmbedSelectOptionSpec): String =
        opt.label.ifEmpty { opt.value }

    private fun embedSelectPlaceholder(spec: EmbedSelectSpec, fieldName: String): String {
        val name = fieldName.trim()
        val hasName = name.isNotEmpty()
        val min = spec.minPick
        val max = spec.maxPick
        if (min > 0 && max > 0 && min <= max) {
            return if (hasName) {
                context.getString(R.string.embed_select_range_for, min, max, name)
            } else {
                context.getString(R.string.embed_select_range, min, max)
            }
        }
        if (max > 1) {
            return if (hasName) {
                context.getString(R.string.embed_select_up_to_for, max, name)
            } else {
                context.getString(R.string.embed_select_up_to, max)
            }
        }
        if (min > 1) {
            return if (hasName) {
                context.getString(R.string.embed_select_at_least_for, min, name)
            } else {
                context.getString(R.string.embed_select_at_least, min)
            }
        }
        return if (hasName) {
            context.getString(R.string.embed_select_one_for, name)
        } else {
            context.getString(R.string.embed_select_one)
        }
    }

    private fun formatEmbedSelectLabel(
        spec: EmbedSelectSpec,
        messageId: Long,
        componentId: String,
        fieldName: String,
    ): CharSequence {
        val vals = EmbedFormUtil.getValuesForComponent(messageId, componentId)
        if (vals.isEmpty()) {
            val ph = embedSelectPlaceholder(spec, fieldName)
            return if (spec.minPick > 0) embedRequiredSuffixStar(ph) else ph
        }
        if (!spec.isMulti) {
            val v = vals.firstOrNull() ?: return embedSelectPlaceholder(spec, fieldName)
            return spec.options.find { it.value == v }?.let { embedSelectOptionDisplayLabel(it) } ?: v
        }
        return vals.joinToString(", ") { valItem ->
            spec.options.find { it.value == valItem }?.let { embedSelectOptionDisplayLabel(it) } ?: valItem
        }
    }

    private fun showEmbedSelectDialog(messageId: Long, componentId: String, spec: EmbedSelectSpec, fieldName: String) {
        val act = AndroidUtilities.findActivity(context) ?: return
        val msg = messageEntity
        val title = run {
            val ph = embedSelectPlaceholder(spec, fieldName)
            if (spec.minPick > 0) embedRequiredSuffixStar(ph) else ph
        }
        EmbedSelectOptionSheet.show(
            context = act,
            theme = theme,
            title = title,
            spec = spec,
            messageId = messageId,
            componentId = componentId,
            onInvalidate = { requestEmbedInteractiveRelayout() },
            onSingleSelectionNotify = { value ->
                msg?.let { m -> delegate?.didChangeEmbedSelect(this@ChatMessageCell, m, componentId, value) }
            },
            onMultiValueAddedNotify = { value ->
                msg?.let { m -> delegate?.didChangeEmbedSelect(this@ChatMessageCell, m, componentId, value) }
            },
        )
    }

    private fun layoutEmbeddedChild(v: View, r: RectF) {
        val l = r.left.toInt()
        val t = r.top.toInt()
        val rgt = r.right.toInt()
        var btm = r.bottom.toInt()
        if (v is EditTextBoldCursor) {
            val fixed = v.getFixedSize()
            if (fixed > 0) btm = t + fixed
        }
        val unchanged = v.left == l && v.top == t && v.right == rgt && v.bottom == btm
        if (unchanged) {
            if (v.isLayoutRequested) v.layout(l, t, rgt, btm)
            return
        }
        val w = (rgt - l).coerceAtLeast(1)
        val h = (btm - t).coerceAtLeast(1)
        if (BuildConfig.DEBUG && v is EditTextBoldCursor) {
            val geomH = (r.bottom - r.top).toInt()
            if (geomH != h) {
                Log.d(
                    EMBED_INPUT_TAG,
                    "layoutEmbeddedChild geomH=$geomH fixedH=$h fixedSize=${v.getFixedSize()} " +
                        "component=${v.tag}",
                )
            }
        }
        val ws = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val hs = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        v.measure(ws, hs)
        v.layout(l, t, rgt, btm)
    }

    private fun resetEmbedInteractiveSync() {
        embedInteractiveAppliedHash = 0
        embedInteractiveAppliedMessageId = 0L
        embedInteractivePostedHash = 0
        embedInteractivePostedMessageId = 0L
    }

    private fun requestEmbedInteractiveRelayout() {
        embedInteractiveAppliedHash = 0
        embedInteractiveAppliedMessageId = 0L
        invalidate()
    }

    private fun scheduleEmbedInteractiveSync() {
        val msg = messageEntity ?: run {
            hideEmbedInteractiveViews()
            return
        }
        if (!hasEmbedContent) {
            hideEmbedInteractiveViews()
            return
        }
        val geoms = embedMessage.lastEmbedInteractiveGeometries
        if (geoms.isEmpty()) {
            hideEmbedInteractiveViews()
            return
        }
        val hash = embedInteractiveGeometryHash(msg.id, geoms)
        if (embedInteractiveAppliedMessageId == msg.id && embedInteractiveAppliedHash == hash) return
        if (embedInteractivePostedMessageId == msg.id && embedInteractivePostedHash == hash) return
        embedInteractivePostedMessageId = msg.id
        embedInteractivePostedHash = hash
        post {
            val cur = messageEntity ?: return@post
            if (cur.id != msg.id || !hasEmbedContent) return@post
            if (embedInteractivePostedMessageId != msg.id || embedInteractivePostedHash != hash) return@post
            embedInteractivePostedMessageId = 0L
            embedInteractivePostedHash = 0
            layoutEmbedInteractiveViews()
            embedInteractiveAppliedMessageId = msg.id
            embedInteractiveAppliedHash = hash
        }
    }

    private fun embedInteractiveGeometryHash(messageId: Long, geoms: List<EmbedInteractiveGeometry>): Int {
        var h = messageId.hashCode()
        h = h * 31 + geoms.size
        for (g in geoms) {
            val r = g.rect
            h = h * 31 + g.componentId.hashCode()
            h = h * 31 + java.lang.Float.floatToIntBits(r.left)
            h = h * 31 + java.lang.Float.floatToIntBits(r.top)
            h = h * 31 + java.lang.Float.floatToIntBits(r.right)
            h = h * 31 + java.lang.Float.floatToIntBits(r.bottom)
            when (g) {
                is EmbedInteractiveGeometry.InputField -> {
                    h = h * 31 + 1
                    h = h * 31 + g.input.hashCode()
                }
                is EmbedInteractiveGeometry.SelectField -> {
                    h = h * 31 + 2
                    h = h * 31 + g.input.hashCode()
                    h = h * 31 + g.fieldName.hashCode()
                }
                is EmbedInteractiveGeometry.RadioField -> {
                    h = h * 31 + 3
                    h = h * 31 + g.input.hashCode()
                }
            }
        }
        return if (h == 0) 1 else h
    }

    private fun layoutEmbedInteractiveViews() {
        val msg = messageEntity ?: run {
            hideEmbedInteractiveViews()
            return
        }
        refreshEmbedInteractiveChrome()
        val geoms = embedMessage.lastEmbedInteractiveGeometries
        if (geoms.isEmpty()) {
            hideEmbedInteractiveViews()
            return
        }
        var inputIdx = 0
        var selectIdx = 0
        var radioIdx = 0
        for (g in geoms) {
            when (g) {
                is EmbedInteractiveGeometry.InputField -> {
                    while (embedInputSlots.size <= inputIdx) embedInputSlots.add(EmbedInputSlot())
                    val slot = embedInputSlots[inputIdx++]
                    if (slot.edit.parent == null) addView(slot.edit)
                    slot.bind(msg.id, g.componentId, g.input)
                    slot.edit.visibility = View.VISIBLE
                    layoutEmbeddedChild(slot.edit, g.rect)
                }
                is EmbedInteractiveGeometry.SelectField -> {
                    while (embedSelectSlots.size <= selectIdx) embedSelectSlots.add(EmbedSelectSlot())
                    val slot = embedSelectSlots[selectIdx++]
                    if (slot.row.parent == null) addView(slot.row)
                    slot.bind(msg.id, g.componentId, g.input, g.fieldName)
                    slot.row.visibility = View.VISIBLE
                    layoutEmbeddedChild(slot.row, g.rect)
                }
                is EmbedInteractiveGeometry.RadioField -> {
                    while (embedRadioSlots.size <= radioIdx) embedRadioSlots.add(EmbedRadioSlot())
                    val slot = embedRadioSlots[radioIdx++]
                    if (slot.container.parent == null) addView(slot.container)
                    slot.bind(msg.id, g.componentId, g.input)
                    slot.container.visibility = View.VISIBLE
                    layoutEmbeddedChild(slot.container, g.rect)
                }
            }
        }
        for (j in inputIdx until embedInputSlots.size) embedInputSlots[j].edit.visibility = View.GONE
        for (j in selectIdx until embedSelectSlots.size) embedSelectSlots[j].row.visibility = View.GONE
        for (j in radioIdx until embedRadioSlots.size) embedRadioSlots[j].container.visibility = View.GONE
        embedInteractiveViewsVisible = inputIdx > 0 || selectIdx > 0 || radioIdx > 0
    }

    private inner class EmbedSelectSlot {
        val row: FrameLayout = FrameLayout(context).apply {
            clipToOutline = true
            isClickable = true
            isFocusable = true
        }
        val label: TextView = TextView(context).apply {
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(9), LayoutHelper.dp(10), LayoutHelper.dp(9))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            isClickable = false
            isFocusable = false
        }
        private var boundKey = ""
        private var rippleInstalled = false

        init {
            row.addView(
                label,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        fun bind(messageId: Long, componentId: String, spec: EmbedSelectSpec, fieldName: String) {
            val key = "$messageId|$componentId|${spec.options.size}|${spec.isMulti}|${
                spec.initialSelection.joinToString()
            }|$fieldName"
            val identityChanged = boundKey != key
            boundKey = key

            if (!rippleInstalled) {
                embedSelectRowForeground()?.let { row.foreground = it }
                rippleInstalled = true
            }

            row.background = embedSelectBackground
            row.isEnabled = !spec.disabled
            row.alpha = if (spec.disabled) 0.5f else 1f

            label.setCompoundDrawablesRelative(null, null, embedSelectChevronDrawable, null)
            label.compoundDrawablePadding = LayoutHelper.dp(6)

            if (identityChanged && spec.initialSelection.isNotEmpty() &&
                EmbedFormUtil.isComponentEmpty(messageId, componentId)
            ) {
                if (spec.isMulti) {
                    EmbedFormUtil.setMultiValues(messageId, componentId, spec.initialSelection)
                } else {
                    EmbedFormUtil.setValue(messageId, componentId, spec.initialSelection.first())
                }
            }

            label.text = formatEmbedSelectLabel(spec, messageId, componentId, fieldName)
            val hasValue = !EmbedFormUtil.isComponentEmpty(messageId, componentId)
            label.setTextColor(if (hasValue) theme.onSurface else theme.onSurfaceVariant)

            val mid = messageId
            val cid = componentId
            val sp = spec
            val fname = fieldName
            row.setOnClickListener {
                if (sp.disabled) return@setOnClickListener
                showEmbedSelectDialog(mid, cid, sp, fname)
            }
        }
    }

    private inner class EmbedRadioSlot {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        private var boundKey = ""
        private var suppressRadioCb = false

        fun bind(messageId: Long, componentId: String, spec: EmbedRadioSpec) {
            val key = "$messageId|$componentId|${spec.options.joinToString { it.value }}"
            val identityChanged = boundKey != key
            boundKey = key

            if (identityChanged) {
                container.removeAllViews()
                if (!spec.multi) {
                    val rg = RadioGroup(context)
                    for (opt in spec.options) {
                        val rb = RadioButton(context)
                        rb.id = View.generateViewId()
                        rb.text = radioOptionDisplayCharSeq(opt)
                        rb.setSingleLine(false)
                        rb.maxLines = 8
                        rb.setTextColor(theme.onSurface)
                        rb.isEnabled = !spec.disabled && !opt.disabled
                        rg.addView(
                            rb,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    }
                    rg.setOnCheckedChangeListener { group, checkedId ->
                        if (suppressRadioCb || checkedId == -1) return@setOnCheckedChangeListener
                        for (i in 0 until group.childCount) {
                            if (group.getChildAt(i).id == checkedId) {
                                EmbedFormUtil.setValue(messageId, componentId, spec.options[i].value)
                                requestEmbedInteractiveRelayout()
                                return@setOnCheckedChangeListener
                            }
                        }
                    }
                    container.addView(rg)
                } else {
                    for (opt in spec.options) {
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        val tv = TextView(context).apply {
                            text = radioOptionDisplayCharSeq(opt)
                            setSingleLine(false)
                            maxLines = 8
                            setTextColor(theme.onSurface)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        }
                        val cb = CheckBox(context)
                        row.addView(
                            tv,
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        row.addView(cb, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        cb.isEnabled = !spec.disabled && !opt.disabled
                        val optVal = opt.value
                        cb.setOnCheckedChangeListener { _, isChecked ->
                            if (suppressRadioCb) return@setOnCheckedChangeListener
                            val has = EmbedFormUtil.isValueSelected(messageId, componentId, optVal)
                            if (isChecked && !has) {
                                val n = EmbedFormUtil.getValuesForComponent(messageId, componentId).size
                                val max = spec.maxOptions ?: Int.MAX_VALUE
                                if (n >= max) {
                                    suppressRadioCb = true
                                    cb.isChecked = false
                                    suppressRadioCb = false
                                    return@setOnCheckedChangeListener
                                }
                                EmbedFormUtil.toggleMultiValue(messageId, componentId, optVal)
                            } else if (!isChecked && has) {
                                EmbedFormUtil.toggleMultiValue(messageId, componentId, optVal)
                            }
                            requestEmbedInteractiveRelayout()
                        }
                        container.addView(row)
                    }
                }
            }

            if (identityChanged && EmbedFormUtil.isComponentEmpty(messageId, componentId)) {
                val uid = currentUserId.toString()
                if (uid.isNotEmpty()) {
                    for (opt in spec.options) {
                        if (opt.extraData.any { it == uid }) {
                            if (spec.multi) {
                                if (!EmbedFormUtil.isValueSelected(messageId, componentId, opt.value)) {
                                    EmbedFormUtil.toggleMultiValue(messageId, componentId, opt.value)
                                }
                            } else {
                                EmbedFormUtil.setValue(messageId, componentId, opt.value)
                                break
                            }
                        }
                    }
                }
            }

            if (!spec.multi) {
                val rg = container.getChildAt(0) as? RadioGroup ?: return
                suppressRadioCb = true
                val cur = EmbedFormUtil.getValue(messageId, componentId)
                var sel = spec.options.indexOfFirst { it.value == cur }
                if (sel < 0) {
                    val uid = currentUserId.toString()
                    sel = spec.options.indexOfFirst { opt -> opt.extraData.any { it == uid } }
                    if (sel >= 0) {
                        EmbedFormUtil.setValue(messageId, componentId, spec.options[sel].value)
                    }
                }
                if (sel >= 0 && sel < rg.childCount) {
                    rg.check(rg.getChildAt(sel).id)
                } else {
                    rg.clearCheck()
                }
                suppressRadioCb = false
            } else {
                val selected = EmbedFormUtil.getValuesForComponent(messageId, componentId).toSet()
                for (i in spec.options.indices) {
                    if (i >= container.childCount) break
                    val row = container.getChildAt(i) as LinearLayout
                    val cb = row.getChildAt(row.childCount - 1) as CheckBox
                    suppressRadioCb = true
                    cb.isChecked = spec.options[i].value in selected
                    suppressRadioCb = false
                }
            }
        }

        private fun radioOptionPrimaryText(opt: EmbedRadioOptionSpec): String =
            opt.label.ifEmpty { opt.value }

        private fun radioOptionDisplayCharSeq(opt: EmbedRadioOptionSpec): CharSequence =
            formatEmbedRichText(
                if (opt.description.isNotEmpty() && opt.label.isNotEmpty()) {
                    "${opt.label}\n${opt.description}"
                } else {
                    radioOptionPrimaryText(opt)
                },
                theme,
            )
    }

    private inner class EmbedInputSlot {
        private val inputBackground = GradientDrawable()
        val edit = EditTextBoldCursor(context).apply {
            includeFontPadding = false
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(12), LayoutHelper.dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            setOnTouchListener { v, event ->
                if (v is EditTextBoldCursor && v.isEmbedScrollable()) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                false
            }
        }
        private var suppressWatch = false
        private var datePickerShowing = false
        private val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatch) return
                val mid = boundMessageId
                val cid = boundComponentId
                if (mid != 0L && cid.isNotEmpty()) {
                    EmbedFormUtil.setValue(mid, cid, s?.toString() ?: "")
                }
                edit.post { edit.scrollCursorIntoView() }
            }
        }
        private var boundMessageId = 0L
        private var boundComponentId = ""
        private var boundSpecKey = ""
        private var boundDateInput = false

        init {
            edit.addTextChangedListener(watcher)
        }

        fun bind(messageId: Long, componentId: String, spec: EmbedInputComponentSpec) {
            val specKey = "${spec.textarea}|${spec.numberInput}|${spec.dateInput}|${spec.disabled}|${spec.placeholder}|${spec.defaultValue}"
            val identityChanged =
                boundMessageId != messageId || boundComponentId != componentId || boundSpecKey != specKey
            boundMessageId = messageId
            boundComponentId = componentId
            boundSpecKey = specKey
            boundDateInput = spec.dateInput
            edit.tag = componentId

            inputBackground.cornerRadius = LayoutHelper.dpf(12f)
            inputBackground.setColor(theme.secondaryLight)
            inputBackground.setStroke(LayoutHelper.dp(1), theme.outline)
            edit.background = inputBackground
            edit.setHintTextColor(theme.onSurfaceVariant)
            edit.setTextColor(theme.onSurface)
            edit.hint = spec.placeholder.takeIf { it.isNotEmpty() }
            edit.isEnabled = !spec.disabled
            edit.alpha = if (spec.disabled) 0.5f else 1f
            edit.isCursorVisible = !spec.dateInput
            edit.showSoftInputOnFocus = !spec.dateInput

            val targetHeight = if (spec.textarea) EMBED_TEXTAREA_HEIGHT else EMBED_INPUT_HEIGHT
            edit.setFixedSize(targetHeight)
            if (BuildConfig.DEBUG) {
                Log.d(
                    EMBED_INPUT_TAG,
                    "bind component=$componentId textarea=${spec.textarea} targetH=$targetHeight " +
                        "identityChanged=$identityChanged",
                )
            }
            if (spec.textarea) {
                edit.gravity = Gravity.TOP or Gravity.START
                edit.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                edit.setSingleLine(false)
                edit.minLines = 1
                edit.maxLines = Int.MAX_VALUE
                edit.scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
                edit.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                edit.setVerticalScrollEnabled(true)
                edit.setHorizontalScrollEnabled(false)
                edit.setAutoScrollToCursor(true)
            } else {
                edit.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                if (identityChanged) {
                    edit.inputType = if (spec.dateInput) {
                        InputType.TYPE_NULL
                    } else if (spec.numberInput) {
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    }
                }
                edit.setSingleLine(false)
                edit.minLines = 1
                edit.maxLines = 1
                if (spec.dateInput) {
                    edit.setVerticalScrollEnabled(false)
                    edit.setHorizontalScrollEnabled(false)
                    edit.setAutoScrollToCursor(false)
                } else {
                    edit.scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
                    edit.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    edit.setVerticalScrollEnabled(false)
                    edit.setHorizontalScrollEnabled(true)
                    edit.setAutoScrollToCursor(true)
                }
            }

            if (identityChanged) {
                if (spec.dateInput && !spec.disabled) {
                    edit.setOnClickListener { showDatePicker() }
                    edit.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                        if (BuildConfig.DEBUG) {
                            logEmbedInputFocus(componentId, hasFocus, targetHeight)
                        }
                        if (hasFocus) showDatePicker()
                    }
                } else {
                    edit.setOnClickListener(null)
                    edit.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                        if (BuildConfig.DEBUG) {
                            logEmbedInputFocus(componentId, hasFocus, targetHeight)
                        }
                    }
                }
            }

            if (identityChanged) {
                val stored = EmbedFormUtil.getValue(messageId, componentId)
                suppressWatch = true
                edit.setText(stored ?: spec.defaultValue)
                suppressWatch = false
                edit.scrollTo(0, 0)
                if (stored == null && spec.defaultValue.isNotEmpty()) {
                    EmbedFormUtil.setValue(messageId, componentId, spec.defaultValue)
                }
            }
        }

        private fun showDatePicker() {
            if (!boundDateInput || boundMessageId == 0L || boundComponentId.isEmpty() || datePickerShowing) return
            datePickerShowing = true
            val cal = calendarFromDateValue(edit.text?.toString()?.takeIf { it.isNotBlank() })
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    val value = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                    suppressWatch = true
                    edit.setText(value)
                    suppressWatch = false
                    EmbedFormUtil.setValue(boundMessageId, boundComponentId, value)
                    edit.clearFocus()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
            ).apply {
                setOnDismissListener { datePickerShowing = false }
                show()
            }
        }

        private fun calendarFromDateValue(value: String?): Calendar {
            val cal = Calendar.getInstance()
            val parts = value
                ?.take(10)
                ?.split('-', '/')
                ?.mapNotNull { it.toIntOrNull() }
            if (parts != null && parts.size == 3) {
                val y = parts[0]
                val m = parts[1]
                val d = parts[2]
                if (y in 1900..9999 && m in 1..12 && d in 1..31) {
                    cal.set(y, m - 1, d)
                }
            }
            return cal
        }
    }

    private fun logEmbedInputFocus(componentId: String, hasFocus: Boolean, targetHeight: Int) {
        Log.d(
            EMBED_INPUT_TAG,
            "focus component=$componentId hasFocus=$hasFocus targetH=$targetHeight " +
                "measuredH=${focusedEmbedInputHeight()} layoutH=${focusedEmbedInputLayoutHeight()}",
        )
    }

    private fun focusedEmbedInputHeight(): Int {
        for (slot in embedInputSlots) {
            if (slot.edit.isFocused) return slot.edit.measuredHeight
        }
        return -1
    }

    private fun focusedEmbedInputLayoutHeight(): Int {
        for (slot in embedInputSlots) {
            if (slot.edit.isFocused) return slot.edit.height
        }
        return -1
    }

    companion object {
        const val COMBINE_TIME_THRESHOLD = 2 * 60L
        private const val TAG = "ChatMessageCell"
        private const val EMBED_INPUT_TAG = "EmbedFormInput"
        private val ANONYMOUS_USER_ID = BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: 0L
        private val anonymousAvatarBitmaps = HashMap<Int, Bitmap>(2)

        private const val VIDEO_THUMB_TIMEOUT_MS = 8_000L
        private val VIDEO_THUMB_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val EMBED_INPUT_HEIGHT = LayoutHelper.dp(40)
        private val EMBED_TEXTAREA_HEIGHT = LayoutHelper.dp(80)
        private val BUBBLE_RIGHT_INSET = LayoutHelper.dp(28)
        private val TIME_GAP_LEFT = LayoutHelper.dp(6)
        private val TIME_GAP_RIGHT = LayoutHelper.dp(4)

        private val CALL_LOG_TOP_MARGIN = LayoutHelper.dp(4f)
        private val CALL_LOG_CARD_MARGIN_V = LayoutHelper.dp(0)
        private val CALL_LOG_CARD_MARGIN_H = LayoutHelper.dp(4f)
        private val CALL_LOG_INNER_PAD = LayoutHelper.dp(10f)
        private val CALL_LOG_TITLE_DESC_GAP = LayoutHelper.dp(6f)
        private val CALL_LOG_ICON_SIZE = LayoutHelper.dp(17f)
        private val CALL_LOG_ROW_GAP = LayoutHelper.dp(4f)
        private val CALL_LOG_ICON_TOP_BIAS = LayoutHelper.dp(2f)
        private val CALL_LOG_CALLBACK_TOP_PAD = LayoutHelper.dp(8f)
        private val CALL_LOG_CALLBACK_BOTTOM_PAD = LayoutHelper.dp(8f)
        private val CALL_LOG_CORNER = LayoutHelper.dp(10f).toFloat()
        private val CALL_LOG_ICON_SLOT = CALL_LOG_ICON_SIZE + CALL_LOG_ROW_GAP

        private val AVATAR_SIZE = LayoutHelper.dp(40)  
        private val PAD_H = LayoutHelper.dp(6)          
        private val PAD_V = LayoutHelper.dp(10)         
        private val PAD_BOTTOM = LayoutHelper.dp(6)    
        private val COMBINE_PAD_V = LayoutHelper.dp(1)
        private val PIN_PAD_H = LayoutHelper.dp(4)
        private val GAP_AVATAR = LayoutHelper.dp(12)   
        private val MENTION_BAR_WIDTH = LayoutHelper.dp(2)
        private val MENTION_BAR_PAINT = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = 0xFFF3E65A.toInt()
        }
        private val MENTION_BG_PAINT = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val GAP_V_INNER = LayoutHelper.dp(6)
        private val ROLE_ICON_SIZE = LayoutHelper.dp(20f)
        private val ROLE_ICON_GAP = LayoutHelper.dp(4f)
        private val LINK_INVITE_V_MARGIN = LayoutHelper.dp(12) 
        private val MEDIA_RADIUS = LayoutHelper.dp(12).toFloat()
        private val OGP_RADIUS = LayoutHelper.dp(8).toFloat()
        private const val OGP_MAX_CHARS = 200
        private val PLAY_BTN_SIZE = LayoutHelper.dp(48).toFloat()
        private val REPLY_AVATAR_SIZE = LayoutHelper.dp(16)
        private val REPLY_H_GAP = LayoutHelper.dp(4)
        private val REPLY_ROW_HEIGHT = LayoutHelper.dp(20)
        private val REPLY_V_GAP = LayoutHelper.dp(2)
        private val CONNECTOR_RADIUS = LayoutHelper.dpf(6f)
        private val CONNECTOR_STROKE = LayoutHelper.dpf(1.5f)
        private val CONNECTOR_GAP = LayoutHelper.dp(4)
        private val FILE_ICON_SIZE = LayoutHelper.dp(30)
        private val FILE_ICON_GAP = LayoutHelper.dp(6)
        private val FILE_ROW_H_PAD = LayoutHelper.dp(10)
        private val FILE_ROW_V_PAD = LayoutHelper.dp(6)
        private val FILE_ROW_RADIUS = LayoutHelper.dpf(6f)
        private val FILE_ROW_MIN_HEIGHT = LayoutHelper.dp(50)

        private val AUDIO_PILL_HEIGHT = LayoutHelper.dp(42)
        private val AUDIO_PILL_PAD = LayoutHelper.dp(6)
        private val AUDIO_PLAY_BTN_SIZE = LayoutHelper.dp(30)
        private val AUDIO_PLAY_ICON_SIZE = LayoutHelper.dp(14)
        private val AUDIO_CONTENT_H_PAD = LayoutHelper.dp(10)
        private val AUDIO_TIME_GAP = LayoutHelper.dp(8)
        private val AUDIO_WAVE_BAR_COUNT = 18
        private val AUDIO_WAVE_BAR_WIDTH = LayoutHelper.dp(2)
        private val AUDIO_WAVE_BAR_GAP = LayoutHelper.dp(3)
        private val AUDIO_WAVE_MIN_HEIGHT = LayoutHelper.dp(4)
        private val AUDIO_WAVE_MAX_HEIGHT = LayoutHelper.dp(18)
        private val AUDIO_WAVE_WIDTH = AUDIO_WAVE_BAR_COUNT * AUDIO_WAVE_BAR_WIDTH +
            (AUDIO_WAVE_BAR_COUNT - 1) * AUDIO_WAVE_BAR_GAP
        private val AUDIO_WAVE_STATIC_AMP = floatArrayOf(
            0.2f, 0.45f, 0.8f, 0.55f, 0.3f, 0.7f, 0.95f, 0.55f,
            0.35f, 0.6f, 0.85f, 0.5f, 0.3f, 0.65f, 0.9f, 0.5f, 0.3f, 0.5f
        )
        private val AUDIO_BG_COLOR = 0xCC4E5057.toInt()
        private val AUDIO_PLAY_BTN_COLOR = 0xFF7C5CFA.toInt()

        private val AUDIO_BG_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val AUDIO_PLAY_BG_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val AUDIO_PLAY_ICON_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }

        private val AUDIO_SPINNER_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = LayoutHelper.dpf(2f)
            strokeCap = Paint.Cap.ROUND
        }

        private val AUDIO_WAVE_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE0E0E0.toInt()
            style = Paint.Style.FILL
        }

        private val AUDIO_TIME_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = LayoutHelper.dpf(13f)
            isFakeBoldText = true
        }
        private val FORWARD_ICON_SIZE = LayoutHelper.dp(14)
        private val FORWARD_ICON_GAP = LayoutHelper.dp(4).toFloat()

        private val SENDING_ICON_SIZE = LayoutHelper.dp(12)
        private val SENDING_STROKE_W = LayoutHelper.dpf(1.5f)

        private val SENDING_CIRCLE_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = SENDING_STROKE_W
        }

        private val SENDING_HAND_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = SENDING_STROKE_W
            strokeCap = Paint.Cap.ROUND
            color = 0xFF8B8D93.toInt()
        }

        private const val FORWARD_TEXT = "Forwarded"
        private const val EDITED_TEXT = "(edited)"
        private const val ERROR_TEXT = "Unable to send message"

        private val REFERENCE_SENDER_CLAN_NICK_REGEX = Regex("\"message_sender_clan_nick\"\\s*:\\s*\"([^\"]*?)\"")
        private val REFERENCE_SENDER_REGEX = Regex("\"message_sender_display_name\"\\s*:\\s*\"([^\"]*?)\"")
        private val REFERENCE_SENDER_USERNAME_REGEX = Regex("\"message_sender_username\"\\s*:\\s*\"([^\"]*?)\"")
        private val REFERENCE_CONTENT_REGEX = Regex("\"references\".*?\"content\"\\s*:\\s*\"(.*?)(?<!\\\\)\"")
        private val REFERENCE_REF_ID_REGEX = Regex("\"message_ref_id\"\\s*:\\s*\"?(\\d+)\"?")
        private val REFERENCE_SENDER_ID_REGEX = Regex("\"message_sender_id\"\\s*:\\s*\"?(\\d+)\"?")
        private val REFERENCE_AVATAR_REGEX = Regex("\"(?:mesages_sender_avatar|message_sender_avatar)\"\\s*:\\s*\"([^\"]+)\"")

        private val SPINNER_RADIUS = LayoutHelper.dp(14).toFloat()
        private val SPINNER_STROKE = LayoutHelper.dpf(2.5f)
        private val SPINNER_OVERLAY_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x55000000
        }
        private val SPINNER_ARC_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = SPINNER_STROKE
            strokeCap = Paint.Cap.ROUND
        }

        private val GRID_GAP = LayoutHelper.dp(2).toFloat()
        private val BADGE_PAD = LayoutHelper.dp(6).toFloat()
        private val BADGE_MARGIN = LayoutHelper.dp(6).toFloat()

        private val GRID_OVERLAY_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val GRID_COUNT_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = LayoutHelper.dp(20).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val PLAY_BG_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66000000.toInt()
            style = Paint.Style.FILL
        }

        private val VIDEO_PLACEHOLDER_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2A2D33.toInt()
            style = Paint.Style.FILL
        }

        private val PLAY_ICON_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }

        private val DURATION_BG_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA000000.toInt()
            style = Paint.Style.FILL
        }

        private val DURATION_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = LayoutHelper.dpf(12f)
        }

        private val GIF_BADGE_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = LayoutHelper.dpf(12f)
            isFakeBoldText = true
        }

        private val FORWARD_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8B8D93.toInt()
            textSize = LayoutHelper.dpf(13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val FORWARD_ARROW_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8B8D93.toInt()
            style = Paint.Style.STROKE
            strokeWidth = LayoutHelper.dpf(1.5f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val ERROR_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD30E0E.toInt()
            textSize = LayoutHelper.dpf(12f)
        }

        private val REPLY_NAME_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF06D6A0.toInt()
            textSize = LayoutHelper.dpf(12f)
        }

        private val REPLY_CONTENT_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8B8D93.toInt()
            textSize = LayoutHelper.dpf(12f)
        }

        private val DELETED_REPLY_TEXT_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8B8D93.toInt()
            textSize = LayoutHelper.dpf(12f)
            typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.ITALIC)
        }

        private val HIGHLIGHT_BG_PAINT = Paint()
        private const val HIGHLIGHT_DECAY_STEP = 16f / 2000f

        private val REPLY_CONNECTOR_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5C5E66.toInt()
            style = Paint.Style.STROKE
            strokeWidth = CONNECTOR_STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val REACTION_CHIP_H = LayoutHelper.dp(30)
        private val REACTION_EMOJI_SIZE = LayoutHelper.dp(18)
        private val REACTION_CHIP_PAD = LayoutHelper.dp(2)
        private val REACTION_CHIP_RADIUS = LayoutHelper.dpf(5f)
        private val REACTION_GAP = LayoutHelper.dp(6)
        private val REACTION_EMOJI_MR = LayoutHelper.dp(2)
        private val REACTION_ADD_SIZE = LayoutHelper.dp(20)
        private val REACTION_TOP_PAD = LayoutHelper.dp(6)

        private val REACTION_COUNT_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val REACTION_BG_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val REACTION_BORDER_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LayoutHelper.dpf(1f)
        }
        private const val EMOJI_PLACEHOLDER_COLOR = 0x1A000000
        private val EMOJI_PLACEHOLDER_RADIUS = LayoutHelper.dpf(4f)

        private val EMBED_COLOR_BAR_W = LayoutHelper.dp(4)
        private val EMBED_PAD = LayoutHelper.dp(10)
        private val EMBED_RADIUS = LayoutHelper.dpf(4f)
        private val EMBED_GAP = LayoutHelper.dp(6)
        private val EMBED_THUMB_SIZE = LayoutHelper.dp(50)
        private val EMBED_IMG_RADIUS = LayoutHelper.dpf(4f)
        private val EMBED_TOP_MARGIN = LayoutHelper.dp(4)

        private val FILE_CARD_BG_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val EMBED_BAR_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val EMBED_TITLE_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val EMBED_TITLE_LINK_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isUnderlineText = true
        }

        private val EMBED_DESC_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(13f)
        }

        private val EMBED_FIELD_NAME_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private val EMBED_FIELD_VALUE_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(13f)
        }

        private val EMBED_FOOTER_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(12f)
        }

        private val EMBED_AUTHOR_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.dpf(13f)
        }

        private fun formatDuration(seconds: Int): String {
            val m = seconds / 60
            val s = seconds % 60
            val sb = StringBuilder(6).append(m).append(':')
            if (s < 10) sb.append('0')
            sb.append(s)
            return sb.toString()
        }

    }
}
