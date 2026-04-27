package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class SearchTabHeader(context: Context, private val theme: ThemeColors) : LinearLayout(context) {

    var onTabSelected: ((Int) -> Unit)? = null
    private var selectedTab = 0
    private val tabViews = ArrayList<TabItemView>()
    private val bottomBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x305A5B5C
        strokeWidth = LayoutHelper.dpf(1f)
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(theme.background)
        setWillNotDraw(false)
    }

    fun setTabs(labels: List<String>) {
        removeAllViews()
        tabViews.clear()

        for (i in labels.indices) {
            val tab = TabItemView(context, theme, labels[i])
            tab.setOnClickListener {
                selectTab(i)
                onTabSelected?.invoke(i)
            }
            tabViews.add(tab)
            addView(tab, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        selectTab(0)
    }

    fun updateCounts(counts: List<Int>) {
        for (i in counts.indices) {
            if (i < tabViews.size) {
                tabViews[i].setCount(counts[i])
            }
        }
    }

    fun selectTab(index: Int) {
        if (index < 0 || index >= tabViews.size) return
        selectedTab = index
        for (i in tabViews.indices) {
            tabViews[i].setActive(i == index)
        }
    }

    fun getSelectedTab(): Int = selectedTab

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = height - bottomBorderPaint.strokeWidth / 2f
        canvas.drawLine(0f, y, width.toFloat(), y, bottomBorderPaint)
    }

    private class TabItemView(
        context: Context,
        private val theme: ThemeColors,
        label: String
    ) : LinearLayout(context) {

        private val labelView: TextView
        private val indicatorView: View
        private var tabLabel = label

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true

            labelView = TextView(context).apply {
                text = label
                textSize = 14f
                setTypeface(typeface, Typeface.NORMAL)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(LayoutHelper.dp(4f), TAB_PAD_V, LayoutHelper.dp(4f), TAB_PAD_V)
            }
            addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            indicatorView = View(context).apply {
                setBackgroundColor(theme.blurple)
                visibility = GONE
            }
            addView(indicatorView, LayoutParams(LayoutParams.MATCH_PARENT, INDICATOR_HEIGHT))
        }

        fun setCount(count: Int) {
            val countText = when {
                count > 0 -> " ($count)"
                else -> ""
            }
            val newText = "$tabLabel$countText"
            if (newText == labelView.text.toString()) return
            labelView.text = newText
        }

        fun setActive(active: Boolean) {
            if (active) {
                labelView.setTextColor(theme.blurple)
                labelView.setTypeface(labelView.typeface, Typeface.BOLD)
                indicatorView.visibility = VISIBLE
            } else {
                labelView.setTextColor(theme.onSurface)
                labelView.setTypeface(labelView.typeface, Typeface.NORMAL)
                indicatorView.visibility = GONE
            }
        }

        companion object {
            private val TAB_PAD_V = LayoutHelper.dp(14f)
            private val INDICATOR_HEIGHT = LayoutHelper.dp(2f)
        }
    }
}
