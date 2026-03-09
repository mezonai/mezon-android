package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class HeaderCell(context: Context, private val theme: ThemeColors) : View(context) {

    private var text = ""
    private var textLayout: StaticLayout? = null
    private var topPadding = LayoutHelper.dp(16)

    fun setText(value: String) {
        text = value
        textLayout = null
        requestLayout()
    }

    fun setTopPadding(dp: Int) {
        topPadding = LayoutHelper.dp(dp)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val textWidth = w - LayoutHelper.dp(16 + 16)

        if (textLayout == null && textWidth > 0) {
            textLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, theme.headerPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }

        val textH = textLayout?.height ?: 0
        val h = topPadding + textH + LayoutHelper.dp(8)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        textLayout?.let {
            canvas.save()
            canvas.translate(LayoutHelper.dp(16).toFloat(), topPadding.toFloat())
            it.draw(canvas)
            canvas.restore()
        }
    }
}
