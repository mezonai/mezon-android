package com.mezon.mobile.home.voice

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon

/** Pre-join UI only. The caller supplies meeting navigation and connection behavior. */
class ExternalMeetingFragment : BaseFragment() {
    enum class JoinRole { SPEAKER, AUDIENCE }

    companion object {
        private val JOIN_COLOR = 0xFF1FA45B.toInt()

        fun create(code: String = "") = ExternalMeetingFragment().apply {
            arguments = Bundle().apply { putString("meeting_code", code) }
        }
    }

    val meetingCode: String get() = arguments?.getString("meeting_code").orEmpty()
    var onJoin: ((JoinRole) -> Unit)? = null
    private var selectedRole = JoinRole.SPEAKER
    private var rolePopup: PopupWindow? = null

    override fun createView(context: Context): View {
        val user = entryPoint().userController()
        val content = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(MeasureSpec.getSize(widthMeasureSpec), LayoutHelper.dp(400))
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(40), LayoutHelper.dp(24), LayoutHelper.dp(40))
        }
        val name = user.displayName.ifBlank { user.username }
        content.addView(AvatarView(context).apply {
            setSizeDp(80)
            setRoundRadius(40f)
            setInfo(user.userId, name)
            setImageUrl(user.avatarUrl)
        }, LinearLayout.LayoutParams(LayoutHelper.dp(80), LayoutHelper.dp(80)))
        content.addView(label(context, name, 15f).apply {
            setTextColor(themeColors.onSurfaceVariant)
        }, contentParams(12))
        content.addView(label(context, context.getString(R.string.external_meeting_title), 23f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, contentParams(28))
        content.addView(label(context, context.getString(R.string.external_meeting_description), 15f).apply {
            setTextColor(themeColors.onSurfaceVariant)
            setLineSpacing(LayoutHelper.dp(4).toFloat(), 1f)
        }, contentParams(12))
        content.addView(buildJoinButton(context), contentParams(28))

        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(themeColors.background)
            addView(FrameLayout(context).apply {
                addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }, ViewGroup.LayoutParams(-1, -2))
        }
        return wrapWithActionBar(context.getString(R.string.external_meeting_title), scroll).also { fragmentView = it }
    }

    private fun buildJoinButton(context: Context): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(JOIN_COLOR, 26)
            clipToOutline = true
            minimumHeight = LayoutHelper.dp(52)
        }
        val join = label(context, context.getString(roleLabel(selectedRole)), 16f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(14), LayoutHelper.dp(16), LayoutHelper.dp(14))
            isFocusable = true
            applyVoiceButtonPressFeedback(pressedScale = 1f)
            setOnClickListener { onJoin?.invoke(selectedRole) }
        }
        row.addView(join, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(View(context).apply {
            setBackgroundColor(0x33000000)
        }, LinearLayout.LayoutParams(LayoutHelper.dp(1), -1))
        row.addView(FrameLayout(context).apply {
            minimumHeight = LayoutHelper.dp(52)
            contentDescription = context.getString(R.string.external_meeting_join_role)
            isFocusable = true
            applyVoiceButtonPressFeedback(pressedScale = 1f)
            addView(ImageView(context).apply {
                setImageDrawable(MezonIcon.chevronDownSmallIcon.getDrawable(context).mutate().apply {
                    setTint(Color.WHITE)
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18), Gravity.CENTER))
            setOnClickListener { showRoleMenu(row, join) }
        }, LinearLayout.LayoutParams(LayoutHelper.dp(52), -1))
        return row
    }

    private fun showRoleMenu(anchor: View, join: TextView) {
        rolePopup?.dismiss()
        val context = anchor.context
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(themeColors.surface, 12).apply {
                setStroke(LayoutHelper.dp(1), themeColors.outlineVariant)
            }
            clipToOutline = true
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4))
        }
        val popup = PopupWindow(menu, anchor.width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = LayoutHelper.dp(8).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        JoinRole.entries.forEach { role ->
            menu.addView(label(context, context.getString(roleLabel(role)), 15f).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minimumHeight = LayoutHelper.dp(48)
                setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(12))
                background = roundedBackground(Color.TRANSPARENT, 8)
                isFocusable = true
                applyVoiceButtonPressFeedback(pressedScale = 1f)
                setOnClickListener {
                    selectedRole = role
                    join.setText(roleLabel(role))
                    popup.dismiss()
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        popup.setOnDismissListener { rolePopup = null }
        rolePopup = popup
        popup.showAsDropDown(anchor, 0, LayoutHelper.dp(8), Gravity.START)
    }

    private fun roleLabel(role: JoinRole) = when (role) {
        JoinRole.SPEAKER -> R.string.external_meeting_join_speaker
        JoinRole.AUDIENCE -> R.string.external_meeting_join_audience
    }

    private fun label(context: Context, value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(themeColors.onSurface)
    }

    private fun contentParams(topMarginDp: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = LayoutHelper.dp(topMarginDp)
    }

    private fun roundedBackground(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = LayoutHelper.dp(radiusDp).toFloat()
        setColor(color)
    }

    override fun onPause() {
        rolePopup?.dismiss()
        super.onPause()
    }
}
