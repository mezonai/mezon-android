package com.mezon.mobile.home.chat.thread

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.formatRelativeTime

class ThreadCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    private var threadInfo: ThreadInfo? = null
    private var senderName: String = ""

    private var nameLayout: StaticLayout? = null
    private var senderLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null

    private var chevronDrawable: Drawable? = null

    private val borderPaint = Paint().apply {
        color = theme.outlineVariant
    }

    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(17f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = theme.tabLabelActive
    }

    private val messagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        color = theme.textDisabled
    }

    private val bulletPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(18f)
        color = theme.textDisabled
    }

    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        color = theme.textDisabled
    }

    fun setData(info: ThreadInfo, resolvedSenderName: String) {
        threadInfo = info
        senderName = resolvedSenderName
        buildLayouts()
        invalidate()
    }

    override fun invalidate() {
        if (threadInfo == null) return
        super.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, CELL_HEIGHT)
        buildLayouts()
    }

    private fun buildLayouts() {
        val info = threadInfo ?: return
        val contentWidth = measuredWidth - PADDING * 2 - CHEVRON_MARGIN_LEFT - CHEVRON_SIZE - CHEVRON_MARGIN_RIGHT_ABS
        if (contentWidth <= 0) return

        nameLayout = StaticLayout.Builder.obtain(
            info.channelLabel, 0, info.channelLabel.length, namePaint, contentWidth
        ).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()

        val timeText = formatRelativeTime(info.lastMessageTs)
        val bulletText = " \u2022 "
        val bulletWidth = bulletPaint.measureText(bulletText).toInt()
        val timeWidth = timePaint.measureText(timeText).toInt()
        val dateAreaWidth = bulletWidth + timeWidth

        if (timeText.isNotEmpty()) {
            timeLayout = StaticLayout.Builder.obtain(
                timeText, 0, timeText.length, timePaint, timeWidth + LayoutHelper.dp(2)
            ).setMaxLines(1).build()
        } else {
            timeLayout = null
        }

        val senderAreaWidth = contentWidth - dateAreaWidth
        if (senderAreaWidth > 0) {
            val msgText = if (senderName.isNotEmpty() && info.lastMessageContent.isNotEmpty()) {
                "$senderName: ${info.lastMessageContent}"
            } else if (senderName.isNotEmpty()) {
                senderName
            } else {
                info.lastMessageContent
            }
            senderLayout = if (msgText.isNotEmpty()) {
                StaticLayout.Builder.obtain(
                    msgText, 0, msgText.length, messagePaint, senderAreaWidth
                ).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
            } else null
        } else {
            senderLayout = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val info = threadInfo ?: return

        canvas.drawRect(0f, 0f, measuredWidth.toFloat(), 1f, borderPaint)

        canvas.save()
        canvas.translate(PADDING.toFloat(), PADDING.toFloat())
        nameLayout?.draw(canvas)
        canvas.restore()

        val secondRowY = PADDING + (nameLayout?.height ?: 0) + LayoutHelper.dp(2)

        senderLayout?.let {
            canvas.save()
            canvas.translate(PADDING.toFloat(), secondRowY.toFloat())
            it.draw(canvas)
            canvas.restore()
        }

        val contentWidth = measuredWidth - PADDING * 2 - CHEVRON_MARGIN_LEFT - CHEVRON_SIZE - CHEVRON_MARGIN_RIGHT_ABS
        val timeText = formatRelativeTime(info.lastMessageTs)
        if (timeText.isNotEmpty()) {
            val bulletText = " \u2022 "
            val bulletWidth = bulletPaint.measureText(bulletText)
            val timeWidth = timePaint.measureText(timeText)
            val senderWidth = senderLayout?.let { if (it.lineCount > 0) it.getLineWidth(0) else 0f } ?: 0f
            val bulletX = PADDING + senderWidth + BULLET_MARGIN_LEFT
            val bulletBaseline = secondRowY.toFloat() + (senderLayout?.getLineBaseline(0)?.toFloat() ?: messagePaint.textSize)
            canvas.drawText(bulletText, bulletX, bulletBaseline, bulletPaint)
            val timeX = bulletX + bulletWidth
            canvas.drawText(timeText, timeX, bulletBaseline, timePaint)
        }

        if (chevronDrawable == null) {
            chevronDrawable = MezonIcon.chevronSmallRightIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(theme.textDisabled, PorterDuff.Mode.SRC_IN)
            }
        }
        val chevronX = measuredWidth - CHEVRON_SIZE - CHEVRON_MARGIN_RIGHT_ABS
        val chevronY = (CELL_HEIGHT - CHEVRON_SIZE) / 2
        chevronDrawable!!.setBounds(chevronX, chevronY, chevronX + CHEVRON_SIZE, chevronY + CHEVRON_SIZE)
        chevronDrawable!!.draw(canvas)
    }

    companion object {
        private val CELL_HEIGHT = LayoutHelper.dp(60f)
        private val PADDING = LayoutHelper.dp(10f)
        private val CHEVRON_SIZE = LayoutHelper.dp(24f)
        private val CHEVRON_MARGIN_LEFT = LayoutHelper.dp(30f)
        private val CHEVRON_MARGIN_RIGHT_ABS = LayoutHelper.dp(4f)
        private val BULLET_MARGIN_LEFT = LayoutHelper.dp(4f)
    }
}
