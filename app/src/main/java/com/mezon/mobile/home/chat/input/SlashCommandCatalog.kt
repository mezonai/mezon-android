package com.mezon.mobile.home.chat.input

import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

object SlashCommandCatalog {
    const val MENU_TYPE_FLASH_MESSAGE = 1
    const val MENU_TYPE_QUICK_MENU = 2
    private const val FRESHNESS_MS = 5 * 60 * 1000L
    private const val FAILURE_RETRY_MS = 15_000L

    private data class Key(val channelId: Long, val menuType: Int)

    private class Entry(val commands: List<SlashCommand>, val fetchedAtMs: Long)

    private val lock = Any()
    private val entries = HashMap<Key, Entry>()
    private val lastAttemptAtMs = HashMap<Key, Long>()
    private val revisions = HashMap<Key, Int>()

    fun cached(channelId: Long, menuType: Int = MENU_TYPE_FLASH_MESSAGE): List<SlashCommand> =
        cachedOrNull(channelId, menuType).orEmpty()

    fun cachedOrNull(channelId: Long, menuType: Int = MENU_TYPE_FLASH_MESSAGE): List<SlashCommand>? =
        synchronized(lock) { entries[Key(channelId, menuType)]?.commands }

    fun isFresh(
        channelId: Long,
        menuType: Int = MENU_TYPE_FLASH_MESSAGE,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val entry = synchronized(lock) { entries[Key(channelId, menuType)] } ?: return false
        return nowMs - entry.fetchedAtMs < FRESHNESS_MS
    }

    fun replace(channelId: Long, menuType: Int, commands: List<SlashCommand>) {
        val key = Key(channelId, menuType)
        synchronized(lock) {
            revisions[key] = (revisions[key] ?: 0) + 1
            entries[key] = Entry(commands.filter { it.name.isNotBlank() }, System.currentTimeMillis())
            lastAttemptAtMs.remove(key)
        }
    }

    suspend fun load(
        channelId: Long,
        api: MezonApi,
        sessionManager: SessionManager,
        ioDispatcher: CoroutineDispatcher,
        menuType: Int = MENU_TYPE_FLASH_MESSAGE,
    ): List<SlashCommand> {
        val key = Key(channelId, menuType)
        val now = System.currentTimeMillis()
        if (isFresh(channelId, menuType, now)) return cached(channelId, menuType)
        val startRevision = synchronized(lock) {
            val last = lastAttemptAtMs[key]
            if (last != null && now - last < FAILURE_RETRY_MS) {
                null
            } else {
                lastAttemptAtMs[key] = now
                revisions[key] ?: 0
            }
        } ?: return cached(channelId, menuType)
        val fetched = fetch(channelId, menuType, api, sessionManager, ioDispatcher)
            ?: return cached(channelId, menuType)
        return synchronized(lock) {
            if ((revisions[key] ?: 0) != startRevision) {
                entries[key]?.commands ?: fetched
            } else {
                entries[key] = Entry(fetched, System.currentTimeMillis())
                fetched
            }
        }
    }

    private suspend fun fetch(
        channelId: Long,
        menuType: Int,
        api: MezonApi,
        sessionManager: SessionManager,
        ioDispatcher: CoroutineDispatcher,
    ): List<SlashCommand>? {
        val response = try {
            sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.listQuickMenuAccess(session.apiUrl, session.token, channelId, menuType)
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
