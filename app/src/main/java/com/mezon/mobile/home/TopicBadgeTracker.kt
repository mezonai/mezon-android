package com.mezon.mobile.home

import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.data.db.NotificationDao
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.chat.MessageEntity
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.notifications.NOTIF_CATEGORY_MENTIONS
import com.mezon.mobile.home.notifications.NotificationEntity
import com.mezon.mobile.home.profile.UserController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val NOTIFICATION_CODE_USER_MENTIONED = -9
private const val NOTIFICATION_CODE_USER_REPLIED = -11

@Singleton
class TopicBadgeTracker @Inject constructor(
    private val channelController: dagger.Lazy<ChannelController>,
    private val clansController: dagger.Lazy<ClansController>,
    private val userClanController: dagger.Lazy<UserClanController>,
    private val userController: dagger.Lazy<UserController>,
    private val notificationDao: NotificationDao,
    private val notificationCenter: NotificationCenter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private data class TopicBadgeEntry(
        val clanId: Long,
        val parentChannelId: Long,
        var count: Int
    )

    private val topicParentMap = HashMap<Long, TopicBadgeEntry>()
    private val topicBadgesByParent = HashMap<Long, Int>()
    private val processedTopicChannelKeys = HashSet<String>()
    private val processedTopicParentKeys = HashSet<String>()
    private val topicClanBadgeApplied = HashMap<Long, Int>()
    private val selfRoleIdsByClan = HashMap<Long, List<Long>>()
    private val lock = Any()

    fun onReconnect() {
        synchronized(lock) {
            topicParentMap.clear()
            topicBadgesByParent.clear()
            processedTopicChannelKeys.clear()
            processedTopicParentKeys.clear()
            topicClanBadgeApplied.clear()
            selfRoleIdsByClan.clear()
        }
        postTopicBadgeChanged()
    }

    fun getTopicBadge(topicId: Long): Int = synchronized(lock) {
        topicParentMap[topicId]?.count ?: 0
    }

    fun hydrateForParentChannel(clanId: Long, parentChannelId: Long) {
        if (clanId == 0L || parentChannelId == 0L) return
        appScope.launch(ioDispatcher) {
            val items = notificationDao.getTopicMentionsForChannel(
                NOTIF_CATEGORY_MENTIONS,
                clanId,
                parentChannelId,
                NOTIFICATION_CODE_USER_MENTIONED,
                NOTIFICATION_CODE_USER_REPLIED
            )
            hydrateFromNotifications(items)
        }
    }

    fun hydrateForClan(clanId: Long) {
        if (clanId == 0L) return
        appScope.launch(ioDispatcher) {
            val items = notificationDao.getTopicMentionsForClan(
                NOTIF_CATEGORY_MENTIONS,
                clanId,
                NOTIFICATION_CODE_USER_MENTIONED,
                NOTIFICATION_CODE_USER_REPLIED
            )
            hydrateFromNotifications(items)
        }
    }

    fun hydrateFromNotifications(notifications: List<NotificationEntity>) {
        if (notifications.isEmpty()) return
        var changed = false
        for (notification in notifications) {
            if (notification.topicId == 0L || notification.messageId == 0L) continue
            if (notification.code != NOTIFICATION_CODE_USER_MENTIONED &&
                notification.code != NOTIFICATION_CODE_USER_REPLIED
            ) continue
            val parentChannelId = notification.channelId
            if (parentChannelId == 0L) continue
            if (channelController.get().getCurrentOpenTopicId() == notification.topicId) continue
            if (isAlreadySeen(notification.topicId, notification.createTimeSeconds)) continue
            if (applyHydratedTopicParentBadge(
                    notification.clanId,
                    parentChannelId,
                    notification.topicId,
                    notification.messageId
                )
            ) {
                channelController.get().adjustChannelUnread(parentChannelId, 1, updateClanBadge = false)
                bumpTopicClanBadge(notification.clanId, notification.topicId, notification.messageId)
                changed = true
            }
        }
        if (changed) postTopicBadgeChanged()
    }

    fun tryIncrementForMention(
        entity: MessageEntity,
        parentChannelId: Long,
        topicId: Long,
        clanId: Long,
        currentUserId: Long
    ): Boolean {
        if (topicId == 0L || parentChannelId == 0L || clanId == 0L || entity.id == 0L) return false
        if (entity.isMe) return false
        if (channelController.get().getCurrentOpenTopicId() == topicId) return false
        val roleIds = resolveSelfRoleIds(clanId, currentUserId)
        if (!entity.isMentionOrReplyForUser(currentUserId, roleIds)) return false
        if (isAlreadySeen(topicId, entity.timestampSeconds)) return false
        val channelDone = incrementTopicChannel(clanId, topicId, entity.id)
        val parentDone = incrementTopicParentBadge(clanId, parentChannelId, topicId, entity.id)
        return channelDone || parentDone
    }

    fun tryIncrementFromNotification(
        clanId: Long,
        parentChannelId: Long,
        topicId: Long,
        messageId: Long
    ): Boolean {
        if (topicId == 0L || parentChannelId == 0L || clanId == 0L || messageId == 0L) return false
        if (channelController.get().getCurrentOpenTopicId() == topicId) return false
        val channelDone = incrementTopicChannel(clanId, topicId, messageId)
        val parentDone = incrementTopicParentBadge(clanId, parentChannelId, topicId, messageId)
        return channelDone || parentDone
    }

    fun resetTopic(topicId: Long) {
        val entry = synchronized(lock) { topicParentMap.remove(topicId) } ?: return
        val decrement = entry.count
        if (decrement <= 0) return
        val parentChannelId = entry.parentChannelId
        synchronized(lock) {
            val parentTotal = topicBadgesByParent[parentChannelId] ?: 0
            if (parentTotal > 0) {
                topicBadgesByParent[parentChannelId] = (parentTotal - decrement).coerceAtLeast(0)
            }
        }
        channelController.get().adjustChannelUnread(parentChannelId, -decrement, updateClanBadge = false)
        channelController.get().adjustChannelUnread(topicId, -decrement, updateClanBadge = false)
        val clanBadgeDecrement = synchronized(lock) { topicClanBadgeApplied.remove(topicId) ?: 0 }
        if (clanBadgeDecrement > 0) {
            clansController.get().updateClanBadgeCount(entry.clanId, -clanBadgeDecrement)
        }
        postTopicBadgeChanged()
    }

    private fun applyHydratedTopicParentBadge(
        clanId: Long,
        parentChannelId: Long,
        topicId: Long,
        messageId: Long
    ): Boolean {
        val parentKey = "${parentChannelId}_$messageId"
        val topicChannelKey = "${topicId}_$messageId"
        synchronized(lock) {
            if (processedTopicParentKeys.contains(parentKey)) return false
            processedTopicParentKeys.add(parentKey)
            processedTopicChannelKeys.add(topicChannelKey)
            trimProcessedKeys(processedTopicParentKeys)
            trimProcessedKeys(processedTopicChannelKeys)
            val existing = topicParentMap[topicId]
            if (existing != null) {
                existing.count += 1
            } else {
                topicParentMap[topicId] = TopicBadgeEntry(clanId, parentChannelId, 1)
            }
            topicBadgesByParent[parentChannelId] = (topicBadgesByParent[parentChannelId] ?: 0) + 1
        }
        return true
    }

    private fun incrementTopicChannel(clanId: Long, topicId: Long, messageId: Long): Boolean {
        val badgeKey = "${topicId}_$messageId"
        synchronized(lock) {
            if (processedTopicChannelKeys.contains(badgeKey)) return false
            processedTopicChannelKeys.add(badgeKey)
            trimProcessedKeys(processedTopicChannelKeys)
        }
        bumpTopicClanBadge(clanId, topicId, messageId)
        return true
    }

    private fun bumpTopicClanBadge(clanId: Long, topicId: Long, messageId: Long) {
        if (channelController.get().incrementUnread(topicId, messageId, updateClanBadge = true)) {
            synchronized(lock) {
                topicClanBadgeApplied[topicId] = (topicClanBadgeApplied[topicId] ?: 0) + 1
            }
        } else {
            synchronized(lock) {
                topicClanBadgeApplied[topicId] = (topicClanBadgeApplied[topicId] ?: 0) + 1
            }
            clansController.get().updateClanBadgeCount(clanId, 1)
        }
    }

    private fun incrementTopicParentBadge(
        clanId: Long,
        parentChannelId: Long,
        topicId: Long,
        messageId: Long
    ): Boolean {
        if (!applyHydratedTopicParentBadge(clanId, parentChannelId, topicId, messageId)) return false
        channelController.get().adjustChannelUnread(parentChannelId, 1, updateClanBadge = false)
        postTopicBadgeChanged()
        return true
    }

    private fun isAlreadySeen(topicId: Long, messageTimestampSeconds: Long): Boolean {
        val ch = channelController.get().findChannelById(topicId) ?: return false
        val lastSeen = ch.lastSeenMessageTs
        if (lastSeen == 0L || messageTimestampSeconds <= 0L) return false
        return messageTimestampSeconds <= lastSeen
    }

    private fun resolveSelfRoleIds(clanId: Long, currentUserId: Long): List<Long> {
        if (clanId == 0L || currentUserId == 0L) return emptyList()
        synchronized(lock) {
            selfRoleIdsByClan[clanId]?.let { return it }
        }
        val selfId = userController.get().userId.takeIf { it != 0L } ?: currentUserId
        val roles = userClanController.get().getClanMembers(clanId)
            .firstOrNull { it.userId == selfId }
            ?.roleIds
            .orEmpty()
        synchronized(lock) {
            selfRoleIdsByClan[clanId] = roles
        }
        return roles
    }

    private fun trimProcessedKeys(keys: HashSet<String>) {
        if (keys.size <= MAX_PROCESSED_KEYS) return
        val removeCount = keys.size - MAX_PROCESSED_KEYS / 2
        val iterator = keys.iterator()
        var removed = 0
        while (iterator.hasNext() && removed < removeCount) {
            iterator.next()
            iterator.remove()
            removed++
        }
    }

    private fun postTopicBadgeChanged() {
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.updateInterfaces, NotificationCenter.UPDATE_MASK_TOPIC
        )
    }

    companion object {
        private const val MAX_PROCESSED_KEYS = 500
    }
}
