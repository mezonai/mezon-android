package com.mezon.mobile.home.chat.emoji

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.network.KlipyGif
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GifGridAdapter(
    private val themeColors: ThemeColors,
    private val onGifClick: (KlipyGif) -> Unit
) : RecyclerView.Adapter<GifGridAdapter.GifViewHolder>() {

    private val gifs = ArrayList<KlipyGif>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long {
        if (position !in gifs.indices) return RecyclerView.NO_ID
        var h = 1125899906842597L
        for (c in gifs[position].id) h = 31 * h + c.code
        return h
    }

    fun setData(newGifs: List<KlipyGif>) {
        diffJob?.cancel()
        if (newGifs.size < 50 && gifs.size < 50) {
            val result = DiffUtil.calculateDiff(GifDiffCallback(gifs, newGifs))
            gifs.clear(); gifs.addAll(newGifs)
            result.dispatchUpdatesTo(this)
        } else {
            val oldList = ArrayList(gifs)
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(GifDiffCallback(oldList, newGifs))
                }
                gifs.clear(); gifs.addAll(newGifs)
                result.dispatchUpdatesTo(this@GifGridAdapter)
            }
        }
    }

    override fun getItemCount(): Int = gifs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifViewHolder {
        val cell = GifCell(parent.context, themeColors)
        val vh = GifViewHolder(cell)
        cell.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                gifs.getOrNull(pos)?.let { onGifClick(it) }
            }
        }
        return vh
    }

    override fun onBindViewHolder(holder: GifViewHolder, position: Int) {
        holder.cell.setGif(gifs[position])
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    class GifViewHolder(val cell: GifCell) : RecyclerView.ViewHolder(cell)

    private class GifDiffCallback(
        private val old: List<KlipyGif>,
        private val new: List<KlipyGif>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].id == new[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }
}
