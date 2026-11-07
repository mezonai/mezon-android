package com.mezon.mobile.home.clans.settings

import android.widget.LinearLayout
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.ActionBarView

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
}
