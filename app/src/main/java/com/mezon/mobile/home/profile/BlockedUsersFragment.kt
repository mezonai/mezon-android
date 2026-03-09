package com.mezon.mobile.home.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import coil.load
import coil.transform.CircleCropTransformation
import com.mezon.mezon.api.Friend
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BlockedUsersFragment : BaseFragment() {

    @Inject lateinit var accountController: AccountController

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var listAdapter: BlockedAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val contentFrame = FrameLayout(requireContext()).apply {
            setBackgroundColor(themeColors.background)
        }

        listAdapter = BlockedAdapter()
        recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = listAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        contentFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emptyView = TextView(requireContext()).apply {
            text = getString(R.string.blocked_users_empty)
            textSize = 15f
            setTextColor(themeColors.onSurfaceVariant)
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        contentFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        return wrapWithActionBar(getString(R.string.blocked_users_title), contentFrame)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observe(NotificationCenter.blockedUsersLoaded) { _, _ ->
            updateList()
        }
        observe(NotificationCenter.themeChanged) { _, _ ->
            view.setBackgroundColor(themeColors.background)
            emptyView.setTextColor(themeColors.onSurfaceVariant)
            listAdapter.notifyDataSetChanged()
        }

        accountController.loadBlockedUsers()
        updateList()
    }

    private fun updateList() {
        val users = accountController.blockedUsers.value
        listAdapter.submitList(users)
        recyclerView.visibility = if (users.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onUnblockClicked(friend: Friend) {
        AlertsCreator.createConfirmDialog(
            requireContext(),
            getString(R.string.blocked_unblock_title),
            getString(R.string.blocked_unblock_description, friend.user.displayName.ifEmpty { friend.user.username }),
            confirmText = getString(R.string.blocked_unblock_confirm),
            cancelText = getString(R.string.common_cancel)
        ) {
            accountController.unblockUser(friend.user.id, friend.user.username) { success ->
                if (!success) {
                    AlertsCreator.showSimpleAlert(
                        requireContext(),
                        getString(R.string.common_error),
                        getString(R.string.blocked_unblock_error)
                    )
                }
            }
        }.show()
    }

    private inner class BlockedAdapter : RecyclerView.Adapter<BlockedAdapter.VH>() {
        private val items = mutableListOf<Friend>()

        fun submitList(newItems: List<Friend>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(themeColors.surface)
                val vPad = LayoutHelper.dp(12)
                val hPad = LayoutHelper.dp(16)
                setPadding(hPad, vPad, hPad, vPad)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = LayoutHelper.dp(1) }
            }

            val avatar = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            row.addView(avatar, LayoutHelper.createLinear(40, 40, 0f, 0, 0f, 0f, 12f, 0f))

            val nameText = TextView(parent.context).apply {
                textSize = 15f
                setTextColor(themeColors.onSurface)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(nameText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

            val unblockBtn = TextView(parent.context).apply {
                text = context.getString(R.string.blocked_unblock_button)
                textSize = 13f
                setTextColor(themeColors.primary)
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(6), LayoutHelper.dp(12), LayoutHelper.dp(6))
            }
            row.addView(unblockBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

            return VH(row, avatar, nameText, unblockBtn)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val friend = items[position]
            val user = friend.user
            val displayName = user.displayName.ifEmpty { user.username }

            holder.nameText.text = displayName
            holder.nameText.setTextColor(themeColors.onSurface)
            holder.unblockBtn.setTextColor(themeColors.primary)
            holder.row.setBackgroundColor(themeColors.surface)

            if (user.avatarUrl.isNotEmpty()) {
                holder.avatar.load(user.avatarUrl) {
                    transformations(CircleCropTransformation())
                    placeholder(R.drawable.ic_profile)
                }
            } else {
                holder.avatar.setImageResource(R.drawable.ic_profile)
            }

            holder.unblockBtn.setOnClickListener { onUnblockClicked(friend) }
        }

        inner class VH(
            val row: LinearLayout,
            val avatar: ImageView,
            val nameText: TextView,
            val unblockBtn: TextView
        ) : RecyclerView.ViewHolder(row)
    }
}
