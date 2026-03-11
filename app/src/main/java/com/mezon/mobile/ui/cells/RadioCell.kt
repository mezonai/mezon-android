package com.mezon.mobile.ui.cells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import com.mezon.mobile.core.BaseCell
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class RadioCell(context: Context, private val theme: ThemeColors) : BaseCell(context) {

    private var checked = false
    private var progress = 0f
    private val checkPath = Path()
    private val outerRadius = LayoutHelper.dpf(10f)
    private val innerRadius = LayoutHelper.dpf(5f)
    private var animator: ValueAnimator? = null

    init {
        minimumWidth = LayoutHelper.dp(22)
        minimumHeight = LayoutHelper.dp(22)
    }

    fun isChecked(): Boolean = checked

    fun setChecked(value: Boolean, animated: Boolean = true) {
        if (checked == value) return
        checked = value
        animator?.cancel()
        if (animated) {
            animator = ValueAnimator.ofFloat(progress, if (value) 1f else 0f).apply {
                duration = 200
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(LayoutHelper.dp(22), LayoutHelper.dp(22))
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.RadioButton"
        info.isCheckable = true
        info.isChecked = checked
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        theme.radioPaint.color = if (progress > 0f) theme.primary else theme.outline
        canvas.drawCircle(cx, cy, outerRadius, theme.radioPaint)

        if (progress > 0f) {
            theme.radioFillPaint.color = theme.primary
            canvas.drawCircle(cx, cy, innerRadius * progress, theme.radioFillPaint)

            theme.checkPaint.alpha = (255 * progress).toInt()
            checkPath.reset()
            checkPath.moveTo(cx - LayoutHelper.dpf(3f), cy)
            checkPath.lineTo(cx - LayoutHelper.dpf(0.5f), cy + LayoutHelper.dpf(2.5f))
            checkPath.lineTo(cx + LayoutHelper.dpf(3.5f), cy - LayoutHelper.dpf(2.5f))
            canvas.drawPath(checkPath, theme.checkPaint)
            theme.checkPaint.alpha = 255
        }
    }
}
