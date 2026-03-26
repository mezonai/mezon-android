package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
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

    private var wantShow = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        applyColors()
    }

    fun applyColors() {
        bgPaint.color = theme.textDisabled
        arrowPaint.color = android.graphics.Color.WHITE
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

    fun show(visible: Boolean) {
        if (wantShow == visible) return
        wantShow = visible
        invalidate()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!wantShow) return false
        return super.onTouchEvent(event)
    }

    fun isButtonVisible(): Boolean = wantShow

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalSize = buttonSize + LayoutHelper.dp(12f)
        setMeasuredDimension(totalSize, totalSize)
    }

    override fun onDraw(canvas: Canvas) {
        if (!wantShow) return
        val cx = measuredWidth / 2f
        val btnRadius = buttonSize / 2f
        val btnCy = measuredHeight - btnRadius - shadowRadius

        canvas.drawCircle(cx, btnCy, btnRadius, bgPaint)

        val arrowSize = LayoutHelper.dp(8f).toFloat()
        arrowPath.reset()
        arrowPath.moveTo(cx - arrowSize, btnCy - arrowSize * 0.5f)
        arrowPath.lineTo(cx, btnCy + arrowSize * 0.5f)
        arrowPath.lineTo(cx + arrowSize, btnCy - arrowSize * 0.5f)
        canvas.drawPath(arrowPath, arrowPaint)

        if (badgeCount > 0) {
            val badgeH = LayoutHelper.dp(18f).toFloat()
            val textWidth = badgeTextPaint.measureText(badgeText)
            val padH = LayoutHelper.dp(5f).toFloat()
            val badgeW = (textWidth + padH * 2).coerceAtLeast(badgeH)
            val badgeRadius = badgeH / 2f

            val badgeRight = cx + buttonSize / 2f + LayoutHelper.dp(2f)
            val badgeLeft = badgeRight - badgeW
            val badgeTop = btnCy - buttonSize / 2f - LayoutHelper.dp(4f)
            badgeRect.set(badgeLeft, badgeTop, badgeRight, badgeTop + badgeH)
            canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint)

            val textY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(badgeText, badgeRect.centerX(), textY, badgeTextPaint)
        }
    }
}
