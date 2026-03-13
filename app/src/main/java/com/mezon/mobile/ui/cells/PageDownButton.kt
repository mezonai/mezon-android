package com.mezon.mobile.ui.cells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class PageDownButton(context: Context, private val theme: ThemeColors) : View(context) {

    private val buttonSize = LayoutHelper.dp(44f)
    private val shadowRadius = LayoutHelper.dp(4f).toFloat()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(shadowRadius, 0f, LayoutHelper.dp(2f).toFloat(), 0x30000000)
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dp(2.2f).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(10f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.WHITE
    }

    private val arrowPath = Path()
    private val badgeRect = RectF()
    private var badgeCount = 0
    private var badgeText = ""

    private var showFraction = 0f
    private var showAnimator: ValueAnimator? = null
    private var wantShow = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        applyColors()
    }

    fun applyColors() {
        bgPaint.color = theme.surface
        arrowPaint.color = theme.onSurfaceVariant
        badgeBgPaint.color = theme.badgeRed
        invalidate()
    }

    fun setUnreadCount(count: Int) {
        if (badgeCount == count) return
        badgeCount = count
        badgeText = when {
            count <= 0 -> ""
            count > 99 -> "99+"
            else -> count.toString()
        }
        invalidate()
    }

    fun show(visible: Boolean, animated: Boolean = true) {
        if (wantShow == visible) return
        wantShow = visible
        showAnimator?.cancel()
        if (!animated) {
            showFraction = if (visible) 1f else 0f
            visibility = if (visible) VISIBLE else GONE
            invalidate()
            return
        }
        val from = showFraction
        val to = if (visible) 1f else 0f
        visibility = VISIBLE
        showAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                showFraction = it.animatedValue as Float
                scaleX = showFraction
                scaleY = showFraction
                alpha = showFraction
                if (showFraction == 0f && !wantShow) {
                    visibility = GONE
                }
            }
            start()
        }
    }

    fun isButtonVisible(): Boolean = wantShow

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalSize = buttonSize + LayoutHelper.dp(20f)
        setMeasuredDimension(totalSize, totalSize)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = measuredWidth / 2f
        val btnRadius = buttonSize / 2f
        val btnCy = measuredHeight - btnRadius - shadowRadius

        canvas.drawCircle(cx, btnCy, btnRadius, bgPaint)

        val arrowSize = LayoutHelper.dp(8f).toFloat()
        arrowPath.reset()
        arrowPath.moveTo(cx - arrowSize, btnCy - arrowSize * 0.35f)
        arrowPath.lineTo(cx, btnCy + arrowSize * 0.35f)
        arrowPath.lineTo(cx + arrowSize, btnCy - arrowSize * 0.35f)
        canvas.drawPath(arrowPath, arrowPaint)

        if (badgeCount > 0) {
            val badgeH = LayoutHelper.dp(18f).toFloat()
            val textWidth = badgeTextPaint.measureText(badgeText)
            val padH = LayoutHelper.dp(5f).toFloat()
            val badgeW = (textWidth + padH * 2).coerceAtLeast(badgeH)
            val badgeRadius = badgeH / 2f

            val badgeLeft = cx - badgeW / 2f
            val badgeTop = 0f
            badgeRect.set(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH)
            canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint)

            val textY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(badgeText, badgeRect.centerX(), textY, badgeTextPaint)
        }
    }
}
