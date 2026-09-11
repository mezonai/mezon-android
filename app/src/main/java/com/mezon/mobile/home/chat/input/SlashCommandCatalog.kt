package com.mezon.mobile.home.chat.input

import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

object SlashCommandCatalog {
    const val MENU_TYPE_FLASH_MESSAGE = 1
    private const val FRESHNESS_MS = 5 * 60 * 1000L
    private const val FAILURE_RETRY_MS = 15_000L

    private class Entry(val commands: List<SlashCommand>, val fetchedAtMs: Long)

    private val lock = Any()
    private val entries = HashMap<Long, Entry>()
    private val lastAttemptAtMs = HashMap<Long, Long>()

    fun cached(channelId: Long): List<SlashCommand> =
        synchronized(lock) { entries[channelId]?.commands.orEmpty() }

    fun isFresh(channelId: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val entry = synchronized(lock) { entries[channelId] } ?: return false
        return nowMs - entry.fetchedAtMs < FRESHNESS_MS
    }

    suspend fun load(
        channelId: Long,
        api: MezonApi,
        sessionManager: SessionManager,
        ioDispatcher: CoroutineDispatcher,
    ): List<SlashCommand> {
        val now = System.currentTimeMillis()
        if (isFresh(channelId, now)) return cached(channelId)
        val shouldFetch = synchronized(lock) {
            val last = lastAttemptAtMs[channelId]
            if (last != null && now - last < FAILURE_RETRY_MS) {
                false
            } else {
                lastAttemptAtMs[channelId] = now
                true
            }
        }
        if (!shouldFetch) return cached(channelId)
        val fetched = fetch(channelId, api, sessionManager, ioDispatcher) ?: return cached(channelId)
        synchronized(lock) { entries[channelId] = Entry(fetched, System.currentTimeMillis()) }
        return fetched
    }

    private suspend fun fetch(
        channelId: Long,
        api: MezonApi,
        sessionManager: SessionManager,
        ioDispatcher: CoroutineDispatcher,
    ): List<SlashCommand>? {
        val response = try {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.listQuickMenuAccess(session.apiUrl, session.token, channelId, MENU_TYPE_FLASH_MESSAGE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return response.listMenusList
            .map(SlashCommand::fromProto)
            .filter { it.name.isNotBlank() }
    }
}
