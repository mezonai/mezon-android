package com.mezon.mobile.home.chat.botcommand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class BotCommandStatusLayout(private val context: Context) {

    enum class Hit { None, Action, Dismiss }

    var isVisible = false
        private set
    var height = 0
        private set
    var width = 0
        private set

    private var glyphLayout: StaticLayout? = null
    private var labelLayout: StaticLayout? = null
    private var actionLayout: StaticLayout? = null
    private var dismissDrawable: Drawable? = null
    private var actionOnNewLine = false
    private var firstRowHeight = 0
    private var actionWidth = 0
    private var actionHeight = 0
    private var originX = 0f
    private var originY = 0f
    private val actionRect = RectF()
    private val dismissRect = RectF()

    private val glyphPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(13f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(12f)
        typeface = Typeface.MONOSPACE
    }
    private val actionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(12f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val actionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(1f)
    }

    fun clear() {
        isVisible = false
        height = 0
        width = 0
        glyphLayout = null
        labelLayout = null
        actionLayout = null
        dismissDrawable = null
        actionRect.setEmpty()
        dismissRect.setEmpty()
    }

    fun prepare(display: BotCommandDisplay?, theme: ThemeColors, maxWidth: Int) {
        if (display == null || maxWidth <= 0) {
            clear()
            return
        }
        val usesFallbackName = display.botName.isBlank()
        val botName = if (usesFallbackName) context.getString(R.string.bot_command_the_bot) else display.botName

        fun statusText(resId: Int): String {
            val text = if (resId == R.string.bot_command_failed) context.getString(resId) else context.getString(resId, botName)
            if (!usesFallbackName || text.isEmpty()) return text
            return text.substring(0, 1).uppercase() + text.substring(1)
        }

        val glyph: String
        val glyphColor: Int
        val label: String
        val actionTitle: String?
        when (display.status) {
            BotCommandStatus.Waiting -> {
                glyph = "…"
                glyphColor = WAITING_COLOR
                label = statusText(R.string.bot_command_waiting)
                actionTitle = null
            }
            is BotCommandStatus.Answered -> {
                glyph = "✓"
                glyphColor = theme.success
                label = statusText(R.string.bot_command_answered)
                actionTitle = context.getString(R.string.bot_command_view_reply)
            }
            BotCommandStatus.NoResponse -> {
                glyph = "!"
                glyphColor = WARNING_COLOR
                label = statusText(R.string.bot_command_no_response)
                actionTitle = if (display.resendable) context.getString(R.string.bot_command_resend) else null
            }
            BotCommandStatus.Failed -> {
                glyph = "✕"
                glyphColor = theme.redStrong
                label = statusText(R.string.bot_command_failed)
                actionTitle = if (display.resendable) context.getString(R.string.bot_command_retry) else null
            }
        }

        glyphPaint.color = glyphColor
        labelPaint.color = if (display.status == BotCommandStatus.Waiting) theme.textDisabled else theme.onSurface
        actionPaint.color = theme.onSurface
        actionBorderPaint.color = theme.border

        val labelMaxWidth = (maxWidth - GLYPH_WIDTH - ITEM_GAP * 2 - DISMISS_SIDE).coerceAtLeast(1)
        glyphLayout = singleLine(glyph, glyphPaint, GLYPH_WIDTH)
        actionLayout = actionTitle?.let { singleLine(it, actionPaint, labelMaxWidth - ACTION_PAD_H * 2) }
        actionWidth = actionLayout?.let { lineWidth(it) + ACTION_PAD_H * 2 } ?: 0
        actionHeight = actionLayout?.let { it.height + ACTION_PAD_V * 2 } ?: 0

        val naturalLabelWidth = labelPaint.measureText(label).toInt() + 1
        actionOnNewLine = actionLayout != null && naturalLabelWidth + ITEM_GAP + actionWidth > labelMaxWidth
        val labelBudget = if (actionLayout != null && !actionOnNewLine) labelMaxWidth - ITEM_GAP - actionWidth else labelMaxWidth
        labelLayout = StaticLayout.Builder.obtain(label, 0, label.length, labelPaint, labelBudget.coerceAtLeast(1)).build()

        val glyphHeight = glyphLayout?.height ?: 0
        val labelHeight = labelLayout?.height ?: 0
        firstRowHeight = maxOf(glyphHeight, labelHeight, DISMISS_SIDE)
        if (actionLayout != null && !actionOnNewLine) firstRowHeight = maxOf(firstRowHeight, actionHeight)
        height = firstRowHeight + if (actionOnNewLine) ROW_GAP + actionHeight else 0
        width = maxWidth

        dismissDrawable = MezonIcon.closeSmallBold.getDrawable(context).mutate().apply {
            setTint(theme.textDisabled)
        }
        isVisible = true
    }

    fun draw(canvas: Canvas, x: Float, y: Float): Float {
        if (!isVisible) return y
        originX = x
        originY = y

        glyphLayout?.let { layout ->
            canvas.save()
            canvas.translate(x + (GLYPH_WIDTH - lineWidth(layout)) / 2f, y + (firstRowHeight - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }

        val labelX = x + GLYPH_WIDTH + ITEM_GAP
        val labelWidth = labelLayout?.let { maxLineWidth(it) } ?: 0
        labelLayout?.let { layout ->
            canvas.save()
            canvas.translate(labelX, y + (firstRowHeight - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }

        val action = actionLayout
        if (action != null) {
            val left = if (actionOnNewLine) labelX else labelX + labelWidth + ITEM_GAP
            val top = if (actionOnNewLine) y + firstRowHeight + ROW_GAP else y + (firstRowHeight - actionHeight) / 2f
            actionRect.set(left - x, top - y, left - x + actionWidth, top - y + actionHeight)
            val radius = LayoutHelper.dpf(4f)
            val inset = actionBorderPaint.strokeWidth / 2f
            canvas.drawRoundRect(
                left + inset, top + inset, left + actionWidth - inset, top + actionHeight - inset,
                radius, radius, actionBorderPaint
            )
            canvas.save()
            canvas.translate(left + ACTION_PAD_H, top + ACTION_PAD_V)
            action.draw(canvas)
            canvas.restore()
        } else {
            actionRect.setEmpty()
        }

        val dismissLeft = x + width - DISMISS_SIDE
        val dismissTop = y + (firstRowHeight - DISMISS_SIDE) / 2f
        dismissRect.set(dismissLeft - x, dismissTop - y, dismissLeft - x + DISMISS_SIDE, dismissTop - y + DISMISS_SIDE)
        dismissDrawable?.let { d ->
            val inset = (DISMISS_SIDE - DISMISS_ICON) / 2
            d.setBounds(
                (dismissLeft + inset).toInt(), (dismissTop + inset).toInt(),
                (dismissLeft + inset + DISMISS_ICON).toInt(), (dismissTop + inset + DISMISS_ICON).toInt()
            )
            d.draw(canvas)
        }
        return y + height
    }

    fun hitTest(x: Float, y: Float): Hit {
        if (!isVisible) return Hit.None
        val localX = x - originX
        val localY = y - originY
        val slop = LayoutHelper.dpf(8f)
        if (!dismissRect.isEmpty &&
            localX >= dismissRect.left - slop && localX <= dismissRect.right + slop &&
            localY >= dismissRect.top - slop && localY <= dismissRect.bottom + slop
        ) return Hit.Dismiss
        if (!actionRect.isEmpty && actionRect.contains(localX, localY)) return Hit.Action
        return Hit.None
    }

    private fun singleLine(text: String, paint: TextPaint, maxWidth: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth.coerceAtLeast(1))
            .setMaxLines(1)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()

    private fun lineWidth(layout: StaticLayout): Int =
        if (layout.lineCount > 0) kotlin.math.ceil(layout.getLineWidth(0)).toInt() else 0

    private fun maxLineWidth(layout: StaticLayout): Int {
        var max = 0f
        for (i in 0 until layout.lineCount) max = maxOf(max, layout.getLineWidth(i))
        return kotlin.math.ceil(max).toInt()
    }

    private companion object {
        val GLYPH_WIDTH = LayoutHelper.dp(14f)
        val ITEM_GAP = LayoutHelper.dp(6f)
        val DISMISS_SIDE = LayoutHelper.dp(24f)
        val DISMISS_ICON = LayoutHelper.dp(14f)
        val ROW_GAP = LayoutHelper.dp(6f)
        val ACTION_PAD_H = LayoutHelper.dp(8f)
        val ACTION_PAD_V = LayoutHelper.dp(3f)
        val WAITING_COLOR = 0xFFA78BFA.toInt()
        val WARNING_COLOR = 0xFFF0B232.toInt()
    }
}
