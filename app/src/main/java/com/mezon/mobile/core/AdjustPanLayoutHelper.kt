package com.mezon.mobile.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.view.ContextThemeWrapper
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.Interpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.view.WindowInsetsCompat

open class AdjustPanLayoutHelper(
    private val parent: View,
    private var useInsetsAnimator: Boolean = USE_ANDROID11_INSET_ANIMATOR
) {

    companion object {
        @JvmField var USE_ANDROID11_INSET_ANIMATOR = false
        @JvmField val keyboardInterpolator: Interpolator =
            CubicBezierInterpolator(0.19919472913616398, 0.010644531250000006, 0.27920937042459737, 0.91025390625)
        const val keyboardDuration = 250L
    }

    private var resizableViewToSet: View? = null

    private var contentView: ViewGroup? = null
    private var resizableView: View? = null
    private var usingInsetAnimator = false
    private var animationInProgress = false
    private var needDelay = false
    private val delayedAnimationRunnable = Runnable {
        if (animator != null && !animator!!.isRunning) {
            animator!!.start()
        }
    }

    var previousHeight = -1
    var previousContentHeight = -1
    var previousStartOffset = -1

    var parentForListener: View? = null
    var animator: ValueAnimator? = null

    val viewsToHeightSet = ArrayList<View>()
    protected var keyboardSize = 0f

    var checkHierarchyHeight = false

    var from = 0f
    var to = 0f
    var inverse = false
    var isKeyboardVisible = false
    var startAfter = -1L

    var showingKeyboard = false

    private var enabled = true
    private var ignoreOnce = false

    private val onPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        val contentHeight = parent.height
        if (contentHeight - startOffset() == previousHeight - previousStartOffset || contentHeight == previousHeight || animator != null) {
            if (animator == null) {
                previousHeight = contentHeight
                previousContentHeight = contentView?.height ?: 0
                previousStartOffset = startOffset()
                usingInsetAnimator = false
            }
            return@OnPreDrawListener true
        }

        if (!heightAnimationEnabled() || Math.abs(previousHeight - contentHeight) < AndroidUtilities.dp(20)) {
            previousHeight = contentHeight
            previousContentHeight = contentView?.height ?: 0
            previousStartOffset = startOffset()
            usingInsetAnimator = false
            return@OnPreDrawListener true
        }

        if (previousHeight != -1 && previousContentHeight == (contentView?.height ?: 0)) {
            isKeyboardVisible = contentHeight < (contentView?.bottom ?: 0)
            animateHeight(previousHeight, contentHeight, isKeyboardVisible)
            previousHeight = contentHeight
            previousContentHeight = contentView?.height ?: 0
            previousStartOffset = startOffset()
            return@OnPreDrawListener false
        }

        previousHeight = contentHeight
        previousContentHeight = contentView?.height ?: 0
        previousStartOffset = startOffset()
        false
    }

    init {
        AndroidUtilities.runOnUIThread(Runnable { onAttach() })
    }

    fun getAdjustingParent(): View = parent

    fun getAdjustingContentView(): ViewGroup? = contentView

    private fun animateHeight(previousHeight: Int, contentHeight: Int, isKeyboardVisible: Boolean) {
        if (ignoreOnce) {
            ignoreOnce = false
            return
        }
        if (!enabled) {
            return
        }
        startTransition(previousHeight, contentHeight, isKeyboardVisible)
        animator?.addUpdateListener { animation ->
            if (!usingInsetAnimator) {
                updateTransition(animation.animatedValue as Float)
            }
        }
        animator?.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!usingInsetAnimator) {
                    stopTransition()
                }
            }
        })
        animator?.duration = keyboardDuration
        animator?.interpolator = keyboardInterpolator

        if (needDelay) {
            needDelay = false
            startAfter = SystemClock.elapsedRealtime() + 100
            AndroidUtilities.runOnUIThread(delayedAnimationRunnable, 100)
        } else {
            animator?.start()
            startAfter = -1
        }
    }

    fun startTransition(previousHeight: Int, contentHeight: Int, isKeyboardVisible: Boolean) {
        animator?.cancel()

        val startOffset = startOffset()
        getViewsToSetHeight(parent)
        var additionalContentHeight = 0
        if (checkHierarchyHeight) {
            val viewParent = parent.parent
            if (viewParent is View) {
                additionalContentHeight = viewParent.height - contentHeight
            }
        }
        if (applyTranslation()) setViewHeight(Math.max(previousHeight, contentHeight + additionalContentHeight))
        resizableView?.requestLayout()

        onTransitionStart(isKeyboardVisible, previousHeight, contentHeight)

        val dy = (contentHeight - previousHeight).toFloat()
        keyboardSize = Math.abs(dy)

        animationInProgress = true
        showingKeyboard = contentHeight <= previousHeight
        if (contentHeight > previousHeight) {
            val adjustedDy = dy - startOffset
            if (applyTranslation()) parent.translationY = -adjustedDy
            onPanTranslationUpdate(adjustedDy, 1f, isKeyboardVisible)
            from = -adjustedDy
            to = 0f
            inverse = true
        } else {
            if (applyTranslation()) parent.translationY = previousStartOffset.toFloat()
            onPanTranslationUpdate(-previousStartOffset.toFloat(), 0f, isKeyboardVisible)
            to = -previousStartOffset.toFloat()
            from = dy
            inverse = false
        }
        animator = ValueAnimator.ofFloat(0f, 1f)
        usingInsetAnimator = false
    }

    fun updateTransition(t: Float) {
        var adjustedT = t
        if (inverse) {
            adjustedT = 1f - t
        }
        val y = (from * adjustedT + to * (1f - adjustedT)).toInt().toFloat()
        if (applyTranslation()) parent.translationY = y
        onPanTranslationUpdate(-y, adjustedT, isKeyboardVisible)
    }

    fun stopTransition() {
        animator?.cancel()
        animationInProgress = false
        usingInsetAnimator = false
        animator = null
        setViewHeight(ViewGroup.LayoutParams.MATCH_PARENT)
        viewsToHeightSet.clear()
        resizableView?.requestLayout()
        onPanTranslationUpdate(0f, if (isKeyboardVisible) 1f else 0f, isKeyboardVisible)
        if (applyTranslation()) parent.translationY = 0f
        onTransitionEnd()
    }

    fun stopTransition(t: Float, isKeyboardVisible: Boolean) {
        animator?.cancel()
        animationInProgress = false
        animator = null
        setViewHeight(ViewGroup.LayoutParams.MATCH_PARENT)
        viewsToHeightSet.clear()
        resizableView?.requestLayout()
        this.isKeyboardVisible = isKeyboardVisible
        onPanTranslationUpdate(0f, t, isKeyboardVisible)
        if (applyTranslation()) parent.translationY = 0f
        onTransitionEnd()
    }

    fun setViewHeight(height: Int) {
        for (i in 0 until viewsToHeightSet.size) {
            viewsToHeightSet[i].layoutParams.height = height
            viewsToHeightSet[i].requestLayout()
        }
    }

    protected open fun startOffset(): Int = 0

    fun getViewsToSetHeight(parent: View) {
        viewsToHeightSet.clear()
        var v: View? = parent
        while (v != null) {
            viewsToHeightSet.add(v)
            if (v == resizableView) {
                return
            }
            val vp = v.parent
            v = if (vp is View) vp else null
        }
    }

    fun onAttach() {
        onDetach()
        val context = parent.context
        val activity = getActivity(context)
        if (activity != null) {
            val decorView = activity.window.decorView as ViewGroup
            contentView = decorView.findViewById(Window.ID_ANDROID_CONTENT)
        }
        resizableView = findResizableView(parent)
        if (resizableView != null) {
            parentForListener = resizableView
            resizableView!!.viewTreeObserver.addOnPreDrawListener(onPreDrawListener)
        }
        if (useInsetsAnimator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setupNewCallback()
        }
    }

    private fun getActivity(context: Context): Activity? {
        return when (context) {
            is Activity -> context
            is ContextThemeWrapper -> getActivity(context.baseContext)
            else -> null
        }
    }

    private fun findResizableView(parent: View): View? {
        if (resizableViewToSet != null) {
            return resizableViewToSet
        }
        var view: View? = parent
        while (view != null) {
            if (view.parent is DrawerLayoutContainer) {
                return view
            }
            val vp = view.parent
            view = if (vp is View) vp else null
        }
        return null
    }

    fun onDetach() {
        animator?.cancel()
        if (parentForListener != null) {
            parentForListener!!.viewTreeObserver.removeOnPreDrawListener(onPreDrawListener)
            parentForListener = null
        }
        if (useInsetsAnimator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            parent.setWindowInsetsAnimationCallback(null)
        }
    }

    fun setEnabled(value: Boolean) {
        this.enabled = value
    }

    fun ignoreOnce() {
        ignoreOnce = true
    }

    protected open fun heightAnimationEnabled(): Boolean = true

    protected open fun applyTranslation(): Boolean = true

    fun OnPanTranslationUpdate(y: Float, progress: Float, keyboardVisible: Boolean) {
        onPanTranslationUpdate(y, progress, keyboardVisible)
    }

    fun OnTransitionStart(keyboardVisible: Boolean, contentHeight: Int) {
        onTransitionStart(keyboardVisible, contentHeight)
    }

    fun OnTransitionEnd() {
        onTransitionEnd()
    }

    protected open fun onPanTranslationUpdate(y: Float, progress: Float, keyboardVisible: Boolean) {}

    protected open fun onTransitionStart(keyboardVisible: Boolean, previousHeight: Int, contentHeight: Int) {
        onTransitionStart(keyboardVisible, contentHeight)
    }

    protected open fun onTransitionStart(keyboardVisible: Boolean, contentHeight: Int) {}

    protected open fun onTransitionEnd() {}

    fun setResizableView(windowView: FrameLayout) {
        resizableViewToSet = windowView
    }

    fun animationInProgress(): Boolean = animationInProgress


    fun delayAnimation() {
        needDelay = true
    }

    fun runDelayedAnimation() {
        AndroidUtilities.cancelRunOnUIThread(delayedAnimationRunnable)
        delayedAnimationRunnable.run()
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private fun setupNewCallback() {
        val rv = resizableView ?: return
        rv.setWindowInsetsAnimationCallback(
            object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsets,
                    runningAnimations: MutableList<WindowInsetsAnimation>
                ): WindowInsets {
                    if (!animationInProgress || AndroidUtilities.screenRefreshRate < 90) {
                        return insets
                    }

                    var imeAnimation: WindowInsetsAnimation? = null
                    for (animation in runningAnimations) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                            imeAnimation = animation
                            break
                        }
                    }

                    if (imeAnimation != null && SystemClock.elapsedRealtime() >= startAfter) {
                        usingInsetAnimator = true
                        updateTransition(imeAnimation.interpolatedFraction)
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimation) {
                    if (!animationInProgress || AndroidUtilities.screenRefreshRate < 90) {
                        return
                    }
                    stopTransition()
                }
            }
        )
    }
}
