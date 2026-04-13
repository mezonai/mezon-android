package com.mezon.mobile.home.chat.thread

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ThreadSectionCell(context: Context, private val theme: ThemeColors) : View(context) {

    private var title: String = ""
    private var titleLayout: StaticLayout? = null

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(17f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = theme.onSurface
    }

    fun setTitle(text: String) {
        title = text
        buildLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, CELL_HEIGHT)
        buildLayout()
    }

    private fun buildLayout() {
        val maxWidth = measuredWidth - PADDING_H * 2
        if (maxWidth <= 0 || title.isEmpty()) return
        titleLayout = StaticLayout.Builder.obtain(
            title, 0, title.length, titlePaint, maxWidth
        ).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
    }

    override fun onDraw(canvas: Canvas) {
        titleLayout?.let {
            canvas.save()
            canvas.translate(PADDING_H.toFloat(), MARGIN_TOP.toFloat())
            it.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        private val MARGIN_TOP = LayoutHelper.dp(10f)
        private val MARGIN_BOTTOM = LayoutHelper.dp(10f)
        private val PADDING_H = LayoutHelper.dp(4f)
        private val CELL_HEIGHT = MARGIN_TOP + LayoutHelper.dp(22f) + MARGIN_BOTTOM
    }
}
