package ai.mezon.app.home.chat

import ai.mezon.app.di.IoDispatcher
import ai.mezon.app.network.ApiCacheTracker
import ai.mezon.app.network.MezonApi
import ai.mezon.app.network.NetworkMonitor
import ai.mezon.app.network.apiCacheKey
import ai.mezon.app.session.SessionManager
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PAGE_SIZE = 50
private const val DIRECTION_BEFORE = 1
private const val DIRECTION_AFTER = 2

@Singleton
class ChatRepository @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val messageStore: MessageStore,
    private val networkMonitor: NetworkMonitor,
    private val cacheTracker: ApiCacheTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "ChatRepository"
    }

    suspend fun loadMessages(channelId: Long, clanId: Long = 0L): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val cached = messageStore.loadFromDb(channelId)
                val cacheKey = apiCacheKey("fetchMessages", clanId, channelId)

                if (!networkMonitor.isOnline.value) {
                    Log.d(TAG, "Offline — showing ${cached.size} cached messages for channel $channelId")
                    return@runCatching
                }

                if (cached.isNotEmpty() && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                    Log.d(TAG, "Cache still valid for channel $channelId, skipping API call")
                    return@runCatching
                }

                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val newestCached = cached.lastOrNull()

                    if (newestCached != null) {
                        val response = api.listChannelMessages(
                            apiUrl = session.apiUrl,
                            token = session.token,
                            channelId = channelId,
                            clanId = clanId,
                            messageId = newestCached.id,
                            direction = DIRECTION_AFTER,
                            limit = PAGE_SIZE
                        )
                        val newer = response.messagesList
                            .map { it.toMessageEntity(currentUserId) }
                            .sortedBy { it.timestampSeconds }

                        if (newer.isNotEmpty()) {
                            Log.d(TAG, "Fetched ${newer.size} new messages since last cache for channel $channelId")
                            messageStore.appendMessages(
                                channelId = channelId,
                                newer = newer,
                                hasMoreBottom = response.messagesList.size >= PAGE_SIZE
                            )
                        }
                    } else {
                        val response = api.listChannelMessages(
                            apiUrl = session.apiUrl,
                            token = session.token,
                            channelId = channelId,
                            clanId = clanId,
                            limit = PAGE_SIZE
                        )
                        val messages = response.messagesList
                            .map { it.toMessageEntity(currentUserId) }
                            .sortedBy { it.timestampSeconds }

                        messageStore.setMessages(
                            channelId = channelId,
                            messages = messages,
                            hasMoreTop = response.messagesList.size >= PAGE_SIZE,
                            hasMoreBottom = false
                        )
                    }

                    cacheTracker.markCalled(cacheKey)
                }
            }
        }

    suspend fun loadMoreTop(channelId: Long, clanId: Long = 0L, oldestMessageId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        apiUrl = session.apiUrl,
                        token = session.token,
                        channelId = channelId,
                        clanId = clanId,
                        messageId = oldestMessageId,
                        direction = DIRECTION_BEFORE,
                        limit = PAGE_SIZE
                    )

                    val older = response.messagesList
                        .map { it.toMessageEntity(currentUserId) }
                        .sortedBy { it.timestampSeconds }

                    messageStore.prependMessages(
                        channelId = channelId,
                        older = older,
                        hasMoreTop = response.messagesList.size >= PAGE_SIZE
                    )
                }
            }
        }

    suspend fun loadMoreBottom(channelId: Long, clanId: Long = 0L, newestMessageId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                sessionManager.withAutoRefresh { session ->
                    val currentUserId = session.userId.toLongOrNull() ?: 0L
                    val response = api.listChannelMessages(
                        apiUrl = session.apiUrl,
                        token = session.token,
                        channelId = channelId,
                        clanId = clanId,
                        messageId = newestMessageId,
                        direction = DIRECTION_AFTER,
                        limit = PAGE_SIZE
                    )

                    val newer = response.messagesList
                        .map { it.toMessageEntity(currentUserId) }
                        .sortedBy { it.timestampSeconds }

                    messageStore.appendMessages(
                        channelId = channelId,
                        newer = newer,
                        hasMoreBottom = response.messagesList.size >= PAGE_SIZE
                    )
                }
            }
        }
}
