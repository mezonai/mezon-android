package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors

class ChannelThreadCell(
    context: Context,
    private val themeColors: ThemeColors
) : BaseCell(context) {

    companion object {
        private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(14f)
        }
        private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
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
        private val connectorPath = Path()
    }

    var thread: ClanChannelEntity? = null
        private set
    private var isFirst = false
    private var isActive = false
    private var truncatedName: CharSequence = ""

    private val cellHeightPx = LayoutHelper.dp(36)
    private val indentPx = LayoutHelper.dp(28)
    private val connectorStrokePx = LayoutHelper.dp(2).toFloat()
    private val cornerRadiusPx = LayoutHelper.dp(6).toFloat()
    private val iconSizePx = LayoutHelper.dp(14)
    private val textMarginPx = LayoutHelper.dp(6)
    private val paddingRightPx = LayoutHelper.dp(16)
    private val badgeSizePx = LayoutHelper.dp(18)



    fun bind(thread: ClanChannelEntity, isFirst: Boolean, isActive: Boolean) {
        this.thread = thread
        this.isFirst = isFirst
        this.isActive = isActive
        update(0)
    }

    fun update(mask: Int, newThread: ClanChannelEntity? = null): Boolean {
        val th = newThread ?: thread ?: return false

        if (mask == 0) {
            if (newThread != null) thread = newThread
            truncatedName = ""
            invalidate()
            return true
        }

        var needInvalidate = false

        if ((mask and NotificationCenter.UPDATE_MASK_BADGE) != 0) {
            if (thread?.unreadCount != th.unreadCount) {
                truncatedName = ""
                needInvalidate = true
            }
        }

        if ((mask and NotificationCenter.UPDATE_MASK_CHAT_NAME) != 0) {
            if (thread?.channelLabel != th.channelLabel) {
                truncatedName = ""
                needInvalidate = true
            }
        }

        if (newThread != null) thread = newThread

        if (needInvalidate) {
            invalidate()
        }
        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), cellHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        val th = thread ?: return
        val cy = height / 2f

        val connectorColor = 0xFF535353.toInt()
        connectorPaint.color = connectorColor
        connectorPaint.strokeWidth = connectorStrokePx

        // L-shaped connector line: vertical line down from top (or mid) + horizontal to icon
        val lineX = (indentPx / 2).toFloat()
        val iconRight = indentPx.toFloat()
        connectorPath.reset()
        if (isFirst) {
            // Short corner: starts from vertical center, goes right
            connectorPath.moveTo(lineX, 0f)
            connectorPath.lineTo(lineX, cy)
            connectorPath.lineTo(iconRight, cy)
        } else {
            // Long corner: full height vertical + horizontal
            connectorPath.moveTo(lineX, 0f)
            connectorPath.lineTo(lineX, cy)
            connectorPath.lineTo(iconRight, cy)
        }
        canvas.drawPath(connectorPath, connectorPaint)

        if (isActive) {
            activeBgPaint.color = themeColors.primaryContainer
            val r = LayoutHelper.dp(6).toFloat()
            activeBgRectF.set(
                indentPx - LayoutHelper.dp(4).toFloat(), LayoutHelper.dp(2).toFloat(),
                width - LayoutHelper.dp(8).toFloat(), height - LayoutHelper.dp(2).toFloat()
            )
            canvas.drawRoundRect(activeBgRectF, r, r, activeBgPaint)
        }

        val hasUnread = th.unreadCount > 0
        val textColor = when {
            isActive || hasUnread -> themeColors.onSurface
            else -> themeColors.onSurfaceVariant
        }
        namePaint.color = textColor

        val textX = indentPx + textMarginPx
        val badgeWidth = if (hasUnread) badgeSizePx + LayoutHelper.dp(4) else 0
        val availW = width - textX - paddingRightPx - badgeWidth

        if (truncatedName.isEmpty()) {
            truncatedName = TextUtils.ellipsize(th.channelLabel, namePaint, availW.toFloat(), TextUtils.TruncateAt.END)
        }
        val textY = cy - (namePaint.descent() + namePaint.ascent()) / 2
        canvas.drawText(truncatedName.toString(), textX.toFloat(), textY, namePaint)

        if (hasUnread) {
            val badgeText = if (th.unreadCount > 99) "99+" else th.unreadCount.toString()
            val textW = unreadBadgeTextPaint.measureText(badgeText)
            val badgeW = (textW + LayoutHelper.dp(8)).coerceAtLeast(badgeSizePx.toFloat())
            val badgeRight = width - paddingRightPx.toFloat()
            val badgeLeft = badgeRight - badgeW
            unreadBadgePaint.color = themeColors.badgeRed
            val rf = RectF(badgeLeft, cy - badgeSizePx / 2f, badgeRight, cy + badgeSizePx / 2f)
            canvas.drawRoundRect(rf, badgeSizePx / 2f, badgeSizePx / 2f, unreadBadgePaint)
            val textY2 = cy - (unreadBadgeTextPaint.descent() + unreadBadgeTextPaint.ascent()) / 2
            canvas.drawText(badgeText, badgeLeft + badgeW / 2f, textY2, unreadBadgeTextPaint)
        }
    }
}
