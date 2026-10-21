package com.mezon.mobile.home

import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.home.clans.ClansController
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnonymousController @Inject constructor(
    private val clansController: Lazy<ClansController>,
    private val notificationCenter: NotificationCenter,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val anonymousStates = HashMap<Long, Boolean>()

    init {
        appScope.launch {
            clansController.get().clans.collect { clans ->
                val allowedClanIds = clans.filterNot { it.preventAnonymous }.map { it.clanId }.toSet()
                val disabledClanIds = synchronized(this@AnonymousController) {
                    anonymousStates.keys.filter { it !in allowedClanIds }.also { ids ->
                        ids.forEach { anonymousStates.remove(it) }
                    }
                }
                disabledClanIds.forEach { clanId ->
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.anonymousModeChanged, clanId)
                }
            }
        }
    }

    fun toggleAnonymous(clanId: Long): Boolean {
        synchronized(this) {
            val current = anonymousStates[clanId] == true
            if (!current && !isAllowed(clanId)) return false
            if (current) anonymousStates.remove(clanId)
            else anonymousStates[clanId] = true
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.anonymousModeChanged, clanId)
        return true
    }

    fun isAnonymous(clanId: Long): Boolean {
        return synchronized(this) { anonymousStates[clanId] == true && isAllowed(clanId) }
    }

    fun isAllowed(clanId: Long): Boolean {
        return clanId != 0L && clansController.get().clans.value.any {
            it.clanId == clanId && !it.preventAnonymous
        }
    }

    fun clearAll() {
        synchronized(this) {
            anonymousStates.clear()
        }
    }
}
