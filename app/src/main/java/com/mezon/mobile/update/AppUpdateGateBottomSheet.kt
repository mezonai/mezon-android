package com.mezon.mobile.update

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

class AppUpdateGateBottomSheet(
    context: android.content.Context,
    private val themeColors: ThemeColors,
    private val remoteVersion: String,
    private val storeUrl: String
) : BottomSheet(context) {

    private val purpleLightBg = 0xFFF3E8FF.toInt()

    private val blurple: Int
        get() = 0xFF5E65DE.toInt()

    init {
        setCanDismissWithSwipe(true)
        setCanDismissWithTouchOutside(true)
        setCancelable(true)
        setCustomView(buildContent())
    }

    private fun buildContent(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                LayoutHelper.dp(24),
                LayoutHelper.dp(40),
                LayoutHelper.dp(24),
                LayoutHelper.dp(40)
            )
        }

        val iconWrap = android.widget.FrameLayout(context).apply {
            val size = LayoutHelper.dp(80)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { bottomMargin = LayoutHelper.dp(24) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(purpleLightBg)
            }
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(
                MezonIcon.downloadIcon.getDrawable(context, blurple)
            )
        }
        iconWrap.addView(
            icon,
            android.widget.FrameLayout.LayoutParams(
                LayoutHelper.dp(40),
                LayoutHelper.dp(40),
                Gravity.CENTER
            )
        )
        root.addView(iconWrap)

        val title = TextView(context).apply {
            setTextColor(themeColors.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = typefaceWeight(700)
            text = context.getString(R.string.update_gate_out_of_date)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = LayoutHelper.dp(12) }
        )

        val description = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT
            text = context.getString(R.string.update_gate_update_experience)
            alpha = 0.6f
            gravity = Gravity.CENTER
            includeFontPadding = false
            val lineHeightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                22f,
                context.resources.displayMetrics
            ).toInt()
            TextViewCompat.setLineHeight(this, lineHeightPx)
        }
        root.addView(
            description,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = LayoutHelper.dp(32)
                marginStart = LayoutHelper.dp(16)
                marginEnd = LayoutHelper.dp(16)
            }
        )

        val button = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = typefaceWeight(600)
            text = context.getString(R.string.update_gate_update_now)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            includeFontPadding = false
            val fill = GradientDrawable().apply {
                setColor(blurple)
                cornerRadius = LayoutHelper.dp(100).toFloat()
            }
            val mask = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = LayoutHelper.dp(100).toFloat()
            }
            background = RippleDrawable(
                ColorStateList.valueOf(0x40FFFFFF),
                fill,
                mask
            )
            setOnClickListener { openStore() }
            setPadding(
                LayoutHelper.dp(32),
                LayoutHelper.dp(16),
                LayoutHelper.dp(32),
                LayoutHelper.dp(16)
            )
        }
        root.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = LayoutHelper.dp(16) }
        )

        val versionLine = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT
            letterSpacing = 0.5f
            alpha = 0.4f
            gravity = Gravity.CENTER
            includeFontPadding = false
            text = context.getString(R.string.update_gate_version_info) + " " + remoteVersion
        }
        root.addView(
            versionLine,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        return root
    }

    private fun openStore() {
        if (storeUrl.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl))
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun typefaceWeight(weight: Int): Typeface {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(Typeface.SANS_SERIF, weight, false)
        }
        return when {
            weight >= 700 -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL) ?: Typeface.DEFAULT_BOLD
            else -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }
}
