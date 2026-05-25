package com.mezon.mobile.home.notifications

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.NotificationDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.TopicBadgeTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NotificationStore"
private const val PAGE_SIZE = 50
private const val DB_CACHE_LIMIT = 200
private const val VIEWPORT_LIMIT = 300

@Singleton
class NotificationStore @Inject constructor(
    private val api: MezonApi,
    private val notificationDao: NotificationDao,
    private val sessionManager: SessionManager,
    private val dispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    private val topicBadgeTracker: dagger.Lazy<TopicBadgeTracker>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _mentions = MutableStateFlow<List<NotificationEntity>>(emptyList())
    val mentions: StateFlow<List<NotificationEntity>> = _mentions.asStateFlow()

    private val _messages = MutableStateFlow<List<NotificationEntity>>(emptyList())
    val messages: StateFlow<List<NotificationEntity>> = _messages.asStateFlow()

    private val _forYou = MutableStateFlow<List<NotificationEntity>>(emptyList())
    val forYou: StateFlow<List<NotificationEntity>> = _forYou.asStateFlow()

    private val _emptyCategory = MutableStateFlow<List<NotificationEntity>>(emptyList())

    private var currentClanId: Long = 0L

    private var hasMoreMentions = false
    private var hasMoreMessages = false
    private var hasMoreForYou = false

    private val dbLoadedCategories = HashSet<Int>(4)

    init {
        appScope.launch { observeSocketNotifications() }
    }

    fun cleanup() {
        _mentions.value = emptyList()
        _messages.value = emptyList()
        _forYou.value = emptyList()
        currentClanId = 0L
        hasMoreMentions = false
        hasMoreMessages = false
        hasMoreForYou = false
        dbLoadedCategories.clear()
    }

    fun setCurrentClan(clanId: Long): Boolean {
        if (currentClanId == clanId) return false
        cleanup()
        currentClanId = clanId
        if (clanId != 0L) {
            loadCategory(NOTIF_CATEGORY_MENTIONS)
        }
        return true
    }

    fun hasMoreForCategory(category: Int): Boolean = when (category) {
        NOTIF_CATEGORY_MENTIONS -> hasMoreMentions
        NOTIF_CATEGORY_MESSAGES -> hasMoreMessages
        NOTIF_CATEGORY_FOR_YOU -> hasMoreForYou
        else -> false
    }

    fun loadMore(category: Int) {
        val list = getForCategory(category).value
        val lastId = list.lastOrNull()?.id ?: return
        loadCategory(category, lastId)
    }

    fun loadCategory(category: Int, notificationId: Long = 0L) {
        val clanId = currentClanId
        if (clanId == 0L) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.notificationsDidLoad, category
            )
            return
        }

        appScope.launch {
            if (notificationId == 0L && category !in dbLoadedCategories) {
                loadFromDb(category)
                if (category == NOTIF_CATEGORY_MENTIONS) {
                    val cached = getForCategory(category).value
                    if (cached.isNotEmpty()) {
                        topicBadgeTracker.get().hydrateFromNotifications(cached)
                    }
                }
            }

            try {
                sessionManager.withAutoRefresh { session ->
                    val result = withContext(ioDispatcher) {
                        api.listNotifications(session.apiUrl, session.token, clanId, category, notificationId, PAGE_SIZE)
                    }
                    val entities = result.notificationsList.map { proto ->
                        proto.toNotificationEntity()
                    }
                    val hasMore = entities.size >= PAGE_SIZE
                    updateCategoryState(category, entities, notificationId == 0L, hasMore)

                    if (entities.isNotEmpty()) {
                        withContext(ioDispatcher) {
                            notificationDao.upsertAll(entities)
                            if (notificationId == 0L) {
                                notificationDao.trimCategory(category, clanId, DB_CACHE_LIMIT)
                            }
                        }
                    }

                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.notificationsDidLoad, category
                    )
                    if (category == NOTIF_CATEGORY_MENTIONS) {
                        topicBadgeTracker.get().hydrateFromNotifications(getForCategory(category).value)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadCategory $category failed", e)

                if (notificationId != 0L) {
                    tryOfflinePagination(category, notificationId)
                } else {
                    val cached = getForCategory(category).value
                    if (cached.isEmpty()) {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.notificationsLoadError, category
                        )
                    } else {
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.notificationsDidLoad, category
                        )
                    }
                }
            }
        }
    }

    fun deleteNotification(id: Long, category: Int) {
        val list = getForCategory(category).value
        val removedIndex = list.indexOfFirst { it.id == id }
        if (removedIndex < 0) return
        val removed = list[removedIndex]
        getMutableForCategory(category)?.update { old -> old.filter { it.id != id } }
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.deleteNotifications(session.apiUrl, session.token, listOf(id), category)
                    }
                }
                withContext(ioDispatcher) {
                    try { notificationDao.deleteById(id) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteNotification failed", e)
                getMutableForCategory(category)?.update { old ->
                    if (old.any { it.id == id }) {
                        old
                    } else {
                        val restored = old.toMutableList()
                        restored.add(removedIndex.coerceIn(0, restored.size), removed)
                        restored
                    }
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.notificationsDidLoad, category
                )
            }
        }
    }

    fun getForCategory(category: Int): StateFlow<List<NotificationEntity>> {
        val flow = getMutableForCategory(category)
        if (flow == null) {
            Log.w(TAG, "getForCategory: unknown category=$category")
            return _emptyCategory.asStateFlow()
        }
        return flow
    }

    private fun getMutableForCategory(category: Int) = when (category) {
        NOTIF_CATEGORY_MENTIONS -> _mentions
        NOTIF_CATEGORY_MESSAGES -> _messages
        NOTIF_CATEGORY_FOR_YOU -> _forYou
        else -> null
    }

    private fun updateCategoryState(category: Int, items: List<NotificationEntity>, isRefresh: Boolean, hasMore: Boolean) {
        val flow = getMutableForCategory(category)
        if (flow == null) {
            Log.w(TAG, "updateCategoryState: unknown category=$category")
            return
        }
        setHasMore(category, hasMore)
        flow.update { old ->
            if (isRefresh) items
            else (old + items).distinctBy { it.id }.takeLast(VIEWPORT_LIMIT)
        }
    }

    private suspend fun tryOfflinePagination(category: Int, notificationId: Long) {
        val list = getForCategory(category).value
        val lastRecord = list.firstOrNull { it.id == notificationId }
        if (lastRecord != null) {
            try {
                val dbData = withContext(ioDispatcher) {
                    notificationDao.getByCategoryBefore(
                        category,
                        currentClanId,
                        lastRecord.createTimeSeconds,
                        lastRecord.id,
                        PAGE_SIZE
                    )
                }
                if (dbData.isNotEmpty()) {
                    updateCategoryState(category, dbData, isRefresh = false, hasMore = dbData.size >= PAGE_SIZE)
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.notificationsDidLoad, category
                    )
                    return
                }
            } catch (dbError: Exception) {
                Log.e(TAG, "DB fallback pagination failed", dbError)
            }
        }
        setHasMore(category, false)
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.notificationsDidLoad, category
        )
    }

    private fun setHasMore(category: Int, hasMore: Boolean) {
        when (category) {
            NOTIF_CATEGORY_MENTIONS -> hasMoreMentions = hasMore
            NOTIF_CATEGORY_MESSAGES -> hasMoreMessages = hasMore
            NOTIF_CATEGORY_FOR_YOU -> hasMoreForYou = hasMore
        }
    }

    private suspend fun loadFromDb(category: Int) {
        val clanId = currentClanId
        if (clanId == 0L) return
        val cached = withContext(ioDispatcher) {
            notificationDao.getByCategory(category, clanId, PAGE_SIZE)
        }
        if (cached.isNotEmpty()) {
            val flow = getMutableForCategory(category) ?: return
            setHasMore(category, cached.size >= PAGE_SIZE)
            flow.update { old ->
                if (old.isEmpty()) cached else old
            }
        }
        dbLoadedCategories.add(category)
    }

    private suspend fun observeSocketNotifications() {
        dispatcher.notifications.collect { notification ->
            val clanId = notification.clanId
            if (clanId == 0L) return@collect
            val category = notification.category
            if (getMutableForCategory(category) == null) {
                Log.w(TAG, "observeSocketNotifications: unknown category=$category id=${notification.id}")
                return@collect
            }
            val entity = notification.toNotificationEntity()
            appScope.launch(ioDispatcher) {
                try {
                    notificationDao.upsertAll(listOf(entity))
                    notificationDao.trimCategory(category, clanId, DB_CACHE_LIMIT)
                } catch (_: Exception) {}
            }
            val activeClanId = currentClanId
            if (activeClanId == 0L || clanId != activeClanId) return@collect
            val flow = getMutableForCategory(category) ?: return@collect
            var inserted = false
            flow.update { old ->
                if (old.any { it.id == entity.id }) old
                else {
                    inserted = true
                    listOf(entity) + old
                }
            }
            if (!inserted) return@collect
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.notificationsDidLoad, category
            )
        }
    }
}
