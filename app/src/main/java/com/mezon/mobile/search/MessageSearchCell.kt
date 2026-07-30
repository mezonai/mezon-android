package com.mezon.mobile.search

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextUtils
import com.mezon.mezon.api.SearchMessageDocument
import com.mezon.mobile.R
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.parseContentPreview

class MessageSearchCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    var document: SearchMessageDocument? = null
        private set

    private val avatarDrawable = AvatarDrawable()
    private var currentAvatarUrl: String? = null
    private var avatarDisposable: MezonImageLoader.Cancellable? = null
    private val tmpRect = RectF()

    private var senderLayout: StaticLayout? = null
    private var contentLayout: StaticLayout? = null
    private var channelLayout: StaticLayout? = null
    private var resolvedSenderName: String? = null

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        avatarDisposable?.cancel()
        avatarDisposable = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        buildLayouts(width)
        val textHeight = listOfNotNull(senderLayout, contentLayout, channelLayout)
            .sumOf { it.height } + LINE_GAP.toInt() *
            (listOfNotNull(senderLayout, contentLayout, channelLayout).size - 1).coerceAtLeast(0)
        val contentHeight = maxOf(AVATAR_SIZE, textHeight)
        setMeasuredDimension(width, PAD_TOP + contentHeight + PAD_BOTTOM)
    }

    override fun invalidate() {
        if (document == null) return
        super.invalidate()
    }

    fun setData(doc: SearchMessageDocument) {
        update(0, doc)
    }

    fun update(
        mask: Int,
        newDoc: SearchMessageDocument? = null,
        senderName: String? = null
    ) {
        val doc = newDoc ?: document ?: return
        if (newDoc != null) document = newDoc
        resolvedSenderName = senderName
        avatarDrawable.setInfo(doc.senderId.hashCode().toLong(), doc.username)
        loadAvatar(doc.avatarUrl)
        buildLayouts(measuredWidth)
        requestLayout()
        invalidate()
    }

    private fun buildLayouts(width: Int) {
        val doc = document ?: return
        val w = width
        if (w <= 0) return

        val textLeft = AVATAR_LEFT + AVATAR_SIZE + TEXT_LEFT_MARGIN
        val textWidth = w - textLeft - PAD_RIGHT
        if (textWidth <= 0) return

        val sender = resolvedSenderName?.takeIf { it.isNotBlank() }
            ?: doc.displayName.ifEmpty { doc.username }
        senderLayout = StaticLayout.Builder.obtain(sender, 0, sender.length, theme.dialogNamePaint, textWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val parsedPreview = parseContentPreview(doc.content)
        val preview = when {
            parsedPreview == "[file]" -> context.getString(R.string.search_message_attachment)
            parsedPreview.isNotEmpty() -> parsedPreview
            hasAttachmentPayload(doc.attachments) -> context.getString(R.string.search_message_attachment)
            else -> ""
        }
        if (preview.isNotEmpty()) {
            contentLayout = StaticLayout.Builder.obtain(preview, 0, preview.length, theme.dialogMessagePaint, textWidth)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            contentLayout = null
        }

        val channelInfo = buildString {
            if (doc.channelLabel.isNotEmpty()) {
                append("#")
                append(doc.channelLabel)
            }
            if (doc.clanName.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(doc.clanName)
            }
        }
        if (channelInfo.isNotEmpty()) {
            channelLayout = StaticLayout.Builder.obtain(channelInfo, 0, channelInfo.length, theme.dialogMessagePaint, textWidth)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            channelLayout = null
        }
    }

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl) return
        currentAvatarUrl = url
        avatarDisposable?.cancel()
        avatarDisposable = null

        if (url.isEmpty()) {
            avatarDrawable.setLoadingPlaceholder(false)
            avatarDrawable.setPhoto(null)
            return
        }

        val proxyUrl = avatarImgproxyUrl(url, AVATAR_SIZE)
        avatarDrawable.setLoadingPlaceholder(true)
        avatarDisposable = MezonImageLoader.getInstance(context).load(
            proxyUrl, AVATAR_SIZE, AVATAR_SIZE,
            onSuccess = { bmp ->
                avatarDrawable.setLoadingPlaceholder(false)
                avatarDrawable.setPhoto(bmp)
                invalidate()
            },
            onError = {
                avatarDrawable.setLoadingPlaceholder(false)
                invalidate()
            }
        )
    }

    override fun onDraw(canvas: Canvas) {
        val doc = document ?: return
        val w = measuredWidth
        val h = measuredHeight

        val avatarTop = PAD_TOP.toFloat()
        tmpRect.set(
            AVATAR_LEFT.toFloat(), avatarTop,
            (AVATAR_LEFT + AVATAR_SIZE).toFloat(), avatarTop + AVATAR_SIZE
        )
        avatarDrawable.setBounds(tmpRect.left.toInt(), tmpRect.top.toInt(), tmpRect.right.toInt(), tmpRect.bottom.toInt())
        avatarDrawable.draw(canvas)

        val textLeft = (AVATAR_LEFT + AVATAR_SIZE + TEXT_LEFT_MARGIN).toFloat()
        var currentY = PAD_TOP.toFloat()

        senderLayout?.let {
            canvas.save()
            canvas.translate(textLeft, currentY)
            it.draw(canvas)
            canvas.restore()
            currentY += it.height + LINE_GAP
        }

        contentLayout?.let {
            canvas.save()
            canvas.translate(textLeft, currentY)
            it.draw(canvas)
            canvas.restore()
            currentY += it.height + LINE_GAP
        }

        channelLayout?.let {
            canvas.save()
            canvas.translate(textLeft, currentY)
            it.draw(canvas)
            canvas.restore()
        }

        val dividerLeft = textLeft
        canvas.drawLine(dividerLeft, (h - 1).toFloat(), w.toFloat(), (h - 1).toFloat(), theme.dividerPaint)
    }

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(40f)
        private val AVATAR_LEFT = LayoutHelper.dp(16f)
        private val TEXT_LEFT_MARGIN = LayoutHelper.dp(12f)
        private val PAD_RIGHT = LayoutHelper.dp(16f)
        private val PAD_TOP = LayoutHelper.dp(10f)
        private val PAD_BOTTOM = LayoutHelper.dp(10f)
        private val LINE_GAP = LayoutHelper.dp(2f).toFloat()

        internal fun hasAttachmentPayload(raw: String): Boolean {
            val value = raw.trim()
            return value.isNotEmpty() && value != "[]" && value != "{}" &&
                !value.equals("null", ignoreCase = true)
        }
    }
}
