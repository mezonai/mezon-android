package com.mezon.mobile.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.view.View
import androidx.annotation.UiThread
import java.util.Arrays
import java.util.HashSet

class NotificationCenter(val currentAccount: Int) {

    interface NotificationCenterDelegate {
        fun didReceivedNotification(id: Int, account: Int, vararg args: Any?)
    }

    interface PostponeNotificationCallback {
        fun needPostpone(id: Int, currentAccount: Int, args: Array<out Any?>): Boolean
    }

    companion object {
        const val MAX_ACCOUNT_COUNT = 4
        const val GLOBAL_ACCOUNT = -1

        @Volatile private var instances = arrayOfNulls<NotificationCenter>(MAX_ACCOUNT_COUNT)
        @Volatile private var globalInstance: NotificationCenter? = null

        @JvmStatic
        fun getInstance(account: Int): NotificationCenter {
            var instance = instances[account]
            if (instance == null) {
                synchronized(NotificationCenter::class.java) {
                    instance = instances[account]
                    if (instance == null) {
                        instance = NotificationCenter(account)
                        instances[account] = instance
                    }
                }
            }
            return instance!!
        }

        @JvmStatic
        fun getGlobalInstance(): NotificationCenter {
            var instance = globalInstance
            if (instance == null) {
                synchronized(NotificationCenter::class.java) {
                    instance = globalInstance
                    if (instance == null) {
                        instance = NotificationCenter(GLOBAL_ACCOUNT)
                        globalInstance = instance
                    }
                }
            }
            return instance!!
        }

        private var totalEvents = 1
        private fun nextId() = totalEvents++

        val selectedClanChanged = nextId()
        val dialogsNeedReload = nextId()
        val dialogsLoadError = nextId()
        val messagesDidLoad = nextId()
        val didReceiveNewMessages = nextId()
        val messageDidUpdate = nextId()
        val messageDidDelete = nextId()
        val messagesLoadError = nextId()
        val messagesLastSeenFromServer = nextId()  
        val onlineStatusChanged = nextId()
        val markAsRead = nextId()
        val connectionStateChanged = nextId()
        val sessionExpired = nextId()
        val typingEvent = nextId()
        val userDataLoaded = nextId()
        val clansDidLoad = nextId()
        val channelsDidLoad = nextId()
        val clanInfoUpdated = nextId()
        val accountInfoLoaded = nextId()
        val blockedUsersLoaded = nextId()
        val notificationsDidLoad = nextId()
        val notificationsLoadError = nextId()

        val updateInterfaces = nextId()
        val didReplacedPhotoInMemCache = nextId()
        val invalidateMotionBackground = nextId()

        val themeChanged = nextId()
        val languageChanged = nextId()
        val autoNightModeChanged = nextId()
        val appDidLogout = nextId()
        val stopAllHeavyOperations = nextId()
        val startAllHeavyOperations = nextId()
        val closeChats = nextId()
        val needCheckSystemBarColors = nextId()
        val navigateToMessagesTab = nextId()
        val navigateToClansTab = nextId()
        val appDidReconnect = nextId()
        val scrollToBottomChat = nextId()

        const val UPDATE_MASK_NAME = 1
        const val UPDATE_MASK_AVATAR = 2
        const val UPDATE_MASK_STATUS = 4
        const val UPDATE_MASK_CHAT_AVATAR = 8
        const val UPDATE_MASK_CHAT_NAME = 16
        const val UPDATE_MASK_CHAT_MEMBERS = 32
        const val UPDATE_MASK_USER_PRINT = 64
        const val UPDATE_MASK_READ_DIALOG_MESSAGE = 256
        const val UPDATE_MASK_SELECT_DIALOG = 512
        const val UPDATE_MASK_NEW_MESSAGE = 2048
        const val UPDATE_MASK_SEND_STATE = 4096
        const val UPDATE_MASK_CHAT = 8192
        const val UPDATE_MASK_MESSAGE_TEXT = 32768
        const val UPDATE_MASK_CHECK = 65536
        const val UPDATE_MASK_REORDER = 131072
        const val UPDATE_MASK_BADGE = 262144
        const val UPDATE_MASK_REACTIONS = 524288
        const val UPDATE_MASK_ALL = UPDATE_MASK_AVATAR or UPDATE_MASK_STATUS or
            UPDATE_MASK_NAME or UPDATE_MASK_CHAT_AVATAR or UPDATE_MASK_CHAT_NAME or
            UPDATE_MASK_CHAT_MEMBERS or UPDATE_MASK_USER_PRINT or
            UPDATE_MASK_READ_DIALOG_MESSAGE or UPDATE_MASK_BADGE or UPDATE_MASK_REACTIONS

        private const val EXPIRE_NOTIFICATIONS_TIME = 5017L
        private const val DEBOUNCE_DELAY_MS = 250L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val observers = SparseArray<ArrayList<NotificationCenterDelegate>>()
    private val removeAfterBroadcast = SparseArray<ArrayList<NotificationCenterDelegate>>()
    private val addAfterBroadcast = SparseArray<ArrayList<NotificationCenterDelegate>>()

    private val delayedPosts = ArrayList<DelayedPost>(10)
    private val delayedPostsTmp = ArrayList<DelayedPost>(10)
    private val delayedRunnables = ArrayList<Runnable>(10)
    private val delayedRunnablesTmp = ArrayList<Runnable>(10)
    private val postponeCallbackList = ArrayList<PostponeNotificationCallback>(4)

    private var broadcasting = 0
    private var animationInProgressCount = 0
    private var animationInProgressPointer = 1
    private val allowedNotifications = SparseArray<AllowedNotifications>()
    private var expireCheckPending = false

    val heavyOperationsCounter = HashSet<Int>()
    private var currentHeavyOperationFlags = 0

    private val alreadyPostedRunnables = SparseArray<Runnable>()

    private fun createArrayForId(id: Int): ArrayList<NotificationCenterDelegate> {
        return if (id == didReplacedPhotoInMemCache || id == stopAllHeavyOperations || id == startAllHeavyOperations) {
            UniqArrayList()
        } else {
            ArrayList()
        }
    }

    // ── Animation-in-progress ───────────────────────────────────────────────

    @UiThread
    fun setAnimationInProgress(oldIndex: Int, allowedIds: IntArray?): Int {
        return setAnimationInProgress(oldIndex, allowedIds, true)
    }

    @UiThread
    fun setAnimationInProgress(oldIndex: Int, allowedIds: IntArray?, stopHeavy: Boolean): Int {
        onAnimationFinish(oldIndex)
        if (heavyOperationsCounter.isEmpty() && stopHeavy) {
            getGlobalInstance().postNotificationName(stopAllHeavyOperations, 512)
        }
        animationInProgressCount++
        animationInProgressPointer++

        if (stopHeavy) {
            heavyOperationsCounter.add(animationInProgressPointer)
        }
        allowedNotifications.put(animationInProgressPointer, AllowedNotifications(allowedIds))
        scheduleExpireCheck()
        return animationInProgressPointer
    }

    @UiThread
    fun onAnimationFinish(index: Int) {
        val allowed = allowedNotifications.get(index) ?: return
        allowedNotifications.delete(index)
        animationInProgressCount--
        if (heavyOperationsCounter.isNotEmpty()) {
            heavyOperationsCounter.remove(index)
            if (heavyOperationsCounter.isEmpty()) {
                getGlobalInstance().postNotificationName(startAllHeavyOperations, 512)
            }
        }
        if (animationInProgressCount == 0) runDelayedNotifications()
        if (allowedNotifications.size() == 0) expireCheckPending = false
    }

    fun isAnimationInProgress(): Boolean = animationInProgressCount > 0

    fun getCurrentHeavyOperationFlags(): Int = currentHeavyOperationFlags

    @UiThread
    fun updateAllowedNotifications(index: Int, allowedIds: IntArray?) {
        allowedNotifications.get(index)?.allowedIds = allowedIds
    }

    private fun scheduleExpireCheck() {
        if (expireCheckPending) return
        expireCheckPending = true
        mainHandler.postDelayed({
            expireCheckPending = false
            checkForExpiredNotifications()
        }, EXPIRE_NOTIFICATIONS_TIME)
    }

    private fun checkForExpiredNotifications() {
        if (allowedNotifications.size() == 0) return
        val now = SystemClock.elapsedRealtime()
        val expired = ArrayList<Int>()
        var minTime = Long.MAX_VALUE
        for (i in 0 until allowedNotifications.size()) {
            val notification = allowedNotifications.valueAt(i)
            if (now - notification.time > 1000) {
                expired.add(allowedNotifications.keyAt(i))
            } else {
                minTime = minOf(notification.time, minTime)
            }
        }
        for (idx in expired) onAnimationFinish(idx)
        if (minTime != Long.MAX_VALUE) {
            val time = EXPIRE_NOTIFICATIONS_TIME - (now - minTime)
            mainHandler.postDelayed({
                checkForExpiredNotifications()
            }, maxOf(17, time))
        }
    }

    // ── Observer management ─────────────────────────────────────────────────

    @UiThread
    fun addObserver(observer: NotificationCenterDelegate, id: Int) {
        if (broadcasting != 0) {
            var list = addAfterBroadcast.get(id)
            if (list == null) { list = ArrayList(); addAfterBroadcast.put(id, list) }
            list.add(observer)
            return
        }
        var list = observers.get(id)
        if (list == null) { list = createArrayForId(id); observers.put(id, list) }
        if (!list.contains(observer)) list.add(observer)
    }

    @UiThread
    fun removeObserver(observer: NotificationCenterDelegate, id: Int) {
        if (broadcasting != 0) {
            var list = removeAfterBroadcast.get(id)
            if (list == null) { list = ArrayList(); removeAfterBroadcast.put(id, list) }
            list.add(observer)
            return
        }
        observers.get(id)?.remove(observer)
    }

    @UiThread
    fun removeObserverForAll(observer: NotificationCenterDelegate) {
        if (broadcasting != 0) {
            for (i in 0 until observers.size()) {
                val id = observers.keyAt(i)
                var list = removeAfterBroadcast.get(id)
                if (list == null) { list = ArrayList(); removeAfterBroadcast.put(id, list) }
                list.add(observer)
            }
            return
        }
        for (i in 0 until observers.size()) observers.valueAt(i).remove(observer)
    }

    fun hasObservers(id: Int): Boolean = observers.indexOfKey(id) >= 0

    fun getObservers(id: Int): ArrayList<NotificationCenterDelegate>? = observers.get(id)

    fun updateObserver(add: Boolean, observer: NotificationCenterDelegate, id: Int) {
        if (add) addObserver(observer, id) else removeObserver(observer, id)
    }

    // ── Post ────────────────────────────────────────────────────────────────

    fun postNotificationNameOnUIThread(id: Int, vararg args: Any?) {
        mainHandler.post { postNotificationName(id, *args) }
    }

    fun postNotificationOnMainThread(id: Int, vararg args: Any?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            postNotificationName(id, *args)
        } else {
            mainHandler.post { postNotificationName(id, *args) }
        }
    }

    fun postNotificationName(id: Int, vararg args: Any?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { postNotificationName(id, *args) }
            return
        }

        var allowDuringAnimation = id == startAllHeavyOperations || id == stopAllHeavyOperations
                || id == didReplacedPhotoInMemCache || id == closeChats
                || id == invalidateMotionBackground || id == needCheckSystemBarColors

        var expiredIndices: ArrayList<Int>? = null
        if (!allowDuringAnimation && allowedNotifications.size() > 0) {
            val total = allowedNotifications.size()
            var matched = 0
            val now = SystemClock.elapsedRealtime()
            for (i in 0 until total) {
                val n = allowedNotifications.valueAt(i)
                if (now - n.time > EXPIRE_NOTIFICATIONS_TIME) {
                    if (expiredIndices == null) expiredIndices = ArrayList()
                    expiredIndices.add(allowedNotifications.keyAt(i))
                }
                val ids = n.allowedIds
                if (ids != null) {
                    if (ids.contains(id)) matched++
                } else {
                    break
                }
            }
            allowDuringAnimation = total == matched
        }

        if (id == startAllHeavyOperations) {
            val flags = args.firstOrNull() as? Int ?: 0
            currentHeavyOperationFlags = currentHeavyOperationFlags and flags.inv()
        } else if (id == stopAllHeavyOperations) {
            val flags = args.firstOrNull() as? Int ?: 0
            currentHeavyOperationFlags = currentHeavyOperationFlags or flags
        }

        if (shouldDebounce(id)) {
            postNotificationDebounced(id, allowDuringAnimation, args)
        } else {
            postNotificationNameInternal(id, allowDuringAnimation, args)
        }

        if (expiredIndices != null) {
            for (idx in expiredIndices) onAnimationFinish(idx)
        }
    }

    private fun shouldDebounce(id: Int): Boolean {
        return id == updateInterfaces
    }

    private fun postNotificationDebounced(id: Int, allowDuringAnimation: Boolean, args: Array<out Any?>) {
        val hash = id + (Arrays.hashCode(args) shl 16)
        if (alreadyPostedRunnables.indexOfKey(hash) >= 0) return
        val runnable = Runnable {
            postNotificationNameInternal(id, allowDuringAnimation, args)
            alreadyPostedRunnables.remove(hash)
        }
        alreadyPostedRunnables.put(hash, runnable)
        mainHandler.postDelayed(runnable, DEBOUNCE_DELAY_MS)
    }

    @UiThread
    fun postNotificationNameInternal(id: Int, allowDuringAnimation: Boolean, args: Array<out Any?>) {
        if (!allowDuringAnimation && isAnimationInProgress()) {
            delayedPosts.add(DelayedPost(id, args))
            return
        }
        if (postponeCallbackList.isNotEmpty()) {
            for (cb in postponeCallbackList) {
                if (cb.needPostpone(id, currentAccount, args)) {
                    delayedPosts.add(DelayedPost(id, args))
                    return
                }
            }
        }
        broadcasting++
        val callbacks = observers.get(id)
        if (callbacks != null && callbacks.isNotEmpty()) {
            for (i in 0 until callbacks.size) {
                callbacks[i].didReceivedNotification(id, currentAccount, *args)
            }
        }
        broadcasting--
        if (broadcasting == 0) {
            if (removeAfterBroadcast.size() != 0) {
                for (i in 0 until removeAfterBroadcast.size()) {
                    val key = removeAfterBroadcast.keyAt(i)
                    val list = removeAfterBroadcast.valueAt(i)
                    observers.get(key)?.let { target -> list.forEach { target.remove(it) } }
                }
                removeAfterBroadcast.clear()
            }
            if (addAfterBroadcast.size() != 0) {
                for (i in 0 until addAfterBroadcast.size()) {
                    val key = addAfterBroadcast.keyAt(i)
                    addAfterBroadcast.valueAt(i).forEach { addObserver(it, key) }
                }
                addAfterBroadcast.clear()
            }
        }
    }

    // ── Delayed / idle ──────────────────────────────────────────────────────

    @UiThread
    fun runDelayedNotifications() {
        if (delayedPosts.isNotEmpty()) {
            delayedPostsTmp.clear()
            delayedPostsTmp.addAll(delayedPosts)
            delayedPosts.clear()
            for (post in delayedPostsTmp)
                postNotificationNameInternal(post.id, allowDuringAnimation = true, post.args)
            delayedPostsTmp.clear()
        }
        if (delayedRunnables.isNotEmpty()) {
            delayedRunnablesTmp.clear()
            delayedRunnablesTmp.addAll(delayedRunnables)
            delayedRunnables.clear()
            delayedRunnablesTmp.forEach { mainHandler.post(it) }
            delayedRunnablesTmp.clear()
        }
    }

    fun doOnIdle(runnable: Runnable) {
        if (isAnimationInProgress()) delayedRunnables.add(runnable) else runnable.run()
    }

    fun removeDelayed(runnable: Runnable) {
        delayedRunnables.remove(runnable)
    }

    // ── Postpone ────────────────────────────────────────────────────────────

    @UiThread
    fun addPostponeNotificationsCallback(callback: PostponeNotificationCallback) {
        if (!postponeCallbackList.contains(callback)) postponeCallbackList.add(callback)
    }

    @UiThread
    fun removePostponeNotificationsCallback(callback: PostponeNotificationCallback) {
        if (postponeCallbackList.remove(callback)) runDelayedNotifications()
    }

    // ── View-lifecycle-bound listen ─────────────────────────────────────────

    fun listen(view: View, id: Int, callback: (account: Int, args: Array<out Any?>) -> Unit): Runnable {
        val delegate = object : NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) =
                callback(account, args)
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = addObserver(delegate, id)
            override fun onViewDetachedFromWindow(v: View) = removeObserver(delegate, id)
        }
        view.addOnAttachStateChangeListener(attachListener)
        return Runnable {
            view.removeOnAttachStateChangeListener(attachListener)
            removeObserver(delegate, id)
        }
    }

    fun listenGlobal(view: View, id: Int, callback: (args: Array<out Any?>) -> Unit): Runnable {
        val delegate = object : NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) =
                callback(args)
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = getGlobalInstance().addObserver(delegate, id)
            override fun onViewDetachedFromWindow(v: View) = getGlobalInstance().removeObserver(delegate, id)
        }
        view.addOnAttachStateChangeListener(attachListener)
        return Runnable {
            view.removeOnAttachStateChangeListener(attachListener)
            getGlobalInstance().removeObserver(delegate, id)
        }
    }

    // ── listenOnce ──────────────────────────────────────────────────────────

    @UiThread
    fun listenOnce(id: Int, callback: Runnable) {
        val holder = arrayOfNulls<NotificationCenterDelegate>(1)
        holder[0] = object : NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                holder[0]?.let {
                    callback.run()
                    removeObserver(it, id)
                    holder[0] = null
                }
            }
        }
        addObserver(holder[0]!!, id)
    }

    @UiThread
    fun listenOnce(id: Int, callback: (account: Int, args: Array<out Any?>) -> Unit) {
        val holder = arrayOfNulls<NotificationCenterDelegate>(1)
        holder[0] = object : NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                holder[0]?.let {
                    callback(account, args)
                    removeObserver(it, id)
                    holder[0] = null
                }
            }
        }
        addObserver(holder[0]!!, id)
    }

    // ── Internal types ──────────────────────────────────────────────────────

    private class DelayedPost(val id: Int, val args: Array<out Any?>)

    private class AllowedNotifications(var allowedIds: IntArray?) {
        val time: Long = SystemClock.elapsedRealtime()
    }

    private class UniqArrayList<T> : ArrayList<T>() {
        private val set = HashSet<T>()

        override fun add(element: T): Boolean {
            return if (set.add(element)) super.add(element) else false
        }

        override fun add(index: Int, element: T) {
            if (set.add(element)) super.add(index, element)
        }

        override fun remove(element: T): Boolean {
            return if (set.remove(element)) super.remove(element) else false
        }

        override fun removeAt(index: Int): T {
            val t = super.removeAt(index)
            set.remove(t)
            return t
        }

        override fun contains(element: T): Boolean = set.contains(element)

        override fun clear() {
            set.clear()
            super.clear()
        }
    }
}
