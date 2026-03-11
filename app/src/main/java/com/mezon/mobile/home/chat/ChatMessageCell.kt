package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import coil.Coil
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Size
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.util.formatRelativeTime
import com.mezon.mobile.util.parseContentText
import kotlin.math.min

class ChatMessageCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    var messageEntity: MessageEntity? = null
        private set

    private val avatarDrawable = AvatarDrawable()
    private var currentAvatarUrl: String? = null
    private var measuredCellHeight = LayoutHelper.dp(60)

    private var contentLayout: StaticLayout? = null
    private var senderLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null
    private var durationLayout: StaticLayout? = null
    private var parsedContent: String = ""
    private var timeText: String = ""
    private var drawPhotoImage = false

    private val photoImage = ImageReceiver(this)
    private var photoWidth = 0
    private var photoHeight = 0

    private var currentBubblePaint = theme.chatBubblePaint
    private var currentContentPaint = theme.chatContentPaint
    private var currentTimePaint = theme.chatTimePaint
    private val senderPaint get() = theme.chatSenderPaint

    private var attachedToWindow = false
    private var pendingMessage: MessageEntity? = null
    private var avatarDisposable: Disposable? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        photoImage.onAttachedToWindow()
        pendingMessage?.let { msg ->
            pendingMessage = null
            update(0, msg)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        photoImage.onDetachedFromWindow()
        avatarDisposable?.dispose()
        avatarDisposable = null
    }

    fun setMessage(msg: MessageEntity) {
        messageEntity = msg
        if (!attachedToWindow) {
            pendingMessage = msg
            return
        }
        update(0)
    }

    fun update(mask: Int, newMsg: MessageEntity? = null): Boolean {
        val msg = newMsg ?: messageEntity ?: return false
        var rebuildLayout = false
        var needInvalidate = false

        if (mask == 0) {
            if (newMsg != null) messageEntity = newMsg
            parsedContent = parseContentText(msg.content)
            timeText = formatRelativeTime(msg.timestampSeconds)
            drawPhotoImage = msg.hasMedia
            updateColors(msg)
            if (drawPhotoImage) computePhotoSize(msg)
            buildLayouts(msg)
            if (!msg.isMe) {
                avatarDrawable.setInfo(msg.senderId, msg.senderName)
                loadAvatar(msg.senderAvatar)
            }
            if (drawPhotoImage) loadPhotoImage(msg)
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
            needInvalidate = true
        }

        if ((mask and NotificationCenter.UPDATE_MASK_NAME) != 0) {
            if (messageEntity?.senderName != msg.senderName) {
                rebuildLayout = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_AVATAR) != 0) {
            if (!msg.isMe && messageEntity?.senderAvatar != msg.senderAvatar) {
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
        val maxW: Int
        val screenW = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        maxW = (screenW * 0.65f).toInt()
        val maxH = maxW + LayoutHelper.dp(100)

        var imgW = msg.attachmentWidth
        var imgH = msg.attachmentHeight
        if (imgW <= 0 || imgH <= 0) {
            imgW = maxW
            imgH = maxW
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

    private fun loadPhotoImage(msg: MessageEntity) {
        val url = msg.attachmentUrl
        val thumb = msg.attachmentThumb.ifEmpty { null }
        val density = resources.displayMetrics.density

        val targetW = (photoWidth * density).toInt().coerceAtLeast(200)
        val targetH = (photoHeight * density).toInt().coerceAtLeast(200)
        val proxiedUrl = createImgproxyUrl(url, targetW, targetH, "fill")
        val proxiedThumb = thumb?.let { createImgproxyUrl(it, targetW / 4, targetH / 4, "fill") }

        photoImage.setRoundRadius(MEDIA_RADIUS.toInt())
        photoImage.setImage(proxiedUrl, proxiedThumb, context)
    }

    private fun updateColors(msg: MessageEntity) {
        if (msg.isMe) {
            currentBubblePaint = theme.chatBubbleOutPaint
            currentContentPaint = theme.chatContentOutPaint
            currentTimePaint = theme.chatTimeOutPaint
        } else {
            currentBubblePaint = theme.chatBubblePaint
            currentContentPaint = theme.chatContentPaint
            currentTimePaint = theme.chatTimePaint
        }
    }

    private fun buildLayouts(msg: MessageEntity) {
        val bubbleWidth = if (drawPhotoImage) photoWidth else MAX_BUBBLE_W - BUBBLE_PAD_H * 2
        if (bubbleWidth <= 0) return

        val textWidth = if (drawPhotoImage) bubbleWidth - BUBBLE_PAD_H * 2 else bubbleWidth

        timeLayout = StaticLayout.Builder.obtain(timeText, 0, timeText.length, currentTimePaint, textWidth.coerceAtLeast(1))
            .setMaxLines(1)
            .build()

        val hasText = parsedContent.isNotBlank() && parsedContent != "[file]"
        contentLayout = if (hasText) {
            StaticLayout.Builder.obtain(parsedContent, 0, parsedContent.length, currentContentPaint, textWidth.coerceAtLeast(1))
                .setLineSpacing(LayoutHelper.dpf(2f), 1f)
                .build()
        } else null

        if (!msg.isMe) {
            val s = msg.senderName
            senderLayout = StaticLayout.Builder.obtain(s, 0, s.length, senderPaint, textWidth.coerceAtLeast(1))
                .setMaxLines(1)
                .build()
        } else {
            senderLayout = null
        }

        durationLayout = if (msg.messageType == MessageEntity.TYPE_VIDEO && msg.attachmentDuration > 0) {
            val dur = formatDuration(msg.attachmentDuration)
            StaticLayout.Builder.obtain(dur, 0, dur.length, DURATION_PAINT, LayoutHelper.dp(100))
                .setMaxLines(1)
                .build()
        } else null

        var h = PAD_V * 2
        if (!msg.isMe) {
            senderLayout?.let { h += it.height + GAP_V_INNER }
        }
        if (drawPhotoImage) {
            h += photoHeight + BUBBLE_PAD_V
            if (!msg.isMe) h += BUBBLE_PAD_V
        }
        if (contentLayout != null) {
            h += BUBBLE_PAD_V
            contentLayout?.let { h += it.height + GAP_V_INNER }
        } else if (!drawPhotoImage) {
            h += BUBBLE_PAD_V * 2
        }
        timeLayout?.let { h += it.height + GAP_V_INNER }
        if (!drawPhotoImage) h += BUBBLE_PAD_V

        measuredCellHeight = h
    }

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarDrawable.setPhoto(null)
        avatarDisposable?.dispose()
        avatarDisposable = null

        if (url.isNotEmpty()) {
            val proxyUrl = createImgproxyUrl(url, AVATAR_SIZE * 2, AVATAR_SIZE * 2, "fill")
            val request = ImageRequest.Builder(context)
                .data(proxyUrl)
                .size(Size(AVATAR_SIZE, AVATAR_SIZE))
                .allowHardware(false)
                .target(onSuccess = { d ->
                    avatarDrawable.setPhoto(LayoutHelper.drawableToBitmap(d, AVATAR_SIZE))
                    post { invalidate() }
                })
                .build()
            avatarDisposable = Coil.imageLoader(context).enqueue(request)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), measuredCellHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val msg = messageEntity ?: return
        if (msg.isMe) drawSentBubble(canvas, msg) else drawReceivedBubble(canvas, msg)
    }

    private fun drawSentBubble(canvas: Canvas, msg: MessageEntity) {
        val innerWidth = if (drawPhotoImage) {
            photoWidth + BUBBLE_PAD_H * 2
        } else {
            val contentWidth = contentLayout?.let { maxLineWidth(it) } ?: 0f
            val timeWidth = timeLayout?.getLineWidth(0) ?: 0f
            maxOf(contentWidth, timeWidth).toInt() + BUBBLE_PAD_H * 2
        }
        val bubbleLeft = width - PAD_H - innerWidth
        val bubbleTop = PAD_V
        val bubbleBottom = measuredCellHeight - PAD_V

        canvas.drawRoundRect(
            RectF(bubbleLeft.toFloat(), bubbleTop.toFloat(), (width - PAD_H).toFloat(), bubbleBottom.toFloat()),
            BUBBLE_RADIUS, BUBBLE_RADIUS, currentBubblePaint
        )

        var yOff = (bubbleTop + BUBBLE_PAD_V).toFloat()

        if (drawPhotoImage) {
            val imgX = (bubbleLeft + BUBBLE_PAD_H).toFloat()
            photoImage.setImageCoords(imgX, yOff, photoWidth.toFloat(), photoHeight.toFloat())
            photoImage.draw(canvas)
            drawMediaOverlays(canvas, msg, imgX, yOff)
            yOff += photoHeight + GAP_V_INNER
        }

        contentLayout?.let {
            canvas.save()
            canvas.translate((bubbleLeft + BUBBLE_PAD_H).toFloat(), yOff)
            it.draw(canvas)
            canvas.restore()
            yOff += it.height + GAP_V_INNER
        }

        timeLayout?.let {
            canvas.save()
            canvas.translate(width - PAD_H - BUBBLE_PAD_H - it.getLineWidth(0), yOff)
            it.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawReceivedBubble(canvas: Canvas, msg: MessageEntity) {
        val aLeft = PAD_H
        val aTop = PAD_V

        avatarDrawable.setBounds(aLeft, aTop, aLeft + AVATAR_SIZE, aTop + AVATAR_SIZE)
        avatarDrawable.draw(canvas)

        val bubbleLeft = aLeft + AVATAR_SIZE + GAP_AVATAR
        val innerWidth = if (drawPhotoImage) {
            photoWidth + BUBBLE_PAD_H * 2
        } else {
            val senderW = senderLayout?.let { maxLineWidth(it) } ?: 0f
            val contentW = contentLayout?.let { maxLineWidth(it) } ?: 0f
            val timeW = timeLayout?.getLineWidth(0) ?: 0f
            maxOf(senderW, contentW, timeW).toInt() + BUBBLE_PAD_H * 2
        }
        val bubbleTop = PAD_V
        val bubbleBottom = measuredCellHeight - PAD_V

        canvas.drawRoundRect(
            RectF(bubbleLeft.toFloat(), bubbleTop.toFloat(), (bubbleLeft + innerWidth).toFloat(), bubbleBottom.toFloat()),
            BUBBLE_RADIUS, BUBBLE_RADIUS, currentBubblePaint
        )

        var yOff = (bubbleTop + BUBBLE_PAD_V).toFloat()
        senderLayout?.let {
            canvas.save()
            canvas.translate((bubbleLeft + BUBBLE_PAD_H).toFloat(), yOff)
            it.draw(canvas)
            canvas.restore()
            yOff += it.height + GAP_V_INNER
        }

        if (drawPhotoImage) {
            val imgX = (bubbleLeft + BUBBLE_PAD_H).toFloat()
            photoImage.setImageCoords(imgX, yOff, photoWidth.toFloat(), photoHeight.toFloat())
            photoImage.draw(canvas)
            drawMediaOverlays(canvas, msg, imgX, yOff)
            yOff += photoHeight + GAP_V_INNER
        }

        contentLayout?.let {
            canvas.save()
            canvas.translate((bubbleLeft + BUBBLE_PAD_H).toFloat(), yOff)
            it.draw(canvas)
            canvas.restore()
            yOff += it.height + GAP_V_INNER
        }

        timeLayout?.let {
            canvas.save()
            canvas.translate((bubbleLeft + BUBBLE_PAD_H).toFloat(), yOff)
            it.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawMediaOverlays(canvas: Canvas, msg: MessageEntity, imgX: Float, imgY: Float) {
        if (msg.messageType == MessageEntity.TYPE_VIDEO) {
            drawVideoPlayButton(canvas, imgX, imgY)
            durationLayout?.let { drawDurationBadge(canvas, it, imgX, imgY) }
        }
    }

    private fun drawVideoPlayButton(canvas: Canvas, imgX: Float, imgY: Float) {
        val cx = imgX + photoWidth / 2f
        val cy = imgY + photoHeight / 2f
        val r = PLAY_BTN_SIZE / 2f
        canvas.drawCircle(cx, cy, r, PLAY_BG_PAINT)

        val triPath = Path()
        val triSize = r * 0.7f
        val left = cx - triSize * 0.35f
        val top = cy - triSize * 0.5f
        triPath.moveTo(left, top)
        triPath.lineTo(left + triSize, cy)
        triPath.lineTo(left, cy + triSize * 0.5f)
        triPath.close()
        canvas.drawPath(triPath, PLAY_ICON_PAINT)
    }

    private fun drawDurationBadge(canvas: Canvas, layout: StaticLayout, imgX: Float, imgY: Float) {
        val tw = layout.getLineWidth(0)
        val pad = LayoutHelper.dp(6).toFloat()
        val bh = layout.height + pad
        val bw = tw + pad * 2
        val bx = imgX + LayoutHelper.dp(6)
        val by = imgY + photoHeight - bh - LayoutHelper.dp(6)

        canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), bh / 2, bh / 2, DURATION_BG_PAINT)
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

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(32)
        private val PAD_H = LayoutHelper.dp(12)
        private val PAD_V = LayoutHelper.dp(2)
        private val BUBBLE_PAD_H = LayoutHelper.dp(12)
        private val BUBBLE_PAD_V = LayoutHelper.dp(8)
        private val BUBBLE_RADIUS = LayoutHelper.dp(16).toFloat()
        private val MAX_BUBBLE_W = LayoutHelper.dp(280)
        private val GAP_AVATAR = LayoutHelper.dp(8)
        private val GAP_V_INNER = LayoutHelper.dp(2)
        private val MEDIA_RADIUS = LayoutHelper.dp(12).toFloat()
        private val PLAY_BTN_SIZE = LayoutHelper.dp(48).toFloat()

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

        private fun formatDuration(seconds: Int): String {
            val m = seconds / 60
            val s = seconds % 60
            return "%d:%02d".format(m, s)
        }
    }
}
