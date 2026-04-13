package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.SharedConfig
import com.mezon.mobile.core.ThemeColors

class ActionBarView(context: Context, private val theme: ThemeColors) : FrameLayout(context) {

    open class ActionBarMenuOnItemClick {
        open fun onItemClick(id: Int) {}
        open fun canOpenMenu(): Boolean = true
    }

    private var interceptTouches = true
    private var forceSkipTouches = false
    private var backButtonImageView: ImageView? = null
    private var titleTextView: TextView? = null
    private var subtitleTextView: TextView? = null
    private var titleStartImageView: ImageView? = null
    private var titleStartIconSize = 0
    private var titleStartIconGap = 0
    private var subtitleStartPadding = 0
    var menu: ActionBarMenu? = null
        private set
    var actionMode: ActionBarMenu? = null
        private set

    var actionBarMenuOnItemClick: ActionBarMenuOnItemClick? = null

    var occupyStatusBar = false
    var castShadows = true
        set(value) {
            if (field != value) {
                (parent as? View)?.invalidate()
                invalidate()
            }
            field = value
        }
    var shadowAlpha = 255
        set(value) {
            if (field != value) {
                (parent as? View)?.invalidate()
                invalidate()
            }
            field = value
        }

    private var addToContainer = true
    private var actionModeVisible = false
    private var isSearchFieldVisible = false
    private var drawDivider = true
    private var centerTitle = false
    private var actionModeAnimation: android.animation.ValueAnimator? = null
    private var actionModeAnimationProgress = 0f
    private var extraHeight = 0
    private var interceptTouchChild: View? = null

    var itemsBackgroundColor = 0
    var itemsColor = 0
    var itemsActionModeColor = 0
    var itemsActionModeBackgroundColor = 0
    var parentFragment: com.mezon.mobile.core.BaseFragment? = null
    private var resourcesProvider: com.mezon.mobile.core.ThemeColors.ResourcesProvider? = null

    private var allowOverlayTitle = false
    private var overlayTitleText: String? = null
    private var overlayTitleId = 0
    private var overlayTitleAction: Runnable? = null
    private var lastTitle: CharSequence? = null

    private var actionModeTag: String? = null
    private var actionModeColor = 0

    var resumed = false
        private set

    val backButton: ImageView
        get() {
            if (backButtonImageView == null) createBackButtonImage()
            return backButtonImageView!!
        }

    init {
        setBackgroundColor(theme.surface)
        setWillNotDraw(false)
    }

    private fun createBackButtonImage() {
        backButtonImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = rippleBackground()
            setPadding(LayoutHelper.dp(1), 0, 0, 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener {
                if (!actionModeVisible && isSearchFieldVisible) {
                    closeSearchField()
                    return@setOnClickListener
                }
                actionBarMenuOnItemClick?.onItemClick(-1)
            }
        }
        addView(backButtonImageView, LayoutHelper.createFrame(54, 54, Gravity.START or Gravity.TOP))
    }

    fun setBackButtonImage(resource: Int) {
        if (backButtonImageView == null) createBackButtonImage()
        backButtonImageView!!.visibility = if (resource == 0) GONE else VISIBLE
        backButtonImageView!!.setImageResource(resource)
        val color = itemsColor.takeIf { it != 0 } ?: theme.onSurface
        backButtonImageView!!.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    fun setBackButtonDrawable(drawable: android.graphics.drawable.Drawable?) {
        if (backButtonImageView == null) createBackButtonImage()
        backButtonImageView!!.visibility = if (drawable == null) GONE else VISIBLE
        backButtonImageView!!.setImageDrawable(drawable)
        val color = itemsColor.takeIf { it != 0 } ?: theme.onSurface
        backButtonImageView!!.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    fun setTitle(title: CharSequence?) {
        if (title != null && titleTextView == null) createTitleTextView()
        titleTextView?.visibility = if (title != null && !isSearchFieldVisible) VISIBLE else INVISIBLE
        titleTextView?.text = title
    }

    fun getTitle(): CharSequence? = titleTextView?.text

    fun setTitleOnClickListener(listener: OnClickListener?) {
        if (titleTextView == null) createTitleTextView()
        titleTextView?.setOnClickListener(listener)
    }

    private fun createTitleTextView() {
        titleTextView = TextView(context).apply {
            setTextColor(theme.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(titleTextView)
    }

    fun setSubtitle(subtitle: CharSequence?) {
        if (subtitle != null && subtitleTextView == null) createSubtitleTextView()
        subtitleTextView?.visibility = if (!subtitle.isNullOrEmpty() && !isSearchFieldVisible) VISIBLE else GONE
        subtitleTextView?.text = subtitle
    }

    fun getSubtitle(): CharSequence? = subtitleTextView?.text

    fun setSubtitleStartPadding(paddingPx: Int) {
        subtitleStartPadding = paddingPx
        subtitleTextView?.setPaddingRelative(paddingPx, 0, 0, 0)
        requestLayout()
    }

    fun setTitleStartIcon(drawable: Drawable?, iconSizePx: Int, gapAfterIconPx: Int) {
        if (drawable == null || iconSizePx <= 0) {
            titleStartIconSize = 0
            titleStartIconGap = 0
            titleStartImageView?.visibility = GONE
            requestLayout()
            return
        }
        titleStartIconSize = iconSizePx
        titleStartIconGap = gapAfterIconPx
        if (titleStartImageView == null) {
            titleStartImageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = GONE
                addView(this)
            }
        }
        titleStartImageView!!.setImageDrawable(drawable)
        titleStartImageView!!.visibility = if (isSearchFieldVisible) GONE else VISIBLE
        requestLayout()
    }

    private fun titleStartLeadWidth(): Int =
        if (titleStartIconSize > 0 && titleStartImageView?.visibility != GONE) {
            titleStartIconSize + titleStartIconGap
        } else 0

    private fun createSubtitleTextView() {
        subtitleTextView = TextView(context).apply {
            setTextColor(theme.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(subtitleStartPadding, 0, 0, 0)
        }
        addView(subtitleTextView)
    }

    fun createMenu(): ActionBarMenu {
        if (menu != null) return menu!!
        menu = ActionBarMenu(context, this)
        addView(menu, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.END))
        return menu!!
    }

    fun createActionMode(): ActionBarMenu {
        if (actionMode != null) return actionMode!!
        actionMode = ActionBarMenu(context, this).apply {
            isActionMode = true
            visibility = GONE
            setBackgroundColor(theme.surface)
        }
        addView(actionMode, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        return actionMode!!
    }

    fun showActionMode(animated: Boolean = true) {
        if (actionMode == null) return
        actionModeVisible = true
        actionMode!!.visibility = VISIBLE
        if (animated && SharedConfig.animationsEnabled()) {
            actionModeAnimation?.cancel()
            actionModeAnimation = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                addUpdateListener {
                    val p = it.animatedValue as Float
                    actionModeAnimationProgress = p
                    actionMode!!.alpha = p
                    titleTextView?.alpha = 1f - p
                    subtitleTextView?.alpha = 1f - p
                    titleStartImageView?.alpha = 1f - p
                    menu?.alpha = 1f - p
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        actionModeAnimationProgress = 1f
                        titleTextView?.visibility = INVISIBLE
                        subtitleTextView?.visibility = INVISIBLE
                        titleStartImageView?.visibility = INVISIBLE
                        menu?.visibility = INVISIBLE
                        actionModeAnimation = null
                    }
                })
                start()
            }
        } else {
            actionModeAnimationProgress = 1f
            actionMode!!.alpha = 1f
            titleTextView?.visibility = INVISIBLE
            subtitleTextView?.visibility = INVISIBLE
            titleStartImageView?.visibility = INVISIBLE
            menu?.visibility = INVISIBLE
            invalidate()
        }
    }

    fun hideActionMode(animated: Boolean = true) {
        if (actionMode == null) return
        actionModeVisible = false
        val title = titleTextView?.text
        titleTextView?.visibility = if (title != null && !isSearchFieldVisible) VISIBLE else INVISIBLE
        subtitleTextView?.let {
            if (!it.text.isNullOrEmpty() && !isSearchFieldVisible) it.visibility = VISIBLE
        }
        if (titleStartIconSize > 0 && !isSearchFieldVisible) {
            titleStartImageView?.visibility = VISIBLE
            titleStartImageView?.alpha = 1f
        }
        menu?.visibility = VISIBLE
        if (animated && SharedConfig.animationsEnabled()) {
            actionModeAnimation?.cancel()
            actionModeAnimation = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 200
                addUpdateListener {
                    val p = it.animatedValue as Float
                    actionModeAnimationProgress = p
                    actionMode!!.alpha = p
                    titleTextView?.alpha = 1f - p
                    subtitleTextView?.alpha = 1f - p
                    titleStartImageView?.alpha = 1f - p
                    menu?.alpha = 1f - p
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        actionModeAnimationProgress = 0f
                        actionMode!!.visibility = GONE
                        actionModeAnimation = null
                    }
                })
                start()
            }
        } else {
            actionModeAnimationProgress = 0f
            actionMode!!.visibility = GONE
            invalidate()
        }
    }

    fun isActionModeShowed(): Boolean = actionModeVisible
    fun isActionModeShowed(animated: Boolean): Boolean = actionModeVisible || (animated && actionModeAnimation != null)
    fun isActionModeShowed(tag: String?): Boolean = actionModeVisible && (tag == null || tag == actionModeTag)

    fun getActionModeMenu(): ActionBarMenu? = actionMode

    fun createActionMode(needTop: Boolean, tag: String?): ActionBarMenu {
        actionModeTag = tag
        return createActionMode()
    }

    fun actionModeIsExist(tag: String?): Boolean = actionMode != null && (tag == null || tag == actionModeTag)

    fun setActionModeColor(color: Int) { actionModeColor = color; actionMode?.setBackgroundColor(color) }

    fun shouldAddToContainer(): Boolean = addToContainer

    fun setAddToContainer(value: Boolean) {
        addToContainer = value
    }

    fun isCastingShadows(): Boolean = castShadows

    fun getCurrentShadowAlpha(): Int = shadowAlpha

    fun isSearchFieldVisible(): Boolean = isSearchFieldVisible

    fun onSearchFieldVisibilityChanged(visible: Boolean) {
        isSearchFieldVisible = visible
        titleTextView?.visibility = if (visible) INVISIBLE else VISIBLE
        subtitleTextView?.let {
            it.visibility = if (visible || it.text.isNullOrEmpty()) GONE else VISIBLE
        }
        titleStartImageView?.visibility = when {
            visible -> GONE
            titleStartIconSize > 0 -> VISIBLE
            else -> GONE
        }
        requestLayout()
    }

    fun onItemClick(id: Int) {
        actionBarMenuOnItemClick?.onItemClick(id)
    }

    fun onResume() {
        resumed = true
    }

    fun onPause() {
        resumed = false
        menu?.hideAllPopupMenus()
    }

    fun setTitleColor(color: Int) {
        titleTextView?.setTextColor(color)
    }

    fun setSubtitleColor(color: Int) {
        subtitleTextView?.setTextColor(color)
    }

    fun setItemsColor(color: Int, isActionMode: Boolean = false) {
        if (isActionMode) {
            itemsActionModeColor = color
            actionMode?.setItemColor(color)
        } else {
            itemsColor = color
            backButtonImageView?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            menu?.setItemColor(color)
        }
    }

    fun setItemsBackgroundColor(color: Int, isActionMode: Boolean = false) {
        if (isActionMode) {
            itemsActionModeBackgroundColor = color
        } else {
            itemsBackgroundColor = color
        }
    }

    fun setMenuOnItemClick(listener: ActionBarMenuOnItemClick?) {
        actionBarMenuOnItemClick = listener
    }

    fun getMenuOnItemClick(): ActionBarMenuOnItemClick? = actionBarMenuOnItemClick

    fun setAllowOverlayTitle(value: Boolean) {
        allowOverlayTitle = value
    }

    fun setTitleOverlayText(title: String?, titleId: Int, action: Runnable?) {
        if (!allowOverlayTitle) return
        overlayTitleText = title
        overlayTitleId = titleId
        overlayTitleAction = action
        if (title != null) {
            if (titleTextView?.text != null) lastTitle = titleTextView?.text
            setTitle(title)
        } else if (lastTitle != null) {
            setTitle(lastTitle)
        }
    }

    fun closeSearchField(closeKeyboard: Boolean = true) {
        menu?.let { m ->
            for (i in 0 until m.childCount) {
                val item = m.getChildAt(i)
                if (item is ActionBarMenuItem && item.isSearchField) {
                    item.toggleSearch(false)
                    if (closeKeyboard) AndroidUtilities.hideKeyboard(item.getSearchField())
                    break
                }
            }
        }
        onSearchFieldVisibilityChanged(false)
    }

    fun openSearchField(text: String?, animated: Boolean = true) {
        menu?.let { m ->
            for (i in 0 until m.childCount) {
                val item = m.getChildAt(i)
                if (item is ActionBarMenuItem && item.isSearchField) {
                    item.toggleSearch(true)
                    item.getSearchField()?.let { field ->
                        if (!text.isNullOrEmpty()) field.setText(text)
                        field.requestFocus()
                        AndroidUtilities.showKeyboard(field)
                    }
                    break
                }
            }
        }
        onSearchFieldVisibilityChanged(true)
    }

    fun onSearchPressed() {
        menu?.let { m ->
            for (i in 0 until m.childCount) {
                val item = m.getChildAt(i)
                if (item is ActionBarMenuItem && item.isSearchField) {
                    item.onSearchPressed()
                    break
                }
            }
        }
    }

    fun setPopupItemsColor(color: Int, icon: Boolean, forActionMode: Boolean) {
        if (forActionMode) {
            actionMode?.setPopupItemsColor(color, icon)
        } else {
            menu?.setPopupItemsColor(color, icon)
        }
    }

    fun setPopupItemsSelectorColor(color: Int, forActionMode: Boolean) {
        if (forActionMode) {
            actionMode?.setPopupItemsSelectorColor(color)
        } else {
            menu?.setPopupItemsSelectorColor(color)
        }
    }

    fun setPopupBackgroundColor(color: Int, forActionMode: Boolean) {
        if (forActionMode) {
            actionMode?.setPopupBackgroundColor(color)
        } else {
            menu?.setPopupBackgroundColor(color)
        }
    }

    fun getBackButtonView(): ImageView? = backButtonImageView

    fun getBackButtonDrawable(): android.graphics.drawable.Drawable? = backButtonImageView?.drawable

    fun setBackButtonContentDescription(description: CharSequence?) {
        backButtonImageView?.contentDescription = description
    }

    fun getTitleTextView(): TextView? = titleTextView

    fun getSubtitleTextView(): TextView? = subtitleTextView

    fun onMenuButtonPressed() {
        menu?.onMenuButtonPressed()
    }

    fun setDrawDivider(draw: Boolean) {
        drawDivider = draw
        invalidate()
    }

    fun updateColors() { applyTheme() }

    fun applyTheme() {
        setBackgroundColor(theme.surface)
        titleTextView?.setTextColor(theme.onSurface)
        subtitleTextView?.setTextColor(theme.onSurface)
        val color = itemsColor.takeIf { it != 0 } ?: theme.onSurface
        backButtonImageView?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        backButtonImageView?.background = rippleBackground()
        invalidate()
    }

    fun setCenterTitle(center: Boolean) {
        centerTitle = center
        requestLayout()
    }

    fun setExtraHeight(height: Int) {
        extraHeight = height
        requestLayout()
    }

    fun getExtraHeight(): Int = extraHeight

    fun setInterceptTouchEventChild(child: View?) {
        interceptTouchChild = child
    }

    fun getCurrentActionBarHeight(): Int = ACTION_BAR_HEIGHT

    companion object {
        @JvmStatic val ACTION_BAR_HEIGHT = com.mezon.mobile.core.LayoutHelper.dp(56)
        private val BACK_BUTTON_SIZE = com.mezon.mobile.core.LayoutHelper.dp(54)
        private val TEXT_LEFT_WITH_BACK = com.mezon.mobile.core.LayoutHelper.dp(72)

        @JvmStatic fun getCurrentActionBarHeightStatic(): Int = ACTION_BAR_HEIGHT
    }

    fun getActionBarFullHeight(): Int {
        val statusBarOffset = if (occupyStatusBar) AndroidUtilities.statusBarHeight else 0
        return getCurrentActionBarHeight() + statusBarOffset + extraHeight
    }

    fun isOccupyingStatusBar(): Boolean = occupyStatusBar

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val statusBarOffset = if (occupyStatusBar) AndroidUtilities.statusBarHeight else 0
        val actionBarHeight = getCurrentActionBarHeight()
        val totalHeight = actionBarHeight + statusBarOffset + extraHeight
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        backButtonImageView?.let { btn ->
            if (btn.visibility != GONE) {
                btn.measure(
                    MeasureSpec.makeMeasureSpec(BACK_BUTTON_SIZE, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(actionBarHeight, MeasureSpec.EXACTLY)
                )
            }
        }

        var menuWidth = 0
        menu?.let { m ->
            if (m.visibility != GONE) {
                m.measure(
                    MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(actionBarHeight, MeasureSpec.EXACTLY)
                )
                menuWidth = m.measuredWidth
            }
        }

        actionMode?.let { am ->
            if (am.visibility != GONE) {
                am.measure(
                    MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(actionBarHeight, MeasureSpec.EXACTLY)
                )
            }
        }

        val backWidth = if (backButtonImageView != null && backButtonImageView!!.visibility != GONE) BACK_BUTTON_SIZE else 0
        val lead = titleStartLeadWidth()
        val titleAvailableWidth = (widthSize - backWidth - menuWidth - LayoutHelper.dp(16) - lead).coerceAtLeast(0)

        titleStartImageView?.let { iv ->
            if (iv.visibility != GONE && titleStartIconSize > 0) {
                iv.measure(
                    MeasureSpec.makeMeasureSpec(titleStartIconSize, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(titleStartIconSize, MeasureSpec.EXACTLY)
                )
            }
        }

        titleTextView?.let { tv ->
            if (tv.visibility != GONE) {
                tv.measure(
                    MeasureSpec.makeMeasureSpec(titleAvailableWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(actionBarHeight, MeasureSpec.AT_MOST)
                )
            }
        }

        subtitleTextView?.let { sv ->
            if (sv.visibility != GONE) {
                sv.measure(
                    MeasureSpec.makeMeasureSpec(titleAvailableWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(actionBarHeight, MeasureSpec.AT_MOST)
                )
            }
        }

        setMeasuredDimension(widthSize, totalHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val statusBarOffset = if (occupyStatusBar) AndroidUtilities.statusBarHeight else 0
        val actionBarHeight = getCurrentActionBarHeight()
        val w = right - left

        backButtonImageView?.let { btn ->
            if (btn.visibility != GONE) {
                val btnTop = statusBarOffset + (actionBarHeight - btn.measuredHeight) / 2
                btn.layout(0, btnTop, btn.measuredWidth, btnTop + btn.measuredHeight)
            }
        }

        menu?.let { m ->
            if (m.visibility != GONE) {
                val menuLeft = w - m.measuredWidth
                m.layout(menuLeft, statusBarOffset, w, statusBarOffset + actionBarHeight)
            }
        }

        actionMode?.let { am ->
            if (am.visibility != GONE) {
                am.layout(0, statusBarOffset, w, statusBarOffset + actionBarHeight)
            }
        }

        val backWidth = if (backButtonImageView != null && backButtonImageView!!.visibility != GONE) TEXT_LEFT_WITH_BACK else LayoutHelper.dp(16)
        val menuWidth = if (menu != null && menu!!.visibility != GONE) menu!!.measuredWidth else 0
        val titleLeft = backWidth
        val titleRight = w - menuWidth - LayoutHelper.dp(8)

        val hasSubtitle = subtitleTextView != null && subtitleTextView!!.visibility == VISIBLE
        val lead = titleStartLeadWidth()

        if (centerTitle) {
            titleTextView?.let { tv ->
                if (tv.visibility != GONE) {
                    val titleH = tv.measuredHeight
                    val topMargin = statusBarOffset + (actionBarHeight - titleH) / 2
                    val tLeft = (w - tv.measuredWidth) / 2
                    tv.layout(tLeft, topMargin, tLeft + tv.measuredWidth, topMargin + titleH)
                }
            }
        } else if (hasSubtitle) {
            val titleH = titleTextView?.measuredHeight ?: 0
            val subH = subtitleTextView?.measuredHeight ?: 0
            val totalH = titleH + subH
            val topMargin = statusBarOffset + (actionBarHeight - totalH) / 2
            val textStart = titleLeft + lead

            titleStartImageView?.let { iv ->
                if (iv.visibility != GONE && titleStartIconSize > 0) {
                    val iconTop = topMargin + (totalH - titleStartIconSize) / 2
                    iv.layout(titleLeft, iconTop, titleLeft + titleStartIconSize, iconTop + titleStartIconSize)
                }
            }

            titleTextView?.let { tv ->
                if (tv.visibility != GONE) {
                    val r = (textStart + tv.measuredWidth).coerceAtMost(titleRight)
                    tv.layout(textStart, topMargin, r, topMargin + titleH)
                }
            }

            subtitleTextView?.let { sv ->
                val subTop = topMargin + titleH
                val r = (textStart + sv.measuredWidth).coerceAtMost(titleRight)
                sv.layout(textStart, subTop, r, subTop + subH)
            }
        } else {
            titleTextView?.let { tv ->
                if (tv.visibility != GONE) {
                    val titleH = tv.measuredHeight
                    val topMargin = statusBarOffset + (actionBarHeight - titleH) / 2
                    val textStart = titleLeft + lead
                    titleStartImageView?.let { iv ->
                        if (iv.visibility != GONE && titleStartIconSize > 0) {
                            val iconTop = topMargin + (titleH - titleStartIconSize) / 2
                            iv.layout(titleLeft, iconTop, titleLeft + titleStartIconSize, iconTop + titleStartIconSize)
                        }
                    }
                    val r = (textStart + tv.measuredWidth).coerceAtMost(titleRight)
                    tv.layout(textStart, topMargin, r, topMargin + titleH)
                }
            }
        }
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (forceSkipTouches) return false
        return super.onTouchEvent(event) || interceptTouches
    }

    fun setInterceptTouches(value: Boolean) {
        interceptTouches = value
    }

    fun setForceSkipTouches(value: Boolean) {
        forceSkipTouches = value
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawDivider) {
            val y = height.toFloat() - 1f
            canvas.drawRect(0f, y, width.toFloat(), y + 1f, theme.dividerPaint)
        }
    }

    fun setBackClickListener(listener: () -> Unit) {
        if (backButtonImageView == null) {
            setBackButtonImage(R.drawable.ic_arrow_back)
        }
        actionBarMenuOnItemClick = object : ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) listener()
            }
        }
    }

    fun createCircleRipple(): android.graphics.drawable.Drawable {
        val color = itemsColor.takeIf { it != 0 } ?: theme.onSurface
        val rippleColor = android.content.res.ColorStateList.valueOf(color and 0x1A_FFFFFF)
        val mask = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        return android.graphics.drawable.RippleDrawable(rippleColor, null, mask)
    }

    private fun rippleBackground() = createCircleRipple()
}
