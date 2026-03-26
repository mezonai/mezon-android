package com.mezon.mobile.home.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class UnreadDividerDecoration(
    private val themeColors: ThemeColors,
    private val label: String
) : RecyclerView.ItemDecoration() {

    var firstUnreadAdapterPosition = RecyclerView.NO_POSITION

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINE_THICKNESS.toFloat()
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TEXT_SIZE.toFloat()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        applyColors()
    }

    fun applyColors() {
        val lineColor = LINE_COLOR
        val textColor = TEXT_COLOR
        linePaint.color = lineColor
        textPaint.color = textColor
        bgPaint.color = themeColors.background
    }

    fun clear() {
        firstUnreadAdapterPosition = RecyclerView.NO_POSITION
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos != RecyclerView.NO_POSITION && pos == firstUnreadAdapterPosition) {
            outRect.top = DIVIDER_HEIGHT
        }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (firstUnreadAdapterPosition == RecyclerView.NO_POSITION) return

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos != firstUnreadAdapterPosition) continue

            val childTop = child.top + child.translationY.toInt()
            val decorTop = childTop - DIVIDER_HEIGHT
            val centerY = (decorTop + childTop) / 2f

            val left = H_PADDING.toFloat()
            val right = parent.width - H_PADDING.toFloat()

            val textWidth = textPaint.measureText(label)
            val centerX = parent.width / 2f
            val textLeft = centerX - textWidth / 2f - TEXT_H_PAD
            val textRight = centerX + textWidth / 2f + TEXT_H_PAD

            c.drawLine(left, centerY, textLeft, centerY, linePaint)
            c.drawLine(textRight, centerY, right, centerY, linePaint)

            val textBaseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
            c.drawText(label, centerX, textBaseline, textPaint)

            break
        }
    }

    companion object {
        private val DIVIDER_HEIGHT = LayoutHelper.dp(36f)
        private val H_PADDING = LayoutHelper.dp(12f)
        private val TEXT_H_PAD = LayoutHelper.dp(8f)
        private val TEXT_SIZE = LayoutHelper.dp(12f)
        private val LINE_THICKNESS = LayoutHelper.dp(1f)
        private val LINE_COLOR = 0x80FF0000.toInt()
        private val TEXT_COLOR = 0xFFFF0000.toInt()
    }
}
