package ai.mezon.app.home.messages

import ai.mezon.app.di.IoDispatcher
import ai.mezon.app.network.ApiCacheTracker
import ai.mezon.app.network.CHANNEL_TYPE_DM
import ai.mezon.app.network.CHANNEL_TYPE_GROUP
import ai.mezon.app.network.MezonApi
import ai.mezon.app.network.NetworkMonitor
import ai.mezon.app.network.apiCacheKey
import ai.mezon.app.session.SessionManager
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectRepository @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val directStore: DirectStore,
    private val networkMonitor: NetworkMonitor,
    private val cacheTracker: ApiCacheTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "DirectRepository"
    }

    suspend fun fetchAndSync(
        page: Int = 1,
        limit: Int = 50
    ): Result<List<DirectMessage>> = withContext(ioDispatcher) {
        runCatching {
            val cacheKey = apiCacheKey("listChannelDescs", page)
            val hasCache = directStore.directs.value.isNotEmpty()

            if (!networkMonitor.isOnline.value && hasCache) {
                Log.d(TAG, "Offline — returning cached directs")
                return@runCatching directStore.directs.value
            }

            if (hasCache && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                Log.d(TAG, "Cache still valid, skipping API call")
                return@runCatching directStore.directs.value
            }

            sessionManager.withAutoRefresh { session ->
                val currentUserId = session.userId.toLongOrNull() ?: 0L

                val dmResponse = api.listChannelDescs(
                    apiUrl = session.apiUrl,
                    token = session.token,
                    channelType = CHANNEL_TYPE_DM,
                    page = page,
                    limit = limit
                )
                val groupResponse = api.listChannelDescs(
                    apiUrl = session.apiUrl,
                    token = session.token,
                    channelType = CHANNEL_TYPE_GROUP,
                    page = page,
                    limit = limit
                )

                val merged = (dmResponse.channeldescList + groupResponse.channeldescList)
                    .filter { it.active == 1 }
                    .distinctBy { it.channelId }
                    .map { it.toDirectMessage(currentUserId) }
                    .sortedByDescending { it.lastMessageTimestamp }

                directStore.setDirects(merged)
                cacheTracker.markCalled(cacheKey)
                merged
            }
        }
    }
}
