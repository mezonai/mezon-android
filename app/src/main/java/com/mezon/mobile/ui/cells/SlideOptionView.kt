package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class SlideOptionView(context: Context, private val theme: ThemeColors) : HorizontalScrollView(context) {

    data class Option(
        val id: String,
        val label: String
    )

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val optionViews = ArrayList<OptionPill>()
    private var selectedIndex = 0
    var onOptionSelected: ((Int, Option) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        val pad = LayoutHelper.dp(12)
        setPadding(pad, LayoutHelper.dp(8), pad, LayoutHelper.dp(8))
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setOptions(options: List<Option>, selected: Int = 0) {
        container.removeAllViews()
        optionViews.clear()
        selectedIndex = selected

        options.forEachIndexed { index, option ->
            val pill = OptionPill(context, theme)
            pill.bind(option.label, index == selectedIndex)
            pill.setOnClickListener {
                if (selectedIndex != index) {
                    optionViews.getOrNull(selectedIndex)?.setOptionSelected(false)
                    selectedIndex = index
                    pill.setOptionSelected(true)
                    onOptionSelected?.invoke(index, option)
                }
            }
            val lp = LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, 36,
                leftMargin = if (index == 0) 0f else 8f
            )
            container.addView(pill, lp)
            optionViews.add(pill)
        }
    }

    fun getSelectedIndex(): Int = selectedIndex

    private class OptionPill(context: Context, private val theme: ThemeColors) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(14f)
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()
        private var label = ""
        private var isOptionSelected = false
        private val hPad = LayoutHelper.dp(16)
        private val cornerRadius = LayoutHelper.dpf(18f)

        init {
            isClickable = true
            isFocusable = true
        }

        fun bind(text: String, selected: Boolean) {
            label = text
            isOptionSelected = selected
            invalidate()
        }

        fun setOptionSelected(selected: Boolean) {
            isOptionSelected = selected
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val textW = textPaint.measureText(label).toInt()
            val w = textW + hPad * 2
            val h = LayoutHelper.dp(36)
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())

            if (isOptionSelected) {
                bgPaint.color = theme.primary
                textPaint.color = theme.onPrimary
            } else {
                bgPaint.color = theme.surfaceVariant
                textPaint.color = theme.onSurface
            }

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, width / 2f, textY, textPaint)
        }
    }
}
