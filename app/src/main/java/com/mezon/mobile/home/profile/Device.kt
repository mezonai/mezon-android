package com.mezon.mobile.home.profile

data class Device(
    val deviceId: String,
    val deviceName: String? = null,
    val ip: String? = null,
    val lastActiveSeconds: Long = 0,
    val loginAtSeconds: Long = 0,
    val platform: String? = null,
    val status: Int = 0,
    val isCurrentDevice: Boolean = false,
    val location: String? = null
) {
    companion object {
        const val PLATFORM_MOBILE = "mobile"
        const val PLATFORM_DESKTOP = "desktop"
        const val PLATFORM_IOS = "ios"
        const val PLATFORM_ANDROID = "android"
        const val PLATFORM_WEB = "web"
    }
}