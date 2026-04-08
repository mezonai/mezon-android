package com.mezon.mobile.home.notifications

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.NotificationDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NotificationStore"
private const val PAGE_SIZE = 50
private const val DB_CACHE_LIMIT = 200

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

    private val dbLoadedCategories = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

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
        currentClanId = clanId
        dbLoadedCategories.clear()
        _mentions.value = emptyList()
        _messages.value = emptyList()
        _forYou.value = emptyList()
        hasMoreMentions = false
        hasMoreMessages = false
        hasMoreForYou = false
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
                val session = sessionManager.sessionFlow.first() ?: return@launch
                val result = withContext(ioDispatcher) {
                    api.listNotifications(session.apiUrl, session.token, clanId, category, notificationId, PAGE_SIZE)
                }
                val entities = result.notificationsList.map { proto ->
                    proto.toNotificationEntity().let { e ->
                        e.copy(
                            category = if (e.category == 0) category else e.category,
                            clanId = if (e.clanId == 0L) clanId else e.clanId
                        )
                    }
                }
                val hasMore = entities.size >= PAGE_SIZE
                updateCategoryState(category, entities, notificationId == 0L, hasMore)

                if (entities.isNotEmpty()) {
                    appScope.launch(ioDispatcher) {
                        notificationDao.upsertAll(entities)
                        if (notificationId == 0L) {
                            notificationDao.trimCategory(category, DB_CACHE_LIMIT)
                        }
                    }
                }

                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.notificationsDidLoad, category
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadCategory $category failed", e)

                if (notificationId != 0L) {
                    val list = getForCategory(category).value
                    val lastRecord = list.firstOrNull { it.id == notificationId }
                    if (lastRecord != null) {
                        try {
                            val dbData = withContext(ioDispatcher) {
                                notificationDao.getByCategoryBefore(category, lastRecord.createTimeSeconds, lastRecord.id, PAGE_SIZE)
                            }
                            if (dbData.isNotEmpty()) {
                                Log.d(TAG, "Offline pagination loaded ${dbData.size} from DB")
                                val hasMore = dbData.size >= PAGE_SIZE
                                updateCategoryState(category, dbData, isRefresh = false, hasMore = hasMore)
                                notificationCenter.postNotificationOnMainThread(
                                    NotificationCenter.notificationsDidLoad, category
                                )
                                return@launch
                            }
                        } catch (dbError: Exception) {
                            Log.e(TAG, "DB fallback pagination failed", dbError)
                        }
                    }
                }

                val cached = getForCategory(category).value
                if (cached.isEmpty()) {
                    notificationCenter.postNotificationOnMainThread(
                        NotificationCenter.notificationsLoadError, category
                    )
                }
            }
        }
    }

    fun deleteNotification(id: Long, category: Int) {
        getMutableForCategory(category)?.update { old -> old.filter { it.id != id } }
        appScope.launch {
            try {
                val session = sessionManager.sessionFlow.first() ?: return@launch
                withContext(ioDispatcher) {
                    api.deleteNotifications(session.apiUrl, session.token, listOf(id), category)
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
        when (category) {
            NOTIF_CATEGORY_MENTIONS -> hasMoreMentions = hasMore
            NOTIF_CATEGORY_MESSAGES -> hasMoreMessages = hasMore
            NOTIF_CATEGORY_FOR_YOU -> hasMoreForYou = hasMore
        }
        flow.update { old ->
            if (isRefresh) items else (old + items).distinctBy { it.id }
        }
    }

    private suspend fun loadFromDb(category: Int) {
        val cached = withContext(ioDispatcher) {
            notificationDao.getByCategory(category, PAGE_SIZE)
        }
        Log.d(TAG, "loadFromDb: category=$category returned ${cached.size} items")
        if (cached.isNotEmpty()) {
            val flow = getMutableForCategory(category) ?: return

            val hasMore = cached.size >= PAGE_SIZE
            when (category) {
                NOTIF_CATEGORY_MENTIONS -> hasMoreMentions = hasMore
                NOTIF_CATEGORY_MESSAGES -> hasMoreMessages = hasMore
                NOTIF_CATEGORY_FOR_YOU -> hasMoreForYou = hasMore
            }

            flow.update { old ->
                if (old.isEmpty()) cached else old
            }
        }
        dbLoadedCategories.add(category)
    }
}
