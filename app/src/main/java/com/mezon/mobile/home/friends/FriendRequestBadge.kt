package com.mezon.mobile.home.friends

import android.content.Context
import android.widget.TextView
import com.mezon.mobile.core.theme.MobileThemeColors
import com.mezon.mobile.ui.cells.createCountBadgeView
import com.mezon.mobile.ui.cells.updateCountBadge

internal fun createFriendRequestBadgeView(context: Context): TextView =
    createCountBadgeView(context, MobileThemeColors.unreadBadge)

internal fun TextView.updateFriendRequestBadge(count: Int) {
    updateCountBadge(count, MobileThemeColors.unreadBadge)
}
