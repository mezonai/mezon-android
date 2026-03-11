package com.mezon.mobile.core

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat

open class BottomSheet(context: Context, private val needFocusable: Boolean = false) : Dialog(context) {

    interface BottomSheetDelegateInterface {
        fun onOpenAnimationStart() {}
        fun onOpenAnimationEnd() {}
        fun canDismiss(): Boolean = true
    }

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

        fun setTextColor(color: Int) {
            textView.setTextColor(color)
        }

        fun setIconColor(color: Int) {
            imageView.setColorFilter(color)
        }

        fun setChecked(value: Boolean) {
            checked = value
        }
    }

    private val theme: ThemeColors get() = ThemeColors.instance
    private var containerView: ContainerView? = null
    private var customView: View? = null
    private var containerHeight = ViewGroup.LayoutParams.WRAP_CONTENT
    private var dismissed = false
    private var allowCustomAnimation = true
    private var canDismissWithSwipe = true
    private var canDismissWithTouchOutside = true
    private var dimAlpha = 0.5f
    private var dimBehind = true
    private var useFullWidth = false
    private var useFullscreen = false
    private var drawNavigationBar = false
    private var navBarColor = 0

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

    protected var titleText: CharSequence? = null
    private var bigTitle = false
    private var titleView: TextView? = null
    private var items: Array<CharSequence>? = null
    private var itemIcons: IntArray? = null
    private var itemClickListener: OnClickListener? = null
    private var contentLayout: LinearLayout? = null
    private var showWithoutAnimation = false
    private var applyTopPadding = true
    private var applyBottomPadding = true
    private var allowNestedScroll = true
    private var overlayNavBarColor = 0
    private var calcMandatoryInsets = false
    private var onHideListener: DialogInterface.OnDismissListener? = null

    fun interface OnClickListener {
        fun onClick(dialog: BottomSheet, which: Int)
    }

    val container: ContainerView? get() = containerView

    fun setCustomView(view: View?) { customView = view }
    fun getCustomView(): View? = customView
    override fun setTitle(title: CharSequence?) { titleText = title }
    fun setTitle(title: CharSequence?, big: Boolean) { titleText = title; bigTitle = big }
    fun setApplyTopPadding(value: Boolean) { applyTopPadding = value }
    fun setApplyBottomPadding(value: Boolean) { applyBottomPadding = value }
    fun setAllowNestedScroll(value: Boolean) { allowNestedScroll = value }
    fun setCanDismissWithSwipe(value: Boolean) { canDismissWithSwipe = value }
    fun setCanDismissWithTouchOutside(value: Boolean) { canDismissWithTouchOutside = value }
    fun setOnHideListener(listener: DialogInterface.OnDismissListener?) { onHideListener = listener }
    override fun isShowing(): Boolean = !dismissed
    fun getContainerView(): ViewGroup? = containerView
    fun getSheetContainer(): ViewGroup? = containerView
    fun setOverlayNavBarColor(color: Int) {
        overlayNavBarColor = color
        navBarColor = color
        if (color != 0) drawNavigationBar = true
    }
    fun setCalcMandatoryInsets(value: Boolean) {
        calcMandatoryInsets = value
        if (value) drawNavigationBar = true
    }
    fun setAllowCustomAnimation(value: Boolean) { allowCustomAnimation = value }
    fun setDimBehind(dim: Boolean) { dimBehind = dim; dimAlpha = if (dim) 0.5f else 0f }
    fun setDimBehindAlpha(alpha: Float) { dimAlpha = alpha }
    fun setUseFullWidth(value: Boolean) { useFullWidth = value }
    fun setUseFullscreen(value: Boolean) { useFullscreen = value }
    fun setShowWithoutAnimation(value: Boolean) { showWithoutAnimation = value }
    fun setDrawNavigationBar(draw: Boolean) { drawNavigationBar = draw }
    fun setNavigationBarColor(color: Int) { navBarColor = color }

    fun fixNavigationBar() {
        drawNavigationBar = true
        navBarColor = theme.getColor(ThemeColors.key_sheetBackground)
    }

    fun setItems(itemTexts: Array<CharSequence>, icons: IntArray? = null, listener: OnClickListener?) {
        items = itemTexts
        itemIcons = icons
        itemClickListener = listener
    }

    fun setItemText(index: Int, text: CharSequence) {
        contentLayout?.let { layout ->
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child is BottomSheetCell && child.tag == index) {
                    child.textView.text = text
                    return
                }
            }
        }
    }

    fun setItemColor(index: Int, textColor: Int, iconColor: Int) {
        contentLayout?.let { layout ->
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                if (child is BottomSheetCell && child.tag == index) {
                    child.setTextColor(textColor)
                    child.setIconColor(iconColor)
                    return
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(context)
        containerView = ContainerView(context)

        val bgDrawable = GradientDrawable().apply {
            setColor(theme.getColor(ThemeColors.key_sheetBackground))
            cornerRadii = floatArrayOf(
                LayoutHelper.dp(14).toFloat(), LayoutHelper.dp(14).toFloat(),
                LayoutHelper.dp(14).toFloat(), LayoutHelper.dp(14).toFloat(),
                0f, 0f, 0f, 0f
            )
        }
        containerView!!.background = bgDrawable

        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val handleView = View(context).apply {
            background = GradientDrawable().apply {
                setColor(theme.getColor(ThemeColors.key_sheetHandle))
                cornerRadius = LayoutHelper.dp(2).toFloat()
            }
        }
        contentLayout!!.addView(handleView, LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = if (applyTopPadding) LayoutHelper.dp(10) else 0
            bottomMargin = if (applyBottomPadding) LayoutHelper.dp(10) else 0
        })

        titleText?.let { title ->
            titleView = TextView(context).apply {
                text = title
                setTextColor(theme.getColor(if (bigTitle) ThemeColors.key_dialogTextBlack else ThemeColors.key_sheetTitle))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, if (bigTitle) 20f else 16f)
                if (bigTitle) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(
                    LayoutHelper.dp(24),
                    LayoutHelper.dp(if (applyTopPadding) 4 else 0),
                    LayoutHelper.dp(24),
                    LayoutHelper.dp(12)
                )
                gravity = Gravity.START
            }
            contentLayout!!.addView(titleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

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
            }
        }

        customView?.let { view ->
            if (view.parent is ViewGroup) (view.parent as ViewGroup).removeView(view)
            contentLayout!!.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (containerHeight == ViewGroup.LayoutParams.WRAP_CONTENT) LinearLayout.LayoutParams.WRAP_CONTENT
                else containerHeight
            ))
        }

        containerView!!.addView(contentLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(containerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        setContentView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        configureWindow()

        root.setOnClickListener { if (isShowing && canDismissWithTouchOutside) dismiss() }
    }

    private fun configureWindow() {
        window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            val lp = w.attributes
            lp.width = if (useFullWidth) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM
            lp.dimAmount = dimAlpha
            if (dimBehind) lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            if (needFocusable) {
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            if (drawNavigationBar && navBarColor != 0) {
                w.navigationBarColor = navBarColor
            }
            w.attributes = lp
        }
    }

    override fun show() {
        super.show()
        dismissed = false
        delegate?.onOpenAnimationStart()
        if (allowCustomAnimation && SharedConfig.animationsEnabled() && !showWithoutAnimation) {
            containerView?.let { cv ->
                cv.translationY = cv.height.toFloat().coerceAtLeast(AndroidUtilities.dp(300).toFloat())
                cv.animate()
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .withEndAction { delegate?.onOpenAnimationEnd() }
                    .start()
            }
        } else {
            delegate?.onOpenAnimationEnd()
        }
    }

    override fun dismiss() {
        if (dismissed) return
        if (delegate?.canDismiss() == false) return
        dismissed = true
        onHideListener?.onDismiss(this)
        if (allowCustomAnimation && SharedConfig.animationsEnabled() && !showWithoutAnimation) {
            containerView?.let { cv ->
                cv.animate()
                    .translationY(cv.height.toFloat())
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .withEndAction { super.dismiss() }
                    .start()
            } ?: super.dismiss()
        } else {
            super.dismiss()
        }
    }

    fun dismissWithButtonClick(which: Int) {
        if (dismissed) return
        dismissed = true
        if (allowCustomAnimation && SharedConfig.animationsEnabled() && !showWithoutAnimation) {
            containerView?.let { cv ->
                cv.animate()
                    .translationY(cv.height.toFloat())
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .withEndAction {
                        itemClickListener?.onClick(this, which)
                        onHideListener?.onDismiss(this)
                        super.dismiss()
                    }
                    .start()
            } ?: run {
                itemClickListener?.onClick(this, which)
                onHideListener?.onDismiss(this)
                super.dismiss()
            }
        } else {
            itemClickListener?.onClick(this, which)
            onHideListener?.onDismiss(this)
            super.dismiss()
        }
    }

    inner class ContainerView(context: Context) : FrameLayout(context), NestedScrollingParent3 {

        private val nestedHelper = NestedScrollingParentHelper(this)
        private var startedTrackingY = 0f
        private var velocityTracker: VelocityTracker? = null
        private var startedTracking = false
        private var maybeTracking = false
        private var nestedScrollingY = 0f

        override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
            return allowNestedScroll && canDismissWithSwipe && (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0
        }

        override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
            nestedHelper.onNestedScrollAccepted(child, target, axes, type)
            nestedScrollingY = 0f
        }

        override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
            if (translationY > 0 && dy > 0) {
                val newTy = (translationY - dy).coerceAtLeast(0f)
                val consumedDy = translationY - newTy
                translationY = newTy
                consumed[1] = consumedDy.toInt()
            }
        }

        override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int, consumed: IntArray) {
            if (dyUnconsumed < 0 && canDismissWithSwipe) {
                translationY = (translationY - dyUnconsumed).coerceAtLeast(0f)
                consumed[1] = dyUnconsumed
            }
        }

        override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int) {
            onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, IntArray(2))
        }

        override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
            return false
        }

        override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean {
            return false
        }

        override fun onStopNestedScroll(target: View, type: Int) {
            nestedHelper.onStopNestedScroll(target, type)
            val ty = translationY
            if (ty > 0) {
                if (ty > height / 3f) {
                    dismiss()
                } else {
                    animate().translationY(0f).setDuration(200)
                        .setInterpolator(DecelerateInterpolator(1.5f))
                        .start()
                }
            }
        }

        override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean {
            return onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)
        }

        override fun onNestedScrollAccepted(child: View, target: View, axes: Int) {
            onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)
        }

        override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
            onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)
        }

        override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int) {
            onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH)
        }

        override fun onStopNestedScroll(target: View) {
            onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)
        }

        override fun getNestedScrollAxes(): Int = nestedHelper.nestedScrollAxes

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!canDismissWithSwipe) return super.onInterceptTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startedTrackingY = ev.y
                    maybeTracking = true
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(ev)
                    val dy = ev.y - startedTrackingY
                    if (dy > AndroidUtilities.touchSlop.toFloat() && !startedTracking) {
                        startedTracking = true
                        startedTrackingY = ev.y
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    maybeTracking = false
                    startedTracking = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!canDismissWithSwipe || !startedTracking) return super.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(ev)
                    translationY = (ev.y - startedTrackingY).coerceAtLeast(0f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    if (vy > 1200 || translationY > height / 3f) {
                        dismiss()
                    } else {
                        animate().translationY(0f).setDuration(200)
                            .setInterpolator(DecelerateInterpolator(1.5f)).start()
                    }
                    startedTracking = false
                    maybeTracking = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                }
            }
            return true
        }
    }

    class Builder(private val context: Context) {
        private val sheet = BottomSheet(context)

        fun setTitle(title: CharSequence?): Builder { sheet.setTitle(title); return this }
        fun setTitle(title: CharSequence?, big: Boolean): Builder { sheet.setTitle(title, big); return this }
        fun setCustomView(view: View?): Builder { sheet.setCustomView(view); return this }
        fun setApplyTopPadding(value: Boolean): Builder { sheet.setApplyTopPadding(value); return this }
        fun setApplyBottomPadding(value: Boolean): Builder { sheet.setApplyBottomPadding(value); return this }
        fun setCanDismissWithSwipe(value: Boolean): Builder { sheet.setCanDismissWithSwipe(value); return this }
        fun setAllowNestedScroll(value: Boolean): Builder { sheet.setAllowNestedScroll(value); return this }
        fun setShowWithoutAnimation(value: Boolean): Builder { sheet.setShowWithoutAnimation(value); return this }
        fun setOnHideListener(listener: DialogInterface.OnDismissListener?): Builder { sheet.setOnHideListener(listener); return this }
        fun setOverlayNavBarColor(color: Int): Builder { sheet.setOverlayNavBarColor(color); return this }
        fun setCalcMandatoryInsets(value: Boolean): Builder { sheet.setCalcMandatoryInsets(value); return this }
        fun setDimBehind(value: Boolean): Builder { sheet.setDimBehind(value); return this }
        fun setItems(items: Array<CharSequence>, listener: OnClickListener?): Builder { sheet.setItems(items, null, listener); return this }
        fun setItems(items: Array<CharSequence>, icons: IntArray?, listener: OnClickListener?): Builder { sheet.setItems(items, icons, listener); return this }
        fun setDelegate(delegate: BottomSheetDelegateInterface?): Builder { sheet.delegate = delegate; return this }
        fun create(): BottomSheet = sheet
        fun show(): BottomSheet { sheet.show(); return sheet }
    }
}
