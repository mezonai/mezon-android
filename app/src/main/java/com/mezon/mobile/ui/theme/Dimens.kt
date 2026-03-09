package com.mezon.mobile.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowSize { COMPACT, MEDIUM, EXPANDED }

data class ScreenDimens(
    val screenWidth: Dp,
    val screenHeight: Dp,
    val orientation: Int
) {
    val windowSize: WindowSize get() = when {
        screenWidth < 600.dp -> WindowSize.COMPACT
        screenWidth < 840.dp -> WindowSize.MEDIUM
        else -> WindowSize.EXPANDED
    }

    val isCompact: Boolean get() = windowSize == WindowSize.COMPACT
    val isMedium: Boolean get() = windowSize == WindowSize.MEDIUM
    val isExpanded: Boolean get() = windowSize == WindowSize.EXPANDED
    val isLandscape: Boolean get() = orientation == Configuration.ORIENTATION_LANDSCAPE

    fun widthPercent(percent: Float): Dp = screenWidth * percent / 100f
    fun heightPercent(percent: Float): Dp = screenHeight * percent / 100f

    fun responsiveDp(compact: Dp, medium: Dp = compact, expanded: Dp = medium): Dp = when (windowSize) {
        WindowSize.COMPACT -> compact
        WindowSize.MEDIUM -> medium
        WindowSize.EXPANDED -> expanded
    }

    fun responsiveColumns(): Int = when (windowSize) {
        WindowSize.COMPACT -> 1
        WindowSize.MEDIUM -> 2
        WindowSize.EXPANDED -> 3
    }
}

val LocalDimens = staticCompositionLocalOf { ScreenDimens(392.dp, 852.dp, Configuration.ORIENTATION_PORTRAIT) }

@Composable
fun rememberScreenDimens(): ScreenDimens {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation) {
        ScreenDimens(
            screenWidth = configuration.screenWidthDp.dp,
            screenHeight = configuration.screenHeightDp.dp,
            orientation = configuration.orientation
        )
    }
}

object Dimens {
    val avatarSmall = 24.dp
    val avatarMedium = 32.dp
    val avatarLarge = 48.dp
    val avatarXLarge = 64.dp

    val paddingTiny = 2.dp
    val paddingXSmall = 4.dp
    val paddingSmall = 8.dp
    val paddingMedium = 12.dp
    val paddingDefault = 16.dp
    val paddingLarge = 24.dp
    val paddingXLarge = 32.dp

    val iconSmall = 16.dp
    val iconMedium = 24.dp
    val iconLarge = 32.dp

    val cornerSmall = 4.dp
    val cornerMedium = 8.dp
    val cornerLarge = 12.dp
    val cornerXLarge = 16.dp
    val cornerFull = 24.dp

    val dividerHeight = 0.5.dp
    val borderWidth = 1.dp

    val bottomBarHeight = 56.dp
    val topBarHeight = 56.dp

    val sendButtonSize = 48.dp
    val fabSmall = 40.dp
    val emptyIconSize = 64.dp
    val badgeMinSize = 20.dp
    val spinnerSmall = 18.dp
    val onlineIndicatorPadding = 2.dp
    val strokeThin = 2.dp

    val bubbleMaxWidth = 320.dp
    val formMaxWidthMedium = 480.dp
    val formMaxWidthExpanded = 520.dp

    val elevationSmall = 4.dp
    val elevationNone = 0.dp

    val buttonCornerDefault = 10.dp
    val buttonCornerRounded = 20.dp

    val switchWidth = 50.dp
    val switchHeight = 30.dp
    val switchThumbSize = 26.dp
    val switchThumbTravel = 20.dp

    val inputCorner = 10.dp
    val inputTextareaHeight = 100.dp
    val inputClearSize = 18.dp

    val avatarStackOverlap = 20.dp

    val selectDropdownCorner = 12.dp

    val radioSize = 24.dp
    val radioInnerPadding = 5.dp
    val checkboxSize = 20.dp

    val searchBarHeight = 40.dp
    val searchBarCorner = 10.dp

    val menuSectionCorner = 10.dp

    val tabHeaderPadding = 4.dp

    val contextMenuMinWidth = 160.dp

    val fakeBoxCorner = 10.dp

    val slideOptionItemWidth = 65.dp
    val slideOptionItemHeight = 90.dp
    val slideOptionSelectBorder = 3.dp

    val confirmDialogCorner = 16.dp

    val imagePickerDefault = 60.dp
}
