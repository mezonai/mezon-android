package ai.mezon.app.ui.components

import ai.mezon.app.ui.theme.Dimens
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

private val BlurpleColor = Color(0xFF717AEF)

@Composable
fun MezonBadge(
    count: Int,
    modifier: Modifier = Modifier,
    backgroundColor: Color = BlurpleColor,
    textColor: Color = Color.White
) {
    if (count <= 0) return

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = Dimens.badgeMinSize, minHeight = Dimens.badgeMinSize)
            .background(backgroundColor, CircleShape)
            .padding(horizontal = Dimens.paddingXSmall),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
