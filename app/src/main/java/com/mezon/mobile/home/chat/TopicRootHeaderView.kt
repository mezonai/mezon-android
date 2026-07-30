package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.UserDisplayRole
import com.mezon.mobile.util.MezonDisplayColors
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.formatRelativeTime

class TopicRootHeaderView(
    context: Context,
    private val theme: ThemeColors
) : LinearLayout(context) {

    private val metaView = TopicRootMetaView(context, theme)
    private val messageCell = ChatMessageCell(context, theme).apply {
        isCombined = true
        isInPinMode = true
        isTopicHeaderContent = true
        topicButtonEnabled = false
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val borderRect = RectF()
    private var configuredClanId = 0L
    private var displayRoleResolver: ((Long) -> UserDisplayRole?)? = null

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding(PAD_H, 0, PAD_H, PAD_BOTTOM)
        borderPaint.color = theme.secondaryLight
        borderPaint.strokeWidth = LayoutHelper.dp(1).toFloat()
        val metaLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = USER_INFO_MARGIN_V
            bottomMargin = USER_INFO_MARGIN_V
        }
        addView(metaView, metaLp)
        addView(messageCell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun configure(clanId: Long, roleResolver: (Long) -> UserDisplayRole?) {
        configuredClanId = clanId
        displayRoleResolver = roleResolver
        messageCell.clanId = clanId
        metaView.displayRoleResolver = roleResolver
    }

    fun setRootMessage(message: MessageEntity?) {
        if (message == null) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        val name = message.senderName.ifBlank { message.senderUsername }
        metaView.setInfo(message, name)
        val displayMsg = message.copy(
            code = MessageEntity.CODE_CHAT,
            topicId = 0L,
            topicCreatorId = 0L,
            rplCount = 0,
            lastSentSeconds = 0L
        )
        messageCell.clanId = configuredClanId
        messageCell.update(0, displayMsg)
        requestLayout()
    }

    fun refreshDisplayRole() {
        metaView.refreshDisplayRole()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (visibility == GONE) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val y = height - borderPaint.strokeWidth / 2f
        borderRect.set(paddingLeft.toFloat(), y, (width - paddingRight).toFloat(), y)
        canvas.drawLine(borderRect.left, y, borderRect.right, y, borderPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        metaView.onDetach()
    }

    companion object {
        private val PAD_H = LayoutHelper.dp(10)
        private val PAD_BOTTOM = LayoutHelper.dp(14)
        private val USER_INFO_MARGIN_V = LayoutHelper.dp(10)
    }
}

private class TopicRootMetaView(
    context: Context,
    private val theme: ThemeColors
) : View(context) {

    var displayRoleResolver: ((Long) -> UserDisplayRole?)? = null

    private val avatarDrawable = AvatarDrawable()
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(15f)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(12f)
    }
    private val roleIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val roleIconRect = RectF()
    private var nameLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null
    private var avatarDisposable: MezonImageLoader.Cancellable? = null
    private var roleIconDisposable: MezonImageLoader.Cancellable? = null
    private var roleIconBitmap: android.graphics.Bitmap? = null
    private var roleIconUrl: String? = null
    private var reserveRoleIcon = false
    private var currentAvatarUrl: String? = null
    private var layoutWidth = 0
    private var boundMessage: MessageEntity? = null
    private var boundName: String = ""

    fun setInfo(message: MessageEntity, name: String) {
        boundMessage = message
        boundName = name
        avatarDrawable.setInfo(message.senderId, name)
        if (currentAvatarUrl != message.senderAvatar) {
            currentAvatarUrl = message.senderAvatar
            avatarDisposable?.cancel()
            if (message.senderAvatar.isNotBlank()) {
                val proxy = avatarImgproxyUrl(message.senderAvatar, AVATAR_SIZE)
                avatarDrawable.setLoadingPlaceholder(true)
                avatarDisposable = MezonImageLoader.getInstance(context).load(
                    proxy,
                    AVATAR_SIZE,
                    AVATAR_SIZE,
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
            } else {
                avatarDrawable.setLoadingPlaceholder(false)
                avatarDrawable.setPhoto(null)
            }
        }
        applyNameColor(message.senderId)
        loadRoleIcon(message.senderId)
        rebuildTextLayouts()
        requestLayout()
        invalidate()
    }

    fun refreshDisplayRole() {
        val message = boundMessage ?: return
        applyNameColor(message.senderId)
        loadRoleIcon(message.senderId)
        rebuildTextLayouts()
        requestLayout()
        invalidate()
    }

    fun onDetach() {
        avatarDisposable?.cancel()
        avatarDisposable = null
        roleIconDisposable?.cancel()
        roleIconDisposable = null
    }

    private fun applyNameColor(senderId: Long) {
        val dr = displayRoleResolver?.invoke(senderId)
        namePaint.color = when {
            dr != null && dr.color != 0 -> dr.color
            else -> theme.onSurface
        }
        if (namePaint.color == 0) {
            namePaint.color = MezonDisplayColors.DEFAULT_MESSAGE_CREATOR_NAME
        }
        timePaint.color = theme.onSurfaceVariant
    }

    private fun loadRoleIcon(senderId: Long) {
        val iconUrl = displayRoleResolver?.invoke(senderId)?.iconUrl?.trim().orEmpty()
        reserveRoleIcon = iconUrl.isNotEmpty()
        if (!reserveRoleIcon) {
            roleIconDisposable?.cancel()
            roleIconDisposable = null
            roleIconUrl = null
            roleIconBitmap = null
            return
        }
        if (iconUrl == roleIconUrl && roleIconBitmap != null) return
        roleIconDisposable?.cancel()
        roleIconUrl = iconUrl
        roleIconBitmap = null
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(iconUrl, ROLE_ICON_SIZE, ROLE_ICON_SIZE)
        if (cached != null) {
            roleIconBitmap = cached
            rebuildTextLayouts()
            invalidate()
            return
        }
        roleIconDisposable = loader.load(iconUrl, ROLE_ICON_SIZE, ROLE_ICON_SIZE, onSuccess = { bmp ->
            if (roleIconUrl == iconUrl) {
                roleIconBitmap = bmp
                rebuildTextLayouts()
                invalidate()
            }
        }, onError = {
            if (roleIconUrl == iconUrl) {
                roleIconBitmap = null
                rebuildTextLayouts()
                invalidate()
            }
        })
    }

    private fun rebuildTextLayouts() {
        if (layoutWidth <= 0 || boundMessage == null) return
        val iconReserve = if (reserveRoleIcon) ROLE_ICON_SIZE + ROLE_ICON_GAP else 0
        val textW = (layoutWidth - AVATAR_SIZE - AVATAR_TEXT_GAP - iconReserve).coerceAtLeast(1)
        nameLayout = StaticLayout.Builder.obtain(boundName, 0, boundName.length, namePaint, textW)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val timeText = formatRelativeTime(boundMessage!!.timestampSeconds)
        timeLayout = StaticLayout.Builder.obtain(timeText, 0, timeText.length, timePaint, textW)
            .setMaxLines(1)
            .build()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != layoutWidth) {
            layoutWidth = w
            rebuildTextLayouts()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0 && w != layoutWidth) {
            layoutWidth = w
            rebuildTextLayouts()
        }
        val nameH = nameLayout?.height ?: 0
        val timeH = timeLayout?.height ?: 0
        val textBlockH = if (nameH > 0 || timeH > 0) nameH + TIME_GAP + timeH else 0
        val rowH = maxOf(AVATAR_SIZE, textBlockH)
        setMeasuredDimension(w, rowH)
    }

    override fun onDraw(canvas: Canvas) {
        val nameH = nameLayout?.height ?: 0
        val timeH = timeLayout?.height ?: 0
        val textBlockH = if (nameH > 0 || timeH > 0) nameH + TIME_GAP + timeH else 0
        val rowH = maxOf(AVATAR_SIZE, textBlockH)
        val avatarTop = (rowH - AVATAR_SIZE) / 2
        val textTop = (rowH - textBlockH) / 2
        val textLeft = AVATAR_SIZE + AVATAR_TEXT_GAP
        avatarDrawable.setBounds(0, avatarTop, AVATAR_SIZE, avatarTop + AVATAR_SIZE)
        avatarDrawable.draw(canvas)
        var y = textTop.toFloat()
        nameLayout?.let {
            canvas.save()
            canvas.translate(textLeft.toFloat(), y)
            it.draw(canvas)
            canvas.restore()
            if (reserveRoleIcon && roleIconBitmap != null) {
                val iconX = textLeft + it.getLineWidth(0).toInt() + ROLE_ICON_GAP
                val iconY = y + (it.height - ROLE_ICON_SIZE) / 2f
                roleIconRect.set(
                    iconX.toFloat(),
                    iconY,
                    (iconX + ROLE_ICON_SIZE).toFloat(),
                    iconY + ROLE_ICON_SIZE
                )
                canvas.drawBitmap(roleIconBitmap!!, null, roleIconRect, roleIconPaint)
            }
            y += it.height + TIME_GAP
        }
        timeLayout?.let {
            canvas.save()
            canvas.translate(textLeft.toFloat(), y)
            it.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(40)
        private val AVATAR_TEXT_GAP = LayoutHelper.dp(10)
        private val ROLE_ICON_SIZE = LayoutHelper.dp(20)
        private val ROLE_ICON_GAP = LayoutHelper.dp(4)
        private val TIME_GAP = LayoutHelper.dp(2)
    }
}
