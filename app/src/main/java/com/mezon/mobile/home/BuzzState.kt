package com.mezon.mobile.home

internal class BuzzState {
    enum class Reception {
        DUPLICATE, RECEIVED, BADGE_ADDED;
        val shouldPlaySound: Boolean get() = this != DUPLICATE
    }

    private data class BuzzMessage(val id: Long, val timestampSeconds: Long)
    private data class Mark(val clanId: Long, val messages: MutableMap<Long, BuzzMessage> = HashMap())
    private val marks = HashMap<Long, Mark>()
    private val processed = LinkedHashSet<Pair<Long, Long>>()
    private val readCursors = HashMap<Long, Long>()
    private val readTimestampCursors = HashMap<Long, Long>()

    @Synchronized
    fun receive(clanId: Long, channelId: Long, topicId: Long, messageId: Long, isViewing: Boolean,
                timestampSeconds: Long = 0L): Reception {
        val targetId = topicId.takeIf { it != 0L } ?: channelId
        if (messageId != 0L) {
            if (!processed.add(targetId to messageId)) return Reception.DUPLICATE
            if (processed.size > 200) processed.remove(processed.first())
        }
        val seenById = messageId != 0L && readCursors[targetId]?.let {
            (messageId shr 22) <= (it shr 22)
        } == true
        val seenByTimestamp = timestampSeconds > 0L && readTimestampCursors[targetId]?.let {
            timestampSeconds <= it
        } == true
        if (!isViewing && !seenById && !seenByTimestamp) {
            val badgeAdded = !marks.containsKey(channelId)
            val mark = marks.getOrPut(channelId) { Mark(clanId) }
            val previous = mark.messages[targetId]
            if (previous == null || (messageId shr 22) >= (previous.id shr 22)) {
                mark.messages[targetId] = BuzzMessage(messageId, timestampSeconds)
            }
            if (badgeAdded) return Reception.BADGE_ADDED
        }
        return Reception.RECEIVED
    }

    @Synchronized
    fun hasBuzz(channelId: Long): Boolean = marks.containsKey(channelId)

    @Synchronized
    fun clearTarget(targetId: Long, seenMessageId: Long? = null, seenTimestampSeconds: Long = 0L): Set<Long> {
        val idCursor = seenMessageId?.takeIf { it > 0L }
        if (idCursor != null) {
            val previous = readCursors[targetId]
            if (previous == null || (idCursor shr 22) > (previous shr 22)) {
                readCursors[targetId] = idCursor
            }
        } else if (seenTimestampSeconds > 0L) {
            readTimestampCursors[targetId] = maxOf(readTimestampCursors[targetId] ?: 0L, seenTimestampSeconds)
        }
        val changed = LinkedHashSet<Long>()
        val iterator = marks.iterator()
        while (iterator.hasNext()) {
            val (channelId, mark) = iterator.next()
            val message = mark.messages[targetId] ?: continue
            val covered = when {
                idCursor != null -> (message.id shr 22) <= (idCursor shr 22)
                seenTimestampSeconds > 0L -> message.timestampSeconds > 0L &&
                    message.timestampSeconds <= seenTimestampSeconds
                else -> seenMessageId == null
            }
            if (!covered) continue
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
        readTimestampCursors.clear()
    }
}
