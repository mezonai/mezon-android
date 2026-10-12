package com.mezon.mobile.home.voice

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

    fun <T> order(
        items: List<T>, nowMs: Long,
        key: (T) -> String, identity: (T) -> String, isScreenShare: (T) -> Boolean
    ): List<T> {
        val remaining = items.associateByTo(LinkedHashMap(), key)
        val stable = ArrayList<T>(items.size)
        for (itemKey in orderedKeys) remaining.remove(itemKey)?.let(stable::add)
        stable.addAll(remaining.values)
        val reorder = reorderDelayMs(nowMs) == 0L
        val groups = Array(if (reorder) 4 else 2) { ArrayList<T>() }
        for (item in stable) {
            val group = if (reorder) priority(identity(item), isScreenShare(item), nowMs)
                else if (isScreenShare(item)) 0 else 1
            groups[group].add(item)
        }
        if (reorder) lastReorderAtMs = nowMs
        val result = groups.flatMapTo(ArrayList(items.size)) { it }
        orderedKeys = result.map(key)
        return result
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

    fun priority(identity: String, isScreenShare: Boolean, nowMs: Long): Int = when {
        isScreenShare -> 0
        identity in speaking -> 1
        (heldUntil[identity] ?: Long.MIN_VALUE) > nowMs -> 2
        else -> 3
    }

    fun nextExpiryMs(nowMs: Long): Long? = heldUntil.values.filter { it > nowMs }.minOrNull()

    fun clear() {
        speaking = emptySet()
        heldUntil.clear()
        lastReorderAtMs = null
        orderedKeys = emptyList()
    }
}
