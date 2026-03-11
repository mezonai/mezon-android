package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

open class EditTextBoldCursor(context: Context) : EditText(context) {

    private var cursorDrawable: Drawable? = null
    private var cursorWidth = LayoutHelper.dp(2).toFloat()
    private var cursorSize = LayoutHelper.dp(24)
    private var cursorColor = ThemeColors.instance.primary
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cursorVisible = true
    private var lastCursorBlink = 0L
    private var cursorBlinkOn = true
    private val BLINK_INTERVAL = 500L

    private var hintText: CharSequence? = null
    private var hintLayout: StaticLayout? = null
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var hintColor = 0
    private var headerHintColor = 0
    private var hintVisible = true

    private var errorLineColor = 0
    private var hasError = false
    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(1).toFloat()
    }

    private var lineColor = 0
    private var activeLineColor = 0
    private var lineVisible = false
    private var drawLine = false
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(1).toFloat()
    }
    private val activeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(1).toFloat()
    }

    private var fixedSize = 0
    private var transformHintToHeader = false
    private var windowView: View? = null
    private var ignoreTopCount = 0
    private var ignoreBottomCount = 0

    init {
        setWillNotDraw(false)
        background = null
        isCursorVisible = false
        updateCursorColor()
    }

    fun setCursorColor(color: Int) {
        cursorColor = color
        updateCursorColor()
        invalidate()
    }

    fun setCursorWidth(width: Float) {
        cursorWidth = LayoutHelper.dp(width.toInt()).toFloat()
        invalidate()
    }

    fun setCursorSize(value: Int) {
        cursorSize = value
    }

    override fun setCursorVisible(visible: Boolean) {
        cursorVisible = visible
        invalidate()
    }

    fun setHintText(hint: CharSequence?) {
        hintText = hint
        hintLayout = null
        requestLayout()
        invalidate()
    }

    fun getHintText(): CharSequence? = hintText

    fun setHintColor(color: Int) {
        hintColor = color
        hintPaint.color = color
        invalidate()
    }

    fun setHeaderHintColor(color: Int) {
        headerHintColor = color
        invalidate()
    }

    fun setHintVisible(value: Boolean, animated: Boolean) {
        if (hintVisible == value) return
        hintVisible = value
        invalidate()
    }

    fun setErrorLineColor(color: Int) {
        errorLineColor = color
        errorPaint.color = color
    }

    fun setHasError(error: Boolean) {
        if (hasError != error) {
            hasError = error
            invalidate()
        }
    }

    fun setLineColors(idle: Int, active: Int, error: Int) {
        lineColor = idle
        activeLineColor = active
        errorLineColor = error
        linePaint.color = idle
        activeLinePaint.color = active
        errorPaint.color = error
        lineVisible = true
        drawLine = true
        invalidate()
    }

    fun setLineVisible(visible: Boolean) {
        lineVisible = visible
        drawLine = visible
        invalidate()
    }

    fun setFixedSize(size: Int) {
        fixedSize = size
    }

    fun setTextWatcherLambda(callback: (CharSequence?) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                callback(s)
            }
        })
    }

    fun setTransformHintToHeader(value: Boolean) {
        transformHintToHeader = value
    }

    fun setWindowView(view: View?) {
        windowView = view
    }

    private fun updateCursorColor() {
        cursorPaint.color = cursorColor
        cursorDrawable = GradientDrawable().apply {
            setColor(cursorColor)
            setSize(cursorWidth.toInt(), 1)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fixedSize > 0) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(fixedSize, MeasureSpec.EXACTLY))
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        buildHintLayout()
    }

    private fun buildHintLayout() {
        val hintStr = hintText ?: return
        val width = measuredWidth - paddingLeft - paddingRight
        if (width <= 0) return
        hintPaint.textSize = textSize
        hintPaint.typeface = typeface
        if (hintColor != 0) hintPaint.color = hintColor
        hintLayout = StaticLayout.Builder.obtain(hintStr, 0, hintStr.length, hintPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(1)
            .setIncludePad(false)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        val isEmpty = text.isNullOrEmpty()

        if (isEmpty && hintLayout != null && hintVisible) {
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            hintLayout?.draw(canvas)
            canvas.restore()
        }

        super.onDraw(canvas)

        if (drawLine && lineVisible) {
            val p = when {
                hasError -> errorPaint
                isFocused -> activeLinePaint
                else -> linePaint
            }
            val y = measuredHeight - p.strokeWidth / 2
            canvas.drawLine(paddingLeft.toFloat(), y, (measuredWidth - paddingRight).toFloat(), y, p)
        }

        if (cursorVisible && isFocused && !isEmpty()) {
            drawCursor(canvas)
        }
    }

    private fun isEmpty(): Boolean = text.isNullOrEmpty()

    private fun drawCursor(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCursorBlink >= BLINK_INTERVAL) {
            cursorBlinkOn = !cursorBlinkOn
            lastCursorBlink = now
        }
        if (!cursorBlinkOn) {
            postInvalidateDelayed(BLINK_INTERVAL)
            return
        }

        val layout = layout ?: return
        val selStart = selectionStart
        if (selStart < 0) return

        val line = layout.getLineForOffset(selStart)
        val x = layout.getPrimaryHorizontal(selStart) + paddingLeft
        val top = layout.getLineTop(line) + paddingTop
        val bottom = layout.getLineBottom(line) + paddingTop

        canvas.drawRect(x, top.toFloat(), x + cursorWidth, bottom.toFloat(), cursorPaint)
        postInvalidateDelayed(BLINK_INTERVAL)
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.EditText"
        hintText?.let { info.hintText = it }
    }
}
