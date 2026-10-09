package com.mezon.mobile.home

internal class BuzzState {
    enum class Reception {
        DUPLICATE, RECEIVED, BADGE_ADDED;
        val shouldPlaySound: Boolean get() = this != DUPLICATE
    }

    private data class Mark(val clanId: Long, val messages: MutableMap<Long, Long> = HashMap())
    private val marks = HashMap<Long, Mark>()
    private val processed = LinkedHashSet<Pair<Long, Long>>()
    private val readCursors = HashMap<Long, Long>()

    @Synchronized
    fun receive(clanId: Long, channelId: Long, topicId: Long, messageId: Long, isViewing: Boolean): Reception {
        val targetId = topicId.takeIf { it != 0L } ?: channelId
        if (messageId != 0L) {
            if (!processed.add(targetId to messageId)) return Reception.DUPLICATE
            if (processed.size > 200) processed.remove(processed.first())
        }
        val alreadySeen = messageId != 0L && readCursors[targetId]?.let {
            (messageId shr 22) <= (it shr 22)
        } == true
        if (!isViewing && !alreadySeen) {
            val badgeAdded = !marks.containsKey(channelId)
            val mark = marks.getOrPut(channelId) { Mark(clanId) }
            val previous = mark.messages[targetId]
            if (previous == null || (messageId shr 22) >= (previous shr 22)) {
                mark.messages[targetId] = messageId
            }
            if (badgeAdded) return Reception.BADGE_ADDED
        }
        return Reception.RECEIVED
    }

    @Synchronized
    fun hasBuzz(channelId: Long): Boolean = marks.containsKey(channelId)

    @Synchronized
    fun clearTarget(targetId: Long, seenMessageId: Long? = null): Set<Long> {
        if (seenMessageId != null && seenMessageId > 0L) {
            val previous = readCursors[targetId]
            if (previous == null || (seenMessageId shr 22) > (previous shr 22)) {
                readCursors[targetId] = seenMessageId
            }
        }
        val changed = LinkedHashSet<Long>()
        val iterator = marks.iterator()
        while (iterator.hasNext()) {
            val (channelId, mark) = iterator.next()
            val messageId = mark.messages[targetId] ?: continue
            if (seenMessageId != null && (seenMessageId shr 22) < (messageId shr 22)) continue
            mark.messages.remove(targetId)
            if (mark.messages.isEmpty()) {
                iterator.remove()
                changed.add(channelId)
            }
        }
        return changed
    }

    @Synchronized
    fun clearRows(targetIds: Set<Long> = emptySet(), matches: (clanId: Long, channelId: Long) -> Boolean): Set<Long> {
        val changed = LinkedHashSet<Long>()
        val iterator = marks.iterator()
        while (iterator.hasNext()) {
            val (channelId, mark) = iterator.next()
            if (!matches(mark.clanId, channelId)) {
                if (targetIds.isEmpty()) continue
                mark.messages.keys.removeAll(targetIds)
                if (mark.messages.isNotEmpty()) continue
            }
            iterator.remove()
            changed.add(channelId)
        }
        return changed
    }

    @Synchronized
    fun reset() {
        marks.clear()
        processed.clear()
        readCursors.clear()
    }
}
