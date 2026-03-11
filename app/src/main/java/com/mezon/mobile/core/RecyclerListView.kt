package com.mezon.mobile.core

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.recyclerview.widget.RecyclerView

open class RecyclerListView @JvmOverloads constructor(
    context: Context,
    private val resourcesProvider: ThemeColors.ResourcesProvider? = null
) : RecyclerView(context) {

    companion object {
        const val SECTIONS_TYPE_SIMPLE = 0
        const val SECTIONS_TYPE_STICKY_HEADERS = 1
        const val SECTIONS_TYPE_DATE = 2
        const val SECTIONS_TYPE_FAST_SCROLL_ONLY = 3

        const val EMPTY_VIEW_ANIMATION_TYPE_ALPHA = 0
        const val EMPTY_VIEW_ANIMATION_TYPE_ALPHA_SCALE = 1
    }

    fun interface OnItemClickListener {
        fun onItemClick(view: View, position: Int)
    }

    fun interface OnItemClickListenerExtended {
        fun onItemClick(view: View, position: Int, x: Float, y: Float)
        fun hasDoubleTap(view: View, position: Int): Boolean = false
        fun onDoubleTap(view: View, position: Int, x: Float, y: Float) {}
    }

    fun interface OnItemLongClickListener {
        fun onItemClick(view: View, position: Int): Boolean
    }

    fun interface OnItemLongClickListenerExtended {
        fun onItemClick(view: View, position: Int, x: Float, y: Float): Boolean
        fun onMove(dx: Float, dy: Float) {}
        fun onLongClickRelease() {}
    }

    fun interface OnInterceptTouchListener {
        fun onInterceptTouchEvent(event: MotionEvent): Boolean
    }

    fun interface IntReturnCallback {
        fun run(): Int
    }

    private var onItemClickListener: OnItemClickListener? = null
    private var onItemClickListenerExtended: OnItemClickListenerExtended? = null
    private var onItemLongClickListener: OnItemLongClickListener? = null
    private var onItemLongClickListenerExtended: OnItemLongClickListenerExtended? = null
    private var emptyView: View? = null
    private var animateEmptyView = false
    private var emptyViewAnimationType = 0
    private var hideIfEmpty = false

    private var selectorDrawable: Drawable? = null
    private var selectorType = 0
    private var selectorRadius = 0
    private var selectorPosition = NO_POSITION
    private var selectorRect = RectF()
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawSelectorBehind = false
    private var selectorColor = 0

    private var currentChildView: View? = null
    private var selectChildRunnable: Runnable? = null
    private var scrollingByUser = false
    private var isFastScrolling = false
    private var disallowInterceptTouchEvents = false
    private var allowItemsInteractionDuringAnimation = true
    private var scrollEnabled = true
    private var instantClick = false
    private var disableHighlightState = false

    private var highlightPosition = NO_POSITION
    private var highlightAnimator: ValueAnimator? = null
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(36, 0, 0, 0)
    }

    private val handler = Handler(Looper.getMainLooper())

    private val observer = object : AdapterDataObserver() {
        override fun onChanged() { checkIfEmpty() }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { checkIfEmpty() }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { checkIfEmpty() }
    }

    private var lastX = 0f
    private var lastY = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val child = currentChildView ?: return false
            val position = getChildAdapterPosition(child)
            if (position == NO_POSITION) return false
            if (!allowItemsInteractionDuringAnimation && itemAnimator?.isRunning == true) return false
            child.playSoundEffect(SoundEffectConstants.CLICK)
            child.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
            onItemClickListener?.let {
                it.onItemClick(child, position)
                return true
            }
            onItemClickListenerExtended?.let {
                it.onItemClick(child, position, e.x, e.y)
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            val child = currentChildView ?: return
            val position = getChildAdapterPosition(child)
            if (position == NO_POSITION) return
            if (!allowItemsInteractionDuringAnimation && itemAnimator?.isRunning == true) return
            var handled = false
            onItemLongClickListener?.let {
                handled = it.onItemClick(child, position)
            }
            onItemLongClickListenerExtended?.let {
                handled = it.onItemClick(child, position, e.x, e.y)
            }
            if (handled) {
                child.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                cancelChildPressed(child)
            }
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val child = currentChildView ?: return false
            val position = getChildAdapterPosition(child)
            if (position == NO_POSITION) return false
            onItemClickListenerExtended?.let {
                if (it.hasDoubleTap(child, position)) {
                    it.onDoubleTap(child, position, e.x, e.y)
                    return true
                }
            }
            return false
        }
    })

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        onItemClickListener = listener
    }

    fun setOnItemClickListener(listener: OnItemClickListenerExtended?) {
        onItemClickListenerExtended = listener
    }

    fun setOnItemLongClickListener(listener: OnItemLongClickListener?) {
        onItemLongClickListener = listener
    }

    fun setOnItemLongClickListener(listener: OnItemLongClickListenerExtended?) {
        onItemLongClickListenerExtended = listener
    }

    fun setEmptyView(view: View?) {
        emptyView = view
        checkIfEmpty()
    }

    fun setAnimateEmptyView(animate: Boolean, type: Int = 0) {
        animateEmptyView = animate
        emptyViewAnimationType = type
    }

    fun setHideIfEmpty(value: Boolean) {
        hideIfEmpty = value
    }

    fun setScrollEnabled(enabled: Boolean) {
        scrollEnabled = enabled
    }

    fun setInstantClick(value: Boolean) {
        instantClick = value
    }

    fun setDisableHighlightState(value: Boolean) {
        disableHighlightState = value
    }

    // ── Selector ────────────────────────────────────────────────────────────

    fun setSelectorType(type: Int) {
        selectorType = type
    }

    fun setSelectorRadius(radius: Int) {
        selectorRadius = radius
    }

    fun setSelectorDrawableColor(color: Int) {
        selectorColor = color
        selectorPaint.color = color
    }

    fun setDrawSelectorBehind(value: Boolean) {
        drawSelectorBehind = value
    }

    fun hideSelector(animated: Boolean = true) {
        selectorPosition = NO_POSITION
        invalidate()
    }

    // ── Highlight row ───────────────────────────────────────────────────────

    fun highlightRow(position: Int) {
        highlightAnimator?.cancel()
        highlightPosition = position
        highlightPaint.alpha = 36
        highlightAnimator = ValueAnimator.ofInt(36, 0).apply {
            duration = 700
            startDelay = 500
            addUpdateListener {
                highlightPaint.alpha = it.animatedValue as Int
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    highlightPosition = NO_POSITION
                }
            })
            start()
        }
        invalidate()
    }

    fun removeHighlightRow() {
        highlightAnimator?.cancel()
        highlightPosition = NO_POSITION
        invalidate()
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    override fun setAdapter(adapter: Adapter<*>?) {
        val old = getAdapter()
        old?.unregisterAdapterDataObserver(observer)
        super.setAdapter(adapter)
        adapter?.registerAdapterDataObserver(observer)
        checkIfEmpty()
    }

    private fun checkIfEmpty() {
        val a = adapter ?: return
        val ev = emptyView ?: return
        val empty = a.itemCount == 0
        if (animateEmptyView && SharedConfig.animationsEnabled()) {
            if (empty) {
                ev.visibility = VISIBLE
                if (emptyViewAnimationType == 1) {
                    ev.alpha = 0f
                    ev.scaleX = 0.7f
                    ev.scaleY = 0.7f
                    ev.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
                } else {
                    ev.alpha = 0f
                    ev.animate().alpha(1f).setDuration(150).start()
                }
            } else {
                if (emptyViewAnimationType == 1) {
                    ev.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(200)
                        .withEndAction { ev.visibility = GONE }.start()
                } else {
                    ev.animate().alpha(0f).setDuration(150)
                        .withEndAction { ev.visibility = GONE }.start()
                }
            }
        } else {
            ev.visibility = if (empty) VISIBLE else GONE
        }
        if (hideIfEmpty) {
            visibility = if (empty) INVISIBLE else VISIBLE
        } else {
            visibility = if (empty) INVISIBLE else VISIBLE
        }
    }

    // ── Touch handling ──────────────────────────────────────────────────────

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!scrollEnabled && layoutManager != null) {
            if (e.action == MotionEvent.ACTION_DOWN) return false
        }
        if (disallowInterceptTouchEvents) return super.onInterceptTouchEvent(e)
        onInterceptTouchListener?.let {
            if (it.onInterceptTouchEvent(e)) return true
        }
        if (onItemClickListener == null && onItemLongClickListener == null &&
            onItemClickListenerExtended == null && onItemLongClickListenerExtended == null) {
            return super.onInterceptTouchEvent(e)
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                val child = findChildViewUnder(e.x, e.y) ?: return super.onInterceptTouchEvent(e)
                currentChildView = child
                if (!disableHighlightState) {
                    selectChildRunnable?.let { handler.removeCallbacks(it) }
                    selectChildRunnable = Runnable {
                        currentChildView?.let { onChildPressed(it, true) }
                        selectChildRunnable = null
                    }
                    if (instantClick) {
                        selectChildRunnable!!.run()
                    } else {
                        handler.postDelayed(selectChildRunnable!!, ViewConfiguration.getTapTimeout().toLong())
                    }
                }
                gestureDetector.onTouchEvent(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectChildRunnable?.let {
                    handler.removeCallbacks(it)
                    selectChildRunnable = null
                }
                currentChildView?.let { onChildPressed(it, false) }
                gestureDetector.onTouchEvent(e)
                currentChildView = null
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    onItemLongClickListenerExtended?.onLongClickRelease()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                onItemLongClickListenerExtended?.onMove(e.x - lastX, e.y - lastY)
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!scrollEnabled) return false
        if (onItemClickListener != null || onItemLongClickListener != null ||
            onItemClickListenerExtended != null || onItemLongClickListenerExtended != null) {
            gestureDetector.onTouchEvent(e)
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            selectChildRunnable?.let {
                handler.removeCallbacks(it)
                selectChildRunnable = null
            }
            currentChildView?.let { onChildPressed(it, false) }
            currentChildView = null
        }
        return super.onTouchEvent(e)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (drawSelectorBehind && selectorPosition != NO_POSITION) {
            drawSelector(canvas)
        }
        super.dispatchDraw(canvas)
        if (!drawSelectorBehind && selectorPosition != NO_POSITION) {
            drawSelector(canvas)
        }
        if (highlightPosition != NO_POSITION) {
            drawHighlight(canvas)
        }
    }

    private fun drawSelector(canvas: Canvas) {
        val holder = findViewHolderForAdapterPosition(selectorPosition) ?: return
        val child = holder.itemView
        if (selectorRadius > 0) {
            canvas.drawRoundRect(
                child.left.toFloat(), child.top.toFloat(),
                child.right.toFloat(), child.bottom.toFloat(),
                selectorRadius.toFloat(), selectorRadius.toFloat(), selectorPaint
            )
        } else {
            canvas.drawRect(
                child.left.toFloat(), child.top.toFloat(),
                child.right.toFloat(), child.bottom.toFloat(), selectorPaint
            )
        }
    }

    private fun drawHighlight(canvas: Canvas) {
        val holder = findViewHolderForAdapterPosition(highlightPosition) ?: return
        val child = holder.itemView
        canvas.drawRect(
            child.left.toFloat(), child.top.toFloat(),
            child.right.toFloat(), child.bottom.toFloat(), highlightPaint
        )
    }

    private fun onChildPressed(child: View, pressed: Boolean) {
        if (!disableHighlightState) {
            child.isPressed = pressed
        }
    }

    private fun cancelChildPressed(child: View) {
        child.isPressed = false
        selectChildRunnable?.let {
            handler.removeCallbacks(it)
            selectChildRunnable = null
        }
    }

    fun setAllowItemsInteractionDuringAnimation(value: Boolean) {
        allowItemsInteractionDuringAnimation = value
    }

    fun invalidateViews() {
        for (i in 0 until childCount) {
            getChildAt(i).invalidate()
        }
    }

    fun getScrollingByUser(): Boolean = scrollingByUser

    fun isFastScrolling(): Boolean = isFastScrolling

    fun getPressedChildView(): View? = currentChildView

    fun clickItem(view: View, position: Int) {
        onItemClickListener?.onItemClick(view, position)
        onItemClickListenerExtended?.onItemClick(view, position, 0f, 0f)
    }

    fun longClickItem(view: View, position: Int) {
        onItemLongClickListener?.onItemClick(view, position)
        onItemLongClickListenerExtended?.onItemClick(view, position, 0f, 0f)
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        scrollingByUser = state == SCROLL_STATE_DRAGGING || state == SCROLL_STATE_SETTLING
        isFastScrolling = state == SCROLL_STATE_SETTLING
    }

    var useLayoutPositionOnClick = false
    var scrolledByUserOnce = false
    private var onInterceptTouchListener: OnInterceptTouchListener? = null
    private var sectionsType = SECTIONS_TYPE_SIMPLE
    private var accessibilityEnabled = true
    private var pinnedHeader: View? = null
    private val headers = ArrayList<View>()
    private val headersCache = ArrayList<View>()
    private var overlayContainer: android.widget.FrameLayout? = null
    private var onScrollListenerInternal: OnScrollListener? = null
    private var itemSelectorColorProvider: ((Int) -> Int?)? = null

    fun setOnInterceptTouchListener(listener: OnInterceptTouchListener?) {
        onInterceptTouchListener = listener
    }

    fun setSectionsType(@SuppressWarnings("unused") type: Int) {
        sectionsType = type
    }

    fun setAccessibilityEnabled(enabled: Boolean) {
        accessibilityEnabled = enabled
    }

    fun setDisallowInterceptTouchEvents(value: Boolean) {
        disallowInterceptTouchEvents = value
    }

    fun setItemSelectorColorProvider(provider: ((Int) -> Int?)?) {
        itemSelectorColorProvider = provider
    }

    override fun findChildViewUnder(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                return child
            }
        }
        return null
    }

    fun findViewByPosition(position: Int): View? {
        val lm = layoutManager ?: return null
        return lm.findViewByPosition(position)
    }

    fun getHeaders(): ArrayList<View> = headers
    fun getHeadersCache(): ArrayList<View> = headersCache
    fun getPinnedHeader(): View? = pinnedHeader

    fun addOverlayView(view: View, layoutParams: android.widget.FrameLayout.LayoutParams) {
        if (overlayContainer == null) {
            overlayContainer = android.widget.FrameLayout(context)
            addView(overlayContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        overlayContainer!!.addView(view, layoutParams)
    }

    fun removeOverlayView(view: View) {
        overlayContainer?.removeView(view)
    }

    @Suppress("DEPRECATION")
    override fun setOnScrollListener(listener: OnScrollListener?) {
        onScrollListenerInternal = listener
        if (listener != null) {
            addOnScrollListener(listener)
        }
    }

    fun getOnScrollListener(): OnScrollListener? = onScrollListenerInternal

    protected open fun canHighlightChildAt(child: View, x: Float, y: Float): Boolean = true

    protected open fun allowSelectChildAtPosition(x: Float, y: Float): Boolean = true

    protected open fun allowSelectChildAtPosition(child: View): Boolean = true

    protected open fun onChildPressed(child: View, x: Float, y: Float, pressed: Boolean) {}

    fun checkSection(force: Boolean) {}

    fun relayoutPinnedHeader() {}

    override fun hasOverlappingRendering(): Boolean = false

    fun drawSectionBackground(canvas: Canvas, fromPos: Int, toPos: Int, color: Int) {
        val paint = Paint()
        paint.color = color
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val pos = getChildAdapterPosition(child)
            if (pos in fromPos..toPos) {
                top = top.coerceAtMost(child.top)
                bottom = bottom.coerceAtLeast(child.bottom)
            }
        }
        if (top < bottom) {
            canvas.drawRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat(), paint)
        }
    }

    abstract class SelectionAdapter : Adapter<ViewHolder>() {
        abstract fun isEnabled(holder: ViewHolder): Boolean
        open fun getSelectionBottomPadding(holder: ViewHolder): Int = 0
    }

    abstract class FastScrollAdapter : SelectionAdapter() {
        abstract fun getLetter(position: Int): String
        abstract fun getPositionForScrollProgress(listView: RecyclerListView, progress: Float, position: IntArray)
        open fun onStartFastScroll() {}
        open fun onFinishFastScroll(listView: RecyclerListView) {}
        open fun getTotalItemsCount(): Int = itemCount
        open fun getScrollProgress(listView: RecyclerListView): Float = 0f
        open fun fastScrollIsVisible(listView: RecyclerListView): Boolean = true
        open fun onFastScrollSingleTap() {}
    }

    abstract class SectionsAdapter : FastScrollAdapter() {
        abstract fun getSectionCount(): Int
        abstract fun getCountForSection(section: Int): Int
        abstract fun isEnabled(holder: ViewHolder, section: Int, row: Int): Boolean
        abstract fun getItemViewType(section: Int, position: Int): Int
        abstract fun getItem(section: Int, position: Int): Any?
        abstract fun onBindViewHolder(section: Int, position: Int, holder: ViewHolder)
        abstract fun getSectionHeaderView(section: Int, view: View?): View?
        override fun getLetter(position: Int): String = ""
        override fun getPositionForScrollProgress(listView: RecyclerListView, progress: Float, position: IntArray) {}

        override fun isEnabled(holder: ViewHolder): Boolean = true

        fun getSectionForPosition(position: Int): Int {
            var count = 0
            for (s in 0 until getSectionCount()) {
                val sectionCount = getCountForSection(s)
                if (position < count + sectionCount) return s
                count += sectionCount
            }
            return 0
        }

        fun getPositionInSectionForPosition(position: Int): Int {
            var count = 0
            for (s in 0 until getSectionCount()) {
                val sectionCount = getCountForSection(s)
                if (position < count + sectionCount) return position - count
                count += sectionCount
            }
            return 0
        }

        override fun getItemCount(): Int {
            var count = 0
            for (s in 0 until getSectionCount()) count += getCountForSection(s)
            return count
        }
    }

    class Holder(itemView: View) : ViewHolder(itemView)
}
