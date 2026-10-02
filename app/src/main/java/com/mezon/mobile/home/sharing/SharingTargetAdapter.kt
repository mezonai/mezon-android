package com.mezon.mobile.home.sharing

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SharingTargetAdapter(
    private val theme: ThemeColors
) : RecyclerView.Adapter<SharingTargetAdapter.ViewHolder>() {

    private val items = ArrayList<SharingTarget>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null
    private var forwardMode: Boolean = false
    private var forwardSelectedKeys: Set<String> = emptySet()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].stableId

    override fun getItemCount(): Int = items.size

    fun getItem(position: Int): SharingTarget = items[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val cell = SharingTargetCell(parent.context, theme)
        return ViewHolder(cell)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val t = items[position]
        val sel = forwardMode && forwardSelectedKeys.contains(t.key)
        (holder.itemView as SharingTargetCell).setData(t, forwardMode, sel)
    }

    fun setData(
        newItems: List<SharingTarget>,
        isForwardMultiSelect: Boolean = false,
        selectedKeys: Set<String> = emptySet()
    ) {
        forwardMode = isForwardMultiSelect
        forwardSelectedKeys = selectedKeys.toSet()
        diffJob?.cancel()
        if (items.size < 50 && newItems.size < 50) {
            val result = DiffUtil.calculateDiff(Callback(items, newItems))
            items.clear()
            items.addAll(newItems)
            result.dispatchUpdatesTo(this)
        } else {
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(Callback(ArrayList(items), newItems))
                }
                items.clear()
                items.addAll(newItems)
                result.dispatchUpdatesTo(this@SharingTargetAdapter)
            }
        }
    }

    fun updateForwardSelection(selectedKeys: Set<String>) {
        val old = forwardSelectedKeys
        val next = selectedKeys.toSet()
        if (old == next) return
        forwardSelectedKeys = next
        for (i in items.indices) {
            val key = items[i].key
            val wasSelected = key in old
            val isSelected = key in next
            if (wasSelected != isSelected) {
                notifyItemChanged(i)
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    class ViewHolder(cell: SharingTargetCell) : RecyclerView.ViewHolder(cell)

    private class Callback(
        private val old: List<SharingTarget>,
        private val new: List<SharingTarget>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(a: Int, b: Int) = old[a].key == new[b].key
        override fun areContentsTheSame(a: Int, b: Int) = old[a] == new[b]
    }
}

private const val UNRESOLVED_DM_ROW_TYPE = 11

private val SharingTarget.stableId: Long
    get() = if (channelId != 0L) channelId * 31L + channelType else userId * 31L + UNRESOLVED_DM_ROW_TYPE
