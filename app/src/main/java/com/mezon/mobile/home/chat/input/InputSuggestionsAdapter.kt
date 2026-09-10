package com.mezon.mobile.home.chat.input

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.InputSuggestionCell

class InputSuggestionsAdapter(
    private val theme: ThemeColors,
    private val onSelect: (InputSuggestionItem) -> Unit
) : RecyclerView.Adapter<InputSuggestionsAdapter.ViewHolder>() {

    private var items: List<InputSuggestionItem> = emptyList()

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        val item = items.getOrNull(position) ?: return RecyclerView.NO_ID
        return suggestionStableId(item)
    }

    fun submit(newItems: List<InputSuggestionItem>) {
        applyItems(newItems)
    }

    fun clear() {
        applyItems(emptyList())
    }

    private fun applyItems(newItems: List<InputSuggestionItem>) {
        val result = DiffUtil.calculateDiff(SuggestionDiffCallback(items, newItems))
        items = newItems
        result.dispatchUpdatesTo(this)
    }

    fun isEmpty(): Boolean = items.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val cell = InputSuggestionCell(parent.context, theme)
        val holder = ViewHolder(cell)
        cell.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos !in items.indices) return@setOnClickListener
            val item = items[pos]
            if (item is InputSuggestionItem.Loading) return@setOnClickListener
            onSelect(item)
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cell = holder.itemView as InputSuggestionCell
        val item = items[position]
        cell.bind(item)
        cell.setDivider(position < items.size - 1)
    }

    class ViewHolder(cell: InputSuggestionCell) : RecyclerView.ViewHolder(cell)

    private class SuggestionDiffCallback(
        private val old: List<InputSuggestionItem>,
        private val new: List<InputSuggestionItem>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            suggestionStableId(old[oldPos]) == suggestionStableId(new[newPos])

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos] == new[newPos] &&
                (oldPos == old.lastIndex) == (newPos == new.lastIndex)
    }
}

private fun suggestionStableId(item: InputSuggestionItem): Long = when (item) {
    is InputSuggestionItem.Here -> Long.MIN_VALUE + 1
    is InputSuggestionItem.Loading -> Long.MIN_VALUE + 2
    is InputSuggestionItem.Member -> item.member.userId
    is InputSuggestionItem.Role -> item.role.roleId or (1L shl 61)
    is InputSuggestionItem.Channel -> item.entity.channelId or (1L shl 60)
    is InputSuggestionItem.Emoji -> item.item.id.hashCode().toLong() or (1L shl 62)
}
