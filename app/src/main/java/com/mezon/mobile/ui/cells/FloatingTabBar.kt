package com.mezon.mobile.ui.cells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.ThemeColors

class FloatingTabBar(context: Context, private val themeColors: ThemeColors) : View(context) {

    data class Tab(val iconResId: Int, val iconSelectedResId: Int)

    interface OnTabSelectedListener {
        fun onTabSelected(index: Int)
    }

    private val tabs = listOf(
        Tab(R.drawable.ic_clans, R.drawable.ic_clans),
        Tab(R.drawable.ic_messages, R.drawable.ic_messages),
        Tab(R.drawable.ic_notifications, R.drawable.ic_notifications),
        Tab(R.drawable.ic_profile, R.drawable.ic_profile)
    )

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()
    private val indicatorRect = RectF()

    private var selectedIndex = 0
    private var animatedSelectedIndex = 0f
    private var selectionAnimator: ValueAnimator? = null

    var onTabSelected: OnTabSelectedListener? = null

    private val icons: List<Drawable?> = tabs.map { tab ->
        ContextCompat.getDrawable(context, tab.iconResId)?.mutate()
    }

    private val hideAnimDuration = 250L
    private var isHidden = false

    init {
        setWillNotDraw(false)
    }

    fun selectTab(index: Int, animate: Boolean = true) {
        if (index == selectedIndex) return
        val from = selectedIndex.toFloat()
        val to = index.toFloat()
        selectedIndex = index

        selectionAnimator?.cancel()
        if (animate && SharedConfig.animationsEnabled()) {
            selectionAnimator = ValueAnimator.ofFloat(from, to).apply {
                duration = 200
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener {
                    animatedSelectedIndex = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animatedSelectedIndex = to
            invalidate()
        }
    }

    fun getSelectedIndex(): Int = selectedIndex

    fun hideTabBar() {
        if (isHidden) return
        isHidden = true
        val target = height.toFloat() + LayoutHelper.dp(24)
        if (SharedConfig.animationsEnabled()) {
            animate()
                .translationY(target)
                .setDuration(hideAnimDuration)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            translationY = target
        }
    }

    fun showTabBar() {
        if (!isHidden) return
        isHidden = false
        if (SharedConfig.animationsEnabled()) {
            animate()
                .translationY(0f)
                .setDuration(hideAnimDuration)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            translationY = 0f
        }
    }

    fun applyTheme() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        bgPaint.color = themeColors.surfaceVariant
        val bgRadius = h / 2f
        bgRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, bgRadius, bgRadius, bgPaint)

        val tabW = w / tabs.size
        val indicatorPad = LayoutHelper.dp(6).toFloat()
        val indicatorLeft = animatedSelectedIndex * tabW + indicatorPad
        val indicatorTop = indicatorPad
        val indicatorRight = indicatorLeft + tabW - indicatorPad * 2
        val indicatorBottom = h - indicatorPad
        indicatorPaint.color = themeColors.primary and 0x00FFFFFF or 0x26000000
        indicatorRect.set(indicatorLeft, indicatorTop, indicatorRight, indicatorBottom)
        val indicatorRadius = (indicatorBottom - indicatorTop) / 2f
        canvas.drawRoundRect(indicatorRect, indicatorRadius, indicatorRadius, indicatorPaint)

        val iconSize = LayoutHelper.dp(24)
        for (i in tabs.indices) {
            val drawable = icons[i] ?: continue
            val centerX = (tabW * i + tabW / 2f).toInt()
            val centerY = (h / 2f).toInt()
            val left = centerX - iconSize / 2
            val top = centerY - iconSize / 2
            drawable.setBounds(left, top, left + iconSize, top + iconSize)

            val isSelected = i == selectedIndex
            val alpha = if (isSelected) 255 else 153
            val color = if (isSelected) themeColors.primary else themeColors.onSurfaceVariant
            drawable.setTint(color)
            drawable.alpha = alpha
            drawable.draw(canvas)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = LayoutHelper.dp(56)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(desiredH, MeasureSpec.EXACTLY))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val tabW = width.toFloat() / tabs.size
                val index = (event.x / tabW).toInt().coerceIn(0, tabs.size - 1)
                if (index != selectedIndex) {
                    selectTab(index)
                    onTabSelected?.onTabSelected(index)
                }
                return true
            }
        }
        return true
    }
}
