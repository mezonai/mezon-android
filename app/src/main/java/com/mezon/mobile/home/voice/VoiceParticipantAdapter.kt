package com.mezon.mobile.home.voice

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

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
    val contentAspectRatio: Float = 16f / 9f,
    val reactionBadge: ParticipantCell.ReactionBadgeType = ParticipantCell.ReactionBadgeType.NONE
)

class VoiceParticipantAdapter(
    private val themeColors: ThemeColors,
    private val getParticipants: () -> List<ParticipantInfo>,
    private val getRoom: () -> Room?,
    private val onScreenShareClick: (ParticipantInfo) -> Unit,
    private val onParticipantLongPress: (ParticipantInfo) -> Unit,
    private val itemKeyProvider: (ParticipantInfo) -> String,
    private val isCompactMode: () -> Boolean
) : RecyclerView.Adapter<VoiceParticipantAdapter.ParticipantVH>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = getParticipants().size

    override fun getItemId(position: Int): Long {
        val item = getParticipants()[position]
        return itemKeyProvider(item).hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantVH {
        val cell = ParticipantCell(parent.context, themeColors).apply {
            layoutParams = createLayoutParams(isCompactMode())
        }
        return ParticipantVH(cell)
    }

    override fun onBindViewHolder(holder: ParticipantVH, position: Int) {
        val participant = getParticipants()[position]
        holder.cell.layoutParams = createLayoutParams(isCompactMode())
        holder.cell.setParticipant(
            participant.identity.toLongOrNull() ?: 0L,
            participant.name,
            participant.username,
            participant.avatarUrl,
            participant.isMuted,
            participant.isSpeaking,
            participant.hasVideo,
            participant.isScreenShare
        )
        holder.cell.setReactionBadge(participant.reactionBadge)

        val room = getRoom()
        if (participant.videoTrack != null && room != null) {
            holder.cell.attachVideoTrack(room, participant.videoTrack)
        } else {
            holder.cell.detachVideoTrack()
        }

        holder.cell.setOnClickListener {
            if (participant.isScreenShare && participant.videoTrack != null) {
                onScreenShareClick(participant)
            }
        }
        holder.cell.setOnLongClickListener {
            if (participant.isScreenShare) {
                return@setOnLongClickListener false
            }
            onParticipantLongPress(participant)
            true
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

    class ParticipantVH(val cell: ParticipantCell) : RecyclerView.ViewHolder(cell)
}
