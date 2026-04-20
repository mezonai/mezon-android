package com.mezon.mobile.home.qr

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView

/**
 * Shown when a Profile QR code is scanned.
 * Design matches image:
 *   - "HỒ SƠ NGƯỜI DÙNG" title (small, gray, uppercase)
 *   - Circular avatar, centered
 *   - Username/display name, centered, bold
 *   - "Thêm bạn" pill button (primary blue)
 *   - "Tin nhắn" pill button (primary blue)
 *   - "Không, cảm ơn" pill button (gray / dismiss)
 */
class QrProfileResultDialog(
    context: Context,
    private val userId: Long,
    private val displayName: String,
    private val username: String,
    private val avatarUrl: String?,
    private val onAddFriend: () -> Unit,
    private val onMessage: () -> Unit
) : BottomSheet(context) {

    private val theme = ThemeColors.instance

    // Blue button color — matches image (#5B5FEF / indigo-blue)
    private val btnPrimaryColor = 0xFF5B5FEF.toInt()
    // Gray dismiss button
    private val btnGrayColor = 0xFF8E8E93.toInt()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                LayoutHelper.dp(20), LayoutHelper.dp(24),
                LayoutHelper.dp(20), LayoutHelper.dp(24)
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        // ── Section title: "HỒ SƠ NGƯỜI DÙNG" ────────────────────────────────
        val sectionTitle = TextView(context).apply {
            text = context.getString(R.string.qr_profile_section_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF8E8E93.toInt())
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
        }
        root.addView(sectionTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = LayoutHelper.dp(20) })

        // ── Circular avatar, centered ─────────────────────────────────────────
        val avatarSize = LayoutHelper.dp(80)
        val avatar = AvatarView(context).apply {
            setSizeDp(80)
            setRoundRadius(999f)   // full circle
            setInfo(userId, displayName)
            if (!avatarUrl.isNullOrEmpty()) setImageUrl(avatarUrl)
        }
        root.addView(avatar, LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = LayoutHelper.dp(12)
        })

        // ── Username, centered, bold ──────────────────────────────────────────
        val nameView = TextView(context).apply {
            text = username.ifEmpty { displayName }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(theme.onSurface)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = LayoutHelper.dp(24) })

        // ── "Thêm bạn" button ─────────────────────────────────────────────────
        root.addView(buildPillButton(
            label = context.getString(R.string.common_add_friend),
            bgColor = btnPrimaryColor,
            textColor = Color.WHITE
        ) {
            dismiss()
            onAddFriend()
        }, pillParams())

        // ── "Tin nhắn" button ─────────────────────────────────────────────────
        root.addView(buildPillButton(
            label = context.getString(R.string.common_send_message),
            bgColor = btnPrimaryColor,
            textColor = Color.WHITE
        ) {
            dismiss()
            onMessage()
        }, pillParams().apply { topMargin = LayoutHelper.dp(10) })

        // ── "Không, cảm ơn" button ────────────────────────────────────────────
        root.addView(buildPillButton(
            label = context.getString(R.string.qr_dismiss_btn),
            bgColor = btnGrayColor,
            textColor = Color.WHITE
        ) {
            dismiss()
        }, pillParams().apply { topMargin = LayoutHelper.dp(10) })

        setCustomView(root)
        super.onCreate(savedInstanceState)
        fixNavigationBar()
    }

    private fun buildPillButton(
        label: String,
        bgColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            this.setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(50f).toFloat()
                setColor(bgColor)
            }
            setPadding(
                LayoutHelper.dp(16), LayoutHelper.dp(14),
                LayoutHelper.dp(16), LayoutHelper.dp(14)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun pillParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(50)
        )
    }
}
