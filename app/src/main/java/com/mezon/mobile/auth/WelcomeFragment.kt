package com.mezon.mobile.auth

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.ActionButton

class WelcomeFragment : AuthFragment() {
    var onStartTapped: (() -> Unit)? = null

    override fun createView(context: Context): View {
        val root = AuthBackground(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(120), LayoutHelper.dp(24), LayoutHelper.dp(70))
        }
        root.addView(content, FrameLayout.LayoutParams(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT
        ))

        val title = TextView(context).apply {
            text = getString(R.string.common_login_welcome_to_mezon)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        content.addView(title, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 0f, 0f, 10f
        ))

        val subtitle = TextView(context).apply {
            text = getString(R.string.common_login_welcome_subtitle)
            textSize = 14f
            setTextColor(AuthStyle.linkText)
            gravity = Gravity.CENTER
        }
        content.addView(subtitle, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        val illustrationArea = FrameLayout(context)
        content.addView(illustrationArea, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f))
        val illustration = ImageView(context).apply {
            setImageResource(R.drawable.auth_welcome_mascot)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        illustrationArea.addView(illustration, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        illustrationArea.addOnLayoutChangeListener { area, _, _, _, _, _, _, _, _ ->
            val size = minOf((area.width * 0.78f).toInt(), area.height)
            val params = illustration.layoutParams as FrameLayout.LayoutParams
            if (size > 0 && (params.width != size || params.height != size)) {
                params.width = size
                params.height = size
                illustration.layoutParams = params
            }
        }

        val buttonArea = FrameLayout(context)
        content.addView(buttonArea, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50))
        val button = ActionButton(context, themeColors).apply {
            AuthStyle.styleButton(this)
            gradientStartColor = AuthStyle.welcomeButton
            gradientEndColor = AuthStyle.welcomeButton
            setText(getString(R.string.common_login_get_started))
            setOnClickListener { onStartTapped?.invoke() }
        }
        buttonArea.addView(button, FrameLayout.LayoutParams(
            minOf(LayoutHelper.dp(288), context.resources.displayMetrics.widthPixels - LayoutHelper.dp(48)),
            LayoutHelper.MATCH_PARENT, Gravity.CENTER
        ))
        return root
    }
}
