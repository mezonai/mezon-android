package com.mezon.mobile.core

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.mezon.mobile.MainActivity
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.friends.AddFriendBottomSheet
import com.mezon.mobile.home.wallet.SendTokenFragment
import com.mezon.mobile.ui.cells.ActionBarView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicInteger

abstract class BaseFragment {

    companion object {
        private val classGuidCounter = AtomicInteger(0)
    }

    var fragmentView: View? = null
    var actionBar: ActionBarView? = null
    var parentLayout: INavigationLayout? = null
    var arguments: Bundle? = null

    var parentDialog: Dialog? = null
    var resourceProvider: ThemeColors.ResourcesProvider? = null

    var isFinished = false
    var finishing = false
    var isPaused = true
    var inTransitionAnimation = false
    var fragmentBeginToShow = false
    var hasOwnBackground = false

    var inPreviewMode = false
        protected set
    var inMenuMode = false
        protected set
    var inBubbleMode = false

    private var isFullyVisible = false
    private var removingFromStack = false
    private var bottomInset = 0

    var classGuid: Int = classGuidCounter.incrementAndGet()
        private set

    var visibleDialog: Dialog? = null

    lateinit var themeColors: ThemeColors

    var currentAccount: Int = 0
        set(value) {
            if (fragmentView != null) {
                throw IllegalStateException("trying to set current account when fragment UI already created")
            }
            field = value
        }

    private var _notificationCenter: NotificationCenter? = null
    var notificationCenter: NotificationCenter
        get() = _notificationCenter ?: NotificationCenter.getInstance(currentAccount)
        set(value) { _notificationCenter = value }
    fun getGlobalNotificationCenter(): NotificationCenter = NotificationCenter.getGlobalInstance()

    private val observers = ArrayList<Triple<NotificationCenter, Int, NotificationCenter.NotificationCenterDelegate>>()

    private var _scope: CoroutineScope? = null
    val fragmentScope: CoroutineScope
        get() {
            if (_scope == null) _scope = CoroutineScope(SupervisorJob())
            return _scope!!
        }

    private var _entryPoint: FragmentEntryPoint? = null
    private var _appContext: Context? = null

    fun inject(context: Context) {
        _appContext = context.applicationContext
        _entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            FragmentEntryPoint::class.java
        )
        onInject(_entryPoint!!)
    }

    protected open fun onInject(entryPoint: FragmentEntryPoint) {}

    protected fun entryPoint(): FragmentEntryPoint =
        _entryPoint ?: error("inject() must be called before accessing entryPoint")

    fun getContext(): Context? = getParentActivity() ?: _appContext

    fun requireContext(): Context = getContext() ?: error("Fragment not attached to activity")

    fun getString(resId: Int): String = requireContext().getString(resId)

    fun getString(resId: Int, vararg args: Any): String = requireContext().getString(resId, *args)

    fun getParentActivity(): Activity? = parentLayout?.getParentActivity()

    fun setInPreviewModeValue(value: Boolean) {
        inPreviewMode = value
    }

    fun setInMenuModeValue(value: Boolean) {
        inMenuMode = value
    }

    fun getInPassivePreviewMode(): Boolean {
        return parentLayout?.isInPassivePreviewMode() ?: false
    }

    fun isRemovingFromStack(): Boolean = removingFromStack
    fun setRemovingFromStack(value: Boolean) { removingFromStack = value }


    // ── Lifecycle ────────────────────────────────────────────────────────────

    abstract fun createView(context: Context): View

    open fun onFragmentCreate(): Boolean = true

    open fun onFragmentDestroy() {
        isFinished = true
        _scope?.cancel()
        _scope = null
        for ((nc, eventId, observer) in observers) {
            nc.removeObserver(observer, eventId)
        }
        observers.clear()

        if (hasForceLightStatusBar() && !AndroidUtilities.isTablet() && parentLayout?.getLastFragment() == this && getParentActivity() != null && !finishing) {
            checkSystemBarColors()
        }

        actionBar = null
    }

    open fun onResume() {
        isPaused = false
        actionBar?.onResume()
    }

    open fun onPause() {
        isPaused = true
        actionBar?.onPause()
        try {
            if (visibleDialog?.isShowing == true && dismissDialogOnPause(visibleDialog!!)) {
                visibleDialog?.dismiss()
                visibleDialog = null
            }
        } catch (_: Exception) {}
    }

    open fun dismissDialogOnPause(dialog: Dialog): Boolean = true

    open fun onUserLeaveHint() {}

    open fun onLowMemory() {}

    open fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}

    open fun onSaveInstanceState(outState: Bundle) {}

    open fun onRestoreSelfArgs(args: Bundle) {}

    open fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {}

    open fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {}

    open fun onPreviewOpenAnimationEnd() {}

    open fun hideKeyboardOnShow(): Boolean = true

    open fun extendActionMode(menu: Menu): Boolean = false

    open fun closeLastFragment(): Boolean = false

    open fun createActionBar(context: Context): ActionBarView {
        val bar = ActionBarView(context, themeColors)
        bar.setBackClickListener { finishFragment() }
        return bar
    }

    fun setInPreviewModeWithActionBar(value: Boolean) {
        inPreviewMode = value
        actionBar?.occupyStatusBar = !value
    }

    // ── Back handling ────────────────────────────────────────────────────────

    open fun onBackPressed(): Boolean = true

    open fun onBackPressed(invoked: Boolean): Boolean = onBackPressed()

    open fun canBeginSlide(): Boolean = true

    open fun onBeginSlide() {
        try {
            actionBar?.onPause()
        } catch (_: Exception) {}
        try {
            if (visibleDialog?.isShowing == true) {
                visibleDialog?.dismiss()
                visibleDialog = null
            }
        } catch (_: Exception) {}
    }

    open fun isSwipeBackEnabled(): Boolean = true

    open fun isSwipeBackEnabled(event: MotionEvent): Boolean = isSwipeBackEnabled()

    // ── Transition animation hooks ──────────────────────────────────────────

    open fun onTransitionAnimationStart(isOpen: Boolean, backward: Boolean) {
        inTransitionAnimation = true
        if (isOpen) fragmentBeginToShow = true
    }

    open fun onTransitionAnimationEnd(isOpen: Boolean, backward: Boolean) {
        inTransitionAnimation = false
    }

    open fun onTransitionAnimationProgress(isOpen: Boolean, progress: Float) {}

    open fun onSlideProgress(isOpen: Boolean, progress: Float) {}

    open fun prepareFragmentToSlide(topFragment: Boolean, beginSlide: Boolean) {}

    open fun needDelayOpenAnimation(): Boolean = false

    open fun onCustomTransitionAnimation(isOpen: Boolean, callback: Runnable): android.animation.AnimatorSet? = null

    open fun getCustomSlideTransition(topFragment: Boolean, backAnimation: Boolean, distanceToMove: Float): android.animation.Animator? = null

    open fun shouldOverrideSlideTransition(topFragment: Boolean, backAnimation: Boolean): Boolean = false

    open fun getPreviewHeight(): Int = -1

    // ── Visibility ──────────────────────────────────────────────────────────

    private var fullyVisibleListener: Runnable? = null

    open fun onBecomeFullyVisible() {
        isFullyVisible = true
        fullyVisibleListener?.let {
            fullyVisibleListener = null
            it.run()
        }
        checkSystemBarColors()
    }

    open fun onBecomeFullyHidden() {
        isFullyVisible = false
    }

    fun whenFullyVisible(callback: Runnable) {
        if (isFullyVisible) {
            callback.run()
        } else {
            fullyVisibleListener = callback
        }
    }

    fun isBeginToShow(): Boolean = fragmentBeginToShow

    // ── System bar colors ────────────────────────────────────────────────────

    open fun isLightStatusBar(): Boolean {
        if (hasForceLightStatusBar() && !::themeColors.isInitialized) return false
        if (hasForceLightStatusBar() && themeColors.resolvedMode == com.mezon.mobile.ui.theme.ThemeMode.LIGHT) return true
        if (!::themeColors.isInitialized) return false
        val barColor = getStatusBarColor()
        return isColorLight(barColor)
    }

    open fun hasForceLightStatusBar(): Boolean = false

    open fun getStatusBarColor(): Int {
        return if (::themeColors.isInitialized) themeColors.surface else Color.WHITE
    }

    private var navigationBarColorOverride: Int? = null

    open fun getNavigationBarColor(): Int {
        navigationBarColorOverride?.let { return it }
        return if (::themeColors.isInitialized) themeColors.surface else Color.WHITE
    }

    fun setNavigationBarColor(color: Int) {
        navigationBarColorOverride = color
        checkSystemBarColors()
    }

    protected fun checkSystemBarColors() {
        val activity = getParentActivity()
        if (activity is MainActivity) {
            activity.checkSystemBarColors()
        }
    }

    private fun isColorLight(color: Int): Boolean {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return luminance > 0.5
    }

    // ── Navigation helpers ──────────────────────────────────────────────────

    fun presentFragment(fragment: BaseFragment): Boolean {
        if (!allowPresentFragment()) return false
        return parentLayout?.presentFragment(fragment) ?: false
    }

    fun presentFragment(fragment: BaseFragment, removeLast: Boolean): Boolean {
        if (!allowPresentFragment()) return false
        return parentLayout?.presentFragment(fragment, removeLast) ?: false
    }

    fun presentFragment(fragment: BaseFragment, removeLast: Boolean, forceWithoutAnimation: Boolean): Boolean {
        if (!allowPresentFragment()) return false
        val params = INavigationLayout.NavigationParams(fragment).apply {
            this.removeLast = removeLast
            this.noAnimation = forceWithoutAnimation
        }
        return parentLayout?.presentFragment(params) ?: false
    }

    fun presentFragment(params: INavigationLayout.NavigationParams): Boolean {
        if (!allowPresentFragment()) return false
        return parentLayout?.presentFragment(params) ?: false
    }

    protected open fun allowPresentFragment(): Boolean = true

    fun finishFragment() {
        finishFragment(true)
    }

    fun finishFragment(animated: Boolean): Boolean {
        if (isFinished || parentLayout == null) return false
        finishing = true
        parentLayout?.closeLastFragment(animated)
        return true
    }

    fun removeSelfFromStack() {
        if (isFinished || parentLayout == null) return
        parentLayout?.removeFragmentFromStack(this)
    }

    fun removeSelfFromStack(immediate: Boolean) {
        if (isFinished || parentLayout == null) return
        parentLayout?.removeFragmentFromStack(this, immediate)
    }

    open fun allowFinishFragmentInsteadOfRemoveFromStack(): Boolean = true

    fun isLastFragment(): Boolean = parentLayout?.getLastFragment() == this

    fun isFinishing(): Boolean = finishing

    // ── Pan translation (keyboard adjust) ──────────────────────────────────

    protected open fun onPanTranslationUpdate(y: Float) {}
    protected open fun onPanTransitionStart() {}
    protected open fun onPanTransitionEnd() {}

    open fun setKeyboardHeightFromParent(keyboardHeight: Int) {}

    // ── Theme helpers ──────────────────────────────────────────────────────

    fun getThemedColor(key: Int): Int {
        return resourceProvider?.getColor(key) ?: ThemeColors.instance.getColor(key)
    }

    // ── Activity delegation ────────────────────────────────────────────────

    fun startActivityForResult(intent: android.content.Intent, requestCode: Int) {
        getParentActivity()?.startActivityForResult(intent, requestCode)
    }

    protected fun setParentActivityTitle(title: CharSequence?) {
        getParentActivity()?.title = title
    }

    // ── View helpers ────────────────────────────────────────────────────────

    fun getLayoutContainer(): android.widget.FrameLayout? {
        val parent = fragmentView?.parent
        return if (parent is android.widget.FrameLayout) parent else null
    }

    open fun clearViews() {
        fragmentView?.let { v ->
            val parent = v.parent as? ViewGroup
            try { parent?.removeViewInLayout(v) } catch (_: Exception) { parent?.removeView(v) }
            fragmentView = null
        }
        actionBar?.let { ab ->
            val parent = ab.parent as? ViewGroup
            try { parent?.removeViewInLayout(ab) } catch (_: Exception) { parent?.removeView(ab) }
            actionBar = null
        }
    }

    fun onRemoveFromParent() {}

    fun resetFragment() {
        if (isFinished) {
            clearViews()
            isFinished = false
            finishing = false
        }
    }

    fun setFragmentPanTranslationOffset(offset: Int) {
        parentLayout?.setFragmentPanTranslationOffset(offset)
    }

    fun saveKeyboardPositionBeforeTransition() {}

    fun isActionBarCrossfadeEnabled(): Boolean = actionBar != null

    // ── Insets ────────────────────────────────────────────────────────────────

    open fun onInsets(insets: android.graphics.Rect) {}

    fun getBottomInset(): Int = bottomInset

    fun setBottomInset(inset: Int) { bottomInset = inset }

    // ── Dialog helpers ──────────────────────────────────────────────────────

    fun showDialog(dialog: Dialog): Dialog? {
        return showDialog(dialog, false, null)
    }

    fun showDialog(dialog: Dialog, allowInTransition: Boolean, onDismissListener: android.content.DialogInterface.OnDismissListener?): Dialog? {
        val layout = parentLayout ?: return null
        if (!allowInTransition && (layout.isTransitionAnimationInProgress() || layout.isSwipeInProgress() || layout.checkTransitionAnimation())) return null
        try {
            visibleDialog?.dismiss()
            visibleDialog = null
        } catch (_: Exception) {}
        return try {
            visibleDialog = dialog
            visibleDialog!!.setCanceledOnTouchOutside(true)
            visibleDialog!!.setOnDismissListener { d ->
                onDialogDismiss(d as Dialog)
                onDismissListener?.onDismiss(d)
                if (d == visibleDialog) visibleDialog = null
            }
            visibleDialog!!.show()
            visibleDialog
        } catch (_: Exception) { null }
    }

    protected open fun onDialogDismiss(dialog: Dialog) {}

    fun dismissCurrentDialog() {
        try {
            visibleDialog?.dismiss()
            visibleDialog = null
        } catch (_: Exception) {}
    }

    // ── Observer helpers ────────────────────────────────────────────────────

    protected fun observe(eventId: Int, observer: NotificationCenter.NotificationCenterDelegate) {
        val nc = notificationCenter
        observers.add(Triple(nc, eventId, observer))
        nc.addObserver(observer, eventId)
    }

    protected fun observeGlobal(eventId: Int, observer: NotificationCenter.NotificationCenterDelegate) {
        val nc = getGlobalNotificationCenter()
        observers.add(Triple(nc, eventId, observer))
        nc.addObserver(observer, eventId)
    }

    protected fun observe(eventId: Int, block: (id: Int, account: Int, args: Array<out Any?>) -> Unit) {
        observe(eventId, object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) =
                block(id, account, args)
        })
    }

    protected fun observeGlobal(eventId: Int, block: (id: Int, account: Int, args: Array<out Any?>) -> Unit) {
        observeGlobal(eventId, object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) =
                block(id, account, args)
        })
    }

    protected fun showAddFriendBottomSheet() {
        val activity = getParentActivity() ?: return
        AddFriendBottomSheet(
            context = activity,
            friendController = entryPoint().friendController(),
            notificationCenter = notificationCenter
        ).apply {
            setDrawNavigationBar(true)
            show()
        }
    }

    protected fun openProfileTransferFunds(userId: Long, receiverUsername: String) {
        val ctx = getContext() ?: return
        SendTokenFragment.presentForProfile(ctx, parentLayout, userId, receiverUsername)
    }

    // ── ActionBar wrapper helper ────────────────────────────────────────────

    private var actionBarThemeDelegate: NotificationCenter.NotificationCenterDelegate? = null

    protected fun wrapWithActionBar(title: String, content: View): View {
        val bar = ActionBarView(content.context, themeColors).apply {
            setTitle(title)
            setBackClickListener { finishFragment() }
        }
        actionBar = bar

        actionBarThemeDelegate?.let { notificationCenter.removeObserver(it, NotificationCenter.themeChanged) }
        val delegate = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                bar.applyTheme()
            }
        }
        actionBarThemeDelegate = delegate
        observe(NotificationCenter.themeChanged, delegate)

        val root = LinearLayout(content.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }
        root.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        return root
    }

    open fun onFragmentClosed() {}

    open fun isSupportEdgeToEdge(): Boolean = false

    open fun drawEdgeNavigationBar(): Boolean = false

    open fun onBottomSheetCreated() {}

    open fun getBackButtonState(): INavigationLayout.BackButtonState? = null

    open fun setPreviewOpenedProgress(progress: Float) {}

    open fun setPreviewReplaceProgress(progress: Float) {}

    protected open fun resumeDelayedFragmentAnimation() {
        parentLayout?.resumeDelayedFragmentAnimation()
    }

    open fun getThemeDescriptions(): ArrayList<ThemeDescription>? = null

    fun applyThemeDescriptions() {
        val descriptions = getThemeDescriptions() ?: return
        for (desc in descriptions) {
            desc.setColor(desc.getSetColor(), false)
        }
    }

    fun getFragmentForAlert(offset: Int): BaseFragment {
        val stack = parentLayout?.getFragmentStack() ?: return this
        return if (stack.size <= 1 + offset) this
        else stack[stack.size - 2 - offset]
    }
}
