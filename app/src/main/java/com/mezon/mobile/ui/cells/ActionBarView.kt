package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ActionBarView(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    private val dividerPaint = Paint().apply { strokeWidth = 1f }

    val backButton: ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(R.drawable.ic_arrow_back)
        setColorFilter(theme.onSurface)
        background = rippleBackground()
        isClickable = true
        isFocusable = true
        contentDescription = "Back"
    }

    private val titleView: TextView = TextView(context).apply {
        setTextColor(theme.onSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        val barHeight = LayoutHelper.dp(56)
        minimumHeight = barHeight
        setBackgroundColor(theme.surface)

        addView(backButton, LayoutHelper.createFrame(48, 48, Gravity.START or Gravity.CENTER_VERTICAL, 4f, 0f, 0f, 0f))
        addView(titleView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL,
            64f, 0f, 16f, 0f
        ))

        setWillNotDraw(false)
    }

    fun setTitle(title: String) {
        titleView.text = title
    }

    fun setBackClickListener(listener: () -> Unit) {
        backButton.setOnClickListener { listener() }
    }

    fun applyTheme() {
        setBackgroundColor(theme.surface)
        titleView.setTextColor(theme.onSurface)
        backButton.setColorFilter(theme.onSurface)
        backButton.background = rippleBackground()
        dividerPaint.color = theme.outlineVariant
        invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.makeMeasureSpec(LayoutHelper.dp(56), MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        dividerPaint.color = theme.outlineVariant
        canvas.drawLine(0f, height.toFloat() - 1f, width.toFloat(), height.toFloat() - 1f, dividerPaint)
    }

    private fun rippleBackground(): android.graphics.drawable.Drawable {
        val typed = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typed, true)
        return if (typed.resourceId != 0) {
            context.getDrawable(typed.resourceId) ?: ColorDrawable(0)
        } else {
            ColorDrawable(0)
        }
    }
}
