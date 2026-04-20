package com.mezon.mobile.qr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.cells.AvatarView
import org.json.JSONObject

class QrProfileDetailFragment : BaseFragment() {

    companion object {
        private const val ARG_USERNAME = "username"
        private const val ARG_DATA = "data"

        fun newInstance(username: String, data: String?): QrProfileDetailFragment {
            return QrProfileDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_USERNAME, username)
                    putString(ARG_DATA, data)
                }
            }
        }
    }

    private lateinit var userController: UserController

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
    }

    override fun createView(context: Context): View {
        val username = arguments?.getString(ARG_USERNAME) ?: ""
        val encodedData = arguments?.getString(ARG_DATA)

        // Decode profile data
        var profileId = ""
        var profileAvatar = ""
        var profileName = username

        if (!encodedData.isNullOrEmpty()) {
            try {
                val decoded = String(Base64.decode(encodedData, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
                val decoded2 = Uri.decode(decoded)
                val json = JSONObject(decoded2)
                profileId = json.optString("id", "")
                profileAvatar = json.optString("avatar", "")
                profileName = json.optString("name", username)
            } catch (_: Exception) {}
        }

        if (username.isEmpty() && profileName.isEmpty()) {
            return buildNotFoundView(context)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(themeColors.background)
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(32), LayoutHelper.dp(24), LayoutHelper.dp(32))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16f).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(24), LayoutHelper.dp(20), LayoutHelper.dp(20))
        }

        val avatarView = AvatarView(context).apply {
            setSizeDp(80)
            setRoundRadius(16f)
        }
        val finalId = profileId.toLongOrNull() ?: 0L
        avatarView.setInfo(finalId, profileName)
        if (profileAvatar.isNotEmpty()) avatarView.setImageUrl(profileAvatar)
        card.addView(avatarView, LinearLayout.LayoutParams(LayoutHelper.dp(80), LayoutHelper.dp(80)))

        val nameView = TextView(context).apply {
            text = profileName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(16), 0, 0)
        }
        card.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val usernameView = TextView(context).apply {
            text = "@$username"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFF666666.toInt())
            gravity = Gravity.CENTER
        }
        card.addView(usernameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(4) })

        card.addView(buildActionButton(context, R.string.profile_add_friend, 0xFF5865F2.toInt()) {
            onAddFriend(profileId, username)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(44)).apply {
            topMargin = LayoutHelper.dp(16)
        })

        card.addView(buildActionButton(context, R.string.profile_message, 0xFF5865F2.toInt()) {
            onOpenChat(profileId, username)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(44)).apply {
            topMargin = LayoutHelper.dp(10)
        })

        card.addView(buildActionButton(context, R.string.qr_no_thanks, 0xFF6B7280.toInt()) {
            finishFragment()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(40)).apply {
            topMargin = LayoutHelper.dp(10)
        })

        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return wrapWithActionBar(context.getString(R.string.profile_title), root)
    }

    private fun buildActionButton(
        context: Context,
        textRes: Int,
        bgColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = context.getString(textRes)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(bgColor)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun onAddFriend(profileId: String, username: String) {
        // TODO: hook add-friend flow (API + UI). Provide screen/route name to wire.
        val ctx = requireContext()
        showToast(ctx, ctx.getString(R.string.profile_add_friend))
    }

    private fun onOpenChat(profileId: String, username: String) {
        // TODO: hook open chat flow (DM). Provide screen/route name to wire.
        val ctx = requireContext()
        showToast(ctx, ctx.getString(R.string.profile_message))
    }

    private fun showToast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun buildNotFoundView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }
        val text = TextView(context).apply {
            text = context.getString(R.string.qr_user_not_found)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(themeColors.onSurfaceVariant)
            gravity = Gravity.CENTER
        }
        root.addView(text, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        return wrapWithActionBar(context.getString(R.string.profile_title), root)
    }
}
