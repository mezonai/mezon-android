package com.mezon.mobile.home.profile

// Enum trạng thái bạn bè
enum class FriendState(val value: Int) {
    FRIEND(0),
    OTHER_PENDING(1), // Đã gửi đi
    MY_PENDING(2),    // Nhận về
    BLOCK(3)
}

// Model FriendEntity
data class FriendEntity(
    val id: String,
    val username: String,
    val displayName: String,
    val avatar: String?,
    val state: FriendState
)

