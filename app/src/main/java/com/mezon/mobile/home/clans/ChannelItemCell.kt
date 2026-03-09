package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL

class ChannelItemCell(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    companion object {
        private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(15f)
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LayoutHelper.dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val activeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val unreadBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val unreadBadgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(10f)
            color = 0xFFFFFFFF.toInt()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val activeBgRectF = RectF()

        private const val CHANNEL_ICON_TEXT = "#"
        private const val VOICE_CHANNEL_TYPE = 10
        private const val FORUM_CHANNEL_TYPE = 5
        private const val ANNOUNCE_CHANNEL_TYPE = 9

        private val iconTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
    }

    private var channel: ClanChannelEntity? = null
    private var isActive = false
    private var truncatedName: CharSequence = ""

    private val cellHeightPx = LayoutHelper.dp(40)
    private val paddingHPx = LayoutHelper.dp(16)
    private val iconSizePx = LayoutHelper.dp(20)
    private val iconMarginPx = LayoutHelper.dp(8)
    private val badgeSizePx = LayoutHelper.dp(20)

    init {
        setWillNotDraw(false)
    }

    fun bind(channel: ClanChannelEntity, active: Boolean) {
        val changed = this.channel?.channelId != channel.channelId
            || this.channel?.channelLabel != channel.channelLabel
            || this.channel?.unreadCount != channel.unreadCount
            || this.isActive != active
        this.channel = channel
        this.isActive = active
        if (changed) {
            truncatedName = ""
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), cellHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        val ch = channel ?: return

        val textColor = when {
            isActive -> themeColors.onSurface
            ch.unreadCount > 0 -> themeColors.onSurface
            else -> themeColors.onSurfaceVariant
        }
        namePaint.color = textColor
        iconPaint.color = textColor
        iconTextPaint.color = textColor

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
        val iconX = paddingHPx.toFloat() + iconSizePx / 2f
        drawChannelIcon(canvas, ch.type, iconX, cy)

        val textX = paddingHPx + iconSizePx + iconMarginPx
        val badgeWidth = if (ch.unreadCount > 0) badgeSizePx + LayoutHelper.dp(4) else 0
        val availW = width - textX - paddingHPx - badgeWidth

        if (truncatedName.isEmpty()) {
            truncatedName = TextUtils.ellipsize(ch.channelLabel, namePaint, availW.toFloat(), TextUtils.TruncateAt.END)
        }
        val textY = cy - (namePaint.descent() + namePaint.ascent()) / 2
        canvas.drawText(truncatedName.toString(), textX.toFloat(), textY, namePaint)

        if (ch.unreadCount > 0) {
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

    private fun drawChannelIcon(canvas: Canvas, type: Int, cx: Float, cy: Float) {
        val half = iconSizePx / 2f
        when (type) {
            VOICE_CHANNEL_TYPE -> {
                canvas.drawLine(cx, cy - half * 0.6f, cx, cy + half * 0.6f, iconPaint)
                canvas.drawLine(cx - half * 0.5f, cy - half * 0.3f, cx - half * 0.5f, cy + half * 0.3f, iconPaint)
                canvas.drawLine(cx + half * 0.5f, cy - half * 0.3f, cx + half * 0.5f, cy + half * 0.3f, iconPaint)
            }
            FORUM_CHANNEL_TYPE -> {
                val r = half * 0.7f
                canvas.drawCircle(cx, cy, r, iconPaint)
                canvas.drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, iconPaint)
                canvas.drawLine(cx - r * 0.5f, cy - r * 0.4f, cx + r * 0.5f, cy - r * 0.4f, iconPaint)
            }
            ANNOUNCE_CHANNEL_TYPE -> {
                val r = half * 0.7f
                canvas.drawCircle(cx, cy, r, iconPaint)
                canvas.drawLine(cx, cy - r * 0.3f, cx, cy + r * 0.3f, iconPaint)
                canvas.drawCircle(cx, cy - r * 0.5f, r * 0.1f, iconPaint)
            }
            else -> {
                val textY = cy - (iconTextPaint.descent() + iconTextPaint.ascent()) / 2
                canvas.drawText(CHANNEL_ICON_TEXT, cx, textY, iconTextPaint)
            }
        }
    }

    private fun drawMuteIcon(canvas: Canvas, cx: Float, cy: Float) {
        iconPaint.color = themeColors.onSurfaceVariant
        val r = iconSizePx * 0.35f
        canvas.drawCircle(cx, cy, r, iconPaint)
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, iconPaint)
        iconPaint.color = channel?.let { themeColors.onSurfaceVariant } ?: themeColors.onSurfaceVariant
    }
}
