package com.mezon.mobile.home.chat.channelinfo

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.MEDIA_ANONYMOUS_USER_ID
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ChannelFilesRow {
    data class Header(val year: String, val dayTitle: String, val showYearLine: Boolean) : ChannelFilesRow()
    data class Doc(val item: ChannelDocumentItem) : ChannelFilesRow()
}

fun buildChannelFileRows(
    documents: List<ChannelDocumentItem>,
    searchQuery: String,
    isVietnamese: Boolean
): List<ChannelFilesRow> {
    val q = normalizeSearchQuery(searchQuery)
    val filtered = if (q.isEmpty()) {
        documents
    } else {
        documents.filter { normalizeSearchQuery(it.filename).contains(q) }
    }
    val grouped = groupByYearDay(filtered) { ChannelDocumentItemUtil.parseItemDate(it) }
    val rows = ArrayList<ChannelFilesRow>()
    val cal = Calendar.getInstance()
    for (g in grouped) {
        cal.timeInMillis = g.dayTs
        val title = formatDateHeader(cal, isVietnamese)
        rows.add(ChannelFilesRow.Header(g.year, title, g.isFirstOfYear))
        for (doc in g.items) {
            rows.add(ChannelFilesRow.Doc(doc))
        }
    }
    return rows
}

interface FilesTabRowResolver {
    fun resolveSharerName(item: ChannelDocumentItem): String
    suspend fun loadAnonymousSharerName(item: ChannelDocumentItem): String?
    fun formatTime(createTimeSeconds: Int): String
    fun formatSharedByLine(displayName: String): String
    fun openUrl(url: String)
}

class ChannelFilesAdapter(
    private val theme: ThemeColors,
    private val resolver: FilesTabRowResolver
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_HEADER = 1
        const val VIEW_DOC = 2
    }

    private var rows: List<ChannelFilesRow> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null

    init {
        setHasStableIds(true)
    }

    fun setRows(newRows: List<ChannelFilesRow>) {
        diffJob?.cancel()
        if (rows.size < 50 && newRows.size < 50) {
            applyRows(newRows, DiffUtil.calculateDiff(FilesDiffCallback(rows, newRows)))
        } else {
            val oldRows = rows
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(FilesDiffCallback(oldRows, newRows))
                }
                applyRows(newRows, result)
            }
        }
    }

    private fun applyRows(newRows: List<ChannelFilesRow>, result: DiffUtil.DiffResult) {
        rows = newRows
        result.dispatchUpdatesTo(this)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    fun getRows(): List<ChannelFilesRow> = rows

    override fun getItemCount(): Int = rows.size

    override fun getItemId(position: Int): Long {
        return when (val r = rows[position]) {
            is ChannelFilesRow.Header -> -(position + 1).toLong()
            is ChannelFilesRow.Doc -> {
                val h = r.item.stableId.hashCode().toLong()
                if (h == 0L) -2L else h
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ChannelFilesRow.Header -> VIEW_HEADER
        is ChannelFilesRow.Doc -> VIEW_DOC
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return when (viewType) {
            VIEW_HEADER -> HeaderVH(ChannelFileSectionHeaderView(ctx, theme))
            else -> {
                val v = ChannelFileDocumentRowView(ctx, theme)
                v.layoutParams =
                    RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = LayoutHelper.dp(4f)
                        bottomMargin = LayoutHelper.dp(4f)
                    }
                v.setOnClickListener {
                    val item = v.tag as? ChannelDocumentItem ?: return@setOnClickListener
                    val url = item.url
                    if (url.isNotEmpty()) resolver.openUrl(url)
                }
                DocVH(v)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderVH -> {
                val h = rows[position] as ChannelFilesRow.Header
                holder.view.bind(h.year, h.dayTitle, h.showYearLine)
            }
            is DocVH -> {
                holder.sharerNameJob?.cancel()
                val item = (rows[position] as ChannelFilesRow.Doc).item
                val name = resolver.resolveSharerName(item)
                val time = resolver.formatTime(item.createTimeSeconds)
                val shared = resolver.formatSharedByLine(name)
                holder.view.bind(item, shared, time)
                holder.view.tag = item
                holder.sharerNameJob = if (item.uploader == MEDIA_ANONYMOUS_USER_ID &&
                    item.anonymousSharerName == null && item.messageId != 0L
                ) {
                    scope.launch {
                        val resolvedName = resolver.loadAnonymousSharerName(item) ?: return@launch
                        if (holder.view.tag === item) {
                            holder.view.setSharedByLine(resolver.formatSharedByLine(resolvedName))
                        }
                    }
                } else null
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is DocVH) {
            holder.sharerNameJob?.cancel()
            holder.sharerNameJob = null
        }
        super.onViewRecycled(holder)
    }

    class HeaderVH(val view: ChannelFileSectionHeaderView) : RecyclerView.ViewHolder(view)
    class DocVH(val view: ChannelFileDocumentRowView) : RecyclerView.ViewHolder(view) {
        var sharerNameJob: Job? = null
    }

    private class FilesDiffCallback(
        private val old: List<ChannelFilesRow>,
        private val new: List<ChannelFilesRow>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val o = old[oldPos]
            val n = new[newPos]
            return when {
                o is ChannelFilesRow.Header && n is ChannelFilesRow.Header ->
                    o.year == n.year && o.dayTitle == n.dayTitle
                o is ChannelFilesRow.Doc && n is ChannelFilesRow.Doc ->
                    o.item.stableId == n.item.stableId
                else -> false
            }
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos] == new[newPos]
    }
}
