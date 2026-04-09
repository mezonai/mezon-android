package com.mezon.mobile.home.messages

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

class DmListAdapter(
    private val themeColors: ThemeColors,
    private val buzzChecker: ((Long) -> Boolean)? = null
) : RecyclerView.Adapter<DmListAdapter.ViewHolder>() {

    companion object {
        private const val DIFF_BG_THRESHOLD = 50
    }

    private val items = ArrayList<DirectMessage>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        if (position in items.indices) items[position].channelId else RecyclerView.NO_ID

    fun getItem(position: Int): DirectMessage? =
        if (position in items.indices) items[position] else null

    fun setData(newItems: List<DirectMessage>) {
        diffJob?.cancel()

        if (newItems.size < DIFF_BG_THRESHOLD && items.size < DIFF_BG_THRESHOLD) {
            applyDiff(newItems, DiffUtil.calculateDiff(DmDiffCallback(items, newItems)))
        } else {
            val oldList = ArrayList(items)
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(DmDiffCallback(oldList, newItems))
                }
                applyDiff(newItems, result)
            }
        }
    }

    private fun applyDiff(newItems: List<DirectMessage>, result: DiffUtil.DiffResult) {
        items.clear()
        items.addAll(newItems)
        result.dispatchUpdatesTo(this)
    }

    fun updateVisibleRows(recyclerView: RecyclerView, mask: Int, dialogs: List<DirectMessage>? = null) {
        val dialogMap: HashMap<Long, DirectMessage>?
        if (dialogs != null) {
            dialogMap = HashMap(dialogs.size)
            for (dm in dialogs) dialogMap[dm.channelId] = dm
        } else {
            dialogMap = null
        }

        val count = recyclerView.childCount
        for (i in 0 until count) {
            val child = recyclerView.getChildAt(i)
            if (child is DialogCell) {
                val current = child.directMessage ?: continue
                child.hasBuzz = buzzChecker?.invoke(current.channelId) == true
                val updated = dialogMap?.get(current.channelId)
                if (child.update(mask, updated)) {
                    setData(dialogs ?: items)
                    break
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val cell = DialogCell(parent.context, themeColors)
        cell.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )
        return ViewHolder(cell)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val dm = items[position]
        holder.cell.hasBuzz = buzzChecker?.invoke(dm.channelId) == true
        holder.cell.update(0, dm)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    class ViewHolder(val cell: DialogCell) : RecyclerView.ViewHolder(cell)

    private class DmDiffCallback(
        private val old: List<DirectMessage>,
        private val new: List<DirectMessage>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos].channelId == new[newPos].channelId
        override fun areContentsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos] == new[newPos]
    }
}
