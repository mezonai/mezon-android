package com.mezon.mobile.home.voice

import java.util.Collections

internal class VoiceSpeakerOrder(
    private val holdMs: Long = 5_000L,
    private val reorderIntervalMs: Long = 1_500L
) {
    private var speaking = emptySet<String>()
    private val heldUntil = HashMap<String, Long>()
    private var lastReorderAtMs: Long? = null
    private var orderedKeys = emptyList<String>()

    fun reorderDelayMs(nowMs: Long): Long = lastReorderAtMs?.let {
        (it + reorderIntervalMs - nowMs).coerceAtLeast(0L)
    } ?: 0L

    fun order(
        items: List<ParticipantInfo>, nowMs: Long,
        key: (ParticipantInfo) -> String, visibleKeys: Set<String>?
    ): List<ParticipantInfo> {
        val remaining = items.associateByTo(LinkedHashMap(), key)
        val stable = ArrayList<ParticipantInfo>(items.size)
        for (itemKey in orderedKeys) remaining.remove(itemKey)?.let(stable::add)
        stable.addAll(remaining.values)
        val reorder = reorderDelayMs(nowMs) == 0L
        val regroup = reorder && visibleKeys == null
        val groups = Array(if (regroup) 4 else 2) { ArrayList<ParticipantInfo>() }
        for (item in stable) {
            val group = if (regroup) priority(item, nowMs) else if (item.isScreenShare) 0 else 1
            groups[group].add(item)
        }
        if (reorder) lastReorderAtMs = nowMs
        val result = groups.flatMapTo(ArrayList<ParticipantInfo>(items.size)) { it }
        if (reorder && visibleKeys != null) promoteHiddenSpeakers(result, nowMs, visibleKeys, key)
        orderedKeys = result.map(key)
        return result
    }

    private fun promoteHiddenSpeakers(
        items: MutableList<ParticipantInfo>, nowMs: Long, visibleKeys: Set<String>,
        key: (ParticipantInfo) -> String
    ) {
        val tiles = items.indices.filterNot { items[it].isScreenShare }
        val shown = tiles.filter { key(items[it]) in visibleKeys }
        val shownIdentities = shown.mapTo(HashSet()) { items[it].identity }
        val hiddenSpeakers = tiles
            .filter { items[it].identity.let { id -> id in speaking && id !in shownIdentities } }
            .distinctBy { items[it].identity }
        val quietSlots = shown
            .filter { items[it].identity.let { id -> id !in speaking && !isHeld(id, nowMs) } }
            .asReversed()
        for ((from, to) in hiddenSpeakers.zip(quietSlots)) Collections.swap(items, from, to)
    }

    fun updateSpeaking(ids: Set<String>, nowMs: Long) {
        for (identity in speaking - ids) heldUntil[identity] = nowMs + holdMs
        for (identity in ids) heldUntil.remove(identity)
        speaking = ids.toSet()
    }

    fun retainMembers(identities: Set<String>, nowMs: Long) {
        speaking = speaking.intersect(identities)
        heldUntil.entries.removeAll { it.key !in identities || it.value <= nowMs }
    }

    private fun priority(item: ParticipantInfo, nowMs: Long): Int = when {
        item.isScreenShare -> 0
        item.identity in speaking -> 1
        isHeld(item.identity, nowMs) -> 2
        else -> 3
    }

    private fun isHeld(identity: String, nowMs: Long) = (heldUntil[identity] ?: Long.MIN_VALUE) > nowMs

    fun nextExpiryMs(nowMs: Long): Long? = heldUntil.values.filter { it > nowMs }.minOrNull()

    fun clear() {
        speaking = emptySet()
        heldUntil.clear()
        lastReorderAtMs = null
        orderedKeys = emptyList()
    }
}
