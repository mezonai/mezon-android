package com.mezon.mobile.home.chat.emoji

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.EmojiItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmojiGridAdapter(
    private val themeColors: ThemeColors,
    private val onEmojiClick: (EmojiItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_EMOJI = 1
    }

    sealed class ListItem {
        abstract val stableId: Long
        data class Header(val title: String, val expanded: Boolean) : ListItem() {
            override val stableId: Long get() = title.hashCode().toLong() or (1L shl 62)
        }
        data class Emoji(val item: EmojiItem) : ListItem() {
            override val stableId: Long get() = item.id.hashCode().toLong()
        }
    }

    private data class CategoryData(val name: String, val emojis: List<EmojiItem>)

    private val items = ArrayList<ListItem>()
    private val categories = ArrayList<CategoryData>()
    private val collapsedSections = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null

    init {
        setHasStableIds(true)
        CollapsibleHeaderCell.applyTheme(themeColors)
    }

    override fun getItemId(position: Int): Long =
        if (position in items.indices) items[position].stableId else RecyclerView.NO_ID

    fun setData(categoryList: List<Pair<String, List<EmojiItem>>>) {
        categories.clear()
        for ((name, emojis) in categoryList) {
            if (emojis.isEmpty()) continue
            categories.add(CategoryData(name, emojis))
        }
        rebuildItems()
    }

    fun setSearchResults(emojis: List<EmojiItem>) {
        categories.clear()
        val newItems = ArrayList<ListItem>(emojis.size)
        for (emoji in emojis) {
            newItems.add(ListItem.Emoji(emoji))
        }
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
        for (cat in categories) {
            val expanded = !collapsedSections.contains(cat.name)
            newItems.add(ListItem.Header(cat.name, expanded))
            if (expanded) {
                for (emoji in cat.emojis) {
                    newItems.add(ListItem.Emoji(emoji))
                }
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
                result.dispatchUpdatesTo(this@EmojiGridAdapter)
            }
        }
    }

    fun isHeader(position: Int): Boolean = items.getOrNull(position) is ListItem.Header

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ListItem.Header -> VIEW_TYPE_HEADER
        is ListItem.Emoji -> VIEW_TYPE_EMOJI
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
                val cell = EmojiCell(parent.context, themeColors)
                val vh = EmojiViewHolder(cell)
                cell.setOnClickListener {
                    val pos = vh.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val item = items.getOrNull(pos)
                        if (item is ListItem.Emoji) onEmojiClick(item.item)
                    }
                }
                vh
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).cell.bind(item.title, item.expanded)
            is ListItem.Emoji -> (holder as EmojiViewHolder).cell.setEmoji(item.item)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    class HeaderViewHolder(val cell: CollapsibleHeaderCell) : RecyclerView.ViewHolder(cell)
    class EmojiViewHolder(val cell: EmojiCell) : RecyclerView.ViewHolder(cell)

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
