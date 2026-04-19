package com.mezon.mobile.home.profile

import com.mezon.mobile.network.MezonApi

class FriendRepository(private val api: MezonApi, private val apiUrl: String, private val token: String) {
    suspend fun listFriends(): List<FriendEntity> {
        val response = api.listFriends(apiUrl, token)
        return response.friendsList.map {
            val user = it.user
            FriendEntity(
                id = user?.id?.toString() ?: "",
                username = user?.username ?: "",
                displayName = user?.displayName ?: "",
                avatar = user?.avatarUrl,
                state = when (it.state) {
                    0 -> FriendState.FRIEND
                    1 -> FriendState.OTHER_PENDING
                    2 -> FriendState.MY_PENDING
                    3 -> FriendState.BLOCK
                    else -> FriendState.OTHER_PENDING
                }
            )
        }
    }

    suspend fun deleteFriend(id: String, username: String) {
        api.unblockFriends(apiUrl, token, listOf(id.toLong()), listOf(username))
    }

    suspend fun acceptFriend(id: String, username: String) {
        // Gọi addFriends với id để accept
        api.addFriends(apiUrl, token, listOf(id.toLong()), listOf(username))
    }
}

