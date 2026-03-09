package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class TextDetailCell(context: Context, private val theme: ThemeColors) : View(context) {

    private var titleText = ""
    private var valueText = ""
    private var titleLayout: StaticLayout? = null
    private var valueLayout: StaticLayout? = null
    private var needDivider = false

    init {
        minimumHeight = LayoutHelper.dp(60)
        isClickable = true
        isFocusable = true
    }

    fun setTextAndValue(title: String, value: String, divider: Boolean = false) {
        titleText = title
        valueText = value
        needDivider = divider
        titleLayout = null
        valueLayout = null
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val textWidth = w - LayoutHelper.dp(16 + 16)

        if (titleLayout == null && textWidth > 0) {
            titleLayout = StaticLayout.Builder
                .obtain(titleText, 0, titleText.length, theme.settingsNamePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setMaxLines(1)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }
        if (valueText.isNotEmpty() && valueLayout == null && textWidth > 0) {
            valueLayout = StaticLayout.Builder
                .obtain(valueText, 0, valueText.length, theme.settingsValuePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }

        var h = LayoutHelper.dp(12)
        titleLayout?.let { h += it.height }
        valueLayout?.let { h += it.height + LayoutHelper.dp(4) }
        h += LayoutHelper.dp(12)
        h = h.coerceAtLeast(LayoutHelper.dp(60))
        if (needDivider) h += 1

        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val leftPad = LayoutHelper.dp(16).toFloat()
        var top = LayoutHelper.dp(12).toFloat()

        titleLayout?.let {
            canvas.save()
            canvas.translate(leftPad, top)
            it.draw(canvas)
            canvas.restore()
            top += it.height
        }

        valueLayout?.let {
            top += LayoutHelper.dp(4)
            canvas.save()
            canvas.translate(leftPad, top)
            it.draw(canvas)
            canvas.restore()
        }

        if (needDivider) {
            canvas.drawRect(
                leftPad, (height - 1).toFloat(),
                width.toFloat(), height.toFloat(),
                theme.dividerPaint
            )
        }
    }
}
