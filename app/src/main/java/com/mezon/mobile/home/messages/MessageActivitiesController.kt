package com.mezon.mobile.home.messages

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mezon.mezon.api.UserActivity
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MessageActivitiesController"
private const val ACTIVITY_TYPE_WORK = 1
private const val ACTIVITY_TYPE_LIVE = 2
private const val ACTIVITY_TYPE_PLAY = 3

@Singleton
class MessageActivitiesController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val friendController: FriendController,
    private val dialogsController: DialogsController,
    private val notificationCenter: NotificationCenter,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val activities = ArrayList<UserActivity>()
    private val _rows = MutableStateFlow<List<MessageActivityRow>>(emptyList())
    val rows: StateFlow<List<MessageActivityRow>> = _rows.asStateFlow()

    private val notificationDelegate = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            rebuildRows()
        }
    }

    init {
        Handler(Looper.getMainLooper()).post {
            notificationCenter.addObserver(notificationDelegate, NotificationCenter.dialogsNeedReload)
            notificationCenter.addObserver(notificationDelegate, NotificationCenter.friendsLoaded)
            notificationCenter.addObserver(notificationDelegate, NotificationCenter.blockedUsersLoaded)
        }
    }

    fun loadListActivities() {
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    val res = api.listActivities(session.apiUrl, session.token)
                    synchronized(activities) {
                        activities.clear()
                        res.actsList.filterTo(activities) { it.userId != 0L }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadListActivities failed", e)
            }
            rebuildRows()
        }
    }

    private fun rebuildRows() {
        val me = StartupCache.userId.toLongOrNull() ?: 0L
        val ordered = synchronized(activities) {
            activities.sortedBy { activitySectionRank(it.activityType) }
        }
        if (ordered.isEmpty()) {
            _rows.value = emptyList()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.messageActivitiesRowsUpdated)
            return
        }
        val merged = LinkedHashMap<Long, Triple<String, String, String>>()
        val relations = friendController.friends.value +
            friendController.sentFriendRequests.value +
            friendController.receivedFriendRequests.value +
            friendController.blockedUsers.value
        for (f in relations) {
            val u = f.user
            if (u.id == 0L) continue
            merged.putIfAbsent(u.id, Triple(
                u.displayName.ifBlank { u.username },
                u.username,
                u.avatarUrl
            ))
        }
        for (dm in dialogsController.getDialogs()) {
            if (dm.type != CHANNEL_TYPE_DM || dm.otherUserId == 0L) continue
            val name = dm.displayName.ifBlank { dm.label }
            merged.putIfAbsent(dm.otherUserId, Triple(name, dm.label.ifBlank { name }, dm.avatarUrl))
        }
        val out = ArrayList<MessageActivityRow>()
        val shown = HashSet<Long>()
        for (act in ordered) {
            val uid = act.userId
            if (uid == me || uid in shown) continue
            val t = merged[uid] ?: continue
            val label = if (act.activityDescription.isNotBlank()) {
                "${act.activityName} - ${act.activityDescription}"
            } else {
                act.activityName
            }
            val display = t.first
            if (display.isBlank()) continue
            shown.add(uid)
            out.add(
                MessageActivityRow(
                    userId = uid,
                    displayName = display,
                    username = t.second,
                    avatarUrl = t.third,
                    activityText = label
                )
            )
        }
        _rows.value = out
        notificationCenter.postNotificationOnMainThread(NotificationCenter.messageActivitiesRowsUpdated)
    }

    private fun activitySectionRank(activityType: Int): Int = when (activityType) {
        ACTIVITY_TYPE_WORK -> 0
        ACTIVITY_TYPE_LIVE -> 1
        ACTIVITY_TYPE_PLAY -> 2
        else -> 3
    }
}
