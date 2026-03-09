package com.mezon.mobile.home.clans

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.ClanDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ClansController"

@Singleton
class ClansController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val clanDao: ClanDao,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    private val channelController: ChannelController,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _clans = MutableStateFlow<List<ClanEntity>>(emptyList())
    val clans: StateFlow<List<ClanEntity>> = _clans.asStateFlow()

    private val _selectedClanId = MutableStateFlow(0L)
    val selectedClanId: StateFlow<Long> = _selectedClanId.asStateFlow()

    var clansLoaded = false
        private set

    init {
        appScope.launch {
            val cached = withContext(ioDispatcher) { clanDao.getAll() }
            if (cached.isNotEmpty()) {
                Log.d(TAG, "init Room cache (${cached.size} clans): ${cached.map { "${it.clanName}(order=${it.clanOrder})" }}")
                _clans.value = cached
                clansLoaded = true
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clansDidLoad)
                // Pre-warm channel cache for first clan so UI is instant on cold start
                val firstClanId = cached.first().clanId
                _selectedClanId.value = firstClanId
                channelController.loadChannelsForClan(firstClanId)
            }
        }
        observeSocketEvents()
    }

    fun selectClan(clanId: Long) {
        if (_selectedClanId.value == clanId) return
        _selectedClanId.value = clanId
        channelController.loadChannelsForClan(clanId)
    }

    fun loadClans(force: Boolean = false) {
        val cacheKey = apiCacheKey("listClanDescs")
        appScope.launch {
            try {
                val session = sessionManager.sessionFlow.first() ?: return@launch
                if (!force && cacheTracker.shouldCall(cacheKey) == ApiCacheTracker.ShouldCall.SKIP) {
                    if (_clans.value.isNotEmpty()) {
                        notificationCenter.postNotificationOnMainThread(NotificationCenter.clansDidLoad)
                        val selectedId = _selectedClanId.value
                        if (selectedId != 0L) {
                            notificationCenter.postNotificationOnMainThread(NotificationCenter.channelsDidLoad, selectedId)
                        }
                    }
                    return@launch
                }
                val result = withContext(ioDispatcher) {
                    api.listClanDescs(session.apiUrl, session.token)
                }
                val apiEntities = result.clandescList.mapIndexed { index, desc ->
                    desc.toClanEntity().let { entity ->
                        if (entity.clanOrder == 0) entity.copy(clanOrder = index) else entity
                    }
                }
                Log.d(TAG, "loadClans API result (${apiEntities.size} clans): ${apiEntities.map { "${it.clanName}(order=${it.clanOrder})" }}")

                val existingOrder = _clans.value.mapIndexed { i, c -> c.clanId to i }.toMap()
                val entities = if (existingOrder.isNotEmpty()) {
                    apiEntities.sortedBy { existingOrder[it.clanId] ?: it.clanOrder }
                } else {
                    apiEntities.sortedBy { it.clanOrder }
                }
                _clans.value = entities
                clansLoaded = true
                cacheTracker.markCalled(cacheKey)
                withContext(ioDispatcher) { clanDao.upsertAll(entities) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clansDidLoad)

                if (_selectedClanId.value == 0L && entities.isNotEmpty()) {
                    selectClan(entities.first().clanId)
                } else if (_selectedClanId.value != 0L) {
                    channelController.loadChannelsForClan(_selectedClanId.value)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadClans failed", e)
            }
        }
    }

    private fun observeSocketEvents() {
        appScope.launch {
            dispatcher.clanUpdatedEvents.collect { event ->
                val existing = _clans.value.find { it.clanId == event.clanId } ?: return@collect
                val updated = existing.copy(
                    clanName = event.clanName.ifEmpty { existing.clanName },
                    logo = event.logo.ifEmpty { existing.logo },
                    isCommunity = event.isCommunity
                )
                _clans.value = _clans.value.map { if (it.clanId == updated.clanId) updated else it }
                appScope.launch(ioDispatcher) { clanDao.upsert(updated) }
                notificationCenter.postNotificationOnMainThread(NotificationCenter.clanInfoUpdated, event.clanId)
            }
        }
    }
}

data class ChannelSection(
    val categoryId: Long,
    val categoryName: String,
    val channels: List<ClanChannelEntity>
)
