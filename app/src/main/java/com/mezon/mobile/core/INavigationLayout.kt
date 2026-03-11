package com.mezon.mobile.core

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout

interface INavigationLayout {

    companion object {
        const val REBUILD_FLAG_REBUILD_LAST = 1
        const val REBUILD_FLAG_REBUILD_ONLY_LAST = 2

        const val FORCE_NOT_ATTACH_VIEW = -2
        const val FORCE_ATTACH_VIEW_AS_FIRST = -3

        @JvmStatic
        fun newLayout(context: android.content.Context, activity: android.app.Activity): INavigationLayout {
            return ActionBarLayout(context, activity)
        }
    }

    fun presentFragment(params: NavigationParams): Boolean
    fun checkTransitionAnimation(): Boolean
    fun addFragmentToStack(fragment: BaseFragment, position: Int): Boolean
    fun removeFragmentFromStack(fragment: BaseFragment, immediate: Boolean)
    fun getFragmentStack(): List<BaseFragment>
    fun setDelegate(delegate: INavigationLayoutDelegate)
    fun closeLastFragment(animated: Boolean, forceNoAnimation: Boolean)
    fun getDrawerLayoutContainer(): DrawerLayoutContainer?
    fun setDrawerLayoutContainer(container: DrawerLayoutContainer)
    fun setRemoveActionBarExtraHeight(removeExtraHeight: Boolean)
    fun setTitleOverlayText(title: String?, titleId: Int, action: Runnable?)
    fun setFragmentStackChangedListener(listener: Runnable?)
    fun isTransitionAnimationInProgress(): Boolean
    fun resumeDelayedFragmentAnimation()
    fun allowSwipe(): Boolean

    fun isInPassivePreviewMode(): Boolean
    fun setInBubbleMode(bubbleMode: Boolean)
    fun isInBubbleMode(): Boolean

    fun isInPreviewMode(): Boolean
    fun isPreviewOpenAnimationInProgress(): Boolean
    fun movePreviewFragment(dy: Float)
    fun expandPreviewFragment()
    fun finishPreviewFragment()
    fun setFragmentPanTranslationOffset(offset: Int)
    fun getOverlayContainerView(): FrameLayout?
    fun setHighlightActionButtons(highlight: Boolean)
    fun getCurrentPreviewFragmentAlpha(): Float
    fun drawCurrentPreviewFragment(canvas: Canvas, foregroundDrawable: Drawable?)

    fun drawHeaderShadow(canvas: Canvas, alpha: Int, y: Int)

    fun isSwipeInProgress(): Boolean

    fun onPause()
    fun onResume()
    fun onBackPressed()
    fun onUserLeaveHint()
    fun onLowMemory()
    fun extendActionMode(menu: Menu): Boolean
    fun onActionModeStarted(mode: Any)
    fun onActionModeFinished(mode: Any)
    fun startActivityForResult(intent: Intent, requestCode: Int)

    fun setNavigationBarColor(color: Int)

    fun setIsSheet(isSheet: Boolean)
    fun isSheet(): Boolean
    fun updateTitleOverlay()
    fun setWindow(window: Window?)

    // ── Default implementations ──────────────────────────────────────────────

    fun removeFragmentFromStack(fragment: BaseFragment) {
        removeFragmentFromStack(fragment, false)
    }

    fun isActionBarInCrossfade(): Boolean = false
    fun hasIntegratedBlurInPreview(): Boolean = false

    fun rebuildFragments(flags: Int) {
        if (flags and REBUILD_FLAG_REBUILD_ONLY_LAST != 0) {
            showLastFragment()
            return
        }
        val last = flags and REBUILD_FLAG_REBUILD_LAST != 0
        rebuildAllFragmentViews(last, last)
    }

    fun setBackgroundView(backgroundView: View?) {}
    fun setUseAlphaAnimations(useAlphaAnimations: Boolean) {}
    fun rebuildLogout() {}
    fun showLastFragment() {}
    fun rebuildAllFragmentViews(last: Boolean, showLastAfter: Boolean) {}

    fun drawHeaderShadow(canvas: Canvas, y: Int) {
        drawHeaderShadow(canvas, 0xFF, y)
    }

    fun getBackgroundFragment(): BaseFragment? {
        val stack = getFragmentStack()
        return if (stack.size <= 1) null else stack[stack.size - 2]
    }

    fun getLastFragment(): BaseFragment? {
        val stack = getFragmentStack()
        return if (stack.isEmpty()) null else stack[stack.size - 1]
    }

    fun getSafeLastFragment(): BaseFragment? {
        val stack = getFragmentStack()
        for (i in stack.size - 1 downTo 0) {
            val fragment = stack[i]
            if (fragment.isFinishing() || fragment.isRemovingFromStack()) continue
            return fragment
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : BaseFragment> findFragment(clazz: Class<T>): T? {
        val stack = getFragmentStack()
        for (i in stack.size - 1 downTo 0) {
            val fragment = stack[i]
            if (fragment.isFinishing() || fragment.isRemovingFromStack()) continue
            if (clazz.isInstance(fragment)) return fragment as T
        }
        return null
    }

    fun bringToFront(i: Int) {
        val fragment = getFragmentStack()[i]
        removeFragmentFromStack(fragment)
        addFragmentToStack(fragment)
        rebuildFragments(REBUILD_FLAG_REBUILD_ONLY_LAST)
    }

    fun removeAllFragments() {
        for (fragment in ArrayList(getFragmentStack())) {
            removeFragmentFromStack(fragment)
        }
    }

    fun getParentActivity(): Activity? {
        val ctx = getView().context
        return ctx as? Activity
    }

    fun getView(): ViewGroup {
        if (this is ViewGroup) return this
        throw IllegalArgumentException("You should override getView() if you're not inheriting from it.")
    }

    fun closeLastFragment() {
        closeLastFragment(true)
    }

    fun closeLastFragment(animated: Boolean) {
        closeLastFragment(animated, false)
    }

    fun setFragmentStack(stack: List<BaseFragment>) {}

    fun removeFragmentFromStack(i: Int) {
        val stack = getFragmentStack()
        if (i < 0 || i >= stack.size) return
        removeFragmentFromStack(stack[i])
    }

    fun addFragmentToStack(fragment: BaseFragment): Boolean {
        return addFragmentToStack(fragment, -1)
    }

    fun presentFragment(fragment: BaseFragment): Boolean {
        return presentFragment(NavigationParams(fragment))
    }

    fun presentFragment(fragment: BaseFragment, removeLast: Boolean): Boolean {
        return presentFragment(NavigationParams(fragment).setRemoveLast(removeLast))
    }

    fun presentFragmentAsPreview(fragment: BaseFragment): Boolean {
        return presentFragment(NavigationParams(fragment).setPreview(true))
    }

    fun presentFragmentAsPreviewWithMenu(fragment: BaseFragment, menuView: View?): Boolean {
        return presentFragment(NavigationParams(fragment).setPreview(true).setMenuView(menuView))
    }

    @Deprecated("Use NavigationParams")
    fun presentFragment(
        fragment: BaseFragment,
        removeLast: Boolean,
        forceWithoutAnimation: Boolean,
        check: Boolean,
        preview: Boolean
    ): Boolean {
        return presentFragment(
            NavigationParams(fragment)
                .setRemoveLast(removeLast)
                .setNoAnimation(forceWithoutAnimation)
                .setCheckPresentFromDelegate(check)
                .setPreview(preview)
        )
    }

    fun dismissDialogs() {
        val stack = getFragmentStack()
        if (stack.isNotEmpty()) {
            stack.last().dismissCurrentDialog()
        }
    }

    fun getWindow(): Window? = getParentActivity()?.window

    fun getBottomTabsHeight(animated: Boolean): Int = 0

    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        if (this is ActionBarLayout) {
            this.onConfigurationChanged(newConfig)
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        val stack = getFragmentStack()
        if (stack.isNotEmpty()) {
            stack.last().onActivityResult(requestCode, resultCode, data)
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val stack = getFragmentStack()
        if (stack.isNotEmpty()) {
            stack.last().onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    // ── Delegate ─────────────────────────────────────────────────────────────

    interface INavigationLayoutDelegate {

        fun needPresentFragment(layout: INavigationLayout, params: NavigationParams): Boolean {
            return needPresentFragment(params.fragment, params.removeLast, params.noAnimation, layout)
        }

        fun needPresentFragment(
            fragment: BaseFragment,
            removeLast: Boolean,
            forceWithoutAnimation: Boolean,
            layout: INavigationLayout
        ): Boolean = true

        fun needAddFragmentToStack(fragment: BaseFragment, layout: INavigationLayout): Boolean = true

        fun onPreIme(): Boolean = false

        fun needCloseLastFragment(layout: INavigationLayout): Boolean = true

        fun onMeasureOverride(measureSpec: IntArray) {}
        fun onRebuildAllFragments(layout: INavigationLayout, last: Boolean) {}
        fun onThemeProgress(progress: Float) {}
        fun onFragmentStackChanged(layout: INavigationLayout) {}
    }

    // ── NavigationParams ─────────────────────────────────────────────────────

    class NavigationParams(val fragment: BaseFragment) {
        var removeLast = false
        var noAnimation = false
        var checkPresentFromDelegate = true
        var preview = false
        var menuView: View? = null
        var needDelayWithoutAnimation = false
        var isFromDelay = false
        var delayDone = false
        var occupyNavigationBar = false

        fun setRemoveLast(value: Boolean) = apply { removeLast = value }
        fun setNoAnimation(value: Boolean) = apply { noAnimation = value }
        fun setCheckPresentFromDelegate(value: Boolean) = apply { checkPresentFromDelegate = value }
        fun setPreview(value: Boolean) = apply { preview = value }
        fun setMenuView(view: View?) = apply { menuView = view }
        fun setNeedDelayWithoutAnimation(value: Boolean) = apply { needDelayWithoutAnimation = value }
    }

    enum class BackButtonState { BACK, MENU }
}
