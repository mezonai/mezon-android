package com.mezon.mobile.home.chat

import com.mezon.mezon.api.MessageReaction
import java.util.UUID

internal class OptimisticReactions {
    data class Operation(val reaction: MessageReaction, val id: UUID = UUID.randomUUID())
    private data class Key(val scope: Long, val message: Long, val sender: Long, val emoji: Long)
    private data class Pending(val operation: Operation, var received: Boolean = false, var finished: Boolean = false)
    private data class State(var baseCount: Int, val operations: MutableList<Pending> = mutableListOf()) {
        fun count(): Int = operations.fold(baseCount) { count, pending ->
            if (pending.operation.reaction.action) 0 else count + 1
        }

        fun commitReceived() {
            while (operations.firstOrNull()?.let { it.received && it.finished } == true) {
                val first = operations.removeAt(0)
                baseCount = if (first.operation.reaction.action) 0 else baseCount + 1
            }
        }
    }

    private val states = mutableMapOf<Key, State>()
    private fun key(reaction: MessageReaction) = Key(
        if (reaction.topicId != 0L) reaction.topicId else reaction.channelId,
        reaction.messageId, reaction.senderId, reaction.emojiId
    )

    fun begin(operation: Operation, baseCount: Int): Int {
        val state = states.getOrPut(key(operation.reaction)) { State(baseCount) }
        state.operations.add(Pending(operation))
        return state.count()
    }

    fun finish(operation: Operation, success: Boolean): Int? {
        val key = key(operation.reaction)
        val state = states[key] ?: return null
        val pending = state.operations.firstOrNull { it.operation.id == operation.id } ?: return null
        if (success) {
            pending.finished = true
        } else {
            val replacement = if (pending.received) state.operations.firstOrNull {
                it !== pending && !it.received && it.operation.reaction.action == operation.reaction.action
            } else null
            if (replacement != null) replacement.received = true
            if (pending.received && replacement == null) {
                pending.finished = true
            } else {
                state.operations.remove(pending)
            }
        }
        return settle(key, state)
    }

    fun receive(reaction: MessageReaction): Int? {
        val key = key(reaction)
        val state = states[key] ?: return null
        val pending = state.operations.firstOrNull { !it.received && it.operation.reaction.action == reaction.action }
        if (pending == null) {
            states.remove(key)
            return null
        }
        pending.received = true
        return settle(key, state)
    }

    fun contains(operation: Operation): Boolean = states[key(operation.reaction)]?.operations
        ?.any { it.operation.id == operation.id } == true

    private fun settle(key: Key, state: State): Int {
        state.commitReceived()
        if (state.operations.isEmpty()) states.remove(key)
        return state.count()
    }
}
