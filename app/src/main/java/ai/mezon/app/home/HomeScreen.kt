package ai.mezon.app.home

import ai.mezon.app.R
import ai.mezon.app.home.clans.ClansScreen
import ai.mezon.app.home.messages.MessagesScreen
import ai.mezon.app.home.notifications.NotificationsScreen
import ai.mezon.app.home.profile.ProfileScreen
import ai.mezon.app.network.ConnectionState
import ai.mezon.app.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

private val BlurpleColor = Color(0xFF5A62F4)

@Composable
fun HomeScreen(
    onLogout: () -> Unit,
    onOpenChat: (channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit = { _, _, _, _ -> },
    viewModel: HomeViewModel = hiltViewModel()
) {
    var selectedTab by rememberSaveable { mutableStateOf(BottomTab.CLANS) }
    val socketState by viewModel.connectionState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.onAppForeground()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.forceLogout.collect { onLogout() }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                BottomTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.icon,
                                contentDescription = stringResource(tab.labelRes)
                            )
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = BlurpleColor,
                            indicatorColor = BlurpleColor,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                BottomTab.CLANS -> ClansScreen()
                BottomTab.MESSAGES -> MessagesScreen(onOpenChat = onOpenChat)
                BottomTab.NOTIFICATIONS -> NotificationsScreen()
                BottomTab.PROFILE -> ProfileScreen(onLogout = {
                    viewModel.disconnect()
                    onLogout()
                })
            }

            SocketIndicator(
                state = socketState,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Dimens.paddingSmall)
            )
        }
    }
}

@Composable
private fun SocketIndicator(state: ConnectionState, modifier: Modifier = Modifier) {
    val (color, label) = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF43B581) to stringResource(R.string.connection_connected)
        ConnectionState.CONNECTING -> Color(0xFFFAA61A) to stringResource(R.string.connection_connecting)
        ConnectionState.DISCONNECTED -> Color(0xFFF04747) to stringResource(R.string.connection_disconnected)
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.paddingSmall)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(Dimens.paddingXSmall))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
