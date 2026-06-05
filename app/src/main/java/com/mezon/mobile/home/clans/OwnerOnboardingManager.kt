package com.mezon.mobile.home.clans

import android.content.Context
import com.mezon.mobile.core.StartupCache

object OwnerOnboardingManager {
    private const val PREFS_NAME = "owner_onboarding_prefs"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCreatedChannel(context: Context, clanId: Long, channels: List<ClanChannelEntity>): Boolean {
        val userId = StartupCache.userId
        if (userId.isEmpty()) return false
        val key = "created_channel_${userId}_${clanId}"
        if (getPrefs(context).getBoolean(key, false)) return true
        
        val textChannelCount = channels.count { it.type == 1 }
        if (textChannelCount > 1) {
            setCreatedChannel(context, clanId, true)
            return true
        }
        return false
    }

    fun setCreatedChannel(context: Context, clanId: Long, value: Boolean) {
        val userId = StartupCache.userId
        if (userId.isNotEmpty()) {
            getPrefs(context).edit().putBoolean("created_channel_${userId}_${clanId}", value).apply()
        }
    }

    fun isInvitedFriends(context: Context, clanId: Long, hasOtherMember: Boolean = false): Boolean {
        return hasOtherMember
    }

    fun setInvitedFriends(context: Context, clanId: Long, value: Boolean) {
        val userId = StartupCache.userId
        if (userId.isNotEmpty()) {
            getPrefs(context).edit().putBoolean("invited_friends_${userId}_${clanId}", value).apply()
        }
    }

    fun isSentMessage(context: Context, clanId: Long): Boolean {
        val userId = StartupCache.userId
        if (userId.isEmpty()) return false
        return getPrefs(context).getBoolean("sent_message_${userId}_${clanId}", false)
    }

    fun setSentMessage(context: Context, clanId: Long, value: Boolean) {
        val userId = StartupCache.userId
        if (userId.isNotEmpty()) {
            getPrefs(context).edit().putBoolean("sent_message_${userId}_${clanId}", value).apply()
        }
    }

    fun getCompletedCount(context: Context, clanId: Long, channels: List<ClanChannelEntity>, hasOtherMember: Boolean = false): Int {
        var count = 0
        if (isCreatedChannel(context, clanId, channels)) count++
        if (isInvitedFriends(context, clanId, hasOtherMember)) count++
        if (isSentMessage(context, clanId)) count++
        return count
    }

    fun isCompletedAll(context: Context, clanId: Long, channels: List<ClanChannelEntity>, hasOtherMember: Boolean = false): Boolean {
        return getCompletedCount(context, clanId, channels, hasOtherMember) == 3
    }

    fun isOnboardingActive(context: Context, clanId: Long): Boolean {
        val userId = StartupCache.userId
        if (userId.isEmpty()) return false
        return getPrefs(context).getBoolean("onboarding_active_${userId}_${clanId}", false)
    }

    fun setOnboardingActive(context: Context, clanId: Long, value: Boolean) {
        val userId = StartupCache.userId
        if (userId.isNotEmpty()) {
            getPrefs(context).edit().putBoolean("onboarding_active_${userId}_${clanId}", value).apply()
        }
    }

    // --- User Onboarding ---
    fun isUserVisitedWelcome(context: Context, clanId: Long): Boolean {
        val userId = StartupCache.userId
        if (userId.isEmpty()) return false
        return getPrefs(context).getBoolean("user_visited_welcome_${userId}_${clanId}", false)
    }

    fun setUserVisitedWelcome(context: Context, clanId: Long, value: Boolean) {
        val userId = StartupCache.userId
        if (userId.isNotEmpty()) {
            getPrefs(context).edit().putBoolean("user_visited_welcome_${userId}_${clanId}", value).apply()
        }
    }

    fun isUserSentWelcome(context: Context, clanId: Long): Boolean {
        val userId = StartupCache.userId
        if (userId.isEmpty()) return false
        return getPrefs(context).getBoolean("user_sent_welcome_${userId}_${clanId}", false)
    }

    fun setUserSentWelcome(context: Context, clanId: Long, value: Boolean) {
        val userId = StartupCache.userId
        if (userId.isNotEmpty()) {
            getPrefs(context).edit().putBoolean("user_sent_welcome_${userId}_${clanId}", value).apply()
        }
    }

    fun isUserInstalledApps(context: Context, clanId: Long): Boolean {
        val userId = StartupCache.userId
        if (userId.isEmpty()) return false
        return getPrefs(context).getBoolean("user_installed_apps_${userId}_${clanId}", false)
    }

    fun setUserInstalledApps(context: Context, clanId: Long, value: Boolean) {
        val userId = StartupCache.userId
        if (userId.isNotEmpty()) {
            getPrefs(context).edit().putBoolean("user_installed_apps_${userId}_${clanId}", value).apply()
        }
    }

    fun getUserCompletedCount(context: Context, clanId: Long): Int {
        var count = 0
        if (isUserVisitedWelcome(context, clanId)) count++
        if (isUserSentWelcome(context, clanId)) count++
        if (isUserInstalledApps(context, clanId)) count++
        return count
    }

    fun isUserCompletedAll(context: Context, clanId: Long): Boolean {
        return getUserCompletedCount(context, clanId) == 3
    }

    fun resolveWelcomeChannel(clan: ClanEntity, channels: List<ClanChannelEntity>): ClanChannelEntity? {
        if (clan.welcomeChannelId != 0L) {
            val matched = channels.firstOrNull { it.channelId == clan.welcomeChannelId }
            if (matched != null) return matched
        }
        val welcomeLabelMatch = channels.firstOrNull { channel ->
            val label = channel.channelLabel.lowercase()
            label.contains("welcome") || label.contains("chào-mừng") || label.contains("chao-mung")
        }
        if (welcomeLabelMatch != null) return welcomeLabelMatch

        return channels.firstOrNull { it.type == 1 }
    }
}
