package com.mezon.mobile.home.voice

/** Call-owned state; receiving events does not depend on a visible voice screen. */
internal class VoiceBreakoutState {
    sealed interface Signal {
        data class Assigned(val channelId: Long) : Signal
        data class Choose(val channelIds: List<Long>) : Signal
        data object Return : Signal
    }

    data class Invitation(val revision: Long, val signal: Signal, val parent: VoiceInfo)
    data class Snapshot(val current: VoiceInfo?, val parent: VoiceInfo?, val invitation: Invitation?, val accepted: Long?)

    private var current: VoiceInfo? = null
    private var parent: VoiceInfo? = null
    private var revision = 0L
    private var invitation: Invitation? = null
    private var accepted: Long? = null
    private val knownParents = HashSet<Long>()

    @Synchronized fun begin(info: VoiceInfo) {
        if (current?.channelId == info.channelId && current?.clanId == info.clanId) return
        end()
        current = info
    }

    @Synchronized fun end() {
        revision++
        current = null
        parent = null
        invitation = null
        accepted = null
        knownParents.clear()
    }

    @Synchronized fun receive(channelId: Long, hasToken: Boolean, selfSelectIds: List<Long>) {
        val source = current?.takeIf { it.clanId != 0L } ?: return
        val root = parent ?: source
        val choices = selfSelectIds.filter { it > 0 && it != root.channelId }.distinct()
        val signal = when {
            choices.isNotEmpty() -> Signal.Choose(choices)
            channelId <= 0 -> return
            channelId == root.channelId -> {
                if (source.channelId == root.channelId) { dismiss(); return }
                Signal.Return
            }
            !hasToken || channelId == source.channelId || channelId in knownParents -> return
            else -> Signal.Assigned(channelId)
        }
        if (invitation?.signal == signal) return
        knownParents.add(root.channelId)
        invitation = Invitation(++revision, signal, root)
        accepted = null
    }

    @Synchronized fun snapshot() = Snapshot(current, parent, invitation, accepted)

    @Synchronized fun accept(expected: Long, channelId: Long): Boolean {
        val invite = invitation?.takeIf { it.revision == expected } ?: return false
        val valid = when (val signal = invite.signal) {
            is Signal.Assigned -> signal.channelId == channelId
            is Signal.Choose -> channelId in signal.channelIds
            Signal.Return -> channelId == invite.parent.channelId
        }
        if (!valid || accepted != null) return false
        if (channelId == current?.channelId) { dismiss(); return false }
        accepted = channelId
        return true
    }

    @Synchronized fun dismiss(expected: Long? = null) {
        if (expected != null && invitation?.revision != expected) return
        revision++
        invitation = null
        accepted = null
    }

    @Synchronized fun reoffer(expected: Long) {
        if (invitation?.revision == expected) accepted = null
    }

    @Synchronized fun commit(expected: Long, target: VoiceInfo): Boolean {
        val invite = invitation?.takeIf { it.revision == expected && accepted == target.channelId } ?: return false
        parent = invite.parent.takeUnless { it.channelId == target.channelId }
        current = target
        dismiss()
        return true
    }

    @Synchronized fun returnToMain() {
        val root = parent ?: return
        invitation = Invitation(++revision, Signal.Return, root)
        accepted = root.channelId
    }
}
