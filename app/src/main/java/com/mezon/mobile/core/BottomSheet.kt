package com.mezon.mobile.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat


open class BottomSheet(
    context: Context,
    private val needFocusable: Boolean = false
) : Dialog(context) {

    // ─── Interfaces ───────────────────────────────────────────────────────

    interface BottomSheetDelegateInterface {
        fun onOpenAnimationStart() {}
        fun onOpenAnimationEnd() {}
        fun canDismiss(): Boolean = true
    }

    fun interface OnClickListener {
        fun onClick(dialog: BottomSheet, which: Int)
    }

    // ─── BottomSheetCell ──────────────────────────────────────────────────

    class BottomSheetCell(context: Context) : FrameLayout(context) {
        val textView: TextView = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(ThemeColors.instance.getColor(ThemeColors.key_dialogTextBlack))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val imageView: ImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
        }
        private var checked = false
        var isItemSelected = false

        init {
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            addView(imageView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_VERTICAL or Gravity.START, 16f, 0f, 0f, 0f))
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.START, 56f, 0f, 16f, 0f))
        }

        fun setTextAndIcon(text: CharSequence, icon: Int) {
            textView.text = text
            if (icon != 0) {
                imageView.setImageResource(icon)
                imageView.visibility = VISIBLE
            } else {
                imageView.visibility = GONE
                (textView.layoutParams as LayoutParams).leftMargin = LayoutHelper.dp(16)
            }
        }

        fun setTextColor(color: Int) { textView.setTextColor(color) }
        fun setIconColor(color: Int) { imageView.setColorFilter(color) }
        fun setChecked(value: Boolean) { checked = value }
        fun isChecked(): Boolean = checked
    }

    // ─── Theme / State ─────────────────────────────────────────────────────

    private val theme: ThemeColors get() = ThemeColors.instance

    // Container hierarchy: root (ContainerView) > containerView (content wrapper)
    var containerView: ViewGroup? = null
        private set
    lateinit var container: ContainerView
        private set

    private var customView: View? = null
    protected var containerHeight = ViewGroup.LayoutParams.WRAP_CONTENT

    fun setContainerHeightPx(height: Int) {
        containerHeight = height
    }
    private var dismissed = false
    private var openAnimationCompleted = false
    private var allowCustomAnimation = true
    @JvmField protected var canDismissWithSwipe = true
    private var canDismissWithTouchOutside = true
    private var dimAlpha = 51  // 0-255, Telegram default ~ 51 (0.2 * 255)
    @JvmField protected var dimBehind = true
    private var useFullWidth = false
    protected var isFullscreen = false
    @JvmField protected var drawNavigationBar = false
    private var navBarColor = 0
    private var navBarColorKey = ThemeColors.key_sheetBackground
    protected var scrollNavBar = false
    protected var drawDoubleNavigationBar = false

    var delegate: BottomSheetDelegateInterface? = null
    var keyboardVisible = false
        private set
    var keyboardHeight = 0
        private set
    var bottomInset = 0
        private set
    var leftInset = 0
        private set
    var rightInset = 0
        private set
    var statusBarHeight = AndroidUtilities.statusBarHeight
        private set

    protected var titleText: CharSequence? = null
    private var bigTitle = false
    private var multipleLinesTitle = false
    private var titleView: TextView? = null
    private var items: Array<CharSequence>? = null
    private var itemIcons: IntArray? = null
    private var itemClickListener: OnClickListener? = null
    private val itemViews = ArrayList<BottomSheetCell>()

    protected var contentLayout: LinearLayout? = null
    private var showWithoutAnimation = false
    private var applyTopPadding = true
    private var applyBottomPadding = true
    @JvmField protected var allowNestedScroll = true
    private var onHideListener: DialogInterface.OnDismissListener? = null
    private var touchSlop: Int = 0
    private var useFastDismiss = false
    private var skipDismissAnimation = false

    private var snapPoints: FloatArray = floatArrayOf()
    private var initialSnapIndex: Int = 0
    var currentSnapIndex: Int = 0
        private set
    private var availableSheetHeight: Int = 0

    // Background / shadow
    protected var shadowDrawable: Drawable? = null
    protected var backgroundPaddingTop = 0
    protected var backgroundPaddingLeft = 0
    private var useBackgroundTopPadding = true
    protected var customViewGravity = Gravity.LEFT or Gravity.TOP

    // Dim overlay
    protected val backDrawable = object : ColorDrawable(0xff000000.toInt()) {
        override fun setAlpha(alpha: Int) {
            super.setAlpha(alpha)
            if (::container.isInitialized) container.invalidate()
        }
    }

    // Open animation
    protected var openInterpolator: Interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
    protected var currentSheetAnimation: AnimatorSet? = null
    protected var currentSheetAnimationType = 0
    protected var navigationBarAnimation: ValueAnimator? = null
    protected var navigationBarAlpha = 0f
    private var behindKeyboardColor = 0
    private var currentPanTranslationY = 0f
    private var lastInsets: WindowInsets? = null
    private var lastKeyboardHeight = 0
    private var layoutCount = 0
    protected var startAnimationRunnable: Runnable? = null
    private var openNoDelay = false
    private var allowDrawContent = true
    private var useHardwareLayer = true
    private var useLightStatusBar = true
    protected var nestedScrollChild: View? = null
    private var disableScroll = false
    private val dismissRunnable = Runnable { dismiss() }
    protected var isPortrait = true
    private var overlayDrawNavBarColor = 0
    private val globalNotificationCenter = NotificationCenter.getGlobalInstance()
    private var heavyOperationsAnimationPointer = 0

    // Swipe-to-back (horizontal) — not commonly used for message menu
    private var transitionFromRight = false

    // ─── Public Setters ────────────────────────────────────────────────────

    val sheetContainer: ViewGroup? get() = containerView
    fun setCustomView(view: View?) { customView = view }
    fun getCustomView(): View? = customView
    override fun setTitle(title: CharSequence?) { titleText = title }
    fun setTitle(title: CharSequence?, big: Boolean) { titleText = title; bigTitle = big }
    fun setTitleMultipleLines(allow: Boolean) { multipleLinesTitle = allow }
    fun setApplyTopPadding(value: Boolean) { applyTopPadding = value }
    fun setApplyBottomPadding(value: Boolean) { applyBottomPadding = value }
    fun setAllowNestedScroll(value: Boolean) {
        allowNestedScroll = value
        if (!value) containerView?.translationY = 0f
    }
    fun setCanDismissWithSwipe(value: Boolean) { canDismissWithSwipe = value }
    fun setCanDismissWithTouchOutside(value: Boolean) { canDismissWithTouchOutside = value }
    fun setOnHideListener(listener: DialogInterface.OnDismissListener?) { onHideListener = listener }
    override fun isShowing(): Boolean = !dismissed
    fun isDismissed(): Boolean = dismissed

    fun setOverlayNavBarColor(color: Int) {
        overlayDrawNavBarColor = color
        navBarColor = color
        if (color != 0) drawNavigationBar = true
        if (::container.isInitialized) container.invalidate()
    }

    fun setAllowCustomAnimation(value: Boolean) { allowCustomAnimation = value }
    fun setDimBehind(dim: Boolean) { dimBehind = dim; dimAlpha = if (dim) 51 else 0 }
    fun setDimBehindAlpha(alpha: Int) { dimAlpha = alpha }
    fun setUseFullWidth(value: Boolean) { useFullWidth = value }
    fun setUseFullscreen(value: Boolean) { isFullscreen = value }
    fun setShowWithoutAnimation(value: Boolean) { showWithoutAnimation = value }
    fun setDrawNavigationBar(draw: Boolean) { drawNavigationBar = draw }
    fun setNavigationBarColor(color: Int) { navBarColor = color }
    fun setDisableScroll(b: Boolean) { disableScroll = b }
    fun setOpenNoDelay(value: Boolean) { openNoDelay = value }
    fun setUseHardwareLayer(value: Boolean) { useHardwareLayer = value }
    fun setAllowDrawContent(value: Boolean) {
        if (allowDrawContent != value) {
            allowDrawContent = value
            if (::container.isInitialized) {
                container.background = if (allowDrawContent) backDrawable else null
                container.invalidate()
            }
        }
    }

    fun fixNavigationBar() {
        fixNavigationBar(theme.getColor(ThemeColors.key_sheetBackground))
    }

    fun fixNavigationBar(bgColor: Int) {
        drawNavigationBar = true
        drawDoubleNavigationBar = true
        scrollNavBar = true
        navBarColorKey = -1
        navBarColor = bgColor
        setOverlayNavBarColor(bgColor)
    }

    open fun setBackgroundColor(color: Int) {
        (shadowDrawable as? GradientDrawable)?.setColor(color)
    }

    fun setItems(itemTexts: Array<CharSequence>, icons: IntArray? = null, listener: OnClickListener?) {
        items = itemTexts
        itemIcons = icons
        itemClickListener = listener
    }

    fun setItemText(index: Int, text: CharSequence) {
        itemViews.getOrNull(index)?.textView?.text = text
    }

    fun setItemColor(index: Int, textColor: Int, iconColor: Int) {
        itemViews.getOrNull(index)?.let { cell ->
            cell.setTextColor(textColor)
            cell.setIconColor(iconColor)
        }
    }

    fun getItemViews(): ArrayList<BottomSheetCell> = itemViews

    fun getTitleView(): TextView? = titleView

    fun setCurrentPanTranslationY(y: Float) {
        currentPanTranslationY = y
        if (::container.isInitialized) container.invalidate()
    }

    fun transitionFromRight(value: Boolean) { transitionFromRight = value }

    fun setSnapPoints(vararg fractions: Float, initialIndex: Int = 0) {
        val cleaned = fractions
            .map { it.coerceIn(0.05f, 1f) }
            .toFloatArray()
            .also { it.sort() }
        snapPoints = cleaned
        initialSnapIndex = initialIndex.coerceIn(0, (cleaned.size - 1).coerceAtLeast(0))
        currentSnapIndex = initialSnapIndex
        if (::container.isInitialized) container.requestLayout()
    }

    fun snapTo(index: Int, animated: Boolean = true) {
        if (snapPoints.isEmpty()) return
        val idx = index.coerceIn(0, snapPoints.size - 1)
        currentSnapIndex = idx
        val target = snapTranslationY(idx)
        val cv = containerView ?: return
        if (animated) {
            ObjectAnimator.ofFloat(cv, View.TRANSLATION_Y, target).apply {
                duration = 250
                interpolator = CubicBezierInterpolator.DEFAULT
                addUpdateListener { onContainerViewTranslation() }
                start()
            }
        } else {
            cv.translationY = target
            onContainerViewTranslation()
        }
    }

    private fun snapTranslationY(index: Int): Float {
        if (snapPoints.isEmpty() || availableSheetHeight <= 0) return 0f
        val maxSnap = snapPoints.last()
        val f = snapPoints[index.coerceIn(0, snapPoints.size - 1)]
        return ((maxSnap - f) * availableSheetHeight)
    }

    fun hasSnapPoints(): Boolean = snapPoints.isNotEmpty()

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vc = ViewConfiguration.get(context)
        touchSlop = vc.scaledTouchSlop

        backgroundPaddingLeft = 0
        backgroundPaddingTop = 0
        shadowDrawable = GradientDrawable().apply {
            setColor(theme.getColor(ThemeColors.key_sheetBackground))
            cornerRadii = floatArrayOf(
                LayoutHelper.dp(14).toFloat(), LayoutHelper.dp(14).toFloat(),
                LayoutHelper.dp(14).toFloat(), LayoutHelper.dp(14).toFloat(),
                0f, 0f, 0f, 0f
            )
        }

        // --- Container (full-screen overlay) ---
        container = ContainerView(context)
        container.clipChildren = false
        container.clipToPadding = false
        container.background = backDrawable
        container.fitsSystemWindows = true
        container.setOnApplyWindowInsetsListener { v, insets ->
            val newTopInset = insets.systemWindowInsetTop
            if (newTopInset != 0 && statusBarHeight != newTopInset) {
                statusBarHeight = newTopInset
            }
            lastInsets = insets
            v.requestLayout()
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else insets.consumeSystemWindowInsets()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            container.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else {
            container.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        backDrawable.alpha = 0

        // --- containerView (content panel) ---
        containerView = FrameLayout(context).apply {
            background = shadowDrawable
            setPadding(
                backgroundPaddingLeft,
                if (applyTopPadding) LayoutHelper.dp(8) else 0,
                backgroundPaddingLeft,
                if (applyBottomPadding) LayoutHelper.dp(8) else 0
            )
            visibility = View.INVISIBLE
        }

        container.addView(containerView, LayoutHelper.createFrame(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM
        ))

        // --- Content layout ---
        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Handle bar
        val handleView = View(context).apply {
            background = GradientDrawable().apply {
                setColor(theme.getColor(ThemeColors.key_sheetHandle))
                cornerRadius = LayoutHelper.dp(2).toFloat()
            }
        }
        contentLayout!!.addView(handleView, LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = LayoutHelper.dp(10)
            bottomMargin = LayoutHelper.dp(10)
        })

        // Title
        titleText?.let { title ->
            titleView = TextView(context).apply {
                text = title
                setTextColor(theme.getColor(if (bigTitle) ThemeColors.key_dialogTextBlack else ThemeColors.key_sheetTitle))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (bigTitle) 20f else 16f)
                if (bigTitle) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(
                    LayoutHelper.dp(24),
                    LayoutHelper.dp(if (applyTopPadding) 4 else 0),
                    LayoutHelper.dp(24),
                    LayoutHelper.dp(12)
                )
                gravity = Gravity.START
                if (multipleLinesTitle) {
                    isSingleLine = false
                    maxLines = 5
                    ellipsize = TextUtils.TruncateAt.END
                } else {
                    setLines(1)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }
            }
            contentLayout!!.addView(titleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // Item cells
        items?.let { itemList ->
            for (i in itemList.indices) {
                val cell = BottomSheetCell(context).apply {
                    tag = i
                    setTextAndIcon(itemList[i], itemIcons?.getOrNull(i) ?: 0)
                    setOnClickListener { dismissWithButtonClick(i) }
                }
                contentLayout!!.addView(cell, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(48)
                ))
                itemViews.add(cell)
            }
        }

        // Custom view
        customView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            contentLayout!!.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (containerHeight == ViewGroup.LayoutParams.WRAP_CONTENT) LinearLayout.LayoutParams.WRAP_CONTENT
                else containerHeight
            ))
        }

        containerView!!.addView(contentLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        configureWindow()
    }

    private fun configureWindow() {
        window?.let { w ->
            w.setWindowAnimations(0) // no system animation
            w.setBackgroundDrawableResource(android.R.color.transparent)
            val lp = w.attributes
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.dimAmount = 0f // we control dim ourselves via backDrawable
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            lp.flags = lp.flags or (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            if (needFocusable) {
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            } else {
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }
            if (isFullscreen) {
                lp.flags = lp.flags or (WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            w.attributes = lp
            if (drawNavigationBar && navBarColor != 0) {
                w.navigationBarColor = navBarColor
            }
        }
    }

    // ─── Show / Dismiss ────────────────────────────────────────────────────

    override fun show() {
        super.show()
        dismissed = false
        openAnimationCompleted = false
        cancelSheetAnimation()

        containerView?.let { cv ->
            cv.measure(
                View.MeasureSpec.makeMeasureSpec(AndroidUtilities.displaySize.x + backgroundPaddingLeft * 2, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(AndroidUtilities.displaySize.y, View.MeasureSpec.AT_MOST)
            )
        }

        if (showWithoutAnimation) {
            backDrawable.alpha = if (dimBehind) dimAlpha else 0
            containerView?.translationY = 0f
            containerView?.visibility = View.VISIBLE
            onContainerViewTranslation()
            delegate?.onOpenAnimationStart()
            delegate?.onOpenAnimationEnd()
            onOpenAnimationEnd()
            openAnimationCompleted = true
            return
        }

        backDrawable.alpha = 0
        layoutCount = 2

        containerView?.translationY = (statusBarHeight +
                (containerView?.measuredHeight ?: AndroidUtilities.dp(300)) +
                (if (scrollNavBar) AndroidUtilities.navigationBarHeight.coerceAtMost(getBottomInsetInternal()).coerceAtLeast(0) else 0)
                ).toFloat()
        onContainerViewTranslation()

        val delay = if (openNoDelay) 0L else 150L
        startAnimationRunnable = Runnable {
            if (startAnimationRunnable != this@BottomSheet.startAnimationRunnable || dismissed) return@Runnable
            startAnimationRunnable = null
            startOpenAnimation()
        }
        AndroidUtilities.runOnUIThread(startAnimationRunnable!!, delay)
    }

    private fun startOpenAnimation() {
        if (dismissed) return
        containerView?.visibility = View.VISIBLE

        if (!onCustomOpenAnimation()) {
            beginHeavyOperationsAnimation()
            if (useHardwareLayer) {
                container.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            val cv = containerView ?: return
            if (transitionFromRight) {
                cv.translationX = LayoutHelper.dp(48).toFloat()
                cv.alpha = 0f
                cv.translationY = 0f
            } else {
                cv.translationY = (getContainerViewHeight() + keyboardHeight + LayoutHelper.dp(10) +
                        AndroidUtilities.navigationBarHeight.coerceAtMost(getBottomInsetInternal()).coerceAtLeast(0)
                        ).toFloat()
            }
            onContainerViewTranslation()

            currentSheetAnimationType = 1
            navigationBarAnimation?.cancel()
            navigationBarAnimation = ValueAnimator.ofFloat(navigationBarAlpha, 1f).apply {
                addUpdateListener {
                    navigationBarAlpha = it.animatedValue as Float
                    container.invalidate()
                }
            }

            currentSheetAnimation = AnimatorSet().apply {
                val animators = ArrayList<Animator>()
                animators.add(ObjectAnimator.ofFloat(cv, View.TRANSLATION_X, 0f))
                animators.add(ObjectAnimator.ofFloat(cv, View.ALPHA, 1f))
                val openTargetTy = if (snapPoints.isNotEmpty()) snapTranslationY(initialSnapIndex) else 0f
                animators.add(ObjectAnimator.ofFloat(cv, View.TRANSLATION_Y, openTargetTy).apply {
                    addUpdateListener { onContainerViewTranslation() }
                })
                animators.add(ObjectAnimator.ofInt(backDrawable, "alpha", if (dimBehind) dimAlpha else 0))
                navigationBarAnimation?.let { animators.add(it) }
                playTogether(animators)

                if (transitionFromRight) {
                    duration = 250
                    interpolator = CubicBezierInterpolator.DEFAULT
                } else {
                    duration = 400
                    interpolator = openInterpolator
                    startDelay = 20
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (currentSheetAnimation?.equals(animation) == true) {
                            currentSheetAnimation = null
                            currentSheetAnimationType = 0
                            finishHeavyOperationsAnimation()
                            openAnimationCompleted = true
                            onOpenAnimationEnd()
                            delegate?.onOpenAnimationEnd()
                            if (useHardwareLayer) {
                                container.setLayerType(View.LAYER_TYPE_NONE, null)
                            }
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (currentSheetAnimation?.equals(animation) == true) {
                            currentSheetAnimation = null
                            currentSheetAnimationType = 0
                            finishHeavyOperationsAnimation()
                        }
                    }
                })
                start()
            }
        }

        delegate?.onOpenAnimationStart()
    }

    protected open fun onOpenAnimationEnd() {}

    protected open fun onCustomOpenAnimation(): Boolean = false
    protected open fun onCustomCloseAnimation(): Boolean = false

    fun cancelSheetAnimation() {
        currentSheetAnimation?.cancel()
        currentSheetAnimation = null
        currentSheetAnimationType = 0
        finishHeavyOperationsAnimation()
    }

    override fun dismiss() {
        if (delegate?.canDismiss() == false) return
        if (dismissed) return
        dismissed = true
        onHideListener?.onDismiss(this)
        cancelSheetAnimation()
        onDismissAnimationStart()

        if (skipDismissAnimation) {
            AndroidUtilities.runOnUIThread { dismissInternal() }
        } else if (!allowCustomAnimation || !onCustomCloseAnimation()) {
            AndroidUtilities.hideKeyboard(container)
            beginHeavyOperationsAnimation()
            currentSheetAnimationType = 2
            navigationBarAnimation?.cancel()
            navigationBarAnimation = ValueAnimator.ofFloat(navigationBarAlpha, 0f).apply {
                addUpdateListener {
                    navigationBarAlpha = it.animatedValue as Float
                    container.invalidate()
                }
            }

            currentSheetAnimation = AnimatorSet().apply {
                val animators = ArrayList<Animator>()
                val cv = containerView
                if (cv != null) {
                    if (transitionFromRight) {
                        animators.add(ObjectAnimator.ofFloat(cv, View.TRANSLATION_X, LayoutHelper.dp(48).toFloat()))
                        animators.add(ObjectAnimator.ofFloat(cv, View.ALPHA, 0f))
                    } else {
                        animators.add(ObjectAnimator.ofFloat(cv, View.TRANSLATION_Y,
                            (getContainerViewHeight() + keyboardHeight + LayoutHelper.dp(10) +
                                    AndroidUtilities.navigationBarHeight.coerceAtMost(getBottomInsetInternal()).coerceAtLeast(0)
                                    ).toFloat()
                        ).apply {
                            addUpdateListener { onContainerViewTranslation() }
                        })
                    }
                }
                animators.add(ObjectAnimator.ofInt(backDrawable, "alpha", 0))
                navigationBarAnimation?.let { animators.add(it) }
                playTogether(animators)

                if (transitionFromRight) {
                    duration = 200
                    interpolator = CubicBezierInterpolator.DEFAULT
                } else {
                    duration = 250
                    interpolator = CubicBezierInterpolator.EASE_OUT
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (currentSheetAnimation?.equals(animation) == true) {
                            currentSheetAnimation = null
                            currentSheetAnimationType = 0
                            finishHeavyOperationsAnimation()
                            AndroidUtilities.runOnUIThread { dismissInternal() }
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (currentSheetAnimation?.equals(animation) == true) {
                            currentSheetAnimation = null
                            currentSheetAnimationType = 0
                            finishHeavyOperationsAnimation()
                        }
                    }
                })
                start()
            }
        }
    }

    fun dismissWithoutAnimation() {
        skipDismissAnimation = true
        dismiss()
    }

    protected open fun onDismissAnimationStart() {}

    fun dismissWithButtonClick(which: Int) {
        if (dismissed) return
        dismissed = true
        cancelSheetAnimation()
        currentSheetAnimationType = 2

        val cv = containerView
        if (cv == null) {
            itemClickListener?.onClick(this@BottomSheet, which)
            onHideListener?.onDismiss(this@BottomSheet)
            AndroidUtilities.runOnUIThread { dismissInternal() }
            return
        }

        beginHeavyOperationsAnimation()
        currentSheetAnimation = AnimatorSet().apply {
            val translationYAnim = ObjectAnimator.ofFloat(cv, View.TRANSLATION_Y,
                (getContainerViewHeight() + keyboardHeight + LayoutHelper.dp(10) +
                        AndroidUtilities.navigationBarHeight.coerceAtMost(getBottomInsetInternal()).coerceAtLeast(0)
                        ).toFloat()
            ).apply {
                addUpdateListener { onContainerViewTranslation() }
            }
            playTogether(
                translationYAnim,
                ObjectAnimator.ofInt(backDrawable, "alpha", 0)
            )
            duration = 180
            interpolator = CubicBezierInterpolator.EASE_OUT
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (currentSheetAnimation?.equals(animation) == true) {
                        currentSheetAnimation = null
                        currentSheetAnimationType = 0
                        finishHeavyOperationsAnimation()
                        itemClickListener?.onClick(this@BottomSheet, which)
                        AndroidUtilities.runOnUIThread {
                            onHideListener?.onDismiss(this@BottomSheet)
                            dismissInternal()
                        }
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (currentSheetAnimation?.equals(animation) == true) {
                        currentSheetAnimation = null
                        currentSheetAnimationType = 0
                        finishHeavyOperationsAnimation()
                    }
                }
            })
            start()
        }
    }

    fun dismissInternal() {
        finishHeavyOperationsAnimation()
        try {
            super.dismiss()
        } catch (_: Exception) {}
    }

    private fun beginHeavyOperationsAnimation() {
        heavyOperationsAnimationPointer = globalNotificationCenter.setAnimationInProgress(
            heavyOperationsAnimationPointer,
            null
        )
    }

    private fun finishHeavyOperationsAnimation() {
        if (heavyOperationsAnimationPointer != 0) {
            globalNotificationCenter.onAnimationFinish(heavyOperationsAnimationPointer)
            heavyOperationsAnimationPointer = 0
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (dismissed) return false
        return super.dispatchTouchEvent(ev)
    }

    fun getContainerViewHeight(): Int = containerView?.measuredHeight ?: 0

    fun backDrawableValue(): ColorDrawable = backDrawable

    fun backgroundPaddingTopValue(): Int = backgroundPaddingTop
    fun backgroundPaddingLeftValue(): Int = backgroundPaddingLeft

    private fun getBottomInsetInternal(): Int = bottomInset

    protected fun bottomInsetValue(): Int = bottomInset

    protected fun leftInsetValue(): Int = leftInset
    protected fun rightInsetValue(): Int = rightInset

    // ─── Touch outside ─────────────────────────────────────────────────────

    protected open fun canDismissWithSwipe(): Boolean = canDismissWithSwipe
    protected open fun canDismissWithTouchOutside(): Boolean = canDismissWithTouchOutside

    protected open fun isTouchOutside(x: Float, y: Float): Boolean {
        val cv = containerView ?: return false
        return y < cv.top || x < cv.left || x > cv.right
    }

    protected open fun onDismissWithTouchOutside() { dismiss() }

    protected open fun onContainerTouchEvent(ev: MotionEvent): Boolean = false
    protected open fun onScrollUp(translationY: Float): Boolean = false
    protected open fun onScrollUpEnd(translationY: Float) {}
    protected open fun onScrollUpBegin(translationY: Float) {}
    protected open fun onContainerViewTranslation() {}
    protected open fun onSwipeStarts() {}
    protected open fun canSwipeToBack(ev: MotionEvent?): Boolean = false

    // ─── ContainerView (NestedScrolling aware) ─────────────────────────────

    inner class ContainerView(context: Context) : FrameLayout(context), NestedScrollingParent3 {

        private val nestedHelper = NestedScrollingParentHelper(this)
        private var velocityTracker: VelocityTracker? = null
        private var startedTrackingX = 0
        private var startedTrackingY = 0
        private var startedTrackingPointerId = -1
        private var maybeStartTracking = false
        private var startedTracking = false
        private var currentAnimation: AnimatorSet? = null
        private val rect = Rect()
        private val backgroundPaint = Paint()
        private var swipeY = 0f
        private var swipeBackX = 0f
        private var allowedSwipeToBack = false

        init {
            setWillNotDraw(false)
        }

        // ── NestedScrollingParent3 ──

        override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
            return (nestedScrollChild == null || child == nestedScrollChild) &&
                    !dismissed && allowNestedScroll &&
                    (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0 &&
                    !canDismissWithSwipe()
        }

        override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
            nestedHelper.onNestedScrollAccepted(child, target, axes, type)
            if (dismissed || !allowNestedScroll) return
            cancelCurrentAnimation()
        }

        override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
            if (dismissed || !allowNestedScroll) return
            cancelCurrentAnimation()
            val cv = containerView ?: return
            val currentTranslation = cv.translationY
            if (currentTranslation > 0 && dy > 0) {
                var newTy = currentTranslation - dy
                consumed[1] = dy
                if (newTy < 0) newTy = 0f
                cv.translationY = newTy
                onContainerViewTranslation()
                this@ContainerView.invalidate()
            }
        }

        override fun onNestedScroll(
            target: View, dxConsumed: Int, dyConsumed: Int,
            dxUnconsumed: Int, dyUnconsumed: Int, type: Int, consumed: IntArray
        ) {
            if (dismissed || !allowNestedScroll) return
            cancelCurrentAnimation()
            val cv = containerView ?: return
            if (dyUnconsumed != 0) {
                var currentTranslation = cv.translationY
                currentTranslation -= dyUnconsumed
                if (currentTranslation < 0) currentTranslation = 0f
                cv.translationY = currentTranslation
                onContainerViewTranslation()
                this@ContainerView.invalidate()
            }
        }

        override fun onNestedScroll(
            target: View, dxConsumed: Int, dyConsumed: Int,
            dxUnconsumed: Int, dyUnconsumed: Int, type: Int
        ) {
            onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, IntArray(2))
        }

        override fun onStopNestedScroll(target: View, type: Int) {
            nestedHelper.onStopNestedScroll(target, type)
            if (dismissed || !allowNestedScroll) return
            checkDismiss(0f, 0f)
        }

        override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean = false

        override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean = false

        // Legacy NestedScrollingParent methods
        override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
            onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)

        override fun onNestedScrollAccepted(child: View, target: View, axes: Int) =
            onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)

        override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) =
            onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)

        override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int) =
            onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH)

        override fun onStopNestedScroll(target: View) =
            onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)

        override fun getNestedScrollAxes(): Int = nestedHelper.nestedScrollAxes

        // ── Check dismiss ──

        private fun checkDismiss(velX: Float, velY: Float) {
            val cv = containerView ?: return
            val translationY = cv.translationY

            if (snapPoints.isNotEmpty()) {
                val lowestTy = snapTranslationY(0)
                val dismissOverdrag = AndroidUtilities.getPixelsInCM(0.8f, false).toFloat()
                val shouldDismiss = (translationY > lowestTy + dismissOverdrag) ||
                        (velY > 3500 && translationY >= lowestTy - LayoutHelper.dp(40).toFloat())
                if (shouldDismiss) {
                    maybeStartTracking = false
                    val allowOld = allowCustomAnimation
                    allowCustomAnimation = false
                    useFastDismiss = true
                    dismiss()
                    allowCustomAnimation = allowOld
                    return
                }
                val predictedTy = (translationY + velY * 0.15f).coerceIn(0f, lowestTy)
                var bestIdx = 0
                var bestDist = Float.MAX_VALUE
                for (i in snapPoints.indices) {
                    val ty = snapTranslationY(i)
                    val d = Math.abs(predictedTy - ty)
                    if (d < bestDist) { bestDist = d; bestIdx = i }
                }
                currentSnapIndex = bestIdx
                val targetTy = snapTranslationY(bestIdx)
                maybeStartTracking = false
                beginHeavyOperationsAnimation()
                currentAnimation = AnimatorSet().apply {
                    val invalidator = ValueAnimator.ofFloat(0f, 1f).apply {
                        addUpdateListener {
                            this@ContainerView.invalidate()
                            onContainerViewTranslation()
                        }
                    }
                    playTogether(
                        ObjectAnimator.ofFloat(cv, "translationY", targetTy),
                        invalidator
                    )
                    duration = 250
                    interpolator = CubicBezierInterpolator.DEFAULT
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (currentAnimation?.equals(animation) == true) {
                                currentAnimation = null
                                finishHeavyOperationsAnimation()
                                swipeY = targetTy
                            }
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            if (currentAnimation?.equals(animation) == true) {
                                currentAnimation = null
                                finishHeavyOperationsAnimation()
                            }
                        }
                    })
                    start()
                }
                return
            }

            val backAnimation = (translationY < AndroidUtilities.getPixelsInCM(0.8f, false)
                    && (velY < 3500 || Math.abs(velY) < Math.abs(velX)))
                    || (velY < 0 && Math.abs(velY) >= 3500)

            if (!backAnimation) {
                val allowOld = allowCustomAnimation
                allowCustomAnimation = false
                useFastDismiss = true
                dismiss()
                allowCustomAnimation = allowOld
            } else {
                maybeStartTracking = false
                beginHeavyOperationsAnimation()
                currentAnimation = AnimatorSet().apply {
                    val invalidator = ValueAnimator.ofFloat(0f, 1f).apply {
                        addUpdateListener {
                            this@ContainerView.invalidate()
                            onContainerViewTranslation()
                        }
                    }
                    playTogether(
                        ObjectAnimator.ofFloat(cv, "translationY", 0f),
                        invalidator
                    )
                    duration = (250 * (translationY.coerceAtLeast(0f) / AndroidUtilities.getPixelsInCM(0.8f, false))).toLong()
                    interpolator = CubicBezierInterpolator.DEFAULT
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (currentAnimation?.equals(animation) == true) {
                                currentAnimation = null
                                finishHeavyOperationsAnimation()
                            }
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            if (currentAnimation?.equals(animation) == true) {
                                currentAnimation = null
                                finishHeavyOperationsAnimation()
                            }
                        }
                    })
                    start()
                }
            }
        }

        private fun cancelCurrentAnimation() {
            currentAnimation?.cancel()
            currentAnimation = null
            onSwipeStarts()
        }

        // ── Touch event processing ──

        fun processTouchEvent(ev: MotionEvent?, intercept: Boolean): Boolean {
            if (dismissed) return false
            if (ev != null && onContainerTouchEvent(ev)) return true

            if (canDismissWithTouchOutside() && ev != null
                && (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE)
                && !startedTracking && !maybeStartTracking && ev.pointerCount == 1
            ) {
                startedTrackingX = ev.x.toInt()
                startedTrackingY = ev.y.toInt()
                if (isTouchOutside(startedTrackingX.toFloat(), startedTrackingY.toFloat())) {
                    if (openAnimationCompleted) {
                        onDismissWithTouchOutside()
                    }
                    return true
                }
                swipeY = containerView?.translationY ?: 0f
                onScrollUpBegin(swipeY)
                startedTrackingPointerId = ev.getPointerId(0)
                maybeStartTracking = true
                cancelCurrentAnimation()
                velocityTracker?.clear()
            } else if (canDismissWithSwipe() && ev != null
                && ev.action == MotionEvent.ACTION_MOVE
                && ev.getPointerId(0) == startedTrackingPointerId
            ) {
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
                val dx = Math.abs(ev.x.toInt() - startedTrackingX).toFloat()
                val dy = (ev.y.toInt() - startedTrackingY).toFloat()
                val canScrollUp = onScrollUp(swipeY + dy)
                val snapMode = snapPoints.isNotEmpty()
                val triggerDy = if (snapMode) Math.abs(dy) else dy

                if (!disableScroll && maybeStartTracking && !startedTracking
                    && triggerDy > 0 && Math.abs(dy) / 3.0f > dx && Math.abs(dy) >= touchSlop
                ) {
                    startedTrackingY = ev.y.toInt()
                    maybeStartTracking = false
                    startedTracking = true
                    requestDisallowInterceptTouchEvent(true)
                } else if (startedTracking) {
                    swipeY += dy
                    if (snapMode) {
                        val lowestTy = snapTranslationY(0)
                        val dismissOverdrag = AndroidUtilities.getPixelsInCM(1.5f, false).toFloat()
                        swipeY = swipeY.coerceIn(0f, lowestTy + dismissOverdrag)
                        containerView?.translationY = swipeY
                    } else {
                        if (!canScrollUp) swipeY = swipeY.coerceAtLeast(0f)
                        containerView?.translationY = swipeY.coerceAtLeast(0f)
                    }
                    onContainerViewTranslation()
                    startedTrackingY = ev.y.toInt()
                    this@ContainerView.invalidate()
                }
            } else if (ev == null ||
                (ev.getPointerId(0) == startedTrackingPointerId
                        && (ev.action == MotionEvent.ACTION_CANCEL
                        || ev.action == MotionEvent.ACTION_UP
                        || ev.action == MotionEvent.ACTION_POINTER_UP))
            ) {
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
                velocityTracker?.computeCurrentVelocity(1000)
                onScrollUpEnd(swipeY)
                if (startedTracking || swipeY > 0) {
                    checkDismiss(velocityTracker?.xVelocity ?: 0f, velocityTracker?.yVelocity ?: 0f)
                } else {
                    maybeStartTracking = false
                }
                startedTracking = false
                velocityTracker?.recycle()
                velocityTracker = null
                startedTrackingPointerId = -1
            }

            return (!intercept && maybeStartTracking) || startedTracking || !(canDismissWithSwipe() || canSwipeToBack(ev))
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (canDismissWithSwipe() || canSwipeToBack(event)) {
                return processTouchEvent(event, true)
            }
            return super.onInterceptTouchEvent(event)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            return processTouchEvent(ev, false)
        }

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            if (maybeStartTracking && !startedTracking) {
                onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0))
            }
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }

        override fun hasOverlappingRendering(): Boolean = false

        // ── Measure / Layout ──

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var width = MeasureSpec.getSize(widthMeasureSpec)
            var height = MeasureSpec.getSize(heightMeasureSpec)

            // Keyboard detection
            val rootView = rootView
            getWindowVisibleDisplayFrame(rect)
            val oldKeyboardHeight = keyboardHeight
            if (rect.bottom != 0 && rect.top != 0) {
                val usableViewHeight = rootView.height - (if (rect.top != 0) statusBarHeight else 0)
                keyboardHeight = Math.max(0, usableViewHeight - (rect.bottom - rect.top))
                if (keyboardHeight < LayoutHelper.dp(20)) {
                    keyboardHeight = 0
                } else {
                    lastKeyboardHeight = keyboardHeight
                }
            } else {
                keyboardHeight = 0
            }
            keyboardVisible = keyboardHeight > LayoutHelper.dp(20)

            if (lastInsets != null) {
                bottomInset = lastInsets!!.systemWindowInsetBottom
                leftInset = lastInsets!!.systemWindowInsetLeft
                rightInset = lastInsets!!.systemWindowInsetRight
                if (keyboardVisible && rect.bottom != 0 && rect.top != 0) {
                    bottomInset -= keyboardHeight
                }
                if (!drawNavigationBar) {
                    height -= this@BottomSheet.bottomInset
                }
            }

            setMeasuredDimension(width, height + (if (lastInsets != null && !drawNavigationBar) this@BottomSheet.bottomInset else 0))

            if (lastInsets != null) {
                width -= this@BottomSheet.rightInset + this@BottomSheet.leftInset
            }
            isPortrait = width < height

            availableSheetHeight = height
            containerView?.let { cv ->
                val widthSpec = if (!useFullWidth) {
                    val sheetWidth = getBottomSheetWidth(isPortrait, width, height)
                    MeasureSpec.makeMeasureSpec(sheetWidth + backgroundPaddingLeft * 2, MeasureSpec.EXACTLY)
                } else {
                    MeasureSpec.makeMeasureSpec(width + backgroundPaddingLeft * 2, MeasureSpec.EXACTLY)
                }
                val heightSpec = if (snapPoints.isNotEmpty()) {
                    MeasureSpec.makeMeasureSpec((height * snapPoints.last()).toInt(), MeasureSpec.EXACTLY)
                } else {
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
                }
                cv.measure(widthSpec, heightSpec)
            }

            val childCount = childCount
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE || child == containerView) continue
                measureChildWithMargins(
                    child,
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), 0,
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY), 0
                )
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            layoutCount--
            val layoutBottom = if (lastInsets != null && !drawNavigationBar) {
                if (this@BottomSheet.needFocusable && this@BottomSheet.keyboardVisible) {
                    bottom - (lastInsets!!.systemWindowInsetBottom - this@BottomSheet.bottomInset)
                } else {
                    bottom - this@BottomSheet.bottomInset
                }
            } else {
                bottom
            }

            containerView?.let { cv ->
                val t = (layoutBottom - top) - cv.measuredHeight
                val l = ((right - left) - cv.measuredWidth) / 2 + this@BottomSheet.leftInset
                cv.layout(l, t, l + cv.measuredWidth, t + cv.measuredHeight)
            }

            val count = childCount
            for (i in 0 until count) {
                val child = getChildAt(i)
                if (child.visibility == GONE || child == containerView) continue
                val lp = child.layoutParams as LayoutParams
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                var gravity = lp.gravity
                if (gravity == -1) gravity = Gravity.TOP or Gravity.LEFT
                val absoluteGravity = gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                val verticalGravity = gravity and Gravity.VERTICAL_GRAVITY_MASK
                val childLeft = when (absoluteGravity) {
                    Gravity.CENTER_HORIZONTAL -> (right - left - childWidth) / 2 + lp.leftMargin - lp.rightMargin
                    Gravity.RIGHT -> right - childWidth - lp.rightMargin
                    else -> lp.leftMargin
                } + this@BottomSheet.leftInset
                val childTop = when (verticalGravity) {
                    Gravity.CENTER_VERTICAL -> (layoutBottom - top - childHeight) / 2 + lp.topMargin - lp.bottomMargin
                    Gravity.BOTTOM -> (layoutBottom - top) - childHeight - lp.bottomMargin
                    else -> lp.topMargin
                }
                child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
            }

            if (layoutCount == 0 && startAnimationRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(startAnimationRunnable!!)
                startAnimationRunnable?.run()
                startAnimationRunnable = null
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            // Draw navigation bar color if needed
            if (drawNavigationBar && bottomInset != 0) {
                val navBarHeight = if (drawNavigationBar) this@BottomSheet.bottomInset else 0
                if (navBarColorKey >= 0) {
                    backgroundPaint.color = theme.getColor(navBarColorKey)
                } else if (navBarColor != 0) {
                    backgroundPaint.color = navBarColor
                } else {
                    backgroundPaint.color = 0xff000000.toInt()
                }
                val wasAlpha = backgroundPaint.alpha
                backgroundPaint.alpha = (wasAlpha * navigationBarAlpha).toInt()
                val cv = containerView
                if (cv != null) {
                    canvas.drawRect(
                        cv.left.toFloat() + backgroundPaddingLeft,
                        (measuredHeight - navBarHeight).toFloat(),
                        cv.right.toFloat() - backgroundPaddingLeft,
                        measuredHeight.toFloat(),
                        backgroundPaint
                    )
                }
                backgroundPaint.alpha = wasAlpha
            }
        }
    }

    protected open fun getBottomSheetWidth(isPortrait: Boolean, width: Int, height: Int): Int {
        return if (isPortrait) width else Math.max(width * 0.8f, Math.min(LayoutHelper.dp(480).toFloat(), width.toFloat())).toInt()
    }

    // ─── Navigation bar color blending ──────────────────────────────────────

    fun getNavigationBarColor(defaultColor: Int): Int {
        val cv = containerView ?: return defaultColor
        val fullHeight = (getContainerViewHeight() + keyboardHeight + LayoutHelper.dp(10) +
                (if (scrollNavBar) AndroidUtilities.navigationBarHeight.coerceAtMost(getBottomInsetInternal()).coerceAtLeast(0) else 0)).toFloat()
        val t = if (fullHeight > 0) (1f - cv.translationY / fullHeight).coerceIn(0f, 1f) else 0f
        return ColorUtils.blendARGB(defaultColor, navBarColor, t)
    }

    // ─── Builder ────────────────────────────────────────────────────────────

    class Builder(private val context: Context, needFocusable: Boolean = false) {
        private val sheet = BottomSheet(context, needFocusable)

        init {
            sheet.fixNavigationBar()
        }

        fun setTitle(title: CharSequence?): Builder { sheet.setTitle(title); return this }
        fun setTitle(title: CharSequence?, big: Boolean): Builder { sheet.setTitle(title, big); return this }
        fun setTitleMultipleLines(allow: Boolean): Builder { sheet.setTitleMultipleLines(allow); return this }
        fun setCustomView(view: View?): Builder { sheet.setCustomView(view); return this }
        fun getCustomView(): View? = sheet.getCustomView()
        fun setApplyTopPadding(value: Boolean): Builder { sheet.setApplyTopPadding(value); return this }
        fun setApplyBottomPadding(value: Boolean): Builder { sheet.setApplyBottomPadding(value); return this }
        fun setCanDismissWithSwipe(value: Boolean): Builder { sheet.setCanDismissWithSwipe(value); return this }
        fun setAllowNestedScroll(value: Boolean): Builder { sheet.setAllowNestedScroll(value); return this }
        fun setShowWithoutAnimation(value: Boolean): Builder { sheet.setShowWithoutAnimation(value); return this }
        fun setOnHideListener(listener: DialogInterface.OnDismissListener?): Builder { sheet.setOnHideListener(listener); return this }
        fun setOverlayNavBarColor(color: Int): Builder { sheet.setOverlayNavBarColor(color); return this }
        fun setDimBehind(value: Boolean): Builder { sheet.setDimBehind(value); return this }
        fun setItems(items: Array<CharSequence>, listener: OnClickListener?): Builder { sheet.setItems(items, null, listener); return this }
        fun setItems(items: Array<CharSequence>, icons: IntArray?, listener: OnClickListener?): Builder { sheet.setItems(items, icons, listener); return this }
        fun setDelegate(delegate: BottomSheetDelegateInterface?): Builder { sheet.delegate = delegate; return this }
        fun setUseHardwareLayer(value: Boolean): Builder { sheet.setUseHardwareLayer(value); return this }
        fun setUseFullWidth(value: Boolean): Builder { sheet.setUseFullWidth(value); return this }
        fun setUseFullscreen(value: Boolean): Builder { sheet.setUseFullscreen(value); return this }
        fun setOnPreDismissListener(listener: DialogInterface.OnDismissListener?): Builder { sheet.setOnHideListener(listener); return this }
        fun setSnapPoints(vararg fractions: Float, initialIndex: Int = 0): Builder {
            sheet.setSnapPoints(*fractions, initialIndex = initialIndex)
            return this
        }
        fun getDismissRunnable(): Runnable = sheet.dismissRunnable

        fun create(): BottomSheet = sheet
        fun show(): BottomSheet { sheet.show(); return sheet }
    }
}
