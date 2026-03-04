package ai.mezon.app.home

import ai.mezon.app.R
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    CLANS(
        labelRes = R.string.clan_title,
        icon = Icons.Outlined.Groups,
        selectedIcon = Icons.Filled.Groups
    ),
    MESSAGES(
        labelRes = R.string.screen_tab_messages,
        icon = Icons.Outlined.ChatBubbleOutline,
        selectedIcon = Icons.Filled.ChatBubble
    ),
    NOTIFICATIONS(
        labelRes = R.string.screen_tab_notifications,
        icon = Icons.Outlined.Notifications,
        selectedIcon = Icons.Filled.Notifications
    ),
    PROFILE(
        labelRes = R.string.screen_tab_profile,
        icon = Icons.Outlined.Person,
        selectedIcon = Icons.Filled.Person
    )
}
