package com.mezon.mobile.ui.cells

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
    private val emptyText: TextView
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
        emptyText = TextView(context).apply {
            setTextColor(theme.onSurfaceVariant)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        emptyContainer.addView(emptyText, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER
        ))
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

    fun showEmpty(message: String) {
        loadingView.visibility = View.GONE
        errorContainer.visibility = View.GONE
        emptyContainer.visibility = View.VISIBLE
        emptyText.text = message
    }

    fun hide() {
        loadingView.visibility = View.GONE
        errorContainer.visibility = View.GONE
        emptyContainer.visibility = View.GONE
    }
}
