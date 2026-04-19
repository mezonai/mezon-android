package com.mezon.mobile.home.profile

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import androidx.lifecycle.viewModelScope

class FriendRequestsViewModel(private val repo: FriendRepository) : ViewModel() {
    private val _friends = MutableStateFlow<List<FriendEntity>>(emptyList())
    val friends: StateFlow<List<FriendEntity>> = _friends

    private val _selectedTab = MutableStateFlow(0) // 0: Received, 1: Sent
    val selectedTab: StateFlow<Int> = _selectedTab

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab
    }

    val receivedList: List<FriendEntity>
        get() = _friends.value.filter { it.state == FriendState.MY_PENDING }
    val sentList: List<FriendEntity>
        get() = _friends.value.filter { it.state == FriendState.OTHER_PENDING }

    fun load() {
        viewModelScope.launch {
            _friends.value = repo.listFriends()
        }
    }

    fun delete(friend: FriendEntity) {
        viewModelScope.launch {
            repo.deleteFriend(friend.id, friend.username)
            load()
        }
    }

    fun approve(friend: FriendEntity) {
        viewModelScope.launch {
            repo.acceptFriend(friend.id, friend.username)
            load()
        }
    }
}

