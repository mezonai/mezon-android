package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ActionButton(context: Context, private val theme: ThemeColors) : View(context) {

    private val rect = RectF()
    private val cornerRadius = LayoutHelper.dpf(12f)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var text = ""
    private var isPressed = false
    var isOutlined = false
        set(value) {
            field = value
            invalidate()
        }
    var useGradient = false
        set(value) {
            field = value
            gradientShader = null
            invalidate()
        }
    var gradientStartColor = 0xFF501794.toInt()
    var gradientEndColor = 0xFF3E70A1.toInt()
    var disabledColor = 0xFF88888C.toInt()
    private var gradientShader: LinearGradient? = null
    private var lastWidth = 0

    init {
        minimumHeight = LayoutHelper.dp(50)
        isClickable = true
        isFocusable = true
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = enabled != isEnabled
        super.setEnabled(enabled)
        if (changed) {
            gradientShader = null
            invalidate()
        }
    }

    fun setText(value: String) {
        text = value
        contentDescription = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, LayoutHelper.dp(50))
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())

        bgPaint.reset()
        bgPaint.isAntiAlias = true

        if (isOutlined) {
            bgPaint.style = Paint.Style.STROKE
            bgPaint.strokeWidth = LayoutHelper.dp(1.5f).toFloat()
            bgPaint.color = theme.primary
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            bgPaint.style = Paint.Style.FILL
            theme.buttonTextPaint.color = theme.primary
        } else if (useGradient) {
            bgPaint.style = Paint.Style.FILL
            if (isEnabled) {
                if (gradientShader == null || lastWidth != width) {
                    gradientShader = LinearGradient(
                        0f, 0f, width.toFloat(), 0f,
                        gradientStartColor, gradientEndColor,
                        Shader.TileMode.CLAMP
                    )
                    lastWidth = width
                }
                bgPaint.shader = gradientShader
            } else {
                bgPaint.color = disabledColor
            }
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            theme.buttonTextPaint.color = 0xFFFFFFFF.toInt()
        } else {
            bgPaint.style = Paint.Style.FILL
            bgPaint.color = if (isEnabled) theme.primary else theme.outline
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            theme.buttonTextPaint.color = theme.onPrimary
        }

        if (isPressed && isEnabled) {
            bgPaint.shader = null
            bgPaint.color = 0x1A000000
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        }

        val textY = height / 2f - (theme.buttonTextPaint.descent() + theme.buttonTextPaint.ascent()) / 2
        canvas.drawText(text, width / 2f, textY, theme.buttonTextPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.text = text
        info.isEnabled = isEnabled
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
