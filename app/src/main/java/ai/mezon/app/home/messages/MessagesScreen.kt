package ai.mezon.app.home.messages

import ai.mezon.app.R
import ai.mezon.app.network.CHANNEL_TYPE_DM
import ai.mezon.app.ui.components.MezonAvatar
import ai.mezon.app.ui.components.MezonBadge
import ai.mezon.app.ui.components.MezonEmptyScreen
import ai.mezon.app.ui.components.MezonErrorScreen
import ai.mezon.app.ui.components.MezonLoadingScreen
import ai.mezon.app.ui.theme.Dimens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val DividerStartPadding = Dimens.avatarLarge + Dimens.paddingMedium + Dimens.paddingDefault

@Composable
fun MessagesScreen(
    onOpenChat: (channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit = { _, _, _, _ -> },
    viewModel: MessagesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        MessagesUiState.Loading -> MezonLoadingScreen()

        MessagesUiState.Empty -> MezonEmptyScreen(
            icon = Icons.Default.ChatBubble,
            message = stringResource(R.string.dm_no_messages)
        )

        is MessagesUiState.Error -> MezonErrorScreen(
            message = state.message,
            onRetry = { viewModel.onIntent(MessagesIntent.Load) }
        )

        is MessagesUiState.Success -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = state.messages,
                    key = { it.channelId },
                    contentType = { it.type }
                ) { dm ->
                    DmListItem(
                        dm = dm,
                        onClick = {
                            onOpenChat(dm.channelId, dm.displayName.ifEmpty { dm.label }, 0L, dm.type)
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = DividerStartPadding),
                        thickness = Dimens.dividerHeight,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DmListItem(dm: DirectMessage, onClick: () -> Unit) {
    val noMessagesText = stringResource(R.string.dm_no_messages)
    val timeText = remember(dm.lastMessageTimestamp) { formatRelativeTime(dm.lastMessageTimestamp) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.paddingDefault, vertical = Dimens.paddingMedium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MezonAvatar(
            imageUrl = dm.avatarUrl,
            name = dm.displayName,
            size = Dimens.avatarLarge,
            showOnlineIndicator = dm.type == CHANNEL_TYPE_DM,
            isOnline = dm.isOnline
        )

        Spacer(Modifier.width(Dimens.paddingMedium))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dm.displayName.ifEmpty { dm.label },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (dm.unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(Dimens.paddingSmall))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dm.unreadCount > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dm.lastMessageContent.ifEmpty { noMessagesText },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dm.unreadCount > 0)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (dm.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (dm.unreadCount > 0) {
                    Spacer(Modifier.width(Dimens.paddingSmall))
                    MezonBadge(count = dm.unreadCount)
                }
            }
        }
    }
}

private fun formatRelativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val now = System.currentTimeMillis() / 1000
    val diff = now - epochSeconds

    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        diff < 604800 -> "${diff / 86400}d"
        diff < 2592000 -> "${diff / 604800}w"
        else -> "${diff / 2592000}mo"
    }
}
