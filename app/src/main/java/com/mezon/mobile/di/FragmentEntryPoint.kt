package com.mezon.mobile.di

import com.mezon.mobile.auth.AuthRepository
import com.mezon.mobile.home.AnonymousController
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.ConnectionController
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.PinMessageController
import com.mezon.mobile.home.chat.AudioPlayerController
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.voice.VoiceController
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.MediaController
import com.mezon.mobile.home.MessagesController
import com.mezon.mobile.home.clans.ChannelCategoryExpandStore
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.channelapp.ChannelAppController
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.notifications.NotificationStore
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.profile.DeviceController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.notification.FcmRepository
import com.mezon.mobile.search.SearchController
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.wallet.WalletController
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
    fun channelAppController(): ChannelAppController
    fun channelCategoryExpandStore(): ChannelCategoryExpandStore
    fun connectionController(): ConnectionController
    fun dialogsController(): DialogsController
    fun messagesController(): MessagesController
    fun notificationStore(): NotificationStore
    fun accountController(): AccountController
    fun deviceController(): DeviceController
    fun friendController(): FriendController
    fun userController(): UserController
    fun mezonApi(): MezonApi
    fun fcmRepository(): FcmRepository
    fun mediaController(): MediaController
    fun searchController(): SearchController
    fun userClanController(): UserClanController
    fun emojiController(): EmojiController
    fun audioPlayerController(): AudioPlayerController
    fun voiceController(): VoiceController
    fun anonymousController(): AnonymousController
    fun memberResolver(): MemberResolver
    fun roleController(): RoleController
    fun pinMessageController(): PinMessageController
    fun sessionManager(): SessionManager
    fun walletController(): WalletController

    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher

    @MainDispatcher
    fun mainDispatcher(): CoroutineDispatcher

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}
