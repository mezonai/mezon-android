package com.mezon.mobile.home.profile

// ...existing code...
import android.view.View
import android.widget.LinearLayout
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.R

class SentFriendRequestsFragment : BaseFragment() {
    override fun createView(context: android.content.Context): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            val emptyView = android.widget.TextView(context).apply {
                text = context.getString(R.string.sent_friend_requests_placeholder)
                setTextColor(themeColors.onSurfaceVariant)
                textSize = 18f
                gravity = android.view.Gravity.CENTER
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(emptyView, params)
        }
        return wrapWithActionBar(context.getString(R.string.sent_friend_requests_title), layout)
    }
}

