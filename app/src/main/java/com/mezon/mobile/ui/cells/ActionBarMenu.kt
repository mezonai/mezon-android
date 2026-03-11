package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.mezon.mobile.core.LayoutHelper

class ActionBarMenu(context: Context, val parentActionBar: ActionBarView) : LinearLayout(context) {

    private val items = LinkedHashMap<Int, ActionBarMenuItem>()
    var isActionMode = false
    private var onLayoutListener: Runnable? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }

    fun addItem(id: Int, icon: Int): ActionBarMenuItem {
        return addItem(id, icon, 0)
    }

    fun addItem(id: Int, icon: Int, backgroundColor: Int): ActionBarMenuItem {
        val item = ActionBarMenuItem(context, this, backgroundColor)
        item.tag = id
        if (icon != 0) {
            item.setIcon(icon)
        }
        addView(item, LayoutHelper.createLinear(48, 48))
        items[id] = item
        item.setOnClickListener {
            if (item.hasSubMenu()) {
                item.toggleSubMenu()
            } else if (!item.isSearchField || !item.overrideMenuClick) {
                parentActionBar.onItemClick(id)
            }
        }
        return item
    }

    fun addItem(id: Int, drawable: Drawable?): ActionBarMenuItem {
        val item = ActionBarMenuItem(context, this, 0)
        item.tag = id
        if (drawable != null) {
            item.setIcon(drawable)
        }
        addView(item, LayoutHelper.createLinear(48, 48))
        items[id] = item
        item.setOnClickListener {
            if (item.hasSubMenu()) {
                item.toggleSubMenu()
            } else {
                parentActionBar.onItemClick(id)
            }
        }
        return item
    }

    fun addItem(id: Int, text: CharSequence): ActionBarMenuItem {
        val item = ActionBarMenuItem(context, this, 0)
        item.tag = id
        addView(item, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48))
        items[id] = item
        item.setOnClickListener { parentActionBar.onItemClick(id) }
        return item
    }

    fun addItemWithWidth(id: Int, icon: Int, width: Int): ActionBarMenuItem {
        val item = ActionBarMenuItem(context, this, 0)
        item.tag = id
        if (icon != 0) {
            item.setIcon(icon)
        }
        addView(item, LayoutHelper.createLinear(width, 48))
        items[id] = item
        item.setOnClickListener {
            if (item.hasSubMenu()) {
                item.toggleSubMenu()
            } else {
                parentActionBar.onItemClick(id)
            }
        }
        return item
    }

    fun getItem(id: Int): ActionBarMenuItem? = items[id]

    fun removeItem(id: Int) {
        val item = items.remove(id) ?: return
        removeView(item)
    }

    fun hideAllPopupMenus() {
        for ((_, item) in items) {
            item.closeSubMenu()
        }
    }

    fun setItemColor(color: Int) {
        for ((_, item) in items) {
            item.setIconColor(color)
        }
    }

    fun setPopupItemsColor(color: Int, icon: Boolean = false) {
        for ((_, item) in items) {
            item.setPopupItemsColor(color, icon)
        }
    }

    fun setPopupItemsSelectorColor(color: Int) {
        for ((_, item) in items) {
            item.setPopupItemsSelectorColor(color)
        }
    }

    fun setPopupBackgroundColor(color: Int) {
        for ((_, item) in items) {
            item.redrawPopup(color)
        }
    }

    fun setItemVisibility(id: Int, visibility: Int) {
        val item = items[id] ?: return
        item.visibility = visibility
    }

    fun setOnLayoutListener(listener: Runnable?) {
        onLayoutListener = listener
    }

    fun onMenuButtonPressed() {
        val lastItem = items.values.lastOrNull { it.hasSubMenu() && it.visibility == VISIBLE }
        lastItem?.toggleSubMenu()
    }

    fun getItemsMeasuredWidth(): Int {
        var width = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != GONE) {
                width += child.measuredWidth
            }
        }
        return width
    }

    fun getVisibleItemsMeasuredWidth(): Int {
        var width = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == VISIBLE) {
                width += child.measuredWidth
            }
        }
        return width
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        onLayoutListener?.run()
    }

    override fun hasOverlappingRendering(): Boolean = false

    fun clearItems() {
        items.clear()
        removeAllViews()
    }

    fun setAlphaForAll(alpha: Float) {
        for (i in 0 until childCount) {
            getChildAt(i).alpha = alpha
        }
    }

    fun getItemsCount(): Int = items.size

    fun getItemAt(index: Int): View? = if (index in 0 until childCount) getChildAt(index) else null

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        for (i in 0 until childCount) {
            getChildAt(i).isEnabled = enabled
        }
    }
}
