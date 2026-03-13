package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.formatRelativeTime
import com.mezon.mobile.util.parseContentText

class SystemMessageCell(context: Context, private val theme: ThemeColors) : View(context) {

    var messageEntity: MessageEntity? = null
        private set

    private var textLayout: StaticLayout? = null
    private var timeLayout: StaticLayout? = null
    private var iconDrawable: Drawable? = null
    private var measuredCellHeight = CELL_MIN_HEIGHT

    fun update(mask: Int, newMsg: MessageEntity? = null): Boolean {
        val msg = newMsg ?: messageEntity ?: return false
        if (newMsg != null) messageEntity = newMsg
        if (mask != 0) return false

        iconDrawable = resolveIcon(msg)
        buildLayouts(msg)
        requestLayout()
        invalidate()
        return true
    }

    private fun resolveIcon(msg: MessageEntity): Drawable? {
        val icon = when (msg.code) {
            MessageEntity.CODE_WELCOME -> MezonIcon.auditLog
            MessageEntity.CODE_CREATE_THREAD -> MezonIcon.channelText
            MessageEntity.CODE_CREATE_PIN -> MezonIcon.pinIcon
            MessageEntity.CODE_AUDIT_LOG -> MezonIcon.auditLog
            MessageEntity.CODE_UPCOMING_EVENT -> MezonIcon.calendarIcon
            else -> null
        } ?: return null

        val d = icon.getDrawable(context).mutate()
        val tint = when (msg.code) {
            MessageEntity.CODE_WELCOME -> theme.success
            MessageEntity.CODE_AUDIT_LOG -> theme.primary
            MessageEntity.CODE_UPCOMING_EVENT -> theme.error
            else -> theme.onSurfaceVariant
        }
        d.setTint(tint)
        return d
    }

    private fun buildLayouts(msg: MessageEntity) {
        val maxWidth = measuredWidth.let { if (it > 0) it else resources.displayMetrics.widthPixels } - PAD_H * 2 - ICON_SIZE - ICON_GAP
        if (maxWidth <= 0) return

        val text = parseContentText(msg.content).ifBlank { systemFallbackText(msg) }
        textLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, theme.systemMessageTextPaint, maxWidth.coerceAtLeast(1))
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(LayoutHelper.dpf(2f), 1f)
            .build()

        val time = formatRelativeTime(msg.timestampSeconds)
        timeLayout = StaticLayout.Builder
            .obtain(time, 0, time.length, theme.chatTimePaint, maxWidth.coerceAtLeast(1))
            .setMaxLines(1)
            .build()

        var h = PAD_V * 2
        textLayout?.let { h += it.height + GAP_V }
        timeLayout?.let { h += it.height }
        measuredCellHeight = h.coerceAtLeast(CELL_MIN_HEIGHT)
    }

    private fun systemFallbackText(msg: MessageEntity): String = when (msg.code) {
        MessageEntity.CODE_WELCOME -> "Welcome!"
        MessageEntity.CODE_CREATE_THREAD -> "started a thread"
        MessageEntity.CODE_CREATE_PIN -> "pinned a message"
        MessageEntity.CODE_AUDIT_LOG -> "audit log"
        MessageEntity.CODE_UPCOMING_EVENT -> "upcoming event"
        else -> ""
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, measuredCellHeight)
        messageEntity?.let { buildLayouts(it) }
    }

    override fun onDraw(canvas: Canvas) {
        val iconX = PAD_H
        val iconY = PAD_V

        iconDrawable?.let { d ->
            d.setBounds(iconX, iconY, iconX + ICON_SIZE, iconY + ICON_SIZE)
            d.draw(canvas)
        }

        val textX = (PAD_H + ICON_SIZE + ICON_GAP).toFloat()
        var yOff = PAD_V.toFloat()

        textLayout?.let {
            canvas.save()
            canvas.translate(textX, yOff)
            it.draw(canvas)
            canvas.restore()
            yOff += it.height + GAP_V
        }

        timeLayout?.let {
            canvas.save()
            canvas.translate(textX, yOff)
            it.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        private val ICON_SIZE = LayoutHelper.dp(20)
        private val ICON_GAP = LayoutHelper.dp(8)
        private val PAD_H = LayoutHelper.dp(16)
        private val PAD_V = LayoutHelper.dp(8)
        private val GAP_V = LayoutHelper.dp(2)
        private val CELL_MIN_HEIGHT = LayoutHelper.dp(40)
    }
}
