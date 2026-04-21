package com.mezon.mobile.home.chat.input

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class InputSuggestionsPopup(
    context: Context,
    private val theme: ThemeColors
) : FrameLayout(context) {

    val recyclerView: RecyclerView
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()
    private val cornerRadius = LayoutHelper.dp(8f).toFloat()
    private val maxHeight = minOf(LayoutHelper.dp(220f), (AndroidUtilities.displaySize.y * 0.30f).toInt())
    private var showAnimator: ValueAnimator? = null

    init {
        bgPaint.color = theme.surface
        bgPaint.style = Paint.Style.FILL
        elevation = LayoutHelper.dp(4f).toFloat()
        visibility = GONE
        clipChildren = false

        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            val pad = LayoutHelper.dp(4f)
            setPadding(0, pad, 0, pad)
        }
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)
        super.dispatchDraw(canvas)
    }

    fun updateVisibility(show: Boolean) {
        if (show && visibility == VISIBLE) return
        if (!show && visibility == GONE) return

        showAnimator?.cancel()
        if (show) {
            visibility = VISIBLE
            alpha = 0f
            translationY = LayoutHelper.dp(8f).toFloat()
            showAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 150
                addUpdateListener {
                    val v = it.animatedValue as Float
                    alpha = v
                    translationY = LayoutHelper.dp(8f) * (1f - v)
                }
                start()
            }
        } else {
            showAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 100
                addUpdateListener {
                    val v = it.animatedValue as Float
                    alpha = v
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        visibility = GONE
                    }
                })
                start()
            }
        }
    }

    fun applyColors() {
        bgPaint.color = theme.surface
        invalidate()
    }
}
