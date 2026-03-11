package com.mezon.mobile.ui.cells

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class HeaderCell(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    val textView: TextView
    private var topPaddingDp = 16

    init {
        textView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(theme.primary)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityStart()
        }
        addView(textView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or LayoutHelper.getAbsoluteGravityStart(),
            leftMargin = 16f, rightMargin = 16f
        ))
    }

    fun setText(value: String) {
        textView.text = value
    }

    fun setTopPadding(dp: Int) {
        topPaddingDp = dp
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val topPad = LayoutHelper.dp(topPaddingDp)
        val bottomPad = LayoutHelper.dp(8)

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            heightMeasureSpec
        )
        val textH = textView.measuredHeight
        setMeasuredDimension(w, topPad + textH + bottomPad)

        val lp = textView.layoutParams as LayoutParams
        lp.topMargin = topPad
        textView.layoutParams = lp
        textView.measure(
            MeasureSpec.makeMeasureSpec(w - LayoutHelper.dp(32), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        setMeasuredDimension(w, topPad + textView.measuredHeight + bottomPad)
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isHeading = true
    }

    fun updateColors() {
        textView.setTextColor(theme.primary)
    }
}
