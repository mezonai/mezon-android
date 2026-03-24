package com.mezon.mobile.home.notifications

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class NotificationAdapter(
    private val theme: ThemeColors
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<NotificationEntity>()
    private var hasMore = false

    companion object {
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_LOADING = 1
    }

    fun getItem(position: Int): NotificationEntity? =
        if (position in items.indices) items[position] else null

    fun setData(list: List<NotificationEntity>, hasMoreData: Boolean = false, isTabChange: Boolean = false) {
        if (isTabChange) {
            items.clear()
            items.addAll(list)
            hasMore = hasMoreData
            notifyDataSetChanged()
            return
        }
        
        val old = ArrayList(items)
        val oldHasMore = hasMore
        items.clear()
        items.addAll(list)
        hasMore = hasMoreData
        
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size + if (oldHasMore) 1 else 0
            override fun getNewListSize() = list.size + if (hasMore) 1 else 0
            override fun areItemsTheSame(o: Int, n: Int): Boolean {
                if (o < old.size && n < list.size) return old[o].id == list[n].id
                if (o == old.size && n == list.size) return true // Both are loading item
                return false
            }
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                if (o < old.size && n < list.size) return old[o] == list[n]
                return true
            }
        }).dispatchUpdatesTo(this)
    }

    override fun getItemCount() = items.size + if (hasMore) 1 else 0

    override fun getItemViewType(position: Int): Int {
        return if (position < items.size) VIEW_TYPE_ITEM else VIEW_TYPE_LOADING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_TYPE_LOADING) {
            val progress = ProgressBar(parent.context).apply {
                isIndeterminate = true
                val pad = LayoutHelper.dp(16)
                setPadding(pad, pad, pad, pad)
                layoutParams = FrameLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48), Gravity.CENTER)
            }
            val frame = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(progress)
            }
            return object : RecyclerView.ViewHolder(frame) {}
        }

        val cell = NotificationCell(parent.context, theme).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ItemVH(cell)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ItemVH && position < items.size) {
            val entity = items[position]
            holder.cell.update(0, entity)
        }
    }

    class ItemVH(val cell: NotificationCell) : RecyclerView.ViewHolder(cell)
}
