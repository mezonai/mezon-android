package com.mezon.mobile.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Menu
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.view.KeyEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.mezon.mobile.core.AndroidUtilities

class ActionBarLayout(context: Context, private val activity: Activity) :
    FrameLayout(context), INavigationLayout {

    inner class LayoutContainer(context: Context) : FrameLayout(context) {

        var isLayoutToIgnore = false

        override fun requestLayout() {
            if (isLayoutToIgnore) return
            super.requestLayout()
        }

        var fragmentPanTranslationOffset = 0
            set(value) {
                field = value
                invalidate()
            }
        var drawNavigationBar = false
        private val backgroundPaint = Paint()
        private var backgroundColor = 0
        private var wasPortrait = false
        var isKeyboardVisible = false
            private set
        var keyboardHeight = 0
            private set
        private val visibleRect = Rect()

        init {
            setWillNotDraw(false)
        }

        override fun setTranslationX(translationX: Float) {
            val invalidateParent = getTranslationX() != translationX
            super.setTranslationX(translationX)
            if (invalidateParent) this@ActionBarLayout.invalidate()
        }

        override fun setAlpha(alpha: Float) {
            val invalidateParent = getAlpha() != alpha
            super.setAlpha(alpha)
            if (invalidateParent) this@ActionBarLayout.invalidate()
        }

        override fun hasOverlappingRendering(): Boolean = Build.VERSION.SDK_INT >= 28

        override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
            val tX = this@ActionBarLayout.innerTranslationX
            if (this === containerViewBack && tX > 0f) {
                canvas.save()
                canvas.clipRect(0f, 0f, tX + 1f, height.toFloat())
                val result = super.drawChild(canvas, child, drawingTime)
                canvas.restore()
                return result
            }
            val result = super.drawChild(canvas, child, drawingTime)
            if (child !is android.widget.Toolbar) {
                val lastFragment = fragmentStack.lastOrNull()
                if (lastFragment != null) {
                    val headerShadow = headerShadowDrawable
                    if (headerShadow != null) {
                        val actionBar = lastFragment.actionBar
                        if (actionBar != null && actionBar.visibility == VISIBLE) {
                            val abHeight = actionBar.measuredHeight
                            val abY = actionBar.y.toInt()
                            headerShadow.setBounds(0, abY + abHeight, measuredWidth, abY + abHeight + headerShadow.intrinsicHeight)
                            headerShadow.draw(canvas)
                        }
                    }
                }
            }
            return result
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (isLayoutToIgnore && measuredWidth > 0 && measuredHeight > 0) {
                setMeasuredDimension(measuredWidth, measuredHeight)
                return
            }
            val width = MeasureSpec.getSize(widthMeasureSpec)
            var height = MeasureSpec.getSize(heightMeasureSpec)
            val isPortrait = height > width
            if (wasPortrait != isPortrait && inPreviewMode) {
                finishPreviewFragment()
            }
            wasPortrait = isPortrait

            val rootView = rootView
            if (rootView != null) {
                rootView.getWindowVisibleDisplayFrame(visibleRect)
                val usableHeight = rootView.height -
                    (if (visibleRect.top != 0) AndroidUtilities.statusBarHeight else 0) -
                    AndroidUtilities.getViewInset(rootView)
                isKeyboardVisible = usableHeight - (visibleRect.bottom - visibleRect.top) > 0
            }

            checkWaitingForKeyboardClose()

            var actionBarHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                if (child is com.mezon.mobile.ui.cells.ActionBarView) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.UNSPECIFIED)
                    )
                    actionBarHeight = child.measuredHeight
                    break
                }
            }

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE || child is com.mezon.mobile.ui.cells.ActionBarView) continue
                if (actionBarHeight > 0) {
                    measureChildWithMargins(child, widthMeasureSpec, 0,
                        MeasureSpec.makeMeasureSpec(height - actionBarHeight, MeasureSpec.EXACTLY), 0)
                } else {
                    measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
                }
            }

            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (isLayoutToIgnore) return
            val count = childCount
            var actionBarHeight = 0
            for (i in 0 until count) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                if (child is com.mezon.mobile.ui.cells.ActionBarView) {
                    child.layout(0, 0, child.measuredWidth, child.measuredHeight)
                    actionBarHeight = child.measuredHeight
                    break
                }
            }
            for (i in 0 until count) {
                val child = getChildAt(i)
                if (child.visibility == GONE || child is com.mezon.mobile.ui.cells.ActionBarView) continue
                val lp = child.layoutParams as FrameLayout.LayoutParams
                child.layout(
                    lp.leftMargin,
                    lp.topMargin + actionBarHeight,
                    lp.leftMargin + child.measuredWidth,
                    lp.topMargin + actionBarHeight + child.measuredHeight
                )
            }
        }

        fun addViewWithInsets(child: View) {
            addView(child)
            androidx.core.view.ViewCompat.requestApplyInsets(this)
        }
    }

    var containerView = LayoutContainer(context)
    var containerViewBack = LayoutContainer(context)

    private val fragmentStack = ArrayList<BaseFragment>()
    private var transitionAnimationInProgress = false
    private var animationInProgress = false
    var innerTranslationX = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val slideInterpolator = DecelerateInterpolator(1.5f)
    private val openAnimDuration = 150L
    private val openSlideDistance = LayoutHelper.dp(48).toFloat()

    private var delegate: INavigationLayout.INavigationLayoutDelegate? = null
    private var fragmentStackChangedListener: Runnable? = null
    private var drawerLayout: DrawerLayoutContainer? = null

    private var maybeStartTracking = false
    private var startedTracking = false
    private var beginTrackingSent = false
    private var startedTrackingX = 0
    private var startedTrackingY = 0
    private var startedTrackingPointerId = -1
    private var velocityTracker: VelocityTracker? = null
    private var backAnimator: ValueAnimator? = null

    private var onBackInvokedCallback: Any? = null

    private var headerShadowDrawable: Drawable? = null

    private var waitingForKeyboardCloseRunnable: Runnable? = null

    private var rebuildAfterAnimation = false
    private var rebuildLastAfterAnimation = false
    private var showLastAfterAnimation = false

    private var inBubbleMode = false
    private var inPreviewMode = false
    private var removeActionBarExtraHeight = false
    private var isSheetValue = false
    private var windowRef: Window? = null
    private var useAlphaAnimations = false
    private var backgroundView: View? = null
    private var themeAnimationValue = 1f
    private var isLayersLayout = false

    private lateinit var _themeColors: ThemeColors

    init {
        addView(containerViewBack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(containerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        containerViewBack.visibility = INVISIBLE

        headerShadowDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x1A000000, 0x00000000)
        ).apply { setSize(0, AndroidUtilities.getShadowHeight()) }

        registerBackCallback()
    }

    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { onBackPressed() }
            onBackInvokedCallback = callback
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
            )
        }
    }

    fun unregisterBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (onBackInvokedCallback as? OnBackInvokedCallback)?.let {
                activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
            onBackInvokedCallback = null
        }
    }

    fun setDependencies(themeColors: ThemeColors, notificationCenter: NotificationCenter) {
        _themeColors = themeColors
    }

    // ── INavigationLayout — required overrides ──────────────────────────────

    override fun setDelegate(delegate: INavigationLayout.INavigationLayoutDelegate) {
        this.delegate = delegate
    }

    override fun setFragmentStack(stack: List<BaseFragment>) {
        fragmentStack.clear()
        fragmentStack.addAll(stack)
    }

    override fun setFragmentStackChangedListener(listener: Runnable?) {
        fragmentStackChangedListener = listener
    }

    private fun notifyStackChanged() {
        fragmentStackChangedListener?.run()
        delegate?.onFragmentStackChanged(this)
    }

    override fun getView(): ViewGroup = this
    override fun getParentActivity(): Activity = activity
    override fun getFragmentStack(): List<BaseFragment> = fragmentStack
    override fun getLastFragment(): BaseFragment? = fragmentStack.lastOrNull()
    override fun isTransitionAnimationInProgress(): Boolean = transitionAnimationInProgress || animationInProgress
    override fun checkTransitionAnimation(): Boolean = transitionAnimationInProgress || animationInProgress
    override fun isSwipeInProgress(): Boolean = startedTracking
    override fun resumeDelayedFragmentAnimation() {}
    override fun allowSwipe(): Boolean = true

    override fun isInPassivePreviewMode(): Boolean = false
    override fun setInBubbleMode(bubbleMode: Boolean) { inBubbleMode = bubbleMode }
    override fun isInBubbleMode(): Boolean = inBubbleMode
    override fun isInPreviewMode(): Boolean = inPreviewMode
    override fun isPreviewOpenAnimationInProgress(): Boolean = false
    override fun movePreviewFragment(dy: Float) {}
    override fun expandPreviewFragment() {}
    override fun finishPreviewFragment() {}
    override fun setFragmentPanTranslationOffset(offset: Int) {
        containerView.fragmentPanTranslationOffset = offset
    }
    override fun getOverlayContainerView(): FrameLayout? = null
    override fun setHighlightActionButtons(highlight: Boolean) {}
    override fun getCurrentPreviewFragmentAlpha(): Float = 0f
    override fun drawCurrentPreviewFragment(canvas: Canvas, foregroundDrawable: Drawable?) {}
    override fun drawHeaderShadow(canvas: Canvas, alpha: Int, y: Int) {
        if (!SharedConfig.drawActionBarShadow || headerShadowDrawable == null || alpha <= 0) return
        headerShadowDrawable!!.alpha = alpha
        headerShadowDrawable!!.setBounds(0, y, measuredWidth, y + headerShadowDrawable!!.intrinsicHeight)
        headerShadowDrawable!!.draw(canvas)
    }
    override fun setRemoveActionBarExtraHeight(removeExtraHeight: Boolean) { removeActionBarExtraHeight = removeExtraHeight }
    override fun setTitleOverlayText(title: String?, titleId: Int, action: Runnable?) {}
    override fun setNavigationBarColor(color: Int) {}
    override fun setIsSheet(isSheet: Boolean) { isSheetValue = isSheet }
    override fun isSheet(): Boolean = isSheetValue
    override fun updateTitleOverlay() {}
    override fun setWindow(window: Window?) { windowRef = window }

    override fun getDrawerLayoutContainer(): DrawerLayoutContainer? = drawerLayout
    override fun setDrawerLayoutContainer(container: DrawerLayoutContainer) { drawerLayout = container }

    override fun setUseAlphaAnimations(value: Boolean) { useAlphaAnimations = value }
    fun setIsLayersLayout() { isLayersLayout = true }
    override fun setBackgroundView(view: View?) { backgroundView = view }
    override fun getWindow(): Window? = windowRef

    fun getThemeAnimationValue(): Float = themeAnimationValue
    fun setThemeAnimationValue(value: Float) { themeAnimationValue = value }

    override fun getBottomTabsHeight(animated: Boolean): Int = 0

    override fun removeAllFragments() {
        for (i in fragmentStack.indices.reversed()) {
            val f = fragmentStack[i]
            f.onPause()
            f.onFragmentDestroy()
            f.parentLayout = null
        }
        fragmentStack.clear()
        notifyStackChanged()
    }

    override fun bringToFront(i: Int) {
        if (i < 0 || i >= fragmentStack.size) return
        val fragment = fragmentStack.removeAt(i)
        fragmentStack.add(fragment)
        notifyStackChanged()
    }

    fun parentDraw(parent: View, canvas: Canvas) {
        if (transitionAnimationInProgress || animationInProgress) {
            parent.invalidate()
        }
    }

    override fun onPause() {
        getLastFragment()?.let { if (!it.isPaused) it.onPause() }
    }

    override fun onResume() {
        getLastFragment()?.let { if (it.isPaused) it.onResume() }
    }

    override fun onBackPressed() {
        onBackPressedInternal()
    }

    override fun onUserLeaveHint() {
        getLastFragment()?.onUserLeaveHint()
    }

    override fun onLowMemory() {
        fragmentStack.forEach { it.onLowMemory() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        AndroidUtilities.checkDisplaySize(context, newConfig)
        for (fragment in fragmentStack) {
            fragment.onConfigurationChanged(newConfig)
        }
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent?): Boolean {
        if (event != null && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (delegate?.onPreIme() == true) return true
        }
        return super.dispatchKeyEventPreIme(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val last = getLastFragment()
            val actionBar = last?.actionBar
            if (actionBar != null) {
                val menu = actionBar.menu
                if (menu != null && menu.getItemsCount() > 0) {
                    actionBar.actionBarMenuOnItemClick?.let {
                        if (it.canOpenMenu()) {
                            menu.getItemAt(menu.getItemsCount() - 1)?.performClick()
                        }
                    }
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterBackCallback()
        waitingForKeyboardCloseRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        waitingForKeyboardCloseRunnable = null
    }

    private val keyboardRect = Rect()

    fun measureKeyboardHeight(): Int {
        val rootView = rootView ?: return 0
        rootView.getWindowVisibleDisplayFrame(keyboardRect)
        val rect = keyboardRect
        if (rect.bottom == 0 && rect.top == 0) return 0
        val usableViewHeight = rootView.height -
                (if (rect.top != 0) AndroidUtilities.statusBarHeight else 0) -
                AndroidUtilities.getViewInset(rootView)
        return (usableViewHeight - (rect.bottom - rect.top)).coerceAtLeast(0)
    }

    private fun checkWaitingForKeyboardClose() {
        val pending = waitingForKeyboardCloseRunnable ?: return
        if (!containerView.isKeyboardVisible && !containerViewBack.isKeyboardVisible) {
            AndroidUtilities.cancelRunOnUIThread(pending)
            pending.run()
            waitingForKeyboardCloseRunnable = null
        }
    }

    fun relayout() {
        requestLayout()
    }

    fun getLastFragmentIncludeMainTabs(): BaseFragment? {
        val last = getLastFragment() ?: return null
        if (last is com.mezon.mobile.home.MainTabsActivity) {
            val state = last.fragmentStates.get(last.viewPager.currentPosition)
            return state?.fragment ?: last
        }
        return last
    }

    override fun extendActionMode(menu: Menu): Boolean {
        return getLastFragment()?.extendActionMode(menu) ?: false
    }

    override fun onActionModeStarted(mode: Any) {}
    override fun onActionModeFinished(mode: Any) {}

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        activity.startActivityForResult(intent, requestCode)
    }

    // ── Back handling ────────────────────────────────────────────────────────

    fun onBackPressedInternal(): Boolean {
        if (startedTracking || checkTransitionAnimation()) return true
        val last = fragmentStack.lastOrNull() ?: return false
        if (!last.onBackPressed()) return false
        if (fragmentStack.size > 1) {
            closeLastFragment(animated = true)
            return true
        }
        return false
    }

    // ── Fragment stack management ────────────────────────────────────────────

    override fun addFragmentToStack(fragment: BaseFragment, position: Int): Boolean {
        if (delegate?.needAddFragmentToStack(fragment, this) == false) return false
        injectDeps(fragment)
        if (!fragment.onFragmentCreate()) return false
        if (fragmentStack.contains(fragment)) return false
        return if (position == -1 || position >= fragmentStack.size) {
            fragmentStack.add(fragment)
            true
        } else if (position == INavigationLayout.FORCE_NOT_ATTACH_VIEW) {
            fragmentStack.add(fragment)
            true
        } else if (position == INavigationLayout.FORCE_ATTACH_VIEW_AS_FIRST) {
            fragmentStack.add(0, fragment)
            true
        } else {
            fragmentStack.add(position, fragment)
            true
        }
    }

    override fun removeFragmentFromStack(fragment: BaseFragment, immediate: Boolean) {
        if (fragmentStack.remove(fragment)) {
            destroyFragment(fragment)
        }
    }

    // ── showLastFragment ────────────────────────────────────────────────────

    override fun showLastFragment() {
        val fragment = fragmentStack.lastOrNull() ?: return
        val view = fragment.fragmentView
            ?: fragment.createView(context).also { fragment.fragmentView = it }
        if (view.parent == null) {
            containerView.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addActionBarToContainer(fragment, containerView)
        view.visibility = VISIBLE
        containerView.visibility = VISIBLE
        fragment.onResume()
        fragment.onBecomeFullyVisible()
    }

    override fun rebuildAllFragmentViews(last: Boolean, showLastAfter: Boolean) {
        if (transitionAnimationInProgress || startedTracking) {
            rebuildAfterAnimation = true
            rebuildLastAfterAnimation = last
            showLastAfterAnimation = showLastAfter
            return
        }
        val size = fragmentStack.size - (if (last) 0 else 1)
        for (i in 0 until size) {
            fragmentStack[i].clearViews()
        }
        if (showLastAfter) showLastFragment()
        delegate?.onRebuildAllFragments(this, last)
    }

    // ── presentFragment (NavigationParams) ──────────────────────────────────

    override fun presentFragment(params: INavigationLayout.NavigationParams): Boolean {
        if (checkTransitionAnimation() && !params.noAnimation) return false

        val fragment = params.fragment
        injectDeps(fragment)
        if (!fragment.onFragmentCreate()) return false
        if (params.checkPresentFromDelegate &&
            delegate?.needPresentFragment(this, params) == false) return true

        if (activity.currentFocus != null && fragment.hideKeyboardOnShow()) {
            AndroidUtilities.hideKeyboard(activity.currentFocus)
        }

        val currentFragment = fragmentStack.lastOrNull()

        val newView = fragment.createView(context)
        fragment.fragmentView = newView

        val needAnimation = !params.noAnimation && SharedConfig.animationsEnabled()
        if (currentFragment?.fragmentView != null && needAnimation) {
            val exitView = currentFragment.fragmentView!!

            if (exitView.parent !== containerView) {
                (exitView.parent as? ViewGroup)?.removeView(exitView)
                containerView.addView(exitView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }

            containerViewBack.removeAllViews()
            containerViewBack.addView(newView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addActionBarToContainer(fragment, containerViewBack)
            containerViewBack.visibility = VISIBLE

            swapContainers()
            bringChildToFront(containerView)

            if (params.removeLast) {
                fragmentStack.removeAt(fragmentStack.size - 1)
            }
            fragmentStack.add(fragment)
            currentFragment.onTransitionAnimationStart(isOpen = false, backward = false)
            fragment.onTransitionAnimationStart(isOpen = true, backward = false)
            fragment.onResume()

            containerView.alpha = 0f
            containerView.translationX = openSlideDistance

            val startAnimation = Runnable {
                transitionAnimationInProgress = true
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = openAnimDuration
                    interpolator = slideInterpolator
                    addUpdateListener { anim ->
                        val progress = anim.animatedValue as Float
                        containerView.alpha = progress
                        containerView.translationX = openSlideDistance * (1f - progress)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            transitionAnimationInProgress = false
                            containerView.alpha = 1f
                            containerView.translationX = 0f
                            if (params.removeLast) {
                                containerViewBack.removeAllViews()
                                destroyFragment(currentFragment)
                            } else {
                                currentFragment.onBecomeFullyHidden()
                                currentFragment.onPause()
                            }
                            containerViewBack.visibility = INVISIBLE
                            currentFragment.onTransitionAnimationEnd(isOpen = false, backward = false)
                            fragment.onTransitionAnimationEnd(isOpen = true, backward = false)
                            fragment.onBecomeFullyVisible()
                            notifyStackChanged()
                            checkPendingRebuild()
                        }
                    })
                    start()
                }
            }

            if (containerView.isKeyboardVisible || containerViewBack.isKeyboardVisible) {
                waitingForKeyboardCloseRunnable = Runnable {
                    if (waitingForKeyboardCloseRunnable !== this.waitingForKeyboardCloseRunnable) return@Runnable
                    waitingForKeyboardCloseRunnable = null
                    startAnimation.run()
                }
                AndroidUtilities.runOnUIThread(waitingForKeyboardCloseRunnable!!, KEYBOARD_CLOSE_DELAY_PRESENT)
            } else {
                startAnimation.run()
            }
        } else {
            containerView.removeAllViews()
            containerView.addView(newView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addActionBarToContainer(fragment, containerView)
            containerView.visibility = VISIBLE
            if (params.removeLast && fragmentStack.isNotEmpty()) {
                val removed = fragmentStack.removeAt(fragmentStack.size - 1)
                destroyFragment(removed)
            }
            fragmentStack.add(fragment)
            if (currentFragment != null) {
                currentFragment.onTransitionAnimationStart(isOpen = false, backward = false)
                currentFragment.onTransitionAnimationEnd(isOpen = false, backward = false)
                currentFragment.onBecomeFullyHidden()
                currentFragment.onPause()
            }
            fragment.onTransitionAnimationStart(isOpen = true, backward = false)
            fragment.onTransitionAnimationEnd(isOpen = true, backward = false)
            fragment.onResume()
            fragment.onBecomeFullyVisible()
            notifyStackChanged()
        }
        return true
    }

    override fun closeLastFragment(animated: Boolean, forceNoAnimation: Boolean) {
        if (fragmentStack.size <= 1) return
        if ((checkTransitionAnimation() || startedTracking) && animated && !forceNoAnimation) return
        val closingFragment = fragmentStack.lastOrNull()
        if (closingFragment?.closeLastFragment() == true) return
        if (delegate?.needCloseLastFragment(this) == false) return

        if (activity.currentFocus != null) {
            AndroidUtilities.hideKeyboard(activity.currentFocus)
        }

        val removingFragment = fragmentStack.last()
        val previousFragment = fragmentStack[fragmentStack.size - 2]

        removingFragment.finishing = true
        removingFragment.onBecomeFullyHidden()
        removingFragment.onTransitionAnimationStart(isOpen = false, backward = true)

        val removingView = removingFragment.fragmentView ?: run {
            fragmentStack.removeAt(fragmentStack.size - 1)
            destroyFragment(removingFragment)
            previousFragment.onBecomeFullyVisible()
            notifyStackChanged()
            return
        }

        if (removingView.parent !== containerView) {
            (removingView.parent as? ViewGroup)?.removeView(removingView)
            containerView.addView(removingView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        val prevView = previousFragment.fragmentView
            ?: previousFragment.createView(context).also { previousFragment.fragmentView = it }
        (prevView.parent as? ViewGroup)?.removeView(prevView)
        containerViewBack.removeAllViews()
        containerViewBack.addView(prevView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addActionBarToContainer(previousFragment, containerViewBack)
        containerViewBack.visibility = VISIBLE
        prevView.visibility = VISIBLE
        bringChildToFront(containerView)

        val needAnim = animated && !forceNoAnimation && SharedConfig.animationsEnabled()
        if (needAnim) {
            previousFragment.onTransitionAnimationStart(isOpen = true, backward = true)
            previousFragment.onResume()

            val startCloseAnimation = Runnable {
                transitionAnimationInProgress = true
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = openAnimDuration
                    interpolator = slideInterpolator
                    addUpdateListener { anim ->
                        val progress = anim.animatedValue as Float
                        containerView.alpha = 1f - progress
                        containerView.translationX = openSlideDistance * progress
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            transitionAnimationInProgress = false
                            finishCloseAnimation(removingFragment)
                        }
                    })
                    start()
                }
            }

            if (containerView.isKeyboardVisible || containerViewBack.isKeyboardVisible) {
                waitingForKeyboardCloseRunnable = Runnable {
                    if (waitingForKeyboardCloseRunnable !== this.waitingForKeyboardCloseRunnable) return@Runnable
                    waitingForKeyboardCloseRunnable = null
                    startCloseAnimation.run()
                }
                AndroidUtilities.runOnUIThread(waitingForKeyboardCloseRunnable!!, KEYBOARD_CLOSE_DELAY_CLOSE)
            } else {
                startCloseAnimation.run()
            }
        } else {
            previousFragment.onTransitionAnimationStart(isOpen = true, backward = true)
            previousFragment.onResume()
            finishCloseAnimation(removingFragment)
        }
    }

    private fun finishCloseAnimation(removingFragment: BaseFragment) {
        removingFragment.onPause()
        removingFragment.onFragmentDestroy()
        removingFragment.parentLayout = null
        fragmentStack.removeAt(fragmentStack.size - 1)

        containerView.alpha = 1f
        containerView.translationX = 0f
        swapContainers()
        bringChildToFront(containerView)

        removingFragment.onTransitionAnimationEnd(isOpen = false, backward = true)
        val nowCurrent = fragmentStack.lastOrNull()
        nowCurrent?.let {
            it.onTransitionAnimationEnd(isOpen = true, backward = true)
            it.onBecomeFullyVisible()
        }

        containerViewBack.visibility = INVISIBLE
        containerView.setLayerType(LAYER_TYPE_NONE, null)

        notifyStackChanged()
        checkPendingRebuild()
    }

    // ── Touch / Swipe-back ──────────────────────────────────────────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return animationInProgress || checkTransitionAnimation() || onTouchEvent(ev)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        onTouchEvent(null)
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onTouchEvent(ev: MotionEvent?): Boolean {
        if (checkTransitionAnimation() || animationInProgress) return false
        if (fragmentStack.size <= 1) return false

        if (ev != null && ev.action == MotionEvent.ACTION_DOWN) {
            val currentFragment = fragmentStack.last()
            if (!currentFragment.isSwipeBackEnabled(ev)) {
                maybeStartTracking = false
                startedTracking = false
                containerView.setLayerType(LAYER_TYPE_NONE, null)
                return false
            }
            startedTrackingPointerId = ev.getPointerId(0)
            maybeStartTracking = true
            startedTrackingX = ev.x.toInt()
            startedTrackingY = ev.y.toInt()
            velocityTracker?.clear()
        } else if (ev != null && ev.action == MotionEvent.ACTION_MOVE
            && ev.getPointerId(0) == startedTrackingPointerId
        ) {
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
            val dx = (ev.x - startedTrackingX).toInt().coerceAtLeast(0)
            val dy = Math.abs(ev.y.toInt() - startedTrackingY)
            velocityTracker!!.addMovement(ev)

            if (!transitionAnimationInProgress && maybeStartTracking && !startedTracking
                && dx >= AndroidUtilities.getPixelsInCM(0.4f, true).toInt()
                && dx / 3 > dy
            ) {
                if (findScrollingChild(this, ev.x, ev.y) == null) {
                    val currentFrag = fragmentStack.last()
                    if (currentFrag.canBeginSlide()) {
                        startedTrackingX = ev.x.toInt()
                        prepareForMoving(fragmentStack[fragmentStack.size - 2])
                    } else {
                        maybeStartTracking = false
                    }
                } else {
                    maybeStartTracking = false
                }
            } else if (startedTracking) {
                if (!beginTrackingSent) {
                    beginTrackingSent = true
                    if (activity.currentFocus != null) {
                        AndroidUtilities.hideKeyboard(activity.currentFocus)
                    }
                    fragmentStack.last().onBeginSlide()
                }
                val translationX = (ev.x - startedTrackingX).coerceAtLeast(0f)
                containerView.translationX = translationX
                innerTranslationX = (translationX)
            }
        } else if (ev != null && ev.getPointerId(0) == startedTrackingPointerId
            && (ev.action == MotionEvent.ACTION_CANCEL
                    || ev.action == MotionEvent.ACTION_UP
                    || ev.action == MotionEvent.ACTION_POINTER_UP)
        ) {
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
            velocityTracker!!.addMovement(ev)
            velocityTracker!!.computeCurrentVelocity(1000)

            if (!startedTracking && ev.action != MotionEvent.ACTION_CANCEL) {
                val velX = velocityTracker!!.xVelocity
                val velY = velocityTracker!!.yVelocity
                val currentFragment = fragmentStack.last()
                if (velX >= 3500f && velX > Math.abs(velY)
                    && currentFragment.isSwipeBackEnabled(ev)
                ) {
                    startedTrackingX = ev.x.toInt()
                    prepareForMoving(fragmentStack[fragmentStack.size - 2])
                }
            }

            if (startedTracking) {
                val x = containerView.x
                val velX = velocityTracker!!.xVelocity
                val velY = velocityTracker!!.yVelocity
                val backAnimation = x < containerView.measuredWidth / 3.0f
                        && (velX < 3500f || Math.abs(velX) < Math.abs(velY))
                animateBackEndAnimation(backAnimation = backAnimation, fromSwipe = true)
            } else {
                maybeStartTracking = false
                startedTracking = false
                containerView.setLayerType(LAYER_TYPE_NONE, null)
            }
            velocityTracker?.recycle()
            velocityTracker = null
        } else if (ev == null) {
            maybeStartTracking = false
            startedTracking = false
            containerView.setLayerType(LAYER_TYPE_NONE, null)
            velocityTracker?.recycle()
            velocityTracker = null
        }

        return startedTracking
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val w = width - paddingLeft - paddingRight
        val tx = innerTranslationX.toInt() + paddingRight
        var clipLeft = paddingLeft
        var clipRight = w + paddingLeft

        if (child == containerViewBack) {
            clipRight = tx + LayoutHelper.dp(1)
        } else if (child == containerView) {
            clipLeft = tx
        }

        val restoreCount = canvas.save()
        if (!transitionAnimationInProgress && !inPreviewMode) {
            canvas.clipRect(clipLeft, 0, clipRight, height)
        }
        val result = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(restoreCount)

        if (tx != 0) {
            val widthOffset = w - tx
            if (child == containerView) {
                val alpha = (255 * widthOffset / LayoutHelper.dp(20)).coerceIn(0, 255)
                if (alpha > 0) {
                    layerShadowDrawable.setBounds(
                        tx - layerShadowDrawable.intrinsicWidth,
                        child.top,
                        tx,
                        child.bottom
                    )
                    layerShadowDrawable.alpha = alpha
                    layerShadowDrawable.draw(canvas)
                }
            } else if (child == containerViewBack) {
                val opacity = (widthOffset / w.toFloat()).coerceIn(0f, 0.8f)
                scrimPaint.color = Color.argb((120 * opacity).toInt(), 0x00, 0x00, 0x00)
                canvas.drawRect(clipLeft.toFloat(), 0f, clipRight.toFloat(), height * 1.5f, scrimPaint)
            }
        }

        return result
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        themeSnapshotBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, 0f, 0f, themeSnapshotPaint)
            }
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun setupContainerBack(previousFragment: BaseFragment) {
        containerViewBack.visibility = VISIBLE

        val fragmentView = previousFragment.fragmentView
            ?: previousFragment.createView(context).also { previousFragment.fragmentView = it }

        val parent = fragmentView.parent as? ViewGroup
        parent?.removeView(fragmentView)
        containerViewBack.removeAllViews()
        containerViewBack.addView(fragmentView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addActionBarToContainer(previousFragment, containerViewBack)
        fragmentView.visibility = VISIBLE

        if (measuredWidth > 0 && measuredHeight > 0) {
            val fullHeight = measuredHeight + measureKeyboardHeight()
            val w = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
            val h = MeasureSpec.makeMeasureSpec(fullHeight, MeasureSpec.EXACTLY)
            containerViewBack.measure(w, h)
            containerViewBack.layout(0, 0, measuredWidth, fullHeight)
            containerViewBack.isLayoutToIgnore = true
        }
        previousFragment.onResume()

        containerView.setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun prepareForMoving(previousFragment: BaseFragment) {
        maybeStartTracking = false
        startedTracking = true
        beginTrackingSent = false
        setupContainerBack(previousFragment)
    }

    private fun animateBackEndAnimation(backAnimation: Boolean, fromSwipe: Boolean) {
        if (!SharedConfig.animationsEnabled()) {
            containerView.translationX = 0f
            innerTranslationX = 0f
            onSlideAnimationEnd(backAnimation)
            return
        }

        val x = containerView.x
        val endTx = if (!backAnimation) containerView.measuredWidth.toFloat() else 0f
        val duration = if (!backAnimation) {
            ((200f / containerView.measuredWidth) * (containerView.measuredWidth - x)).toLong().coerceAtLeast(50)
        } else {
            ((320f / containerView.measuredWidth) * x).toLong().coerceAtLeast(120)
        }

        animationInProgress = true
        backAnimator?.cancel()
        backAnimator = ValueAnimator.ofFloat(x, endTx).apply {
            this.duration = duration
            addUpdateListener { anim ->
                val tx = anim.animatedValue as Float
                containerView.translationX = tx
                innerTranslationX = (tx)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                    animationInProgress = false
                    onSlideAnimationEnd(backAnimation = true)
                    backAnimator = null
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    animationInProgress = false
                    onSlideAnimationEnd(backAnimation)
                    backAnimator = null
                }
            })
            start()
        }
        containerViewBack.isLayoutToIgnore = true
    }

    private fun onSlideAnimationEnd(backAnimation: Boolean) {
        if (!backAnimation) {
            if (fragmentStack.size >= 2) {
                val removingFragment = fragmentStack.last()
                removingFragment.onPause()
                removingFragment.onFragmentDestroy()
                removingFragment.parentLayout = null
                fragmentStack.removeAt(fragmentStack.size - 1)

                containerView.alpha = 1f
                swapContainers()
                bringChildToFront(containerView)

                removingFragment.onTransitionAnimationEnd(isOpen = false, backward = true)
                val nowCurrent = fragmentStack.lastOrNull()
                nowCurrent?.let {
                    it.onResume()
                    it.onTransitionAnimationEnd(isOpen = true, backward = true)
                    it.onBecomeFullyVisible()
                }
            }
        } else {
            if (fragmentStack.size >= 2) {
                val prevFragment = fragmentStack[fragmentStack.size - 2]
                val prevView = prevFragment.fragmentView
                if (prevView != null) {
                    (prevView.parent as? ViewGroup)?.removeView(prevView)
                }
                prevFragment.onPause()
            }
        }

        containerViewBack.visibility = INVISIBLE
        val wasFrozen = containerViewBack.isLayoutToIgnore || containerView.isLayoutToIgnore
        containerViewBack.isLayoutToIgnore = false
        containerView.isLayoutToIgnore = false
        startedTracking = false
        animationInProgress = false
        containerView.translationX = 0f
        containerViewBack.translationX = 0f
        containerView.setLayerType(LAYER_TYPE_NONE, null)
        innerTranslationX = (0f)

        if (wasFrozen) {
            containerView.requestLayout()
            containerViewBack.requestLayout()
        }

        notifyStackChanged()
        checkPendingRebuild()
    }

    private fun swapContainers() {
        val temp = containerView
        containerView = containerViewBack
        containerViewBack = temp
    }

    private fun checkPendingRebuild() {
        if (rebuildAfterAnimation) {
            rebuildAfterAnimation = false
            rebuildAllFragmentViews(rebuildLastAfterAnimation, showLastAfterAnimation)
        }
    }

    private var themeAnimatorSet: ValueAnimator? = null
    private var themeSnapshotBitmap: android.graphics.Bitmap? = null
    private val themeSnapshotPaint = Paint()

    fun animateThemeChange() {
        if (!SharedConfig.animationsEnabled()) {
            getLastFragment()?.applyThemeDescriptions()
            return
        }

        try {
            val w = measuredWidth
            val h = measuredHeight
            if (w <= 0 || h <= 0) {
                getLastFragment()?.applyThemeDescriptions()
                return
            }
            themeSnapshotBitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(themeSnapshotBitmap!!)
            draw(c)
        } catch (_: Exception) {
            themeSnapshotBitmap = null
            getLastFragment()?.applyThemeDescriptions()
            return
        }

        getLastFragment()?.applyThemeDescriptions()

        themeSnapshotPaint.alpha = 255
        themeAnimatorSet?.cancel()
        themeAnimatorSet = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 300
            addUpdateListener {
                val alpha = ((it.animatedValue as Float) * 255).toInt()
                themeSnapshotPaint.alpha = alpha
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    themeSnapshotBitmap?.recycle()
                    themeSnapshotBitmap = null
                    themeAnimatorSet = null
                    invalidate()
                }
            })
            start()
        }
    }

    private fun addActionBarToContainer(fragment: BaseFragment, container: LayoutContainer) {
        val actionBar = fragment.actionBar ?: return
        if (!actionBar.shouldAddToContainer()) return
        (actionBar.parent as? ViewGroup)?.removeView(actionBar)
        container.addView(actionBar)
        actionBar.occupyStatusBar = !inBubbleMode
    }

    private fun destroyFragment(fragment: BaseFragment) {
        val view = fragment.fragmentView
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
        }
        fragment.onFragmentDestroy()
        fragment.fragmentView = null
    }

    private fun injectDeps(fragment: BaseFragment) {
        if (!::_themeColors.isInitialized) return
        fragment.themeColors = _themeColors
        fragment.notificationCenter = NotificationCenter.getInstance(fragment.currentAccount)
        fragment.parentLayout = this
        fragment.inject(context)
    }

    companion object {
        private const val KEYBOARD_CLOSE_DELAY_PRESENT = 250L
        private const val KEYBOARD_CLOSE_DELAY_CLOSE = 200L

        private val scrimPaint = Paint()
        private val hitRect = Rect()

        private val layerShadowDrawable: GradientDrawable by lazy {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x00000000, 0x20000000)
            ).apply { setSize(LayoutHelper.dp(3), 0) }
        }

        fun findScrollingChild(parent: ViewGroup, x: Float, y: Float): View? {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child.visibility != VISIBLE) continue
                child.getHitRect(hitRect)
                if (hitRect.contains(x.toInt(), y.toInt())) {
                    if (child.canScrollHorizontally(-1)) return child
                    if (child is ViewGroup) {
                        val found = findScrollingChild(child, x - hitRect.left, y - hitRect.top)
                        if (found != null) return found
                    }
                }
            }
            return null
        }
    }
}
