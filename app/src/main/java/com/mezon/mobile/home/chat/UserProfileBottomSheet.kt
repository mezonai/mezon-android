package com.mezon.mobile.home.chat

import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.theme.ThemeMode


class UserProfileBottomSheet(
    context: android.content.Context,
    private val userId: Long,
    private val displayName: String,
    private val username: String,
    private val avatarUrl: String?,
    private val aboutMe: String? = null,
    private val memberSince: String? = null,
    private val isOwnProfile: Boolean = false,
    private val isDM: Boolean = false,
    private val listener: UserProfileListener? = null
) : BottomSheet(context) {

    interface UserProfileListener {
        fun onSendMessage(userId: Long) {}
        fun onVoiceCall(userId: Long) {}
        fun onAddFriend(userId: Long) {}
    }

    private val theme: ThemeColors = ThemeColors.instance

    // RN color mappings
    private val primaryColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFFF4F4F8.toInt()
            ThemeMode.DARK -> 0xFF121218.toInt()
            ThemeMode.ABYSS -> 0xFF110B33.toInt()
            else -> 0xFF121218.toInt()
        }
    private val secondaryColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFFFFFFFF.toInt()
            ThemeMode.DARK -> 0xFF1C1D23.toInt()
            ThemeMode.ABYSS -> 0xFF19153C.toInt()
            else -> 0xFF1C1D23.toInt()
        }
    private val textStrongColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFF070709.toInt()
            ThemeMode.DARK -> 0xFFDFE0E4.toInt()
            ThemeMode.ABYSS -> 0xFFDFE0E4.toInt()
            else -> 0xFFDFE0E4.toInt()
        }
    private val textColor: Int
        get() = when (theme.resolvedMode) {
            ThemeMode.LIGHT -> 0xFF29292B.toInt()
            ThemeMode.DARK -> 0xFFCCCCCC.toInt()
            ThemeMode.ABYSS -> 0xFFCCCCCC.toInt()
            else -> 0xFFCCCCCC.toInt()
        }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false      // Allow avatar to overflow below backdrop
            clipToPadding = false
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false      // Allow avatar to overflow below backdrop
            clipToPadding = false
        }

        // 1. Colored backdrop with avatar
        rootLayout.addView(buildBackdropWithAvatar())

        // 2. User info card (name + username + action buttons)
        rootLayout.addView(buildUserInfoCard(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = LayoutHelper.dp(14)
            rightMargin = LayoutHelper.dp(14)
            topMargin = LayoutHelper.dp(30)  // Space for avatar overflow
            bottomMargin = LayoutHelper.dp(12)
        })

        // 3. Details card (member since, about me)
        val detailsCard = buildDetailsCard()
        if (detailsCard != null) {
            rootLayout.addView(detailsCard, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = LayoutHelper.dp(14)
                rightMargin = LayoutHelper.dp(14)
                bottomMargin = LayoutHelper.dp(20)
            })
        }

        scrollView.addView(rootLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setCustomView(scrollView)
        super.onCreate(savedInstanceState)

        // Disable clipping on BottomSheet's internal layout hierarchy
        // so the avatar can overflow below the backdrop
        contentLayout?.clipChildren = false
        contentLayout?.clipToPadding = false
        containerView?.clipChildren = false
        containerView?.clipToPadding = false
    }

    // ─── Backdrop + Avatar ────────────────────────────────────────────

    private fun buildBackdropWithAvatar(): FrameLayout {
        val container = FrameLayout(context)

        // Colored backdrop — RN: height: 120, backgroundColor: color from image
        val backdrop = View(context).apply {
            setBackgroundColor(0xFF808080.toInt())  // Gray fallback — could extract from avatar
        }
        container.addView(backdrop, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(120)
        ))

        // Avatar — RN: 80×80, positioned at bottom: -25%, paddingLeft: 14
        val avatarSize = LayoutHelper.dp(80)
        val avatarDrawable = AvatarDrawable().apply {
            setInfo(displayName)
        }
        val avatarView = ImageView(context).apply {
            setImageDrawable(avatarDrawable)
            scaleType = ImageView.ScaleType.FIT_CENTER

            // White circular border
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(primaryColor)
                setStroke(LayoutHelper.dp(4), primaryColor)
            }
            setPadding(LayoutHelper.dp(3), LayoutHelper.dp(3), LayoutHelper.dp(3), LayoutHelper.dp(3))
        }
        container.addView(avatarView, FrameLayout.LayoutParams(
            avatarSize, avatarSize, Gravity.START or Gravity.BOTTOM
        ).apply {
            leftMargin = LayoutHelper.dp(14)
            bottomMargin = -LayoutHelper.dp(30)  // Overflow below backdrop
        })

        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(120) + LayoutHelper.dp(0)  // Just backdrop height
        )
        container.clipChildren = false

        return container
    }

    // ─── User Info Card ───────────────────────────────────────────────

    private fun buildUserInfoCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(secondaryColor)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16),
                LayoutHelper.dp(16), LayoutHelper.dp(16))
        }

        // Display name — RN: textStrong, h6=14sp, fontWeight 600
        val nameView = TextView(context).apply {
            text = displayName
            setTextColor(textStrongColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)  // RN: h6 = verticalScale(14)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = LayoutHelper.dp(2) })

        // Username — RN: text color, medium=14sp
        val usernameView = TextView(context).apply {
            text = "@$username"
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        }
        card.addView(usernameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        if (!isOwnProfile) {
            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            actionsRow.addView(buildActionButton(
                context.getString(R.string.user_profile_send_message),
                R.drawable.ic_chat_icon,
                textColor
            ) {
                dismiss()
                listener?.onSendMessage(userId)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = LayoutHelper.dp(14) })

            // Voice Call button
            actionsRow.addView(buildActionButton(
                context.getString(R.string.user_profile_voice_call),
                R.drawable.ic_phone_call_icon,
                textColor
            ) {
                dismiss()
                listener?.onVoiceCall(userId)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = LayoutHelper.dp(14) })

            // Add Friend button
            actionsRow.addView(buildActionButton(
                context.getString(R.string.user_profile_add_friend),
                R.drawable.ic_userplus,
                0xFF42A869.toInt()  // baseColor.green
            ) {
                dismiss()
                listener?.onAddFriend(userId)
            })

            card.addView(actionsRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = LayoutHelper.dp(20) })
        }

        return card
    }


    private fun buildActionButton(
        label: String,
        iconRes: Int,
        labelColor: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val button = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(primaryColor)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10),
                LayoutHelper.dp(10), LayoutHelper.dp(10))
            minimumWidth = LayoutHelper.dp(80)
            isClickable = true
            isFocusable = true
            // Ripple
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = context.getDrawable(outValue.resourceId)
            setOnClickListener { onClick() }
        }

        val icon = ImageView(context).apply {
            try { setImageResource(iconRes) } catch (_: Exception) {}
            setColorFilter(labelColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        button.addView(icon, LinearLayout.LayoutParams(
            LayoutHelper.dp(24), LayoutHelper.dp(24)
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = LayoutHelper.dp(6)
        })

        val text = TextView(context).apply {
            this.text = label
            setTextColor(labelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)  
            gravity = Gravity.CENTER
            maxLines = 1
        }
        button.addView(text, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        return button
    }


    private fun buildDetailsCard(): LinearLayout? {
        val hasMemberSince = !memberSince.isNullOrEmpty()
        val hasAboutMe = !aboutMe.isNullOrEmpty()
        if (!hasMemberSince && !hasAboutMe) return null

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(secondaryColor)
                cornerRadius = LayoutHelper.dp(8).toFloat()
            }
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(20),
                LayoutHelper.dp(20), LayoutHelper.dp(20))
        }

        if (hasMemberSince) {
            val titleView = TextView(context).apply {
                text = context.getString(R.string.user_profile_member_since)
                setTextColor(textStrongColor)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f) 
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            card.addView(titleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = LayoutHelper.dp(10) }) 

            val valueView = TextView(context).apply {
                text = memberSince
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            }
            card.addView(valueView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = if (hasAboutMe) LayoutHelper.dp(16) else 0 })
        }

        if (hasAboutMe) {
            val aboutTitle = TextView(context).apply {
                text = context.getString(R.string.user_profile_about_me)
                setTextColor(textStrongColor)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)  
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            card.addView(aboutTitle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = LayoutHelper.dp(10) }) 

            val aboutText = TextView(context).apply {
                text = aboutMe
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTypeface(typeface, android.graphics.Typeface.ITALIC)
            }
            card.addView(aboutText)
        }

        return card
    }
}
