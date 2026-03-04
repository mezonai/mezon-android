package ai.mezon.app.home.chat

import ai.mezon.app.ui.components.MezonAvatar
import ai.mezon.app.ui.theme.Dimens
import ai.mezon.app.util.formatRelativeTime
import ai.mezon.app.util.parseContentText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val BubbleShapeSent = RoundedCornerShape(
    Dimens.cornerXLarge, Dimens.cornerXLarge, Dimens.cornerSmall, Dimens.cornerXLarge
)
private val BubbleShapeReceived = RoundedCornerShape(
    Dimens.cornerXLarge, Dimens.cornerXLarge, Dimens.cornerXLarge, Dimens.cornerSmall
)

@Composable
fun MessageBubble(msg: MessageEntity) {
    if (msg.isMe) MessageBubbleSent(msg) else MessageBubbleReceived(msg)
}

@Composable
private fun MessageBubbleSent(msg: MessageEntity) {
    val content = remember(msg.content) { parseContentText(msg.content) }
    val time = remember(msg.timestampSeconds) { formatRelativeTime(msg.timestampSeconds) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.avatarLarge, end = Dimens.paddingMedium, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Dimens.paddingXSmall, bottom = 2.dp)
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .widthIn(max = Dimens.bubbleMaxWidth)
                .clip(BubbleShapeSent)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = Dimens.paddingMedium, vertical = Dimens.paddingSmall)
        )
    }
}

@Composable
private fun MessageBubbleReceived(msg: MessageEntity) {
    val content = remember(msg.content) { parseContentText(msg.content) }
    val time = remember(msg.timestampSeconds) { formatRelativeTime(msg.timestampSeconds) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.paddingMedium, end = Dimens.avatarLarge, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        MezonAvatar(imageUrl = msg.senderAvatar, name = msg.senderName, size = Dimens.avatarMedium)
        Spacer(Modifier.width(Dimens.paddingSmall))
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = msg.senderName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Dimens.paddingXSmall, bottom = 2.dp)
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = Dimens.bubbleMaxWidth)
                        .clip(BubbleShapeReceived)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = Dimens.paddingMedium, vertical = Dimens.paddingSmall)
                )
                Spacer(Modifier.width(Dimens.paddingXSmall))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}
