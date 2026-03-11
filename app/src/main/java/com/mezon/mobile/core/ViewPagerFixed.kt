package com.mezon.mobile.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class ViewPagerFixed(context: Context) : FrameLayout(context) {

    abstract class Adapter {
        abstract fun getItemCount(): Int
        abstract fun createView(viewType: Int): View
        abstract fun bindView(view: View, position: Int, viewType: Int)
        open fun getItemId(position: Int): Int = position
        open fun getItemTitle(position: Int): CharSequence = ""
        open fun getItemViewType(position: Int): Int = 0
        open fun hasStableIds(): Boolean = false
        open fun canScrollTo(position: Int): Boolean = true
    }

    interface OnPageChangeListener {
        fun onPageSelected(position: Int, forward: Boolean)
        fun onPageScrolled(progress: Float) {}
        fun onScrollEnd() {}
    }

    var currentPosition = 0
        private set
    private var nextPosition = 0
    private var currentProgress = 1f

    val viewPages = arrayOfNulls<View>(2)
    private val viewTypes = intArrayOf(-1, -1)
    private val viewsByType = SparseArray<View>()

    var adapter: Adapter? = null
        private set
    var onPageChangeListener: OnPageChangeListener? = null

    private var maybeStartTracking = false
    private var startedTracking = false
    private var startedTrackingPointerId = -1
    private var startedTrackingX = 0
    private var startedTrackingY = 0
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop: Float
    private val maximumVelocity: Int

    private var tabsAnimation: AnimatorSet? = null
    private var tabsAnimationInProgress = false
    private var animatingForward = false
    private var additionalOffset = 0f
    private var backAnimation = false
    private var manualScrolling: ValueAnimator? = null
    private var manualScrollingCancelled = false
    private var allowDisallowInterceptTouch = true
    private var disableSliding = false
    var swipeEnabled = true
    private var notificationsLockerIndex = -1
    var notificationCenter: NotificationCenter? = null

    init {
        val vc = ViewConfiguration.get(context)
        touchSlop = AndroidUtilities.getPixelsInCM(0.3f, true)
        maximumVelocity = vc.scaledMaximumFlingVelocity
        clipChildren = true
    }

    private fun lockNotifications() {
        val nc = notificationCenter ?: return
        notificationsLockerIndex = nc.setAnimationInProgress(notificationsLockerIndex, null)
    }

    private fun unlockNotifications() {
        val nc = notificationCenter ?: return
        nc.onAnimationFinish(notificationsLockerIndex)
    }

    fun setAdapter(newAdapter: Adapter) {
        adapter = newAdapter
        viewTypes[0] = newAdapter.getItemViewType(currentPosition)
        viewPages[0] = newAdapter.createView(viewTypes[0])
        if (viewPages[0] == null && currentPosition != 0) {
            currentPosition = 0
            viewTypes[0] = newAdapter.getItemViewType(currentPosition)
            viewPages[0] = newAdapter.createView(viewTypes[0])
        }
        newAdapter.bindView(viewPages[0]!!, currentPosition, viewTypes[0])
        addView(viewPages[0])
        viewPages[0]?.visibility = VISIBLE
    }

    fun setPosition(position: Int) {
        if (adapter == null) {
            currentPosition = position
            return
        }
        if (tabsAnimation != null) {
            tabsAnimation!!.cancel()
        }
        if (viewPages[1] != null) {
            viewsByType.put(viewTypes[1], viewPages[1])
            removeView(viewPages[1])
            viewPages[1] = null
        }
        if (currentPosition != position) {
            currentPosition = position
            nextPosition = 0
            currentProgress = 1f
            updateViewForIndex(0)
            viewPages[0]?.translationX = 0f
        }
    }

    fun scrollToPosition(position: Int): Boolean {
        if (!swipeEnabled && tabsAnimationInProgress) return false
        if (position == currentPosition || (manualScrolling != null && nextPosition == position)) {
            return false
        }
        if (manualScrolling != null) {
            manualScrollingCancelled = true
            manualScrolling!!.cancel()
            manualScrolling = null
        }

        val forward = currentPosition < position
        animatingForward = forward
        nextPosition = position
        updateViewForIndex(1)

        onPageChangeListener?.onPageSelected(position, forward)

        if (!SharedConfig.animationsEnabled()) {
            if (viewPages[1] != null) {
                swapViews()
                viewsByType.put(viewTypes[1], viewPages[1])
                removeView(viewPages[1])
                viewPages[0]?.translationX = 0f
                viewPages[1] = null
            }
            onPageChangeListener?.onScrollEnd()
            return true
        }

        val tX = viewPages[0]?.measuredWidth ?: 0
        if (forward) {
            viewPages[1]?.translationX = tX.toFloat()
        } else {
            viewPages[1]?.translationX = -tX.toFloat()
        }

        manualScrollingCancelled = false
        manualScrolling = ValueAnimator.ofFloat(0f, 1f)
        manualScrolling!!.addUpdateListener { anm ->
            val progress = anm.animatedValue as Float
            if (viewPages[1] == null) return@addUpdateListener
            val w = viewPages[0]!!.measuredWidth.toFloat()
            if (animatingForward) {
                viewPages[1]?.translationX = w * (1f - progress)
                viewPages[0]?.translationX = -w * progress
            } else {
                viewPages[1]?.translationX = -w * (1f - progress)
                viewPages[0]?.translationX = w * progress
            }
            currentProgress = 1f - progress
            onPageChangeListener?.onPageScrolled(progress)
        }
        manualScrolling!!.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (viewPages[1] != null) {
                    if (!manualScrollingCancelled) {
                        swapViews()
                    }
                    viewsByType.put(viewTypes[1], viewPages[1])
                    removeView(viewPages[1])
                    viewPages[0]?.translationX = 0f
                    viewPages[1] = null
                }
                manualScrolling = null
                if (!manualScrollingCancelled) {
                    onPageChangeListener?.onScrollEnd()
                }
            }
        })
        manualScrolling!!.duration = 300L
        manualScrolling!!.interpolator = EASE_OUT_QUINT
        manualScrolling!!.start()
        return true
    }

    fun isManualScrolling(): Boolean = manualScrolling != null && manualScrolling!!.isRunning

    fun getCurrentView(): View? = viewPages[0]


    fun getPositionVisibility(position: Int): Float {
        if (measuredWidth == 0) {
            return (1 - Math.abs(currentPosition - position)).toFloat().coerceIn(0f, 1f)
        }
        return (1f - Math.abs(getPositionAnimated() - position)).coerceIn(0f, 1f)
    }

    private fun getPositionAnimated(): Float {
        var pos = 0f
        if (viewPages[0] != null && viewPages[0]!!.visibility == VISIBLE) {
            val availTx = measuredWidth.toFloat().coerceAtLeast(1f)
            val t = (1f - Math.abs(viewPages[0]!!.translationX / availTx)).coerceIn(0f, 1f)
            pos += currentPosition * t
        }
        if (viewPages[1] != null && viewPages[1]!!.visibility == VISIBLE) {
            val availTx = measuredWidth.toFloat().coerceAtLeast(1f)
            val t = (1f - Math.abs(viewPages[1]!!.translationX / availTx)).coerceIn(0f, 1f)
            pos += nextPosition * t
        }
        return pos
    }

    fun clearViews() {
        viewsByType.clear()
    }

    fun rebuild(animated: Boolean) {
        val a = adapter ?: return
        if (tabsAnimation != null) {
            tabsAnimation!!.cancel()
            tabsAnimation = null
        }
        if (viewPages[1] != null) {
            removeView(viewPages[1])
            viewPages[1] = null
        }
        if (a.getItemCount() == 0) {
            if (viewPages[0] != null) {
                removeView(viewPages[0])
                viewPages[0] = null
            }
            return
        }
        if (currentPosition > a.getItemCount() - 1) {
            currentPosition = a.getItemCount() - 1
        }
        if (currentPosition < 0) {
            currentPosition = 0
        }
        viewTypes[0] = a.getItemViewType(currentPosition)
        viewPages[0] = a.createView(viewTypes[0])
        a.bindView(viewPages[0]!!, currentPosition, viewTypes[0])
        addView(viewPages[0])
        viewPages[0]?.visibility = VISIBLE
    }

    private fun updateViewForIndex(index: Int) {
        val a = adapter ?: return
        val adapterPosition = if (index == 0) currentPosition else nextPosition
        if (adapterPosition < 0 || adapterPosition >= a.getItemCount()) return

        if (viewPages[index] == null) {
            viewTypes[index] = a.getItemViewType(adapterPosition)
            var v = viewsByType.get(viewTypes[index])
            if (v == null) {
                v = a.createView(viewTypes[index])
            } else {
                viewsByType.remove(viewTypes[index])
            }
            (v.parent as? ViewGroup)?.removeView(v)
            addView(v)
            viewPages[index] = v
            a.bindView(viewPages[index]!!, adapterPosition, viewTypes[index])
            viewPages[index]?.visibility = VISIBLE
        } else {
            if (viewTypes[index] == a.getItemViewType(adapterPosition)) {
                a.bindView(viewPages[index]!!, adapterPosition, viewTypes[index])
                viewPages[index]?.visibility = VISIBLE
            } else {
                viewsByType.put(viewTypes[index], viewPages[index])
                viewPages[index]?.visibility = GONE
                removeView(viewPages[index])
                viewTypes[index] = a.getItemViewType(adapterPosition)
                var v = viewsByType.get(viewTypes[index])
                if (v == null) {
                    v = a.createView(viewTypes[index])
                } else {
                    viewsByType.remove(viewTypes[index])
                }
                addView(v)
                viewPages[index] = v
                viewPages[index]?.visibility = VISIBLE
                a.bindView(viewPages[index]!!, adapterPosition, a.getItemViewType(adapterPosition))
            }
        }
    }

    protected fun swapViews() {
        val page = viewPages[0]
        viewPages[0] = viewPages[1]
        viewPages[1] = page
        var p = currentPosition
        currentPosition = nextPosition
        nextPosition = p
        currentProgress = 1f - currentProgress
        p = viewTypes[0]
        viewTypes[0] = viewTypes[1]
        viewTypes[1] = p
    }

    private fun translateAnimator(view: View, toTx: Float): ValueAnimator {
        val a = ValueAnimator.ofFloat(view.translationX, toTx)
        a.addUpdateListener { animation ->
            view.translationX = animation.animatedValue as Float
        }
        a.addListener(object : AnimatorListenerAdapter() {
            var canceled = false
            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }
            override fun onAnimationEnd(animation: Animator) {
                if (!canceled) {
                    view.translationX = toTx
                }
            }
        })
        return a
    }

    fun checkTabsAnimationInProgress(): Boolean {
        if (tabsAnimationInProgress) {
            var cancel = false
            if (backAnimation) {
                if (Math.abs(viewPages[0]?.translationX ?: 0f) < 1) {
                    viewPages[0]?.translationX = 0f
                    viewPages[1]?.translationX =
                        (viewPages[0]?.measuredWidth ?: 0).toFloat() * if (animatingForward) 1 else -1
                    cancel = true
                }
            } else if (Math.abs(viewPages[1]?.translationX ?: 0f) < 1) {
                viewPages[0]?.translationX =
                    (viewPages[0]?.measuredWidth ?: 0).toFloat() * if (animatingForward) -1 else 1
                viewPages[1]?.translationX = 0f
                cancel = true
            }
            if (cancel) {
                tabsAnimation?.cancel()
                tabsAnimation = null
                tabsAnimationInProgress = false
            }
            return tabsAnimationInProgress
        }
        return false
    }

    private fun prepareForMoving(ev: MotionEvent, forward: Boolean): Boolean {
        val a = adapter ?: return false
        if (!forward && currentPosition == 0) return false
        if (forward && currentPosition == a.getItemCount() - 1) return false
        if (manualScrolling != null) return false
        if (!a.canScrollTo(currentPosition + if (forward) 1 else -1)) return false

        parent?.requestDisallowInterceptTouchEvent(true)
        maybeStartTracking = false
        startedTracking = true
        startedTrackingX = (ev.x + additionalOffset).toInt()
        swipeEnabled = false
        lockNotifications()

        animatingForward = forward
        nextPosition = currentPosition + if (forward) 1 else -1
        updateViewForIndex(1)
        if (viewPages[1] != null) {
            val w = viewPages[0]?.measuredWidth ?: 0
            viewPages[1]!!.translationX = if (forward) w.toFloat() else -w.toFloat()
        }
        return true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (checkTabsAnimationInProgress()) return true
        onTouchEvent(ev)
        return startedTracking
    }

    override fun onTouchEvent(ev: MotionEvent?): Boolean {
        return onTouchEventInternal(ev)
    }

    private fun onTouchEventInternal(ev: MotionEvent?): Boolean {
        if (disableSliding) return false
        val a = adapter
        if (a == null || a.getItemCount() <= 1) return false

        if (ev != null) {
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
            velocityTracker!!.addMovement(ev)
        }

        if (ev != null && ev.action == MotionEvent.ACTION_DOWN && checkTabsAnimationInProgress()) {
            startedTracking = true
            startedTrackingPointerId = ev.getPointerId(0)
            startedTrackingX = ev.x.toInt()
            val positionBefore = currentPosition
            if (animatingForward) {
                if (startedTrackingX < (viewPages[0]?.measuredWidth ?: 0) + (viewPages[0]?.translationX?.toInt() ?: 0)) {
                    additionalOffset = viewPages[0]?.translationX ?: 0f
                } else {
                    swapViews()
                    animatingForward = false
                    additionalOffset = viewPages[0]?.translationX ?: 0f
                }
            } else if (viewPages[1] != null) {
                if (startedTrackingX < (viewPages[1]?.measuredWidth ?: 0) + (viewPages[1]?.translationX?.toInt() ?: 0)) {
                    swapViews()
                    animatingForward = true
                    additionalOffset = viewPages[0]?.translationX ?: 0f
                } else {
                    additionalOffset = viewPages[0]?.translationX ?: 0f
                }
            }
            tabsAnimation?.removeAllListeners()
            tabsAnimation?.cancel()
            tabsAnimationInProgress = false
            if (currentPosition != positionBefore) {
                onPageChangeListener?.onPageSelected(currentPosition, animatingForward)
            }
        } else if (ev != null && ev.action == MotionEvent.ACTION_DOWN) {
            additionalOffset = 0f
        }

        if (!startedTracking && ev != null) {
            val child = findHorizontallyScrollableChild(this, ev.x, ev.y)
            if (child != null && (child.canScrollHorizontally(1) || child.canScrollHorizontally(-1))) {
                return false
            }
        }

        if (ev != null && ev.action == MotionEvent.ACTION_DOWN && !startedTracking && !maybeStartTracking) {
            startedTrackingPointerId = ev.getPointerId(0)
            maybeStartTracking = true
            startedTrackingX = ev.x.toInt()
            startedTrackingY = ev.y.toInt()
        } else if (ev != null && ev.action == MotionEvent.ACTION_MOVE && ev.getPointerId(0) == startedTrackingPointerId) {
            val dx = (ev.x - startedTrackingX + additionalOffset).toInt()
            val dy = Math.abs(ev.y.toInt() - startedTrackingY)

            if (startedTracking && (animatingForward && dx > 0 || !animatingForward && dx < 0)) {
                if (!prepareForMoving(ev, dx < 0)) {
                    maybeStartTracking = true
                    startedTracking = false
                    viewPages[0]?.translationX = 0f
                    val w = viewPages[0]?.measuredWidth ?: 0
                    viewPages[1]?.translationX = if (animatingForward) w.toFloat() else -w.toFloat()
                    nextPosition = 0
                    currentProgress = 1f
                }
            }
            if (maybeStartTracking && !startedTracking) {
                val dxLocal = (ev.x - startedTrackingX).toInt()
                if (Math.abs(dxLocal) >= touchSlop && Math.abs(dxLocal) > dy) {
                    prepareForMoving(ev, dx < 0)
                }
            } else if (startedTracking) {
                val w = (viewPages[0]?.measuredWidth ?: 0).toFloat()
                if (w > 0) {
                    viewPages[0]?.translationX = dx.toFloat()
                    if (viewPages[1] != null) {
                        if (animatingForward) {
                            viewPages[1]!!.translationX = w + dx
                        } else {
                            viewPages[1]!!.translationX = dx - w
                        }
                    }
                    val scrollProgress = Math.abs(dx) / w
                    currentProgress = 1f - scrollProgress
                    onPageChangeListener?.onPageScrolled(scrollProgress)
                }
            }
        } else if (ev == null || (ev.getPointerId(0) == startedTrackingPointerId &&
                    (ev.action == MotionEvent.ACTION_CANCEL || ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_POINTER_UP))) {
            velocityTracker?.computeCurrentVelocity(1000, maximumVelocity.toFloat())
            val velX: Float
            val velY: Float
            if (ev != null && ev.action != MotionEvent.ACTION_CANCEL) {
                velX = velocityTracker?.xVelocity ?: 0f
                velY = velocityTracker?.yVelocity ?: 0f
                if (!startedTracking) {
                    if (Math.abs(velX) >= 3000 && Math.abs(velX) > Math.abs(velY)) {
                        prepareForMoving(ev, velX < 0)
                    }
                }
            } else {
                velX = 0f
                velY = 0f
            }
            if (startedTracking) {
                if (!SharedConfig.animationsEnabled()) {
                    val shouldSwap = run {
                        val x = viewPages[0]?.x ?: 0f
                        Math.abs(x) >= viewPages[0]!!.measuredWidth / 3.0f
                    }
                    if (shouldSwap && viewPages[1] != null) {
                        swapViews()
                    }
                    if (viewPages[1] != null) {
                        viewsByType.put(viewTypes[1], viewPages[1])
                        removeView(viewPages[1])
                        viewPages[1]?.visibility = GONE
                        viewPages[1] = null
                    }
                    viewPages[0]?.translationX = 0f
                    tabsAnimationInProgress = false
                    maybeStartTracking = false
                    startedTracking = false
                    swipeEnabled = true
                    onPageChangeListener?.onPageSelected(currentPosition, animatingForward)
                    onPageChangeListener?.onScrollEnd()
                    unlockNotifications()
                    return false
                }
                val x = viewPages[0]?.x ?: 0f
                tabsAnimation = AnimatorSet()
                if (additionalOffset != 0f) {
                    if (Math.abs(velX) > 1500) {
                        backAnimation = if (animatingForward) velX > 0 else velX < 0
                    } else {
                        if (animatingForward) {
                            backAnimation = if (viewPages[1] != null) {
                                viewPages[1]!!.x > (viewPages[0]!!.measuredWidth shr 1)
                            } else {
                                false
                            }
                        } else {
                            backAnimation = viewPages[0]!!.x < (viewPages[0]!!.measuredWidth shr 1)
                        }
                    }
                } else {
                    backAnimation = Math.abs(x) < viewPages[0]!!.measuredWidth / 3.0f &&
                            (Math.abs(velX) < 3500 || Math.abs(velX) < Math.abs(velY))
                }
                var dx = 0f
                if (backAnimation) {
                    dx = Math.abs(x)
                    if (animatingForward) {
                        tabsAnimation!!.playTogether(translateAnimator(viewPages[0]!!, 0f))
                        if (viewPages[1] != null) {
                            tabsAnimation!!.playTogether(translateAnimator(viewPages[1]!!, viewPages[1]!!.measuredWidth.toFloat()))
                        }
                    } else {
                        tabsAnimation!!.playTogether(translateAnimator(viewPages[0]!!, 0f))
                        if (viewPages[1] != null) {
                            tabsAnimation!!.playTogether(translateAnimator(viewPages[1]!!, -viewPages[1]!!.measuredWidth.toFloat()))
                        }
                    }
                } else if (nextPosition >= 0) {
                    dx = viewPages[0]!!.measuredWidth - Math.abs(x)
                    if (animatingForward) {
                        tabsAnimation!!.playTogether(translateAnimator(viewPages[0]!!, -viewPages[0]!!.measuredWidth.toFloat()))
                        if (viewPages[1] != null) {
                            tabsAnimation!!.playTogether(translateAnimator(viewPages[1]!!, 0f))
                        }
                    } else {
                        tabsAnimation!!.playTogether(translateAnimator(viewPages[0]!!, viewPages[0]!!.measuredWidth.toFloat()))
                        if (viewPages[1] != null) {
                            tabsAnimation!!.playTogether(translateAnimator(viewPages[1]!!, 0f))
                        }
                    }
                }
                val progressAnimator = ValueAnimator.ofFloat(0f, 1f)
                progressAnimator.addUpdateListener {
                    if (tabsAnimationInProgress) {
                        val w = (viewPages[0]?.measuredWidth ?: 1).toFloat()
                        val scrollProgress = Math.abs(viewPages[0]?.translationX ?: 0f) / w
                        currentProgress = 1f - scrollProgress
                        onPageChangeListener?.onPageScrolled(scrollProgress)
                    }
                }
                tabsAnimation!!.playTogether(progressAnimator)
                tabsAnimation!!.interpolator = TELEGRAM_INTERPOLATOR

                val width = measuredWidth
                val halfWidth = width / 2
                val distanceRatio = (1.0f * dx / width.toFloat()).coerceAtMost(1.0f)
                val distance = halfWidth.toFloat() + halfWidth.toFloat() * distanceInfluenceForSnapDuration(distanceRatio)
                val absVelX = Math.abs(velX)
                val duration: Int = if (absVelX > 0) {
                    4 * Math.round(1000.0f * Math.abs(distance / absVelX))
                } else {
                    val pageDelta = dx / measuredWidth
                    ((pageDelta + 1.0f) * 100.0f).toInt()
                }
                tabsAnimation!!.duration = duration.toLong().coerceIn(150, 600)

                tabsAnimation!!.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        tabsAnimation = null
                        if (viewPages[1] != null) {
                            if (!backAnimation) {
                                swapViews()
                            }
                            viewsByType.put(viewTypes[1], viewPages[1])
                            removeView(viewPages[1])
                            viewPages[1]?.visibility = GONE
                            viewPages[1] = null
                        }
                        tabsAnimationInProgress = false
                        maybeStartTracking = false
                        swipeEnabled = true
                        onPageChangeListener?.onPageSelected(currentPosition, animatingForward)
                        onPageChangeListener?.onScrollEnd()
                        unlockNotifications()
                    }
                })
                tabsAnimation!!.start()
                tabsAnimationInProgress = true
                startedTracking = false
            } else {
                maybeStartTracking = false
                swipeEnabled = true
                unlockNotifications()
            }
            if (velocityTracker != null) {
                velocityTracker!!.recycle()
                velocityTracker = null
            }
        }
        return startedTracking || maybeStartTracking
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (allowDisallowInterceptTouch && maybeStartTracking && !startedTracking) {
            onTouchEvent(null)
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    fun getItemsCount(): Int = adapter?.getItemCount() ?: 0

    fun isAnimating(): Boolean = tabsAnimationInProgress || (manualScrolling?.isRunning == true)

    fun isAtStart(): Boolean = currentPosition <= 0

    fun isAtEnd(): Boolean {
        val count = adapter?.getItemCount() ?: 0
        return count <= 0 || currentPosition >= count - 1
    }

    fun setAllowDisallowInterceptTouch(allow: Boolean) {
        allowDisallowInterceptTouch = allow
    }

    fun setDisableSliding(disable: Boolean) {
        disableSliding = disable
    }

    fun fillTabs(tabsView: TabsView?) {
        val a = adapter ?: return
        if (tabsView == null) return
        tabsView.removeAllTabs()
        for (i in 0 until a.getItemCount()) {
            tabsView.addTab(a.getItemId(i), a.getItemTitle(i))
        }
        if (currentPosition in 0 until a.getItemCount()) {
            tabsView.selectTabByPosition(currentPosition)
        }
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        if (direction == 0) return false
        if (tabsAnimationInProgress || startedTracking) return true
        val a = adapter ?: return false
        val forward = direction > 0
        if (!forward && currentPosition == 0) return false
        if (forward && currentPosition == a.getItemCount() - 1) return false
        return true
    }

    class TabsView(context: Context, private val viewPager: ViewPagerFixed) : android.widget.HorizontalScrollView(context) {

        interface TabsViewDelegate {
            fun canPerformActions(): Boolean = true
            fun onPageSelected(page: Int, forward: Boolean) {}
            fun onPageScrolled(progress: Float) {}
            fun getTabCounter(tabIndex: Int): Int = 0
        }

        var delegate: TabsViewDelegate? = null
        private val tabsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private var selectedTabId = -1
        private var currentTabCount = 0
        private val tabViews = ArrayList<TextView>()
        private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var indicatorX = 0f
        private var indicatorWidth = 0f
        private var animatingIndicator = false

        init {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(tabsContainer, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            setWillNotDraw(false)
            indicatorPaint.color = ThemeColors.instance.primary
        }

        fun setIndicatorColor(color: Int) {
            indicatorPaint.color = color
            invalidate()
        }

        fun addTab(id: Int, title: CharSequence) {
            val tab = TextView(context).apply {
                text = title
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                typeface = AndroidUtilities.bold()
                val pad = LayoutHelper.dp(16)
                setPadding(pad, 0, pad, 0)
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                tag = id
                setOnClickListener {
                    val idx = tabViews.indexOf(this)
                    if (idx >= 0 && idx != viewPager.currentPosition) {
                        viewPager.scrollToPosition(idx)
                        delegate?.onPageSelected(idx, idx > viewPager.currentPosition)
                    }
                }
            }
            tabViews.add(tab)
            tabsContainer.addView(tab, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
            ))
            currentTabCount++
            updateColors()
        }

        fun removeAllTabs() {
            tabViews.clear()
            tabsContainer.removeAllViews()
            currentTabCount = 0
        }

        fun selectTab(id: Int) {
            selectedTabId = id
            for (i in tabViews.indices) {
                if (tabViews[i].tag == id) {
                    animateIndicatorTo(i)
                    break
                }
            }
            updateColors()
        }

        fun selectTabByPosition(position: Int) {
            if (position in 0 until tabViews.size) {
                selectedTabId = tabViews[position].tag as? Int ?: position
                animateIndicatorTo(position)
                updateColors()
            }
        }

        private fun animateIndicatorTo(position: Int) {
            if (position !in tabViews.indices) return
            val tab = tabViews[position]
            val targetX = tab.left.toFloat()
            val targetW = tab.width.toFloat()
            if (!SharedConfig.animationsEnabled()) {
                indicatorX = targetX
                indicatorWidth = targetW
                invalidate()
                return
            }
            animatingIndicator = true
            val startX = indicatorX
            val startW = indicatorWidth
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                addUpdateListener {
                    val p = it.animatedValue as Float
                    indicatorX = AndroidUtilities.lerp(startX, targetX, p)
                    indicatorWidth = AndroidUtilities.lerp(startW, targetW, p)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        animatingIndicator = false
                    }
                })
            }
            anim.start()
        }

        fun isAnimatingIndicator(): Boolean = animatingIndicator

        private fun updateColors() {
            val activeColor = ThemeColors.instance.primary
            val inactiveColor = ThemeColors.instance.getColor(ThemeColors.key_text_secondary)
            for (i in tabViews.indices) {
                val tab = tabViews[i]
                val isSelected = tab.tag == selectedTabId
                tab.setTextColor(if (isSelected) activeColor else inactiveColor)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (indicatorWidth > 0) {
                val indicatorHeight = LayoutHelper.dp(3).toFloat()
                val y = height - indicatorHeight
                val radius = LayoutHelper.dp(1.5f).toFloat()
                canvas.drawRoundRect(indicatorX, y, indicatorX + indicatorWidth, height.toFloat(), radius, radius, indicatorPaint)
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (changed && !animatingIndicator) {
                val pos = viewPager.currentPosition
                if (pos in tabViews.indices) {
                    indicatorX = tabViews[pos].left.toFloat()
                    indicatorWidth = tabViews[pos].width.toFloat()
                    invalidate()
                }
            }
        }

        fun getTabCount(): Int = currentTabCount

        fun getTab(index: Int): View? = tabViews.getOrNull(index)
    }

    companion object {
        private val TELEGRAM_INTERPOLATOR: Interpolator = Interpolator { t ->
            val t2 = t - 1.0f
            t2 * t2 * t2 * t2 * t2 + 1.0f
        }

        private val EASE_OUT_QUINT: Interpolator = Interpolator { t ->
            val t2 = t - 1.0f
            t2 * t2 * t2 * t2 * t2 + 1.0f
        }

        private val hitRect = Rect()

        fun distanceInfluenceForSnapDuration(f: Float): Float {
            var value = f - 0.5f
            value *= 0.47123894f
            return Math.sin(value.toDouble()).toFloat()
        }

        fun findHorizontallyScrollableChild(parent: ViewGroup, x: Float, y: Float): View? {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child.visibility != VISIBLE) continue
                child.getHitRect(hitRect)
                if (hitRect.contains(x.toInt(), y.toInt())) {
                    if (child.canScrollHorizontally(-1) || child.canScrollHorizontally(1)) return child
                    if (child is ViewGroup) {
                        val found = findHorizontallyScrollableChild(
                            child,
                            x - hitRect.left,
                            y - hitRect.top
                        )
                        if (found != null) return found
                    }
                }
            }
            return null
        }
    }
}
