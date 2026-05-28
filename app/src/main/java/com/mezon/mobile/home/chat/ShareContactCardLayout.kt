package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.mezon.mobile.R
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.ShareContactData
import com.mezon.mobile.util.avatarImgproxyUrl

enum class ShareContactHit {
    None, Profile, Call, Message
}

class ShareContactCardLayout(private val context: Context) {

    var invalidateCallback: (() -> Unit)? = null

    private val avatarDrawable = AvatarDrawable()
    private var avatarCancellable: MezonImageLoader.Cancellable? = null
    private var currentAvatarUrl = ""
    private val cardRect = RectF()
    private val clipPath = Path()
    private val avatarClipPath = Path()
    private val profileHitRect = RectF()
    private val callHitRect = RectF()
    private val messageHitRect = RectF()

    private var displayLayout: StaticLayout? = null
    private var usernameLayout: StaticLayout? = null
    private var callLabelLayout: StaticLayout? = null
    private var messageLabelLayout: StaticLayout? = null

    private var callIcon: Drawable? = null
    private var messageIcon: Drawable? = null

    private var headerHeight = 0
    private var actionRowHeight = 0
    private var textBlockTop = 0f
    private var showOnline = false
    private var messageEnabled = true
    private var callEnabled = true
    private var blockedNoticeLayout: StaticLayout? = null
    private var pressedAction = ShareContactHit.None
    private var rippleLight = false

    var cardWidth = 0
        private set
    var blockHeight = 0
        private set

    private var cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val actionTopDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val onlineDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var actionTint = 0

    fun setPressedAction(hit: ShareContactHit) {
        if (pressedAction == hit) return
        pressedAction = hit
        invalidateCallback?.invoke()
    }

    fun prepare(
        data: ShareContactData,
        theme: ThemeColors,
        maxBubbleWidth: Int,
        isOnline: Boolean = false,
        messageEnabled: Boolean = true,
        callEnabled: Boolean = true,
        blockedNotice: String? = null
    ) {
        cardWidth = (maxBubbleWidth * 0.9f).toInt().coerceAtLeast(LayoutHelper.dp(200))
        showOnline = isOnline
        this.messageEnabled = messageEnabled
        this.callEnabled = callEnabled
        rippleLight = theme.resolvedMode == com.mezon.mobile.ui.theme.ThemeMode.LIGHT
        val innerPadH = LayoutHelper.dp(12)
        val headerPadV = LayoutHelper.dp(16)
        val textBlockW = (cardWidth - innerPadH - TEXT_RIGHT_PAD - AVATAR_SIZE - AVATAR_GAP).coerceAtLeast(1)

        DISPLAY_PAINT.typeface = Typeface.DEFAULT_BOLD
        DISPLAY_PAINT.textSize = LayoutHelper.sp(14f)
        DISPLAY_PAINT.color = theme.textStrong

        USERNAME_PAINT.textSize = LayoutHelper.sp(12f)
        USERNAME_PAINT.color = theme.tabLabelInactive

        ACTION_PAINT.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        ACTION_PAINT.isFakeBoldText = false
        ACTION_PAINT.textSize = LayoutHelper.sp(14f)

        val display = data.displayName.ifBlank { data.username }
        displayLayout = StaticLayout.Builder.obtain(display, 0, display.length, DISPLAY_PAINT, textBlockW)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val userLine = "@${data.username}"
        usernameLayout = StaticLayout.Builder.obtain(userLine, 0, userLine.length, USERNAME_PAINT, textBlockW)
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val notice = blockedNotice?.trim().orEmpty()
        blockedNoticeLayout = if (notice.isNotEmpty()) {
            NOTICE_PAINT.color = theme.redStrong
            StaticLayout.Builder.obtain(notice, 0, notice.length, NOTICE_PAINT, textBlockW)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        } else {
            null
        }

        avatarDrawable.setInfo(data.userId, data.username)
        loadAvatar(data.avatarUrl)

        CARD_BORDER_PAINT.color = theme.borderDim
        cardBgPaint.color = theme.channelPanelBg
        dividerPaint.color = theme.border
        actionTopDividerPaint.color = theme.borderDim
        onlineDotPaint.color = theme.onlineGreen
        val actionsEnabled = messageEnabled || callEnabled
        actionTint = if (actionsEnabled) theme.tabLabelInactive else theme.textDisabled

        val noticeH = if (blockedNoticeLayout != null) {
            BLOCKED_NOTICE_GAP + (blockedNoticeLayout?.height ?: 0)
        } else {
            0
        }
        val textStackH = (displayLayout?.height ?: 0) + USERNAME_GAP + (usernameLayout?.height ?: 0) + noticeH
        val headerContentH = maxOf(AVATAR_SIZE, textStackH)
        headerHeight = headerPadV * 2 + headerContentH
        textBlockTop = headerPadV + (headerContentH - textStackH) / 2f

        val callTint = if (callEnabled) actionTint else theme.textDisabled
        val messageTint = if (messageEnabled) actionTint else theme.textDisabled
        callIcon = MezonIcon.phoneCallIcon.getDrawable(context).mutate().apply {
            setColorFilter(callTint, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        messageIcon = MezonIcon.chatIcon.getDrawable(context).mutate().apply {
            setColorFilter(messageTint, android.graphics.PorterDuff.Mode.SRC_IN)
        }

        val callLabel = context.getString(R.string.user_profile_voice_call)
        val msgLabel = context.getString(R.string.share_contact_message)
        ACTION_PAINT.color = callTint
        callLabelLayout = StaticLayout.Builder.obtain(callLabel, 0, callLabel.length, ACTION_PAINT, LayoutHelper.dp(140))
            .setMaxLines(1)
            .build()
        ACTION_PAINT.color = messageTint
        messageLabelLayout = StaticLayout.Builder.obtain(msgLabel, 0, msgLabel.length, ACTION_PAINT, LayoutHelper.dp(140))
            .setMaxLines(1)
            .build()
        ACTION_PAINT.color = callTint

        val labelH = maxOf(callLabelLayout?.height ?: 0, messageLabelLayout?.height ?: 0)
        actionRowHeight = ACTION_ROW_PAD_V * 2 + maxOf(ACTION_ICON_SIZE, labelH)
        blockHeight = headerHeight + actionRowHeight

        profileHitRect.set(0f, 0f, cardWidth.toFloat(), headerHeight.toFloat())
        val actionTop = headerHeight.toFloat()
        val halfW = cardWidth / 2f
        callHitRect.set(0f, actionTop, halfW, blockHeight.toFloat())
        messageHitRect.set(halfW, actionTop, cardWidth.toFloat(), blockHeight.toFloat())
        cardRect.set(0f, 0f, cardWidth.toFloat(), blockHeight.toFloat())
    }

    fun draw(canvas: Canvas, left: Float, top: Float) {
        canvas.save()
        canvas.translate(left, top)
        clipPath.reset()
        clipPath.addRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, cardBgPaint)
        val actionTop = headerHeight.toFloat()
        canvas.drawLine(0f, actionTop, cardWidth.toFloat(), actionTop, actionTopDividerPaint)
        canvas.drawLine(cardWidth / 2f, actionTop, cardWidth / 2f, blockHeight.toFloat(), dividerPaint)

        val innerPadH = LayoutHelper.dp(12).toFloat()
        val headerPadV = LayoutHelper.dp(16).toFloat()
        val avatarLeft = innerPadH
        val avatarTop = headerPadV + (headerHeight - headerPadV * 2 - AVATAR_SIZE) / 2f
        drawAvatar(canvas, avatarLeft, avatarTop)

        val textLeft = avatarLeft + AVATAR_SIZE + AVATAR_GAP
        var textY = textBlockTop
        displayLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textY)
            it.draw(canvas)
            canvas.restore()
            textY += it.height
        }
        usernameLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textY + USERNAME_GAP)
            it.draw(canvas)
            canvas.restore()
            textY += USERNAME_GAP + it.height
        }
        blockedNoticeLayout?.let {
            canvas.save()
            canvas.translate(textLeft, textY + BLOCKED_NOTICE_GAP)
            it.draw(canvas)
            canvas.restore()
        }

        if (callEnabled && pressedAction == ShareContactHit.Call) {
            drawActionRipple(canvas, callHitRect)
        }
        if (messageEnabled && pressedAction == ShareContactHit.Message) {
            drawActionRipple(canvas, messageHitRect)
        }
        drawActionButton(canvas, callHitRect, callIcon, callLabelLayout)
        drawActionButton(canvas, messageHitRect, messageIcon, messageLabelLayout)
        canvas.restore()

        canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, CARD_BORDER_PAINT)
        canvas.restore()
    }

    private fun drawAvatar(canvas: Canvas, left: Float, top: Float) {
        val cx = left + AVATAR_SIZE / 2f
        val cy = top + AVATAR_SIZE / 2f
        val outerR = AVATAR_SIZE / 2f
        avatarClipPath.reset()
        avatarClipPath.addCircle(cx, cy, outerR, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(avatarClipPath)
        avatarDrawable.setBounds(
            left.toInt(),
            top.toInt(),
            (left + AVATAR_SIZE).toInt(),
            (top + AVATAR_SIZE).toInt()
        )
        avatarDrawable.draw(canvas)
        canvas.restore()
        if (showOnline) {
            val dotR = ONLINE_DOT_SIZE / 2f
            canvas.drawCircle(
                left + AVATAR_SIZE - dotR + ONLINE_DOT_INSET,
                top + AVATAR_SIZE - dotR + ONLINE_DOT_INSET,
                dotR,
                onlineDotPaint
            )
        }
    }

    private fun drawActionRipple(canvas: Canvas, rect: RectF) {
        RIPPLE_PAINT.color = if (rippleLight) 0x26000000 else 0x33FFFFFF
        canvas.drawRect(rect, RIPPLE_PAINT)
    }

    private fun drawActionButton(
        canvas: Canvas,
        hit: RectF,
        icon: Drawable?,
        label: StaticLayout?
    ) {
        val iconSz = ACTION_ICON_SIZE
        val gap = ACTION_ICON_GAP
        val labelW = if (label != null && label.lineCount > 0) {
            label.getLineWidth(0).toInt()
        } else {
            0
        }
        val labelH = label?.height ?: 0
        val contentW = iconSz + gap + labelW
        val contentH = maxOf(iconSz, labelH)
        val startX = hit.left + (hit.width() - contentW) / 2f
        val centerY = (hit.top + hit.bottom) / 2f
        val contentTop = centerY - contentH / 2f
        icon?.let {
            val il = startX.toInt()
            val iconTop = (contentTop + (contentH - iconSz) / 2f).toInt()
            it.setBounds(il, iconTop, il + iconSz, iconTop + iconSz)
            it.draw(canvas)
        }
        label?.let {
            canvas.save()
            canvas.translate(
                startX + iconSz + gap,
                contentTop + (contentH - labelH) / 2f
            )
            it.draw(canvas)
            canvas.restore()
        }
    }

    fun hitTest(localX: Float, localY: Float): ShareContactHit {
        if (profileHitRect.contains(localX, localY)) return ShareContactHit.Profile
        if (callEnabled && callHitRect.contains(localX, localY)) return ShareContactHit.Call
        if (messageEnabled && messageHitRect.contains(localX, localY)) return ShareContactHit.Message
        return ShareContactHit.None
    }

    private fun loadAvatar(url: String) {
        if (url == currentAvatarUrl && avatarDrawable.hasPhoto()) return
        currentAvatarUrl = url
        avatarCancellable?.cancel()
        avatarCancellable = null
        if (url.isEmpty()) {
            avatarDrawable.setPhoto(null)
            avatarDrawable.setDrawableByInfo(true)
            return
        }
        val proxyUrl = avatarImgproxyUrl(url, AVATAR_SIZE)
        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(proxyUrl, AVATAR_SIZE, AVATAR_SIZE)
        if (cached != null) {
            avatarDrawable.setPhoto(cached)
            avatarDrawable.setDrawableByInfo(true)
            return
        }
        avatarDrawable.setDrawableByInfo(false)
        avatarCancellable = loader.load(proxyUrl, AVATAR_SIZE, AVATAR_SIZE, onSuccess = { bmp ->
            avatarDrawable.setPhoto(bmp)
            avatarDrawable.setDrawableByInfo(true)
            invalidateCallback?.invoke()
        }, onError = {
            avatarDrawable.setDrawableByInfo(true)
            invalidateCallback?.invoke()
        })
    }

    fun clear() {
        avatarCancellable?.cancel()
        avatarCancellable = null
        currentAvatarUrl = ""
        showOnline = false
        messageEnabled = true
        callEnabled = true
        blockedNoticeLayout = null
        pressedAction = ShareContactHit.None
        displayLayout = null
        usernameLayout = null
        callLabelLayout = null
        messageLabelLayout = null
        avatarDrawable.setPhoto(null)
        avatarDrawable.setDrawableByInfo(true)
        cardWidth = 0
        blockHeight = 0
        headerHeight = 0
        actionRowHeight = 0
        textBlockTop = 0f
        profileHitRect.setEmpty()
        callHitRect.setEmpty()
        messageHitRect.setEmpty()
    }

    companion object {
        private val AVATAR_SIZE = LayoutHelper.dp(40)
        private val AVATAR_GAP = LayoutHelper.dp(12)
        private val TEXT_RIGHT_PAD = LayoutHelper.dp(10)
        private val USERNAME_GAP = LayoutHelper.dp(2)
        private val BLOCKED_NOTICE_GAP = LayoutHelper.dp(4)
        private val NOTICE_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(11f)
        }
        private val CARD_RADIUS = LayoutHelper.dpf(12f)
        private val ACTION_ROW_PAD_V = LayoutHelper.dp(10)
        private val ACTION_ICON_SIZE = LayoutHelper.dp(18)
        private val ACTION_ICON_GAP = LayoutHelper.dp(6)
        private val ONLINE_DOT_SIZE = LayoutHelper.dp(14)
        private val ONLINE_DOT_INSET = LayoutHelper.dp(-2).toFloat()
        private val TMP_RECT = RectF()
        private val RIPPLE_PAINT = Paint(Paint.ANTI_ALIAS_FLAG)
        private val CARD_BORDER_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LayoutHelper.dp(1f).toFloat()
        }
        private val DISPLAY_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val USERNAME_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val ACTION_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG)
    }
}
