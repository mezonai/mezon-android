package com.mezon.mobile.ui.cells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.theme.MezonIconColors

class FloatingTabBar(context: Context, private val themeColors: ThemeColors) : View(context) {

    private data class Tab(val iconResId: Int)

    interface OnTabSelectedListener {
        fun onTabSelected(index: Int)
    }

    private val tabs = listOf(
        Tab(R.drawable.mz_clans_24),
        Tab(R.drawable.mz_messages_24),
        Tab(R.drawable.mz_bell_24),
        Tab(R.drawable.mz_user_24)
    )

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgRect = RectF()
    private val indicatorRect = RectF()

    private var selectedIndex = 0
    private var animatedSelectedIndex = 0f
    private var selectionAnimator: ValueAnimator? = null

    var onTabSelected: OnTabSelectedListener? = null

    private val icons = tabs.map { tab ->
        MezonIcon.loadDrawable(context, tab.iconResId)
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

        val (normalIconColor, selectedIconColor) = if (themeColors.resolvedMode.isLight) {
            MezonIconColors.LIGHT
        } else {
            MezonIconColors.DARK
        }
        for (i in tabs.indices) {
            val centerX = (tabW * i + tabW / 2f).toInt()
            val centerY = (h / 2f).toInt()
            val icon = icons[i]
            icon.setTint(if (i == selectedIndex) selectedIconColor else normalIconColor)
            icon.drawCenteredIcon(canvas, centerX, centerY)
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
