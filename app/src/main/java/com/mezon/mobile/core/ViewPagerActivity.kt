package com.mezon.mobile.core

import android.content.Context
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

abstract class ViewPagerActivity : BaseFragment() {

    val fragmentStates = SparseArray<FragmentState>()
    lateinit var viewPager: ViewPagerFixed
    private var isResumedState = false
    private var isFullyVisibleState = false
    private var visibilityByParent = 1f
    private var maxFragmentsCacheCount = 3

    abstract fun getStartPosition(): Int
    abstract fun getFragmentsCount(): Int
    abstract fun createFragmentForPosition(position: Int): BaseFragment

    fun setMaxFragmentsCacheCount(count: Int) { maxFragmentsCacheCount = count }

    class FragmentState(val fragment: BaseFragment) {
        var onCreateCalled = false
        var isFullyVisible = false
        var isResumed = false
        var isInAnimation = false
        var lastVisibility = 0f

        fun setVisibility(
            visibilityByPage: Float,
            visibilityByParent: Float,
            parentIsFullyVisible: Boolean,
            parentIsResumed: Boolean
        ) {
            val oldVisibility = lastVisibility
            val newVisibility = visibilityByPage * visibilityByParent
            lastVisibility = newVisibility
            val isOpen = newVisibility > oldVisibility

            if (!isResumed && visibilityByPage > 0) {
                fragment.onResume()
                isResumed = true
            }
            if (!isInAnimation && (oldVisibility == 0f || oldVisibility == 1f)
                && oldVisibility != newVisibility
                && Math.abs(oldVisibility - newVisibility) != 1f
            ) {
                fragment.onTransitionAnimationStart(isOpen, false)
                isInAnimation = true
            }
            if (isInAnimation && oldVisibility != newVisibility) {
                fragment.onTransitionAnimationProgress(
                    isOpen,
                    if (isOpen) newVisibility else 1f - newVisibility
                )
            }
            if (isInAnimation && (newVisibility == 0f || newVisibility == 1f)) {
                fragment.onTransitionAnimationEnd(isOpen, false)
                isInAnimation = false
            }
            if (!isFullyVisible && newVisibility >= 1f) {
                fragment.onBecomeFullyVisible()
                isFullyVisible = true
            }
            if (isFullyVisible && (newVisibility == 0f && !parentIsFullyVisible || visibilityByPage == 0f)) {
                fragment.onBecomeFullyHidden()
                isFullyVisible = false
            }
            if (isResumed && (newVisibility == 0f && !parentIsResumed || visibilityByPage == 0f)) {
                fragment.onPause()
                isResumed = false
            }
        }
    }

    override fun createView(context: Context): View {
        viewPager = ViewPagerFixed(context)

        viewPager.setAdapter(object : ViewPagerFixed.Adapter() {
            override fun getItemCount(): Int = getFragmentsCount()

            override fun createView(viewType: Int): View {
                return FrameLayout(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }

            override fun bindView(view: View, position: Int, viewType: Int) {
                var state = fragmentStates.get(position)
                if (state == null) {
                    val fragment = createFragmentForPosition(position)
                    state = FragmentState(fragment)
                    fragmentStates.put(position, state)
                }

                val fragment = state.fragment
                if (!state.onCreateCalled) {
                    fragment.themeColors = themeColors
                    fragment.parentLayout = parentLayout
                    fragment.inject(requireContext())
                    fragment.onFragmentCreate()
                    state.onCreateCalled = true
                }

                if (fragment.fragmentView == null) {
                    fragment.createView(context)
                }

                val container = view as FrameLayout
                container.removeAllViews()
                val fragmentView = fragment.fragmentView
                if (fragmentView != null) {
                    (fragmentView.parent as? ViewGroup)?.removeView(fragmentView)
                    container.addView(
                        fragmentView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                val ab = fragment.actionBar
                if (ab != null && ab.shouldAddToContainer()) {
                    (ab.parent as? ViewGroup)?.removeView(ab)
                    container.addView(ab)
                }
            }

            override fun getItemTitle(position: Int): CharSequence {
                return getTabTitle(position)
            }
        })

        viewPager.onPageChangeListener = object : ViewPagerFixed.OnPageChangeListener {
            override fun onPageSelected(position: Int, forward: Boolean) {
                onTabSelected(position, forward)
                checkFragmentsVisibility()
            }

            override fun onPageScrolled(progress: Float) {
                onTabAnimationUpdate(progress)
                checkFragmentsVisibility()
            }

            override fun onScrollEnd() {
                checkFragmentsVisibility()
            }
        }

        viewPager.setPosition(getStartPosition())
        return viewPager
    }

    protected fun checkFragmentsVisibility() {
        for (i in 0 until fragmentStates.size()) {
            val position = fragmentStates.keyAt(i)
            val state = fragmentStates.valueAt(i) ?: continue
            if (state.fragment.fragmentView != null) {
                val pageVis = viewPager.getPositionVisibility(position)
                val pVis = if (isResumedState) visibilityByParent else 0f
                state.setVisibility(pageVis, pVis, isFullyVisibleState, isResumedState)
            }
        }
    }

    open fun getTabTitle(position: Int): CharSequence = ""

    open fun onTabSelected(position: Int, forward: Boolean) {}

    open fun onTabAnimationUpdate(progress: Float) {}

    fun getFragmentForPosition(position: Int): BaseFragment? =
        fragmentStates.get(position)?.fragment

    fun getCurrentFragment(): BaseFragment? =
        fragmentStates.get(viewPager.currentPosition)?.fragment

    override fun onResume() {
        super.onResume()
        isResumedState = true
        checkFragmentsVisibility()
    }

    override fun onPause() {
        super.onPause()
        isResumedState = false
        checkFragmentsVisibility()
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        isFullyVisibleState = true
        visibilityByParent = 1f
        checkFragmentsVisibility()
    }

    override fun onBecomeFullyHidden() {
        super.onBecomeFullyHidden()
        isFullyVisibleState = false
        visibilityByParent = 0f
        checkFragmentsVisibility()
    }

    override fun onTransitionAnimationProgress(isOpen: Boolean, progress: Float) {
        super.onTransitionAnimationProgress(isOpen, progress)
        visibilityByParent = if (isOpen) progress else 1f - progress
        checkFragmentsVisibility()
    }

    override fun clearViews() {
        for (i in 0 until fragmentStates.size()) {
            val state = fragmentStates.valueAt(i) ?: continue
            state.fragment.clearViews()
            state.isFullyVisible = false
            state.isResumed = false
            state.lastVisibility = 0f
        }
        super.clearViews()
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        for (i in 0 until fragmentStates.size()) {
            val state = fragmentStates.valueAt(i)
            if (state?.onCreateCalled == true) {
                state.fragment.onFragmentDestroy()
            }
        }
        fragmentStates.clear()
    }

    override fun onBackPressed(): Boolean {
        val current = getCurrentFragment()
        if (current != null && !current.onBackPressed()) {
            return false
        }
        return super.onBackPressed()
    }

    fun dropFragment(position: Int) {
        val state = fragmentStates.get(position) ?: return
        if (state.onCreateCalled) {
            state.fragment.onFragmentDestroy()
        }
        fragmentStates.remove(position)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        val current = viewPager.currentPosition
        val keys = ArrayList<Int>()
        for (i in 0 until fragmentStates.size()) {
            val key = fragmentStates.keyAt(i)
            if (key != current) keys.add(key)
        }
        for (key in keys) {
            if (fragmentStates.size() <= maxFragmentsCacheCount) break
            dropFragment(key)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        for (i in 0 until fragmentStates.size()) {
            fragmentStates.valueAt(i)?.fragment?.onConfigurationChanged(newConfig)
        }
    }

    override fun onInsets(insets: android.graphics.Rect) {
        super.onInsets(insets)
        for (i in 0 until fragmentStates.size()) {
            fragmentStates.valueAt(i)?.fragment?.onInsets(insets)
        }
    }

    fun getFragmentAtPosition(position: Int): BaseFragment? = fragmentStates.get(position)?.fragment
}
