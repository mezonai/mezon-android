package com.mezon.mobile.home.voice

import android.util.Log
import com.mezon.mezon.rtapi.VoiceEndedEvent
import com.mezon.mezon.rtapi.VoiceJoinedEvent
import com.mezon.mezon.rtapi.VoiceLeavedEvent
import com.mezon.mezon.rtapi.VoiceReactionSend
import com.mezon.mezon.rtapi.AIAgentEnabledEvent
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
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    val voiceMembersByClan = HashMap<Long, HashMap<Long, ArrayList<Long>>>()
    val inVoiceStatus = HashMap<Long, VoiceStatus>()

    var currentVoiceInfo: VoiceInfo? = null
        private set
    var isJoined: Boolean = false
        private set
    var isConnecting: Boolean = false
        private set
    var meetToken: String? = null
        private set

    private val aiAgentEnabled = HashMap<String, Boolean>()

    private val voiceMemberListFetchInflight = ConcurrentHashMap.newKeySet<Long>()

    init {
        appScope.launch { dispatcher.voiceJoinedEvents.collect { onVoiceJoined(it) } }
        appScope.launch { dispatcher.voiceLeavedEvents.collect { onVoiceLeaved(it) } }
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
            voiceMembersByClan.clear()
            inVoiceStatus.clear()
            currentVoiceInfo = null
            isJoined = false
            isConnecting = false
            meetToken = null
            aiAgentEnabled.clear()
        }
        voiceMemberListFetchInflight.clear()
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
        if (!noCache && !voiceMemberListFetchInflight.add(clanId)) return

        appScope.launch {
            try {
                sessionManager.withAutoRefresh { session ->
                val response = withContext(ioDispatcher) {
                    api.listChannelVoiceUsers(session.apiUrl, session.token, clanId)
                }
                synchronized(this@VoiceController) {
                    val clanMap = voiceMembersByClan.getOrPut(clanId) { HashMap() }
                    clanMap.clear()
                    for (voiceChannelUser in response.voiceChannelUsersList) {
                        val channelId = voiceChannelUser.channelId
                        val userIds = ArrayList<Long>()
                        for (uid in voiceChannelUser.userIdsList) {
                            val parsed = uid.toLongOrNull() ?: continue
                            userIds.add(parsed)
                            inVoiceStatus[parsed] = VoiceStatus(clanId, channelId)
                        }
                        if (userIds.isNotEmpty()) {
                            clanMap[channelId] = userIds
                        }
                    }
                }
                cacheTracker.markCalled(key)
                notificationCenter.postNotificationOnMainThread(
                    NotificationCenter.voiceChannelMembersChanged, clanId
                )
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchVoiceChannelMembers failed", e)
            } finally {
                if (!noCache) voiceMemberListFetchInflight.remove(clanId)
            }
        }
    }

    suspend fun joinVoiceChannel(channelId: Long, clanId: Long, channelLabel: String): String? {
        Log.d(TAG, "joinVoiceChannel: channelId=$channelId clanId=$clanId label=$channelLabel")
        synchronized(this) {
            if (isJoined && currentVoiceInfo?.channelId == channelId) {
                Log.d(TAG, "Already joined this channel, returning existing token")
                return meetToken
            }
            if (isJoined || isConnecting) {
                Log.d(TAG, "Already in another channel or connecting, leaving first")
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
                Log.d(TAG, "Calling generateMeetToken: apiUrl=${session.apiUrl} roomName=$roomName")
                val response = withContext(ioDispatcher) {
                    api.generateMeetToken(session.apiUrl, session.token, channelId, roomName)
                }
                val token = response.token
                Log.d(TAG, "generateMeetToken response: token=${if (token.isNullOrEmpty()) "NULL/EMPTY" else "${token.length} chars"}")
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

    fun onRoomConnected(channelId: Long) {
        synchronized(this) {
            if (currentVoiceInfo?.channelId != channelId) return
            isJoined = true
            isConnecting = false
        }
    }

    fun leaveVoiceChannel() {
        synchronized(this) {
            currentVoiceInfo = null
            meetToken = null
            isJoined = false
            isConnecting = false
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.voiceLeftRoom)
    }

    fun onDisconnectedFromRoom(reason: String) {
        synchronized(this) {
            currentVoiceInfo = null
            meetToken = null
            isJoined = false
            isConnecting = false
        }
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.voiceRoomDisconnected, reason
        )
    }

    suspend fun kickParticipant(clanId: Long, channelId: Long, roomName: String, username: String) {
        withContext(ioDispatcher) {
            sessionManager.withAutoRefresh { session ->
                api.removeMeetParticipant(
                    session.apiUrl,
                    session.token,
                    clanId,
                    channelId,
                    roomName,
                    username
                )
            }
        }
    }

    suspend fun muteParticipant(clanId: Long, channelId: Long, roomName: String, username: String) {
        withContext(ioDispatcher) {
            sessionManager.withAutoRefresh { session ->
                api.muteMeetParticipant(
                    session.apiUrl,
                    session.token,
                    clanId,
                    channelId,
                    roomName,
                    username
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
            val clanMap = voiceMembersByClan.getOrPut(clanId) { HashMap() }
            val members = clanMap.getOrPut(channelId) { ArrayList() }
            if (!members.contains(userId)) {
                members.add(userId)
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
            voiceMembersByClan[clanId]?.get(channelId)?.remove(userId)
            val status = inVoiceStatus[userId]
            if (status != null && status.channelId == channelId) {
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
            val removed = voiceMembersByClan[clanId]?.remove(channelId)
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

    @Suppress("UNUSED_PARAMETER")
    private fun onVoiceStarted(event: VoiceStartedEvent) {
        Log.d(TAG, "Voice started: clan=${event.clanId} channel=${event.voiceChannelId}")
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
        Log.d(TAG, "aiAgent enabled=$enabled clanId=$clanId channelId=$channelId $reason")
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
                Log.d(
                    TAG,
                    "updateAiAgent try=${index + 1}/${candidates.size} enabled=$targetEnabled clanId=$clanId channelId=$channelId room=$candidate"
                )
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
            currentVoiceInfo?.clanId == clanId && currentVoiceInfo?.channelId == channelId
        }
        if (shouldDisconnect) {
            onDisconnectedFromRoom("deleted")
        }
    }

    private fun onClanDeleted(clanId: Long) {
        if (clanId == 0L) return
        val shouldDisconnect = synchronized(this) {
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
