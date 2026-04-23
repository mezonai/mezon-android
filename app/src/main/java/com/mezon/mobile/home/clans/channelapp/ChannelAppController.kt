package com.mezon.mobile.home.clans.channelapp

import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.ChannelAppDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelAppController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val channelAppDao: ChannelAppDao,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _appsByClan = MutableStateFlow<Map<Long, List<ChannelAppUiModel>>>(emptyMap())
    val appsByClan: StateFlow<Map<Long, List<ChannelAppUiModel>>> = _appsByClan.asStateFlow()

    private val loadingByClan = ConcurrentHashMap<Long, Boolean>()

    fun cleanup() {
        _appsByClan.value = emptyMap()
        loadingByClan.clear()
    }

    fun getApps(clanId: Long): List<ChannelAppUiModel> =
        _appsByClan.value[clanId] ?: emptyList()

    fun findByChannelId(channelId: Long): ChannelAppUiModel? {
        for ((_, apps) in _appsByClan.value) {
            apps.firstOrNull { it.channelId == channelId }?.let { return it }
        }
        return null
    }

    suspend fun findOrFetchByChannelId(channelId: Long): ChannelAppUiModel? {
        findByChannelId(channelId)?.let { return it }
        val fromDb = withContext(ioDispatcher) { channelAppDao.getByChannelId(channelId) }
        return fromDb?.let { ChannelAppUiModel.fromEntity(it) }
    }

    fun loadAppsForClan(clanId: Long, force: Boolean = false) {
        if (clanId == 0L) return
        appScope.launch { loadAppsForClanNow(clanId, force) }
    }

    private suspend fun loadAppsForClanNow(clanId: Long, force: Boolean) {
        if (loadingByClan[clanId] == true) return

        val cacheKey = apiCacheKey("listChannelApps", clanId.toString())
        val inMemory = _appsByClan.value[clanId]
        if (inMemory == null) {
            val cached = withContext(ioDispatcher) { channelAppDao.getByClan(clanId) }
            if (cached.isNotEmpty()) {
                val mapped = cached.map(ChannelAppUiModel::fromEntity)
                updateCache(clanId, mapped)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.channelAppsDidLoad, clanId
                )
            }
        } else if (inMemory.isNotEmpty()) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.channelAppsDidLoad, clanId
            )
        }

        if (!force && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
            return
        }

        loadingByClan[clanId] = true
        try {
            val apiList = sessionManager.withAutoRefresh { session ->
                withContext(ioDispatcher) {
                    api.listChannelApps(session.apiUrl, session.token, clanId)
                }
            }
            val newList = apiList.channelAppsList.map { proto ->
                ChannelAppUiModel(
                    channelId = proto.channelId,
                    clanId = if (proto.clanId != 0L) proto.clanId else clanId,
                    appId = proto.appId,
                    appUrl = proto.appUrl,
                    appName = proto.appName,
                    appLogo = proto.appLogo
                )
            }
            cacheTracker.markCalled(cacheKey)

            val existing = _appsByClan.value[clanId] ?: emptyList()
            if (!isSame(existing, newList)) {
                updateCache(clanId, newList)
                appScope.launch(ioDispatcher) {
                    channelAppDao.deleteByClan(clanId)
                    if (newList.isNotEmpty()) {
                        channelAppDao.upsertAll(
                            newList.mapIndexed { idx, m -> m.toEntity(idx) }
                        )
                    }
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.channelAppsDidLoad, clanId
                )
            }
        } catch (_: Exception) {
        } finally {
            loadingByClan.remove(clanId)
        }
    }

    private fun updateCache(clanId: Long, list: List<ChannelAppUiModel>) {
        val updated = _appsByClan.value.toMutableMap()
        updated[clanId] = list
        _appsByClan.value = updated
    }

    private fun isSame(a: List<ChannelAppUiModel>, b: List<ChannelAppUiModel>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x.channelId != y.channelId) return false
            if (x.appId != y.appId) return false
            if (x.appName != y.appName) return false
            if (x.appLogo != y.appLogo) return false
            if (x.appUrl != y.appUrl) return false
        }
        return true
    }
}
