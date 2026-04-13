package com.mezon.mobile.home.profile

import com.mezon.mobile.ui.cells.MezonIcon

enum class UserOnlineStatus(val value: String) {
    ONLINE("Online"),
    IDLE("Idle"),
    DO_NOT_DISTURB("Do Not Disturb"),
    INVISIBLE("Invisible");

    override fun toString(): String = value

    fun isEnlarged(): Boolean = this == IDLE || this == DO_NOT_DISTURB

    fun getIcon(): MezonIcon = when (this) {
        ONLINE -> MezonIcon.onlineStatusIcon
        IDLE -> MezonIcon.idleStatusIcon
        DO_NOT_DISTURB -> MezonIcon.disturbStatusIcon
        INVISIBLE -> MezonIcon.offlineStatusIcon
    }

    companion object {
        fun fromString(status: String): UserOnlineStatus {
            return entries.firstOrNull {
                it.value.equals(status, ignoreCase = true)
            } ?: ONLINE
        }
    }
}
