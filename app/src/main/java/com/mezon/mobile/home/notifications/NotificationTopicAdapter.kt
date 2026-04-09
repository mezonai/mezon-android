package com.mezon.mobile.home.notifications

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import kotlinx.coroutines.*

class NotificationTopicAdapter(
    private val theme: ThemeColors
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<TopicEntity>()
    private var diffJob: Job? = null

    fun setData(list: List<TopicEntity>) {
        diffJob?.cancel()
        val oldList = ArrayList(items)

        diffJob = CoroutineScope(Dispatchers.Default).launch {
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldList.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(o: Int, n: Int) = oldList[o].id == list[n].id
                override fun areContentsTheSame(o: Int, n: Int) = oldList[o] == list[n]
            })

            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext
                items.clear()
                items.addAll(list)
                diffResult.dispatchUpdatesTo(this@NotificationTopicAdapter)
                diffJob = null
            }
        }
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val cell = TopicCell(parent.context, theme).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ItemVH(cell)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ItemVH && position < items.size) {
            holder.cell.setData(items[position])
        }
    }

    class ItemVH(val cell: TopicCell) : RecyclerView.ViewHolder(cell)
}