package com.mezon.mobile.home.chat.input

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.InputSuggestionCell

class InputSuggestionsAdapter(
    private val theme: ThemeColors,
    private val onSelect: (InputSuggestionItem) -> Unit
) : RecyclerView.Adapter<InputSuggestionsAdapter.ViewHolder>() {

    private var items: List<InputSuggestionItem> = emptyList()

    override fun getItemCount(): Int = items.size

    fun submit(newItems: List<InputSuggestionItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun clear() {
        items = emptyList()
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = items.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val cell = InputSuggestionCell(parent.context, theme)
        return ViewHolder(cell)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cell = holder.itemView as InputSuggestionCell
        val item = items[position]
        cell.bind(item)
        cell.setDivider(position < items.size - 1)
        cell.setOnClickListener { onSelect(item) }
    }

    class ViewHolder(cell: InputSuggestionCell) : RecyclerView.ViewHolder(cell)
}
