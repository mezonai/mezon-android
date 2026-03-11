package com.mezon.mobile.ui.cells

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.ThemeColors

class BottomTabBar(context: Context, private val themeColors: ThemeColors) : View(context) {

    data class Tab(val iconResId: Int, val labelResId: Int)

    interface OnTabSelectedListener {
        fun onTabSelected(index: Int)
    }

    private val tabs = listOf(
        Tab(R.drawable.ic_clans, R.string.clan_title),
        Tab(R.drawable.ic_messages, R.string.screen_tab_messages),
        Tab(R.drawable.ic_notifications, R.string.screen_tab_notifications),
        Tab(R.drawable.ic_profile, R.string.screen_tab_profile)
    )

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.dp(10).toFloat()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var selectedIndex = 0
    var onTabSelected: OnTabSelectedListener? = null
    var badgeCounts = IntArray(tabs.size)
        private set

    private val icons: List<Drawable?> = tabs.map { tab ->
        ContextCompat.getDrawable(context, tab.iconResId)?.mutate()
    }

    private val labels: List<String> = tabs.map { tab ->
        context.getString(tab.labelResId)
    }

    private val hideAnimDuration = 200L
    private var isHidden = false

    private val iconSize = LayoutHelper.dp(24)
    private val iconLabelGap = LayoutHelper.dp(4)
    private val barHeight = LayoutHelper.dp(56)

    init {
        setWillNotDraw(false)
    }

    fun selectTab(index: Int, animate: Boolean = true) {
        if (index == selectedIndex) return
        selectedIndex = index
        invalidate()
    }

    fun getSelectedIndex(): Int = selectedIndex

    fun setBadgeCount(tabIndex: Int, count: Int) {
        if (tabIndex in badgeCounts.indices) {
            badgeCounts[tabIndex] = count
            invalidate()
        }
    }

    fun hideTabBar() {
        if (isHidden) return
        isHidden = true
        val target = height.toFloat()
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

    fun showTabBar(animated: Boolean = true) {
        if (!isHidden) return
        isHidden = false
        if (animated && SharedConfig.animationsEnabled()) {
            animate()
                .translationY(0f)
                .setDuration(hideAnimDuration)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            animate().cancel()
            translationY = 0f
        }
    }

    fun applyTheme() {
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(barHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(themeColors.surface)

        borderPaint.color = themeColors.outlineVariant
        canvas.drawLine(0f, 0f, w, 0f, borderPaint)

        val tabW = w / tabs.size
        val totalContentH = iconSize + iconLabelGap + labelPaint.textSize
        val topOffset = (h - totalContentH) / 2f

        for (i in tabs.indices) {
            val isSelected = i == selectedIndex
            val centerX = tabW * i + tabW / 2f

            val tintColor = if (isSelected) themeColors.primary else themeColors.onSurfaceVariant
            val alpha = if (isSelected) 255 else 140

            val drawable = icons[i]
            if (drawable != null) {
                val iconLeft = (centerX - iconSize / 2f).toInt()
                val iconTop = topOffset.toInt()
                drawable.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                drawable.setTint(tintColor)
                drawable.alpha = alpha
                drawable.draw(canvas)
            }

            labelPaint.color = tintColor
            labelPaint.alpha = alpha
            val labelY = topOffset + iconSize + iconLabelGap + labelPaint.textSize
            canvas.drawText(labels[i], centerX, labelY, labelPaint)
        }
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
