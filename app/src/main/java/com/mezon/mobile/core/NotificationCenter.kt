package com.mezon.mobile.core

import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationCenter @Inject constructor() {

    fun interface Observer {
        fun onNotification(id: Int, args: Array<out Any?>)
    }

    companion object {
        private var totalEvents = 0
        private fun nextId() = totalEvents++

        val dialogsNeedReload = nextId()
        val dialogsLoadError = nextId()
        val messagesDidLoad = nextId()
        val didReceiveNewMessages = nextId()
        val messageDidUpdate = nextId()
        val messageDidDelete = nextId()
        val messagesLoadError = nextId()
        val onlineStatusChanged = nextId()
        val markAsRead = nextId()
        val connectionStateChanged = nextId()
        val themeChanged = nextId()
        val sessionExpired = nextId()
        val typingEvent = nextId()
        val userDataLoaded = nextId()
        val languageChanged = nextId()
        val clansDidLoad = nextId()
        val channelsDidLoad = nextId()
        val clanInfoUpdated = nextId()
        val accountInfoLoaded = nextId()
        val blockedUsersLoaded = nextId()
        val notificationsDidLoad = nextId()
        val notificationsLoadError = nextId()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = SparseArray<ArrayList<Observer>>()

    @Synchronized
    fun addObserver(id: Int, observer: Observer) {
        var list = observers.get(id)
        if (list == null) {
            list = ArrayList()
            observers.put(id, list)
        }
        if (!list.contains(observer)) {
            list.add(observer)
        }
    }

    @Synchronized
    fun removeObserver(id: Int, observer: Observer) {
        observers.get(id)?.remove(observer)
    }

    fun removeObserverForAll(observer: Observer) {
        synchronized(this) {
            for (i in 0 until observers.size()) {
                observers.valueAt(i).remove(observer)
            }
        }
    }

    fun postNotificationOnMainThread(id: Int, vararg args: Any?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            postNotification(id, *args)
        } else {
            mainHandler.post { postNotification(id, *args) }
        }
    }

    fun postNotification(id: Int, vararg args: Any?) {
        val callbacks: List<Observer>
        synchronized(this) {
            callbacks = observers.get(id)?.toList() ?: return
        }
        for (callback in callbacks) {
            callback.onNotification(id, args)
        }
    }
}
