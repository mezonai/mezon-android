package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.UserProfileBottomSheet
import com.mezon.mobile.ui.cells.AvatarView

class FriendRequestsFragment : BaseFragment() {

    private enum class Tab { RECEIVED, SENT }

    private lateinit var accountController: AccountController
    private lateinit var listView: RecyclerListView
    private lateinit var emptyContainer: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptySubtitle: TextView
    private lateinit var adapter: RequestAdapter
    private lateinit var receivedTab: TextView
    private lateinit var sentTab: TextView

    private var selectedTab: Tab = Tab.RECEIVED
    private var rows: List<FriendRequestEntry> = emptyList()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.friendRequestsLoaded) { _, _, _ ->
            if (fragmentView == null) return@observe
            reloadData()
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            applyTheme()
            reloadData()
        }
        observe(NotificationCenter.languageChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            updateTabStyle()
            reloadData()
        }
        accountController.loadFriendRequests(noCache = true)
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(10), LayoutHelper.dp(16), LayoutHelper.dp(16))
        }
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, LayoutHelper.dp(10))
        }
        receivedTab = buildTab(context, getString(R.string.friends_request_received)) {
            selectedTab = Tab.RECEIVED
            updateTabStyle()
            reloadData()
            accountController.loadFriendRequests(noCache = true)
        }
        sentTab = buildTab(context, getString(R.string.friends_request_sent)) {
            selectedTab = Tab.SENT
            updateTabStyle()
            reloadData()
            accountController.loadFriendRequests(noCache = true)
        }
        tabs.addView(receivedTab, LinearLayout.LayoutParams(0, LayoutHelper.dp(36), 1f))
        tabs.addView(sentTab, LinearLayout.LayoutParams(0, LayoutHelper.dp(36), 1f).apply {
            leftMargin = LayoutHelper.dp(8)
        })
        root.addView(tabs)

        emptyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val emptyImage = ImageView(context).apply {
            setImageResource(R.drawable.empty_friend)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        emptyContainer.addView(emptyImage, LinearLayout.LayoutParams(
            LayoutHelper.dp(220), LayoutHelper.dp(220)
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        emptyTitle = TextView(context).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(themeColors.onSurface)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        emptyContainer.addView(emptyTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(16) })
        emptySubtitle = TextView(context).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(themeColors.onSurfaceVariant)
        }
        emptyContainer.addView(emptySubtitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(8) })
        root.addView(emptyContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = LayoutHelper.dp(16) })

        listView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        adapter = RequestAdapter()
        listView.adapter = adapter
        root.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        updateTabStyle()
        reloadData()
        return wrapWithActionBar(getString(R.string.friends_request_title), root)
    }

    private fun buildTab(context: Context, label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun updateTabStyle() {
        val activeBg = themeColors.primary
        val inactiveBg = themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_sheetItemBackground)
        val activeText = 0xFFFFFFFF.toInt()
        val inactiveText = themeColors.onSurface
        listOf(receivedTab to (selectedTab == Tab.RECEIVED), sentTab to (selectedTab == Tab.SENT)).forEach { (tab, active) ->
            tab.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(18f).toFloat()
                setColor(if (active) activeBg else inactiveBg)
            }
            tab.setTextColor(if (active) activeText else inactiveText)
        }
    }

    private fun applyTheme() {
        fragmentView?.setBackgroundColor(themeColors.background)
        emptyTitle.setTextColor(themeColors.onSurface)
        emptySubtitle.setTextColor(themeColors.onSurfaceVariant)
        updateTabStyle()
        adapter.notifyDataSetChanged()
    }

    private fun reloadData() {
        val all = accountController.friendRequests.value
        rows = when (selectedTab) {
            Tab.RECEIVED -> all.filter { it.state == 2 }
            Tab.SENT -> all.filter { it.state == 1 }
        }
        adapter.notifyDataSetChanged()
        val empty = rows.isEmpty()
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyContainer.visibility = if (empty) View.VISIBLE else View.GONE
        emptyTitle.text = when (selectedTab) {
            Tab.RECEIVED -> getString(R.string.friends_empty_received)
            Tab.SENT -> getString(R.string.friends_empty_sent)
        }
        emptySubtitle.text = when (selectedTab) {
            Tab.RECEIVED -> getString(R.string.friends_empty_received_sub)
            Tab.SENT -> getString(R.string.friends_empty_sent_sub)
        }
    }

    private inner class RequestAdapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true
        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = RequestCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = LayoutHelper.dp(8)
            }
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as RequestCell).bind(rows[position], position < rows.lastIndex)
        }
    }

    private inner class RequestCell(context: Context) : LinearLayout(context) {
        private val avatar = AvatarView(context)
        private val name = TextView(context)
        private val deleteBtn = ImageView(context)
        private val approveBtn = LinearLayout(context)
        private var bound: FriendRequestEntry? = null

        init {
            orientation = VERTICAL
            val cardBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.getColor(com.mezon.mobile.core.ThemeColors.key_sheetItemBackground))
            }
            background = cardBg

            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
            }

            avatar.setSizeDp(44)
            avatar.setRoundRadius(22f)
            row.addView(avatar, LinearLayout.LayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)))

            name.setTextColor(themeColors.onSurface)
            name.textSize = 15f
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            name.setPadding(LayoutHelper.dp(12), 0, 0, 0)
            row.addView(name, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))

            deleteBtn.setImageResource(R.drawable.ic_close_small_bold_icon)
            deleteBtn.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            deleteBtn.setPadding(LayoutHelper.dp(6), LayoutHelper.dp(6), LayoutHelper.dp(6), LayoutHelper.dp(6))
            deleteBtn.isClickable = true
            deleteBtn.isFocusable = true
            row.addView(deleteBtn, LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36)).apply {
                leftMargin = LayoutHelper.dp(8)
            })

            val checkIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_checkmark_small_icon)
                colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
            }
            approveBtn.apply {
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFF16A34A.toInt())
                }
                isClickable = true
                isFocusable = true
                addView(checkIcon, LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36)))
            }
            row.addView(approveBtn, LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36)).apply {
                leftMargin = LayoutHelper.dp(8)
            })

            addView(row, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

            row.setOnClickListener { bound?.let { showUserInfo(it) } }
            row.setOnLongClickListener {
                bound?.let { showUserInfo(it) }
                true
            }
            deleteBtn.setOnClickListener {
                val entry = bound ?: return@setOnClickListener
                accountController.deleteFriendRelation(entry) { success ->
                    if (!success) Toast.makeText(context, R.string.common_something_went_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            approveBtn.setOnClickListener {
                val entry = bound ?: return@setOnClickListener
                accountController.acceptFriendRequest(entry) { success ->
                    if (!success) Toast.makeText(context, R.string.common_something_went_wrong, Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun bind(entry: FriendRequestEntry, showDivider: Boolean) {
            bound = entry
            val display = entry.displayName.ifEmpty { entry.username }
            name.text = display
            avatar.setInfo(entry.id, display)
            avatar.setImageUrl(entry.avatarUrl.ifBlank { null })
            approveBtn.visibility = if (entry.state != 1) View.VISIBLE else View.GONE
        }
    }

    private fun showUserInfo(entry: FriendRequestEntry) {
        UserProfileBottomSheet(
            context = requireContext(),
            userId = entry.id,
            displayName = entry.displayName,
            username = entry.username,
            avatarUrl = entry.avatarUrl,
            isOwnProfile = false,
            isDM = true
        ).show()
    }
}

