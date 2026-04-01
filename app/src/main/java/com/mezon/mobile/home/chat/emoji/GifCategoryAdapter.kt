package com.mezon.mobile.home.chat.emoji

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.network.TenorCategory

class GifCategoryAdapter(
    private val themeColors: ThemeColors,
    private val onCategoryClick: (TenorCategory) -> Unit
) : RecyclerView.Adapter<GifCategoryAdapter.CategoryViewHolder>() {

    private val categories = ArrayList<TenorCategory>()

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long =
        if (position in categories.indices) categories[position].name.hashCode().toLong() else RecyclerView.NO_ID

    fun setData(newCategories: List<TenorCategory>) {
        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = categories.size
            override fun getNewListSize() = newCategories.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                categories[oldPos].name == newCategories[newPos].name
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                categories[oldPos] == newCategories[newPos]
        })
        categories.clear()
        categories.addAll(newCategories)
        result.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = categories.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val cell = GifCategoryCell(parent.context, themeColors)
        val vh = CategoryViewHolder(cell)
        cell.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                categories.getOrNull(pos)?.let { onCategoryClick(it) }
            }
        }
        return vh
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.cell.setCategory(categories[position])
    }

    class CategoryViewHolder(val cell: GifCategoryCell) : RecyclerView.ViewHolder(cell)
}
