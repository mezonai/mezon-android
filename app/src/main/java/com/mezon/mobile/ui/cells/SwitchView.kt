package com.mezon.mobile.ui.cells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class SwitchView(context: Context, private val theme: ThemeColors) : View(context) {

    private var checked = false
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val trackRect = RectF()
    private val trackWidth = LayoutHelper.dpf(36f)
    private val trackHeight = LayoutHelper.dpf(20f)
    private val thumbRadius = LayoutHelper.dpf(8f)
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

    private fun toggle() {
        setChecked(!checked)
        onCheckedChange?.invoke(checked)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackWidth.toInt(), trackHeight.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val trackRadius = trackHeight / 2f
        trackRect.set(0f, 0f, trackWidth, trackHeight)

        val offColor = theme.outline
        val onColor = theme.primary
        val r = ((onColor shr 16) and 0xFF) * progress + ((offColor shr 16) and 0xFF) * (1 - progress)
        val g = ((onColor shr 8) and 0xFF) * progress + ((offColor shr 8) and 0xFF) * (1 - progress)
        val b = (onColor and 0xFF) * progress + (offColor and 0xFF) * (1 - progress)
        theme.switchTrackPaint.color = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, theme.switchTrackPaint)

        val thumbMargin = LayoutHelper.dpf(2f)
        val thumbMinX = thumbMargin + thumbRadius
        val thumbMaxX = trackWidth - thumbMargin - thumbRadius
        val thumbCx = thumbMinX + (thumbMaxX - thumbMinX) * progress
        val thumbCy = trackHeight / 2f
        canvas.drawCircle(thumbCx, thumbCy, thumbRadius, theme.switchThumbPaint)
    }
}
