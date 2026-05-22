package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.CreateClanRnUiTokens

class ChannelCategoryPickerSheet(
    context: Context,
    private val themeColors: ThemeColors,
    categories: List<Pair<Long, String>>,
    title: CharSequence,
    private val onCategoryPicked: (Long, String) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    private val items = categories

    init {
        setTitle(title)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val list = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = CategoryAdapter()
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            itemAnimator = null
            setPadding(LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f), LayoutHelper.dp(8f))
        }
        list.setOnItemClickListener { _, pos ->
            if (pos in items.indices) {
                val (id, name) = items[pos]
                onCategoryPicked(id, name)
                dismiss()
            }
        }
        val empty = TextView(context).apply {
            text = context.getString(R.string.channel_category_picker_empty)
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
            visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 24f, 0f, 24f))
            addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(280f)))
        }
        setCustomView(wrap)
    }

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryVH>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): CategoryVH {
            val row = TextView(parent.context).apply {
                setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(14f), LayoutHelper.dp(16f), LayoutHelper.dp(14f))
                textSize = 16f
                setTextColor(themeColors.textStrong)
                typeface = Typeface.DEFAULT_BOLD
            }
            return CategoryVH(row)
        }

        override fun onBindViewHolder(holder: CategoryVH, position: Int) {
            holder.label.text = items[position].second
        }

        override fun getItemCount(): Int = items.size
    }

    private class CategoryVH(val label: TextView) : RecyclerView.ViewHolder(label)
}
