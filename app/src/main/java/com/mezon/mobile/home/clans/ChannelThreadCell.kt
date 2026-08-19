package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class ChannelThreadCell(
    context: Context,
    private val themeColors: ThemeColors
) : BaseCell(context) {

    companion object {
        private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(15f)
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
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val activeBgRectF = RectF()
        private val arcRectF = RectF()
        private val badgeRectF = RectF()

        private val ACTIVE_RADIUS = LayoutHelper.dp(6).toFloat()
        private val ACTIVE_PAD_H = LayoutHelper.dp(6).toFloat()
        private val ACTIVE_PAD_V = LayoutHelper.dp(2).toFloat()
        private val ACTIVE_PAD_RIGHT = LayoutHelper.dp(8).toFloat()
        private val BADGE_GAP = LayoutHelper.dp(4)
        private val BADGE_TEXT_PAD = LayoutHelper.dp(8).toFloat()
        private val EVENT_ICON_UPCOMING_STATE = intArrayOf(android.R.attr.state_selected)
        private val EVENT_ICON_ONGOING_STATE = intArrayOf(android.R.attr.state_activated)
        private const val MUTED_CONTENT_ALPHA = 0.6f
    }

    var thread: ClanChannelEntity? = null
        private set
    private var isFirst = false
    private var isLast = false
    private var isActive = false
    private var eventStatus = ClanEventStatus.CREATED
    private var truncatedName: String = ""
    private var truncatedNameWidth = -1
    private val eventIconDrawable by lazy { MezonIcon.calendarIcon.getDrawable(context) }

    private val cellHeightPx = LayoutHelper.dp(37)
    private val connectorLineX = LayoutHelper.dp(26).toFloat()
    private val branchEndX = LayoutHelper.dp(40).toFloat()
    private val textStartX = LayoutHelper.dp(44)
    private val connectorStrokePx = LayoutHelper.dp(1.5f).toFloat()
    private val cornerRadius = LayoutHelper.dp(6).toFloat()
    private val paddingRightPx = LayoutHelper.dp(16)
    private val badgeSizePx = LayoutHelper.dp(18)
    private val eventIconSizePx = LayoutHelper.dp(16)
    private val eventIconTrailingMarginPx = LayoutHelper.dp(8)

    fun bind(
        thread: ClanChannelEntity,
        isFirst: Boolean,
        isLast: Boolean,
        isActive: Boolean,
        eventStatus: Int? = null,
    ) {
        this.thread = thread
        this.isFirst = isFirst
        this.isLast = isLast
        this.isActive = isActive
        this.eventStatus = normalizeEventStatus(eventStatus)
        truncatedName = ""
        invalidate()
    }

    fun setEventStatus(eventStatus: Int?) {
        val normalized = normalizeEventStatus(eventStatus)
        if (this.eventStatus == normalized) return
        this.eventStatus = normalized
        truncatedName = ""
        invalidate()
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
            if (thread?.unreadCount != th.unreadCount || thread?.hasUnread != th.hasUnread) {
                truncatedName = ""
                needInvalidate = true
            }
        }

        if (thread?.isMuted != th.isMuted) {
            needInvalidate = true
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

        connectorPaint.color = 0xFF535353.toInt()
        connectorPaint.strokeWidth = connectorStrokePx

        val verticalBottom = if (isLast) cy - cornerRadius else height.toFloat()
        canvas.drawLine(connectorLineX, 0f, connectorLineX, verticalBottom, connectorPaint)

        arcRectF.set(
            connectorLineX, cy - cornerRadius * 2,
            connectorLineX + cornerRadius * 2, cy
        )
        canvas.drawArc(arcRectF, 180f, -90f, false, connectorPaint)

        canvas.drawLine(connectorLineX + cornerRadius, cy, branchEndX, cy, connectorPaint)

        if (isActive) {
            activeBgPaint.color = themeColors.primaryContainer
            activeBgRectF.set(
                textStartX - ACTIVE_PAD_H, ACTIVE_PAD_V,
                width - ACTIVE_PAD_RIGHT, height - ACTIVE_PAD_V
            )
            canvas.drawRoundRect(activeBgRectF, ACTIVE_RADIUS, ACTIVE_RADIUS, activeBgPaint)
        }

        val isMutedVisual = th.isMuted && !isActive
        val showUnreadHighlight = th.hasUnread && !isMutedVisual
        val showMentionBadge = th.unreadCount > 0
        val textColor = when {
            isActive -> themeColors.onSurface
            isMutedVisual -> withAlpha(themeColors.onSurfaceVariant, MUTED_CONTENT_ALPHA)
            showUnreadHighlight -> themeColors.onSurface
            else -> themeColors.onSurfaceVariant
        }
        namePaint.color = textColor
        namePaint.typeface = if (showUnreadHighlight) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        val hasChannelEvent = eventStatus == ClanEventStatus.UPCOMING || eventStatus == ClanEventStatus.ONGOING
        val textX = if (hasChannelEvent) {
            eventIconDrawable.state = if (eventStatus == ClanEventStatus.ONGOING) {
                EVENT_ICON_ONGOING_STATE
            } else {
                EVENT_ICON_UPCOMING_STATE
            }
            eventIconDrawable.alpha = if (isMutedVisual) (255 * MUTED_CONTENT_ALPHA).toInt() else 255
            eventIconDrawable.setBounds(
                textStartX,
                ((height - eventIconSizePx) / 2f).toInt(),
                textStartX + eventIconSizePx,
                ((height + eventIconSizePx) / 2f).toInt(),
            )
            eventIconDrawable.draw(canvas)
            textStartX + eventIconSizePx + eventIconTrailingMarginPx
        } else {
            textStartX
        }

        val badgeWidth = if (showMentionBadge) badgeSizePx + BADGE_GAP else 0
        val availW = width - textX - paddingRightPx - badgeWidth

        if (truncatedName.isEmpty() || truncatedNameWidth != availW) {
            truncatedNameWidth = availW
            truncatedName = TextUtils.ellipsize(th.channelLabel, namePaint, availW.toFloat(), TextUtils.TruncateAt.END).toString()
        }
        val textY = cy - (namePaint.descent() + namePaint.ascent()) / 2
        canvas.drawText(truncatedName, textX.toFloat(), textY, namePaint)

        if (showMentionBadge) {
            val badgeText = if (th.unreadCount > 99) "99+" else th.unreadCount.toString()
            val textW = unreadBadgeTextPaint.measureText(badgeText)
            val badgeW = (textW + BADGE_TEXT_PAD).coerceAtLeast(badgeSizePx.toFloat())
            val badgeRight = width - paddingRightPx.toFloat()
            val badgeLeft = badgeRight - badgeW
            unreadBadgePaint.color = themeColors.badgeRed
            badgeRectF.set(badgeLeft, cy - badgeSizePx / 2f, badgeRight, cy + badgeSizePx / 2f)
            canvas.drawRoundRect(badgeRectF, badgeSizePx / 2f, badgeSizePx / 2f, unreadBadgePaint)
            val textY2 = cy - (unreadBadgeTextPaint.descent() + unreadBadgeTextPaint.ascent()) / 2
            canvas.drawText(badgeText, badgeLeft + badgeW / 2f, textY2, unreadBadgeTextPaint)
        }
    }

    private fun withAlpha(color: Int, alphaFraction: Float): Int {
        val alpha = (255f * alphaFraction).toInt().coerceIn(0, 255)
        return color and 0x00FFFFFF or (alpha shl 24)
    }

    private fun normalizeEventStatus(status: Int?): Int = when (status) {
        ClanEventStatus.UPCOMING, ClanEventStatus.ONGOING -> status
        else -> ClanEventStatus.CREATED
    }
}
