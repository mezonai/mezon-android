package com.mezon.mobile.home.clans.settings

import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.theme.ThemeMode

object ClanRolesUiTheme {

    fun rnScreenBackground(theme: ThemeColors): Int = theme.background

    fun applyPrimaryFlowRoot(root: LinearLayout, theme: ThemeColors) {
        root.setBackgroundColor(rnScreenBackground(theme))
    }

    fun applyPrimaryFlowActionBar(actionBar: ActionBarView, theme: ThemeColors) {
        actionBar.setBackgroundColor(rnScreenBackground(theme))
        actionBar.setTitleColor(theme.textStrong)
        actionBar.setItemsColor(theme.textStrong, false)
        actionBar.setDrawDivider(false)
    }

    fun textOnScreenMuted(theme: ThemeColors): Int = theme.colorText

    fun secondaryCardTitleColor(theme: ThemeColors): Int = theme.textStrong

    internal fun badgeIconTint(backgroundColor: Int, theme: ThemeColors): Int? {
        val lightTint = ThemeMode.DARK.iconTint
        if (backgroundColor == theme.blurple) return lightTint
        if ((backgroundColor ushr 24) != 0xFF) return null

        val darkTint = ThemeMode.LIGHT.iconTint
        val darkContrast = ColorUtils.calculateContrast(darkTint, backgroundColor)
        val lightContrast = ColorUtils.calculateContrast(lightTint, backgroundColor)
        return if (darkContrast > lightContrast) darkTint else lightTint
    }
}
