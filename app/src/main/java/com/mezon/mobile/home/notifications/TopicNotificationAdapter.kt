package com.mezon.mobile.home.notifications

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.SdTopicEntity
import kotlinx.coroutines.*

class TopicNotificationAdapter(
    private val theme: ThemeColors
) : RecyclerView.Adapter<TopicNotificationAdapter.TopicViewHolder>() {

    private val items = ArrayList<SdTopicEntity>()
    private val memberCache = HashMap<Long, ClanMember>()
    private var adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null

    init { setHasStableIds(true) }

    fun updateMemberCache(resolvedMembers: Map<Long, ClanMember>) {
        memberCache.putAll(resolvedMembers)
    }

    fun setData(list: List<SdTopicEntity>, resolvedMembers: Map<Long, ClanMember> = emptyMap(), isTabChange: Boolean = false) {
        diffJob?.cancel()
        val oldList = ArrayList(items)
        if (isTabChange || (oldList.isEmpty() && list.isNotEmpty())) {
            items.clear()
            items.addAll(list)
            memberCache.clear()
            memberCache.putAll(resolvedMembers)
            notifyDataSetChanged()
            return
        }
        resolvedMembers.forEach { (id, member) -> memberCache[id] = member }
        diffJob = adapterScope.launch {
            val diffResult = withContext(Dispatchers.Default) {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldList.size
                    override fun getNewListSize() = list.size
                    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                        oldList[oldPos].id == list[newPos].id
                    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                        oldList[oldPos] == list[newPos]
                })
            }
            items.clear()
            items.addAll(list)
            diffResult.dispatchUpdatesTo(this@TopicNotificationAdapter)
            diffJob = null
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (!adapterScope.isActive) {
            adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        diffJob = null
        adapterScope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun getItemId(position: Int): Long = items[position].id

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): TopicViewHolder {
        val cell = TopicNotificationCell(parent.context, theme).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return TopicViewHolder(cell)
    }

    override fun onBindViewHolder(holder: TopicViewHolder, position: Int) {
        val item = items[position]
        holder.cell.memberResolver = { topic ->
            val senderId = topic.senderIdForAvatar()
            if (senderId == 0L) null else memberCache[senderId]
        }
        holder.cell.update(0, item)
    }

    class TopicViewHolder(val cell: TopicNotificationCell) : RecyclerView.ViewHolder(cell)
}
