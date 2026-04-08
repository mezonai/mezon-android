package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class WelcomeMessageCell(context: Context, private val theme: ThemeColors) : View(context) {

    var channelName = ""
    var channelType = 0
    var clanId = 0L
    var isPrivate = false

    private var messageEntity: MessageEntity? = null
    private var titleLayout: StaticLayout? = null
    private var subtitleLayout: StaticLayout? = null
    private var iconDrawable: Drawable? = null
    private var measuredCellHeight = 0

    private val isDM: Boolean get() = clanId == 0L
    private val isGroup: Boolean get() = channelType == 2
    private val isChannel: Boolean get() = !isDM

    fun update(msg: MessageEntity) {
        messageEntity = msg
        iconDrawable = resolveIcon()
        requestLayout()
        invalidate()
    }

    private fun resolveIcon(): Drawable? {
        if (isDM) return null
        val icon = if (isPrivate) MezonIcon.channelTextLock else MezonIcon.channelText
        return icon.getDrawable(context)
    }

    private fun buildLayouts() {
        val w = measuredWidth
        if (w <= 0) return
        val contentWidth = w - PAD_H * 2

        val title = buildTitleText()
        titleLayout = StaticLayout.Builder
            .obtain(title, 0, title.length, titlePaint, contentWidth.coerceAtLeast(1))
            .setMaxLines(3)
            .setLineSpacing(LayoutHelper.dpf(2f), 1f)
            .build()

        val subtitle = buildSubtitleText()
        subtitleLayout = if (subtitle.isNotEmpty()) {
            StaticLayout.Builder
                .obtain(subtitle, 0, subtitle.length, subtitlePaint, contentWidth.coerceAtLeast(1))
                .setMaxLines(4)
                .setLineSpacing(LayoutHelper.dpf(2f), 1f)
                .build()
        } else null

        var h = PAD_TOP
        if (iconDrawable != null) h += ICON_CIRCLE_SIZE + ICON_MARGIN_BOTTOM
        titleLayout?.let { h += it.height + TITLE_MARGIN_BOTTOM }
        subtitleLayout?.let { h += it.height }
        h += PAD_BOTTOM
        measuredCellHeight = h
    }

    private fun buildTitleText(): String = when {
        isDM && isGroup -> channelName.ifEmpty { "Group" }
        isDM -> channelName.ifEmpty { "Direct Message" }
        else -> if (channelName.isNotEmpty()) "Welcome to #$channelName" else "Welcome!"
    }

    private fun buildSubtitleText(): String = when {
        isDM && isGroup -> "Welcome to the group! This is the start of the group."
        isDM -> if (channelName.isNotEmpty()) "This is the beginning of your direct message history with $channelName." else ""
        else -> {
            val chType = if (isPrivate) "private " else ""
            if (channelName.isNotEmpty()) "This is the start of the #$channelName ${chType}channel."
            else "This is the start of the channel."
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, 0)
        buildLayouts()
        setMeasuredDimension(w, measuredCellHeight)
    }

    override fun onDraw(canvas: Canvas) {
        var y = PAD_TOP.toFloat()

        iconDrawable?.let { d ->
            val cx = PAD_H + ICON_CIRCLE_SIZE / 2
            val cy = y.toInt() + ICON_CIRCLE_SIZE / 2
            val radius = ICON_CIRCLE_SIZE / 2

            iconCirclePaint.color = theme.surfaceVariant
            canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius.toFloat(), iconCirclePaint)

            val iconHalf = ICON_INNER_SIZE / 2
            d.setBounds(cx - iconHalf, cy - iconHalf, cx + iconHalf, cy + iconHalf)
            d.draw(canvas)

            y += (ICON_CIRCLE_SIZE + ICON_MARGIN_BOTTOM).toFloat()
        }

        titlePaint.color = theme.onSurface
        titleLayout?.let {
            canvas.save()
            canvas.translate(PAD_H.toFloat(), y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + TITLE_MARGIN_BOTTOM
        }

        subtitlePaint.color = theme.onSurfaceVariant
        subtitleLayout?.let {
            canvas.save()
            canvas.translate(PAD_H.toFloat(), y)
            it.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        private val PAD_H = LayoutHelper.dp(16)
        private val PAD_TOP = LayoutHelper.dp(30)
        private val PAD_BOTTOM = LayoutHelper.dp(30)
        private val ICON_CIRCLE_SIZE = LayoutHelper.dp(70)
        private val ICON_INNER_SIZE = LayoutHelper.dp(40)
        private val ICON_MARGIN_BOTTOM = LayoutHelper.dp(10)
        private val TITLE_MARGIN_BOTTOM = LayoutHelper.dp(10)

        private val iconCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(22f)
            isFakeBoldText = true
        }

        private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(12f)
        }
    }
}
