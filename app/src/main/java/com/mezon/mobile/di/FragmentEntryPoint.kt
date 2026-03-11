package com.mezon.mobile.di

import com.mezon.mobile.auth.AuthRepository
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.ConnectionController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.MessagesController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.notifications.NotificationStore
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.MezonApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FragmentEntryPoint {
    fun authRepository(): AuthRepository
    fun chatController(): ChatController
    fun clansController(): ClansController
    fun channelController(): ChannelController
    fun connectionController(): ConnectionController
    fun dialogsController(): DialogsController
    fun messagesController(): MessagesController
    fun notificationStore(): NotificationStore
    fun accountController(): AccountController
    fun userController(): UserController
    fun mezonApi(): MezonApi

    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher

    @MainDispatcher
    fun mainDispatcher(): CoroutineDispatcher

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}
