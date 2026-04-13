package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.util.createImgproxyUrl
import android.view.View

class UnreadDmCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    var directMessage: DirectMessage? = null
        private set

    private val avatar = AvatarDrawable()
    private val avatarSizePx = LayoutHelper.dp(36)
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(9f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.WHITE
    }
    private val badgeRect = RectF()
    private var badgeText = ""
    private var currentAvatarUrl: String? = null
    private var avatarCancellable: MezonImageLoader.Cancellable? = null

    fun setData(dm: DirectMessage) {
        directMessage = dm
        avatar.setInfo(dm.channelId, dm.displayName.ifEmpty { dm.label })
        badgeText = when {
            dm.unreadCount <= 0 -> ""
            dm.unreadCount > 99 -> "99+"
            else -> dm.unreadCount.toString()
        }
        loadAvatar(dm.avatarUrl)
        invalidate()
    }

    private fun loadAvatar(url: String) {
        if (url.isEmpty()) {
            avatar.setPhoto(null)
            currentAvatarUrl = null
            avatarCancellable?.cancel()
            avatarCancellable = null
            return
        }
        val imgUrl = createImgproxyUrl(url, avatarSizePx * 2, avatarSizePx * 2, "fill")
        if (imgUrl == currentAvatarUrl && avatar.hasPhoto()) return
        currentAvatarUrl = imgUrl

        avatarCancellable?.cancel()
        avatarCancellable = null

        val loader = MezonImageLoader.getInstance(context)
        val cached = loader.getBitmapFromMemory(imgUrl, avatarSizePx, avatarSizePx)
        if (cached != null) {
            avatar.setPhoto(cached)
            return
        }

        avatarCancellable = loader.load(
            imgUrl, avatarSizePx, avatarSizePx,
            onSuccess = { bmp ->
                avatar.setPhoto(bmp)
                post { invalidate() }
            }
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        avatarCancellable?.cancel()
        avatarCancellable = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, avatarSizePx + LayoutHelper.dp(12))
    }

    override fun onDraw(canvas: Canvas) {
        val dm = directMessage ?: return
        val cx = width / 2f
        val cy = height / 2f
        val halfSize = avatarSizePx / 2f

        val left = (cx - halfSize).toInt()
        val top = (cy - halfSize).toInt()
        val right = (cx + halfSize).toInt()
        val bottom = (cy + halfSize).toInt()

        avatar.setBounds(left, top, right, bottom)
        avatar.draw(canvas)

        if (dm.unreadCount > 0) {
            badgeBgPaint.color = themeColors.badgeRed
            val badgeH = LayoutHelper.dp(16f).toFloat()
            val textW = badgeTextPaint.measureText(badgeText)
            val padH = LayoutHelper.dp(4f).toFloat()
            val badgeW = (textW + padH * 2).coerceAtLeast(badgeH)
            val badgeRadius = badgeH / 2f

            val badgeRight = right.toFloat() + LayoutHelper.dp(2f)
            val badgeLeft = badgeRight - badgeW
            val badgeTop = top.toFloat() - LayoutHelper.dp(2f)
            badgeRect.set(badgeLeft, badgeTop, badgeRight, badgeTop + badgeH)

            canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint)
            val textY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(badgeText, badgeRect.centerX(), textY, badgeTextPaint)
        }
    }
}
