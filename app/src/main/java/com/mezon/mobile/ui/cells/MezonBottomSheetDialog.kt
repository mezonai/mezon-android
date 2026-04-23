package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

object MezonBottomSheetDialog {

    fun create(
        context: Context,
        theme: ThemeColors,
        title: String? = null,
        scrollable: Boolean = true,
        contentBuilder: (LinearLayout) -> Unit
    ): Dialog {
        val dialog = Dialog(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
        }

        val handle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(theme.onSurface and 0x4DFFFFFF)
                cornerRadius = LayoutHelper.dpf(2f)
            }
        }
        val handleContainer = FrameLayout(context).apply {
            addView(handle, LayoutHelper.createFrame(40, 4, Gravity.CENTER, topMargin = 8f, bottomMargin = 8f))
        }
        root.addView(handleContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        if (title != null) {
            val titleView = TextView(context).apply {
                text = title
                setTextColor(theme.onSurface)
                textSize = 16f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            root.addView(titleView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                0f, Gravity.CENTER, 16f, 8f, 16f, 8f
            ))
        }

        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, 0, pad, pad)
        }

        contentBuilder(contentContainer)

        if (scrollable) {
            val scrollView = NestedScrollView(context).apply {
                addView(contentContainer, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        } else {
            root.addView(contentContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }
}
