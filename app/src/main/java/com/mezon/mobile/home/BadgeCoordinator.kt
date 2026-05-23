package com.mezon.mobile.home

import android.os.SystemClock
import android.util.Log
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.MainDispatcher
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.network.channelTypeToStreamMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BadgeCoordinator"
private const val DEBOUNCE_LAST_SEEN_MS = 100L
private const val DEDUP_FULL_READ_MS = 3000L
private const val BATCH_CHANNEL_ACTIVITY_MS = 50L
private const val CLAN_RECONCILE_MS = 500L
private const val RETRY_LAST_SEEN_MS = 1000L
private const val RETRY_LAST_SEEN_MAX_MS = 30_000L
private const val RETRY_LAST_SEEN_MAX_ATTEMPTS = 8
private const val TS_MASK = 0xFFFF_FFFFL

private data class PendingLastSeen(
    val channelId: Long,
    val clanId: Long,
    val channelType: Int,
    val messageId: Long,
    val timestampSeconds: Int,
    val badgeCount: Int
)

private data class ChannelActivityTick(
    val clanId: Long,
    val channelId: Long,
    val messageId: Long,
    val ts: Long
)

private data class QueuedLastSeenWrite(
    val pending: PendingLastSeen,
    val socketBadgeCount: Int,
    val attempt: Int = 0
)

@Singleton
class BadgeCoordinator @Inject constructor(
    private val channelController: dagger.Lazy<ChannelController>,
    private val clansController: dagger.Lazy<ClansController>,
    private val dialogsController: DialogsController,
    private val mezonSocket: MezonSocket,
    private val notificationCenter: NotificationCenter,
    @ApplicationScope private val appScope: CoroutineScope,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher
) {

    private val lastSeenJobs = ConcurrentHashMap<String, Job>()
    private val pendingLastSeen = ConcurrentHashMap<String, PendingLastSeen>()
    private val deferredLastSeenByKey = ConcurrentHashMap<String, PendingLastSeen>()
    private val retryLastSeenByKey = ConcurrentHashMap<String, QueuedLastSeenWrite>()
    private val retryLastSeenJobs = ConcurrentHashMap<String, Job>()
    private val fullReadDedupAt = ConcurrentHashMap<String, Long>()
    private val reconcileJobs = ConcurrentHashMap<Long, Job>()

    private val channelActivityTicks = ConcurrentHashMap<String, ChannelActivityTick>()
    private var channelActivityJob: Job? = null
    private val channelActivityLock = Any()

    fun onReconnect() {
        lastSeenJobs.values.forEach { it.cancel() }
        lastSeenJobs.clear()
        pendingLastSeen.clear()
        deferredLastSeenByKey.clear()
        retryLastSeenJobs.values.forEach { it.cancel() }
        retryLastSeenJobs.clear()
        fullReadDedupAt.clear()
        reconcileJobs.values.forEach { it.cancel() }
        reconcileJobs.clear()
        channelActivityJob?.cancel()
        channelActivityJob = null
        synchronized(channelActivityLock) { channelActivityTicks.clear() }
        Log.d(TAG, "onReconnect: cleared coordinator state")
    }

    fun cleanup() {
        onReconnect()
        retryLastSeenByKey.clear()
    }

    fun scheduleLastSeenWrite(
        channelId: Long,
        clanId: Long,
        channelType: Int,
        messageId: Long,
        timestampSeconds: Int,
        badgeCount: Int = 0,
        skipDefer: Boolean = false
    ) {
        val key = "${clanId}_$channelId"
        val pending = PendingLastSeen(
            channelId, clanId, channelType, messageId, timestampSeconds, badgeCount
        )
        if (!skipDefer && shouldDeferLastSeen(pending)) {
            deferredLastSeenByKey[key] = pending
            return
        }
        pendingLastSeen[key] = pending
        lastSeenJobs[key]?.cancel()
        lastSeenJobs[key] = appScope.launch(mainDispatcher) {
            delay(DEBOUNCE_LAST_SEEN_MS)
            flushLastSeen(key)
        }
    }

    fun processDeferredQueue() {
        appScope.launch(mainDispatcher) {
            val snapshot = deferredLastSeenByKey.values.toList()
            deferredLastSeenByKey.clear()
            for (p in snapshot) {
                if (shouldDeferLastSeen(p)) {
                    deferredLastSeenByKey["${p.clanId}_${p.channelId}"] = p
                } else {
                    scheduleLastSeenWrite(
                        p.channelId,
                        p.clanId,
                        p.channelType,
                        p.messageId,
                        p.timestampSeconds,
                        p.badgeCount,
                        skipDefer = true
                    )
                }
            }
            for ((key, queued) in retryLastSeenByKey) {
                if (!shouldDeferLastSeen(queued.pending)) {
                    scheduleLastSeenRetry(key, delayMs = 0L)
                }
            }
        }
    }

    private fun shouldDeferLastSeen(p: PendingLastSeen): Boolean {
        if (!clansController.get().clansLoaded) return true
        if (p.clanId == 0L) {
            return !dialogsController.dialogsLoaded
        }
        if (channelController.get().isChannelListLoading(p.clanId)) return true
        return false
    }

    private fun flushLastSeen(key: String) {
        lastSeenJobs.remove(key)
        val p = pendingLastSeen.remove(key) ?: return
        retryLastSeenJobs.remove(key)?.cancel()
        retryLastSeenByKey.remove(key)
        if (shouldSkipDuplicateFullRead(key, p)) {
            Log.d(TAG, "flushLastSeen: skip dedup key=$key")
            return
        }
        val socketBadgeCount = if (p.clanId != 0L) {
            channelController.get().findChannelById(p.channelId)?.unreadCount ?: p.badgeCount
        } else {
            p.badgeCount
        }
        if (p.badgeCount == 0) {
            fullReadDedupAt[key] = SystemClock.elapsedRealtime()
        }
        if (p.badgeCount == 0) {
            if (p.clanId != 0L) {
                channelController.get().markChannelAsRead(p.channelId, p.timestampSeconds, p.messageId)
            } else {
                dialogsController.markDialogAsRead(p.channelId, seenTimestampSeconds = p.timestampSeconds, seenMessageId = p.messageId)
            }
        } else {
            if (p.clanId != 0L) {
                channelController.get().updateChannelLastSeen(
                    p.channelId, p.messageId, p.badgeCount, p.timestampSeconds
                )
            } else {
                dialogsController.updateDialogLastSeen(
                    p.channelId, p.messageId, p.badgeCount, p.timestampSeconds
                )
            }
        }
        sendLastSeenToSocket(key, p, socketBadgeCount)
        if (p.clanId != 0L) {
            scheduleClanReconcile(p.clanId)
        }
    }

    private fun sendLastSeenToSocket(key: String, p: PendingLastSeen, socketBadgeCount: Int) {
        appScope.launch {
            try {
                val mode = channelTypeToStreamMode(p.channelType)
                mezonSocket.writeLastSeenMessage(
                    p.clanId, p.channelId, mode, p.messageId, p.timestampSeconds, socketBadgeCount
                )
                retryLastSeenByKey.remove(key)
                retryLastSeenJobs.remove(key)
                Log.d(
                    TAG,
                    "writeLastSeen: ch=${p.channelId} clan=${p.clanId} mid=${p.messageId} socketBadge=$socketBadgeCount argBadge=${p.badgeCount}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "writeLastSeenMessage failed", e)
                queueLastSeenRetry(key, p, socketBadgeCount)
            }
        }
    }

    private fun queueLastSeenRetry(key: String, p: PendingLastSeen, socketBadgeCount: Int) {
        val prevAttempt = retryLastSeenByKey[key]?.attempt ?: 0
        val nextAttempt = prevAttempt + 1
        if (nextAttempt > RETRY_LAST_SEEN_MAX_ATTEMPTS) {
            Log.w(TAG, "writeLastSeen retry: giving up after $prevAttempt attempts key=$key")
            retryLastSeenByKey.remove(key)
            retryLastSeenJobs.remove(key)?.cancel()
            return
        }
        retryLastSeenByKey[key] = QueuedLastSeenWrite(p, socketBadgeCount, nextAttempt)
        val backoff = (RETRY_LAST_SEEN_MS shl minOf(nextAttempt - 1, 5))
            .coerceAtMost(RETRY_LAST_SEEN_MAX_MS)
        scheduleLastSeenRetry(key, backoff)
    }

    private fun scheduleLastSeenRetry(
        key: String,
        delayMs: Long = RETRY_LAST_SEEN_MS
    ) {
        retryLastSeenJobs[key]?.cancel()
        retryLastSeenJobs[key] = appScope.launch {
            delay(delayMs)
            val latest = retryLastSeenByKey[key] ?: return@launch
            if (shouldDeferLastSeen(latest.pending)) {
                retryLastSeenJobs.remove(key)
                return@launch
            }
            if (!mezonSocket.awaitConnected()) {
                retryLastSeenJobs.remove(key)
                queueLastSeenRetry(key, latest.pending, latest.socketBadgeCount)
                return@launch
            }
            retryLastSeenJobs.remove(key)
            sendLastSeenToSocket(key, latest.pending, latest.socketBadgeCount)
        }
    }

    private fun scheduleClanReconcile(clanId: Long) {
        if (clanId == 0L) return
        reconcileJobs[clanId]?.cancel()
        reconcileJobs[clanId] = appScope.launch(mainDispatcher) {
            delay(CLAN_RECONCILE_MS)
            reconcileJobs.remove(clanId)
            clansController.get().reconcileClanBadgeFromChannels(clanId)
        }
    }

    private fun shouldSkipDuplicateFullRead(key: String, p: PendingLastSeen): Boolean {
        if (p.badgeCount != 0) return false
        val now = SystemClock.elapsedRealtime()
        val last = fullReadDedupAt[key] ?: return false
        if (now - last >= DEDUP_FULL_READ_MS) return false
        if (p.clanId != 0L) {
            val ch = channelController.get().findChannelById(p.channelId) ?: return false
            return ch.unreadCount == 0 && !ch.hasUnread
        }
        val dm = dialogsController.getDialog(p.channelId) ?: return false
        return dm.unreadCount == 0
    }

    fun onIncomingUnreadChannelSignal(clanId: Long, channelId: Long, messageId: Long, createTimeSeconds: Long) {
        if (clanId == 0L) return
        val k = "$clanId-$channelId"
        val ts = createTimeSeconds and TS_MASK
        synchronized(channelActivityLock) {
            channelActivityTicks[k] = channelActivityTicks[k]?.let { prev ->
                ChannelActivityTick(
                    clanId,
                    channelId,
                    maxOf(prev.messageId, messageId),
                    maxOf(prev.ts, ts)
                )
            } ?: ChannelActivityTick(clanId, channelId, messageId, ts)
        }
        channelActivityJob?.cancel()
        channelActivityJob = appScope.launch(mainDispatcher) {
            delay(BATCH_CHANNEL_ACTIVITY_MS)
            val batch = synchronized(channelActivityLock) {
                val list = ArrayList(channelActivityTicks.values)
                channelActivityTicks.clear()
                list
            }
            channelActivityJob = null
            val clansTouched = HashSet<Long>()
            for (tick in batch) {
                channelController.get().updateLastSentMessage(tick.channelId, tick.messageId, tick.ts)
                clansController.get().setHasUnread(tick.clanId)
                clansTouched.add(tick.clanId)
            }
            if (batch.isNotEmpty()) {
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_BADGE
                )
            }
            for (cid in clansTouched) {
                scheduleClanReconcile(cid)
            }
        }
    }
}
