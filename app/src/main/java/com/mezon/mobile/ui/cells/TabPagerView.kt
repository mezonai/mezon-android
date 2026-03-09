package com.mezon.mobile.ui.cells

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class TabPagerView(context: Context, private val theme: ThemeColors) : LinearLayout(context) {

    val tabLayout: TabLayout
    val viewPager: ViewPager2

    init {
        orientation = VERTICAL

        tabLayout = TabLayout(context).apply {
            setBackgroundColor(theme.surface)
            setSelectedTabIndicatorColor(theme.primary)
            tabTextColors = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(theme.primary, theme.onSurfaceVariant)
            )
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
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
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()
    }

    fun setupWithViews(titles: List<String>, viewProvider: (Int) -> View) {
        viewPager.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<SimpleViewHolder>() {
            override fun getItemCount(): Int = titles.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SimpleViewHolder {
                val view = viewProvider(viewType)
                view.layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                return SimpleViewHolder(view)
            }
            override fun onBindViewHolder(holder: SimpleViewHolder, position: Int) {}
            override fun getItemViewType(position: Int): Int = position
        }
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()
    }

    private class SimpleViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)
}
