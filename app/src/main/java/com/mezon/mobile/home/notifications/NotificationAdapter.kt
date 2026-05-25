package com.mezon.mobile.home.notifications

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import kotlinx.coroutines.*

class NotificationAdapter(
    private val theme: ThemeColors,
    private val memberResolver: (Long, Long, Long, Int) -> ClanMember?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<NotificationEntity>()
    private var hasMore = false
    private var adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null

    init { setHasStableIds(true) }

    companion object {
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_LOADING = 1
        private const val LOADING_ITEM_ID = Long.MIN_VALUE
    }

    override fun getItemId(position: Int): Long {
        return if (position < items.size) items[position].id else LOADING_ITEM_ID
    }

    fun setData(list: List<NotificationEntity>, hasMoreData: Boolean = false, isTabChange: Boolean = false) {
        diffJob?.cancel()
        val oldList = ArrayList(items)
        if (isTabChange || (oldList.isEmpty() && list.isNotEmpty())) {
            items.clear()
            items.addAll(list)
            hasMore = hasMoreData
            notifyDataSetChanged()
            return
        }

        val oldHasMore = hasMore

        diffJob = adapterScope.launch {
            val diffResult = withContext(Dispatchers.Default) {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldList.size + if (oldHasMore) 1 else 0
                    override fun getNewListSize() = list.size + if (hasMoreData) 1 else 0
                    override fun areItemsTheSame(o: Int, n: Int): Boolean {
                        val isOldLoading = o == oldList.size
                        val isNewLoading = n == list.size
                        if (isOldLoading && isNewLoading) return true
                        if (isOldLoading || isNewLoading) return false
                        return oldList[o].id == list[n].id
                    }
                    override fun areContentsTheSame(o: Int, n: Int): Boolean {
                        val isOldLoading = o == oldList.size
                        val isNewLoading = n == list.size
                        if (isOldLoading && isNewLoading) return true
                        if (isOldLoading || isNewLoading) return false
                        return oldList[o] == list[n]
                    }
                })
            }
            items.clear()
            items.addAll(list)
            hasMore = hasMoreData
            diffResult.dispatchUpdatesTo(this@NotificationAdapter)
            diffJob = null
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (!adapterScope.isActive) {
            adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        diffJob = null
        super.onDetachedFromRecyclerView(recyclerView)
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
            holder.cell.memberResolver = memberResolver
            holder.cell.update(0, entity)
        }
    }

    class ItemVH(val cell: NotificationCell) : RecyclerView.ViewHolder(cell)
}
