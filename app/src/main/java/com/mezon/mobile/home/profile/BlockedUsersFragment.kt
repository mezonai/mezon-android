package com.mezon.mobile.home.profile

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.graphics.drawable.BitmapDrawable
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mezon.api.Friend
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint

class BlockedUsersFragment : BaseFragment() {

    private lateinit var accountController: AccountController

    private lateinit var recyclerView: RecyclerListView
    private lateinit var emptyView: TextView
    private lateinit var listAdapter: BlockedAdapter

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.blockedUsersLoaded) { _, _, _ ->
            if (fragmentView != null) updateList()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            if ((mask and NotificationCenter.UPDATE_MASK_NAME) != 0 ||
                (mask and NotificationCenter.UPDATE_MASK_AVATAR) != 0 || mask == 0
            ) {
                updateList()
            }
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.background)
            emptyView.setTextColor(themeColors.onSurfaceVariant)
            listAdapter.notifyDataSetChanged()
        }

        accountController.loadBlockedUsers()
        return true
    }

    override fun createView(context: Context): View {
        val contentFrame = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }

        listAdapter = BlockedAdapter()
        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setOnItemClickListener(RecyclerListView.OnItemClickListener { view, position ->
                val friend = listAdapter.getItem(position) ?: return@OnItemClickListener
                onUnblockClicked(friend)
            })
        }
        contentFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emptyView = TextView(context).apply {
            text = getString(R.string.blocked_users_empty)
            textSize = 15f
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        updateList()

        return wrapWithActionBar(getString(R.string.blocked_users_title), contentFrame)
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

    private inner class BlockedAdapter : RecyclerListView.SelectionAdapter() {
        private val items = mutableListOf<Friend>()

        fun submitList(newItems: List<Friend>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun getItem(position: Int): Friend? = items.getOrNull(position)

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = BlockedUserCell(parent.context)
            cell.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val friend = items[position]
            (holder.itemView as BlockedUserCell).bind(friend, position < items.size - 1)
        }
    }

    private inner class BlockedUserCell(context: Context) : FrameLayout(context) {
        private val avatar: ImageView
        private val nameText: TextView
        private val unblockBtn: TextView
        private var avatarCancellable: MezonImageLoader.Cancellable? = null
        private var currentAvatarUrl: String? = null

        init {
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val vPad = LayoutHelper.dp(12)
                val hPad = LayoutHelper.dp(16)
                setPadding(hPad, vPad, hPad, vPad)
            }

            avatar = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            row.addView(avatar, LayoutHelper.createLinear(40, 40, 0f, 0, 0f, 0f, 12f, 0f))

            nameText = TextView(context).apply {
                textSize = 15f
                setTextColor(themeColors.onSurface)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(nameText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

            unblockBtn = TextView(context).apply {
                text = context.getString(R.string.blocked_unblock_button)
                textSize = 13f
                setTextColor(themeColors.primary)
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(6), LayoutHelper.dp(12), LayoutHelper.dp(6))
            }
            row.addView(unblockBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            setWillNotDraw(true)
        }

        fun bind(friend: Friend, divider: Boolean) {
            val user = friend.user
            nameText.text = user.displayName.ifEmpty { user.username }
            nameText.setTextColor(themeColors.onSurface)
            unblockBtn.setTextColor(themeColors.primary)

            avatarCancellable?.cancel()
            avatarCancellable = null

            if (user.avatarUrl.isNotEmpty()) {
                val sizePx = LayoutHelper.dp(40)
                val proxyUrl = createImgproxyUrl(user.avatarUrl, sizePx * 2, sizePx * 2, "fill")
                if (proxyUrl == currentAvatarUrl) {
                    unblockBtn.setOnClickListener { onUnblockClicked(friend) }
                    setWillNotDraw(!divider)
                    return
                }
                currentAvatarUrl = proxyUrl
                avatar.setImageResource(R.drawable.ic_profile)

                val loader = MezonImageLoader.getInstance(context)
                val cached = loader.getBitmapFromMemory(proxyUrl, sizePx, sizePx)
                if (cached != null) {
                    avatar.setImageDrawable(BitmapDrawable(context.resources, cached))
                } else {
                    avatarCancellable = loader.load(proxyUrl, sizePx, sizePx, onSuccess = { bmp ->
                        avatar.setImageDrawable(BitmapDrawable(context.resources, bmp))
                    })
                }
            } else {
                currentAvatarUrl = null
                avatar.setImageResource(R.drawable.ic_profile)
            }

            unblockBtn.setOnClickListener { onUnblockClicked(friend) }

            setWillNotDraw(!divider)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            avatarCancellable?.cancel()
            avatarCancellable = null
        }

        override fun hasOverlappingRendering(): Boolean = false

        override fun onDraw(canvas: android.graphics.Canvas) {
            val leftPad = LayoutHelper.dp(68).toFloat()
            val y = (height - 1).toFloat()
            canvas.drawRect(leftPad, y, width.toFloat(), y + 1f, themeColors.dividerPaint)
        }
    }
}
