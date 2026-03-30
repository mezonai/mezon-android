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
            setTextColor(theme.onSurfaceVariant)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.TOP or LayoutHelper.getAbsoluteGravityStart()
        }
        addView(textView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.TOP or LayoutHelper.getAbsoluteGravityStart(),
            leftMargin = 21f, rightMargin = 21f
        ))
    }

    fun setText(value: String) {
        textView.text = value
    }

    fun setTopPadding(dp: Int) {
        topPaddingDp = dp
        requestLayout()
    }

    fun setSideMargin(dp: Int) {
        val lp = textView.layoutParams as LayoutParams
        lp.leftMargin = LayoutHelper.dp(dp)
        lp.rightMargin = LayoutHelper.dp(dp)
        textView.layoutParams = lp
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val topPad = LayoutHelper.dp(topPaddingDp)
        val bottomPad = LayoutHelper.dp(8)

        textView.setPadding(0, topPad, 0, bottomPad)
        
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isHeading = true
    }

    fun updateColors() {
        textView.setTextColor(theme.onSurfaceVariant)
    }
}
