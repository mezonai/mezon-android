package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ShadowSectionCell(context: Context, private val theme: ThemeColors) : View(context) {

    private val sectionHeight = LayoutHelper.dp(12)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), sectionHeight)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), theme.shadowPaint)
    }
}
