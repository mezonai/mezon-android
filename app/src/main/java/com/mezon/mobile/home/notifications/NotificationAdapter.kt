package com.mezon.mobile.home.notifications

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors

class NotificationAdapter(
    private val theme: ThemeColors
) : RecyclerView.Adapter<NotificationAdapter.VH>() {

    private val items = ArrayList<NotificationEntity>()

    fun getItem(position: Int): NotificationEntity? =
        if (position in items.indices) items[position] else null

    fun setData(list: List<NotificationEntity>) {
        val old = ArrayList(items)
        items.clear()
        items.addAll(list)
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == list[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        }).dispatchUpdatesTo(this)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val cell = NotificationCell(parent.context, theme).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return VH(cell)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entity = items[position]
        holder.cell.update(0, entity)
    }

    class VH(val cell: NotificationCell) : RecyclerView.ViewHolder(cell)
}
