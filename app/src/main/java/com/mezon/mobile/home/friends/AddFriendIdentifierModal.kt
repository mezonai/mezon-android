package com.mezon.mobile.home.friends

import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class AddFriendIdentifierModal(
    context: android.content.Context,
    private val friendController: FriendController,
    private val currentUsername: String
) : Dialog(context) {

    private val themeColors = ThemeColors.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val root = FrameLayout(context).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { dismiss() }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(14f).toFloat()
                setColor(themeColors.background)
            }
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(16))
            isClickable = true
            isFocusable = true
        }
        root.addView(
            card,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 16f, 0f, 16f, 0f)
        )

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val title = TextView(context).apply {
            text = context.getString(R.string.friends_add_by_username)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(themeColors.onSurface)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        titleRow.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        val close = TextView(context).apply {
            text = "\u2715"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(themeColors.onSurfaceVariant)
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(4), LayoutHelper.dp(8), LayoutHelper.dp(4))
            setOnClickListener { dismiss() }
        }
        titleRow.addView(close, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        val label = TextView(context).apply {
            text = context.getString(R.string.friends_add_who_you_want)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
        }
        card.addView(
            label,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(12)
            }
        )

        val input = EditText(context).apply {
            hint = context.getString(R.string.friends_add_placeholder_username_only)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(themeColors.surfaceVariant)
            }
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(10))
            isSingleLine = true
        }
        card.addView(
            input,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(8)
            }
        )

        val helper = TextView(context).apply {
            text = context.getString(R.string.friends_add_by_the_way, currentUsername)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
        }
        card.addView(
            helper,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(10)
            }
        )

        val sendButton = TextView(context).apply {
            text = context.getString(R.string.friends_add_send_request)
            gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onPrimary)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(12f).toFloat()
                setColor(themeColors.onSurfaceVariant)
            }
            isEnabled = false
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            if (outValue.resourceId != 0) {
                foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
            }
            setPadding(0, LayoutHelper.dp(12), 0, LayoutHelper.dp(12))
        }
        card.addView(
            sendButton,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(16)
            }
        )

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val canSend = !s.isNullOrBlank()
                sendButton.isEnabled = canSend
                val color = if (canSend) themeColors.primary else themeColors.onSurfaceVariant
                (sendButton.background as GradientDrawable).setColor(color)
            }
        })

        sendButton.setOnClickListener {
            val usernameOrPhone = input.text?.toString()?.trim().orEmpty()
            if (usernameOrPhone.isEmpty()) return@setOnClickListener
            val relation = friendController.findFriendByUsername(usernameOrPhone)
            val normalizedCurrent = currentUsername.trim().lowercase()
            val normalizedInput = usernameOrPhone.lowercase()
            if (normalizedCurrent.isNotEmpty() && normalizedInput == normalizedCurrent) {
                Toast.makeText(context, R.string.friends_toast_send_fail, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (relation?.state == FRIEND_STATE_BLOCKED) {
                Toast.makeText(context, R.string.friends_toast_blocked_user, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (relation?.state == FRIEND_STATE_FRIEND) {
                Toast.makeText(context, R.string.friends_toast_already_friend, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (relation?.state == FRIEND_STATE_INVITE_SENT) {
                Toast.makeText(context, R.string.friends_toast_wait_accept, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            friendController.sendFriendRequest(username = usernameOrPhone) { success ->
                if (success) {
                    Toast.makeText(context, R.string.friends_toast_send_success, Toast.LENGTH_SHORT).show()
                    dismiss()
                } else {
                    Toast.makeText(context, R.string.friends_toast_send_fail, Toast.LENGTH_SHORT).show()
                }
            }
        }

        setContentView(root)
        setCanceledOnTouchOutside(true)
    }
}
