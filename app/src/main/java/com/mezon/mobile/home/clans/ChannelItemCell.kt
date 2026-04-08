package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class ChannelItemCell(
    context: Context,
    private val themeColors: ThemeColors
) : BaseCell(context) {

    companion object {
        private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(16f)
        }
        private val activeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val unreadDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val unreadBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val unreadBadgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(10f)
            color = 0xFFFFFFFF.toInt()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val activeBgRectF = RectF()
        private val mutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LayoutHelper.dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        fun resolveChannelIcon(type: Int, isPrivate: Boolean): MezonIcon = when (type) {
            CHANNEL_TYPE_VOICE -> MezonIcon.channelVoice
            CHANNEL_TYPE_STREAMING -> MezonIcon.channelStream
            CHANNEL_TYPE_APP -> MezonIcon.channelApp
            CHANNEL_TYPE_FORUM -> MezonIcon.forumIcon
            CHANNEL_TYPE_ANNOUNCEMENT -> MezonIcon.announcementIcon
            else -> if (isPrivate) MezonIcon.channelTextLock else MezonIcon.channelText
        }
    }

    var channel: ClanChannelEntity? = null
        private set
    private var isActive = false
    private var truncatedName: CharSequence = ""
    private var currentIconDrawable: Drawable? = null
    private var currentIconType: Int = -1
    private var currentIconPrivate: Boolean = false

    private val cellHeightPx = LayoutHelper.dp(40)
    private val paddingHPx = LayoutHelper.dp(16)
    private val iconSizePx = LayoutHelper.dp(12)
    private val iconMarginPx = LayoutHelper.dp(8)
    private val badgeSizePx = LayoutHelper.dp(20)
    private val unreadDotRadius = LayoutHelper.dp(3f).toFloat()



    fun bind(channel: ClanChannelEntity, active: Boolean) {
        this.channel = channel
        this.isActive = active
        truncatedName = ""
        invalidate()
    }

    fun update(mask: Int, newChannel: ClanChannelEntity? = null): Boolean {
        val ch = newChannel ?: channel ?: return false

        if (mask == 0) {
            if (newChannel != null) channel = newChannel
            truncatedName = ""
            invalidate()
            return true
        }

        var needInvalidate = false

        if ((mask and NotificationCenter.UPDATE_MASK_BADGE) != 0) {
            if (channel?.unreadCount != ch.unreadCount || channel?.hasUnread != ch.hasUnread) {
                needInvalidate = true
                truncatedName = ""
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_CHAT_NAME) != 0) {
            if (channel?.channelLabel != ch.channelLabel) {
                truncatedName = ""
                needInvalidate = true
            }
        }

        if (newChannel != null) channel = newChannel

        if (needInvalidate) {
            invalidate()
        }
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), cellHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        val ch = channel ?: return
        val hasUnread = ch.hasUnread
        val hasMentionBadge = ch.unreadCount > 0

        val textColor = when {
            isActive || hasUnread -> themeColors.onSurface
            else -> themeColors.onSurfaceVariant
        }
        namePaint.color = textColor
        namePaint.typeface = if (hasUnread) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT

        if (isActive) {
            activeBgPaint.color = themeColors.primaryContainer
            val r = LayoutHelper.dp(6).toFloat()
            activeBgRectF.set(
                paddingHPx / 2f, (height - cellHeightPx + LayoutHelper.dp(2)) / 2f,
                width - paddingHPx / 2f, (height + cellHeightPx - LayoutHelper.dp(2)) / 2f
            )
            canvas.drawRoundRect(activeBgRectF, r, r, activeBgPaint)
        }

        val cy = height / 2f

        if (hasUnread && !isActive && !ch.isThread) {
            unreadDotPaint.color = themeColors.onSurface
            canvas.drawCircle(0f, cy, unreadDotRadius, unreadDotPaint)
        }

        val icon = resolveIconDrawable(ch.type, ch.isPrivate)
        val iconLeft = paddingHPx
        val iconTop = ((height - iconSizePx) / 2f).toInt()
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSizePx, iconTop + iconSizePx)
        icon.draw(canvas)

        val textX = paddingHPx + iconSizePx + iconMarginPx
        val badgeWidth = if (hasMentionBadge) badgeSizePx + LayoutHelper.dp(4) else 0
        val availW = width - textX - paddingHPx - badgeWidth

        if (truncatedName.isEmpty()) {
            truncatedName = TextUtils.ellipsize(ch.channelLabel, namePaint, availW.toFloat(), TextUtils.TruncateAt.END)
        }
        val textY = cy - (namePaint.descent() + namePaint.ascent()) / 2
        canvas.drawText(truncatedName.toString(), textX.toFloat(), textY, namePaint)

        if (hasMentionBadge) {
            val badgeText = if (ch.unreadCount > 99) "99+" else ch.unreadCount.toString()
            val textW = unreadBadgeTextPaint.measureText(badgeText)
            val badgeW = (textW + LayoutHelper.dp(8)).coerceAtLeast(badgeSizePx.toFloat())
            val badgeRight = width - paddingHPx.toFloat()
            val badgeLeft = badgeRight - badgeW
            unreadBadgePaint.color = themeColors.badgeRed
            val badgeRectF = RectF(badgeLeft, cy - badgeSizePx / 2f, badgeRight, cy + badgeSizePx / 2f)
            canvas.drawRoundRect(badgeRectF, badgeSizePx / 2f, badgeSizePx / 2f, unreadBadgePaint)
            val textY2 = cy - (unreadBadgeTextPaint.descent() + unreadBadgeTextPaint.ascent()) / 2
            canvas.drawText(badgeText, badgeLeft + badgeW / 2f, textY2, unreadBadgeTextPaint)
        } else if (ch.isMuted) {
            drawMuteIcon(canvas, (width - paddingHPx - iconSizePx / 2f), cy)
        }
    }

    private fun resolveIconDrawable(type: Int, isPrivate: Boolean): Drawable {
        if (currentIconDrawable != null && currentIconType == type && currentIconPrivate == isPrivate) {
            return currentIconDrawable!!
        }
        currentIconType = type
        currentIconPrivate = isPrivate
        currentIconDrawable = resolveChannelIcon(type, isPrivate).getDrawable(context)
        return currentIconDrawable!!
    }

    private fun drawMuteIcon(canvas: Canvas, cx: Float, cy: Float) {
        mutePaint.color = themeColors.onSurfaceVariant
        val r = iconSizePx * 0.35f
        canvas.drawCircle(cx, cy, r, mutePaint)
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, mutePaint)
    }
}
