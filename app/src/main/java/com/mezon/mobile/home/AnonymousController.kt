package com.mezon.mobile.home

import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.network.SocketEventDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnonymousController @Inject constructor(
    private val socketEventDispatcher: SocketEventDispatcher,
    private val notificationCenter: NotificationCenter,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val anonymousStates = HashMap<Long, Boolean>()
    private val allowedClans = HashSet<Long>()

    init {
        appScope.launch { observeAllowAnonymousEvents() }
    }

    fun toggleAnonymous(clanId: Long) {
        synchronized(this) {
            val current = anonymousStates[clanId] == true
            if (current) anonymousStates.remove(clanId)
            else anonymousStates[clanId] = true
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.anonymousModeChanged, clanId)
    }

    fun isAnonymous(clanId: Long): Boolean {
        return synchronized(this) { anonymousStates[clanId] == true }
    }

    fun isAllowed(clanId: Long): Boolean {
        return synchronized(this) { allowedClans.contains(clanId) }
    }

    fun clearAll() {
        synchronized(this) {
            anonymousStates.clear()
            allowedClans.clear()
        }
    }

    private suspend fun observeAllowAnonymousEvents() {
        socketEventDispatcher.allowAnonymousEvents.collect { event ->
            val clanId = event.clanId
            if (clanId == 0L) return@collect
            val allowed = event.allow
            synchronized(this) {
                if (allowed) allowedClans.add(clanId)
                else {
                    allowedClans.remove(clanId)
                    anonymousStates.remove(clanId)
                }
            }
            notificationCenter.postNotificationOnMainThread(NotificationCenter.anonymousModeChanged, clanId)
        }
    }
}
