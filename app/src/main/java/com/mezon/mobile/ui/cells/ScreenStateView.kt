package com.mezon.mobile.ui.cells

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ScreenStateView(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    private val loadingView: ProgressBar
    private val errorContainer: LinearLayout
    private val errorText: TextView
    private val retryButton: TextView
    private val emptyContainer: LinearLayout
    private val emptyIcon: ImageView
    private val emptyText: TextView
    private val emptyTextLp: LinearLayout.LayoutParams
    var onRetry: (() -> Unit)? = null

    init {
        loadingView = ProgressBar(context)
        addView(loadingView, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER
        ))

        errorContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        errorText = TextView(context).apply {
            setTextColor(theme.error)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        errorContainer.addView(errorText, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER
        ))
        retryButton = TextView(context).apply {
            text = "Retry"
            setTextColor(theme.primary)
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val pad = LayoutHelper.dp(12)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onRetry?.invoke() }
        }
        errorContainer.addView(retryButton, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER, 0f, 12f, 0f, 0f
        ))
        addView(errorContainer, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER
        ))

        emptyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        emptyIcon = ImageView(context).apply {
            visibility = View.GONE
        }
        emptyContainer.addView(emptyIcon, LayoutHelper.createLinear(100, 100, 0f, Gravity.CENTER_HORIZONTAL))
        emptyText = TextView(context).apply {
            setTextColor(theme.onSurfaceVariant)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        emptyTextLp = LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER
        )
        emptyContainer.addView(emptyText, emptyTextLp)
        addView(emptyContainer, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER
        ))
    }

    fun showLoading() {
        loadingView.visibility = View.VISIBLE
        errorContainer.visibility = View.GONE
        emptyContainer.visibility = View.GONE
    }

    fun showError(message: String) {
        loadingView.visibility = View.GONE
        errorContainer.visibility = View.VISIBLE
        emptyContainer.visibility = View.GONE
        errorText.text = message
    }

    fun showEmpty(message: String, emptyIconResId: Int = 0) {
        loadingView.visibility = View.GONE
        errorContainer.visibility = View.GONE
        emptyContainer.visibility = View.VISIBLE
        emptyText.text = message
        if (emptyIconResId != 0) {
            emptyIcon.setMezonIconResource(emptyIconResId)
            emptyIcon.visibility = View.VISIBLE
            emptyContainer.setPadding(0, LayoutHelper.dp(60f), 0, 0)
            emptyTextLp.topMargin = LayoutHelper.dp(10f)
            emptyText.layoutParams = emptyTextLp
            emptyText.setTextColor(theme.onSurface)
            emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            emptyText.setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
        } else {
            emptyIcon.visibility = View.GONE
            emptyContainer.setPadding(0, 0, 0, 0)
            emptyTextLp.topMargin = 0
            emptyText.layoutParams = emptyTextLp
            emptyText.setTextColor(theme.onSurfaceVariant)
            emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            emptyText.setPadding(0, 0, 0, 0)
        }
    }

    fun hide() {
        loadingView.visibility = View.GONE
        errorContainer.visibility = View.GONE
        emptyContainer.visibility = View.GONE
    }
}
