package com.mezon.mobile.home.notifications

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextUtils
import com.mezon.mobile.R
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.chat.SdTopicEntity
import com.mezon.mobile.util.TopicOriginalPreviewToken
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.convertTimestampToTimeAgo

class TopicNotificationCell(
    context: Context,
    private val theme: ThemeColors
) : BaseCell(context) {

    var entity: SdTopicEntity? = null
        private set

    var memberResolver: ((SdTopicEntity) -> ClanMember?)? = null

    private val avatarDrawable = AvatarDrawable()
    private var currentAvatarUrl: String? = null
    private var avatarDisposable: MezonImageLoader.Cancellable? = null
    private var layoutWidth = 0
    private var measuredCellHeight = MIN_HEIGHT

    private var headerLayout: StaticLayout? = null
    private var repliedToLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null

    override fun invalidate() {
        if (entity == null) return
        super.invalidate()
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
            rebuildFromEntity()
        }
    }

    fun update(mask: Int, newEntity: SdTopicEntity? = null): Boolean {
        val item = newEntity ?: entity ?: return false
        if (newEntity != null) entity = newEntity

        val member = memberResolver?.invoke(item)
        val displayName = member?.displayLabel().orEmpty()
        val avatarUrl = member?.avatarUrl().orEmpty()
        val senderId = item.senderIdForAvatar()
        val avatarLabel = displayName.ifBlank { member?.username.orEmpty() }
        avatarDrawable.setInfo(senderId, avatarLabel)

        val rebuildText = mask == 0 ||
            (mask and NotificationCenter.UPDATE_MASK_MESSAGE_TEXT) != 0 ||
            (mask and NotificationCenter.UPDATE_MASK_NAME) != 0
        if (rebuildText) {
            if (layoutWidth > 0) {
                buildLayouts(item, layoutWidth)
                measuredCellHeight = computeHeight()
            }
        }
        if (mask == 0 || (mask and NotificationCenter.UPDATE_MASK_AVATAR) != 0 || (mask and NotificationCenter.UPDATE_MASK_NAME) != 0) {
            loadAvatar(avatarUrl)
        }
        if (rebuildText) requestLayout()
        invalidate()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0 && w != layoutWidth) {
            layoutWidth = w
            rebuildFromEntity()
        }
        setMeasuredDimension(w, measuredCellHeight.coerceAtLeast(MIN_HEIGHT))
    }

    override fun onDraw(canvas: Canvas) {
        val avatarLeft = PADDING_H
        val avatarTop = (height - AVATAR_SIZE) / 2
        avatarDrawable.setBounds(
            avatarLeft,
            avatarTop,
            avatarLeft + AVATAR_SIZE,
            avatarTop + AVATAR_SIZE
        )
        avatarDrawable.draw(canvas)

        val textLeft = (avatarLeft + AVATAR_SIZE + GAP_H).toFloat()
        var textTop = ((height - textBlockHeight()) / 2f).coerceAtLeast(PADDING_V.toFloat())
        val headerTop = textTop

        headerLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
            textTop += it.height + GAP_AFTER_HEADER
        }

        repliedToLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textTop)
            it.draw(canvas)
            canvas.restore()
        }

        timeLayout?.let {
            val tx = width - PADDING_H - it.getLineWidth(0)
            canvas.save()
            canvas.translate(tx, headerTop)
            it.draw(canvas)
            canvas.restore()
        }

        canvas.drawRect(
            textLeft,
            height - 1f,
            width.toFloat(),
            height.toFloat(),
            theme.dividerPaint
        )
    }

    private fun rebuildFromEntity() {
        val item = entity ?: return
        buildLayouts(item, layoutWidth)
        measuredCellHeight = computeHeight()
    }

    private fun computeHeight(): Int {
        return PADDING_V * 2 + AVATAR_SIZE.coerceAtLeast(textBlockHeight())
    }

    private fun textBlockHeight(): Int {
        val headerH = headerLayout?.height ?: 0
        val repliedH = repliedToLayout?.height ?: 0
        if (headerH == 0 && repliedH == 0) return 0
        return headerH + GAP_AFTER_HEADER + repliedH
    }

    private fun buildLayouts(item: SdTopicEntity, width: Int) {
        if (width <= 0) return
        val contentWidth = width - PADDING_H * 2 - AVATAR_SIZE - GAP_H
        if (contentWidth <= 0) return

        val timeText = convertTimestampToTimeAgo(context, item.createTimeSeconds)
        val timePaint = theme.dialogTimePaint
        timeLayout = StaticLayout.Builder
            .obtain(timeText, 0, timeText.length, timePaint, contentWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val timeWidth = timeLayout?.let { it.getLineWidth(0).toInt() + LayoutHelper.dp(8) } ?: 0
        val headerWidth = (contentWidth - timeWidth).coerceAtLeast(1)
        val headerText = context.getString(R.string.notif_topic_discussion).uppercase()
        headerLayout = StaticLayout.Builder
            .obtain(headerText, 0, headerText.length, theme.dialogNameBoldPaint, headerWidth)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val repliedPrefix = context.getString(R.string.notif_original_message)
        val repliedText = "$repliedPrefix ${localizedRootMessagePreview(item.rootMessagePreview)}"
        repliedToLayout = StaticLayout.Builder
            .obtain(repliedText, 0, repliedText.length, theme.dialogMessagePaint, contentWidth)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    }

    private fun localizedRootMessagePreview(preview: String): String =
        when (preview) {
            TopicOriginalPreviewToken.ATTACHMENT -> context.getString(R.string.notif_topic_original_attachment)
            TopicOriginalPreviewToken.CONTACT -> context.getString(R.string.notif_topic_original_contact)
            TopicOriginalPreviewToken.INTERACTIVE_MESSAGE -> {
                context.getString(R.string.notif_topic_original_interactive_message)
            }
            else -> preview
        }

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarDrawable.setPhoto(null)
        avatarDisposable?.cancel()
        avatarDisposable = null
        if (url.isNotEmpty()) {
            val proxyUrl = avatarImgproxyUrl(url, AVATAR_SIZE)
            avatarDisposable = MezonImageLoader.getInstance(context).load(
                proxyUrl, AVATAR_SIZE, AVATAR_SIZE,
                onSuccess = { bmp ->
                    avatarDrawable.setPhoto(bmp)
                    invalidate()
                }
            )
        }
    }

    private fun ClanMember.displayLabel(): String =
        clanNick.ifBlank { displayName.ifBlank { username } }

    private fun ClanMember.avatarUrl(): String =
        clanAvatar.ifBlank { avatarUrl }

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(44)
        private val PADDING_H = LayoutHelper.dp(16)
        private val PADDING_V = LayoutHelper.dp(12)
        private val GAP_H = LayoutHelper.dp(12)
        private val GAP_AFTER_HEADER = LayoutHelper.dp(2)
        private val MIN_HEIGHT = LayoutHelper.dp(88)
    }
}
