package com.mezon.mobile.di

import com.mezon.mobile.auth.AuthRepository
import com.mezon.mobile.home.AnonymousController
import com.mezon.mobile.home.BadgeCoordinator
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.TopicBadgeTracker
import com.mezon.mobile.home.TopicController
import com.mezon.mobile.home.ConnectionController
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.ChannelFilesController
import com.mezon.mobile.home.ChannelGalleryController
import com.mezon.mobile.home.PinMessageController
import com.mezon.mobile.home.chat.AudioPlayerController
import com.mezon.mobile.home.call.CallController
import com.mezon.mobile.home.call.CallManager
import com.mezon.mobile.home.call.WebRtcInfra
import com.mezon.mobile.home.chat.EmojiController
import com.mezon.mobile.home.clans.settings.SoundEffectSettingsController
import com.mezon.mobile.home.clans.settings.StickerSettingsController
import com.mezon.mobile.home.chat.ImageClipboardCoordinator
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.ForwardTargetUsageStore
import com.mezon.mobile.home.messages.MessageActivitiesController
import com.mezon.mobile.home.voice.VoiceController
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.MediaController
import com.mezon.mobile.home.MessagesController
import com.mezon.mobile.home.clans.ChannelCategoryExpandStore
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ChannelPermissionController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.settings.CommunitySettingsController
import com.mezon.mobile.home.clans.channelapp.ChannelAppController
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.clans.settings.InvitePeopleController
import com.mezon.mobile.home.clans.settings.OnboardingSettingsController
import com.mezon.mobile.home.notifications.NotificationStore
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.home.profile.DeviceController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.ApiCacheTracker
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.network.NetworkMonitor
import com.mezon.mobile.notification.FcmRepository
import com.mezon.mobile.search.SearchController
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.util.SentryReporter
import com.mezon.mobile.wallet.WalletController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FragmentEntryPoint {
    fun authRepository(): AuthRepository
    fun badgeCoordinator(): BadgeCoordinator
    fun chatController(): ChatController
    fun clansController(): ClansController
    fun channelController(): ChannelController
    fun channelPermissionController(): ChannelPermissionController
    fun permissionPolicy(): PermissionPolicy
    fun channelAppController(): ChannelAppController
    fun channelCategoryExpandStore(): ChannelCategoryExpandStore
    fun connectionController(): ConnectionController
    fun dialogsController(): DialogsController
    fun forwardTargetUsageStore(): ForwardTargetUsageStore
    fun messageActivitiesController(): MessageActivitiesController
    fun messagesController(): MessagesController
    fun notificationStore(): NotificationStore
    fun topicBadgeTracker(): TopicBadgeTracker
    fun topicController(): TopicController
    fun accountController(): AccountController
    fun deviceController(): DeviceController
    fun friendController(): FriendController
    fun userController(): UserController
    fun mezonApi(): MezonApi
    fun networkMonitor(): NetworkMonitor
    fun fcmRepository(): FcmRepository
    fun mediaController(): MediaController
    fun searchController(): SearchController
    fun userClanController(): UserClanController
    fun emojiController(): EmojiController
    fun soundEffectSettingsController(): SoundEffectSettingsController
    fun stickerSettingsController(): StickerSettingsController
    fun imageClipboardCoordinator(): ImageClipboardCoordinator
    fun audioPlayerController(): AudioPlayerController
    fun voiceController(): VoiceController
    fun anonymousController(): AnonymousController
    fun memberResolver(): MemberResolver
    fun roleController(): RoleController
    fun onboardingSettingsController(): OnboardingSettingsController
    fun communitySettingsController(): CommunitySettingsController
    fun invitePeopleController(): InvitePeopleController
    fun pinMessageController(): PinMessageController
    fun channelFilesController(): ChannelFilesController
    fun channelGalleryController(): ChannelGalleryController
    fun sessionManager(): SessionManager
    fun walletController(): WalletController
    fun apiCacheTracker(): ApiCacheTracker
    fun callController(): CallController
    fun callManager(): CallManager
    fun webRtcInfra(): WebRtcInfra
    fun okHttpClient(): OkHttpClient
    fun sentryReporter(): SentryReporter

    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher

    @MainDispatcher
    fun mainDispatcher(): CoroutineDispatcher

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}
