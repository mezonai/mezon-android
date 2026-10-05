package com.mezon.mobile.home.chat.input

import android.os.SystemClock
import android.util.Log
import com.mezon.mezon.api.MentionUser
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.network.HttpRpcStatusException
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.MezonSocket
import com.mezon.mobile.network.SocketRpcServerException
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MentionSearchController"
private const val MIN_QUERY_CHARS = 2
private const val MAX_QUERY_CHARS = 64
private const val SERVER_PAGE_SIZE = 50
private const val DEBOUNCE_MS = 200L
private const val UNSUPPORTED_RETRY_MS = 10 * 60 * 1000L
private const val MAX_CACHED_ANSWERS = 32
private const val UNKNOWN_API_CODE = 404

data class MentionSearchResult(
    val members: List<ClanMember>,
    val pending: Boolean
) {
    companion object {
        val NONE = MentionSearchResult(emptyList(), false)
    }
}

@Singleton
class MentionSearchController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val userClanController: UserClanController,
    private val channelController: ChannelController,
    private val mezonSocket: MezonSocket,
    private val notificationCenter: NotificationCenter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private data class Answer(
        val members: List<ClanMember>,
        val complete: Boolean,
        val failed: Boolean
    )

    private data class Request(
        val generation: Long,
        val clanId: Long,
        val channelId: Long,
        val key: String,
        val text: String
    )

    private val answers = object : LinkedHashMap<String, Answer>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>?): Boolean =
            size > MAX_CACHED_ANSWERS
    }
    private var scopeClanId = 0L
    private var scopeChannelId = 0L
    private var generation = 0L
    private var wantedKey: String? = null
    private var wantedText = ""
    private var lastInputAt = 0L
    private var debounceToken = 0L
    private var requestInFlight = false
    private var unsupportedUntil = 0L

    init {
        appScope.launch {
            sessionManager.sessionFlow.collect { session ->
                if (session == null) endSession()
            }
        }
        appScope.launch {
            mezonSocket.reconnected.collect { retryAfterReconnect() }
        }
    }

    fun search(clanId: Long, channelId: Long, keyword: String): MentionSearchResult {
        val text = Normalizer.normalize(keyword.trim(), Normalizer.Form.NFC)
        if (clanId == 0L || !isSearchable(text) || !userClanController.isClanRosterCapped(clanId)) {
            cancelPending()
            return MentionSearchResult.NONE
        }
        val targetChannelId = privateScopeChannelId(clanId, channelId)
        val key = text.lowercase()
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now < unsupportedUntil) {
                cancelPendingLocked()
                return MentionSearchResult.NONE
            }
            if (scopeClanId != clanId || scopeChannelId != targetChannelId) {
                resetLocked()
                scopeClanId = clanId
                scopeChannelId = targetChannelId
            }
            val known = lookupLocked(key)
            if (known != null) {
                cancelPendingLocked()
                return MentionSearchResult(known, false)
            }
            if (wantedKey != key) {
                wantedKey = key
                wantedText = text
                lastInputAt = now
                scheduleLocked(now)
            }
            return MentionSearchResult(emptyList(), true)
        }
    }

    fun cancelPending() {
        synchronized(this) { cancelPendingLocked() }
    }

    private fun cancelPendingLocked() {
        wantedKey = null
        wantedText = ""
        debounceToken++
    }

    private fun resetLocked() {
        generation++
        answers.clear()
        cancelPendingLocked()
    }

    private fun endSession() {
        synchronized(this) {
            resetLocked()
            scopeClanId = 0L
            scopeChannelId = 0L
            unsupportedUntil = 0L
        }
    }

    private fun retryAfterReconnect() {
        synchronized(this) {
            unsupportedUntil = 0L
            answers.values.removeAll { it.failed }
        }
    }

    private fun isSearchable(text: String): Boolean {
        if (text.contains("  ")) return false
        var chars = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!Character.isISOControl(codePoint)) chars++
            index += Character.charCount(codePoint)
        }
        return chars in MIN_QUERY_CHARS..MAX_QUERY_CHARS
    }

    private fun privateScopeChannelId(clanId: Long, channelId: Long): Long {
        val channel = channelController.findChannelById(channelId, clanId) ?: return 0L
        val target = if (channel.parentId != 0L) {
            channelController.findChannelById(channel.parentId, clanId) ?: channel
        } else {
            channel
        }
        return if (target.isPrivate) target.channelId else 0L
    }

    private fun lookupLocked(key: String): List<ClanMember>? {
        answers[key]?.let { return it.members }
        for ((prefix, answer) in answers) {
            if (answer.complete && key.startsWith(prefix)) {
                val words = queryWords(key)
                return answer.members.filter { matchesQuery(it, words) }
            }
        }
        return null
    }

    private fun queryWords(key: String): List<String> =
        InputSuggestionsController.removeDiacritics(key).split(' ').filter { it.isNotEmpty() }

    private fun matchesQuery(member: ClanMember, words: List<String>): Boolean {
        val fields = listOf(member.username, member.displayName, member.clanNick)
            .map { InputSuggestionsController.removeDiacritics(it.lowercase()) }
        return words.all { word -> fields.any { it.contains(word) } }
    }

    private fun scheduleLocked(now: Long) {
        val token = ++debounceToken
        if (requestInFlight) return
        val waitMs = (DEBOUNCE_MS - (now - lastInputAt)).coerceAtLeast(0L)
        appScope.launch(ioDispatcher) {
            delay(waitMs)
            val request = claimRequest(token) ?: return@launch
            runRequest(request)
        }
    }

    private fun claimRequest(token: Long): Request? {
        synchronized(this) {
            if (token != debounceToken || requestInFlight) return null
            val key = wantedKey ?: return null
            if (lookupLocked(key) != null) return null
            requestInFlight = true
            return Request(generation, scopeClanId, scopeChannelId, key, wantedText)
        }
    }

    private suspend fun runRequest(request: Request) {
        val answer = try {
            fetch(request)
        } catch (e: CancellationException) {
            synchronized(this) { requestInFlight = false }
            throw e
        }
        val answersCurrentScope = synchronized(this) {
            requestInFlight = false
            val sameGeneration = request.generation == generation
            if (sameGeneration) answers[request.key] = answer
            val wanted = wantedKey
            if (wanted != null && lookupLocked(wanted) == null) {
                scheduleLocked(SystemClock.elapsedRealtime())
            }
            sameGeneration
        }
        if (answersCurrentScope) {
            notificationCenter.postNotificationOnMainThread(
                NotificationCenter.mentionSearchDidLoad, request.clanId
            )
        }
    }

    private suspend fun fetch(request: Request): Answer {
        return try {
            val users = sessionManager.withAutoRefresh { session ->
                api.searchMentionUsers(
                    session.apiUrl,
                    session.token,
                    request.clanId,
                    request.channelId,
                    request.text
                ).usersList
            }
            Answer(
                members = users.toClanMembers(request.clanId),
                complete = users.size < SERVER_PAGE_SIZE,
                failed = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "searchMentionUsers failed", e)
            if (isUnknownApi(e)) {
                synchronized(this) {
                    unsupportedUntil = SystemClock.elapsedRealtime() + UNSUPPORTED_RETRY_MS
                }
            }
            Answer(members = emptyList(), complete = false, failed = true)
        }
    }

    private fun isUnknownApi(e: Exception): Boolean = when (e) {
        is SocketRpcServerException -> e.code == UNKNOWN_API_CODE
        is HttpRpcStatusException -> e.code == UNKNOWN_API_CODE
        else -> false
    }

    private fun List<MentionUser>.toClanMembers(clanId: Long): List<ClanMember> {
        val members = ArrayList<ClanMember>(size)
        val seen = HashSet<Long>(size)
        for (user in this) {
            if (user.id == 0L || !seen.add(user.id)) continue
            members.add(
                ClanMember(
                    userId = user.id,
                    username = user.username,
                    displayName = user.displayName,
                    avatarUrl = user.avatarUrl,
                    isOnline = false,
                    clanNick = user.clanNick,
                    clanAvatar = user.clanAvatar,
                    clanId = clanId,
                    roleIds = emptyList()
                )
            )
        }
        return members
    }
}
