package com.mezon.mobile.core

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.mezon.mobile.ui.cells.ActionBarView

class ThemeDescription @JvmOverloads constructor(
    private val viewToInvalidate: View? = null,
    private val changeFlags: Int = 0,
    private val listClasses: Array<Class<*>>? = null,
    private val paintToUpdate: Array<Paint>? = null,
    private val drawablesToUpdate: Array<Drawable>? = null,
    private val delegate: ThemeDescriptionDelegate? = null,
    private val currentKey: Int = 0,
    private val resourcesProvider: ThemeColors.ResourcesProvider? = null
) {

    interface ThemeDescriptionDelegate {
        fun didSetColor()
        fun onAnimationProgress(progress: Float) {}
    }

    private var previousColor = 0
    private var animationColor = 0
    private var tag: Any? = null

    fun setTag(t: Any?) {
        tag = t
    }

    fun getTag(): Any? = tag

    fun setColor(color: Int, useDefault: Boolean) {
        setColor(color, useDefault, true)
    }

    fun setColor(color: Int, useDefault: Boolean, save: Boolean = false) {
        if (save) {
            ThemeColors.instance.setColor(currentKey, color)
        }

        paintToUpdate?.forEach { paint ->
            paint.color = color
            if (paint is android.text.TextPaint) {
                paint.linkColor = color
            }
        }

        drawablesToUpdate?.forEach { drawable ->
            applyColorToDrawable(drawable, color)
        }

        viewToInvalidate?.let { view ->
            applyColorToView(view, color)
        }

        if (listClasses != null && viewToInvalidate is ViewGroup) {
            applyToListChildren(viewToInvalidate, color)
        }

        delegate?.didSetColor()
    }

    private fun applyColorToView(view: View, color: Int) {
        if (changeFlags and FLAG_BACKGROUND != 0) {
            view.setBackgroundColor(color)
        }
        if (changeFlags and FLAG_CELLBACKGROUNDCOLOR != 0) {
            view.setBackgroundColor(color)
        }
        if (changeFlags and FLAG_TEXTCOLOR != 0 && view is TextView) {
            view.setTextColor(color)
        }
        if (changeFlags and FLAG_HINTTEXTCOLOR != 0 && view is TextView) {
            view.setHintTextColor(color)
        }
        if (changeFlags and FLAG_LINKCOLOR != 0 && view is TextView) {
            view.setLinkTextColor(color)
        }
        if (changeFlags and FLAG_IMAGECOLOR != 0 && view is ImageView) {
            view.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        if (changeFlags and FLAG_BACKGROUNDFILTER != 0 && view is ImageView) {
            val bg = view.background
            bg?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        if (changeFlags and FLAG_CURSORCOLOR != 0 && view is EditText) {
            try {
                view.highlightColor = color and 0x40FFFFFF
            } catch (_: Exception) {}
        }
        if (changeFlags and FLAG_AB_TITLECOLOR != 0 && view is ActionBarView) {
            view.setTitleColor(color)
        }
        if (changeFlags and FLAG_AB_SUBTITLECOLOR != 0 && view is ActionBarView) {
            view.setSubtitleColor(color)
        }
        if (changeFlags and FLAG_AB_ITEMSCOLOR != 0 && view is ActionBarView) {
            view.setItemsColor(color)
        }
        if (changeFlags and FLAG_AB_SELECTORCOLOR != 0 && view is ActionBarView) {
            view.setItemsBackgroundColor(color)
        }
        if (changeFlags and FLAG_SELECTOR != 0 && view is RecyclerListView) {
            view.setSelectorDrawableColor(color)
        }

        view.invalidate()
    }

    private fun applyColorToDrawable(drawable: Drawable, color: Int) {
        when (drawable) {
            is ShapeDrawable -> drawable.paint.color = color
            is GradientDrawable -> drawable.setColor(color)
            else -> drawable.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun applyToListChildren(viewGroup: ViewGroup, color: Int) {
        if (listClasses == null) return
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            for (cls in listClasses) {
                if (cls.isInstance(child)) {
                    applyColorToView(child, color)
                    break
                }
            }
            if (child is ViewGroup) {
                applyToListChildren(child, color)
            }
        }
    }

    fun startEditing() {
        previousColor = getSetColor()
    }

    fun getCurrentColor(): Int = getSetColor()

    fun getSetColor(): Int {
        return resourcesProvider?.getColor(currentKey) ?: ThemeColors.instance.getColor(currentKey)
    }

    fun getCurrentKey(): Int = currentKey

    fun setAnimatedColor(color: Int) {
        animationColor = color
        setColor(color, false)
    }

    fun setDefaultColor() {
        setColor(ThemeColors.instance.getDefaultColor(currentKey), true)
    }

    fun setPreviousColor() {
        setColor(previousColor, false)
    }

    fun apply() {
        setColor(getSetColor(), false, false)
    }

    fun getTitle(): String {
        return when (currentKey) {
            ThemeColors.key_windowBackgroundWhite -> "windowBackgroundWhite"
            ThemeColors.key_windowBackgroundGray -> "windowBackgroundGray"
            ThemeColors.key_divider -> "divider"
            ThemeColors.key_actionBarDefault -> "actionBarDefault"
            ThemeColors.key_actionBarDefaultIcon -> "actionBarDefaultIcon"
            ThemeColors.key_actionBarDefaultTitle -> "actionBarDefaultTitle"
            ThemeColors.key_actionBarDefaultSubtitle -> "actionBarDefaultSubtitle"
            ThemeColors.key_chats_name -> "chats_name"
            ThemeColors.key_chats_message -> "chats_message"
            ThemeColors.key_chat_inBubble -> "chat_inBubble"
            ThemeColors.key_chat_outBubble -> "chat_outBubble"
            else -> "key_$currentKey"
        }
    }

    companion object {
        const val FLAG_BACKGROUND = 0x001
        const val FLAG_TEXTCOLOR = 0x002
        const val FLAG_IMAGECOLOR = 0x004
        const val FLAG_CELLBACKGROUNDCOLOR = 0x008
        const val FLAG_HINTTEXTCOLOR = 0x010
        const val FLAG_SELECTOR = 0x020
        const val FLAG_LINKCOLOR = 0x040
        const val FLAG_BACKGROUNDFILTER = 0x080
        const val FLAG_AB_TITLECOLOR = 0x100
        const val FLAG_AB_SUBTITLECOLOR = 0x200
        const val FLAG_AB_ITEMSCOLOR = 0x400
        const val FLAG_AB_SELECTORCOLOR = 0x800
        const val FLAG_CHECKTAG = 0x1000
        const val FLAG_PROGRESSBAR = 0x2000
        const val FLAG_CURSORCOLOR = 0x4000
        const val FLAG_CHECKBOX = 0x8000
        const val FLAG_CHECKBOXCHECK = 0x10000
        const val FLAG_FASTSCROLL = 0x20000
        const val FLAG_IMAGEVIEW = 0x40000
        const val FLAG_SERVICEBACKGROUND = 0x80000
        const val FLAG_AB_SEARCH = 0x100000

        fun listOf(vararg descriptions: ThemeDescription): ArrayList<ThemeDescription> {
            return ArrayList(descriptions.toList())
        }
    }
}
