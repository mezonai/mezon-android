package com.mezon.mobile.ui.cells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ToggleView(
    context: Context,
    private val theme: ThemeColors,
    private val widthDp: Int = 52,
    private val heightDp: Int = 28
) : View(context) {

    private var checked = false
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    fun isChecked(): Boolean = checked

    fun setChecked(value: Boolean, animated: Boolean = true) {
        if (checked == value) return
        checked = value
        animator?.cancel()
        if (animated) {
            animator = ValueAnimator.ofFloat(progress, if (value) 1f else 0f).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            progress = if (value) 1f else 0f
            invalidate()
        }
    }

    private fun toggle() {
        setChecked(!checked)
        onCheckedChange?.invoke(checked)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(LayoutHelper.dp(widthDp), LayoutHelper.dp(heightDp))
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checked
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val trackRadius = h / 2f

        trackRect.set(0f, 0f, w, h)

        val onColor = theme.primary
        val offColor = theme.outline
        val r = lerpColor(offColor, onColor, progress)
        trackPaint.color = r
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        val padding = LayoutHelper.dpf(3f)
        val thumbRadius = (h - padding * 2) / 2f
        val thumbMinX = padding + thumbRadius
        val thumbMaxX = w - padding - thumbRadius
        val thumbCx = thumbMinX + (thumbMaxX - thumbMinX) * progress
        thumbPaint.color = android.graphics.Color.WHITE
        canvas.drawCircle(thumbCx, h / 2f, thumbRadius, thumbPaint)
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ra = ((a shr 16) and 0xFF) * (1 - t) + ((b shr 16) and 0xFF) * t
        val ga = ((a shr 8) and 0xFF) * (1 - t) + ((b shr 8) and 0xFF) * t
        val ba = (a and 0xFF) * (1 - t) + (b and 0xFF) * t
        return (0xFF shl 24) or (ra.toInt() shl 16) or (ga.toInt() shl 8) or ba.toInt()
    }
}
