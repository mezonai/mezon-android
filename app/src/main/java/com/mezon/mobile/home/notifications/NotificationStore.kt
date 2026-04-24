package com.mezon.mobile.home.notifications

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.NotificationDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.MezonApi
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
    private val notificationCenter: NotificationCenter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val _mentions = MutableStateFlow<List<NotificationEntity>>(emptyList())
    val mentions: StateFlow<List<NotificationEntity>> = _mentions.asStateFlow()

    private val _messages = MutableStateFlow<List<NotificationEntity>>(emptyList())
    val messages: StateFlow<List<NotificationEntity>> = _messages.asStateFlow()

    private val _forYou = MutableStateFlow<List<NotificationEntity>>(emptyList())
    val forYou: StateFlow<List<NotificationEntity>> = _forYou.asStateFlow()

    private var currentClanId: Long = 0L

    private var hasMoreMentions = false
    private var hasMoreMessages = false
    private var hasMoreForYou = false

    private val dbLoadedCategories = HashSet<Int>(4)

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
        if (clanId == 0L) return

        appScope.launch {
            if (notificationId == 0L && category !in dbLoadedCategories) {
                loadFromDb(category)
                val dbData = getForCategory(category).value
                if (dbData.isNotEmpty()) {
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.notificationsDidLoad, category
                    )
                }
            }

            try {
                sessionManager.withAutoRefresh { session ->
                    val result = withContext(ioDispatcher) {
                        api.listNotifications(session.apiUrl, session.token, clanId, category, notificationId, PAGE_SIZE)
                    }
                    val entities = result.notificationsList.map { proto ->
                        proto.toNotificationEntity();
                    }
                    val hasMore = entities.size >= PAGE_SIZE
                    updateCategoryState(category, entities, notificationId == 0L, hasMore)

                    if (entities.isNotEmpty()) {
                        withContext(ioDispatcher) {
                            notificationDao.upsertAll(entities)
                            if (notificationId == 0L) {
                                notificationDao.trimCategory(category, DB_CACHE_LIMIT)
                            }
                        }
                    }

                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.notificationsDidLoad, category
                    )
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
                    }
                }
            }
        }
    }

    fun deleteNotification(id: Long, category: Int) {
        getMutableForCategory(category)?.update { old -> old.filter { it.id != id } }
        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        api.deleteNotifications(session.apiUrl, session.token, listOf(id), category)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteNotification failed", e)
            }
            withContext(ioDispatcher) {
                try { notificationDao.deleteById(id) } catch (_: Exception) {}
            }
        }
    }

    fun getForCategory(category: Int): StateFlow<List<NotificationEntity>> =
        getMutableForCategory(category) ?: _mentions

    private fun getMutableForCategory(category: Int) = when (category) {
        NOTIF_CATEGORY_MENTIONS -> _mentions
        NOTIF_CATEGORY_MESSAGES -> _messages
        NOTIF_CATEGORY_FOR_YOU -> _forYou
        else -> null
    }

    private fun updateCategoryState(category: Int, items: List<NotificationEntity>, isRefresh: Boolean, hasMore: Boolean) {
        val flow = getMutableForCategory(category) ?: return
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
                    notificationDao.getByCategoryBefore(category, lastRecord.createTimeSeconds, lastRecord.id, PAGE_SIZE)
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
        val cached = withContext(ioDispatcher) {
            notificationDao.getByCategory(category, PAGE_SIZE)
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
}
