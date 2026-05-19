package com.mezon.mobile.home.chat.poll

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import kotlin.math.min

private const val MAX_OPTIONS_COLLAPSED = 5

private data class OptionLine(
    val answerIndex: Int,
    val labelLayout: StaticLayout,
    val top: Float,
    val height: Float,
    val voteCount: Int,
    val percentage: Int
)

class PollMessageLayout(private val context: Context) {

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val optionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2f).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val optionSelectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val optionSelectionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.dp(17f).toFloat()
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.dp(13f).toFloat()
    }
    private val optionLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.dp(14f).toFloat()
    }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.dp(12f).toFloat()
    }
    private val linkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.dp(13f).toFloat()
    }
    private val buttonTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.dp(13f).toFloat()
        typeface = Typeface.DEFAULT_BOLD
    }

    private val cardRect = RectF()
    private val tmpRect = RectF()
    private val optionLines = ArrayList<OptionLine>()
    private var footerStatsRect = RectF()
    private var detailLinkRect = RectF()
    private var actionButtonRect = RectF()
    private var expandLinkRect = RectF()
    private var expandLinkVisible = false

    var cardWidth = 0
        private set
    var blockHeight = 0
        private set

    private var questionLayout: StaticLayout? = null
    private var subtitleLayout: StaticLayout? = null
    private var actionLabel: String = ""
    private var footerLeftLabel: String = ""
    private var footerRightTime: String = ""
    private var expandLinkText: String = ""
    private var footerStatsDrawText: String = ""
    private var detailLinkText: String = ""

    fun prepare(
        parsed: ParsedPoll,
        state: PollLocalState,
        currentUserId: Long,
        theme: ThemeColors,
        bubbleMaxW: Int,
        hostView: View
    ) {
        val pad = LayoutHelper.dp(12)
        cardWidth = bubbleMaxW.coerceAtLeast(LayoutHelper.dp(120))
        val innerW = (cardWidth - pad * 2).coerceAtLeast(1)

        val nowSec = System.currentTimeMillis() / 1000L
        val voted = resolvedVoted(parsed, currentUserId, state.optimisticMyIndices)
        val hasVoted = voted.isNotEmpty()
        val expired = parsed.expireAtSeconds > 0 && parsed.expireAtSeconds < nowSec
        val showResults = parsed.isClosed || expired || hasVoted || state.showResultsPreview

        titlePaint.color = theme.onSurface
        subtitlePaint.color = theme.onSurfaceVariant
        optionLabelPaint.color = theme.onSurface
        metaPaint.color = theme.onSurfaceVariant
        linkPaint.color = theme.blurple
        buttonTextPaint.color = 0xFFFFFFFF.toInt()
        cardPaint.color = theme.surfaceVariant
        optionBgPaint.color = (theme.onSurface and 0x00FFFFFF) or 0x18000000
        barFillPaint.color = theme.primary
        buttonPaint.color = theme.primary
        checkPaint.color = theme.primary
        checkStroke.color = 0xFFFFFFFF.toInt()
        optionSelectionFillPaint.color = (theme.primary and 0x00FFFFFF) or 0x28000000
        optionSelectionStrokePaint.color = theme.primary
        optionSelectionStrokePaint.strokeWidth = LayoutHelper.dpf(2f)

        val qText = pollAnswerPlainText(parsed.question)
        questionLayout = StaticLayout.Builder.obtain(qText, 0, qText.length, titlePaint, innerW)
            .setMaxLines(6)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()

        val sub = context.getString(
            if (parsed.isMultiple) R.string.poll_select_multiple else R.string.poll_select_one
        )
        subtitleLayout = StaticLayout.Builder.obtain(sub, 0, sub.length, subtitlePaint, innerW)
            .setMaxLines(2)
            .build()

        actionLabel = computeActionLabel(parsed, state, hasVoted, showResults, expired)

        val total = parsed.totalVotes.coerceAtLeast(0)
        footerLeftLabel = context.resources.getQuantityString(R.plurals.poll_total_votes, total, total)
        footerRightTime = when {
            parsed.isClosed -> context.getString(R.string.poll_closed)
            expired -> context.getString(R.string.poll_ended)
            parsed.expireAtSeconds > 0 -> {
                val left = parsed.expireAtSeconds - nowSec
                if (left <= 0) context.getString(R.string.poll_ended)
                else formatRemaining(left)
            }
            else -> ""
        }

        optionLines.clear()
        val visible = visibleAnswers(parsed, state)
        val optionH = LayoutHelper.dp(44).toFloat()
        val gap = LayoutHelper.dp(6).toFloat()

        var y = pad.toFloat()
        y += (questionLayout?.height ?: 0) + LayoutHelper.dp(6)
        y += (subtitleLayout?.height ?: 0) + LayoutHelper.dp(10)

        val optionPadH = LayoutHelper.dp(12)
        val labelBaseW = (innerW - optionPadH * 2).coerceAtLeast(LayoutHelper.dp(40))

        for (ans in visible) {
            val count = parsed.countFor(ans.index)
            val pct = if (parsed.totalVotes > 0) ((count * 100f) / parsed.totalVotes).toInt().coerceIn(0, 100) else 0
            var labelMaxW = labelBaseW.toFloat()
            if (showResults) {
                val meta = "${pct}% · " + context.resources.getQuantityString(
                    R.plurals.poll_option_votes,
                    count,
                    count
                )
                val mw = metaPaint.measureText(meta)
                val side = LayoutHelper.dp(18).toFloat()
                val edgePad = LayoutHelper.dp(10).toFloat()
                labelMaxW = (labelBaseW - mw - side - edgePad - LayoutHelper.dp(6)).coerceAtLeast(LayoutHelper.dp(40).toFloat())
            }
            val labelSeq = buildPollAnswerSpannable(ans.label, hostView)
            val labelLayout = StaticLayout.Builder.obtain(
                labelSeq, 0, labelSeq.length, optionLabelPaint, labelMaxW.toInt()
            )
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            optionLines.add(
                OptionLine(
                    answerIndex = ans.index,
                    labelLayout = labelLayout,
                    top = y,
                    height = optionH,
                    voteCount = count,
                    percentage = pct
                )
            )
            y += optionH + gap
        }

        expandLinkVisible = parsed.answers.size > MAX_OPTIONS_COLLAPSED
        expandLinkText = context.getString(
            if (state.optionsExpanded) R.string.poll_show_less else R.string.poll_load_more
        )
        if (expandLinkVisible) {
            val lh = LayoutHelper.dp(20).toFloat()
            expandLinkRect.set(
                pad.toFloat(),
                y,
                pad + linkPaint.measureText(expandLinkText),
                y + lh
            )
            y += lh + gap
        } else {
            expandLinkRect.setEmpty()
            expandLinkText = ""
        }

        val footerH = LayoutHelper.dp(22).toFloat()
        val footerTop = y + LayoutHelper.dp(4)

        val btnPadH = LayoutHelper.dp(14).toFloat()
        val btnPadV = LayoutHelper.dp(10).toFloat()
        val bw = if (actionLabel.isNotEmpty()) {
            buttonTextPaint.measureText(actionLabel) + btnPadH * 2
        } else {
            0f
        }
        val bh = if (actionLabel.isNotEmpty()) {
            LayoutHelper.dp(20) + btnPadV * 2
        } else {
            0f
        }
        val rowH = maxOf(footerH, bh)
        if (actionLabel.isNotEmpty()) {
            val btnTop = footerTop + (rowH - bh) / 2f
            actionButtonRect.set(
                cardWidth - pad - bw,
                btnTop,
                (cardWidth - pad).toFloat(),
                btnTop + bh
            )
        } else {
            actionButtonRect.setEmpty()
        }

        val footerStatsFull = buildString {
            append(footerLeftLabel)
            if (footerRightTime.isNotEmpty()) {
                append(" · ")
                append(footerRightTime)
            }
        }
        val fullW = (cardWidth - pad * 2).toFloat()
        val maxStatsW = if (bw > 0f) {
            (actionButtonRect.left - pad - LayoutHelper.dp(8)).coerceIn(1f, fullW)
        } else {
            fullW
        }
        footerStatsDrawText = TextUtils.ellipsize(
            footerStatsFull,
            metaPaint,
            maxStatsW,
            TextUtils.TruncateAt.END
        ).toString()
        val statsW = metaPaint.measureText(footerStatsDrawText)
        footerStatsRect.set(pad.toFloat(), footerTop, pad + statsW, footerTop + rowH)

        detailLinkText = context.getString(R.string.poll_view_details)
        val detailH = LayoutHelper.dp(22).toFloat()
        val detailTop = footerTop + rowH + LayoutHelper.dp(4)
        val detailW = linkPaint.measureText(detailLinkText)
        detailLinkRect.set(pad.toFloat(), detailTop, pad + detailW, detailTop + detailH)

        y = detailTop + detailH + LayoutHelper.dp(12)
        blockHeight = y.toInt().coerceAtLeast((actionButtonRect.bottom + LayoutHelper.dp(12)).toInt())
        cardRect.set(0f, 0f, cardWidth.toFloat(), blockHeight.toFloat())
    }

    private fun visibleAnswers(parsed: ParsedPoll, state: PollLocalState): List<PollAnswerItem> {
        val all = parsed.answers
        if (all.size <= MAX_OPTIONS_COLLAPSED || state.optionsExpanded) return all
        return all.take(MAX_OPTIONS_COLLAPSED)
    }

    private fun formatRemaining(seconds: Long): String {
        val h = (seconds / 3600).toInt()
        val d = h / 24
        return when {
            d >= 1 -> context.resources.getQuantityString(R.plurals.poll_days_remaining, d, d)
            h >= 1 -> context.resources.getQuantityString(R.plurals.poll_hours_remaining, h, h)
            else -> context.getString(R.string.poll_less_than_hour)
        }
    }

    private fun computeActionLabel(
        parsed: ParsedPoll,
        state: PollLocalState,
        hasVoted: Boolean,
        showResults: Boolean,
        expired: Boolean
    ): String = when {
        parsed.isClosed || expired -> ""
        showResults && !hasVoted && !parsed.isClosed && !expired -> context.getString(R.string.poll_back_to_vote)
        hasVoted -> context.getString(R.string.poll_remove_vote)
        state.selection.isNotEmpty() -> context.getString(R.string.poll_vote)
        else -> context.getString(R.string.poll_show_results)
    }

    fun draw(canvas: Canvas, left: Float, top: Float, parsed: ParsedPoll, state: PollLocalState, currentUserId: Long) {
        canvas.save()
        canvas.translate(left, top)
        val r = LayoutHelper.dpf(12f)
        tmpRect.set(cardRect)
        canvas.drawRoundRect(tmpRect, r, r, cardPaint)

        val pad = LayoutHelper.dp(12).toFloat()
        var y = pad
        questionLayout?.let { lay ->
            canvas.save()
            canvas.translate(pad, y)
            lay.draw(canvas)
            canvas.restore()
            y += lay.height + LayoutHelper.dp(6)
        }
        subtitleLayout?.let { lay ->
            canvas.save()
            canvas.translate(pad, y)
            lay.draw(canvas)
            canvas.restore()
            y += lay.height + LayoutHelper.dp(10)
        }

        val nowSec = System.currentTimeMillis() / 1000L
        val voted = resolvedVoted(parsed, currentUserId, state.optimisticMyIndices)
        val hasVoted = voted.isNotEmpty()
        val expired = parsed.expireAtSeconds > 0 && parsed.expireAtSeconds < nowSec
        val showResults = parsed.isClosed || expired || hasVoted || state.showResultsPreview
        val innerW = cardWidth - pad * 2
        val canPick = !parsed.isClosed && !expired && !hasVoted && !state.showResultsPreview

        for (line in optionLines) {
            val highlight = canPick && state.selection.contains(line.answerIndex)
            drawOptionLine(
                canvas, pad, line, innerW.toFloat(), showResults,
                voted.contains(line.answerIndex), highlight
            )
        }

        if (expandLinkVisible && expandLinkText.isNotEmpty()) {
            canvas.drawText(expandLinkText, expandLinkRect.left, expandLinkRect.bottom - LayoutHelper.dp(4), linkPaint)
        }

        canvas.drawText(
            footerStatsDrawText,
            footerStatsRect.left,
            footerStatsRect.top + footerStatsRect.height() / 2f - (metaPaint.descent() + metaPaint.ascent()) / 2f,
            metaPaint
        )
        if (detailLinkText.isNotEmpty()) {
            canvas.drawText(
                detailLinkText,
                detailLinkRect.left,
                detailLinkRect.top + detailLinkRect.height() / 2f - (linkPaint.descent() + linkPaint.ascent()) / 2f,
                linkPaint
            )
        }

        if (actionLabel.isNotEmpty() && !actionButtonRect.isEmpty) {
            val rr = LayoutHelper.dpf(8f)
            canvas.drawRoundRect(actionButtonRect, rr, rr, buttonPaint)
            val fx = actionButtonRect.left + (actionButtonRect.width() - buttonTextPaint.measureText(actionLabel)) / 2f
            val fy = actionButtonRect.centerY() - (buttonTextPaint.ascent() + buttonTextPaint.descent()) / 2f
            canvas.drawText(actionLabel, fx, fy, buttonTextPaint)
        }

        canvas.restore()
    }

    private fun drawOptionLine(
        canvas: Canvas,
        pad: Float,
        line: OptionLine,
        innerW: Float,
        showResults: Boolean,
        isChosen: Boolean,
        highlightSelection: Boolean
    ) {
        val top = line.top
        val h = line.height
        val left = pad
        val right = pad + innerW
        val rx = LayoutHelper.dpf(10f)
        tmpRect.set(left, top, right, top + h)
        canvas.drawRoundRect(tmpRect, rx, rx, optionBgPaint)

        if (highlightSelection) {
            canvas.drawRoundRect(tmpRect, rx, rx, optionSelectionFillPaint)
            canvas.drawRoundRect(tmpRect, rx, rx, optionSelectionStrokePaint)
        }

        if (showResults) {
            val fillW = innerW * (line.percentage / 100f)
            barFillPaint.alpha = if (isChosen) 210 else 110
            val inset = LayoutHelper.dp(4).toFloat()
            val inset8 = LayoutHelper.dp(8).toFloat()
            tmpRect.set(
                left + inset,
                top + inset,
                left + inset + min(fillW, innerW - inset8),
                top + h - inset
            )
            val barRx = LayoutHelper.dpf(6f)
            canvas.drawRoundRect(tmpRect, barRx, barRx, barFillPaint)
            barFillPaint.alpha = 255
        }

        val labelX = left + LayoutHelper.dp(12)
        val lay = line.labelLayout
        val labelTop = top + (h - lay.height) / 2f
        canvas.save()
        canvas.translate(labelX, labelTop)
        lay.draw(canvas)
        canvas.restore()

        if (showResults) {
            val labelY = top + h / 2f - (optionLabelPaint.descent() + optionLabelPaint.ascent()) / 2f
            val meta = "${line.percentage}% · " + context.resources.getQuantityString(
                R.plurals.poll_option_votes,
                line.voteCount,
                line.voteCount
            )
            val mw = metaPaint.measureText(meta)
            val side = LayoutHelper.dp(18).toFloat()
            val edgePad = LayoutHelper.dp(10).toFloat()
            val cx = right - edgePad - side / 2f
            val metaRight = if (isChosen) cx - side / 2f - LayoutHelper.dp(6).toFloat() else right - LayoutHelper.dp(12)
            canvas.drawText(meta, metaRight - mw, labelY, metaPaint)
            if (isChosen) {
                val cy = top + h / 2f
                tmpRect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
                val checkRx = LayoutHelper.dpf(4f)
                canvas.drawRoundRect(tmpRect, checkRx, checkRx, checkPaint)
                canvas.drawLine(cx - side * 0.12f, cy, cx - side * 0.02f, cy + side * 0.14f, checkStroke)
                canvas.drawLine(cx - side * 0.02f, cy + side * 0.14f, cx + side * 0.18f, cy - side * 0.12f, checkStroke)
            }
        }
    }

    fun hitTest(x: Float, y: Float, cardLeft: Float, cardTop: Float): PollTap? {
        val lx = x - cardLeft
        val ly = y - cardTop
        val edgeSlop = LayoutHelper.dp(4).toFloat()
        if (lx < cardRect.left - edgeSlop || ly < cardRect.top - edgeSlop ||
            lx > cardRect.right + edgeSlop || ly > cardRect.bottom + edgeSlop) return null
        if (expandLinkVisible && expandLinkRect.contains(lx, ly)) return PollTap.ToggleExpandOptions
        val optPad = LayoutHelper.dp(12).toFloat()
        val optSlop = LayoutHelper.dp(3).toFloat()
        val firstRowSlop = LayoutHelper.dp(14).toFloat()
        val optLeft = cardRect.left + optPad - optSlop
        val optRight = cardRect.right - optPad + optSlop
        for (i in optionLines.indices) {
            val line = optionLines[i]
            val vSlop = if (i == 0) firstRowSlop else optSlop
            if (lx >= optLeft && lx <= optRight &&
                ly >= line.top - vSlop && ly <= line.top + line.height + optSlop
            ) {
                return PollTap.ToggleOption(line.answerIndex)
            }
        }
        if (!actionButtonRect.isEmpty && actionButtonRect.contains(lx, ly)) return PollTap.PrimaryAction
        if (detailLinkText.isNotEmpty() && detailLinkRect.contains(lx, ly)) return PollTap.ViewDetails
        return null
    }
}

private fun resolvedVoted(parsed: ParsedPoll, currentUserId: Long, optimistic: List<Int>?): List<Int> {
    if (optimistic != null) return optimistic
    return votedAnswerIndices(parsed, currentUserId)
}
