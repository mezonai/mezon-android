package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import kotlin.math.ceil
import android.view.accessibility.AccessibilityNodeInfo
import com.mezon.mobile.core.LayoutHelper

class SimpleTextView(context: Context) : View(context) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var textLayout: StaticLayout? = null
    private var text: CharSequence = ""
    private var currentGravity = Gravity.START or Gravity.TOP
    private var maxLines = 1
    private var textWidth = 0
    private var textHeight = 0
    private var textOffsetX = 0f
    private var textOffsetY = 0f
    private var wasLayout = false
    private var buildLayoutOnMeasure = false

    private var leftDrawable: Drawable? = null
    private var rightDrawable: Drawable? = null
    private var drawablePadding = LayoutHelper.dp(4)
    private var leftDrawableTopPadding = 0
    private var rightDrawableTopPadding = 0

    private var scrollNonFitText = false
    private var ellipsizeByGradient = false
    private var rightDrawableOnClick: OnClickListener? = null
    var rightDrawableOutside = false
    private var rightPadding = 0

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setTextColor(color: Int) {
        textPaint.color = color
        invalidate()
    }

    fun setTextSize(sizePx: Int) {
        if (textPaint.textSize != sizePx.toFloat()) {
            textPaint.textSize = sizePx.toFloat()
            if (wasLayout) buildLayout(measuredWidth)
            requestLayout()
        }
    }

    fun setTypeface(typeface: Typeface?) {
        textPaint.typeface = typeface
        if (wasLayout) buildLayout(measuredWidth)
        requestLayout()
    }

    fun setGravity(gravity: Int) {
        currentGravity = gravity
        invalidate()
    }

    fun setMaxLines(lines: Int) {
        maxLines = lines.coerceAtLeast(1)
        if (wasLayout) buildLayout(measuredWidth)
        requestLayout()
    }

    fun setText(value: CharSequence?) {
        setText(value, false)
    }

    fun setText(value: CharSequence?, animated: Boolean) {
        val newText = value ?: ""
        if (!animated && text == newText) return
        text = newText
        if (wasLayout) buildLayout(measuredWidth)
        requestLayout()
    }

    fun getText(): CharSequence = text

    fun getTextWidth(): Float = textWidth.toFloat()

    fun getTextHeight(): Float = textHeight.toFloat()

    fun setLeftDrawable(drawable: Drawable?) {
        leftDrawable = drawable
        if (wasLayout) buildLayout(measuredWidth)
        requestLayout()
    }

    fun setRightDrawable(drawable: Drawable?) {
        rightDrawable = drawable
        if (wasLayout) buildLayout(measuredWidth)
        requestLayout()
    }

    fun setDrawablePadding(value: Int) {
        if (drawablePadding == value) return
        drawablePadding = value
        if (wasLayout) buildLayout(measuredWidth)
        requestLayout()
    }

    fun setLeftDrawable(resId: Int) {
        setLeftDrawable(if (resId == 0) null else context.resources.getDrawable(resId, null))
    }

    fun setRightDrawable(resId: Int) {
        setRightDrawable(if (resId == 0) null else context.resources.getDrawable(resId, null))
    }

    fun setScrollNonFitText(value: Boolean) {
        if (scrollNonFitText == value) return
        scrollNonFitText = value
        requestLayout()
    }

    fun setEllipsizeByGradient(value: Boolean) {
        if (ellipsizeByGradient == value) return
        ellipsizeByGradient = value
        invalidate()
    }

    fun getBuildLayoutWidth(width: Float): Int {
        if (width <= 0 || text.isEmpty()) return 0
        var availableWidth = width.toInt() - rightPadding
        leftDrawable?.let { availableWidth -= it.intrinsicWidth + drawablePadding }
        rightDrawable?.let { availableWidth -= it.intrinsicWidth + drawablePadding }
        availableWidth = availableWidth.coerceAtLeast(1)
        val ellipsized = if (maxLines == 1) {
            TextUtils.ellipsize(text, textPaint, availableWidth.toFloat(), TextUtils.TruncateAt.END)
        } else text
        val alignment = when {
            currentGravity and Gravity.CENTER_HORIZONTAL != 0 -> Layout.Alignment.ALIGN_CENTER
            currentGravity and Gravity.END != 0 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = StaticLayout.Builder.obtain(ellipsized, 0, ellipsized.length, textPaint, availableWidth)
            .setAlignment(alignment)
            .setMaxLines(maxLines)
            .setIncludePad(false)
            .build()
        var w = 0
        for (i in 0 until layout.lineCount) {
            w = w.coerceAtLeast(ceil(layout.getLineWidth(i)).toInt())
        }
        return w
    }

    fun setRightPadding(padding: Int) {
        if (rightPadding == padding) return
        rightPadding = padding
        if (wasLayout) buildLayout(measuredWidth - paddingLeft - paddingRight)
        invalidate()
    }

    fun setRightDrawableOnClick(listener: OnClickListener?) {
        rightDrawableOnClick = listener
    }

    fun getPaint(): TextPaint = textPaint

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        buildLayout(width - paddingLeft - paddingRight)
        val desiredHeight = textHeight + paddingTop + paddingBottom
        val resolvedHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight
        }
        setMeasuredDimension(width, resolvedHeight)
    }

    private fun buildLayout(maxWidth: Int) {
        wasLayout = true
        if (maxWidth <= 0 || text.isEmpty()) {
            textLayout = null
            textWidth = 0
            textHeight = 0
            return
        }

        var availableWidth = maxWidth - rightPadding
        leftDrawable?.let { d ->
            availableWidth -= d.intrinsicWidth + drawablePadding
        }
        rightDrawable?.let { d ->
            availableWidth -= d.intrinsicWidth + drawablePadding
        }
        availableWidth = availableWidth.coerceAtLeast(1)

        val ellipsized = if (maxLines == 1) {
            TextUtils.ellipsize(text, textPaint, availableWidth.toFloat(), TextUtils.TruncateAt.END)
        } else text

        val alignment = when {
            currentGravity and Gravity.CENTER_HORIZONTAL != 0 -> Layout.Alignment.ALIGN_CENTER
            currentGravity and Gravity.END != 0 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }

        textLayout = StaticLayout.Builder.obtain(ellipsized, 0, ellipsized.length, textPaint, availableWidth)
            .setAlignment(alignment)
            .setMaxLines(maxLines)
            .setIncludePad(false)
            .setEllipsize(if (maxLines > 1) TextUtils.TruncateAt.END else null)
            .build()

        textLayout?.let { layout ->
            textWidth = 0
            for (i in 0 until layout.lineCount) {
                textWidth = textWidth.coerceAtLeast(Math.ceil(layout.getLineWidth(i).toDouble()).toInt())
            }
            textHeight = layout.height
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        calcTextOffsets()
    }

    private fun calcTextOffsets() {
        val layout = textLayout ?: return
        val contentHeight = layout.height
        val viewHeight = measuredHeight - paddingTop - paddingBottom

        textOffsetY = when {
            currentGravity and Gravity.CENTER_VERTICAL != 0 -> paddingTop + (viewHeight - contentHeight) / 2f
            currentGravity and Gravity.BOTTOM != 0 -> paddingTop + viewHeight - contentHeight.toFloat()
            else -> paddingTop.toFloat()
        }

        val leftOccupied = leftDrawable?.let { it.intrinsicWidth + drawablePadding } ?: 0
        textOffsetX = paddingLeft.toFloat() + leftOccupied
    }

    override fun onDraw(canvas: Canvas) {
        val layout = textLayout ?: return

        val cy = measuredHeight / 2f

        leftDrawable?.let { d ->
            val dw = d.intrinsicWidth
            val dh = d.intrinsicHeight
            val dx = paddingLeft
            val dy = (cy - dh / 2f + leftDrawableTopPadding).toInt()
            d.setBounds(dx, dy, dx + dw, dy + dh)
            d.draw(canvas)
        }

        canvas.save()
        canvas.translate(textOffsetX, textOffsetY)
        layout.draw(canvas)
        canvas.restore()

        rightDrawable?.let { d ->
            val dw = d.intrinsicWidth
            val dh = d.intrinsicHeight
            val dx = measuredWidth - paddingRight - rightPadding - dw
            val dy = (cy - dh / 2f + rightDrawableTopPadding).toInt()
            d.setBounds(dx, dy, dx + dw, dy + dh)
            d.draw(canvas)
        }
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.TextView"
        info.text = text
    }
}
