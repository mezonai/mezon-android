package com.mezon.mobile.core

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout

object AlertsCreator {

    fun createSimpleAlert(
        context: Context,
        title: String,
        message: String,
        positiveButton: String = "OK",
        negativeButton: String? = null,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null
    ): AlertDialog {
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButton) { _, _ -> onPositive?.invoke() }
        negativeButton?.let {
            builder.setNegativeButton(it) { _, _ -> onNegative?.invoke() }
        }
        return builder.create()
    }

    fun showSimpleAlert(
        context: Context,
        title: String,
        message: String,
        positiveButton: String = "OK",
        negativeButton: String? = null,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null
    ) {
        if (context is Activity && context.isFinishing) return
        createSimpleAlert(context, title, message, positiveButton, negativeButton, onPositive, onNegative).show()
    }

    fun createConfirmDialog(
        context: Context,
        title: String,
        message: String,
        confirmText: String = "Confirm",
        cancelText: String = "Cancel",
        destructive: Boolean = false,
        onConfirm: () -> Unit
    ): AlertDialog {
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirmText) { _, _ -> onConfirm() }
            .setNegativeButton(cancelText, null)
        val dialog = builder.create()
        if (destructive) {
            dialog.setDestructiveButton(0)
        }
        return dialog
    }

    fun createListDialog(
        context: Context,
        title: String,
        items: Array<String>,
        onItemClick: (Int) -> Unit
    ): AlertDialog {
        return AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items.map { it as CharSequence }.toTypedArray()) { _, which ->
                onItemClick(which)
            }
            .create()
    }

    fun createCustomDialog(
        context: Context,
        title: String,
        customView: android.view.View,
        positiveButton: String = "OK",
        negativeButton: String? = "Cancel",
        onPositive: (() -> Unit)? = null
    ): AlertDialog {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(24)
            setPadding(pad, LayoutHelper.dp(8), pad, 0)
            addView(customView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
            ))
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(positiveButton) { _, _ -> onPositive?.invoke() }
        negativeButton?.let {
            builder.setNegativeButton(it, null)
        }
        return builder.create()
    }
}
