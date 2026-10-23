package com.mezon.mobile.home

import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.home.clans.ClansController
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnonymousController @Inject constructor(
    private val clansController: Lazy<ClansController>,
    private val notificationCenter: NotificationCenter,
    @ApplicationScope private val appScope: CoroutineScope
) {

    enum class ToggleResult { CHANGED, NOT_ALLOWED, CLAN_UNAVAILABLE }

    private val anonymousClanIds = HashSet<Long>()

    init {
        appScope.launch {
            clansController.get().clans
                .distinctUntilChanged { previous, current ->
                    previous.size == current.size && previous.indices.all { index ->
                        previous[index].clanId == current[index].clanId &&
                            previous[index].preventAnonymous == current[index].preventAnonymous
                    }
                }
                .collect { clans ->
                    if (synchronized(this@AnonymousController) { anonymousClanIds.isEmpty() }) return@collect
                    val clanPolicies = clans.associate { it.clanId to it.preventAnonymous }
                    val disabledClanIds = synchronized(this@AnonymousController) {
                        anonymousClanIds.filter { clanPolicies[it] != false }.also { ids ->
                            ids.forEach { anonymousClanIds.remove(it) }
                        }
                    }
                    disabledClanIds.forEach { clanId ->
                        val disabledByClanPolicy = clanPolicies[clanId] == true
                        notificationCenter.postNotificationOnMainThread(
                            NotificationCenter.anonymousModeChanged, clanId, disabledByClanPolicy
                        )
                    }
                }
        }
    }

    fun toggleAnonymous(clanId: Long): ToggleResult {
        if (clanId == 0L) return ToggleResult.CLAN_UNAVAILABLE
        synchronized(this) {
            val current = clanId in anonymousClanIds
            if (current) {
                anonymousClanIds.remove(clanId)
            } else {
                val clan = clansController.get().clans.value.firstOrNull { it.clanId == clanId }
                    ?: return ToggleResult.CLAN_UNAVAILABLE
                if (clan.preventAnonymous) return ToggleResult.NOT_ALLOWED
                anonymousClanIds.add(clanId)
            }
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.anonymousModeChanged, clanId)
        return ToggleResult.CHANGED
    }

    fun isAnonymous(clanId: Long): Boolean {
        return synchronized(this) { clanId in anonymousClanIds && isAllowed(clanId) }
    }

    fun isAllowed(clanId: Long): Boolean {
        return clanId != 0L && clansController.get().clans.value.any {
            it.clanId == clanId && !it.preventAnonymous
        }
    }

    fun clearAll() {
        synchronized(this) {
            anonymousClanIds.clear()
        }
    }
}
