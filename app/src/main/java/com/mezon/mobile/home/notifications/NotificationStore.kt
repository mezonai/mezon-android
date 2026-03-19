package com.mezon.mobile.home.notifications

import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.MezonApi
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

private const val TAG = "NotificationStore"

@Singleton
class NotificationStore @Inject constructor(
    private val api: MezonApi,
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

    fun cleanup() {
        _mentions.value = emptyList()
        _messages.value = emptyList()
        _forYou.value = emptyList()
        currentClanId = 0L
    }

    fun setCurrentClan(clanId: Long) {
        if (currentClanId == clanId) return
        currentClanId = clanId
        loadCategory(NOTIF_CATEGORY_MENTIONS)
    }

    fun loadCategory(category: Int, notificationId: Long = 0L) {
        val clanId = currentClanId
        if (clanId == 0L) return
        appScope.launch {
            try {
                val session = sessionManager.sessionFlow.first() ?: return@launch
                val result = withContext(ioDispatcher) {
                    api.listNotifications(session.apiUrl, session.token, clanId, category, notificationId)
                }
                val entities = result.notificationsList.map { it.toNotificationEntity() }
                when (category) {
                    NOTIF_CATEGORY_MENTIONS -> {
                        _mentions.value = if (notificationId == 0L) entities
                        else (_mentions.value + entities).distinctBy { it.id }
                    }
                    NOTIF_CATEGORY_MESSAGES -> {
                        _messages.value = if (notificationId == 0L) entities
                        else (_messages.value + entities).distinctBy { it.id }
                    }
                    NOTIF_CATEGORY_FOR_YOU -> {
                        _forYou.value = if (notificationId == 0L) entities
                        else (_forYou.value + entities).distinctBy { it.id }
                    }
                }
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.notificationsDidLoad, category
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadCategory $category failed", e)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.notificationsLoadError, category
                )
            }
        }
    }

    fun deleteNotification(id: Long, category: Int) {
        when (category) {
            NOTIF_CATEGORY_MENTIONS -> _mentions.value = _mentions.value.filter { it.id != id }
            NOTIF_CATEGORY_MESSAGES -> _messages.value = _messages.value.filter { it.id != id }
            NOTIF_CATEGORY_FOR_YOU -> _forYou.value = _forYou.value.filter { it.id != id }
        }
        appScope.launch {
            try {
                val session = sessionManager.sessionFlow.first() ?: return@launch
                withContext(ioDispatcher) {
                    api.deleteNotifications(session.apiUrl, session.token, listOf(id), category)
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteNotification failed", e)
            }
        }
    }

    fun getForCategory(category: Int): StateFlow<List<NotificationEntity>> = when (category) {
        NOTIF_CATEGORY_MENTIONS -> mentions
        NOTIF_CATEGORY_MESSAGES -> messages
        NOTIF_CATEGORY_FOR_YOU -> forYou
        else -> mentions
    }
}
