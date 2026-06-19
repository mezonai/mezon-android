package com.mezon.mobile.home.chat.channelinfo

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.clans.ChannelCanvasData
import com.mezon.mobile.home.clans.ChannelCanvasItemCell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CanvasListAdapter(
    private val theme: ThemeColors,
    private val onViewCanvas: (ChannelCanvasData) -> Unit,
    private val onCopyLink: (ChannelCanvasData) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_ITEM = 0
        private const val VIEW_LOADING = 1
    }

    private var items: List<ChannelCanvasData> = emptyList()
    private var showLoadingFooter = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null

    init {
        setHasStableIds(true)
    }

    fun setItems(newItems: List<ChannelCanvasData>, showLoadingFooter: Boolean = false) {
        val footerChanged = showLoadingFooter != this.showLoadingFooter
        if (newItems === items && !footerChanged) return
        if (newItems === items) {
            val hadFooter = this.showLoadingFooter
            this.showLoadingFooter = showLoadingFooter
            if (showLoadingFooter && !hadFooter) notifyItemInserted(items.size)
            else if (!showLoadingFooter && hadFooter) notifyItemRemoved(items.size)
            return
        }
        this.showLoadingFooter = showLoadingFooter
        diffJob?.cancel()
        if (showLoadingFooter) {
            items = newItems
            notifyDataSetChanged()
            return
        }
        if (items.size < 50 && newItems.size < 50) {
            applyItems(newItems, DiffUtil.calculateDiff(CanvasDiffCallback(items, newItems)))
            return
        }
        val oldItems = items
        diffJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                DiffUtil.calculateDiff(CanvasDiffCallback(oldItems, newItems))
            }
            applyItems(newItems, result)
        }
    }

    private fun applyItems(newItems: List<ChannelCanvasData>, result: DiffUtil.DiffResult) {
        items = newItems
        result.dispatchUpdatesTo(this)
    }

    fun dispose() {
        diffJob?.cancel()
        scope.cancel()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    override fun getItemCount(): Int = items.size + if (showLoadingFooter) 1 else 0

    override fun getItemViewType(position: Int): Int {
        return if (showLoadingFooter && position == items.size) VIEW_LOADING else VIEW_ITEM
    }

    override fun getItemId(position: Int): Long {
        return if (showLoadingFooter && position == items.size) RecyclerView.NO_ID else items[position].id
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_LOADING) {
            val container = FrameLayout(parent.context)
            val progress = ProgressBar(parent.context)
            container.addView(
                progress,
                FrameLayout.LayoutParams(
                    LayoutHelper.dp(32f),
                    LayoutHelper.dp(32f),
                    Gravity.CENTER
                )
            )
            container.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(56f)
            )
            return LoadingViewHolder(container)
        }
        val cell = ChannelCanvasItemCell(parent.context, theme).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(50f)
            ).apply {
                val marginH = LayoutHelper.dp(12f)
                val marginV = LayoutHelper.dp(4f)
                setMargins(marginH, marginV, marginH, marginV)
            }
        }
        return CanvasViewHolder(cell)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is CanvasViewHolder) {
            holder.bind(items[position])
        }
    }

    private class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view)

    inner class CanvasViewHolder(private val cell: ChannelCanvasItemCell) : RecyclerView.ViewHolder(cell) {
        init {
            cell.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onViewCanvas(items[position])
            }
            cell.onCopyLinkClick = {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onCopyLink(items[position])
            }
        }

        fun bind(item: ChannelCanvasData) {
            val title = item.title.replace("\n", " ").ifBlank {
                cell.context.getString(com.mezon.mobile.R.string.channel_canvas_untitled)
            }
            cell.bind(title)
        }
    }

    private class CanvasDiffCallback(
        private val oldItems: List<ChannelCanvasData>,
        private val newItems: List<ChannelCanvasData>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldItems.size
        override fun getNewListSize(): Int = newItems.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldItems[oldItemPosition].id == newItems[newItemPosition].id
        }
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldItems[oldItemPosition]
            val new = newItems[newItemPosition]
            return old.title == new.title && old.creatorId == new.creatorId && old.isDefault == new.isDefault
        }
    }
}
