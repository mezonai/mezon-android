package com.mezon.mobile.home.voice

/** Call under VoiceController's lock. Rows belong to users; membership belongs to peers. */
internal class VoicePeerPresence {
    data class Key(val clan: Long, val channel: Long, val user: Long)
    data class Entry(val channel: Long, val user: Long, val peer: Int)
    private val peers = HashMap<Key, MutableSet<Int>>()
    private val revisions = HashMap<Long, Long>()

    fun revision(clan: Long): Long = revisions[clan] ?: 0L
    private fun changed(clan: Long) { revisions[clan] = revision(clan) + 1 }
    fun peerIds(clan: Long, channel: Long, user: Long): List<Int> =
        peers[Key(clan, channel, user)]?.sorted().orEmpty()

    fun joined(clan: Long, channel: Long, user: Long, peer: Int) {
        changed(clan)
        if (peer > 0) peers.getOrPut(Key(clan, channel, user)) { HashSet() }.add(peer)
    }

    /** Unknown/duplicate leaves cannot remove a different known connection. */
    fun left(clan: Long, channel: Long, user: Long, peer: Int): Boolean {
        changed(clan)
        val key = Key(clan, channel, user)
        peers[key]?.remove(peer)
        if (!peers[key].isNullOrEmpty()) return false
        peers.remove(key)
        return true
    }

    fun removeRoom(clan: Long, channel: Long) {
        changed(clan)
        peers.keys.removeAll { it.clan == clan && it.channel == channel }
    }

    fun removeClan(clan: Long) {
        changed(clan)
        peers.keys.removeAll { it.clan == clan }
    }

    fun replaceClan(clan: Long, entries: List<Entry>) {
        val next = HashMap<Key, MutableSet<Int>>()
        for (entry in entries) {
            val ids = next.getOrPut(Key(clan, entry.channel, entry.user)) { HashSet() }
            if (entry.peer > 0) ids.add(entry.peer)
        }
        // Legacy snapshots cannot replace IDs learned from realtime events.
        for ((key, ids) in next) if (ids.isEmpty()) ids.addAll(peers[key].orEmpty())
        removeClan(clan)
        peers.putAll(next)
    }
}
