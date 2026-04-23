package com.mezon.mobile.home.chat

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.Spannable
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.view.MotionEvent
import com.mezon.mobile.BuildConfig
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
import com.mezon.mobile.util.getEmojiUrl
import com.mezon.mobile.util.MentionColors
import com.mezon.mobile.util.EmbedData
import com.mezon.mobile.util.OgpData
import com.mezon.mobile.util.formatRelativeTime
import com.mezon.mobile.util.buildPlainTextWithHeadings
import com.mezon.mobile.util.isRawMessage
import com.mezon.mobile.util.parseEmbedData
import com.mezon.mobile.util.parseContentPreview
import com.mezon.mobile.util.parseContentText
import com.mezon.mobile.util.parseContentToSpannable
import com.mezon.mobile.util.parseOgpData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.min

class ChatMessageCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {
    var hasMentionHighlight: Boolean = false
    private var highlightProgress = 0f

    var messageEntity: MessageEntity? = null
        private set
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
    private var ephemeralLayout: StaticLayout? = null
    private var errorLayout: StaticLayout? = null
    private var hasReply = false
    private var parsedContent: String = ""
    private var timeText: String = ""
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

    private var embedData: EmbedData? = null
    private var embedTitleLayout: StaticLayout? = null
    private var embedDescLayout: StaticLayout? = null
    private var embedAuthorLayout: StaticLayout? = null
    private var embedFieldLayouts = emptyList<Pair<StaticLayout?, StaticLayout?>>()
    private var embedFooterLayout: StaticLayout? = null
    private val embedImage = ImageReceiver(this)
    private val embedThumbImage = ImageReceiver(this)
    private var embedImageW = 0
    private var embedImageH = 0
    private var embedBlockLeft = 0f
    private var embedBlockTop = 0f
    private var embedBlockRight = 0f
    private var embedBlockBottom = 0f
    private var pressedOnEmbed = false
    private var cachedEmbedTitleW = 0f
    private var cachedEmbedDescW = 0f

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
    private var reactionIsMyFlags: BooleanArray = BooleanArray(0)
    private var reactionRowHeight = 0
    private val reactionChipRect = RectF()
    private var reactionEmojiBitmaps: Array<android.graphics.Bitmap?> = emptyArray()
    private var reactionEmojiCancellables: Array<MezonImageLoader.Cancellable?> = emptyArray()
    private var reactionAddBounds = RectF()
    private var reactionAddIcon: android.graphics.drawable.Drawable? = null
    var currentUserId: Long = 0L

    private var currentContentPaint = theme.chatContentPaint
    private var currentTimePaint = theme.chatTimePaint
    private val senderPaint get() = theme.chatSenderPaint


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
        embedImage.onAttachedToWindow()
        embedThumbImage.onAttachedToWindow()
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
        embedImage.onDetachedFromWindow()
        embedThumbImage.onDetachedFromWindow()
        avatarCancellable?.cancel()
        avatarCancellable = null
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
        ephemeralLayout = null
        errorLayout = null
        ogpTitleLayout = null
        ogpDescLayout = null
        ogpData = null
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
        embedData = null
        embedTitleLayout = null
        embedDescLayout = null
        embedAuthorLayout = null
        embedFieldLayouts = emptyList()
        embedFooterLayout = null
        embedImageW = 0
        embedImageH = 0
        embedImage.recycle()
        embedThumbImage.recycle()
        drawPhotoImage = false
        drawFileAttachment = false
        drawForwardHeader = false
        drawEdited = false
        drawEphemeral = false
        drawError = false
        drawSending = false
        parsedContent = ""
        avatarCancellable?.cancel()
        avatarCancellable = null
        currentAvatarUrl = null
        avatarLoadStartTime = 0L
        avatarFallbackVisible = false
        avatarDrawable.setDrawableByInfo(true)
        videoThumbJob?.cancel()
        videoThumbJob = null
        mediaGridCount = 0
        mediaGridTotalH = 0
        gridExtraCount = 0
        extraPhotoImages.forEach { it.recycle() }
        lastBoundId = 0L
        lastBoundContentHash = 0
        lastBoundCombined = false
        cachedMeasuredWidth = 0
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
                msg.updateTimeSeconds.hashCode() xor (if (msg.hideEditted) 2 else 0)
            if (msg.id == lastBoundId && contentHash == lastBoundContentHash && isCombined == lastBoundCombined) {
                return false
            }
            lastBoundId = msg.id
            lastBoundContentHash = contentHash
            lastBoundCombined = isCombined

            if (newMsg != null) messageEntity = newMsg
            parsedContent = parseContentText(msg.content)
            timeText = formatRelativeTime(msg.timestampSeconds)
            drawPhotoImage = msg.hasMedia
            val isAudioAtt = msg.isAudioAttachment && !msg.hasMedia
            drawAudioAttachment = isAudioAtt
            drawFileAttachment = msg.isFileAttachment && !msg.hasMedia && !isAudioAtt
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
                val displayName = if (isAnon) "Anonymous" else msg.senderName
                avatarDrawable.setInfo(msg.senderId, displayName)
                if (isAnon) loadAnonymousAvatar() else loadAvatar(msg.senderAvatar)
            }
            if (drawPhotoImage) loadPhotoImage(msg)
            if (drawPhotoImage) {
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
            val newContent = parseContentText(msg.content)
            if (newContent != parsedContent) {
                parsedContent = newContent
                rebuildLayout = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_SEND_STATE) != 0) {
            val prevError = drawError
            drawSending = msg.isSending
            drawError = msg.isError
            Log.d(
                TAG,
                "update SEND_STATE id=${msg.id} sendState=${msg.sendState} drawSending=$drawSending drawPhotoImage=$drawPhotoImage"
            )
            if (drawError && !prevError) {
                rebuildLayout = true
            } else {
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_NAME) != 0) {
            if (messageEntity?.senderName != msg.senderName) {
                rebuildLayout = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_AVATAR) != 0) {
            if (!isCombined && messageEntity?.senderAvatar != msg.senderAvatar) {
                val isAnon = msg.senderId == ANONYMOUS_USER_ID
                val displayName = if (isAnon) "Anonymous" else msg.senderName
                avatarDrawable.setInfo(msg.senderId, displayName)
                if (isAnon) loadAnonymousAvatar() else loadAvatar(msg.senderAvatar)
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_REACTIONS) != 0) {
            rebuildLayout = true
        }

        if (newMsg != null) messageEntity = newMsg

        if (rebuildLayout) {
            val m = messageEntity ?: return false
            timeText = formatRelativeTime(m.timestampSeconds)
            drawPhotoImage = m.hasMedia
            val isAudioAtt = m.isAudioAttachment && !m.hasMedia
            drawAudioAttachment = isAudioAtt
            drawFileAttachment = m.isFileAttachment && !m.hasMedia && !isAudioAtt
            drawForwardHeader = m.isForwarded
            drawEdited = m.isEdited && !m.hideEditted
            drawEphemeral = m.isEphemeral
            drawError = m.isError
            drawSending = m.isSending
            updateColors(m)
            buildLayouts(m)
            requestLayout()
            invalidate()
            return true
        }
        if (needInvalidate) {
            invalidate()
        }
        return false
    }

    private fun computePhotoSize(msg: MessageEntity) {
        val screenW = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val isStickerMsg = msg.messageType == MessageEntity.TYPE_GIF &&
            (msg.attachmentFiletype.equals("sticker", true) || msg.attachmentUrl.contains("/stickers/"))
        val rawMaxW = if (isStickerMsg) LayoutHelper.dp(160) else (screenW * 0.65f).toInt()
        val maxW = if (isInPinMode) rawMaxW.coerceAtMost(maxBubbleWidth()) else rawMaxW
        val maxH = maxW + LayoutHelper.dp(100)

        var imgW = msg.attachmentWidth
        var imgH = msg.attachmentHeight
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

    private val videoThumbScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var videoThumbJob: Job? = null

    private fun loadPhotoImage(msg: MessageEntity) {
        val allMedia = msg.allImageAttachments
        mediaGridCount = allMedia.size.coerceAtMost(4)

        if (mediaGridCount == 0) return

        val allReceivers = arrayOf(photoImage, *extraPhotoImages)
        for (i in 0 until 4) {
            if (i < mediaGridCount) {
                val att = allMedia[i]
                calculateProxySize(att.width, att.height)
                val pw = proxySizeW
                val ph = proxySizeH
                val isAnimated = att.filetype.contains("gif", true) ||
                    att.url.contains("tenor.com", true)
                allReceivers[i].setRoundRadius(MEDIA_RADIUS.toInt())
                allReceivers[i].setRequestedSize(pw, ph)
                val isLocalUri = att.url.startsWith("content://") || att.url.startsWith("file://")
                val isVideo = att.filetype.startsWith("video/")
                if (isLocalUri && isVideo) {
                    allReceivers[i].recycle()
                    if (i == 0) loadLocalVideoThumbnail(att.url)
                } else if (isLocalUri) {
                    allReceivers[i].setLocalUri(android.net.Uri.parse(att.url), context)
                } else if (isAnimated) {
                    allReceivers[i].setImage(att.url, att.thumb.ifEmpty { null }, context)
                } else if (att.filetype.startsWith("video/")) {
                    val thumb = att.thumb.ifEmpty { null }
                    if (thumb != null) {
                        allReceivers[i].setImage(createImgproxyUrl(thumb, pw, ph, "fill"), null, context)
                    } else if (i == 0) {
                        loadVideoThumbnail(att.url)
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

        gridExtraCount = if (allMedia.size > 4) allMedia.size - 3 else 0
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

    private var proxySizeW = 0
    private var proxySizeH = 0
    private var spinnerAngle = 0f
    private val spinnerArcRect = RectF()

    private fun calculateProxySize(origW: Int, origH: Int) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val imgW = if (origW > 0) origW else 500
        val imgH = if (origH > 0) origH else 500
        val screenRatio = screenW.toFloat() / screenH
        val imgRatio = imgW.toFloat() / imgH
        if (imgRatio > screenRatio) {
            proxySizeW = (screenW * 0.9f).toInt()
            proxySizeH = (proxySizeW / imgRatio).toInt()
        } else {
            proxySizeH = (screenH * 0.9f).toInt()
            proxySizeW = (proxySizeH * imgRatio).toInt()
        }
        proxySizeW = proxySizeW.coerceIn(200, 1200)
        proxySizeH = proxySizeH.coerceIn(200, 1200)
    }

    @Suppress("deprecation")
    private fun loadVideoThumbnail(videoUrl: String) {
        videoThumbJob?.cancel()
        videoThumbJob = videoThumbScope.launch {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(videoUrl, HashMap<String, String>())
                val frame = retriever.getFrameAtTime(100_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                if (frame != null) {
                    withContext(Dispatchers.Main) {
                        photoImage.setBitmapDirectly(frame)
                        invalidate()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadLocalVideoThumbnail(localUrl: String) {
        videoThumbJob?.cancel()
        videoThumbJob = videoThumbScope.launch {
            try {
                val uri = android.net.Uri.parse(localUrl)
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val frame = retriever.getFrameAtTime(100_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                if (frame != null) {
                    withContext(Dispatchers.Main) {
                        photoImage.setBitmapDirectly(frame)
                        invalidate()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateColors(msg: MessageEntity) {
        currentContentPaint = if (msg.code == MessageEntity.CODE_MESSAGE_BUZZ) theme.chatBuzzTextPaint
            else theme.chatContentPaint
        currentTimePaint = theme.chatTimePaint
    }

    private fun maxBubbleWidth(): Int {
        val w = if (measuredWidth > 0) measuredWidth else resources.displayMetrics.widthPixels
        if (isInPinMode) return w - PIN_PAD_H * 2
        return w - PAD_H - AVATAR_SIZE - GAP_AVATAR - LayoutHelper.dp(28)
    }

    private fun buildLayouts(msg: MessageEntity) {
        val bubbleMaxW = maxBubbleWidth()
        // No bubble padding — content starts right after avatar (flat style like RN)
        val bubbleWidth = if (drawPhotoImage) photoWidth else bubbleMaxW
        if (bubbleWidth <= 0) return

        val textWidth = if (drawPhotoImage) photoWidth else bubbleWidth

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

        val hasText = parsedContent.isNotBlank() && parsedContent != "[file]" && parsedContent != "[embed]"
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
            val layout = StaticLayout.Builder.obtain(charSeq, 0, charSeq.length, currentContentPaint, textWidth.coerceAtLeast(1))
                .setLineSpacing(LayoutHelper.dpf(2f), 1f)
                .build()
            val spannedText = charSeq as? Spanned
            if (spannedText != null) {
                val codeFenceSpans = spannedText.getSpans(0, spannedText.length, CodeFenceSpan::class.java)
                for (span in codeFenceSpans) {
                    val spanStart = spannedText.getSpanStart(span)
                    val spanEnd = spannedText.getSpanEnd(span)
                    span.spanFirstLine = layout.getLineForOffset(spanStart)
                    span.spanLastLine = layout.getLineForOffset((spanEnd - 1).coerceAtLeast(spanStart))
                }
            }
            layout
        } else null

        senderLayout = if (!isCombined) {
            val s = if (msg.senderId == ANONYMOUS_USER_ID) "Anonymous" else msg.senderName
            val senderMaxW = (bubbleMaxW * 0.60f).toInt().coerceAtLeast(1) 
            StaticLayout.Builder.obtain(s, 0, s.length, senderPaint, senderMaxW)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        } else null

        buildReplyLayouts(textWidth)
        buildFileLayouts(msg, textWidth)
        buildAudioLayouts(msg)
        buildEphemeralLayout(msg, textWidth)
        buildErrorLayout(msg, textWidth)

        ogpData = if (msg.content.contains("\"mk\"") && msg.content.contains("lk_ogp")) {
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

        embedData = if (msg.content.contains("\"embed\"")) {
            parseEmbedData(msg.content)
        } else null
        buildEmbedLayouts(textWidth)

        durationLayout = if (msg.messageType == MessageEntity.TYPE_VIDEO && msg.attachmentDuration > 0) {
            val dur = formatDuration(msg.attachmentDuration)
            StaticLayout.Builder.obtain(dur, 0, dur.length, DURATION_PAINT, LayoutHelper.dp(100))
                .setMaxLines(1)
                .build()
        } else null

        val hasCodeFence = contentLayout?.text?.let { cs ->
            cs is android.text.Spanned && cs.getSpans(0, cs.length, com.mezon.mobile.home.chat.CodeFenceSpan::class.java).isNotEmpty()
        } == true

        cachedContentW = if (hasCodeFence) textWidth.toFloat() else contentLayout?.let { maxLineWidth(it) } ?: 0f
        cachedSenderW = senderLayout?.let { maxLineWidth(it) } ?: 0f
        cachedTimeW = timeLayout?.let { it.getLineWidth(0) } ?: 0f
        cachedReplyNameW = replyNameLayout?.let { maxLineWidth(it) } ?: 0f
        cachedReplyTextW = replyTextLayout?.let { maxLineWidth(it) } ?: 0f
        cachedOgpTitleW = ogpTitleLayout?.let { maxLineWidth(it) } ?: 0f
        cachedOgpDescW = ogpDescLayout?.let { maxLineWidth(it) } ?: 0f
        cachedForwardW = forwardLayout?.let { maxLineWidth(it) + FORWARD_ICON_SIZE + FORWARD_ICON_GAP } ?: 0f
        cachedFileNameW = fileNameLayout?.let { maxLineWidth(it) } ?: 0f
        cachedFileSizeW = fileSizeLayout?.let { maxLineWidth(it) } ?: 0f
        cachedEphW = ephemeralLayout?.let { maxLineWidth(it) + EPHEMERAL_ICON_SIZE + GAP_V_INNER } ?: 0f

        buildReactionLayouts(msg, textWidth)

        val replyW = if (hasReply) cachedReplyNameW + cachedReplyTextW + REPLY_AVATAR_SIZE + REPLY_H_GAP * 2 else 0f
        val ogpW = if (ogpData != null) maxOf(cachedOgpTitleW, cachedOgpDescW, ogpImageW.toFloat()) else 0f
        val fileW = if (drawFileAttachment) fileRowWidth.toFloat() else 0f
        val audioW = if (drawAudioAttachment) audioPillWidth.toFloat() else 0f
        val embedW = if (embedData != null) (bubbleMaxW).toFloat() else 0f
        cachedInnerWidth = if (drawPhotoImage) {
            photoWidth
        } else if (hasCodeFence) {
            bubbleMaxW
        } else {
            val allW = maxOf(cachedSenderW, cachedContentW, cachedTimeW, replyW, ogpW, cachedForwardW, fileW, audioW, cachedEphW, embedW)
            allW.toInt().coerceAtMost(bubbleMaxW)
        }

        measuredCellHeight = computeHeight(msg)
        updatedContent = true
    }

    private fun computeHeight(msg: MessageEntity): Int {
        val topPad = if (isCombined) COMBINE_PAD_V else PAD_V
        var h = topPad + PAD_BOTTOM

        if (hasReply) {
            h += REPLY_ROW_HEIGHT + REPLY_V_GAP
        }

        forwardLayout?.let { h += it.height + GAP_V_INNER }
        senderLayout?.let { h += it.height + GAP_V_INNER }

        contentLayout?.let { h += it.height + GAP_V_INNER }

        if (drawPhotoImage) {
            val imgH = if (mediaGridCount > 1) mediaGridTotalH else photoHeight
            h += imgH + GAP_V_INNER
        }

        if (drawFileAttachment) {
            val textH = (fileNameLayout?.height ?: 0) + (fileSizeLayout?.height ?: 0)
            val innerH = maxOf(FILE_ICON_SIZE, textH)
            h += FILE_ROW_V_PAD * 2 + maxOf(innerH, FILE_ROW_MIN_HEIGHT - FILE_ROW_V_PAD * 2) + GAP_V_INNER
        }

        if (drawAudioAttachment) {
            h += AUDIO_PILL_HEIGHT + GAP_V_INNER
        }

        if (ogpData != null) {
            h += GAP_V_INNER
            ogpTitleLayout?.let { h += it.height + GAP_V_INNER }
            ogpDescLayout?.let { h += it.height + GAP_V_INNER }
            h += ogpImageH + GAP_V_INNER
        }

        if (embedData != null) {
            h += computeEmbedHeight()
        }

        if (drawEphemeral) {
            ephemeralLayout?.let { h += it.height + GAP_V_INNER }
        }

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
            fileIconDrawable = null
            fileRowWidth = 0
            return
        }
        val cardInnerW = ((textWidth * 0.8f).toInt()).coerceAtLeast(FILE_ICON_SIZE + FILE_ICON_GAP + 1)
        val fileTextW = (cardInnerW - FILE_ROW_H_PAD * 2 - FILE_ICON_SIZE - FILE_ICON_GAP).coerceAtLeast(1)
        val name = msg.attachmentFilename.ifEmpty { "File" }
        fileNameLayout = StaticLayout.Builder.obtain(name, 0, name.length, theme.chatFileNamePaint, fileTextW)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val sizeText = FileUtils.formatFileSize(msg.attachmentSize.toLong())
        fileSizeLayout = StaticLayout.Builder.obtain(sizeText, 0, sizeText.length, currentTimePaint, fileTextW)
            .setMaxLines(1)
            .build()

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
        return "%d:%02d".format(minutes, seconds)
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
            return
        }
        val text = EPHEMERAL_TEXT
        ephemeralLayout = StaticLayout.Builder.obtain(text, 0, text.length, EPHEMERAL_PAINT, textWidth.coerceAtLeast(1))
            .setMaxLines(1)
            .build()
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

    private fun applyEmbedPaintColors() {
        EMBED_TITLE_PAINT.color = theme.onSurface
        EMBED_TITLE_LINK_PAINT.color = theme.textLink
        EMBED_DESC_PAINT.color = theme.onSurfaceVariant
        EMBED_FIELD_NAME_PAINT.color = theme.onSurface
        EMBED_FIELD_VALUE_PAINT.color = theme.onSurfaceVariant
        EMBED_FOOTER_PAINT.color = theme.onSurfaceVariant
        EMBED_AUTHOR_PAINT.color = theme.onSurface
    }

    private fun buildEmbedLayouts(textWidth: Int) {
        val data = embedData
        if (data == null) {
            embedTitleLayout = null
            embedDescLayout = null
            embedAuthorLayout = null
            embedFieldLayouts = emptyList()
            embedFooterLayout = null
            embedImageW = 0
            embedImageH = 0
            embedImage.setImage(null, null, context)
            embedThumbImage.setImage(null, null, context)
            return
        }

        applyEmbedPaintColors()

        val contentW = (textWidth - EMBED_COLOR_BAR_W - EMBED_PAD * 2).coerceAtLeast(1)
        val hasThumb = data.thumbnailUrl.isNotEmpty()
        val innerTextW = if (hasThumb) (contentW - EMBED_THUMB_SIZE - EMBED_GAP).coerceAtLeast(1) else contentW

        embedAuthorLayout = if (data.authorName.isNotEmpty()) {
            StaticLayout.Builder.obtain(data.authorName, 0, data.authorName.length, EMBED_AUTHOR_PAINT, innerTextW)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else null

        val titlePaint = if (data.url.isNotEmpty()) EMBED_TITLE_LINK_PAINT else EMBED_TITLE_PAINT
        embedTitleLayout = if (data.title.isNotEmpty()) {
            val cleanTitle = data.title.replace(Regex("[\\n\\r\\t]+"), " ").replace(Regex("\\s+"), " ").trim()
            StaticLayout.Builder.obtain(cleanTitle, 0, cleanTitle.length, titlePaint, innerTextW)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else null

        embedDescLayout = if (data.description.isNotEmpty()) {
            val cleanDesc = data.description.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            StaticLayout.Builder.obtain(cleanDesc, 0, cleanDesc.length, EMBED_DESC_PAINT, innerTextW)
                .setMaxLines(6)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(LayoutHelper.dpf(2f), 1f)
                .build()
        } else null

        embedFieldLayouts = data.fields.map { field ->
            val nameLay = if (field.name.isNotEmpty()) {
                StaticLayout.Builder.obtain(field.name, 0, field.name.length, EMBED_FIELD_NAME_PAINT, contentW)
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
            } else null
            val valLay = if (field.value.isNotEmpty()) {
                StaticLayout.Builder.obtain(field.value, 0, field.value.length, EMBED_FIELD_VALUE_PAINT, contentW)
                    .setMaxLines(3)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
            } else null
            nameLay to valLay
        }

        val footerParts = mutableListOf<String>()
        if (data.footerText.isNotEmpty()) footerParts.add(data.footerText)
        if (data.timestamp.isNotEmpty()) {
            try {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .parse(data.timestamp.replace("Z", "+0000").take(19))
                if (date != null) {
                    val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
                    footerParts.add(fmt.format(date))
                }
            } catch (_: Exception) {}
        }
        val footerStr = footerParts.joinToString(" • ")
        embedFooterLayout = if (footerStr.isNotEmpty()) {
            StaticLayout.Builder.obtain(footerStr, 0, footerStr.length, EMBED_FOOTER_PAINT, contentW)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else null

        if (data.imageUrl.isNotEmpty()) {
            val aspect = if (data.imageWidth > 0 && data.imageHeight > 0) {
                data.imageWidth.toFloat() / data.imageHeight
            } else 16f / 9f
            embedImageW = contentW
            embedImageH = (embedImageW / aspect).toInt().coerceIn(LayoutHelper.dp(60), LayoutHelper.dp(300))
            embedImage.setRoundRadius(EMBED_IMG_RADIUS.toInt())
            val proxyUrl = createImgproxyUrl(data.imageUrl, embedImageW, embedImageH, "fit")
            embedImage.setImage(proxyUrl, null, context)
        } else {
            embedImageW = 0
            embedImageH = 0
            embedImage.setImage(null, null, context)
        }

        if (hasThumb) {
            embedThumbImage.setRoundRadius(EMBED_IMG_RADIUS.toInt())
            val thumbProxy = createImgproxyUrl(data.thumbnailUrl, EMBED_THUMB_SIZE, EMBED_THUMB_SIZE, "fit")
            embedThumbImage.setImage(thumbProxy, null, context)
        } else {
            embedThumbImage.setImage(null, null, context)
        }

        cachedEmbedTitleW = embedTitleLayout?.let { maxLineWidth(it) } ?: 0f
        cachedEmbedDescW = embedDescLayout?.let { maxLineWidth(it) } ?: 0f
    }

    private fun computeEmbedHeight(): Int {
        if (embedData == null) return 0
        var h = EMBED_PAD * 2 + EMBED_TOP_MARGIN
        embedAuthorLayout?.let { h += it.height + EMBED_GAP }
        embedTitleLayout?.let { h += it.height + EMBED_GAP }
        embedDescLayout?.let { h += it.height + EMBED_GAP }
        for ((nameLay, valLay) in embedFieldLayouts) {
            nameLay?.let { h += it.height + LayoutHelper.dp(2) }
            valLay?.let { h += it.height + EMBED_GAP }
        }
        if (embedImageH > 0) h += embedImageH + EMBED_GAP
        embedFooterLayout?.let { h += it.height + EMBED_GAP }
        val thumbH = if (embedData?.thumbnailUrl?.isNotEmpty() == true) EMBED_THUMB_SIZE + EMBED_PAD else 0
        return maxOf(h, thumbH + EMBED_PAD * 2 + EMBED_TOP_MARGIN)
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
                replyContent = ""
                replySenderId = 0L
                replyHasAttachment = false
                return true
            }

            val refMatch = REFERENCE_SENDER_REGEX.find(content)
            val refContentMatch = REFERENCE_CONTENT_REGEX.find(content)
            replySenderName = refMatch?.groupValues?.getOrNull(1)
                ?.replace("\\\"", "\"") ?: ""
            val rawRefContent = refContentMatch?.groupValues?.getOrNull(1)
                ?.replace("\\n", " ")
                ?.replace("\\\"", "\"")
                ?: ""
            replyContent = parseContentPreview(rawRefContent).take(80)

            val senderIdMatch = REFERENCE_SENDER_ID_REGEX.find(content)
            replySenderId = senderIdMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            replyHasAttachment = content.contains("\"has_attachment\":true")

            val avatarMatch = REFERENCE_AVATAR_REGEX.find(content)
            replySenderAvatarUrl = avatarMatch?.groupValues?.getOrNull(1)?.replace("\\/", "/")

            replyAvatarDrawable.setInfo(replySenderId, replySenderName)
            loadReplyAvatar(replySenderAvatarUrl ?: "")
            replySenderName.isNotEmpty() || replyContent.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private var replySenderName = ""
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
                post { invalidate() }
            }, onError = {
                avatarDrawable.setDrawableByInfo(true)
                avatarFallbackVisible = true
                post { invalidate() }
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

    private fun checkAvatarFallbackTimeout() {
        if (!avatarFallbackVisible && !avatarDrawable.hasPhoto() && avatarLoadStartTime > 0) {
            if (System.currentTimeMillis() - avatarLoadStartTime > 3000L) {
                avatarDrawable.setDrawableByInfo(true)
                avatarFallbackVisible = true
            }
        }
    }

    private var cachedMeasuredWidth = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val msg = messageEntity
        if (msg != null && w > 0 && w != cachedMeasuredWidth) {
            cachedMeasuredWidth = w
            buildLayouts(msg)
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
    }

    private var pressedLink: ClickableSpan? = null
    private var pressedOnMedia = false
    private var pressedMediaIndex = 0
    private var pressedOnOgp = false
    private var pressedOnFile = false
    private var pressedOnAudio = false
    private var pressedOnAvatar = false
    private var pressedOnReply = false
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
                pressedReactionIndex = -1
                longPressHandled = false
                startX = x
                startY = y

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
                val data = ogpData
                if (data != null && x >= ogpBlockLeft && x <= ogpBlockRight && y >= ogpBlockTop && y <= ogpBlockBottom) {
                    pressedOnOgp = true
                    scheduleLongPress()
                    return true
                }
                val ed = embedData
                if (ed != null && x >= embedBlockLeft && x <= embedBlockRight && y >= embedBlockTop && y <= embedBlockBottom) {
                    pressedOnEmbed = true
                    scheduleLongPress()
                    return true
                }
                if (reactionGroups.isNotEmpty()) {
                    val contentLeft = if (isCombined) PAD_H + AVATAR_SIZE + GAP_AVATAR else PAD_H + AVATAR_SIZE + GAP_AVATAR
                    val topPad = if (isCombined) COMBINE_PAD_V else PAD_V
                    var reacBaseY = topPad.toFloat()
                    if (hasReply) reacBaseY += REPLY_ROW_HEIGHT + REPLY_V_GAP
                    forwardLayout?.let { reacBaseY += it.height + GAP_V_INNER }
                    senderLayout?.let { reacBaseY += it.height + GAP_V_INNER }
                    contentLayout?.let { reacBaseY += it.height + GAP_V_INNER }
                    if (drawPhotoImage) {
                        val imgH = if (mediaGridCount > 1) mediaGridTotalH else photoHeight
                        reacBaseY += imgH + GAP_V_INNER
                    }
                    if (drawFileAttachment) reacBaseY += FILE_ICON_SIZE + GAP_V_INNER
                    if (drawAudioAttachment) reacBaseY += AUDIO_PILL_HEIGHT + GAP_V_INNER
                    if (ogpData != null) {
                        reacBaseY += GAP_V_INNER
                        ogpTitleLayout?.let { reacBaseY += it.height + GAP_V_INNER }
                        ogpDescLayout?.let { reacBaseY += it.height + GAP_V_INNER }
                        reacBaseY += ogpImageH + GAP_V_INNER
                    }
                    if (embedData != null) reacBaseY += computeEmbedHeight()
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
                    for (i in reactionChipBounds.indices) {
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
                if (longPressScheduled) {
                    val dx = x - startX
                    val dy = y - startY
                    val slop = AndroidUtilities.touchSlop.toFloat()
                    if (dx * dx + dy * dy > slop * slop) {
                        cancelScheduledLongPress()
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
                    pressedReactionIndex = -1
                    return true
                }
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
                if (pressedOnEmbed) {
                    pressedOnEmbed = false
                    embedData?.let { if (it.url.isNotEmpty()) onLinkClicked(it.url) }
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
        } else {
            drawMessageBubble(canvas, msg)
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

                timeLayout?.let { time ->
                    val timeX = (contentLeft + cachedSenderW + LayoutHelper.dp(6)).toFloat()
                        .coerceAtMost((width - LayoutHelper.dp(4)).toFloat())
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
        } else if (!photoImage.hasMainImage()) {
            shimmerEffect.draw(canvas, imgX, yOff, imgX + photoWidth, yOff + photoHeight, 0f,
                theme.resolvedMode != com.mezon.mobile.ui.theme.ThemeMode.LIGHT)
            postInvalidateDelayed(32)
        }
    }

    private fun drawMessageBubble(canvas: Canvas, msg: MessageEntity) {
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

            timeLayout?.let { time ->
                val timeX = (contentLeft + cachedSenderW + LayoutHelper.dp(6)).toFloat()
                        .coerceAtMost((width - LayoutHelper.dp(4)).toFloat())
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

        contentLayout?.let {
            contentLayoutLeft = contentLeft
            contentLayoutTop = yOff.toInt()
            canvas.save()
            canvas.translate(contentLeft.toFloat(), yOff)
            it.draw(canvas)
            canvas.restore()
            yOff += it.height + GAP_V_INNER
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

        ogpData?.let { yOff = drawOgpBlock(canvas, contentLeft.toFloat(), yOff) + GAP_V_INNER }

        if (embedData != null) {
            yOff = drawEmbedCard(canvas, contentLeft.toFloat(), yOff)
        }

        if (drawEphemeral) {
            yOff = drawEphemeralIndicator(canvas, contentLeft.toFloat(), yOff)
        }

        if (reactionGroups.isNotEmpty()) {
            yOff += REACTION_TOP_PAD
            drawReactionRow(canvas, contentLeft.toFloat(), yOff)
            yOff += reactionRowHeight
        }

        // timeLayout is drawn on the same row as senderLayout (right side) — not at the bottom
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
        val cardW = fileRowWidth.toFloat()
        val textH = (fileNameLayout?.height ?: 0) + (fileSizeLayout?.height ?: 0)
        val innerH = maxOf(FILE_ICON_SIZE, textH)
        val cardH = (FILE_ROW_V_PAD * 2 + maxOf(innerH, FILE_ROW_MIN_HEIGHT - FILE_ROW_V_PAD * 2)).toFloat()

        fileBlockLeft = x
        fileBlockTop = y
        fileBlockRight = x + cardW
        fileBlockBottom = y + cardH

        EMBED_BG_PAINT.color = theme.secondaryLight
        fileRoundRect.set(x, y, x + cardW, y + cardH)
        canvas.drawRoundRect(fileRoundRect, FILE_ROW_RADIUS, FILE_ROW_RADIUS, EMBED_BG_PAINT)

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
        fileNameLayout?.let {
            canvas.save()
            canvas.translate(textX, textY)
            it.draw(canvas)
            canvas.restore()
            textY += it.height
        }
        fileSizeLayout?.let {
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
        val iconSize = EPHEMERAL_ICON_SIZE.toFloat()
        canvas.drawCircle(x + iconSize / 2, y + layout.height / 2f, iconSize / 2, EPHEMERAL_ICON_PAINT)
        canvas.save()
        canvas.translate(x + iconSize + GAP_V_INNER, y)
        layout.draw(canvas)
        canvas.restore()
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

    private fun drawEmbedCard(canvas: Canvas, left: Float, top: Float): Float {
        val data = embedData ?: return top
        applyEmbedPaintColors()
        val cardTop = top + EMBED_TOP_MARGIN
        val embedH = computeEmbedHeight() - EMBED_TOP_MARGIN
        val bubbleMaxW = maxBubbleWidth()
        val contentW = (bubbleMaxW - EMBED_COLOR_BAR_W - EMBED_PAD * 2).coerceAtLeast(1)
        val cardW = (EMBED_COLOR_BAR_W + EMBED_PAD * 2 + contentW).toFloat()

        embedBlockLeft = left
        embedBlockTop = cardTop
        embedBlockRight = left + cardW
        embedBlockBottom = cardTop + embedH

        EMBED_BG_PAINT.color = theme.surfaceVariant
        tmpRect.set(left, cardTop, left + cardW, cardTop + embedH)
        canvas.drawRoundRect(tmpRect, EMBED_RADIUS, EMBED_RADIUS, EMBED_BG_PAINT)

        val barColor = if (data.color != 0) data.color else theme.primary
        EMBED_BAR_PAINT.color = barColor
        tmpRect.set(left, cardTop, left + EMBED_COLOR_BAR_W, cardTop + embedH)
        canvas.drawRoundRect(tmpRect, EMBED_RADIUS / 2, EMBED_RADIUS / 2, EMBED_BAR_PAINT)

        val textLeft = left + EMBED_COLOR_BAR_W + EMBED_PAD
        var y = cardTop + EMBED_PAD.toFloat()

        if (data.thumbnailUrl.isNotEmpty()) {
            val thumbX = left + cardW - EMBED_PAD - EMBED_THUMB_SIZE
            val thumbY = y
            embedThumbImage.setImageCoords(thumbX, thumbY, EMBED_THUMB_SIZE.toFloat(), EMBED_THUMB_SIZE.toFloat())
            embedThumbImage.draw(canvas)
        }

        embedAuthorLayout?.let {
            canvas.save()
            canvas.translate(textLeft, y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + EMBED_GAP
        }

        embedTitleLayout?.let {
            canvas.save()
            canvas.translate(textLeft, y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + EMBED_GAP
        }

        embedDescLayout?.let {
            canvas.save()
            canvas.translate(textLeft, y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + EMBED_GAP
        }

        for ((nameLay, valLay) in embedFieldLayouts) {
            nameLay?.let {
                canvas.save()
                canvas.translate(textLeft, y)
                it.draw(canvas)
                canvas.restore()
                y += it.height + LayoutHelper.dp(2)
            }
            valLay?.let {
                canvas.save()
                canvas.translate(textLeft, y)
                it.draw(canvas)
                canvas.restore()
                y += it.height + EMBED_GAP
            }
        }

        if (embedImageW > 0 && embedImageH > 0) {
            embedImage.setImageCoords(textLeft, y, embedImageW.toFloat(), embedImageH.toFloat())
            embedImage.draw(canvas)
            if (!embedImage.hasMainImage()) {
                shimmerEffect.draw(canvas, textLeft, y, textLeft + embedImageW, y + embedImageH,
                    EMBED_IMG_RADIUS, theme.resolvedMode != com.mezon.mobile.ui.theme.ThemeMode.LIGHT)
                postInvalidateDelayed(32)
            }
            y += embedImageH + EMBED_GAP
        }

        embedFooterLayout?.let {
            canvas.save()
            canvas.translate(textLeft, y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + EMBED_GAP
        }

        return cardTop + embedH
    }

    private var gridExtraCount = 0

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
                    if (drawSending) {
                        drawAttachmentUploadSpinner(canvas, x, startY, cellW, cellH, MEDIA_RADIUS)
                    } else if (!allReceivers[i].hasMainImage()) {
                        shimmerEffect.draw(canvas, x, startY, x + cellW, startY + cellH, MEDIA_RADIUS, isDark)
                        needsShimmerRedraw = true
                    }
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
                if (drawSending) {
                    drawAttachmentUploadSpinner(canvas, startX, startY, leftW, leftH, MEDIA_RADIUS)
                } else if (!allReceivers[0].hasMainImage()) {
                    shimmerEffect.draw(canvas, startX, startY, startX + leftW, startY + leftH, MEDIA_RADIUS, isDark)
                    needsShimmerRedraw = true
                }

                val rx = startX + leftW + gap
                for (i in 1 until 3) {
                    val ry = startY + (i - 1) * (rightH + gap)
                    allReceivers[i].setImageCoords(rx, ry, rightW, rightH)
                    allReceivers[i].draw(canvas)
                    if (drawSending) {
                        drawAttachmentUploadSpinner(canvas, rx, ry, rightW, rightH, MEDIA_RADIUS)
                    } else if (!allReceivers[i].hasMainImage()) {
                        shimmerEffect.draw(canvas, rx, ry, rx + rightW, ry + rightH, MEDIA_RADIUS, isDark)
                        needsShimmerRedraw = true
                    }
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
                    if (drawSending) {
                        drawAttachmentUploadSpinner(canvas, x, y, cellW, cellH, MEDIA_RADIUS)
                    } else if (!allReceivers[i].hasMainImage()) {
                        shimmerEffect.draw(canvas, x, y, x + cellW, y + cellH, MEDIA_RADIUS, isDark)
                        needsShimmerRedraw = true
                    }
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
        if (drawSending) {
            drawAttachmentUploadSpinner(canvas, imgX, imgY, photoWidth.toFloat(), photoHeight.toFloat(), MEDIA_RADIUS)
        } else if (!photoImage.hasMainImage()) {
            shimmerEffect.draw(canvas, imgX, imgY, imgX + photoWidth, imgY + photoHeight, MEDIA_RADIUS,
                theme.resolvedMode != com.mezon.mobile.ui.theme.ThemeMode.LIGHT)
            postInvalidateDelayed(32)
        }
        if (msg.messageType == MessageEntity.TYPE_VIDEO) {
            drawVideoPlayButton(canvas, imgX, imgY)
            durationLayout?.let { drawDurationBadge(canvas, it, imgX, imgY) }
        }
        if (msg.messageType == MessageEntity.TYPE_GIF) {
            drawGifBadge(canvas, imgX, imgY)
        }
    }

    private fun drawVideoPlayButton(canvas: Canvas, imgX: Float, imgY: Float) {
        val cx = imgX + photoWidth / 2f
        val cy = imgY + photoHeight / 2f
        val r = PLAY_BTN_SIZE / 2f
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
            reactionChipBounds.clear()
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
            reactionChipBounds.clear()
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

        val bitmaps = Array<android.graphics.Bitmap?>(groups.size) { null }
        val cancellables = Array<MezonImageLoader.Cancellable?>(groups.size) { null }
        val loader = MezonImageLoader.getInstance(context)
        var pendingLoads = 0

        for (i in groups.indices) {
            val url = getEmojiUrl(groups[i].emojiId.toString()) ?: continue
            val cached = loader.getBitmapFromMemory(url, REACTION_EMOJI_SIZE, REACTION_EMOJI_SIZE)
            if (cached != null) {
                bitmaps[i] = cached
                continue
            }
            pendingLoads++
            val idx = i
            cancellables[i] = loader.load(url, REACTION_EMOJI_SIZE, REACTION_EMOJI_SIZE,
                onSuccess = { bmp ->
                    bitmaps[idx] = bmp
                    reactionEmojiBitmaps = bitmaps
                    pendingLoads--
                    if (pendingLoads <= 0) invalidate()
                }, onError = { pendingLoads-- })
        }
        reactionEmojiBitmaps = bitmaps
        reactionEmojiCancellables = cancellables

        reactionChipBounds.clear()
        var x = 0f
        var y = 0f
        val availW = maxWidth.toFloat()
        for (i in groups.indices) {
            val countW = countLayouts[i]?.let { it.getLineWidth(0) } ?: 0f
            val chipW = REACTION_CHIP_PAD * 2 + REACTION_EMOJI_SIZE + REACTION_EMOJI_MR + countW
            if (x > 0 && x + chipW > availW) {
                x = 0f
                y += REACTION_CHIP_H + REACTION_GAP
            }
            reactionChipBounds.add(RectF(x, y, x + chipW, y + REACTION_CHIP_H))
            x += chipW + REACTION_GAP
        }

        val addChipW = REACTION_ADD_SIZE.toFloat()
        if (x > 0 && x + addChipW > availW) {
            x = 0f
            y += REACTION_CHIP_H + REACTION_GAP
        }
        reactionAddBounds = RectF(x, y, x + addChipW, y + REACTION_CHIP_H)

        reactionRowHeight = (y + REACTION_CHIP_H).toInt()
    }

    private fun drawReactionRow(canvas: Canvas, startX: Float, startY: Float) {
        val groups = reactionGroups
        val secondaryColor = theme.tertiary
        val myBg = theme.reactionBgColor
        val myBorder = theme.reactionBorderColor

        for (i in groups.indices) {
            val bounds = reactionChipBounds.getOrNull(i) ?: continue
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

    companion object {
        const val COMBINE_TIME_THRESHOLD = 2 * 60L
        private const val TAG = "ChatMessageCell"
        private val ANONYMOUS_USER_ID = BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: 0L
        private val anonymousAvatarBitmaps = HashMap<Int, Bitmap>(2)

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
        private val EPHEMERAL_ICON_SIZE = LayoutHelper.dp(12)

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
        private const val EPHEMERAL_TEXT = "Only visible to you"
        private const val ERROR_TEXT = "Unable to send message"

        private val REFERENCE_SENDER_REGEX = Regex("\"message_sender_display_name\"\\s*:\\s*\"([^\"]*?)\"")
        private val REFERENCE_CONTENT_REGEX = Regex("\"references\".*?\"content\"\\s*:\\s*\"(.*?)(?<!\\\\)\"")
        private val REFERENCE_REF_ID_REGEX = Regex("\"message_ref_id\"\\s*:\\s*\"?(\\d+)\"?")
        private val REFERENCE_SENDER_ID_REGEX = Regex("\"message_sender_id\"\\s*:\\s*\"?(\\d+)\"?")
        private val REFERENCE_AVATAR_REGEX = Regex("\"mesages_sender_avatar\"\\s*:\\s*\"([^\"]+)\"")

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

        private val EPHEMERAL_PAINT = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8B8D93.toInt()
            textSize = LayoutHelper.dpf(11f)
        }

        private val EPHEMERAL_ICON_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8B8D93.toInt()
            style = Paint.Style.FILL
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

        private val EMBED_BG_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
            return "%d:%02d".format(m, s)
        }

    }
}
