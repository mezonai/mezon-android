package com.mezon.mobile.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.cells.MezonIcon
import kotlin.math.max

class OfflineNetworkBannerView(context: Context) : LinearLayout(context) {

    companion object {
        private val BG_COLOR = Color.argb((255 * 0.89f).toInt(), 63, 69, 75)
        private val H_PAD = LayoutHelper.dp(10f)
        private val V_PAD = LayoutHelper.dp(8f)
        private val ICON_WH = LayoutHelper.dp(18f)
        private val GAP = LayoutHelper.dp(8f)
        private val CORNER = LayoutHelper.dp(10f).toFloat()
        private val TOP_EXTRA = LayoutHelper.dp(8f)
        private val EDGE_PAD = LayoutHelper.dp(12f)
    }

    private val labelView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        background = GradientDrawable().apply {
            setColor(BG_COLOR)
            cornerRadius = CORNER
        }
        elevation = LayoutHelper.dp(5f).toFloat()

        val icon = ImageView(context).apply {
            layoutParams = LayoutParams(ICON_WH, ICON_WH)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val d = MezonIcon.noSignalIcon.getDrawable(context).mutate()
            d.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        addView(icon)

        labelView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = GAP
            }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            text = context.getString(R.string.common_poor_connection)
        }
        addView(labelView)

        setPadding(H_PAD, V_PAD, H_PAD, V_PAD)

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topInset = max(sys.top, cut.top)
            val trailInset = EDGE_PAD +
                if (ViewCompat.getLayoutDirection(v) == ViewCompat.LAYOUT_DIRECTION_RTL) {
                    max(sys.left, cut.left)
                } else {
                    max(sys.right, cut.right)
                }
            val lp = v.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.topMargin = topInset + TOP_EXTRA
                if (ViewCompat.getLayoutDirection(v) == ViewCompat.LAYOUT_DIRECTION_RTL) {
                    lp.leftMargin = trailInset
                    lp.rightMargin = 0
                } else {
                    lp.leftMargin = 0
                    lp.rightMargin = trailInset
                }
                v.layoutParams = lp
            }
            insets
        }
    }

    fun refreshLabel() {
        labelView.text = context.getString(R.string.common_poor_connection)
    }
}
