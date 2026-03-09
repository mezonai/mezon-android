package com.mezon.mobile.home

data class CachedUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val avatar: String,
    val isOnline: Boolean = false
)
