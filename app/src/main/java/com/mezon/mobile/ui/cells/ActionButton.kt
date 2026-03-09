package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ActionButton(context: Context, private val theme: ThemeColors) : View(context) {

    private val rect = RectF()
    private val cornerRadius = LayoutHelper.dpf(12f)
    private var text = ""
    private var isPressed = false
    var isOutlined = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        minimumHeight = LayoutHelper.dp(48)
        isClickable = true
        isFocusable = true
    }

    fun setText(value: String) {
        text = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, LayoutHelper.dp(48))
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())

        if (isOutlined) {
            theme.buttonBgPaint.style = android.graphics.Paint.Style.STROKE
            theme.buttonBgPaint.strokeWidth = LayoutHelper.dp(1.5f).toFloat()
            theme.buttonBgPaint.color = theme.primary
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, theme.buttonBgPaint)
            theme.buttonBgPaint.style = android.graphics.Paint.Style.FILL
            theme.buttonTextPaint.color = theme.primary
        } else {
            theme.buttonBgPaint.style = android.graphics.Paint.Style.FILL
            theme.buttonBgPaint.color = if (isEnabled) theme.primary else theme.outline
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, theme.buttonBgPaint)
            theme.buttonTextPaint.color = theme.onPrimary
        }

        if (isPressed && isEnabled) {
            theme.buttonBgPaint.color = 0x1A000000
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, theme.buttonBgPaint)
        }

        val textY = height / 2f - (theme.buttonTextPaint.descent() + theme.buttonTextPaint.ascent()) / 2
        canvas.drawText(text, width / 2f, textY, theme.buttonTextPaint)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }
}
