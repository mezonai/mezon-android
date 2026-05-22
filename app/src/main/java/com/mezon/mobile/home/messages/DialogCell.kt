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
import com.mezon.mobile.util.formatRelativeTime

class DialogCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    var directMessage: DirectMessage? = null
        private set
    var hasBuzz = false

    private val avatarDrawable = AvatarDrawable()
    private val avatarLoadState = ChannelAvatarLoadState()
    private var attachedToWindow = false
    private var needsLayout = false
    private var visibleOnScreen = true
    private val tmpRect = RectF()

    private var nameLayout: StaticLayout? = null
    private var previewLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null
    private var timeColor: Int = 0
    private var badgeLayout: StaticLayout? = null
    private var buzzLayout: StaticLayout? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        if (needsLayout) {
            needsLayout = false
            buildLayouts()
            invalidate()
        }
        directMessage?.let { loadAvatar(it) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attachedToWindow = false
        avatarLoadState.cancel()
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

    override fun invalidate() {
        if (directMessage == null) return
        super.invalidate()
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
            val changed = newDm != null && newDm != directMessage
            if (newDm != null) directMessage = newDm
            avatarDrawable.setInfo(dm.channelId, dm.avatarPlaceholderKey())
            buildLayouts()
            loadAvatar(dm)
            invalidate()
            return changed
        }

        if ((mask and NotificationCenter.UPDATE_MASK_NAME) != 0) {
            val oldName = directMessage?.displayName ?: ""
            if (oldName != dm.displayName) {
                rebuildLayout = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_AVATAR) != 0) {
            if (directMessage?.avatarUrl != dm.avatarUrl) {
                loadAvatar(dm)
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_NEW_MESSAGE) != 0 ||
            (mask and NotificationCenter.UPDATE_MASK_MESSAGE_TEXT) != 0
        ) {
            if (directMessage?.lastMessageContent != dm.lastMessageContent ||
                directMessage?.lastSentMessageTs != dm.lastSentMessageTs
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
        timeColor = if (isUnread) theme.primary else theme.onSurfaceVariant

        val timeText = if (dm.lastSentMessageTs > 0) formatRelativeTime(dm.lastSentMessageTs) else ""
        timeLayout = StaticLayout.Builder.obtain(timeText, 0, timeText.length, timePaint, contentWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val timeWidth = timeLayout?.let { it.getLineWidth(0).toInt() + TIME_GAP } ?: 0
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

        buzzLayout = if (hasBuzz) {
            val buzzText = "Buzz!!"
            StaticLayout.Builder.obtain(buzzText, 0, buzzText.length, theme.buzzBadgeTextPaint, contentWidth)
                .setMaxLines(1).build()
        } else null

        val buzzSpace = if (buzzLayout != null) {
            (buzzLayout!!.getLineWidth(0) + BUZZ_H_PAD * 2).toInt() + BADGE_GAP
        } else 0
        val badgeSpace = if (badgeLayout != null) BADGE_MIN_W + BADGE_GAP else 0
        val previewWidth = contentWidth - badgeSpace - buzzSpace
        val previewText = dm.lastMessageContent.ifEmpty { "No messages" }
        previewLayout = StaticLayout.Builder.obtain(previewText, 0, previewText.length, previewPaint, previewWidth.coerceAtLeast(0))
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun loadAvatar(dm: DirectMessage) {
        loadChannelAvatar(
            context,
            avatarDrawable,
            ChannelAvatarRequest(
                channelType = dm.type,
                avatarUrl = dm.avatarUrl,
                avatarId = dm.channelId,
                placeholderKey = dm.avatarPlaceholderKey(),
                sizePx = AVATAR_SIZE
            ),
            avatarLoadState,
            attachedToWindow
        ) { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        if (!visibleOnScreen) return
        val dm = directMessage ?: return
        val cx = PADDING_H
        val cy = (height - AVATAR_SIZE) / 2
        val isUnread = dm.unreadCount > 0

        avatarDrawable.setBounds(cx, cy, cx + AVATAR_SIZE, cy + AVATAR_SIZE)
        avatarDrawable.draw(canvas)

        val textLeft = (cx + AVATAR_SIZE + GAP_H).toFloat()
        var textTop = PADDING_V.toFloat()

        nameLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
        }

        timeLayout?.let {
            theme.dialogTimePaint.color = timeColor
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
                val bw = maxOf(BADGE_MIN_W.toFloat(), btw + BADGE_PAD)
                val bh = BADGE_H.toFloat()
                val bx = width - PADDING_H - bw
                val by = textTop + (previewLayout?.height ?: 0) / 2f - bh / 2f
                tmpRect.set(bx, by, bx + bw, by + bh)
                canvas.drawRoundRect(tmpRect, bh / 2, bh / 2, theme.dialogBadgePaint)
                canvas.save()
                canvas.translate(bx + (bw - btw) / 2, by + (bh - badge.height) / 2)
                badge.draw(canvas)
                canvas.restore()
            }
        }

        buzzLayout?.let { buzz ->
            val btw = buzz.getLineWidth(0)
            val bw = btw + BUZZ_H_PAD * 2
            val bh = BUZZ_BADGE_H.toFloat()
            val nameH = nameLayout?.height ?: 0
            val bx = textLeft + (nameLayout?.getLineWidth(0) ?: 0f) + BADGE_GAP
            val by = PADDING_V + (nameH - bh) / 2f
            tmpRect.set(bx, by, bx + bw, by + bh)
            canvas.drawRoundRect(tmpRect, BUZZ_RADIUS, BUZZ_RADIUS, theme.buzzBadgePaint)
            canvas.save()
            canvas.translate(bx + (bw - btw) / 2, by + (bh - buzz.height) / 2)
            buzz.draw(canvas)
            canvas.restore()
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
        private val BADGE_MIN_W = LayoutHelper.dp(20)
        private val BADGE_H = LayoutHelper.dp(20)
        private val CELL_HEIGHT = LayoutHelper.dp(72)
        private val BADGE_PAD = LayoutHelper.dp(10)
        private val BADGE_GAP = LayoutHelper.dp(8)
        private val TIME_GAP = LayoutHelper.dp(8)
        private val BUZZ_H_PAD = LayoutHelper.dp(4).toFloat()
        private val BUZZ_BADGE_H = LayoutHelper.dp(20)
        private val BUZZ_RADIUS = LayoutHelper.dpf(4f)
    }
}
