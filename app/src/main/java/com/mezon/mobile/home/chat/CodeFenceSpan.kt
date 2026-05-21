package com.mezon.mobile.home.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import com.mezon.mobile.core.LayoutHelper

class CodeFenceSpan(
    private val bgColor: Int,
    var spanFirstLine: Int = -1,
    var spanLastLine: Int = -1,
) : LineBackgroundSpan, LeadingMarginSpan {

    private val rect = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val radius = LayoutHelper.dp(4).toFloat()
    private val containerInsetH = LayoutHelper.dp(4)
    private val innerTextPadH = LayoutHelper.dp(12)

    override fun getLeadingMargin(first: Boolean): Int = innerTextPadH + containerInsetH

    companion object {
        fun layoutExtraHorizontalShrink(): Int = LayoutHelper.dp(4) * 2
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
        bgPaint.color = bgColor
        val isFirst = lineNumber == spanFirstLine || spanFirstLine < 0
        val isLast = lineNumber == spanLastLine || spanLastLine < 0
        val t = top.toFloat()
        val b = bottom.toFloat()
        val inset = containerInsetH.toFloat()
        val l = left + inset
        val r = right - inset
        if (l >= r) return
        rect.set(l, t, r, b)
        if (isFirst || isLast) {
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
        } else {
            canvas.drawRect(rect, bgPaint)
        }
    }
}
