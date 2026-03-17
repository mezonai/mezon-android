package com.mezon.mobile.home.messages

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextUtils
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.util.formatRelativeTime

class DialogCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    var directMessage: DirectMessage? = null
        private set

    private val avatarDrawable = AvatarDrawable()
    private var currentAvatarUrl: String? = null
    private var avatarDisposable: MezonImageLoader.Cancellable? = null
    private var attachedToWindow = false
    private var needsLayout = false
    private var visibleOnScreen = true

    private var nameLayout: StaticLayout? = null
    private var previewLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null
    private var badgeLayout: StaticLayout? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        if (needsLayout) {
            needsLayout = false
            buildLayouts()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        avatarDisposable?.cancel()
        avatarDisposable = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), CELL_HEIGHT)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (needsLayout) {
            needsLayout = false
            buildLayouts()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildLayouts()
    }

    fun setVisibleOnScreen(visible: Boolean) {
        visibleOnScreen = visible
    }

    fun setData(dm: DirectMessage) {
        directMessage = dm
        update(0)
    }

    fun update(mask: Int, newDm: DirectMessage? = null): Boolean {
        val dm = newDm ?: directMessage ?: return false
        var rebuildLayout = false
        var needInvalidate = false

        if (mask == 0) {
            if (newDm != null) directMessage = newDm
            avatarDrawable.setInfo(dm.channelId, dm.displayName.ifEmpty { dm.label })
            buildLayouts()
            loadAvatar(dm.avatarUrl)
            invalidate()
            return true
        }

        if ((mask and NotificationCenter.UPDATE_MASK_STATUS) != 0) {
            if (directMessage?.isOnline != dm.isOnline) {
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_NAME) != 0) {
            val oldName = directMessage?.displayName ?: ""
            if (oldName != dm.displayName) {
                rebuildLayout = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_AVATAR) != 0) {
            if (directMessage?.avatarUrl != dm.avatarUrl) {
                loadAvatar(dm.avatarUrl)
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_NEW_MESSAGE) != 0 ||
            (mask and NotificationCenter.UPDATE_MASK_MESSAGE_TEXT) != 0
        ) {
            if (directMessage?.lastMessageContent != dm.lastMessageContent ||
                directMessage?.lastMessageTimestamp != dm.lastMessageTimestamp
            ) {
                rebuildLayout = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_READ_DIALOG_MESSAGE) != 0 ||
            (mask and NotificationCenter.UPDATE_MASK_BADGE) != 0
        ) {
            if (directMessage?.unreadCount != dm.unreadCount) {
                rebuildLayout = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_SEND_STATE) != 0) {
            needInvalidate = true
        }

        if (newDm != null) directMessage = newDm

        if (rebuildLayout) {
            buildLayouts()
            invalidate()
            return true
        }
        if (needInvalidate) {
            invalidate()
        }
        return false
    }

    private fun buildLayouts() {
        val dm = directMessage ?: return
        val contentWidth = width - PADDING_H * 2 - AVATAR_SIZE - GAP_H
        if (contentWidth <= 0) return

        val isUnread = dm.unreadCount > 0
        val namePaint = if (isUnread) theme.dialogNameBoldPaint else theme.dialogNamePaint
        val previewPaint = if (isUnread) theme.dialogMessageBoldPaint else theme.dialogMessagePaint
        val timePaint = theme.dialogTimePaint
        timePaint.color = if (isUnread) theme.primary else theme.onSurfaceVariant

        val timeText = formatRelativeTime(dm.lastMessageTimestamp)
        timeLayout = StaticLayout.Builder.obtain(timeText, 0, timeText.length, timePaint, contentWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val timeWidth = timeLayout?.let { it.getLineWidth(0).toInt() + LayoutHelper.dp(8) } ?: 0
        val nameWidth = contentWidth - timeWidth
        val nameText = dm.displayName.ifEmpty { dm.label }
        nameLayout = StaticLayout.Builder.obtain(nameText, 0, nameText.length, namePaint, nameWidth.coerceAtLeast(0))
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val badgeText = if (dm.unreadCount > 0) {
            if (dm.unreadCount > 99) "99+" else dm.unreadCount.toString()
        } else null
        badgeLayout = badgeText?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, theme.dialogBadgeTextPaint, contentWidth)
                .setMaxLines(1)
                .build()
        }

        val badgeSpace = if (badgeLayout != null) BADGE_MIN_W + LayoutHelper.dp(8) else 0
        val previewWidth = contentWidth - badgeSpace
        val previewText = dm.lastMessageContent.ifEmpty { "No messages" }
        previewLayout = StaticLayout.Builder.obtain(previewText, 0, previewText.length, previewPaint, previewWidth.coerceAtLeast(0))
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarDrawable.setPhoto(null)
        avatarDisposable?.cancel()
        avatarDisposable = null

        if (url.isNotEmpty()) {
            val proxyUrl = createImgproxyUrl(url, AVATAR_SIZE * 2, AVATAR_SIZE * 2, "fill")
            avatarDisposable = MezonImageLoader.getInstance(context).load(
                proxyUrl, AVATAR_SIZE, AVATAR_SIZE,
                onSuccess = { bmp ->
                    avatarDrawable.setPhoto(bmp)
                    post { invalidate() }
                }
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!visibleOnScreen) return
        val dm = directMessage ?: return
        val cx = PADDING_H
        val cy = (height - AVATAR_SIZE) / 2
        val isUnread = dm.unreadCount > 0

        avatarDrawable.setBounds(cx, cy, cx + AVATAR_SIZE, cy + AVATAR_SIZE)
        avatarDrawable.draw(canvas)

        if (dm.type == CHANNEL_TYPE_DM && dm.isOnline) {
            val dotR = ONLINE_DOT / 2f
            val dotCx = (cx + AVATAR_SIZE - dotR)
            val dotCy = (cy + AVATAR_SIZE - dotR)
            canvas.drawCircle(dotCx, dotCy, dotR + ONLINE_BORDER / 2f, theme.dialogOnlineBorderPaint)
            canvas.drawCircle(dotCx, dotCy, dotR - 1f, theme.dialogOnlinePaint)
        }

        val textLeft = (cx + AVATAR_SIZE + GAP_H).toFloat()
        var textTop = PADDING_V.toFloat()

        nameLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
        }

        timeLayout?.let {
            val timeX = width - PADDING_H - it.getLineWidth(0)
            val namePaint = if (isUnread) theme.dialogNameBoldPaint else theme.dialogNamePaint
            canvas.save()
            canvas.translate(timeX, textTop + (namePaint.textSize - theme.dialogTimePaint.textSize) / 2)
            it.draw(canvas)
            canvas.restore()
        }

        textTop += (nameLayout?.height ?: 0) + GAP_V

        previewLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
        }

        badgeLayout?.let { badge ->
            if (dm.unreadCount > 0) {
                val btw = badge.getLineWidth(0)
                val bw = maxOf(BADGE_MIN_W.toFloat(), btw + LayoutHelper.dp(10))
                val bh = BADGE_H.toFloat()
                val bx = width - PADDING_H - bw
                val by = textTop + (previewLayout?.height ?: 0) / 2f - bh / 2f
                canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), bh / 2, bh / 2, theme.dialogBadgePaint)
                canvas.save()
                canvas.translate(bx + (bw - btw) / 2, by + (bh - badge.height) / 2)
                badge.draw(canvas)
                canvas.restore()
            }
        }

        val divLeft = (cx + AVATAR_SIZE + GAP_H).toFloat()
        canvas.drawRect(divLeft, height - 1f, width.toFloat(), height.toFloat(), theme.dividerPaint)
    }

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(48)
        private val PADDING_H = LayoutHelper.dp(16)
        private val PADDING_V = LayoutHelper.dp(12)
        private val GAP_H = LayoutHelper.dp(12)
        private val GAP_V = LayoutHelper.dp(2)
        private val ONLINE_DOT = LayoutHelper.dp(12)
        private val ONLINE_BORDER = LayoutHelper.dp(2)
        private val BADGE_MIN_W = LayoutHelper.dp(20)
        private val BADGE_H = LayoutHelper.dp(20)
        private val CELL_HEIGHT = LayoutHelper.dp(72)
    }
}
