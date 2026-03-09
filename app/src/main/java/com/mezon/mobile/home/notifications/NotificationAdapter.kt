package com.mezon.mobile.home.notifications

import android.util.Log
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors

class NotificationAdapter(
    private val theme: ThemeColors,
    private val onPress: (NotificationEntity) -> Unit,
    private val onLongPress: (NotificationEntity) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.VH>() {

    private val items = ArrayList<NotificationEntity>()

    fun setData(list: List<NotificationEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
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
        holder.cell.setData(entity)
        holder.cell.setOnClickListener {
            Log.d("NotifNav", "cell clicked pos=$position entity.id=${entity.id}")
            onPress(entity)
        }
        holder.cell.setOnLongClickListener {
            onLongPress(entity)
            true
        }
    }

    class VH(val cell: NotificationCell) : RecyclerView.ViewHolder(cell)
}
