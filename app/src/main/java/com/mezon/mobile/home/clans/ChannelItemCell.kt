package com.mezon.mobile.home.clans

import com.mezon.mobile.R
import com.mezon.mobile.core.theme.DesktopThemeToken
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
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

        private const val MUTED_CONTENT_ALPHA = 0.6f

        private val ACTIVE_RADIUS = LayoutHelper.dp(6).toFloat()
        private val ACTIVE_INSET = LayoutHelper.dp(2).toFloat()
        private val BADGE_GAP = LayoutHelper.dp(4)
        private val BADGE_TEXT_PAD = LayoutHelper.dp(8).toFloat()

        fun resolveChannelIcon(
            type: Int,
            isPrivate: Boolean,
            isAgeRestricted: Boolean = false,
            compact: Boolean = false,
        ): Int {
            fun size(regular: Int, small: Int) = if (compact) small else regular
            return when (type) {
                CHANNEL_TYPE_VOICE -> if (isPrivate) size(R.drawable.mz_speaker_lock_20, R.drawable.mz_speaker_lock_16) else size(R.drawable.mz_speaker_20, R.drawable.mz_speaker_16)
                CHANNEL_TYPE_STREAMING -> size(R.drawable.mz_live_20, R.drawable.mz_live_16)
                CHANNEL_TYPE_APP -> size(R.drawable.mz_channel_app_20, R.drawable.mz_channel_app_16)
                CHANNEL_TYPE_FORUM -> size(R.drawable.mz_forum_20, R.drawable.mz_forum_16)
                CHANNEL_TYPE_ANNOUNCEMENT -> size(R.drawable.mz_announcement_20, R.drawable.mz_announcement_16)
                CHANNEL_TYPE_THREAD -> if (isPrivate) size(R.drawable.mz_thread_private_20, R.drawable.mz_thread_private_16) else size(R.drawable.mz_thread_20, R.drawable.mz_thread_16)
                else -> when {
                    isAgeRestricted -> size(R.drawable.mz_channel_warning_20, R.drawable.mz_channel_warning_16)
                    isPrivate -> size(R.drawable.mz_channel_private_20, R.drawable.mz_channel_private_16)
                    else -> size(R.drawable.mz_channel_20, R.drawable.mz_channel_16)
                }
            }
        }
    }

    var channel: ClanChannelEntity? = null
        private set
    private var isActive = false
    private var hasBuzz = false
    private var voiceActive = false
    private var truncatedName: String = ""
    private var truncatedNameWidth = -1f
    private var currentIconDrawable: Drawable? = null
    private var currentIconType: Int = -1
    private var currentIconPrivate: Boolean = false
    private var currentIconAgeRestricted: Boolean = false
    private var cachedIconColorFilter: PorterDuffColorFilter? = null
    private var cachedIconColor: Int = 0
    private val voiceActiveColorFilter = PorterDuffColorFilter(0xFF16A34A.toInt(), PorterDuff.Mode.SRC_IN)
    private val badgeRectF = RectF()
    private var streamViewerCount: Int? = null
    private var streamViewerCountText = ""
    private var viewerEyeDrawable: Drawable? = null
    private val viewerCountPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(11f)
        textAlign = Paint.Align.RIGHT
    }


    private val cellHeightPx = LayoutHelper.dp(34)
    private val paddingHPx = LayoutHelper.dp(16)
    private val iconSizePx = LayoutHelper.dp(16)
    private val iconMarginPx = LayoutHelper.dp(8)
    private val badgeSizePx = LayoutHelper.dp(20)
    private val unreadDotRadius = LayoutHelper.dp(3f).toFloat()



    fun bind(channel: ClanChannelEntity, active: Boolean, voiceActive: Boolean = false, hasBuzz: Boolean = false, streamViewerCount: Int? = null) {
        this.channel = channel
        this.hasBuzz = hasBuzz
        this.isActive = active
        this.voiceActive = voiceActive
        this.streamViewerCount = if (channel.type == CHANNEL_TYPE_STREAMING && voiceActive) streamViewerCount?.coerceAtLeast(0) else null
        streamViewerCountText = this.streamViewerCount?.let { java.text.NumberFormat.getIntegerInstance().format(it) }.orEmpty()
        truncatedName = ""
        invalidate()
    }

    fun applyBuzz(value: Boolean) {
        if (hasBuzz == value) return
        hasBuzz = value
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

        if (channel?.isMuted != ch.isMuted) {
            needInvalidate = true
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

    override fun invalidate() {
        if (channel == null) return
        super.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), cellHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        val ch = channel ?: return
        val isMutedVisual = ch.isMuted && !isActive
        val showUnreadHighlight = ch.hasUnread && !isMutedVisual
        val showMentionBadge = ch.unreadCount > 0 && !ch.isVoiceLike

        val textColor = when {
            isActive -> themeColors.desktop[DesktopThemeToken.TEXT_SECONDARY]
            isMutedVisual -> withAlpha(themeColors.textThemePrimary, MUTED_CONTENT_ALPHA)
            showUnreadHighlight -> themeColors.desktop[DesktopThemeToken.TEXT_SECONDARY]
            else -> themeColors.textThemePrimary
        }
        namePaint.color = textColor
        namePaint.typeface = if (showUnreadHighlight) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT

        if (isActive) {
            activeBgPaint.color = themeColors.desktop[DesktopThemeToken.BG_ACTIVE_MEMBER_CHANNEL]
            activeBgRectF.set(
                paddingHPx / 2f, (height - cellHeightPx) / 2f + ACTIVE_INSET,
                width - paddingHPx / 2f, (height + cellHeightPx) / 2f - ACTIVE_INSET
            )
            canvas.drawRoundRect(activeBgRectF, ACTIVE_RADIUS, ACTIVE_RADIUS, activeBgPaint)
        }

        val cy = height / 2f

        if (showUnreadHighlight && !ch.isThread) {
            unreadDotPaint.color = themeColors.desktop[DesktopThemeToken.TEXT_SECONDARY]
            canvas.drawCircle(0f, cy, unreadDotRadius, unreadDotPaint)
        }

        val icon = resolveIconDrawable(ch.type, ch.isPrivate, ch.isAgeRestricted)
        val isVoiceType = ch.type == CHANNEL_TYPE_VOICE || ch.type == CHANNEL_TYPE_STREAMING || ch.type == CHANNEL_TYPE_APP
        icon.colorFilter = if (isVoiceType && voiceActive) {
            voiceActiveColorFilter
        } else {
            val iconColor = if (ch.type == CHANNEL_TYPE_THREAD) {
                themeColors.iconTint
            } else {
                val token = if (isActive || showUnreadHighlight) {
                    DesktopThemeToken.BG_ICON_THEME_ACTIVE
                } else {
                    DesktopThemeToken.BG_ICON_THEME
                }
                themeColors.desktop[token].let {
                    if (isMutedVisual) withAlpha(it, MUTED_CONTENT_ALPHA) else it
                }
            }
            if (cachedIconColorFilter == null || cachedIconColor != iconColor) {
                cachedIconColor = iconColor
                cachedIconColorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
            }
            cachedIconColorFilter
        }
        val iconLeft = paddingHPx
        val iconTop = ((height - iconSizePx) / 2f).toInt()
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSizePx, iconTop + iconSizePx)
        icon.draw(canvas)

        val textX = paddingHPx + iconSizePx + iconMarginPx
        val badgeText = if (ch.unreadCount > 99) "99+" else ch.unreadCount.toString()
        val mentionWidth = if (showMentionBadge) {
            (unreadBadgeTextPaint.measureText(badgeText) + BADGE_TEXT_PAD).coerceAtLeast(badgeSizePx.toFloat())
        } else 0f
        val buzzWidth = if (hasBuzz) themeColors.buzzBadgeTextPaint.measureText("Buzz!!") + LayoutHelper.dp(8) else 0f
        val viewerWidth = if (streamViewerCount != null)
            viewerCountPaint.measureText(streamViewerCountText) + LayoutHelper.dp(14 + 6) else 0f
        val viewerInset = if (streamViewerCount != null) viewerWidth + BADGE_GAP else 0f
        val badgeWidth = (if (showMentionBadge) mentionWidth + BADGE_GAP else 0f) +
            (if (hasBuzz) buzzWidth + BADGE_GAP else 0f) + viewerInset
        val availW = (width - textX - paddingHPx - badgeWidth).coerceAtLeast(0f)

        if (truncatedName.isEmpty() || truncatedNameWidth != availW) {
            truncatedNameWidth = availW
            truncatedName = TextUtils.ellipsize(ch.channelLabel, namePaint, availW.toFloat(), TextUtils.TruncateAt.END).toString()
        }
        val textY = cy - (namePaint.descent() + namePaint.ascent()) / 2
        canvas.drawText(truncatedName, textX.toFloat(), textY, namePaint)

        if (hasBuzz) {
            val right = width - paddingHPx.toFloat() - viewerInset - (if (showMentionBadge) mentionWidth + BADGE_GAP else 0f)
            val left = right - buzzWidth
            val halfHeight = LayoutHelper.dp(10).toFloat()
            badgeRectF.set(left, cy - halfHeight, right, cy + halfHeight)
            canvas.drawRoundRect(badgeRectF, LayoutHelper.dpf(4f), LayoutHelper.dpf(4f), themeColors.buzzBadgePaint)
            val buzzY = cy - (themeColors.buzzBadgeTextPaint.descent() + themeColors.buzzBadgeTextPaint.ascent()) / 2
            canvas.drawText("Buzz!!", left + LayoutHelper.dp(4), buzzY, themeColors.buzzBadgeTextPaint)
        }

        if (streamViewerCount != null) {
            viewerCountPaint.color = textColor
            val textRight = (width - paddingHPx).toFloat()
            val iconRight = textRight - viewerCountPaint.measureText(streamViewerCountText) - LayoutHelper.dp(6)
            val iconSize = LayoutHelper.dp(14)
            val eye = viewerEyeDrawable
                ?: requireNotNull(ContextCompat.getDrawable(context, R.drawable.mz_eye_16)).mutate()
                    .also { viewerEyeDrawable = it }
            eye.setTint(textColor)
            eye.setBounds((iconRight - iconSize).toInt(), (cy - iconSize / 2f).toInt(), iconRight.toInt(), (cy + iconSize / 2f).toInt())
            eye.draw(canvas)
            canvas.drawText(streamViewerCountText, textRight, cy - (viewerCountPaint.descent() + viewerCountPaint.ascent()) / 2, viewerCountPaint)
        }

        if (showMentionBadge) {
            val textW = unreadBadgeTextPaint.measureText(badgeText)
            val badgeW = (textW + BADGE_TEXT_PAD).coerceAtLeast(badgeSizePx.toFloat())
            val badgeRight = width - paddingHPx.toFloat()
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

    private fun resolveIconDrawable(type: Int, isPrivate: Boolean, isAgeRestricted: Boolean): Drawable {
        if (currentIconDrawable != null && currentIconType == type && currentIconPrivate == isPrivate && currentIconAgeRestricted == isAgeRestricted) {
            return currentIconDrawable!!
        }
        currentIconType = type
        currentIconPrivate = isPrivate
        currentIconAgeRestricted = isAgeRestricted
        val resId = resolveChannelIcon(type, isPrivate, isAgeRestricted, compact = true)
        currentIconDrawable = MezonIcon.loadDrawable(context, resId)
        return currentIconDrawable!!
    }
}
