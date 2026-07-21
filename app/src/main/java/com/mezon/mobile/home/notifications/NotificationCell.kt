package com.mezon.mobile.home.notifications

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextUtils
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.convertTimestampToTimeAgo

class NotificationCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    var entity: NotificationEntity? = null
        private set

    var memberResolver: ((Long, Long, Long, Int) -> ClanMember?)? = null

    private val avatarDrawable = AvatarDrawable()
    private var currentAvatarUrl: String? = null
    private var avatarDisposable: MezonImageLoader.Cancellable? = null

    private var subjectLayout: StaticLayout? = null
    private var bodyLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        avatarDisposable?.cancel()
        avatarDisposable = null
    }

    override fun invalidate() {
        if (entity == null) return
        super.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), CELL_HEIGHT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildLayouts()
    }

    fun setData(n: NotificationEntity) {
        entity = n
        update(0)
    }

    fun update(mask: Int, newEntity: NotificationEntity? = null): Boolean {
        val n = newEntity ?: entity ?: return false

        if (mask == 0) {
            if (newEntity != null) entity = newEntity
            val displayName = resolveDisplayName(n)
            avatarDrawable.setInfo(n.senderId.takeIf { it != 0L } ?: n.id, displayName)
            buildLayouts()
            loadAvatar(resolveAvatarUrl(n))
            invalidate()
            return true
        }

        var rebuildLayout = false
        var needInvalidate = false

        if ((mask and NotificationCenter.UPDATE_MASK_MESSAGE_TEXT) != 0) {
            if (entity?.messageText != n.messageText) {
                rebuildLayout = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_AVATAR) != 0) {
            val avatarUrl = resolveAvatarUrl(n)
            if (entity?.avatarUrl != n.avatarUrl || currentAvatarUrl != avatarUrl) {
                loadAvatar(avatarUrl)
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_NAME) != 0) {
            avatarDrawable.setInfo(n.senderId.takeIf { it != 0L } ?: n.id, resolveDisplayName(n))
            rebuildLayout = true
        }

        if (newEntity != null) entity = newEntity

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
        val n = entity ?: return
        val contentWidth = width - PADDING_H * 2 - AVATAR_SIZE - GAP_H
        if (contentWidth <= 0) return

        val timeText = convertTimestampToTimeAgo(context, n.createTimeSeconds)
        val timePaint = theme.dialogTimePaint
        timePaint.color = theme.onSurfaceVariant
        timeLayout = StaticLayout.Builder
            .obtain(timeText, 0, timeText.length, timePaint, contentWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val timeWidth = timeLayout?.let { it.getLineWidth(0).toInt() + LayoutHelper.dp(8) } ?: 0
        val firstLineWidth = (contentWidth - timeWidth).coerceAtLeast(1)

        val subjectText = buildSubjectText(n)
        val subjectPaint = theme.dialogNameBoldPaint
        subjectLayout = StaticLayout.Builder
            .obtain(subjectText, 0, subjectText.length, subjectPaint, firstLineWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val bodyText = if (n.category == NOTIF_CATEGORY_FOR_YOU) "" else n.messageText
        val bodyPaint = theme.dialogMessagePaint
        bodyLayout = if (bodyText.isEmpty()) null else StaticLayout.Builder
            .obtain(bodyText, 0, bodyText.length, bodyPaint, contentWidth)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun buildSubjectText(n: NotificationEntity): String {
        return when (n.category) {
            NOTIF_CATEGORY_MENTIONS -> n.subject
            NOTIF_CATEGORY_MESSAGES -> n.senderName.ifEmpty { n.subject }
            NOTIF_CATEGORY_FOR_YOU -> buildForYouSubject(n)
            else -> n.subject
        }
    }

    private fun buildForYouSubject(n: NotificationEntity): String {
        val username = resolveForYouUsername(n)
        if (username.isEmpty()) return n.subject
        val subject = n.subject
        val notice = if (subject.contains(username)) subject.substring(username.length) else subject
        return username + notice
    }

    private fun resolveForYouUsername(n: NotificationEntity): String = n.senderUsername

    private fun resolveAvatarUrl(n: NotificationEntity): String {
        val fromEntity = n.avatarUrl.ifBlank { n.senderAvatar }
        if (fromEntity.isNotEmpty()) return fromEntity
        val member = memberResolver?.invoke(n.senderId, n.clanId, n.channelId, n.channelType)
        return member?.let { it.clanAvatar.ifBlank { it.avatarUrl } }.orEmpty()
    }

    private fun resolveDisplayName(n: NotificationEntity): String {
        if (n.category == NOTIF_CATEGORY_FOR_YOU) {
            val username = resolveForYouUsername(n)
            if (username.isNotEmpty()) return username
        }
        val member = memberResolver?.invoke(n.senderId, n.clanId, n.channelId, n.channelType)
        if (member != null) {
            return member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
        }
        return n.senderName.ifBlank { n.senderUsername }
    }

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarDrawable.setPhoto(null)
        avatarDrawable.setLoadingPlaceholder(false)
        avatarDisposable?.cancel()
        avatarDisposable = null
        if (url.isNotEmpty()) {
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
    }

    override fun onDraw(canvas: Canvas) {
        val avatarLeft = PADDING_H
        val avatarTop = (height - AVATAR_SIZE) / 2
        avatarDrawable.setBounds(avatarLeft, avatarTop, avatarLeft + AVATAR_SIZE, avatarTop + AVATAR_SIZE)
        avatarDrawable.draw(canvas)

        val textLeft = (avatarLeft + AVATAR_SIZE + GAP_H).toFloat()
        var textTop = PADDING_V.toFloat()

        if (bodyLayout == null) {
            val subjectHeight = subjectLayout?.height ?: 0
            textTop = (height - subjectHeight) / 2f
        }

        subjectLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
        }

        timeLayout?.let {
            val tx = width - PADDING_H - it.getLineWidth(0)
            canvas.save()
            canvas.translate(tx, textTop + (theme.dialogNameBoldPaint.textSize - theme.dialogTimePaint.textSize) / 2)
            it.draw(canvas)
            canvas.restore()
        }

        textTop += (subjectLayout?.height ?: 0) + BODY_GAP_AFTER_SUBJECT_PX

        bodyLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
        }

        val divLeft = (PADDING_H + AVATAR_SIZE + GAP_H).toFloat()
        canvas.drawRect(divLeft, height - 1f, width.toFloat(), height.toFloat(), theme.dividerPaint)
    }

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(44)
        private val PADDING_H = LayoutHelper.dp(16)
        private val PADDING_V = LayoutHelper.dp(14)
        private val GAP_H = LayoutHelper.dp(12)
        private val CELL_HEIGHT = LayoutHelper.dp(76)
        private val BODY_GAP_AFTER_SUBJECT_PX = LayoutHelper.dp(2).toFloat()
    }
}
