package com.mezon.mobile.home.voice

import android.app.Activity
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.chat.ReactionEmojiPickerSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceReactionHandler(
    private val themeColors: ThemeColors,
    private val voiceController: VoiceController,
    private val emojiController: EmojiController,
    private val notificationCenter: NotificationCenter,
    private val channelId: Long,
    private val getActivity: () -> Activity?,
    private val getReactionOverlay: () -> ReactionOverlayView?,
    private val getParticipantGrid: () -> RecyclerListView?,
    private val participants: MutableList<ParticipantInfo>,
    private val reactionStates: MutableMap<String, ParticipantCell.ReactionBadgeType>,
    private val getRoomScope: () -> CoroutineScope?,
    private val getLocalSenderMeta: () -> SenderMeta = { SenderMeta("", null) }
) {
    data class SenderMeta(val name: String, val avatarUrl: String?)

    companion object {
        private const val TAG = "VoiceReactionHandler"
        private const val RAISE_UP_PREFIX = "raising-up:"
        private const val RAISE_DOWN_PREFIX = "raising-down:"
        private const val SENDER_NAME_PREFIX = "sender-name:"
        private const val SENDER_AVATAR_PREFIX = "sender-avatar:"
        private const val DEFAULT_BADGE_DURATION_MS = 3000L
    }

    private fun buildPayload(primary: String): List<String> {
        val meta = getLocalSenderMeta()
        val list = ArrayList<String>(3)
        list.add(primary)
        if (meta.name.isNotBlank()) {
            list.add("$SENDER_NAME_PREFIX${meta.name}")
        }
        val avatar = meta.avatarUrl?.trim().orEmpty()
        if (avatar.isNotEmpty()) {
            list.add("$SENDER_AVATAR_PREFIX$avatar")
        }
        return list
    }

    fun showEmojiReactionPicker() {
        val activity = getActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val sheet = ReactionEmojiPickerSheet(
            context = activity,
            themeColors = themeColors,
            emojiController = emojiController,
            notificationCenter = notificationCenter,
            autoDismiss = false
        ) { emojiId, _ ->
            voiceController.sendVoiceReaction(buildPayload(emojiId.toString()), channelId)
        }
        sheet.show()
    }

    fun showSoundReactionPicker() {
        val sheet = VoiceReactionPickerBottomSheet(
            context = getActivity() ?: return,
            themeColors = themeColors,
            emojiController = emojiController,
            notificationCenter = notificationCenter
        ) { value ->
            voiceController.sendVoiceReaction(buildPayload(value), channelId)
        }
        sheet.show()
    }

    fun showReactionOverlay(emojis: List<String>, senderName: String? = null) {
        getReactionOverlay()?.showEmojis(emojis, senderName)
    }

    fun playSoundReaction(soundValue: String) {
        val source = normalizeSoundSource(soundValue)
        if (source.isBlank()) {
            Log.w(TAG, "playSoundReaction blank source raw=${soundValue.take(80)}")
            return
        }
        try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setOnPreparedListener { start() }
                setOnCompletionListener { mp -> mp.release() }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    true
                }
                setDataSource(source)
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "playSoundReaction failed src=${source.take(120)}", e)
        }
    }

    fun showPerParticipantBadge(senderIdentity: String, emojis: List<String>) {
        val hasRaiseUp = emojis.any { it.startsWith(RAISE_UP_PREFIX) }
        val hasRaiseDown = emojis.any { it.startsWith(RAISE_DOWN_PREFIX) }
        if (hasRaiseUp || hasRaiseDown) {
            clearBadge(senderIdentity)
            return
        }
        val hasSound = emojis.any { it.startsWith("sound:") }
        val type = when {
            hasSound -> ParticipantCell.ReactionBadgeType.SOUND_EFFECT
            else -> ParticipantCell.ReactionBadgeType.NONE
        }
        if (type == ParticipantCell.ReactionBadgeType.NONE) return
        reactionStates[senderIdentity] = type

        val affected = ArrayList<Int>()
        for (i in participants.indices) {
            if (participants[i].identity == senderIdentity) {
                participants[i] = participants[i].copy(reactionBadge = type)
                affected.add(i)
            }
        }
        if (affected.isNotEmpty()) {
            val participantGrid = getParticipantGrid() ?: return
            val count = participantGrid.childCount
            for (i in 0 until count) {
                val child = participantGrid.getChildAt(i) as? ParticipantCell ?: continue
                val pos = participantGrid.getChildAdapterPosition(child)
                if (pos in affected) {
                    child.setReactionBadge(type)
                }
            }
        }

        getRoomScope()?.launch {
            delay(DEFAULT_BADGE_DURATION_MS)
            clearBadge(senderIdentity)
        }
    }

    private fun clearBadge(senderIdentity: String) {
        reactionStates.remove(senderIdentity)
        val clearIndices = ArrayList<Int>()
        for (i in participants.indices) {
            if (participants[i].identity == senderIdentity) {
                participants[i] = participants[i].copy(reactionBadge = ParticipantCell.ReactionBadgeType.NONE)
                clearIndices.add(i)
            }
        }
        if (clearIndices.isNotEmpty()) {
            val participantGrid = getParticipantGrid() ?: return
            val count = participantGrid.childCount
            for (i in 0 until count) {
                val child = participantGrid.getChildAt(i) as? ParticipantCell ?: continue
                if (participantGrid.getChildAdapterPosition(child) in clearIndices) {
                    child.clearReactionBadge()
                }
            }
        }
    }

    private fun normalizeSoundSource(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("/") -> BuildConfig.MEZON_BASE_IMG_URL + value
            else -> value
        }
    }
}
