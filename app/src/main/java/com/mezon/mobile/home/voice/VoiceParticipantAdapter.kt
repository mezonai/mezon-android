package com.mezon.mobile.home.voice

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.voice.sfu.SfuRole
import org.webrtc.VideoTrack

data class ParticipantInfo(
    val identity: String,
    val name: String,
    val username: String = "",
    val avatarUrl: String? = null,
    val isMuted: Boolean,
    val isSpeaking: Boolean,
    val hasVideo: Boolean,
    val videoTrack: VideoTrack? = null,
    val isScreenShare: Boolean = false,
    val mirrorVideo: Boolean = false,
    val contentAspectRatio: Float = 16f / 9f,
    val role: SfuRole? = null,
    val reactionBadge: ParticipantCell.ReactionBadgeType = ParticipantCell.ReactionBadgeType.NONE
)

class VoiceParticipantAdapter(
    private val themeColors: ThemeColors,
    private val getParticipants: () -> List<ParticipantInfo>,
    private val onScreenShareClick: (ParticipantInfo) -> Unit,
    private val onParticipantLongPress: (ParticipantInfo) -> Unit,
    private val itemKeyProvider: (ParticipantInfo) -> String,
    private val isCompactMode: () -> Boolean
) : RecyclerView.Adapter<VoiceParticipantAdapter.ParticipantVH>() {

    private var items: List<ParticipantInfo> = ArrayList(getParticipants())

    init {
        setHasStableIds(true)
        registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = refreshItems()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = refreshItems()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = refreshItems()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refreshItems()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refreshItems()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = refreshItems()
        })
    }

    private fun refreshItems() {
        items = ArrayList(getParticipants())
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        val item = items.getOrNull(position) ?: return RecyclerView.NO_ID
        return itemKeyProvider(item).hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantVH {
        val cell = ParticipantCell(parent.context, themeColors).apply {
            layoutParams = createLayoutParams(isCompactMode())
        }
        val holder = ParticipantVH(cell)
        cell.setOnClickListener {
            val participant = holder.participant ?: return@setOnClickListener
            if (participant.isScreenShare && participant.videoTrack != null) {
                onScreenShareClick(participant)
            }
        }
        cell.setOnLongClickListener {
            val participant = holder.participant ?: return@setOnLongClickListener false
            if (participant.isScreenShare) {
                return@setOnLongClickListener false
            }
            onParticipantLongPress(participant)
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: ParticipantVH, position: Int) {
        val participant = items[position]
        holder.participant = participant
        holder.cell.layoutParams = createLayoutParams(isCompactMode())
        holder.cell.setParticipant(
            participant.identity.toLongOrNull() ?: 0L,
            participant.name,
            participant.username,
            participant.avatarUrl,
            participant.isMuted,
            participant.isSpeaking,
            participant.hasVideo,
            participant.isScreenShare,
            participant.role == SfuRole.AUDIENCE
        )
        holder.cell.setReactionBadge(participant.reactionBadge)

        if (participant.videoTrack != null) {
            holder.cell.attachVideoTrack(participant.videoTrack, participant.mirrorVideo)
        } else {
            holder.cell.detachVideoTrack()
        }
    }

    override fun onViewRecycled(holder: ParticipantVH) {
        holder.cell.detachVideoTrack()
    }

    private fun createLayoutParams(compactMode: Boolean): RecyclerView.LayoutParams {
        val height = if (compactMode) LayoutHelper.dp(118) else LayoutHelper.dp(150)
        val margin = if (compactMode) LayoutHelper.dp(2) else LayoutHelper.dp(5)
        return RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            height
        ).apply {
            setMargins(margin, margin, margin, margin)
        }
    }

    class ParticipantVH(val cell: ParticipantCell) : RecyclerView.ViewHolder(cell) {
        var participant: ParticipantInfo? = null
    }
}
