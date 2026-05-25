package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.avatarImgproxyUrl
import kotlin.math.ceil
import kotlin.math.min

internal class TopicMessageButtonLayout(
    private val context: Context,
    private val theme: ThemeColors
) {
    private val topicButtonRect = RectF()
    private val topicButtonPath = Path()
    private val topicBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topicBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val topicLinkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val topicMutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val topicBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topicBadgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val topicBadgeRect = RectF()
    private val topicAvatarDrawable = AvatarDrawable()
    private var topicCreatorLayout: StaticLayout? = null
    private var topicViewLayout: StaticLayout? = null
    private var topicRepliesLayout: StaticLayout? = null
    private var topicRepliesSecondRowLayout: StaticLayout? = null
    private var topicArrowDrawable = MezonIcon.chevronSmallRightIcon.getDrawable(context).mutate()
    private var topicAvatarUrl: String? = null
    private var topicAvatarDisposable: MezonImageLoader.Cancellable? = null
    private var creatorLabelText = ""
    private var viewLabelText = ""
    private var repliesLabelText = ""
    private var badgeCount = 0
    private var badgeLabelText = ""
    private var useTwoTextRows = false
    var visible = false
        private set
    var topicId = 0L
        private set
    var rootMessageId = 0L
        private set
    var blockHeight = 0
        private set

    private var invalidateCallback: (() -> Unit)? = null

    init {
        applyThemeColors()
    }

    private fun applyThemeColors() {
        topicBadgePaint.color = theme.badgeRed
        topicBadgeTextPaint.color = 0xFFFFFFFF.toInt()
        topicBadgeTextPaint.textSize = TOPIC_BADGE_TEXT_SIZE
        topicLinkPaint.textSize = TOPIC_TEXT_SIZE
        topicLinkPaint.color = theme.textLink
        topicMutedPaint.textSize = TOPIC_TEXT_SIZE
        topicMutedPaint.color = theme.onSurfaceVariant
        topicBgPaint.color = theme.dividerColor
        topicBorderPaint.color = theme.outlineVariant
        topicBorderPaint.strokeWidth = TOPIC_BORDER.toFloat()
    }

    fun setInvalidateCallback(callback: () -> Unit) {
        invalidateCallback = callback
    }

    fun cancelAvatarLoad() {
        topicAvatarDisposable?.cancel()
        topicAvatarDisposable = null
    }

    fun bind(
        msg: MessageEntity,
        creatorName: String,
        creatorAvatarUrl: String,
        creatorLabel: String,
        viewLabel: String,
        repliesLabel: String,
        mentionBadgeCount: Int = 0
    ) {
        val effectiveTopicId = msg.effectiveTopicId
        visible = msg.isTopicRootMessage && effectiveTopicId != 0L
        if (!visible) {
            blockHeight = 0
            topicButtonRect.setEmpty()
            topicCreatorLayout = null
            topicViewLayout = null
            topicRepliesLayout = null
            topicRepliesSecondRowLayout = null
            useTwoTextRows = false
            return
        }
        topicId = effectiveTopicId
        rootMessageId = msg.id
        creatorLabelText = creatorLabel
        viewLabelText = viewLabel
        repliesLabelText = repliesLabel
        badgeCount = mentionBadgeCount.coerceAtLeast(0)
        badgeLabelText = when {
            badgeCount <= 0 -> ""
            badgeCount > 99 -> "99+"
            else -> badgeCount.toString()
        }
        topicAvatarDrawable.setInfo(msg.topicCreatorId, creatorName)
        if (topicAvatarUrl != creatorAvatarUrl) {
            topicAvatarUrl = creatorAvatarUrl
            topicAvatarDisposable?.cancel()
            if (creatorAvatarUrl.isNotBlank()) {
                val proxy = avatarImgproxyUrl(creatorAvatarUrl, TOPIC_AVATAR_SIZE)
                val loader = MezonImageLoader.getInstance(context)
                topicAvatarDisposable = loader.load(
                    proxy,
                    TOPIC_AVATAR_SIZE,
                    TOPIC_AVATAR_SIZE,
                    onSuccess = { bmp ->
                        topicAvatarDrawable.setPhoto(bmp)
                        invalidateCallback?.invoke()
                    }
                )
            } else {
                topicAvatarDrawable.setPhoto(null)
            }
        }
        blockHeight = TOPIC_PADDING_V * 2 + TOPIC_AVATAR_SIZE
    }

    fun layout(contentLeft: Float, top: Float, width: Int) {
        if (!visible) {
            topicButtonRect.setEmpty()
            topicCreatorLayout = null
            topicViewLayout = null
            topicRepliesLayout = null
            topicRepliesSecondRowLayout = null
            useTwoTextRows = false
            blockHeight = 0
            return
        }
        val innerW = (width - contentLeft - PAD_END).toInt().coerceAtLeast(1)
        val left = contentLeft
        val topY = top + TOPIC_MARGIN_TOP
        val textStart = TOPIC_PADDING_H + TOPIC_AVATAR_SIZE + TOPIC_AVATAR_GAP
        val badgeReserve = if (badgeCount > 0) TOPIC_BADGE_SIZE + TOPIC_TEXT_GAP else 0
        val reservedEnd = TOPIC_PADDING_H + TOPIC_ARROW_SIZE + TOPIC_TEXT_GAP + badgeReserve
        val rowMaxW = (innerW - textStart - reservedEnd).coerceAtLeast(1)
        buildTextRow(rowMaxW)
        val row1H = listOf(topicCreatorLayout, topicViewLayout, topicRepliesLayout)
            .maxOfOrNull { it?.height ?: 0 } ?: 0
        val row2H = topicRepliesSecondRowLayout?.height ?: 0
        val textBlockH = if (useTwoTextRows) row1H + TOPIC_TEXT_GAP + row2H else row1H
        blockHeight = TOPIC_PADDING_V * 2 + maxOf(TOPIC_AVATAR_SIZE, textBlockH)
        topicButtonRect.set(left, topY, left + innerW, topY + blockHeight)
        topicButtonPath.reset()
        topicButtonPath.addRoundRect(topicButtonRect, TOPIC_RADIUS, TOPIC_RADIUS, Path.Direction.CW)
    }

    fun draw(canvas: Canvas) {
        if (!visible || topicButtonRect.isEmpty) return
        canvas.drawPath(topicButtonPath, topicBgPaint)
        canvas.drawPath(topicButtonPath, topicBorderPaint)
        val avatarLeft = topicButtonRect.left + TOPIC_PADDING_H
        val avatarTop = topicButtonRect.top + TOPIC_PADDING_V
        topicAvatarDrawable.setBounds(
            avatarLeft.toInt(),
            avatarTop.toInt(),
            avatarLeft.toInt() + TOPIC_AVATAR_SIZE,
            avatarTop.toInt() + TOPIC_AVATAR_SIZE
        )
        topicAvatarDrawable.draw(canvas)
        val row1H = listOf(topicCreatorLayout, topicViewLayout, topicRepliesLayout)
            .maxOfOrNull { it?.height ?: 0 } ?: 0
        val row2H = topicRepliesSecondRowLayout?.height ?: 0
        val textBlockH = if (useTwoTextRows) row1H + TOPIC_TEXT_GAP + row2H else row1H
        var textX = avatarLeft + TOPIC_AVATAR_SIZE + TOPIC_AVATAR_GAP
        var textY = topicButtonRect.top + TOPIC_PADDING_V +
            (maxOf(TOPIC_AVATAR_SIZE, textBlockH) - textBlockH) / 2f
        canvas.save()
        canvas.clipPath(topicButtonPath)
        topicCreatorLayout?.let {
            canvas.save()
            canvas.translate(textX, textY)
            it.draw(canvas)
            canvas.restore()
            textX += it.width + TOPIC_TEXT_GAP
        }
        topicViewLayout?.let {
            canvas.save()
            canvas.translate(textX, textY)
            it.draw(canvas)
            canvas.restore()
            textX += it.width + TOPIC_TEXT_GAP
        }
        topicRepliesLayout?.let {
            canvas.save()
            canvas.translate(textX, textY)
            it.draw(canvas)
            canvas.restore()
        }
        topicRepliesSecondRowLayout?.let {
            val secondRowX = avatarLeft + TOPIC_AVATAR_SIZE + TOPIC_AVATAR_GAP
            val secondRowY = textY + row1H + TOPIC_TEXT_GAP
            canvas.save()
            canvas.translate(secondRowX, secondRowY)
            it.draw(canvas)
            canvas.restore()
        }
        if (badgeCount > 0 && badgeLabelText.isNotEmpty()) {
            val textW = topicBadgeTextPaint.measureText(badgeLabelText)
            val badgeW = maxOf(TOPIC_BADGE_SIZE.toFloat(), textW + TOPIC_BADGE_TEXT_PAD)
            val badgeRight = topicButtonRect.right - TOPIC_PADDING_H - TOPIC_ARROW_SIZE - TOPIC_TEXT_GAP
            val badgeLeft = badgeRight - badgeW
            val badgeCy = topicButtonRect.top + topicButtonRect.height() / 2f
            topicBadgeRect.set(badgeLeft, badgeCy - TOPIC_BADGE_SIZE / 2f, badgeRight, badgeCy + TOPIC_BADGE_SIZE / 2f)
            canvas.drawRoundRect(
                topicBadgeRect,
                TOPIC_BADGE_SIZE / 2f,
                TOPIC_BADGE_SIZE / 2f,
                topicBadgePaint
            )
            val badgeTextY = badgeCy - (topicBadgeTextPaint.descent() + topicBadgeTextPaint.ascent()) / 2f
            canvas.drawText(badgeLabelText, topicBadgeRect.centerX(), badgeTextY, topicBadgeTextPaint)
        }
        canvas.restore()
        val arrowLeft = topicButtonRect.right - TOPIC_PADDING_H - TOPIC_ARROW_SIZE
        val arrowTop = topicButtonRect.top + (topicButtonRect.height() - TOPIC_ARROW_SIZE) / 2f
        topicArrowDrawable.setBounds(
            arrowLeft.toInt(),
            arrowTop.toInt(),
            (arrowLeft + TOPIC_ARROW_SIZE).toInt(),
            (arrowTop + TOPIC_ARROW_SIZE).toInt()
        )
        topicArrowDrawable.colorFilter = android.graphics.PorterDuffColorFilter(
            theme.onSurfaceVariant,
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        topicArrowDrawable.draw(canvas)
    }

    fun hitTest(x: Float, y: Float): Boolean =
        visible && topicButtonRect.contains(x, y)

    private fun buildTextRow(rowMaxW: Int) {
        useTwoTextRows = false
        topicRepliesSecondRowLayout = null
        topicCreatorLayout = null
        topicViewLayout = null
        topicRepliesLayout = null

        if (repliesLabelText.isBlank()) {
            var remaining = rowMaxW
            topicCreatorLayout = makeLayout(creatorLabelText, topicLinkPaint, remaining)?.also {
                remaining -= it.width + TOPIC_TEXT_GAP
            }
            topicViewLayout = makeLayout(viewLabelText, topicMutedPaint, remaining)
            return
        }

        val repliesNaturalW = measureTextWidth(repliesLabelText, topicLinkPaint)
        val repliesReserved = min(repliesNaturalW, rowMaxW / 2).coerceAtLeast(
            min(MIN_REPLIES_WIDTH, rowMaxW)
        )
        var remaining = (rowMaxW - repliesReserved - TOPIC_TEXT_GAP).coerceAtLeast(0)
        topicCreatorLayout = makeLayout(creatorLabelText, topicLinkPaint, remaining)?.also {
            remaining = (remaining - it.width - TOPIC_TEXT_GAP).coerceAtLeast(0)
        }
        topicViewLayout = makeLayout(viewLabelText, topicMutedPaint, remaining)
        val usedRow1 = (topicCreatorLayout?.width ?: 0) + TOPIC_TEXT_GAP +
            (topicViewLayout?.width ?: 0) + TOPIC_TEXT_GAP
        val repliesRow1Max = (rowMaxW - usedRow1).coerceAtLeast(1)
        topicRepliesLayout = makeLayout(repliesLabelText, topicLinkPaint, repliesRow1Max.coerceAtLeast(repliesReserved))

        if (topicRepliesLayout == null ||
            (topicRepliesLayout!!.width < repliesNaturalW && repliesNaturalW > repliesRow1Max)
        ) {
            useTwoTextRows = true
            topicRepliesLayout = null
            remaining = rowMaxW
            topicCreatorLayout = makeLayout(creatorLabelText, topicLinkPaint, remaining)?.also {
                remaining = (remaining - it.width - TOPIC_TEXT_GAP).coerceAtLeast(0)
            }
            topicViewLayout = makeLayout(viewLabelText, topicMutedPaint, remaining)
            topicRepliesSecondRowLayout = makeLayout(repliesLabelText, topicLinkPaint, rowMaxW)
        }
    }

    private fun makeLayout(text: String, paint: TextPaint, maxWidth: Int): StaticLayout? {
        if (text.isBlank() || maxWidth <= 0) return null
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth.coerceAtLeast(1))
            .setMaxLines(1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun measureTextWidth(text: String, paint: TextPaint): Int {
        if (text.isBlank()) return 0
        return ceil(paint.measureText(text)).toInt()
    }

    companion object {
        private val TOPIC_AVATAR_SIZE = LayoutHelper.dp(20)
        private val TOPIC_ARROW_SIZE = LayoutHelper.dp(16)
        private val TOPIC_PADDING_H = LayoutHelper.dp(10)
        private val TOPIC_PADDING_V = LayoutHelper.dp(6)
        private val TOPIC_MARGIN_TOP = LayoutHelper.dp(4)
        private val TOPIC_RADIUS = LayoutHelper.dp(6).toFloat()
        private val TOPIC_BORDER = LayoutHelper.dp(1)
        private val TOPIC_AVATAR_GAP = LayoutHelper.dp(4)
        private val TOPIC_TEXT_GAP = LayoutHelper.dp(4)
        private val TOPIC_TEXT_SIZE = LayoutHelper.dpf(13f)
        private val TOPIC_BADGE_SIZE = LayoutHelper.dp(16)
        private val TOPIC_BADGE_TEXT_SIZE = LayoutHelper.dpf(10f)
        private val TOPIC_BADGE_TEXT_PAD = LayoutHelper.dp(4)
        private val PAD_END = LayoutHelper.dp(16)
        private val MIN_REPLIES_WIDTH = LayoutHelper.dp(56)
    }
}
