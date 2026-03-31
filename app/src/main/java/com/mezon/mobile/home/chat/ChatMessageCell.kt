package com.mezon.mobile.home.chat

import android.content.Intent
import android.content.Context
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
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.util.MentionColors
import com.mezon.mobile.util.OgpData
import com.mezon.mobile.util.formatRelativeTime
import com.mezon.mobile.util.isRawMessage
import com.mezon.mobile.util.parseContentPreview
import com.mezon.mobile.util.parseContentText
import com.mezon.mobile.util.parseContentToSpannable
import com.mezon.mobile.util.parseOgpData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import kotlin.math.min

class ChatMessageCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {
    var hasMentionHighlight: Boolean = false
    private var highlightProgress = 0f

    var messageEntity: MessageEntity? = null
        private set
    var isCombined: Boolean = false

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
    private var drawForwardHeader = false
    private var drawEdited = false
    private var drawEphemeral = false
    private var drawError = false
    private var drawSending = false
    private var fileIconDrawable: Drawable? = null

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
        avatarCancellable?.cancel()
        avatarCancellable = null
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
            drawFileAttachment = msg.isFileAttachment && !msg.hasMedia
            drawForwardHeader = msg.isForwarded
            drawEdited = msg.isEdited && !msg.hideEditted
            drawEphemeral = msg.isEphemeral
            drawError = msg.isError
            drawSending = msg.isSending
            hasReply = parseReply(msg)
            updateColors(msg)
            if (drawPhotoImage) computePhotoSize(msg)
            buildLayouts(msg)
            if (!isCombined) {
                avatarDrawable.setInfo(msg.senderId, msg.senderName)
                loadAvatar(msg.senderAvatar)
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
                avatarDrawable.setInfo(msg.senderId, msg.senderName)
                loadAvatar(msg.senderAvatar)
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_REACTIONS) != 0) {
            needInvalidate = true
        }

        if (newMsg != null) messageEntity = newMsg

        if (rebuildLayout) {
            val m = messageEntity ?: return false
            timeText = formatRelativeTime(m.timestampSeconds)
            drawPhotoImage = m.hasMedia
            drawFileAttachment = m.isFileAttachment && !m.hasMedia
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
        val maxW = if (isStickerMsg) LayoutHelper.dp(160) else (screenW * 0.65f).toInt()
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
                if (isLocalUri) {
                    allReceivers[i].setLocalUri(android.net.Uri.parse(att.url), context)
                } else if (allReceivers[i].hasMainImage()) {
                    // Keep existing local preview — don't reload CDN to avoid flash
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
            2 -> photoHeight / 2
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

    private fun updateColors(msg: MessageEntity) {
        currentContentPaint = theme.chatContentPaint
        currentTimePaint = theme.chatTimePaint
    }

    private fun maxBubbleWidth(): Int {
        val w = if (measuredWidth > 0) measuredWidth else resources.displayMetrics.widthPixels
        // Subtract left-side space (avatar + paddings) + right padding (RN paddingRight:28dp)
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

        val hasText = parsedContent.isNotBlank() && parsedContent != "[file]"
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
                parsedContent
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
            val s = msg.senderName
            val senderMaxW = (bubbleMaxW * 0.60f).toInt().coerceAtLeast(1) 
            StaticLayout.Builder.obtain(s, 0, s.length, senderPaint, senderMaxW)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        } else null

        buildReplyLayouts(textWidth)
        buildFileLayouts(msg, textWidth)
        buildEphemeralLayout(msg, textWidth)
        buildErrorLayout(msg, textWidth)

        ogpData = if (msg.content.contains("\"mk\"") && msg.content.contains("lk_ogp")) {
            parseOgpData(msg.content)
        } else null
        if (ogpData != null) {
            val ogp = ogpData!!
            val ogpTextW = (textWidth * 0.9f).toInt().coerceAtLeast(1)
            ogpTitleLayout = StaticLayout.Builder.obtain(ogp.title, 0, ogp.title.length, currentContentPaint, ogpTextW)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            ogpDescLayout = StaticLayout.Builder.obtain(ogp.description, 0, ogp.description.length, theme.chatTimePaint, ogpTextW)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            ogpImageW = (textWidth * 0.6f).toInt().coerceAtLeast(LayoutHelper.dp(120))
            ogpImageH = (ogpImageW * 0.6f).toInt().coerceAtLeast(LayoutHelper.dp(80))
            ogpImage.setRoundRadius(OGP_RADIUS.toInt())
            val proxiedImg = createImgproxyUrl(ogp.image, ogpImageW * 2, ogpImageH * 2, "fill")
            ogpImage.setImage(proxiedImg, null, context)
        } else {
            ogpTitleLayout = null
            ogpDescLayout = null
            ogpImageW = 0
            ogpImageH = 0
            ogpImage.setImage(null, null, context)
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

        val replyW = if (hasReply) cachedReplyNameW + cachedReplyTextW + REPLY_AVATAR_SIZE + REPLY_H_GAP * 2 else 0f
        val ogpW = if (ogpData != null) maxOf(cachedOgpTitleW, cachedOgpDescW, ogpImageW.toFloat()) else 0f
        val fileW = if (drawFileAttachment) maxOf(FILE_ICON_SIZE + FILE_ICON_GAP + cachedFileNameW, FILE_ICON_SIZE + FILE_ICON_GAP + cachedFileSizeW) else 0f
        cachedInnerWidth = if (drawPhotoImage) {
            photoWidth
        } else if (hasCodeFence) {
            bubbleMaxW
        } else {
            val allW = maxOf(cachedSenderW, cachedContentW, cachedTimeW, replyW, ogpW, cachedForwardW, fileW, cachedEphW)
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

        if (drawPhotoImage) {
            val imgH = if (mediaGridCount > 1) mediaGridTotalH else photoHeight
            h += imgH + GAP_V_INNER
        }

        if (drawFileAttachment) {
            h += FILE_ICON_SIZE + GAP_V_INNER
        }

        contentLayout?.let { h += it.height + GAP_V_INNER }

        if (ogpData != null) {
            h += GAP_V_INNER
            ogpTitleLayout?.let { h += it.height + GAP_V_INNER }
            ogpDescLayout?.let { h += it.height + GAP_V_INNER }
            h += ogpImageH + GAP_V_INNER
        }

        if (drawEphemeral) {
            ephemeralLayout?.let { h += it.height + GAP_V_INNER }
        }

        // time is drawn inline with senderLayout row — no separate height needed
        // (if isCombined, senderLayout=null and timeLayout=null, so nothing to add)

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
            return
        }
        val fileTextW = (textWidth - FILE_ICON_SIZE - FILE_ICON_GAP).coerceAtLeast(1)
        val name = msg.attachmentFilename.ifEmpty { "File" }
        fileNameLayout = StaticLayout.Builder.obtain(name, 0, name.length, currentContentPaint, fileTextW)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.MIDDLE)
            .build()

        val sizeText = formatFileSize(msg.attachmentSize)
        fileSizeLayout = StaticLayout.Builder.obtain(sizeText, 0, sizeText.length, currentTimePaint, fileTextW)
            .setMaxLines(1)
            .build()

        val d = MezonIcon.fileIcon.getDrawable(context).mutate()
        val tint = getFileColor(msg.attachmentFilename)
        d.setTint(tint)
        fileIconDrawable = d
    }

    private fun getFileColor(filename: String): Int {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> 0xFFE53935.toInt()
            "doc", "docx" -> 0xFF1E88E5.toInt()
            "xls", "xlsx" -> 0xFF43A047.toInt()
            "ppt", "pptx" -> 0xFFF4511E.toInt()
            "zip", "rar", "7z", "tar", "gz" -> 0xFFFDD835.toInt()
            "mp3", "wav", "aac", "flac", "ogg" -> 0xFFE040FB.toInt()
            "txt", "csv", "log" -> 0xFF78909C.toInt()
            "apk" -> 0xFF66BB6A.toInt()
            "json", "xml", "html", "css", "js", "ts", "kt", "java", "py" -> 0xFF26C6DA.toInt()
            else -> theme.primary
        }
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

            Log.d("ReplyAvatar", "parseReply: name=$replySenderName senderId=$replySenderId avatarUrl=$replySenderAvatarUrl content=${replyContent.take(30)}")

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
            val proxyUrl = createImgproxyUrl(url, AVATAR_SIZE * 2, AVATAR_SIZE * 2, "fill")
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

    private fun loadReplyAvatar(url: String) {
        replyAvatarCancellable?.cancel()
        replyAvatarCancellable = null
        if (url.isEmpty()) {
            Log.d("ReplyAvatar", "loadReplyAvatar: url is empty, showing initials")
            replyAvatarDrawable.setPhoto(null)
            replyAvatarDrawable.setDrawableByInfo(true)
            return
        }
        val proxyUrl = createImgproxyUrl(url, REPLY_AVATAR_SIZE * 2, REPLY_AVATAR_SIZE * 2, "fill")
        Log.d("ReplyAvatar", "loadReplyAvatar: url=$url proxyUrl=$proxyUrl")
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(proxyUrl, REPLY_AVATAR_SIZE, REPLY_AVATAR_SIZE)
        if (cached != null) {
            Log.d("ReplyAvatar", "loadReplyAvatar: found in memory cache")
            replyAvatarDrawable.setPhoto(cached)
            replyAvatarDrawable.setDrawableByInfo(true)
            return
        }
        replyAvatarDrawable.setDrawableByInfo(true)
        replyAvatarCancellable = loader.load(proxyUrl, REPLY_AVATAR_SIZE, REPLY_AVATAR_SIZE, onSuccess = { bmp ->
            Log.d("ReplyAvatar", "loadReplyAvatar: loaded successfully ${bmp.width}x${bmp.height}")
            replyAvatarDrawable.setPhoto(bmp)
            replyAvatarDrawable.setDrawableByInfo(true)
            invalidate()
        }, onError = {
            Log.d("ReplyAvatar", "loadReplyAvatar: load FAILED for url=$url")
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
        fun didClickMention(cell: ChatMessageCell, userId: String?, roleId: String?) {}
        fun didClickHashtag(cell: ChatMessageCell, channelId: String?) {}
        fun didLongPress(cell: ChatMessageCell, msg: MessageEntity) {}
        fun didClickAvatar(cell: ChatMessageCell, msg: MessageEntity) {}
        fun didPressReply(cell: ChatMessageCell, replyMessageId: Long) {}
    }

    private var pressedLink: ClickableSpan? = null
    private var pressedOnMedia = false
    private var pressedMediaIndex = 0
    private var pressedOnOgp = false
    private var pressedOnFile = false
    private var pressedOnAvatar = false
    private var pressedOnReply = false
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
        delegate?.didLongPress(this, msg)
        pressedLink = null
        pressedOnMedia = false
        pressedOnOgp = false
        pressedOnFile = false
        pressedOnAvatar = false
        pressedOnReply = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressedLink = null
                pressedOnMedia = false
                pressedOnOgp = false
                pressedOnFile = false
                pressedOnAvatar = false
                pressedOnReply = false
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
                    pressedOnFile = false
                    pressedOnAvatar = false
                    pressedOnReply = false
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
                pressedOnFile = false
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
            MENTION_BG_PAINT.color = theme.midnightBlue and 0x00FFFFFF.toInt() or 0x26000000
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
        var yOff = topPad.toFloat()

        if (!isCombined) {
            avatarDrawable.setBounds(PAD_H, topPad, PAD_H + AVATAR_SIZE, topPad + AVATAR_SIZE)
            avatarDrawable.draw(canvas)
        }

        if (!isCombined) {
            senderLayout?.let {
                val sx = (PAD_H + AVATAR_SIZE + GAP_AVATAR).toFloat()
                canvas.save()
                canvas.translate(sx, yOff)
                it.draw(canvas)
                canvas.restore()
                yOff += it.height + GAP_V_INNER
            }
        }

        val imgX = (PAD_H + AVATAR_SIZE + GAP_AVATAR).toFloat()
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
        yOff += photoHeight + GAP_V_INNER

        timeLayout?.let {
            canvas.save()
            canvas.translate(imgX, yOff)
            it.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawMessageBubble(canvas: Canvas, msg: MessageEntity) {
        val topPad = if (isCombined) COMBINE_PAD_V else PAD_V
        val contentLeft = PAD_H + AVATAR_SIZE + GAP_AVATAR

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

        contentLayout?.let {
            contentLayoutLeft = contentLeft
            contentLayoutTop = yOff.toInt()
            canvas.save()
            canvas.translate(contentLeft.toFloat(), yOff)
            it.draw(canvas)
            canvas.restore()
            yOff += it.height + GAP_V_INNER
        }

        ogpData?.let { yOff = drawOgpBlock(canvas, contentLeft.toFloat(), yOff) + GAP_V_INNER }

        if (drawEphemeral) {
            yOff = drawEphemeralIndicator(canvas, contentLeft.toFloat(), yOff)
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
        fileBlockLeft = x
        fileBlockTop = y
        val iconY = y.toInt()
        iconD.setBounds(x.toInt(), iconY, x.toInt() + FILE_ICON_SIZE, iconY + FILE_ICON_SIZE)
        iconD.draw(canvas)

        val textX = x + FILE_ICON_SIZE + FILE_ICON_GAP
        var textY = y
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
        val blockW = FILE_ICON_SIZE + FILE_ICON_GAP + maxOf(cachedFileNameW, cachedFileSizeW)
        fileBlockRight = x + blockW
        fileBlockBottom = y + FILE_ICON_SIZE
        return y + FILE_ICON_SIZE + GAP_V_INNER
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

    companion object {
        const val COMBINE_TIME_THRESHOLD = 2 * 60L
        private const val TAG = "ChatMessageCell"

        private val AVATAR_SIZE = LayoutHelper.dp(40)  
        private val PAD_H = LayoutHelper.dp(6)          
        private val PAD_V = LayoutHelper.dp(10)         
        private val PAD_BOTTOM = LayoutHelper.dp(6)    
        private val COMBINE_PAD_V = LayoutHelper.dp(1)
        private val GAP_AVATAR = LayoutHelper.dp(12)   
        private val MENTION_BAR_WIDTH = LayoutHelper.dp(2)
        private val MENTION_BAR_PAINT = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = 0xFF5A62F4.toInt()
        }
        private val MENTION_BG_PAINT = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val GAP_V_INNER = LayoutHelper.dp(6) 
        private val MEDIA_RADIUS = LayoutHelper.dp(12).toFloat()
        private val OGP_RADIUS = LayoutHelper.dp(8).toFloat()
        private val PLAY_BTN_SIZE = LayoutHelper.dp(48).toFloat()
        private val REPLY_AVATAR_SIZE = LayoutHelper.dp(16)
        private val REPLY_H_GAP = LayoutHelper.dp(4)
        private val REPLY_ROW_HEIGHT = LayoutHelper.dp(20)
        private val REPLY_V_GAP = LayoutHelper.dp(2)
        private val CONNECTOR_RADIUS = LayoutHelper.dpf(6f)
        private val CONNECTOR_STROKE = LayoutHelper.dpf(1.5f)
        private val CONNECTOR_GAP = LayoutHelper.dp(4)
        private val FILE_ICON_SIZE = LayoutHelper.dp(40)
        private val FILE_ICON_GAP = LayoutHelper.dp(10)
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

        private fun formatDuration(seconds: Int): String {
            val m = seconds / 60
            val s = seconds % 60
            return "%d:%02d".format(m, s)
        }

        private fun formatFileSize(bytes: Int): String {
            if (bytes <= 0) return ""
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> "%.1f MB".format(bytes / (1024f * 1024f))
            }
        }
    }
}
