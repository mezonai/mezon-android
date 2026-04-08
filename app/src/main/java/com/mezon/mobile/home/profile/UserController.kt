package com.mezon.mobile.home.profile

import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.session.LocaleManager
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.session.ThemeManager
import com.mezon.mobile.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserController @Inject constructor(
    private val sessionManager: SessionManager,
    private val themeManager: ThemeManager,
    private val localeManager: LocaleManager,
    private val notificationCenter: NotificationCenter,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    @Volatile var userId: Long = 0L
        private set
    @Volatile var userIdStr: String = ""
        private set
    @Volatile var themeMode: ThemeMode = ThemeMode.SYSTEM
        private set
    @Volatile var languageTag: String = LocaleManager.ENGLISH
        private set
    @Volatile var username: String = ""
        private set
    @Volatile var displayName: String = ""
        private set
    @Volatile var email: String = ""
        private set
    @Volatile var phoneNumber: String = ""
        private set
    @Volatile var avatarUrl: String = ""
        private set
    @Volatile var aboutMe: String = ""
        private set
    @Volatile var createTimeSeconds: Long = 0L
        private set
    @Volatile var userStatus: String = ""
        private set

    init {
        appScope.launch { load() }
    }

    private suspend fun load() {
        val session = withContext(ioDispatcher) { sessionManager.sessionFlow.first() }
        val mode = withContext(ioDispatcher) { themeManager.themeMode.first() }
        val lang = withContext(ioDispatcher) { localeManager.currentLanguage.first() }

        synchronized(this) {
            userId = session?.userId?.toLongOrNull() ?: 0L
            userIdStr = session?.userId ?: ""
            themeMode = mode
            languageTag = lang
        }

        notificationCenter.postNotificationOnMainThread(NotificationCenter.userDataLoaded)
        notificationCenter.postNotificationOnMainThread(
            NotificationCenter.updateInterfaces,
            NotificationCenter.UPDATE_MASK_NAME or NotificationCenter.UPDATE_MASK_AVATAR
        )
    }

    fun updateFromAccount(info: AccountInfo) {
        val nameChanged: Boolean
        val avatarChanged: Boolean
        synchronized(this) {
            nameChanged = username != info.username || displayName != info.displayName
            avatarChanged = avatarUrl != info.avatarUrl
            username = info.username
            displayName = info.displayName
            email = info.email
            phoneNumber = info.phoneNumber
            avatarUrl = info.avatarUrl
            aboutMe = info.aboutMe
            createTimeSeconds = info.createTimeSeconds
            userStatus = info.userStatus
            if (info.userId != 0L) {
                userId = info.userId
                userIdStr = info.userId.toString()
            }
        }
        notificationCenter.postNotificationOnMainThread(NotificationCenter.userDataLoaded)
        var mask = 0
        if (nameChanged) mask = mask or NotificationCenter.UPDATE_MASK_NAME
        if (avatarChanged) mask = mask or NotificationCenter.UPDATE_MASK_AVATAR
        if (mask != 0) {
            notificationCenter.postNotificationOnMainThread(NotificationCenter.updateInterfaces, mask)
        }
    }

    fun applyTheme(mode: ThemeMode) {
        synchronized(this) { themeMode = mode }
        appScope.launch { themeManager.setTheme(mode) }
        NotificationCenter.getGlobalInstance()
            .postNotificationOnMainThread(NotificationCenter.themeChanged, mode)
    }

    fun applyLanguage(tag: String) {
        synchronized(this) { languageTag = tag }
        appScope.launch { localeManager.setLanguage(tag) }
    }

    fun reload() {
        appScope.launch { load() }
    }
}
