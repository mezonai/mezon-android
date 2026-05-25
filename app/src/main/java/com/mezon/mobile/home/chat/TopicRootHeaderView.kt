package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.util.convertTimestampToTimeAgo
import com.mezon.mobile.util.parseContentPreview
import com.mezon.mobile.util.avatarImgproxyUrl

class TopicRootHeaderView(
    context: Context,
    private val theme: ThemeColors
) : View(context) {

    private val avatarDrawable = AvatarDrawable()
    private var nameLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null
    private var contentLayout: StaticLayout? = null
    private var avatarDisposable: MezonImageLoader.Cancellable? = null
    private var currentAvatarUrl: String? = null
    private var boundMessage: MessageEntity? = null
    private var boundName: String = ""
    private var layoutWidth = 0
    private var measuredContentHeight = MIN_HEIGHT

    fun setRootMessage(message: MessageEntity?) {
        if (message == null) {
            visibility = GONE
            boundMessage = null
            return
        }
        visibility = VISIBLE
        val name = message.senderName.ifBlank { message.senderUsername }
        avatarDrawable.setInfo(message.senderId, name)
        if (currentAvatarUrl != message.senderAvatar) {
            currentAvatarUrl = message.senderAvatar
            avatarDisposable?.cancel()
            if (message.senderAvatar.isNotBlank()) {
                val proxy = avatarImgproxyUrl(message.senderAvatar, AVATAR_SIZE)
                val loader = MezonImageLoader.getInstance(context)
                avatarDisposable = loader.load(
                    proxy,
                    AVATAR_SIZE,
                    AVATAR_SIZE,
                    onSuccess = { bmp ->
                        avatarDrawable.setPhoto(bmp)
                        invalidate()
                    }
                )
            } else {
                avatarDrawable.setPhoto(null)
            }
        }
        boundMessage = message
        boundName = name
        if (layoutWidth > 0) {
            buildLayoutsFromWidth(layoutWidth)
            measuredContentHeight = computeHeight()
        }
        requestLayout()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        avatarDisposable?.cancel()
        avatarDisposable = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != layoutWidth) {
            layoutWidth = w
            if (boundMessage != null) {
                buildLayoutsFromWidth(w)
                measuredContentHeight = computeHeight()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0 && w != layoutWidth && boundMessage != null) {
            layoutWidth = w
            buildLayoutsFromWidth(w)
            measuredContentHeight = computeHeight()
        }
        setMeasuredDimension(w, measuredContentHeight.coerceAtLeast(MIN_HEIGHT))
    }

    override fun onDraw(canvas: Canvas) {
        val textLeft = PADDING_H + AVATAR_SIZE + GAP
        avatarDrawable.setBounds(PADDING_H, PADDING_V, PADDING_H + AVATAR_SIZE, PADDING_V + AVATAR_SIZE)
        avatarDrawable.draw(canvas)
        var y = PADDING_V.toFloat()
        nameLayout?.let {
            canvas.save()
            canvas.translate(textLeft.toFloat(), y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + GAP / 2f
        }
        timeLayout?.let {
            canvas.save()
            canvas.translate(textLeft.toFloat(), y)
            it.draw(canvas)
            canvas.restore()
        }
        y = (PADDING_V + AVATAR_SIZE + GAP).toFloat()
        contentLayout?.let {
            canvas.save()
            canvas.translate(PADDING_H.toFloat(), y)
            it.draw(canvas)
            canvas.restore()
        }
        theme.dividerPaint.let { paint ->
            canvas.drawLine(
                PADDING_H.toFloat(),
                height - 1f,
                width - PADDING_H.toFloat(),
                height - 1f,
                paint
            )
        }
    }

    private fun computeHeight(): Int {
        return PADDING_V * 2 + AVATAR_SIZE.coerceAtLeast(
            (nameLayout?.height ?: 0) + GAP + (timeLayout?.height ?: 0)
        ) + GAP + (contentLayout?.height ?: 0)
    }

    private fun buildLayoutsFromWidth(w: Int) {
        val message = boundMessage ?: return
        val name = boundName
        val textW = w - PADDING_H * 2 - AVATAR_SIZE - GAP
        if (textW <= 0) return
        nameLayout = StaticLayout.Builder.obtain(name, 0, name.length, theme.dialogNamePaint, textW)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val timeText = convertTimestampToTimeAgo(context, message.timestampSeconds)
        timeLayout = StaticLayout.Builder.obtain(timeText, 0, timeText.length, theme.dialogTimePaint, textW)
            .setMaxLines(1)
            .build()
        val preview = parseContentPreview(message.content).ifBlank {
            if (message.hasAnyMedia || message.attachmentUrl.isNotEmpty()) "[attachment]" else ""
        }
        contentLayout = if (preview.isNotBlank()) {
            StaticLayout.Builder.obtain(preview, 0, preview.length, theme.dialogMessagePaint, w - PADDING_H * 2)
                .setMaxLines(6)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else null
    }

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(40)
        private val PADDING_H = LayoutHelper.dp(16)
        private val PADDING_V = LayoutHelper.dp(12)
        private val GAP = LayoutHelper.dp(8)
        private val MIN_HEIGHT = LayoutHelper.dp(72)
    }
}
