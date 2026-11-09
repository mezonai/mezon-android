package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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

    data class Tab(val iconResId: Int, val detailIconResId: Int, val labelResId: Int)

    interface OnTabSelectedListener {
        fun onTabSelected(index: Int)
    }

    private val tabs = listOf(
        Tab(R.drawable.ic_clans, R.drawable.ic_clans_detail, R.string.clan_title),
        Tab(R.drawable.ic_messages, R.drawable.ic_messages_detail, R.string.screen_tab_messages),
        Tab(R.drawable.ic_notifications, R.drawable.ic_notifications_detail, R.string.screen_tab_notifications),
        Tab(R.drawable.ic_profile, R.drawable.ic_profile_detail, R.string.screen_tab_profile)
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

    private val detailIcons: List<Drawable?> = tabs.map { tab ->
        ContextCompat.getDrawable(context, tab.detailIconResId)?.mutate()
    }

    private val labels: List<String> = tabs.map { tab ->
        context.getString(tab.labelResId)
    }

    private val hideAnimDuration = 200L
    private var isHidden = false

    private val iconSize = LayoutHelper.dp(24)
    private val iconLabelGap = LayoutHelper.dp(4)
    private val barHeight = LayoutHelper.dp(56)

    private var pressedTab = -1

    private val tmpIconBounds = Rect()

    private val cachedPrimaryTint = IntArray(tabs.size)
    private val cachedDetailTint = IntArray(tabs.size)

    private var tabWidth = 0f
    private val tabCenters = FloatArray(tabs.size)
    private var iconTopPx = 0
    private var labelBaselineY = 0f

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
            if (badgeCounts[tabIndex] == count) return
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
        for (i in cachedPrimaryTint.indices) {
            cachedPrimaryTint[i] = 0
            cachedDetailTint[i] = 0
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(barHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        tabWidth = w.toFloat() / tabs.size
        for (i in tabs.indices) {
            tabCenters[i] = tabWidth * i + tabWidth / 2f
        }
        val totalContentH = iconSize + iconLabelGap + labelPaint.textSize
        val topOffset = (h - totalContentH) / 2f
        iconTopPx = topOffset.toInt()
        labelBaselineY = topOffset + iconSize + iconLabelGap + labelPaint.textSize
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()

        canvas.drawColor(themeColors.surface)

        borderPaint.color = themeColors.outlineVariant
        canvas.drawLine(0f, 0f, w, 0f, borderPaint)

        for (i in tabs.indices) {
            val isSelected = i == selectedIndex
            val centerX = tabCenters[i]

            val primaryColor = if (isSelected) themeColors.tabIconActivePrimary else themeColors.tabIconPrimary
            val detailColor = if (isSelected) themeColors.tabIconActiveDetail else themeColors.tabIconDetail
            val labelColor = if (isSelected) themeColors.tabLabelActive else themeColors.tabLabelInactive

            val iconLeft = (centerX - iconSize / 2f).toInt()
            tmpIconBounds.set(iconLeft, iconTopPx, iconLeft + iconSize, iconTopPx + iconSize)

            val drawable = icons[i]
            if (drawable != null) {
                drawable.bounds = tmpIconBounds
                if (cachedPrimaryTint[i] != primaryColor) {
                    drawable.setTint(primaryColor)
                    cachedPrimaryTint[i] = primaryColor
                }
                drawable.draw(canvas)
            }

            val detailDrawable = detailIcons[i]
            if (detailDrawable != null) {
                detailDrawable.bounds = tmpIconBounds
                if (cachedDetailTint[i] != detailColor) {
                    detailDrawable.setTint(detailColor)
                    cachedDetailTint[i] = detailColor
                }
                detailDrawable.draw(canvas)
            }

            labelPaint.color = labelColor
            canvas.drawText(labels[i], centerX, labelBaselineY, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tabWidth <= 0f) return false
        val index = (event.x / tabWidth).toInt().coerceIn(0, tabs.size - 1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedTab = index
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressedTab >= 0 && index != pressedTab) {
                    pressedTab = -1
                }
            }

            MotionEvent.ACTION_UP -> {
                if (pressedTab >= 0 && index == pressedTab && index != selectedIndex) {
                    selectTab(index)
                    onTabSelected?.onTabSelected(index)
                }
                pressedTab = -1
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedTab = -1
            }
        }
        return true
    }
}
