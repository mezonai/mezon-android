package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.PinMessageData
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.theme.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

class PinMessageCell(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    interface PinMessageCellDelegate {
        fun onJumpToMessage(data: PinMessageData)
        fun onUnpin(data: PinMessageData)
    }

    var delegate: PinMessageCellDelegate? = null
    var pinData: PinMessageData? = null
        private set

    private val headerView = PinHeaderView(context, theme)
    private val messageCell = ChatMessageCell(context, theme)
    private val closeButton = ImageView(context)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpRect = RectF()

    init {
        setWillNotDraw(false)
        clipToPadding = false

        addView(headerView, LayoutParams(LayoutParams.MATCH_PARENT, HEADER_HEIGHT).apply {
            leftMargin = PADDING
            topMargin = PADDING
            rightMargin = PADDING + CLOSE_SIZE + CLOSE_GAP
        })

        messageCell.isCombined = true
        messageCell.isInPinMode = true
        addView(messageCell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = PADDING
            topMargin = PADDING + HEADER_HEIGHT + HEADER_CONTENT_GAP
            rightMargin = PADDING
            bottomMargin = PADDING
        })

        val closeDrawable = MezonIcon.circleXIcon.getDrawable(context).mutate()
        closeDrawable.colorFilter = PorterDuffColorFilter(textColor(), PorterDuff.Mode.SRC_IN)
        closeButton.setImageDrawable(closeDrawable)
        closeButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val iconPad = (CLOSE_TOUCH - ICON_SIZE) / 2
        closeButton.setPadding(iconPad, iconPad, iconPad, iconPad)
        closeButton.setOnClickListener {
            pinData?.let { delegate?.onUnpin(it) }
        }
        addView(closeButton, LayoutParams(CLOSE_TOUCH, CLOSE_TOUCH).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = LayoutHelper.dp(8)
            rightMargin = LayoutHelper.dp(4)
        })

        setOnClickListener {
            pinData?.let { delegate?.onJumpToMessage(it) }
        }
    }

    fun setData(data: PinMessageData, displayName: String?, avatarUrl: String?) {
        pinData = data
        val name = displayName?.takeIf { it.isNotBlank() } ?: data.username.ifBlank { "Unknown" }
        headerView.setInfo(name, avatarUrl ?: data.avatar, data.messageId)

        val entity = data.toMessageEntity()
        messageCell.update(0, entity)

        applyColors()
        requestLayout()
        invalidate()
    }

    private fun applyColors() {
        bgPaint.color = cellBgColor()
        val d = closeButton.drawable
        d?.colorFilter = PorterDuffColorFilter(textColor(), PorterDuff.Mode.SRC_IN)
    }

    private fun cellBgColor(): Int = when (theme.resolvedMode) {
        ThemeMode.LIGHT -> 0xFFFFFFFF.toInt()
        ThemeMode.DARK -> 0xFF1C1D23.toInt()
        ThemeMode.ABYSS -> 0xFF040421.toInt()
        else -> 0xFF1C1D23.toInt()
    }

    private fun textColor(): Int = when (theme.resolvedMode) {
        ThemeMode.LIGHT -> 0xFF29292B.toInt()
        ThemeMode.DARK -> 0xFFCCCCCC.toInt()
        ThemeMode.ABYSS -> 0xFFD6D0EB.toInt()
        else -> 0xFFCCCCCC.toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val totalH = PADDING + HEADER_HEIGHT + HEADER_CONTENT_GAP + messageCell.measuredHeight + PADDING + ITEM_MARGIN_BOTTOM
        setMeasuredDimension(measuredWidth, totalH)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val contentH = measuredHeight - ITEM_MARGIN_BOTTOM
        tmpRect.set(0f, 0f, measuredWidth.toFloat(), contentH.toFloat())
        canvas.drawRoundRect(tmpRect, CORNER_RADIUS, CORNER_RADIUS, bgPaint)
        super.dispatchDraw(canvas)
    }

    companion object {
        private val PADDING = LayoutHelper.dp(15)
        private val HEADER_HEIGHT = LayoutHelper.dp(40)
        private val HEADER_CONTENT_GAP = LayoutHelper.dp(6)
        private val ICON_SIZE = LayoutHelper.dp(24)
        private val CLOSE_SIZE = LayoutHelper.dp(24)
        private val CLOSE_GAP = LayoutHelper.dp(8)
        private val CLOSE_TOUCH = LayoutHelper.dp(40)
        private val CORNER_RADIUS = LayoutHelper.dp(10).toFloat()
        private val ITEM_MARGIN_BOTTOM = LayoutHelper.dp(10)
    }
}

private class PinHeaderView(context: Context, private val theme: ThemeColors) : View(context) {

    private val avatarDrawable = com.mezon.mobile.core.AvatarDrawable()
    private var avatarDisposable: MezonImageLoader.Cancellable? = null
    private var currentAvatarUrl: String? = null
    private var nameLayout: StaticLayout? = null
    private var nameText: String = ""
    private var lastLayoutWidth = 0

    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.dp(16).toFloat()
        isFakeBoldText = true
    }

    fun setInfo(name: String, avatarUrl: String, fallbackId: Long) {
        nameText = name
        avatarDrawable.setInfo(fallbackId, name)
        loadAvatar(avatarUrl)
        namePaint.color = nameColor()
        lastLayoutWidth = 0
        requestLayout()
        invalidate()
    }

    private fun buildNameLayout(availableWidth: Int) {
        if (availableWidth <= 0 || nameText.isEmpty()) return
        if (availableWidth == lastLayoutWidth && nameLayout != null) return
        lastLayoutWidth = availableWidth
        nameLayout = StaticLayout.Builder.obtain(nameText, 0, nameText.length, namePaint, availableWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun nameColor(): Int = when (theme.resolvedMode) {
        ThemeMode.LIGHT -> 0xFF070709.toInt()
        ThemeMode.DARK -> 0xFFDFE0E4.toInt()
        ThemeMode.ABYSS -> 0xFFF1EDFF.toInt()
        else -> 0xFFDFE0E4.toInt()
    }

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarDrawable.setPhoto(null)
        avatarDisposable?.cancel()
        avatarDisposable = null
        if (url.isNotEmpty()) {
            val proxyUrl = com.mezon.mobile.util.avatarImgproxyUrl(url, AVATAR_SIZE)
            avatarDisposable = MezonImageLoader.getInstance(context).load(
                proxyUrl, AVATAR_SIZE, AVATAR_SIZE,
                onSuccess = { bmp ->
                    avatarDrawable.setPhoto(bmp)
                    invalidate()
                }
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, AVATAR_SIZE)
        buildNameLayout((w - AVATAR_SIZE - GAP).coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        avatarDrawable.setBounds(0, 0, AVATAR_SIZE, AVATAR_SIZE)
        avatarDrawable.draw(canvas)

        nameLayout?.let {
            val textLeft = (AVATAR_SIZE + GAP).toFloat()
            val textTop = (AVATAR_SIZE - it.height) / 2f
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        avatarDisposable?.cancel()
        avatarDisposable = null
    }

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(40)
        private val GAP = LayoutHelper.dp(10)
    }
}

private fun PinMessageData.toMessageEntity(): MessageEntity {
    val first = attachments.firstOrNull()
    val type = when {
        first == null || first.url.isEmpty() -> MessageEntity.TYPE_TEXT
        first.filetype.startsWith("image/gif", true) -> MessageEntity.TYPE_GIF
        first.filetype.equals("sticker", true) -> MessageEntity.TYPE_GIF
        first.url.contains("tenor.com", true) -> MessageEntity.TYPE_GIF
        first.url.contains("/stickers/") -> MessageEntity.TYPE_GIF
        first.filetype.startsWith("image/", true) -> MessageEntity.TYPE_PHOTO
        first.filetype.startsWith("video/", true) -> MessageEntity.TYPE_VIDEO
        first.url.isNotEmpty() -> MessageEntity.TYPE_FILE
        else -> MessageEntity.TYPE_TEXT
    }

    val extraJson = if (attachments.size > 1) {
        val arr = JSONArray()
        for (i in 1 until attachments.size) {
            val a = attachments[i]
            val obj = JSONObject()
            obj.put("url", a.url)
            obj.put("thumb", a.thumbnail)
            obj.put("width", a.width)
            obj.put("height", a.height)
            obj.put("filename", a.filename)
            obj.put("filetype", a.filetype)
            obj.put("size", a.size)
            obj.put("duration", a.duration)
            arr.put(obj)
        }
        arr.toString()
    } else ""

    return MessageEntity(
        id = messageId,
        channelId = channelId,
        senderId = senderId,
        senderName = username,
        senderAvatar = avatar,
        content = content,
        timestampSeconds = createTimeSeconds.toLong(),
        code = 0,
        isMe = false,
        messageType = type,
        attachmentUrl = first?.url.orEmpty(),
        attachmentThumb = first?.thumbnail.orEmpty(),
        attachmentWidth = first?.width ?: 0,
        attachmentHeight = first?.height ?: 0,
        attachmentFilename = first?.filename.orEmpty(),
        attachmentFiletype = first?.filetype.orEmpty(),
        attachmentSize = first?.size ?: 0,
        attachmentDuration = first?.duration ?: 0,
        extraAttachmentsJson = extraJson
    )
}
