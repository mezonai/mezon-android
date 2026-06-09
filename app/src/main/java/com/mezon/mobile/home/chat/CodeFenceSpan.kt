package com.mezon.mobile.home.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import com.mezon.mobile.core.LayoutHelper

class CodeFenceSpan(
    private val bgColor: Int,
    var spanFirstLine: Int = -1,
    var spanLastLine: Int = -1,
) : LineBackgroundSpan, LeadingMarginSpan {

    private val rect = RectF()
    private val cornerRadii = FloatArray(8)
    private val bgPath = Path()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val radius = LayoutHelper.dp(4).toFloat()
    private val lineGapBridge = LayoutHelper.dpf(2f)
    private val containerInsetH = LayoutHelper.dp(4)
    private val innerTextPadH = LayoutHelper.dp(12)

    override fun getLeadingMargin(first: Boolean): Int = innerTextPadH + containerInsetH

    companion object {
        fun layoutExtraHorizontalShrink(): Int = LayoutHelper.dp(4) * 2

        fun layoutWidthForText(charSeq: CharSequence, baseWidth: Int): Int {
            val spanned = charSeq as? Spanned ?: return baseWidth
            if (spanned.getSpans(0, spanned.length, CodeFenceSpan::class.java).isEmpty()) return baseWidth
            return (baseWidth - layoutExtraHorizontalShrink()).coerceAtLeast(1)
        }

        fun bindLineBounds(spanned: Spanned, layout: Layout) {
            for (span in spanned.getSpans(0, spanned.length, CodeFenceSpan::class.java)) {
                val spanStart = spanned.getSpanStart(span)
                val spanEnd = spanned.getSpanEnd(span)
                val firstContent = (spanStart until spanEnd).firstOrNull { spanned[it] != '\n' } ?: spanStart
                val lastContent = (spanEnd - 1 downTo spanStart).firstOrNull { spanned[it] != '\n' }
                    ?: (spanEnd - 1).coerceAtLeast(spanStart)
                val a = firstContent.coerceAtMost(lastContent)
                val b = lastContent.coerceAtLeast(firstContent)
                span.spanFirstLine = layout.getLineForOffset(a)
                span.spanLastLine = layout.getLineForOffset(b)
            }
        }

        fun buildRichStaticLayout(
            charSeq: CharSequence,
            paint: android.text.TextPaint,
            baseWidth: Int,
            configure: StaticLayout.Builder.() -> Unit = {},
        ): StaticLayout {
            val layoutW = layoutWidthForText(charSeq, baseWidth)
            val layout = StaticLayout.Builder.obtain(charSeq, 0, charSeq.length, paint, layoutW)
                .apply(configure)
                .build()
            (charSeq as? Spanned)?.let { bindLineBounds(it, layout) }
            return layout
        }
    }

    override fun drawLeadingMargin(
        canvas: Canvas, paint: Paint, x: Int, dir: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int,
        first: Boolean, layout: android.text.Layout
    ) {
    }

    override fun drawBackground(
        canvas: Canvas, paint: Paint,
        left: Int, right: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int,
        lineNumber: Int
    ) {
        if (spanFirstLine >= 0 && lineNumber < spanFirstLine) return
        if (spanLastLine >= 0 && lineNumber > spanLastLine) return
        bgPaint.color = bgColor
        val isFirst = spanFirstLine >= 0 && lineNumber == spanFirstLine
        val isLast = spanLastLine >= 0 && lineNumber == spanLastLine
        var t = top.toFloat()
        var b = bottom.toFloat()
        if (!isFirst) t -= lineGapBridge
        if (!isLast) b += lineGapBridge
        val inset = containerInsetH.toFloat()
        val l = left + inset
        val r = right - inset
        if (l >= r) return
        rect.set(l, t, r, b)
        when {
            isFirst && isLast -> canvas.drawRoundRect(rect, radius, radius, bgPaint)
            isFirst -> drawPartialRoundRect(canvas, roundTop = true, roundBottom = false)
            isLast -> drawPartialRoundRect(canvas, roundTop = false, roundBottom = true)
            else -> canvas.drawRect(rect, bgPaint)
        }
    }

    private fun drawPartialRoundRect(canvas: Canvas, roundTop: Boolean, roundBottom: Boolean) {
        val topR = if (roundTop) radius else 0f
        val bottomR = if (roundBottom) radius else 0f
        cornerRadii[0] = topR
        cornerRadii[1] = topR
        cornerRadii[2] = topR
        cornerRadii[3] = topR
        cornerRadii[4] = bottomR
        cornerRadii[5] = bottomR
        cornerRadii[6] = bottomR
        cornerRadii[7] = bottomR
        bgPath.reset()
        bgPath.addRoundRect(rect, cornerRadii, Path.Direction.CW)
        canvas.drawPath(bgPath, bgPaint)
    }
}
