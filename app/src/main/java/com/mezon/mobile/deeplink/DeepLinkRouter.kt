package com.mezon.mobile.deeplink

import android.net.Uri
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.StartupCache
import com.mezon.mobile.di.IoDispatcher
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.channelapp.ChannelAppController
import com.mezon.mobile.home.clans.channelapp.ChannelAppFragment
import com.mezon.mobile.home.qr.QrPayloadParser
import com.mezon.mobile.home.qr.QrProfileFragment
import com.mezon.mobile.ui.cells.ToastOverlay
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeepLinkRouter @Inject constructor(
    private val clansController: ClansController,
    private val channelAppController: ChannelAppController,
    private val notificationCenter: NotificationCenter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    @Volatile
    private var pendingRoute: DeepLinkRoute? = null

    @Volatile
    private var pendingSourceUrl: String? = null

    fun hasPending(): Boolean = pendingRoute != null

    fun clearPending() {
        pendingRoute = null
        pendingSourceUrl = null
    }

    fun ingest(uri: Uri): DeepLinkRoute? {
        val route = DeepLinkParser.parse(uri) ?: return null
        pendingRoute = route
        pendingSourceUrl = uri.toString()
        return route
    }

    fun dispatchPending(activity: MainActivity) {
        val route = pendingRoute ?: return
        val sourceUrl = pendingSourceUrl
        pendingRoute = null
        pendingSourceUrl = null
        dispatchRoute(activity, route, sourceUrl)
    }

    fun dispatchRoute(activity: MainActivity, route: DeepLinkRoute, sourceUrl: String? = null) {
        if (!StartupCache.hasSession || StartupCache.needsUsernameSetup) {
            pendingRoute = route
            pendingSourceUrl = sourceUrl
            return
        }
        AndroidUtilities.runOnUIThread {
            when (route) {
                is DeepLinkRoute.ChannelApp -> dispatchChannelApp(activity, route)
                is DeepLinkRoute.Invite -> presentFragment(activity, InviteClanFragment.newInstance(route.inviteId))
                is DeepLinkRoute.Profile -> dispatchProfile(activity, route)
                is DeepLinkRoute.BotInstall -> presentFragment(
                    activity,
                    InstallClanFragment.newInstance(route.appId, InstallKind.BOT, sourceUrl)
                )
                is DeepLinkRoute.AppInstall -> presentFragment(
                    activity,
                    InstallClanFragment.newInstance(route.appId, InstallKind.APP, sourceUrl)
                )
            }
        }
    }

    private fun dispatchProfile(activity: MainActivity, route: DeepLinkRoute.Profile) {
        val payload = QrPayloadParser.decodeProfilePayload(route.data)
        presentFragment(
            activity,
            QrProfileFragment.newInstance(
                username = route.username,
                displayName = payload?.name.orEmpty(),
                avatarUrl = payload?.avatar,
                userId = payload?.id ?: 0L
            )
        )
    }

    private fun dispatchChannelApp(activity: MainActivity, route: DeepLinkRoute.ChannelApp) {
        if (route.channelId == 0L || route.clanId == 0L) return
        clansController.selectClan(route.clanId, force = true)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
        activity.lifecycleScope.launch {
            val app = withContext(ioDispatcher) {
                channelAppController.ensureAppLoaded(route.channelId, route.clanId)
            }
            if (app == null) {
                ToastOverlay(activity, activity.themeColors).show(
                    activity.drawerLayoutContainer,
                    ToastOverlay.ToastType.INFO,
                    activity.getString(R.string.channel_app_launch_unavailable),
                    null
                )
                return@launch
            }
            presentFragment(
                activity,
                ChannelAppFragment.newInstance(
                    channelId = app.channelId,
                    clanId = if (app.clanId != 0L) app.clanId else route.clanId,
                    appId = app.appId,
                    appUrl = app.appUrl,
                    appName = app.appName
                )
            )
        }
    }

    private fun presentFragment(activity: MainActivity, fragment: com.mezon.mobile.core.BaseFragment) {
        activity.actionBarLayout.presentFragment(fragment)
    }
}
