package com.mezon.mobile.ui.cells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig

class NumberTextView(context: Context) : View(context) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var currentNumber = 0
    private var oldNumber = 0
    private var progress = 1f
    private var animator: ValueAnimator? = null
    private var textWidth = 0f
    private var textHeight = 0f
    private var centerNumber = false

    private var currentText = "0"
    private var oldText = "0"

    init {
        setWillNotDraw(false)
    }

    fun setTextColor(color: Int) {
        textPaint.color = color
        invalidate()
    }

    fun setTextSize(sizeDp: Int) {
        textPaint.textSize = LayoutHelper.dp(sizeDp).toFloat()
        val fm = textPaint.fontMetrics
        textHeight = fm.descent - fm.ascent
        updateTextWidth()
        requestLayout()
    }

    fun setTypeface(typeface: Typeface?) {
        textPaint.typeface = typeface
        updateTextWidth()
        requestLayout()
    }

    fun setCenterNumber(center: Boolean) {
        centerNumber = center
        invalidate()
    }

    fun getNumber(): Int = currentNumber

    fun getTextWidth(): Float = textWidth

    override fun invalidate() {
        super.invalidate()
        if (animator != null && animator!!.isRunning) {
            (parent as? View)?.invalidate()
        }
    }

    fun setNumber(number: Int, animated: Boolean) {
        if (currentNumber == number) return
        oldNumber = currentNumber
        currentNumber = number
        oldText = currentText
        currentText = number.toString()
        updateTextWidth()

        if (animated && SharedConfig.animationsEnabled()) {
            progress = 0f
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            progress = 1f
            invalidate()
        }
    }

    private fun updateTextWidth() {
        textWidth = textPaint.measureText(currentText)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = Math.ceil(textPaint.measureText("0").toDouble()).toInt() * 6 + paddingLeft + paddingRight
        val h = Math.ceil(textHeight.toDouble()).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (currentText.isEmpty()) return

        val cy = measuredHeight / 2f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        val contentWidth = measuredWidth - paddingLeft - paddingRight
        val offsetX = if (centerNumber) {
            paddingLeft + (contentWidth - textWidth) / 2f
        } else {
            paddingLeft.toFloat()
        }

        if (progress >= 1f) {
            canvas.drawText(currentText, offsetX, baseline, textPaint)
            return
        }

        val goingUp = currentNumber > oldNumber
        val slideDistance = textHeight * 0.6f

        val oldAlpha = textPaint.alpha
        val maxLen = maxOf(currentText.length, oldText.length)
        var startDiff = -1

        for (i in 0 until maxLen) {
            val cc = currentText.getOrNull(i)
            val oc = oldText.getOrNull(i)
            if (cc != oc && startDiff == -1) {
                startDiff = i
            }
        }
        if (startDiff == -1) startDiff = 0

        val staticPart = currentText.substring(0, startDiff)
        var animOffsetX = offsetX

        if (staticPart.isNotEmpty()) {
            canvas.drawText(staticPart, animOffsetX, baseline, textPaint)
            animOffsetX += textPaint.measureText(staticPart)
        }

        val animOld = oldText.substring(startDiff.coerceAtMost(oldText.length))
        val animNew = currentText.substring(startDiff.coerceAtMost(currentText.length))

        canvas.save()
        canvas.clipRect(animOffsetX, 0f, measuredWidth.toFloat(), measuredHeight.toFloat())
        canvas.translate(animOffsetX, 0f)

        val direction = if (goingUp) -1f else 1f

        textPaint.alpha = ((1f - progress) * oldAlpha).toInt()
        val oldY = baseline + direction * slideDistance * progress
        if (animOld.isNotEmpty()) {
            canvas.drawText(animOld, 0f, oldY, textPaint)
        }

        textPaint.alpha = (progress * oldAlpha).toInt()
        val newY = baseline - direction * slideDistance * (1f - progress)
        if (animNew.isNotEmpty()) {
            canvas.drawText(animNew, 0f, newY, textPaint)
        }

        textPaint.alpha = oldAlpha
        canvas.restore()
    }

    override fun hasOverlappingRendering(): Boolean = false
}
