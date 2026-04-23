package com.mezon.mobile.home.qr

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.ToastOverlay

class QrProfileFragment : BaseFragment() {

    companion object {
        private const val ARG_DISPLAY_NAME = "displayName"
        private const val ARG_USERNAME = "username"
        private const val ARG_AVATAR_URL = "avatarUrl"

        fun newInstance(username: String, displayName: String, avatarUrl: String?): QrProfileFragment {
            return QrProfileFragment().apply {
                arguments = android.os.Bundle().apply {
                    putString(ARG_USERNAME, username)
                    putString(ARG_DISPLAY_NAME, displayName)
                    putString(ARG_AVATAR_URL, avatarUrl)
                }
            }
        }
    }

    private var displayName: String = ""
    private var username: String = ""
    private var avatarUrl: String? = null
    private lateinit var accountController: AccountController

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        val args = arguments
        displayName = args?.getString(ARG_DISPLAY_NAME).orEmpty()
        username = args?.getString(ARG_USERNAME).orEmpty()
        avatarUrl = args?.getString(ARG_AVATAR_URL)
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
            if (!avatarUrl.isNullOrBlank()) {
                setImageUrl(avatarUrl)
            }
        }
        card.addView(avatar, LinearLayout.LayoutParams(
            LayoutHelper.dp(64),
            LayoutHelper.dp(64)
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

        card.addView(buildPrimaryButton(
            getString(R.string.qr_profile_add_friend)
        ) {
            accountController.sendFriendRequest(username = username) { success ->
                val res = if (success) R.string.friends_toast_send_success else R.string.friends_toast_send_fail
                showToast(getString(res))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(44)
        ).apply { topMargin = LayoutHelper.dp(16) })

        card.addView(buildPrimaryButton(
            getString(R.string.qr_profile_message),
            secondary = true
        ) {
            showToast(getString(R.string.feature_coming_soon))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(44)
        ).apply { topMargin = LayoutHelper.dp(8) })

        card.addView(buildGhostButton(
            getString(R.string.qr_profile_decline)
        ) {
            finishFragment()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(44)
        ).apply { topMargin = LayoutHelper.dp(8) })

        return root
    }

    private fun buildPrimaryButton(text: String, secondary: Boolean = false, onClick: () -> Unit): View {
        val color = if (secondary) themeColors.primaryContainer else themeColors.primary
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
