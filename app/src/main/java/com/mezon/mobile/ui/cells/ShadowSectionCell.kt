package com.mezon.mobile.ui.cells

import android.content.Context
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ShadowSectionCell(context: Context, private val theme: ThemeColors) : View(context) {

    private val sectionHeight = LayoutHelper.dp(12)

    init {
        setBackgroundColor(theme.shadowPaint.color)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), sectionHeight)
    }

    fun updateColors() {
        setBackgroundColor(theme.shadowPaint.color)
    }
}
