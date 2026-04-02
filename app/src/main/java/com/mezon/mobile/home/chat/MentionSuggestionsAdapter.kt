package com.mezon.mobile.home.chat

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.ui.cells.MentionSuggestionCell

class MentionSuggestionsAdapter(
    private val theme: ThemeColors,
    private val onMentionSelected: (MentionSuggestionItem) -> Unit
) : RecyclerView.Adapter<MentionSuggestionsAdapter.ViewHolder>() {

    private var results = ArrayList<MentionSuggestionItem>()

    override fun getItemCount(): Int = results.size

    fun search(query: String, members: List<ClanMember>) {
        val q = query.lowercase()
        val filtered = members.filter { m ->
            val dn = m.clanNick.ifBlank { m.displayName }
            dn.lowercase().startsWith(q) ||
                m.username.lowercase().startsWith(q) ||
                m.displayName.lowercase().startsWith(q)
        }
        results.clear()
        if ("here".startsWith(q)) {
            results.add(MentionSuggestionItem.Here)
        }
        for (m in filtered) {
            results.add(MentionSuggestionItem.Member(m))
        }
        notifyDataSetChanged()
    }

    fun clear() {
        results.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val cell = MentionSuggestionCell(parent.context, theme)
        return ViewHolder(cell)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cell = holder.itemView as MentionSuggestionCell
        val item = results[position]
        when (item) {
            is MentionSuggestionItem.Here -> cell.setHereItem()
            is MentionSuggestionItem.Member -> cell.setMember(item.member)
        }
        cell.setDivider(position < results.size - 1)
        cell.setOnClickListener { onMentionSelected(item) }
    }

    class ViewHolder(cell: MentionSuggestionCell) : RecyclerView.ViewHolder(cell)
}

sealed class MentionSuggestionItem {
    data object Here : MentionSuggestionItem()
    data class Member(val member: ClanMember) : MentionSuggestionItem()
}
