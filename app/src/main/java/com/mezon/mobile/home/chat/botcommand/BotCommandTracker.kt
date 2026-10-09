package com.mezon.mobile.home.chat.botcommand

import com.mezon.mezon.rtapi.ChannelMessageSend
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.util.MezonSnowflake
import com.mezon.mobile.util.firstReferenceMessageId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class BotFlashCommand(
    val botId: Long,
    val menuName: String,
    val actionMsg: String,
) {
    private val trimmedAction: String
        get() = actionMsg.trim()

    fun stillPrefixes(content: String): Boolean {
        val action = trimmedAction
        if (action.isEmpty()) return false
        return content.trimStart().startsWith(action)
    }

    fun arguments(content: String): String {
        val leading = content.trimStart()
        val action = trimmedAction
        val rest = if (action.isNotEmpty() && leading.startsWith(action)) leading.substring(action.length) else leading
        return rest.trim()
    }
}

sealed class BotCommandStatus {
    data object Waiting : BotCommandStatus()
    data class Answered(val replyMessageId: Long) : BotCommandStatus()
    data object NoResponse : BotCommandStatus()
    data object Failed : BotCommandStatus()
}

data class BotCommandDisplay(
    val menuName: String,
    val arguments: String,
    val botName: String,
    val status: BotCommandStatus,
    val resendable: Boolean,
)

sealed class BotCommandUserAction {
    data class ViewReply(val messageId: Long) : BotCommandUserAction()
    data object Resend : BotCommandUserAction()
    data object Dismiss : BotCommandUserAction()
}

class BotCommandDispatch(
    val botId: Long,
    val botName: String,
    val menuName: String,
    val arguments: String,
    val resendable: Boolean,
    val prepare: suspend () -> ChannelMessageSend,
)

class BotCommandTracker(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val send: suspend (ChannelMessageSend) -> Long,
) {

    class Entry(
        val rowId: Long,
        val botId: Long,
        val botName: String,
        val menuName: String,
        val arguments: String,
        val resendable: Boolean,
        val createdAtMs: Long,
        var status: BotCommandStatus,
        var commandMessageId: Long,
        var timedOut: Boolean,
        var message: ChannelMessageSend?,
    ) {
        val display: BotCommandDisplay
            get() = BotCommandDisplay(
                menuName = menuName,
                arguments = arguments,
                botName = botName,
                status = status,
                resendable = resendable && message != null,
            )

        val commandText: String
            get() = if (arguments.isEmpty()) "/$menuName" else "/$menuName $arguments"

        val awaitsReply: Boolean
            get() = commandMessageId != 0L &&
                (status == BotCommandStatus.Waiting || status == BotCommandStatus.NoResponse)
    }

    private val mutableEntries = ArrayList<Entry>()
    private val consumedReplyIds = HashSet<Long>()
    private val inFlightByBot = HashMap<Long, Int>()

    val entries: List<Entry>
        get() = mutableEntries

    var onChange: (() -> Unit)? = null

    fun display(rowId: Long): BotCommandDisplay? =
        mutableEntries.firstOrNull { it.rowId == rowId }?.display

    fun isCommandRow(rowId: Long): Boolean =
        mutableEntries.any { it.rowId == rowId }

    fun dispatch(request: BotCommandDispatch) {
        beginFlight(request.botId)
        scope.launch(dispatcher) {
            var prepared: ChannelMessageSend? = null
            val commandMessageId = try {
                val message = request.prepare()
                prepared = message
                send(message)
            } catch (e: CancellationException) {
                endFlight(request.botId)
                throw e
            } catch (_: Exception) {
                null
            }
            endFlight(request.botId)
            val entry = Entry(
                rowId = MezonSnowflake.generate(),
                botId = request.botId,
                botName = request.botName,
                menuName = request.menuName,
                arguments = request.arguments,
                resendable = request.resendable,
                createdAtMs = System.currentTimeMillis(),
                status = if (commandMessageId == null) BotCommandStatus.Failed else BotCommandStatus.Waiting,
                commandMessageId = commandMessageId ?: 0L,
                timedOut = false,
                message = if (request.resendable) prepared else null,
            )
            mutableEntries.add(entry)
            if (commandMessageId != null) scheduleTimeout(entry.rowId, commandMessageId)
            onChange?.invoke()
        }
    }

    fun resend(rowId: Long) {
        val index = mutableEntries.indexOfFirst { it.rowId == rowId }
        if (index < 0) return
        val old = mutableEntries[index]
        val message = old.message ?: return
        if (old.status != BotCommandStatus.NoResponse && old.status != BotCommandStatus.Failed) return
        old.status = BotCommandStatus.Waiting
        onChange?.invoke()

        beginFlight(old.botId)
        scope.launch(dispatcher) {
            val commandMessageId = try {
                send(message)
            } catch (e: CancellationException) {
                endFlight(old.botId)
                throw e
            } catch (_: Exception) {
                null
            }
            endFlight(old.botId)
            val currentIndex = mutableEntries.indexOfFirst { it.rowId == rowId }
            if (currentIndex < 0) return@launch
            if (commandMessageId == null) {
                mutableEntries[currentIndex].status = BotCommandStatus.Failed
                onChange?.invoke()
                return@launch
            }
            val replacement = Entry(
                rowId = MezonSnowflake.generate(),
                botId = old.botId,
                botName = old.botName,
                menuName = old.menuName,
                arguments = old.arguments,
                resendable = old.resendable,
                createdAtMs = System.currentTimeMillis(),
                status = BotCommandStatus.Waiting,
                commandMessageId = commandMessageId,
                timedOut = false,
                message = message,
            )
            mutableEntries.removeAt(currentIndex)
            mutableEntries.add(replacement)
            scheduleTimeout(replacement.rowId, commandMessageId)
            onChange?.invoke()
        }
    }

    fun dismiss(rowId: Long) {
        val index = mutableEntries.indexOfFirst { it.rowId == rowId }
        if (index < 0) return
        mutableEntries.removeAt(index)
        onChange?.invoke()
    }

    fun resolveReplies(messages: List<MessageEntity>): Boolean {
        val pending = mutableEntries.filter { it.awaitsReply }
        if (pending.isEmpty()) return false
        val candidates = messages.filter { candidate ->
            candidate.id > 0L && !candidate.isMe && !isCommandRow(candidate.id) &&
                candidate.id !in consumedReplyIds
        }
        if (candidates.isEmpty()) return false

        var changed = false
        for (entry in pending) {
            val reply = candidates.firstOrNull {
                it.id !in consumedReplyIds && firstReferenceMessageId(it.content) == entry.commandMessageId
            } ?: continue
            answer(entry, reply.id)
            changed = true
        }

        val ordered = pending.sortedBy { it.timedOut }
        for (entry in ordered) {
            if (!entry.awaitsReply) continue
            if (entry.timedOut && (inFlightByBot[entry.botId] ?: 0) > 0) continue
            val reply = candidates.firstOrNull { candidate ->
                candidate.id !in consumedReplyIds &&
                    firstReferenceMessageId(candidate.content) == 0L &&
                    isFromBot(candidate, entry.botId) &&
                    candidate.id > entry.commandMessageId
            } ?: continue
            answer(entry, reply.id)
            changed = true
        }
        return changed
    }

    private fun isFromBot(candidate: MessageEntity, botId: Long): Boolean =
        candidate.senderId == botId || (candidate.senderId == 0L && candidate.isEphemeral)

    private fun answer(entry: Entry, replyId: Long) {
        entry.status = BotCommandStatus.Answered(replyId)
        consumedReplyIds.add(replyId)
    }

    private fun scheduleTimeout(rowId: Long, commandMessageId: Long) {
        scope.launch(dispatcher) {
            delay(RESPONSE_TIMEOUT_MS)
            val entry = mutableEntries.firstOrNull { it.rowId == rowId } ?: return@launch
            if (entry.commandMessageId != commandMessageId || entry.status != BotCommandStatus.Waiting) return@launch
            entry.timedOut = true
            entry.status = BotCommandStatus.NoResponse
            onChange?.invoke()
        }
    }

    private fun beginFlight(botId: Long) {
        inFlightByBot[botId] = (inFlightByBot[botId] ?: 0) + 1
    }

    private fun endFlight(botId: Long) {
        val remaining = (inFlightByBot[botId] ?: 0) - 1
        if (remaining > 0) inFlightByBot[botId] = remaining else inFlightByBot.remove(botId)
    }

    private companion object {
        const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
