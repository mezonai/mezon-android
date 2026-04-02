package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors

class ActionBarMenuItem(
    context: Context,
    private val parentMenu: ActionBarMenu,
    backgroundColor: Int
) : FrameLayout(context) {

    interface ActionBarMenuItemSearchListener {
        fun onSearchExpand() {}
        fun onSearchCollapse(): Boolean = true
        fun onTextChanged(text: EditText?) {}
        fun onSearchPressed(editText: EditText?) {}
        fun onCaptionCleared() {}
    }

    val iconView: ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
    }

    private var iconColor = 0
    private var subMenuOpenSide = 0
    private var popupItemsColor = 0
    private var popupItemsSelectorColor = 0
    var overrideMenuClick = false
    private var menuYOffset = 0
    private var menuXOffset = 0

    private var popupWindow: PopupWindow? = null
    private var popupLayout: LinearLayout? = null
    private val subItems = ArrayList<View>()

    var isSearchField = false
        private set
    private var searchContainer: FrameLayout? = null
    private var searchField: EditTextBoldCursor? = null
    private var clearButton: ImageView? = null
    private var searchListener: ActionBarMenuItemSearchListener? = null
    private var searchFieldHint: CharSequence? = null

    init {
        addView(iconView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
            Gravity.CENTER
        ))
        foreground = parentMenu.parentActionBar.createCircleRipple()
        isClickable = true
        isFocusable = true
    }

    fun setIcon(resId: Int) {
        iconView.setImageResource(resId)
        if (iconColor != 0) {
            iconView.colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        }
    }

    fun setIcon(drawable: Drawable?) {
        iconView.setImageDrawable(drawable)
        if (iconColor != 0 && drawable != null) {
            iconView.colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        }
    }

    fun setIconColor(color: Int) {
        iconColor = color
        iconView.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    fun getItemIconView(): ImageView = iconView

    fun setPopupItemsColor(color: Int, icon: Boolean = false) {
        popupItemsColor = color
    }

    fun setPopupItemsSelectorColor(color: Int) {
        popupItemsSelectorColor = color
    }

    fun setMenuClickOverride(value: Boolean) {
        overrideMenuClick = value
    }

    fun setSubMenuOpenSide(side: Int) {
        subMenuOpenSide = side
    }

    fun setMenuYOffset(offset: Int) {
        menuYOffset = offset
    }

    fun setMenuXOffset(offset: Int) {
        menuXOffset = offset
    }

    fun hasSubMenu(): Boolean = popupLayout != null && subItems.isNotEmpty()

    private fun ensurePopupLayout() {
        if (popupLayout != null) return
        popupLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val theme = ThemeColors.instance
            setBackgroundColor(theme.getColor(ThemeColors.key_dialogBackground))
            val pad = LayoutHelper.dp(8)
            setPadding(0, pad, 0, pad)
        }
    }

    fun addSubItem(id: Int, text: CharSequence): View {
        return addSubItem(id, 0, text)
    }

    fun addSubItem(id: Int, icon: Int, text: CharSequence): View {
        ensurePopupLayout()
        val theme = ThemeColors.instance
        val cell = TextView(context).apply {
            setText(text)
            setTextColor(if (popupItemsColor != 0) popupItemsColor else theme.getColor(ThemeColors.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
            minHeight = LayoutHelper.dp(48)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            tag = id
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            if (icon != 0) {
                setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawablePadding = LayoutHelper.dp(12)
            }
            setOnClickListener {
                parentMenu.parentActionBar.onItemClick(id)
                closeSubMenu()
            }
        }
        popupLayout!!.addView(cell, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(48)
        ))
        subItems.add(cell)
        return cell
    }

    fun addGap(): View {
        ensurePopupLayout()
        val divider = View(context).apply {
            setBackgroundColor(ThemeColors.instance.getColor(ThemeColors.key_divider))
        }
        popupLayout!!.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(1)
        ).apply {
            topMargin = LayoutHelper.dp(4)
            bottomMargin = LayoutHelper.dp(4)
        })
        subItems.add(divider)
        return divider
    }

    fun removeAllSubItems() {
        popupLayout?.removeAllViews()
        subItems.clear()
    }

    fun getSubItem(id: Int): View? = subItems.firstOrNull { it.tag == id }

    fun hideSubItem(id: Int) {
        subItems.firstOrNull { it.tag == id }?.visibility = GONE
    }

    fun showSubItem(id: Int) {
        subItems.firstOrNull { it.tag == id }?.visibility = VISIBLE
    }

    fun toggleSubMenu() {
        if (popupWindow?.isShowing == true) {
            closeSubMenu()
        } else {
            openSubMenu()
        }
    }

    fun openSubMenu() {
        val layout = popupLayout ?: return
        if (subItems.isEmpty()) return

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
        }
        if (layout.parent is ViewGroup) (layout.parent as ViewGroup).removeView(layout)
        scrollView.addView(layout)

        popupWindow = PopupWindow(scrollView, LayoutHelper.dp(200), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isFocusable = true
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            animationStyle = 0
            elevation = LayoutHelper.dp(4).toFloat()
        }

        val location = IntArray(2)
        getLocationOnScreen(location)

        val xOff = when (subMenuOpenSide) {
            1 -> location[0] + menuXOffset
            else -> location[0] + measuredWidth - LayoutHelper.dp(200) + menuXOffset
        }
        val yOff = location[1] + measuredHeight + menuYOffset

        popupWindow?.showAtLocation(this, Gravity.NO_GRAVITY, xOff, yOff)
    }

    fun closeSubMenu() {
        popupWindow?.dismiss()
        popupWindow = null
    }

    fun setIsSearchField(value: Boolean): ActionBarMenuItem {
        if (isSearchField == value) return this
        isSearchField = value
        if (value) {
            searchContainer = FrameLayout(context)

            searchField = EditTextBoldCursor(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
                setHintColor(ThemeColors.instance.getColor(ThemeColors.key_text_secondary))
                setTextColor(ThemeColors.instance.getColor(ThemeColors.key_text_primary))
                isSingleLine = true
                setBackgroundResource(0)
                val pad = LayoutHelper.dp(4)
                setPadding(0, pad, 0, pad)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                searchFieldHint?.let { hint = it }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        searchListener?.onTextChanged(searchField)
                        clearButton?.visibility = if (s.isNullOrEmpty()) GONE else VISIBLE
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
                setOnEditorActionListener { _, _, _ ->
                    searchListener?.onSearchPressed(searchField)
                    true
                }
            }
            searchContainer!!.addView(searchField, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.CENTER_VERTICAL, 0f, 0f, 36f, 0f
            ))

            clearButton = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                visibility = GONE
                setOnClickListener {
                    searchField?.setText("")
                    searchField?.requestFocus()
                    AndroidUtilities.showKeyboard(searchField)
                }
            }
            searchContainer!!.addView(clearButton, LayoutHelper.createFrame(
                36, LayoutHelper.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL
            ))

            searchContainer!!.visibility = GONE
        }
        return this
    }

    fun setSearchFieldHint(hint: CharSequence?) {
        searchFieldHint = hint
        searchField?.hint = hint
    }

    fun setSearchFieldText(text: CharSequence?, animated: Boolean = false) {
        searchField?.setText(text)
        searchField?.setSelection(searchField?.text?.length ?: 0)
    }

    fun getSearchField(): EditText? = searchField

    fun getSearchContainer(): FrameLayout? = searchContainer

    fun setActionBarMenuItemSearchListener(listener: ActionBarMenuItemSearchListener?) {
        searchListener = listener
    }

    fun toggleSearch(openKeyboard: Boolean) {
        if (!isSearchField) return
        val visible = searchContainer?.visibility == VISIBLE
        if (visible) {
            if (searchListener?.onSearchCollapse() != false) {
                searchContainer?.visibility = GONE
                iconView.visibility = VISIBLE
                searchField?.setText("")
                AndroidUtilities.hideKeyboard(searchField)
            }
        } else {
            searchListener?.onSearchExpand()
            searchContainer?.visibility = VISIBLE
            iconView.visibility = GONE
            searchField?.requestFocus()
            if (openKeyboard) {
                AndroidUtilities.showKeyboard(searchField)
            }
        }
    }

    val parentActionBar: ActionBarView get() = parentMenu.parentActionBar

    fun isSearchFieldVisible(): Boolean = isSearchField && searchContainer?.visibility == VISIBLE

    fun isSubMenuShowing(): Boolean = popupWindow?.isShowing == true

    fun showSubItem(id: Int, animated: Boolean) {
        val view = subItems.firstOrNull { it.tag == id } ?: return
        view.visibility = VISIBLE
    }

    fun setShowedFromBottom(value: Boolean) {}

    fun setFitSubItems(value: Boolean) {}

    fun setLayoutInScreen(value: Boolean) {}

    fun setPopupAnimationEnabled(value: Boolean) {}

    fun setAllowCloseAnimation(value: Boolean) {}

    fun redrawPopup(color: Int) {
        popupLayout?.setBackgroundColor(color)
    }

    fun getVisibleSubItemsCount(): Int = subItems.count { it.visibility == VISIBLE }

    fun hasSubItem(id: Int): Boolean = subItems.any { it.tag == id }

    fun isSubItemVisible(id: Int): Boolean = subItems.firstOrNull { it.tag == id }?.visibility == VISIBLE

    fun setSubItemShown(id: Int, show: Boolean) {
        val view = subItems.firstOrNull { it.tag == id } ?: return
        view.visibility = if (show) VISIBLE else GONE
    }

    fun getSearchClearButton(): ImageView? = clearButton

    fun requestFocusOnSearchView() {
        searchField?.requestFocus()
    }

    fun clearFocusOnSearchView() {
        searchField?.clearFocus()
    }

    fun clearSearchText() {
        searchField?.setText("")
    }

    fun onSearchPressed() {
        searchListener?.onSearchPressed(searchField)
    }

    fun forceUpdatePopupPosition() {
        if (popupWindow?.isShowing == true) {
            popupWindow?.dismiss()
            openSubMenu()
        }
    }

    override fun setContentDescription(description: CharSequence?) {
        super.setContentDescription(description)
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }
}
