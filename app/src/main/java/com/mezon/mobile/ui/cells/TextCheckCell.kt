package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class TextCheckCell(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    private val switchView = SwitchView(context, theme)
    private var titleText = ""
    private var subtitleText = ""
    private var titleLayout: StaticLayout? = null
    private var subtitleLayout: StaticLayout? = null
    private var needDivider = false
    var onCheckedChange: ((Boolean) -> Unit)?
        get() = switchView.onCheckedChange
        set(value) { switchView.onCheckedChange = value }

    init {
        setWillNotDraw(false)
        val switchLp = LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.END,
            rightMargin = 16f
        )
        addView(switchView, switchLp)
        minimumHeight = LayoutHelper.dp(48)
    }

    fun setTextAndCheck(title: String, subtitle: String = "", checked: Boolean, divider: Boolean = false) {
        titleText = title
        subtitleText = subtitle
        switchView.setChecked(checked, animated = false)
        needDivider = divider
        titleLayout = null
        subtitleLayout = null
        requestLayout()
    }

    fun isChecked(): Boolean = switchView.isChecked()

    fun setChecked(value: Boolean) {
        switchView.setChecked(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val textWidth = w - LayoutHelper.dp(16 + 16 + 50)

        if (titleLayout == null && textWidth > 0) {
            titleLayout = StaticLayout.Builder
                .obtain(titleText, 0, titleText.length, theme.settingsNamePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }
        if (subtitleText.isNotEmpty() && subtitleLayout == null && textWidth > 0) {
            subtitleLayout = StaticLayout.Builder
                .obtain(subtitleText, 0, subtitleText.length, theme.settingsValuePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        }

        var h = LayoutHelper.dp(16)
        titleLayout?.let { h += it.height }
        subtitleLayout?.let { h += it.height + LayoutHelper.dp(4) }
        h += LayoutHelper.dp(16)
        h = h.coerceAtLeast(LayoutHelper.dp(48))
        if (needDivider) h += 1

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val leftPad = LayoutHelper.dp(16).toFloat()
        var top = LayoutHelper.dp(16).toFloat()

        titleLayout?.let {
            canvas.save()
            canvas.translate(leftPad, top)
            it.draw(canvas)
            canvas.restore()
            top += it.height
        }

        subtitleLayout?.let {
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
