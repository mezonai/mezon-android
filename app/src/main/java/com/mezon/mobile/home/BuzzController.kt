package com.mezon.mobile.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mezon.mezon.api.ChannelMessage
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.notification.ActiveChannelTracker
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuzzController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationCenter: NotificationCenter,
    private val activeChannelTracker: ActiveChannelTracker,
    private val channelController: Lazy<ChannelController>,
    dispatcher: SocketEventDispatcher,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val state = BuzzState()
    private val handler = Handler(Looper.getMainLooper())
    private var sound: BuzzSound? = null
    private val changeLock = Any()
    private val pendingChangedChannels = LinkedHashSet<Long>()
    private var changePosted = false
    private val publishChanges = Runnable {
        val channelIds = synchronized(changeLock) {
            changePosted = false
            pendingChangedChannels.toLongArray().also { pendingChangedChannels.clear() }
        }
        if (channelIds.isNotEmpty()) {
            notificationCenter.postNotificationName(NotificationCenter.buzzStateChanged, channelIds)
        }
    }

    init {
        handler.post { getSound() }
        appScope.launch {
            dispatcher.lastSeenMessageEvents.collect { event ->
                clearSeen(event.channelId, event.messageId)
            }
        }
        appScope.launch {
            dispatcher.markAsRead.collect { event ->
                runOnMain {
                    val channels = channelController.get()
                    val targetIds = if (event.channelId != 0L) setOf(event.channelId) else emptySet()
                    val changed = state.clearRows(targetIds) { clanId, channelId ->
                        when {
                            event.channelId != 0L -> channelId == event.channelId ||
                                channels.findChannelById(channelId)?.parentId == event.channelId
                            event.clanId == 0L || clanId != event.clanId -> false
                            event.categoryId == 0L -> true
                            else -> {
                                val channel = channels.findChannelById(channelId)
                                val categoryId = if (channel?.isThread == true) {
                                    channels.findChannelById(channel.parentId)?.categoryId
                                } else channel?.categoryId
                                categoryId == event.categoryId
                            }
                        }
                    }
                    notifyChanged(changed)
                }
            }
        }
    }

    fun receive(message: ChannelMessage, currentUserId: Long) {
        if (message.code != MessageEntity.CODE_MESSAGE_BUZZ || message.senderId == currentUserId) return
        runOnMain {
            val reception = state.receive(message.clanId, message.channelId, message.topicId, message.messageId,
                activeChannelTracker.isViewing(message.channelId, message.topicId))
            if (!reception.shouldPlaySound) return@runOnMain
            if (reception == BuzzState.Reception.BADGE_ADDED) notifyChanged(setOf(message.channelId))
            playSound()
        }
    }

    fun hasBuzz(channelId: Long): Boolean = state.hasBuzz(channelId)

    fun clearTarget(targetId: Long) = runOnMain {
        notifyChanged(state.clearTarget(targetId))
    }

    fun clearSeen(targetId: Long, messageId: Long) = runOnMain {
        notifyChanged(state.clearTarget(targetId, messageId))
    }

    fun removeChannel(channelId: Long) = removeChannels(listOf(channelId))

    fun removeChannels(channelIds: Collection<Long>) {
        val targets = channelIds.toSet()
        runOnMain { notifyChanged(state.clearRows(targets) { _, id -> id in targets }) }
    }

    // Registration changes happen on the UI thread; read visibility and mutate Buzz together there.
    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }

    private fun notifyChanged(channelIds: Set<Long>) {
        if (channelIds.isEmpty()) return
        synchronized(changeLock) {
            pendingChangedChannels.addAll(channelIds)
            if (changePosted) return
            changePosted = true
            handler.post(publishChanges)
        }
    }

    private fun getSound(): BuzzSound? {
        sound?.let { return it }
        return try {
            BuzzSound(context).also { sound = it }
        } catch (e: Exception) {
            Log.e("BuzzController", "Failed to initialize Buzz sound", e)
            null
        }
    }

    fun playSound() {
        handler.post { getSound()?.play() }
    }

    fun cleanup() = runOnMain {
        state.reset()
        handler.removeCallbacksAndMessages(null)
        synchronized(changeLock) {
            pendingChangedChannels.clear()
            changePosted = false
        }
        sound?.release()
        sound = null
    }
}
