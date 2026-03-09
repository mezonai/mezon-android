package com.mezon.mobile.core

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

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
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setPositiveButton(positiveButton) { dialog, _ ->
            onPositive?.invoke()
            dialog.dismiss()
        }
        negativeButton?.let {
            builder.setNegativeButton(it) { dialog, _ ->
                onNegative?.invoke()
                dialog.dismiss()
            }
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
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setPositiveButton(confirmText) { dialog, _ ->
            onConfirm()
            dialog.dismiss()
        }
        builder.setNegativeButton(cancelText) { dialog, _ -> dialog.dismiss() }
        val dialog = builder.create()
        if (destructive) {
            dialog.setOnShowListener {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(0xFFD30E0E.toInt())
            }
        }
        return dialog
    }

    fun createListDialog(
        context: Context,
        title: String,
        items: Array<String>,
        onItemClick: (Int) -> Unit
    ): AlertDialog {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(title)
        builder.setItems(items) { dialog, which ->
            onItemClick(which)
            dialog.dismiss()
        }
        return builder.create()
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
        builder.setTitle(title)
        builder.setView(container)
        builder.setPositiveButton(positiveButton) { dialog, _ ->
            onPositive?.invoke()
            dialog.dismiss()
        }
        negativeButton?.let {
            builder.setNegativeButton(it) { dialog, _ -> dialog.dismiss() }
        }
        return builder.create()
    }
}
