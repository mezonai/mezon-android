package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.util.TypedValue
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class TextDetailCell(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    val textView: TextView
    val valueTextView: TextView
    private val textContainer: LinearLayout
    private var needDivider = false

    init {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)

        textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        textView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(theme.onSurface)
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textContainer.addView(textView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        valueTextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(theme.onSurfaceVariant)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textContainer.addView(valueTextView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            topMargin = 4f
        ))

        addView(textContainer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL,
            leftMargin = 20f, topMargin = 12f, rightMargin = 20f, bottomMargin = 12f
        ))

        minimumHeight = LayoutHelper.dp(64)
        setWillNotDraw(true)
    }

    fun setTextAndValue(title: String, value: String, divider: Boolean = false) {
        textView.text = title
        valueTextView.text = value
        valueTextView.visibility = if (value.isNotEmpty()) VISIBLE else GONE
        needDivider = divider
        setWillNotDraw(!divider)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = measuredHeight.coerceAtLeast(LayoutHelper.dp(64)) + if (needDivider) 1 else 0
        setMeasuredDimension(measuredWidth, h)
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onDraw(canvas: Canvas) {
        if (needDivider) {
            val leftPad = LayoutHelper.dp(20).toFloat()
            val y = (height - 1).toFloat()
            canvas.drawRect(leftPad, y, width.toFloat(), y + 1f, theme.dividerPaint)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val value = if (valueTextView.visibility == VISIBLE) valueTextView.text else null
        if (value != null) {
            info.text = "${textView.text} $value"
        }
    }

    fun updateColors() {
        textView.setTextColor(theme.onSurface)
        valueTextView.setTextColor(theme.onSurfaceVariant)
    }
}
