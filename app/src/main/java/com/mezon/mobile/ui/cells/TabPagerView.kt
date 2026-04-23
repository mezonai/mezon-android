package com.mezon.mobile.ui.cells

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class TabPagerView(context: Context, private val theme: ThemeColors) : LinearLayout(context) {

    val tabLayout: LinearLayout
    val viewPager: ViewPager2
    private val tabViews = mutableListOf<TextView>()
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null

    init {
        orientation = VERTICAL

        tabLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(theme.surface)
        }
        addView(tabLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        viewPager = ViewPager2(context).apply {
            id = View.generateViewId()
        }
        addView(viewPager, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
    }

    fun setup(activity: FragmentActivity, titles: List<String>, fragmentProvider: (Int) -> Fragment) {
        viewPager.adapter = object : FragmentStateAdapter(activity) {
            override fun getItemCount(): Int = titles.size
            override fun createFragment(position: Int): Fragment = fragmentProvider(position)
        }
        bindTabs(titles)
    }

    fun setupWithViews(titles: List<String>, viewProvider: (Int) -> View) {
        viewPager.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<SimpleViewHolder>() {
            override fun getItemCount(): Int = titles.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SimpleViewHolder {
                val view = viewProvider(viewType)
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                return SimpleViewHolder(view)
            }
            override fun onBindViewHolder(holder: SimpleViewHolder, position: Int) {}
            override fun getItemViewType(position: Int): Int = position
        }
        bindTabs(titles)
    }

    private fun bindTabs(titles: List<String>) {
        tabLayout.removeAllViews()
        tabViews.clear()
        if (titles.isEmpty()) return
        titles.forEachIndexed { index, title ->
            val tab = TextView(context).apply {
                text = title
                gravity = android.view.Gravity.CENTER
                textSize = 14f
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(10))
                setOnClickListener { viewPager.currentItem = index }
            }
            tabViews.add(tab)
            tabLayout.addView(tab, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        }
        pageCallback?.let { viewPager.unregisterOnPageChangeCallback(it) }
        pageCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTabState(position)
            }
        }
        viewPager.registerOnPageChangeCallback(pageCallback!!)
        updateTabState(viewPager.currentItem.coerceIn(0, titles.lastIndex))
    }

    private fun updateTabState(activeIndex: Int) {
        tabViews.forEachIndexed { index, view ->
            val selected = index == activeIndex
            view.setTextColor(if (selected) theme.primary else theme.onSurfaceVariant)
            view.background = if (selected) {
                GradientDrawable().apply {
                    setColor(theme.surfaceVariant)
                    cornerRadius = LayoutHelper.dpf(12f)
                }
            } else {
                null
            }
        }
    }

    private class SimpleViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)
}
