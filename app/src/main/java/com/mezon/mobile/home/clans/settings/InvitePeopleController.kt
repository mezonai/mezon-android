package com.mezon.mobile.home.clans.settings

import android.content.Context
import com.mezon.mobile.R
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.UserClanController
import com.mezon.mezon.api.Friend
import com.mezon.mobile.home.friends.FRIEND_STATE_BLOCKED
import com.mezon.mobile.home.friends.FRIEND_STATE_FRIEND
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.messages.toDirectMessage
import com.mezon.mobile.home.profile.UserController
import com.mezon.mezon.rtapi.channelMessageSend
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.channelTypeToStreamMode
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InvitePeopleController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val api: MezonApi,
    private val sessionManager: SessionManager,
    private val dialogsController: DialogsController,
    private val friendController: FriendController,
    private val userClanController: UserClanController,
    private val userController: UserController,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(InvitePeopleUiState())
    val state: StateFlow<InvitePeopleUiState> = _state.asStateFlow()

    private var allTargets: List<InviteDmTarget> = emptyList()
    private var searchJob: Job? = null

    fun open(clanId: Long, clanName: String, clanLogo: String) {
        scope.launch(io) {
            _state.value = InvitePeopleUiState(
                clanId = clanId,
                clanName = clanName,
                clanLogo = clanLogo,
                isLoadingLink = true,
                isLoadingTargets = true,
            )
            try {
                friendController.loadFriendRelations(noCache = false)
                userClanController.loadClanMembers(clanId)
                dialogsController.loadDialogs()

                val (welcomeChannelId, inviteUrl, inviteToken) = sessionManager.withAutoRefresh { session ->
                    val sys = api.getSystemMessageForClan(session.apiUrl, session.token, clanId)
                    val welcomeId = sys.channelId
                    if (welcomeId == 0L) {
                        throw NoWelcomeChannelException()
                    }
                    val link = api.createLinkInviteUser(
                        session.apiUrl,
                        session.token,
                        clanId,
                        welcomeId,
                        expiryTime = 10,
                    )
                    Triple(welcomeId, link.toShareableInviteUrl(), link.inviteLink)
                }

                allTargets = fetchInviteTargets(clanId)
                _state.update {
                    it.copy(
                        welcomeChannelId = welcomeChannelId,
                        inviteUrl = inviteUrl,
                        inviteToken = inviteToken,
                        dmTargets = filterTargets(allTargets, it.searchQuery),
                        isLoadingLink = false,
                        isLoadingTargets = false,
                        linkError = null,
                    )
                }
            } catch (e: NoWelcomeChannelException) {
                _state.update {
                    it.copy(
                        isLoadingLink = false,
                        isLoadingTargets = false,
                        linkError = ERROR_NO_WELCOME_CHANNEL,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoadingLink = false,
                        isLoadingTargets = false,
                        linkError = e.message?.takeIf { msg -> msg.isNotBlank() } ?: ERROR_CREATE_LINK,
                    )
                }
            }
        }
    }

    fun reset() {
        searchJob?.cancel()
        searchJob = null
        allTargets = emptyList()
        _state.value = InvitePeopleUiState()
    }

    fun onSearch(query: String) {
        searchJob?.cancel()
        searchJob = scope.launch(io) {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update {
                it.copy(
                    searchQuery = query,
                    dmTargets = filterTargets(allTargets, query),
                )
            }
        }
    }

    fun applySearchImmediately(query: String) {
        searchJob?.cancel()
        searchJob = null
        _state.update {
            it.copy(
                searchQuery = query,
                dmTargets = filterTargets(allTargets, query),
            )
        }
    }

    fun sendInviteToTarget(
        target: InviteDmTarget,
        onResult: (success: Boolean, errorMessage: String?) -> Unit,
    ) {
        val url = _state.value.inviteUrl
        if (url.isBlank()) {
            onResult(false, ERROR_CREATE_LINK)
            return
        }
        if (_state.value.sentTargetIds.contains(target.rowId)) return

        scope.launch(io) {
            _state.update { it.copy(sendingTargetId = target.rowId) }
            try {
                val channelId = target.channelId ?: run {
                    val userId = target.userId ?: throw IllegalStateException("missing user")
                    val created = dialogsController.getOrCreateDm(userId)
                    if (created == 0L) throw IllegalStateException("dm_create_failed")
                    created
                }

                val inviteId = parseInviteIdFromUrl(url)
                val preview = inviteId?.let { api.getLinkInvitePreview(it) }
                val memberDescription = preview?.memberCount?.takeIf { it > 0 }?.let { count ->
                    appContext.getString(R.string.clan_action_member_count, count)
                }
                val content = buildInviteLinkContent(url, preview, memberDescription)
                sendInviteMessageDirect(channelId, target.channelType, content)

                _state.update {
                    it.copy(
                        sentTargetIds = it.sentTargetIds + target.rowId,
                        sendingTargetId = null,
                    )
                }
                onResult(true, null)
            } catch (e: Exception) {
                _state.update { it.copy(sendingTargetId = null) }
                onResult(false, e.message ?: ERROR_SEND)
            }
        }
    }

    private suspend fun sendInviteMessageDirect(
        channelId: Long,
        channelType: Int,
        content: String,
    ) {
        sessionManager.withAutoRefresh { session ->
            val request = channelMessageSend {
                clanId = 0L
                this.channelId = channelId
                mode = channelTypeToStreamMode(channelType)
                isPublic = false
                this.content = content
            }
            api.sendChannelMessage(session.apiUrl, session.token, request)
        }
    }

    private suspend fun fetchInviteTargets(clanId: Long): List<InviteDmTarget> {
        return sessionManager.withAutoRefresh { session ->
            val currentUserId = session.userId.toLongOrNull() ?: userController.userId

            val clanMemberIds = runCatching {
                api.listClanUsers(session.apiUrl, session.token, clanId)
                    .clanUsersList
                    .mapNotNull { it.user?.id }
                    .toSet()
            }.getOrElse {
                userClanController.getClanMembers(clanId).map { it.userId }.toSet()
            }

            val allFriends: List<Friend> = runCatching {
                api.listFriendsAll(session.apiUrl, session.token).friendsList
            }.getOrElse { friendController.friends.value }

            val blockedIds = allFriends
                .filter { it.state == FRIEND_STATE_BLOCKED }
                .map { it.user.id }
                .toSet()

            val result = LinkedHashMap<String, InviteDmTarget>()

            val cachedDialogs = synchronized(dialogsController) {
                if (dialogsController.dialogs.isNotEmpty()) {
                    ArrayList(dialogsController.dialogs)
                } else {
                    null
                }
            }

            if (cachedDialogs != null) {
                for (dm in cachedDialogs) {
                    addDmTarget(result, dm, clanMemberIds, blockedIds)
                }
            } else {
                val dmDescs = runCatching {
                    api.listChannelDescs(session.apiUrl, session.token, CHANNEL_TYPE_DM).channeldescList +
                        api.listChannelDescs(session.apiUrl, session.token, CHANNEL_TYPE_GROUP).channeldescList
                }.getOrElse { emptyList() }
                for (desc in dmDescs.filter { it.active == 1 }) {
                    val dm = desc.toDirectMessage(currentUserId, null)
                    addDmTarget(result, dm, clanMemberIds, blockedIds)
                }
            }

            for (friend in allFriends) {
                if (friend.state != FRIEND_STATE_FRIEND) continue
                val userId = friend.user.id
                if (userId == currentUserId || userId in clanMemberIds || userId in blockedIds) continue
                val rowId = "user_$userId"
                if (result.values.any { it.userId == userId }) continue
                val display = friend.user.displayName.ifBlank { friend.user.username }
                result[rowId] = InviteDmTarget(
                    rowId = rowId,
                    channelId = null,
                    channelType = CHANNEL_TYPE_DM,
                    userId = userId,
                    title = display,
                    subtitle = friend.user.username.takeIf { it.isNotBlank() },
                    avatarUrl = friend.user.avatarUrl.takeIf { it.isNotBlank() },
                )
            }

            result.values.sortedBy { it.title.lowercase() }
        }
    }

    private fun addDmTarget(
        result: LinkedHashMap<String, InviteDmTarget>,
        dm: com.mezon.mobile.home.messages.DirectMessage,
        clanMemberIds: Set<Long>,
        blockedIds: Set<Long>,
    ) {
        when (dm.type) {
            CHANNEL_TYPE_GROUP -> {
                val rowId = "ch_${dm.channelId}"
                result[rowId] = InviteDmTarget(
                    rowId = rowId,
                    channelId = dm.channelId,
                    channelType = CHANNEL_TYPE_GROUP,
                    userId = null,
                    title = dm.label.ifBlank { dm.displayName },
                    subtitle = dm.username.takeIf { it.isNotBlank() },
                    avatarUrl = dm.avatarUrl.takeIf { it.isNotBlank() },
                )
            }
            CHANNEL_TYPE_DM -> {
                val otherId = dm.otherUserId
                if (otherId != 0L && otherId in clanMemberIds) return
                if (otherId != 0L && otherId in blockedIds) return
                val rowId = "ch_${dm.channelId}"
                result[rowId] = InviteDmTarget(
                    rowId = rowId,
                    channelId = dm.channelId,
                    channelType = CHANNEL_TYPE_DM,
                    userId = otherId.takeIf { it != 0L },
                    title = dm.displayName.ifBlank { dm.label }.ifBlank { dm.username },
                    subtitle = dm.username.takeIf { it.isNotBlank() },
                    avatarUrl = dm.avatarUrl.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    private fun filterTargets(targets: List<InviteDmTarget>, query: String): List<InviteDmTarget> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return targets
        return targets.filter { target ->
            target.title.lowercase().contains(q) ||
                target.subtitle?.lowercase()?.contains(q) == true
        }
    }

    private class NoWelcomeChannelException : Exception()

    companion object {
        const val ERROR_NO_WELCOME_CHANNEL = "no_welcome_channel"
        const val ERROR_CREATE_LINK = "create_link_failed"
        const val ERROR_SEND = "send_failed"
        private const val SEARCH_DEBOUNCE_MS = 150L
    }
}
