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

class TextCheckCell(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    val textView: TextView
    val subtitleTextView: TextView
    private val switchView = SwitchView(context, theme)
    private val textContainer: LinearLayout
    private var needDivider = false

    var onCheckedChange: ((Boolean) -> Unit)?
        get() = switchView.onCheckedChange
        set(value) { switchView.onCheckedChange = value }

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
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textContainer.addView(textView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        subtitleTextView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(theme.onSurfaceVariant)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = GONE
        }
        textContainer.addView(subtitleTextView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            topMargin = 4f
        ))

        addView(textContainer, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL,
            leftMargin = 20f, topMargin = 12f, rightMargin = 66f, bottomMargin = 12f
        ))

        addView(switchView, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.END,
            rightMargin = 16f
        ))

        minimumHeight = LayoutHelper.dp(50)
        setWillNotDraw(true)
    }

    fun setTextAndCheck(title: String, subtitle: String = "", checked: Boolean, divider: Boolean = false) {
        textView.text = title
        if (subtitle.isNotEmpty()) {
            subtitleTextView.text = subtitle
            subtitleTextView.visibility = VISIBLE
        } else {
            subtitleTextView.visibility = GONE
        }
        switchView.setChecked(checked, animated = false)
        needDivider = divider
        setWillNotDraw(!divider)
    }

    fun isChecked(): Boolean = switchView.isChecked()

    fun setChecked(value: Boolean) {
        switchView.setChecked(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val h = measuredHeight.coerceAtLeast(LayoutHelper.dp(50)) + if (needDivider) 1 else 0
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
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = switchView.isChecked()
    }

    fun updateColors() {
        textView.setTextColor(theme.onSurface)
        subtitleTextView.setTextColor(theme.onSurfaceVariant)
    }
}
