package com.mezon.mobile.ui.cells

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
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
    private val rippleW = LayoutHelper.dp(64)
    private val rippleH = LayoutHelper.dp(32)
    private val rippleRadius = LayoutHelper.dp(16).toFloat()
    private val rippleMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1 }

    private val rippleDrawable: RippleDrawable

    init {
        setWillNotDraw(false)
        val rippleColor = ColorStateList.valueOf(themeColors.onSurface and 0x0D_FFFFFF)
        val mask = object : Drawable() {
            override fun draw(canvas: Canvas) {
                val b = bounds
                canvas.drawRoundRect(
                    b.left.toFloat(), b.top.toFloat(),
                    b.right.toFloat(), b.bottom.toFloat(),
                    rippleRadius, rippleRadius, rippleMaskPaint
                )
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity(): Int = PixelFormat.UNKNOWN
        }
        rippleDrawable = RippleDrawable(rippleColor, null, mask)
        rippleDrawable.callback = this
    }

    override fun verifyDrawable(who: Drawable): Boolean {
        return who == rippleDrawable || super.verifyDrawable(who)
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
        rippleDrawable.setColor(ColorStateList.valueOf(themeColors.onSurface and 0x0D_FFFFFF))
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

        rippleDrawable.draw(canvas)

        for (i in tabs.indices) {
            val isSelected = i == selectedIndex
            val centerX = tabW * i + tabW / 2f

            val primaryColor = themeColors.tabIconPrimary
            val detailColor = themeColors.tabIconDetail
            val labelColor = if (isSelected) themeColors.tabLabelActive else themeColors.tabLabelInactive

            val iconLeft = (centerX - iconSize / 2f).toInt()
            val iconTop = topOffset.toInt()
            val iconBounds = android.graphics.Rect(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

            val drawable = icons[i]
            if (drawable != null) {
                drawable.bounds = iconBounds
                drawable.setTint(primaryColor)
                drawable.alpha = 255
                drawable.draw(canvas)
            }

            val detailDrawable = detailIcons[i]
            if (detailDrawable != null) {
                detailDrawable.bounds = iconBounds
                detailDrawable.setTint(detailColor)
                detailDrawable.alpha = 255
                detailDrawable.draw(canvas)
            }

            labelPaint.color = labelColor
            labelPaint.alpha = 255
            val labelY = topOffset + iconSize + iconLabelGap + labelPaint.textSize
            canvas.drawText(labels[i], centerX, labelY, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tabW = width.toFloat() / tabs.size
        val index = (event.x / tabW).toInt().coerceIn(0, tabs.size - 1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedTab = index
                positionRipple(index, tabW)
                rippleDrawable.setHotspot(event.x, event.y)
                rippleDrawable.state = intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                rippleDrawable.setHotspot(event.x, event.y)
            }

            MotionEvent.ACTION_UP -> {
                if (pressedTab >= 0 && index == pressedTab && index != selectedIndex) {
                    selectTab(index)
                    onTabSelected?.onTabSelected(index)
                }
                rippleDrawable.state = intArrayOf()
                pressedTab = -1
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                rippleDrawable.state = intArrayOf()
                pressedTab = -1
                invalidate()
            }
        }
        return true
    }

    private fun positionRipple(index: Int, tabW: Float) {
        val centerX = tabW * index + tabW / 2f
        val totalContentH = iconSize + iconLabelGap + labelPaint.textSize
        val topOffset = (height - totalContentH) / 2f
        val iconCenterY = topOffset + iconSize / 2f
        val left = (centerX - rippleW / 2f).toInt()
        val top = (iconCenterY - rippleH / 2f).toInt()
        rippleDrawable.setBounds(left, top, left + rippleW, top + rippleH)
    }
}
