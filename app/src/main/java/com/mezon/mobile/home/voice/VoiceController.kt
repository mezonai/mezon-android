package com.mezon.mobile.home.voice

import android.content.Context
import android.util.Log
import com.mezon.mezon.rtapi.VoiceEndedEvent
import com.mezon.mezon.rtapi.VoiceJoinedEvent
import com.mezon.mezon.rtapi.VoiceLeavedEvent
import com.mezon.mezon.rtapi.VoiceReactionSend
import com.mezon.mezon.rtapi.AIAgentEnabledEvent
import com.mezon.mezon.rtapi.ScreenShareEvent
import com.mezon.mezon.rtapi.VoiceStartedEvent
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.network.SocketEventDispatcher
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.profile.UserController
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceController"

@Singleton
class VoiceController @Inject constructor(
    private val api: MezonApi,
    private val socket: MezonSocket,
    private val dispatcher: SocketEventDispatcher,
    private val sessionManager: SessionManager,
    private val userClanController: UserClanController,
    private val userController: UserController,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    val voiceMembersByClan = HashMap<Long, HashMap<Long, ArrayList<Long>>>()
    val inVoiceStatus = HashMap<Long, VoiceStatus>()
    private var voicePresence = VoicePeerPresence()
    private var voicePresenceGeneration = 0L
    private val screenSharingByClan = HashMap<Long, HashMap<Long, HashSet<Long>>>()

    var currentVoiceInfo: VoiceInfo? = null
        private set
    var isJoined: Boolean = false
        private set
    var isConnecting: Boolean = false
        private set
    var meetToken: String? = null
        private set
    var isLocalVideoEnabled: Boolean = false

    private val aiAgentEnabled = HashMap<String, Boolean>()

    private val voiceMemberListFetchInflight = ConcurrentHashMap.newKeySet<Long>()

    init {
        appScope.launch { dispatcher.voiceJoinedEvents.collect { onVoiceJoined(it) } }
        appScope.launch { dispatcher.voiceLeavedEvents.collect { onVoiceLeaved(it) } }
        appScope.launch { dispatcher.screenShareEvents.collect { onScreenShare(it) } }
        appScope.launch { dispatcher.voiceEndedEvents.collect { onVoiceEnded(it) } }
        appScope.launch { dispatcher.voiceStartedEvents.collect { onVoiceStarted(it) } }
        appScope.launch { dispatcher.voiceReactionEvents.collect { onVoiceReaction(it) } }
        appScope.launch { dispatcher.channelDeletedEvents.collect { onChannelDeleted(it.clanId, it.channelId) } }
        appScope.launch { dispatcher.clanDeletedEvents.collect { onClanDeleted(it.clanId) } }
        appScope.launch { dispatcher.userClanRemovedEvents.collect { onUserRemovedFromClan(it.clanId) } }
        appScope.launch { dispatcher.aiagentEnabledEvents.collect { onAiAgentEnabled(it) } }
    }

    fun cleanup() {
        synchronized(this) {
            voicePresenceGeneration++
            voicePresence = VoicePeerPresence()
            voiceMemberListFetchInflight.clear()
            voiceMembersByClan.clear()
            inVoiceStatus.clear()
            screenSharingByClan.clear()
            currentVoiceInfo = null
            isJoined = false
            isConnecting = false
            isLocalVideoEnabled = false
            meetToken = null
            aiAgentEnabled.clear()
        }
        VoiceChannelForegroundService.stop(appContext)
    }

    @Synchronized
    fun getVoiceMembersForChannel(channelId: Long, clanId: Long): List<Long> {
        return voiceMembersByClan[clanId]?.get(channelId)?.let { ArrayList(it) } ?: emptyList()
    }

    @Synchronized
    fun getVoiceMemberCount(channelId: Long, clanId: Long): Int {
        return voiceMembersByClan[clanId]?.get(channelId)?.size ?: 0
    }

    @Synchronized
    fun getScreenSharingUsersForChannel(channelId: Long, clanId: Long): Set<Long> {
        return screenSharingByClan[clanId]?.get(channelId)?.let { HashSet(it) } ?: emptySet()
    }

    @Synchronized
    fun isUserInVoice(userId: Long): Boolean {
        return inVoiceStatus.containsKey(userId)
    }

    @Synchronized
    fun getUserVoiceStatus(userId: Long): VoiceStatus? {
        return inVoiceStatus[userId]
    }

    fun fetchVoiceChannelMembers(clanId: Long, noCache: Boolean = false) {
        if (clanId == 0L) return
        val key = apiCacheKey("ListChannelVoiceUsers", clanId)
        if (cacheTracker.shouldCall(key, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) return
        val generation = synchronized(this) {
            if (!voiceMemberListFetchInflight.add(clanId)) return
            voicePresenceGeneration
        }

        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                    repeat(3) {
                        val revision = synchronized(this@VoiceController) { voicePresence.revision(clanId) }
                        val response = withContext(ioDispatcher) {
                            api.listChannelVoiceUsers(session.apiUrl, session.token, clanId)
                        }
                        val applied = synchronized(this@VoiceController) {
                            if (generation != voicePresenceGeneration) return@withAutoRefresh
                            if (revision != voicePresence.revision(clanId)) {
                                false
                            } else {
                                val clanMap = voiceMembersByClan.getOrPut(clanId) { HashMap() }
                                clanMap.clear()
                                inVoiceStatus.entries.removeAll { it.value.clanId == clanId }
                                val sharingMap = screenSharingByClan.getOrPut(clanId) { HashMap() }
                                sharingMap.clear()
                                val peers = ArrayList<VoicePeerPresence.Entry>()
                                for (room in response.voiceChannelUsersList) {
                                    val channelId = room.channelId
                                    val aligned = room.userIdsCount == room.peerIdsCount
                                    Log.d(TAG, "[MezonSFU][presence] snapshot clan=$clanId channel=$channelId users=${room.userIdsList} peers=${room.peerIdsList} aligned=$aligned")
                                    val userIds = LinkedHashSet<Long>()
                                    for ((index, uid) in room.userIdsList.withIndex()) {
                                        val userId = uid.toLongOrNull() ?: continue
                                        userIds.add(userId)
                                        peers.add(VoicePeerPresence.Entry(channelId, userId, if (aligned) room.getPeerIds(index) else 0))
                                        inVoiceStatus[userId] = VoiceStatus(clanId, channelId)
                                    }
                                    val sharingIds = room.shareScreenIdsList.mapNotNull { it.toLongOrNull() }
                                        .filterTo(HashSet()) { it in userIds }
                                    if (sharingIds.isNotEmpty()) sharingMap[channelId] = sharingIds
                                    if (userIds.isNotEmpty()) clanMap[channelId] = ArrayList(userIds)
                                }
                                voicePresence.replaceClan(clanId, peers)
                                true
                            }
                        }
                        if (applied) {
                            cacheTracker.markCalled(key)
                            notificationCenter.postNotificationOnMainThread(NotificationCenter.voiceChannelMembersChanged, clanId)
                            return@withAutoRefresh
                        }
                    }
                    cacheTracker.invalidate(key)
                    Log.d(TAG, "[MezonSFU][presence] snapshot skipped: membership changed during refresh clan=$clanId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchVoiceChannelMembers failed", e)
                cacheTracker.invalidate(key)
            } finally {
                synchronized(this@VoiceController) {
                    if (generation == voicePresenceGeneration) voiceMemberListFetchInflight.remove(clanId)
                }
            }
        }
    }

    suspend fun joinVoiceChannel(channelId: Long, clanId: Long, channelLabel: String): String? {
        synchronized(this) {
            if (isJoined && currentVoiceInfo?.channelId == channelId) {
                return meetToken
            }
        }
        if (isJoined || isConnecting) {
            leaveVoiceChannel()
        }
        synchronized(this) {
            isConnecting = true
        }
        return try {
            sessionManager.withAutoRefresh { session ->
                val roomName = channelId.toString()
                val response = withContext(ioDispatcher) {
                    api.generateMeetToken(
                        session.apiUrl, session.token, channelId, roomName,
                        meetTokenMetadata(clanId, session.userId.toLongOrNull() ?: 0L)
                    )
                }
                val token = response.token
                if (token.isNullOrEmpty()) {
                    synchronized(this) { isConnecting = false }
                    return@withAutoRefresh null
                }
                synchronized(this) {
                    currentVoiceInfo = VoiceInfo(channelId, clanId, channelLabel, roomName)
                    meetToken = token
                    isJoined = false
                }
                token
            }
        } catch (e: Exception) {
            Log.e(TAG, "joinVoiceChannel failed", e)
            synchronized(this) { isConnecting = false }
            null
        }
    }

    suspend fun refreshMeetToken(channelId: Long, clanId: Long): String? {
        return try {
            sessionManager.withAutoRefresh { session ->
                val roomName = channelId.toString()
                val response = withContext(ioDispatcher) {
                    api.generateMeetToken(
                        session.apiUrl, session.token, channelId, roomName,
                        meetTokenMetadata(clanId, session.userId.toLongOrNull() ?: 0L)
                    )
                }
                val token = response.token
                if (!token.isNullOrEmpty()) {
                    synchronized(this) { meetToken = token }
                }
                token
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshMeetToken failed", e)
            null
        }
    }

    private fun meetTokenMetadata(clanId: Long, userId: Long): String {
        val member = userClanController.getClanMembers(clanId).firstOrNull { it.userId == userId }
        val profile = userClanController.getUserById(userId)
        val isCurrentUser = userController.userId == userId
        val name = listOf(
            member?.clanNick,
            member?.displayName,
            userController.displayName.takeIf { isCurrentUser },
            profile?.displayName,
            member?.username,
            userController.username.takeIf { isCurrentUser },
            profile?.username
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        val avatar = listOf(
            member?.clanAvatar,
            member?.avatarUrl,
            userController.avatarUrl.takeIf { isCurrentUser },
            profile?.avatarUrl
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        return buildJsonObject {
            put("username", name)
            put("avatar", avatar)
        }.toString()
    }

    fun onRoomConnected(channelId: Long) {
        val label: String
        synchronized(this) {
            if (currentVoiceInfo?.channelId != channelId) return
            isJoined = true
            isConnecting = false
            label = currentVoiceInfo?.channelLabel.orEmpty()
        }
        VoiceChannelForegroundService.start(appContext, label)
    }

    fun leaveVoiceChannel() {
        synchronized(this) {
            currentVoiceInfo = null
            meetToken = null
            isJoined = false
            isConnecting = false
        }
        VoiceChannelForegroundService.stop(appContext)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.voiceLeftRoom)
    }

    fun onDisconnectedFromRoom(reason: String) {
        synchronized(this) {
            currentVoiceInfo = null
            meetToken = null
            isJoined = false
            isConnecting = false
        }
        VoiceChannelForegroundService.stop(appContext)
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceRoomDisconnected, reason
        )
    }

    suspend fun kickParticipant(clanId: Long, channelId: Long, userId: Long): String {
        return withContext(ioDispatcher) {
            sessionManager.withAutoRefresh { session ->
                api.removeMeetParticipant(
                    session.apiUrl,
                    session.token,
                    clanId,
                    channelId,
                    userId
                )
            }
        }
    }

    suspend fun muteParticipant(clanId: Long, channelId: Long, userId: Long): String {
        return withContext(ioDispatcher) {
            sessionManager.withAutoRefresh { session ->
                api.muteMeetParticipant(
                    session.apiUrl,
                    session.token,
                    clanId,
                    channelId,
                    userId
                )
            }
        }
    }

    fun sendVoiceReaction(emojis: List<String>, channelId: Long) {
        appScope.launch {
            try {
                socket.writeVoiceReaction(emojis, channelId)
            } catch (e: Exception) {
                Log.e(TAG, "sendVoiceReaction failed", e)
            }
        }
    }

    private fun onVoiceJoined(event: VoiceJoinedEvent) {
        val clanId = event.clanId
        val channelId = event.voiceChannelId
        val userId = event.userId
        if (clanId == 0L || channelId == 0L || userId == 0L) return

        synchronized(this) {
            val before = voicePresence.peerIds(clanId, channelId, userId)
            voicePresence.joined(clanId, channelId, userId, event.peerId)
            Log.d(TAG, "[MezonSFU][presence] joined clan=$clanId channel=$channelId user=$userId peer=${event.peerId} before=$before after=${voicePresence.peerIds(clanId, channelId, userId)}")
            val clanMap = voiceMembersByClan.getOrPut(clanId) { HashMap() }
            val members = clanMap.getOrPut(channelId) { ArrayList() }
            if (!members.contains(userId)) {
                members.add(userId)
                screenSharingByClan[clanId]?.get(channelId)?.remove(userId)
            }
            inVoiceStatus[userId] = VoiceStatus(clanId, channelId)
        }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceChannelMembersChanged, clanId, channelId
        )
    }

    private fun onVoiceLeaved(event: VoiceLeavedEvent) {
        val clanId = event.clanId
        val channelId = event.voiceChannelId
        val userId = event.voiceUserId
        if (clanId == 0L || channelId == 0L || userId == 0L) return

        synchronized(this) {
            val before = voicePresence.peerIds(clanId, channelId, userId)
            val removeUser = voicePresence.left(clanId, channelId, userId, event.peerId)
            Log.d(TAG, "[MezonSFU][presence] leaved clan=$clanId channel=$channelId user=$userId peer=${event.peerId} before=$before after=${voicePresence.peerIds(clanId, channelId, userId)} removeUser=$removeUser")
            if (!removeUser) return
            voiceMembersByClan[clanId]?.get(channelId)?.remove(userId)
            screenSharingByClan[clanId]?.get(channelId)?.remove(userId)
            val status = inVoiceStatus[userId]
            if (status != null && status.clanId == clanId && status.channelId == channelId) {
                inVoiceStatus.remove(userId)
            }
        }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceChannelMembersChanged, clanId, channelId
        )
    }

    private fun onVoiceEnded(event: VoiceEndedEvent) {
        val clanId = event.clanId
        val channelIdStr = event.voiceChannelId
        val channelId = channelIdStr.toLongOrNull() ?: return
        if (clanId == 0L || channelId == 0L) return

        val shouldDisconnect = synchronized(this) {
            voicePresence.removeRoom(clanId, channelId)
            val removed = voiceMembersByClan[clanId]?.remove(channelId)
            screenSharingByClan[clanId]?.remove(channelId)
            removed?.forEach { uid ->
                val status = inVoiceStatus[uid]
                if (status != null && status.channelId == channelId) {
                    inVoiceStatus.remove(uid)
                }
            }
            currentVoiceInfo?.channelId == channelId
        }

        if (shouldDisconnect) {
            onDisconnectedFromRoom("deleted")
        }

        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceChannelMembersChanged, clanId, channelId
        )
    }

    private fun onScreenShare(event: ScreenShareEvent) {
        val clanId = event.clanId
        val channelId = event.voiceChannelId
        val userId = event.userId
        if (clanId == 0L || channelId == 0L || userId == 0L) return

        val changed = synchronized(this) {
            val isMember = voiceMembersByClan[clanId]?.get(channelId)?.contains(userId) == true
            if (!isMember) {
                false
            } else {
                val sharing = screenSharingByClan.getOrPut(clanId) { HashMap() }.getOrPut(channelId) { HashSet() }
                if (event.isSharing) sharing.add(userId) else sharing.remove(userId)
            }
        }
        if (changed) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.voiceChannelMembersChanged, clanId, channelId
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onVoiceStarted(event: VoiceStartedEvent) {
    }

    @Synchronized
    fun isAiAgentEnabled(clanId: Long, channelId: Long): Boolean {
        return aiAgentEnabled["$clanId:$channelId"] == true
    }

    private fun aiAgentKey(clanId: Long, channelId: Long) = "$clanId:$channelId"

    private fun applyAiAgentEnabledState(clanId: Long, channelId: Long, enabled: Boolean, reason: String) {
        synchronized(this) {
            aiAgentEnabled[aiAgentKey(clanId, channelId)] = enabled
        }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceAiAgentStateChanged,
            clanId, channelId, enabled
        )
    }

    private fun onAiAgentEnabled(event: AIAgentEnabledEvent) {
        applyAiAgentEnabledState(
            event.clanId, event.channelId, event.enabled,
            "socket room=${event.roomName}"
        )
    }

    suspend fun addAiAgentToChannel(clanId: Long, channelId: Long, roomName: String, fallbackRoomNames: List<String> = emptyList()) {
        updateAiAgentWithFallback(
            clanId = clanId,
            channelId = channelId,
            primaryRoomName = roomName,
            fallbackRoomNames = fallbackRoomNames,
            targetEnabled = true
        )
    }

    suspend fun disconnectAiAgent(clanId: Long, channelId: Long, roomName: String, fallbackRoomNames: List<String> = emptyList()) {
        updateAiAgentWithFallback(
            clanId = clanId,
            channelId = channelId,
            primaryRoomName = roomName,
            fallbackRoomNames = fallbackRoomNames,
            targetEnabled = false
        )
    }

    private suspend fun updateAiAgentWithFallback(
        clanId: Long,
        channelId: Long,
        primaryRoomName: String,
        fallbackRoomNames: List<String>,
        targetEnabled: Boolean
    ) {
        val candidates = linkedSetOf<String>()
        if (primaryRoomName.isNotBlank()) candidates.add(primaryRoomName)
        for (name in fallbackRoomNames) {
            if (name.isNotBlank()) candidates.add(name)
        }
        candidates.add(channelId.toString())
        if (candidates.isEmpty()) candidates.add(primaryRoomName)

        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            try {
                sessionManager.withAutoRefresh { session ->
                    withContext(ioDispatcher) {
                        if (targetEnabled) {
                            api.addAgentToChannel(session.apiUrl, session.token, channelId, candidate)
                        } else {
                            api.disconnectAgent(session.apiUrl, session.token, channelId, candidate)
                        }
                    }
                }
                val reason = if (targetEnabled) "rpcAddAgent room=$candidate" else "rpcDisconnectAgent room=$candidate"
                applyAiAgentEnabledState(clanId, channelId, targetEnabled, reason)
                return
            } catch (e: Throwable) {
                lastError = e
                Log.w(
                    TAG,
                    "updateAiAgent failed enabled=$targetEnabled clanId=$clanId channelId=$channelId room=$candidate",
                    e
                )
            }
        }
        throw RuntimeException("updateAiAgent failed for all room candidates", lastError)
    }

    private fun onVoiceReaction(event: VoiceReactionSend) {
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceReactionReceived,
            event.emojisList,
            event.channelId,
            event.senderId
        )
    }

    private fun onChannelDeleted(clanId: Long, channelId: Long) {
        if (clanId == 0L || channelId == 0L) return
        val shouldDisconnect = synchronized(this) {
            voicePresence.removeRoom(clanId, channelId)
            voiceMembersByClan[clanId]?.remove(channelId)
            screenSharingByClan[clanId]?.remove(channelId)
            inVoiceStatus.entries.removeAll { it.value.clanId == clanId && it.value.channelId == channelId }
            currentVoiceInfo?.clanId == clanId && currentVoiceInfo?.channelId == channelId
        }
        if (shouldDisconnect) {
            onDisconnectedFromRoom("deleted")
        }
    }

    private fun onClanDeleted(clanId: Long) {
        if (clanId == 0L) return
        val shouldDisconnect = synchronized(this) {
            voicePresence.removeClan(clanId)
            voiceMembersByClan.remove(clanId)
            screenSharingByClan.remove(clanId)
            inVoiceStatus.entries.removeAll { it.value.clanId == clanId }
            currentVoiceInfo?.clanId == clanId
        }
        if (shouldDisconnect) {
            onDisconnectedFromRoom("deleted")
        }
    }

    private fun onUserRemovedFromClan(clanId: Long) {
        if (clanId == 0L) return
        val shouldDisconnect = synchronized(this) {
            currentVoiceInfo?.clanId == clanId
        }
        if (shouldDisconnect) {
            onDisconnectedFromRoom("removed")
        }
    }
}
