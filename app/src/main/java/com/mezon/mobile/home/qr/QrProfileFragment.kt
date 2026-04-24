package com.mezon.mobile.home.qr

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QrProfileFragment : BaseFragment() {

    companion object {
        private const val ARG_DISPLAY_NAME = "displayName"
        private const val ARG_USERNAME     = "username"
        private const val ARG_AVATAR_URL   = "avatarUrl"
        private const val ARG_USER_ID      = "userId"

        fun newInstance(
            username: String,
            displayName: String,
            avatarUrl: String?,
            userId: Long = 0L
        ): QrProfileFragment {
            return QrProfileFragment().apply {
                arguments = android.os.Bundle().apply {
                    putString(ARG_USERNAME,     username)
                    putString(ARG_DISPLAY_NAME, displayName)
                    putString(ARG_AVATAR_URL,   avatarUrl)
                    putLong(ARG_USER_ID,        userId)
                }
            }
        }
    }

    private var displayName: String = ""
    private var username: String    = ""
    private var avatarUrl: String?  = null
    private var userId: Long        = 0L

    private lateinit var friendController:  FriendController
    private lateinit var dialogsController: DialogsController
    private lateinit var chatController:    ChatController

    override fun onInject(entryPoint: FragmentEntryPoint) {
        friendController  = entryPoint.friendController()
        dialogsController = entryPoint.dialogsController()
        chatController    = entryPoint.chatController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        val args = arguments
        displayName = args?.getString(ARG_DISPLAY_NAME).orEmpty()
        username    = args?.getString(ARG_USERNAME).orEmpty()
        avatarUrl   = args?.getString(ARG_AVATAR_URL)
        userId      = args?.getLong(ARG_USER_ID, 0L) ?: 0L
        return true
    }

    override fun createView(context: android.content.Context): View {
        val pageBg = themeColors.background
        val root = FrameLayout(context).apply {
            setBackgroundColor(pageBg)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16f).toFloat()
                setColor(themeColors.surface)
            }
            setPadding(
                LayoutHelper.dp(20),
                LayoutHelper.dp(16),
                LayoutHelper.dp(20),
                LayoutHelper.dp(16)
            )
        }
        root.addView(card, FrameLayout.LayoutParams(
            LayoutHelper.dp(300),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        val title = TextView(context).apply {
            text = getString(R.string.qr_profile_sheet_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(themeColors.onSurfaceVariant)
            letterSpacing = 0.05f
        }
        card.addView(title)

        val avatar = AvatarView(context).apply {
            setSizeDp(64)
            setRoundRadius(32f)
            val nameSeed = displayName.ifEmpty { username }
            setInfo(0L, nameSeed)
            if (!avatarUrl.isNullOrBlank()) setImageUrl(avatarUrl)
        }
        card.addView(avatar, LinearLayout.LayoutParams(
            LayoutHelper.dp(64), LayoutHelper.dp(64)
        ).apply { topMargin = LayoutHelper.dp(12) })

        val nameView = TextView(context).apply {
            text = username.ifEmpty { displayName }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        card.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(8) })

        // Add friend
        card.addView(buildPrimaryButton(getString(R.string.qr_profile_add_friend)) {
            friendController.sendFriendRequest(username = username) { success ->
                val res = if (success) R.string.friends_toast_send_success else R.string.friends_toast_send_fail
                showToast(getString(res))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(44)
        ).apply { topMargin = LayoutHelper.dp(16) })

        // Message — open DM if userId is known, otherwise toast
        card.addView(buildPrimaryButton(getString(R.string.qr_profile_message), secondary = true) {
            openDm()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(44)
        ).apply { topMargin = LayoutHelper.dp(8) })

        // Dismiss
        card.addView(buildGhostButton(getString(R.string.qr_profile_decline)) {
            finishFragment()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(44)
        ).apply { topMargin = LayoutHelper.dp(8) })

        return root
    }

    // ── DM navigation ────────────────────────────────────────────────────────

    private fun openDm() {
        if (userId == 0L) {
            showToast(getString(R.string.feature_coming_soon))
            return
        }
        fragmentScope.launch {
            val dmChannelId = dialogsController.getOrCreateDm(userId)
            if (dmChannelId == 0L) {
                launch(Dispatchers.Main) {
                    showToast(getString(R.string.qr_dm_open_failed))
                }
                return@launch
            }
            chatController.openChannel(dmChannelId, 0L, CHANNEL_TYPE_DM)
            launch(Dispatchers.Main.immediate) {
                val chatName = displayName.ifBlank { username }
                (getParentActivity() as? MainActivity)?.openChat(
                    dmChannelId, chatName, 0L, CHANNEL_TYPE_DM
                )
                // Do NOT call finishFragment() here: openChat() pushes ChatFragment on top of the
                // actionBarLayout stack, so finishFragment() would close ChatFragment (the new top)
                // rather than QrProfileFragment. Let ChatFragment sit on top naturally.
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildPrimaryButton(text: String, secondary: Boolean = false, onClick: () -> Unit): View {
        val color     = if (secondary) themeColors.primaryContainer else themeColors.primary
        val textColor = if (secondary) themeColors.onPrimaryContainer else themeColors.onPrimary
        return TextView(requireContext()).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(textColor)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(color)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun buildGhostButton(text: String, onClick: () -> Unit): View {
        return TextView(requireContext()).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun showToast(msg: String) {
        val parent = getLayoutContainer() ?: (fragmentView as? android.view.ViewGroup) ?: return
        ToastOverlay(requireContext(), themeColors).show(parent, ToastOverlay.ToastType.INFO, msg)
    }
}
