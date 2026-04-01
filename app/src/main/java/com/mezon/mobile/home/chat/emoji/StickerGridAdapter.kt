package com.mezon.mobile.home.chat.emoji

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.StickerItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FOR_SALE_HEADER = "For Sale"

class StickerGridAdapter(
    private val themeColors: ThemeColors,
    private val onStickerClick: (StickerItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_STICKER = 1
    }

    sealed class ListItem {
        abstract val stableId: Long
        data class Header(val title: String, val expanded: Boolean) : ListItem() {
            override val stableId: Long get() = title.hashCode().toLong() or (1L shl 62)
        }
        data class Sticker(val item: StickerItem) : ListItem() {
            override val stableId: Long get() = item.id.hashCode().toLong()
        }
    }

    private data class GroupData(val name: String, val stickers: List<StickerItem>)

    private val items = ArrayList<ListItem>()
    private val groups = ArrayList<GroupData>()
    private val collapsedSections = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null

    init {
        setHasStableIds(true)
        collapsedSections.add(FOR_SALE_HEADER)
        CollapsibleHeaderCell.applyTheme(themeColors)
    }

    override fun getItemId(position: Int): Long =
        if (position in items.indices) items[position].stableId else RecyclerView.NO_ID

    fun setData(stickers: List<StickerItem>) {
        groups.clear()

        val forSale = stickers.filter { it.isForSale }
        if (forSale.isNotEmpty()) {
            groups.add(GroupData(FOR_SALE_HEADER, forSale))
        }

        val owned = stickers.filter { !it.isForSale && it.src.isNotBlank() }
        val byClan = LinkedHashMap<String, MutableList<StickerItem>>()
        for (s in owned) {
            val key = s.clanName.ifBlank { s.category.ifBlank { "Stickers" } }
            byClan.getOrPut(key) { ArrayList() }.add(s)
        }
        for ((groupName, group) in byClan) {
            groups.add(GroupData(groupName, group))
        }

        rebuildItems()
    }

    fun setSearchResults(stickers: List<StickerItem>) {
        groups.clear()
        val newItems = ArrayList<ListItem>(stickers.size)
        for (s in stickers) newItems.add(ListItem.Sticker(s))
        applyNewItems(newItems)
    }

    fun toggleSection(title: String) {
        if (collapsedSections.contains(title)) {
            collapsedSections.remove(title)
        } else {
            collapsedSections.add(title)
        }
        rebuildItems()
    }

    private fun rebuildItems() {
        val newItems = ArrayList<ListItem>()
        for (group in groups) {
            val expanded = !collapsedSections.contains(group.name)
            newItems.add(ListItem.Header(group.name, expanded))
            if (expanded) {
                for (s in group.stickers) newItems.add(ListItem.Sticker(s))
            }
        }
        applyNewItems(newItems)
    }

    private fun applyNewItems(newItems: ArrayList<ListItem>) {
        diffJob?.cancel()
        if (newItems.size < 50 && items.size < 50) {
            val result = DiffUtil.calculateDiff(ItemDiffCallback(items, newItems))
            items.clear(); items.addAll(newItems)
            result.dispatchUpdatesTo(this)
        } else {
            val oldList = ArrayList(items)
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(ItemDiffCallback(oldList, newItems))
                }
                items.clear(); items.addAll(newItems)
                result.dispatchUpdatesTo(this@StickerGridAdapter)
            }
        }
    }

    fun isHeader(position: Int): Boolean = items.getOrNull(position) is ListItem.Header

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.Header -> VIEW_TYPE_HEADER
        is ListItem.Sticker -> VIEW_TYPE_STICKER
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val cell = CollapsibleHeaderCell(parent.context, themeColors)
                val vh = HeaderViewHolder(cell)
                cell.onToggle = {
                    val pos = vh.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val item = items.getOrNull(pos)
                        if (item is ListItem.Header) toggleSection(item.title)
                    }
                }
                vh
            }
            else -> {
                val cell = StickerCell(parent.context, themeColors)
                val vh = StickerViewHolder(cell)
                cell.setOnClickListener {
                    val pos = vh.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val item = items.getOrNull(pos)
                        if (item is ListItem.Sticker) onStickerClick(item.item)
                    }
                }
                vh
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).cell.bind(item.title, item.expanded)
            is ListItem.Sticker -> (holder as StickerViewHolder).cell.setSticker(item.item)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    class HeaderViewHolder(val cell: CollapsibleHeaderCell) : RecyclerView.ViewHolder(cell)
    class StickerViewHolder(val cell: StickerCell) : RecyclerView.ViewHolder(cell)

    private class ItemDiffCallback(
        private val old: List<ListItem>,
        private val new: List<ListItem>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].stableId == new[newPos].stableId
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }
}
