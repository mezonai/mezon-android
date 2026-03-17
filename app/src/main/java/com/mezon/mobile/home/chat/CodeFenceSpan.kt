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
    var spanLastLine: Int = -1
) : LineBackgroundSpan, LeadingMarginSpan {

    private val rect = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val radius = LayoutHelper.dp(4).toFloat()
    private val paddingH = LayoutHelper.dp(10).toFloat()

    override fun getLeadingMargin(first: Boolean): Int = paddingH.toInt()

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
        rect.set(0f, top.toFloat(), right.toFloat(), bottom.toFloat())
        val isFirst = lineNumber == spanFirstLine || spanFirstLine < 0
        val isLast = lineNumber == spanLastLine || spanLastLine < 0
        if (isFirst || isLast) {
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
        } else {
            canvas.drawRect(rect, bgPaint)
        }
    }
}
