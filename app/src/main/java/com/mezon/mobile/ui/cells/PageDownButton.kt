package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class PageDownButton(context: Context, private val theme: ThemeColors) : View(context) {

    private val buttonSize = LayoutHelper.dp(44f)
    private val shadowRadius = LayoutHelper.dp(4f).toFloat()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(shadowRadius, 0f, LayoutHelper.dp(2f).toFloat(), 0x30000000)
    }

    private val arrow = MezonIcon.loadDrawable(context, R.drawable.mz_chevron_down_20, theme.iconTint)

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.sp(10f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.WHITE
    }

    private val badgeRect = RectF()
    private var badgeCount = 0
    private var badgeText = ""

    companion object {
        private val MEASURE_PAD = LayoutHelper.dp(12f)
        private val BADGE_HEIGHT = LayoutHelper.dp(18f).toFloat()
        private val BADGE_PAD_H = LayoutHelper.dp(5f).toFloat()
        private val BADGE_OFFSET_RIGHT = LayoutHelper.dp(2f).toFloat()
        private val BADGE_OFFSET_TOP = LayoutHelper.dp(4f).toFloat()
    }

    private var wantShow = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        applyColors()
    }

    fun applyColors() {
        bgPaint.color = theme.textDisabled
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
        visibility = if (visible) VISIBLE else GONE
        invalidate()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!wantShow) return false
        return super.onTouchEvent(event)
    }

    fun isButtonVisible(): Boolean = wantShow

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalSize = buttonSize + MEASURE_PAD
        setMeasuredDimension(totalSize, totalSize)
    }

    override fun onDraw(canvas: Canvas) {
        if (!wantShow) return
        val cx = measuredWidth / 2f
        val btnRadius = buttonSize / 2f
        val btnCy = measuredHeight - btnRadius - shadowRadius

        canvas.drawCircle(cx, btnCy, btnRadius, bgPaint)

        arrow.drawCenteredIcon(canvas, cx.toInt(), btnCy.toInt())

        if (badgeCount > 0) {
            val textWidth = badgeTextPaint.measureText(badgeText)
            val badgeW = (textWidth + BADGE_PAD_H * 2).coerceAtLeast(BADGE_HEIGHT)
            val badgeRadius = BADGE_HEIGHT / 2f

            val badgeRight = cx + buttonSize / 2f + BADGE_OFFSET_RIGHT
            val badgeLeft = badgeRight - badgeW
            val badgeTop = btnCy - buttonSize / 2f - BADGE_OFFSET_TOP
            badgeRect.set(badgeLeft, badgeTop, badgeRight, badgeTop + BADGE_HEIGHT)
            canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint)

            val textY = badgeRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2
            canvas.drawText(badgeText, badgeRect.centerX(), textY, badgeTextPaint)
        }
    }
}
