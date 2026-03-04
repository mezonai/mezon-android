package ai.mezon.app

import ai.mezon.app.navigation.AppNavGraph
import ai.mezon.app.navigation.NavRoutes
import ai.mezon.app.notification.NotificationHelper
import ai.mezon.app.session.LocaleManager
import ai.mezon.app.session.SessionManager
import ai.mezon.app.session.ThemeManager
import ai.mezon.app.ui.theme.MezonTheme
import ai.mezon.app.ui.theme.ThemeMode
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var localeManager: LocaleManager
    @Inject lateinit var themeManager: ThemeManager

    private var resolvedDest: String? = null
    private var navController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val restored = savedInstanceState?.getString(KEY_START_DEST)
        var startDestination by mutableStateOf(restored)

        if (restored != null) {
            resolvedDest = restored
        } else {
            lifecycleScope.launch {
                localeManager.restoreOnColdStart()
                val stored = sessionManager.sessionFlow.first()
                val dest = if (stored != null) NavRoutes.HOME else NavRoutes.LOGIN
                resolvedDest = dest
                startDestination = dest
            }
        }

        setContent {
            val themeMode by themeManager.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)

            MezonTheme(themeMode = themeMode) {
                val dest = startDestination
                if (dest != null) {
                    val nc = rememberNavController()
                    navController = nc
                    AppNavGraph(
                        navController = nc,
                        startDestination = dest
                    )
                }
            }
        }

        if (savedInstanceState == null) {
            handleNotificationIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        intent ?: return
        val extras = intent.extras ?: return

        val channelId = extras.getLong(NotificationHelper.EXTRA_CHANNEL_ID, 0L)
        val dmId = extras.getLong(NotificationHelper.EXTRA_DM_ID, 0L)

        if (channelId != 0L) {
            val channelName = extras.getString(NotificationHelper.EXTRA_CHANNEL_NAME, "")
            val clanId = extras.getLong(NotificationHelper.EXTRA_CLAN_ID, 0L)
            val channelType = extras.getInt(NotificationHelper.EXTRA_CHANNEL_TYPE, 0)
            lifecycleScope.launch {
                val session = sessionManager.sessionFlow.first()
                if (session != null) {
                    navController?.navigate(
                        NavRoutes.chatRoute(channelId, channelName, clanId, channelType)
                    )
                }
            }
            intent.removeExtra(NotificationHelper.EXTRA_CHANNEL_ID)
        } else if (dmId != 0L) {
            lifecycleScope.launch {
                val session = sessionManager.sessionFlow.first()
                if (session != null) {
                    navController?.navigate(
                        NavRoutes.chatRoute(dmId, "", 0L, 3)
                    )
                }
            }
            intent.removeExtra(NotificationHelper.EXTRA_DM_ID)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        resolvedDest?.let { outState.putString(KEY_START_DEST, it) }
    }

    companion object {
        private const val KEY_START_DEST = "start_dest"
    }
}
