package com.mezon.mobile.search

import android.util.Log
import com.mezon.mezon.api.SearchMessageDocument
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.ClanUser
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.CHANNEL_TYPE_STREAMING
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.toClanChannelEntity
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.apiCacheKey
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SearchController"
private const val SIZE_PAGE_SEARCH = 20
const val LOCAL_PAGE_SIZE = 50

data class SearchMember(
    val id: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val isOnline: Boolean,
    val isDm: Boolean,
    val channelId: Long,
    val channelType: Int
)

fun SearchMember.avatarPlaceholderKey(): String = username

fun SearchMember.avatarEntityId(): Long =
    if (isDm && channelType == CHANNEL_TYPE_GROUP) channelId else id

data class ChannelSearchDisplay(
    val channel: ClanChannelEntity,
    val clanName: String,
    val parentChannelLabel: String,
    val hideClanName: Boolean = false
) {
    val showJoinUi: Boolean
        get() = channel.type == CHANNEL_TYPE_VOICE || channel.type == CHANNEL_TYPE_STREAMING
}

@Singleton
class SearchController @Inject constructor(
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val dialogsController: DialogsController,
    private val clansController: ClansController,
    private val userClanController: UserClanController,
    private val notificationCenter: NotificationCenter,
    private val cacheTracker: ApiCacheTracker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val allMembers = ArrayList<SearchMember>()
    private val allChannels = ArrayList<ClanChannelEntity>()
    private val channelById = HashMap<Long, ClanChannelEntity>()
    private val searchMessages = ArrayList<SearchMessageDocument>()
    var searchMessagesTotal = 0
        private set

    private var membersLoaded = false
    private var channelsLoaded = false

    @Synchronized
    fun getMembers(): List<SearchMember> = ArrayList(allMembers)

    @Synchronized
    fun getChannels(): List<ClanChannelEntity> = ArrayList(allChannels)

    @Synchronized
    fun hasChannels(): Boolean = allChannels.isNotEmpty()

    @Synchronized
    fun findChannelById(channelId: Long): ClanChannelEntity? = channelById[channelId]

    @Synchronized
    fun getMessages(): List<SearchMessageDocument> = ArrayList(searchMessages)

    fun loadMembers(noCache: Boolean = false) {
        userClanController.loadUsers(noCache)
        rebuildMembers()
    }

    fun rebuildMembers() {
        appScope.launch(ioDispatcher) {
            try {
                val currentUserId = sessionManager.sessionFlow.first()?.userId?.toLongOrNull() ?: 0L

                val dmList = dialogsController.getDialogs()
                val dmUserIds = HashSet<Long>()
                val dmMembers = ArrayList<SearchMember>()

                for (dm in dmList) {
                    if (dm.type == CHANNEL_TYPE_DM && dm.otherUserId != 0L) {
                        dmUserIds.add(dm.otherUserId)
                        dmMembers.add(dm.toSearchMember())
                    } else if (dm.type != CHANNEL_TYPE_DM) {
                        dmMembers.add(dm.toSearchMember())
                    }
                }

                val clanUsers = userClanController.getUsers()
                val seenUserIds = HashSet<Long>(dmUserIds)
                seenUserIds.add(currentUserId)

                val nonDmMembers = ArrayList<SearchMember>()
                for (user in clanUsers) {
                    if (user.id in seenUserIds) continue
                    seenUserIds.add(user.id)
                    nonDmMembers.add(user.toSearchMember())
                }

                synchronized(this@SearchController) {
                    allMembers.clear()
                    allMembers.addAll(dmMembers)
                    allMembers.addAll(nonDmMembers)
                    membersLoaded = true
                    cachedMembersQuery = null
                    cachedMembersResult.clear()
                }

                notificationCenter.postNotificationOnMainThread(NotificationCenter.searchMembersDidLoad)
            } catch (e: Exception) {
                Log.e(TAG, "rebuildMembers failed", e)
            }
        }
    }

    fun loadChannels(noCache: Boolean = false) {
        appScope.launch(ioDispatcher) {
            try {
                val cacheKey = apiCacheKey("searchChannels")
                val hasCache: Boolean
                synchronized(this@SearchController) { hasCache = allChannels.isNotEmpty() }

                if (hasCache && cacheTracker.shouldCall(cacheKey, noCache = noCache) == ApiCacheTracker.ShouldCall.SKIP) {
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.searchChannelsDidLoad)
                    return@launch
                }

                sessionManager.withAutoRefresh { session ->
                    val response = api.listChannelByUserId(session.apiUrl, session.token)

                    val channels = response.channeldescList.map { it.toClanChannelEntity() }
                    synchronized(this@SearchController) {
                        allChannels.clear()
                        allChannels.addAll(channels)
                        channelById.clear()
                        for (c in channels) channelById[c.channelId] = c
                        channelsLoaded = true
                        cachedChannelsQuery = null
                        cachedChannelsResult.clear()
                    }

                    cacheTracker.markCalled(cacheKey)
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.searchChannelsDidLoad)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadChannels failed", e)
            }
        }
    }

    var hasMoreMessages = true
        private set
    private var messagesPage = 1

    fun searchMessagesApi(
        channelId: Long = 0L,
        content: String = "",
        from: Int = 1,
        size: Int = SIZE_PAGE_SEARCH
    ) {
        appScope.launch(ioDispatcher) {
            try {
                sessionManager.withAutoRefresh { session ->
                    val clanId = clansController.selectedClanId.value

                    val filters = ArrayList<Pair<String, String>>()
                    filters.add("channel_id" to channelId.toString())
                    filters.add("clan_id" to clanId.toString())
                    if (content.isNotBlank()) filters.add("content" to content.trim())

                    val response = api.searchMessages(
                        session.apiUrl, session.token, filters, from, size
                    )

                    synchronized(this@SearchController) {
                        if (from <= 1) searchMessages.clear()
                        searchMessages.addAll(response.messagesList)
                        searchMessagesTotal = response.total
                        messagesPage = from
                        hasMoreMessages = response.messagesList.isNotEmpty()
                    }

                    notificationCenter.postNotificationOnMainThread(NotificationCenter.searchMessagesDidLoad)
                }
            } catch (e: Exception) {
                Log.e(TAG, "searchMessages failed", e)
            }
        }
    }

    fun loadMoreMessages(channelId: Long = 0L, content: String = "") {
        val nextPage: Int
        synchronized(this) {
            if (!hasMoreMessages) return
            nextPage = messagesPage + 1
        }
        searchMessagesApi(channelId = channelId, content = content, from = nextPage)
    }

    private var cachedMembersQuery: String? = null
    private var cachedMembersResult = ArrayList<SearchMember>()
    private var cachedChannelsQuery: String? = null
    private var cachedChannelsResult = ArrayList<ClanChannelEntity>()

    fun filterMembers(query: String, limit: Int = LOCAL_PAGE_SIZE): List<SearchMember> {
        val result = getFilteredMembersCached(query)
        return result.take(limit)
    }

    fun filterChannelDisplays(
        query: String,
        limit: Int = LOCAL_PAGE_SIZE,
        hideClanName: Boolean = false
    ): List<ChannelSearchDisplay> {
        val filtered = getFilteredChannelsCached(query)
        val enriched = enrichChannelDisplays(filtered, hideClanName)
        return orderTextVoiceStreaming(enriched).take(limit)
    }

    fun channelDisplaysForPicker(entities: List<ClanChannelEntity>): List<ChannelSearchDisplay> =
        enrichChannelDisplays(entities, hideClanName = true)

    fun filterMembersCount(query: String): Int = getFilteredMembersCached(query).size

    fun filterChannelsCount(query: String): Int = getFilteredChannelsCached(query).size

    fun totalMembersForQuery(query: String): Int = getFilteredMembersCached(query).size

    fun totalChannelsForQuery(query: String): Int = getFilteredChannelsCached(query).size

    @Synchronized
    private fun getFilteredMembersCached(query: String): List<SearchMember> {
        if (query == cachedMembersQuery && cachedMembersResult.isNotEmpty()) {
            return cachedMembersResult
        }
        val members = ArrayList(allMembers)
        val result = if (query.isBlank()) {
            members
        } else {
            val search = query.trim().lowercase()
            val searchNorm = removeDiacritics(search)
            val scored = ArrayList<Triple<SearchMember, Int, Int>>(members.size / 4)
            for (member in members) {
                val username = member.username.lowercase()
                val displayName = member.displayName.lowercase()
                val usernameNorm = removeDiacritics(username)
                val displayNorm = removeDiacritics(displayName)

                val displayScore = when {
                    displayName == search -> 1050
                    displayName.startsWith(search) -> 950
                    displayNorm == searchNorm -> 850
                    displayNorm.startsWith(searchNorm) -> 750
                    displayName.contains(search) -> 550
                    displayNorm.contains(searchNorm) -> 450
                    else -> 0
                }
                val usernameScore = when {
                    username == search -> 1000
                    username.startsWith(search) -> 900
                    usernameNorm == searchNorm -> 800
                    usernameNorm.startsWith(searchNorm) -> 700
                    username.contains(search) -> 500
                    usernameNorm.contains(searchNorm) -> 400
                    else -> 0
                }
                val score = maxOf(displayScore, usernameScore)
                if (score > 0) {
                    val len = displayName.length.takeIf { it > 0 } ?: username.length
                    scored.add(Triple(member, score, len))
                }
            }
            scored.sortWith(compareByDescending<Triple<SearchMember, Int, Int>> { it.second }
                .thenBy { it.third })
            ArrayList<SearchMember>(scored.size).also { list ->
                for (t in scored) list.add(t.first)
            }
        }
        cachedMembersQuery = query
        cachedMembersResult = result
        return result
    }

    @Synchronized
    private fun getFilteredChannelsCached(query: String): List<ClanChannelEntity> {
        if (query == cachedChannelsQuery && cachedChannelsResult.isNotEmpty()) {
            return cachedChannelsResult
        }
        val channels = ArrayList(allChannels)
        val result = if (query.isBlank()) {
            channels
        } else {
            val searchNorm = normalizeSearchString(query)
            val pairs = ArrayList<Pair<ClanChannelEntity, String>>(channels.size)
            for (ch in channels) {
                val norm = normalizeSearchString(ch.channelLabel)
                if (norm.contains(searchNorm)) pairs.add(ch to norm)
            }
            pairs.sortWith(Comparator { a, b -> compareNormalized(a.second, b.second, searchNorm) })
            ArrayList<ClanChannelEntity>(pairs.size).also { out ->
                for (p in pairs) out.add(p.first)
            }
        }
        cachedChannelsQuery = query
        cachedChannelsResult = result
        return result
    }

    private fun compareNormalized(aNorm: String, bNorm: String, search: String): Int {
        val aExact = aNorm == search
        val bExact = bNorm == search
        if (aExact && !bExact) return -1
        if (!aExact && bExact) return 1
        val aIndex = aNorm.indexOf(search)
        val bIndex = bNorm.indexOf(search)
        if (aIndex == -1 && bIndex == -1) return 0
        if (aIndex == -1) return 1
        if (bIndex == -1) return -1
        if (aIndex != bIndex) return aIndex - bIndex
        return aNorm.compareTo(bNorm)
    }

    fun invalidateFilterCache() {
        synchronized(this) {
            cachedMembersQuery = null
            cachedMembersResult.clear()
            cachedChannelsQuery = null
            cachedChannelsResult.clear()
        }
    }

    fun clearSearchMessages() {
        synchronized(this) {
            searchMessages.clear()
            searchMessagesTotal = 0
            messagesPage = 1
            hasMoreMessages = true
        }
    }

    private fun enrichChannelDisplays(
        entities: List<ClanChannelEntity>,
        hideClanName: Boolean
    ): List<ChannelSearchDisplay> {
        val labelById = HashMap<Long, String>()
        synchronized(this) {
            for (c in allChannels) {
                labelById[c.channelId] = c.channelLabel
            }
        }
        val clanNames = clansController.clans.value.associate { it.clanId to it.clanName }
        return entities.map { ch ->
            val parent = if (ch.parentId != 0L) {
                labelById[ch.parentId].orEmpty()
            } else {
                ""
            }
            ChannelSearchDisplay(
                channel = ch,
                clanName = clanNames[ch.clanId].orEmpty(),
                parentChannelLabel = parent,
                hideClanName = hideClanName
            )
        }
    }

    private fun orderTextVoiceStreaming(items: List<ChannelSearchDisplay>): List<ChannelSearchDisplay> {
        val text = ArrayList<ChannelSearchDisplay>()
        val voice = ArrayList<ChannelSearchDisplay>()
        val stream = ArrayList<ChannelSearchDisplay>()
        for (d in items) {
            when (d.channel.type) {
                CHANNEL_TYPE_VOICE -> voice.add(d)
                CHANNEL_TYPE_STREAMING -> stream.add(d)
                else -> text.add(d)
            }
        }
        val out = ArrayList<ChannelSearchDisplay>(items.size)
        out.addAll(text)
        out.addAll(voice)
        out.addAll(stream)
        return out
    }

    private fun compareByLabel(a: ClanChannelEntity, b: ClanChannelEntity, search: String): Int {
        val aNorm = normalizeSearchString(a.channelLabel)
        val bNorm = normalizeSearchString(b.channelLabel)

        val aExact = aNorm == search
        val bExact = bNorm == search
        if (aExact && !bExact) return -1
        if (!aExact && bExact) return 1

        val aIndex = aNorm.indexOf(search)
        val bIndex = bNorm.indexOf(search)

        if (aIndex == -1 && bIndex == -1) return 0
        if (aIndex == -1) return 1
        if (bIndex == -1) return -1
        if (aIndex != bIndex) return aIndex - bIndex
        return aNorm.compareTo(bNorm)
    }

    companion object {
        private val DIACRITICS_REGEX = Regex("[\\u0300-\\u036f]")

        fun removeDiacritics(input: String): String {
            return Normalizer.normalize(input, Normalizer.Form.NFD)
                .replace(DIACRITICS_REGEX, "")
        }

        fun normalizeSearchString(str: String): String {
            if (str.isEmpty()) return ""
            return removeDiacritics(str)
                .replace("-", " ")
                .replace("_", " ")
                .replace("+", " ")
                .uppercase()
        }
    }
}

private fun DirectMessage.toSearchMember(): SearchMember = SearchMember(
    id = if (type == CHANNEL_TYPE_DM) otherUserId else channelId,
    username = when (type) {
        CHANNEL_TYPE_DM -> username
        CHANNEL_TYPE_GROUP -> label.ifEmpty { displayName }
        else -> username.ifEmpty { displayName.ifEmpty { label } }
    },
    displayName = displayName.ifEmpty { label },
    avatarUrl = avatarUrl,
    isOnline = isOnline,
    isDm = true,
    channelId = channelId,
    channelType = type
)

private fun ClanUser.toSearchMember(): SearchMember = SearchMember(
    id = id,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    isOnline = isOnline,
    isDm = false,
    channelId = 0L,
    channelType = 0
)
