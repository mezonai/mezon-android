package com.mezon.mobile.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout

open class SizeNotifierFrameLayout @JvmOverloads constructor(
    context: Context,
    private var parentLayout: INavigationLayout? = null
) : FrameLayout(context) {

    interface SizeNotifierFrameLayoutDelegate {
        fun onSizeChanged(keyboardHeight: Int, isWidthGreater: Boolean)
    }

    interface IViewWithInvalidateCallback {
        fun setInvalidateCallback(callback: Runnable?)
    }

    private val rect = Rect()
    var keyboardHeight = 0
        protected set
    private var delegate: SizeNotifierFrameLayoutDelegate? = null
    private val delegates = ArrayList<SizeNotifierFrameLayoutDelegate>()
    private var occupyStatusBar = true
    private var emojiHeight = 0
    private var emojiOffset = 0f
    private var animationInProgress = false
    private var skipBackgroundDrawing = false
    var useSmoothKeyboard = false
    var paused = true
        private set
    private var lastKeyboardHeight = -1

    private var bottomClip = 0
    private var backgroundTranslationY = 0
    var backgroundView: View? = null
        private set
    var backgroundDrawable: Drawable? = null
        private set
    var attached = false
        private set

    init {
        setWillNotDraw(false)
    }

    fun setDelegate(delegate: SizeNotifierFrameLayoutDelegate?) {
        this.delegate = delegate
    }

    fun addDelegate(delegate: SizeNotifierFrameLayoutDelegate) {
        delegates.add(delegate)
    }

    fun removeDelegate(delegate: SizeNotifierFrameLayoutDelegate) {
        delegates.remove(delegate)
    }

    fun setOccupyStatusBar(value: Boolean) {
        occupyStatusBar = value
    }

    fun getOccupyStatusBar(): Boolean = occupyStatusBar

    open fun onPause() {
        paused = true
    }

    open fun onResume() {
        paused = false
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        notifyHeightChanged()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        attached = false
    }

    fun measureKeyboardHeight(): Int {
        val rootView = rootView ?: return 0
        getWindowVisibleDisplayFrame(rect)
        if (rect.bottom == 0 && rect.top == 0) return 0
        val usableViewHeight = rootView.height -
                (if (rect.top != 0) AndroidUtilities.statusBarHeight else 0) -
                AndroidUtilities.getViewInset(rootView)
        keyboardHeight = (usableViewHeight - (rect.bottom - rect.top)).coerceAtLeast(0)
        return keyboardHeight
    }

    fun notifyHeightChanged() {
        if (delegate != null || delegates.isNotEmpty()) {
            keyboardHeight = measureKeyboardHeight()
            if (keyboardHeight == lastKeyboardHeight) return
            lastKeyboardHeight = keyboardHeight
            val isWidthGreater = AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y
            post {
                delegate?.onSizeChanged(keyboardHeight, isWidthGreater)
                for (i in delegates.indices) {
                    delegates[i].onSizeChanged(keyboardHeight, isWidthGreater)
                }
            }
        }
    }

    fun getHeightWithKeyboard(): Int = measuredHeight + keyboardHeight

    fun setEmojiKeyboardHeight(height: Int) {
        if (emojiHeight != height) {
            emojiHeight = height
            backgroundView?.invalidate()
        }
    }

    fun getEmojiKeyboardHeight(): Int = emojiHeight

    fun setEmojiOffset(animInProgress: Boolean, offset: Float) {
        if (emojiOffset != offset || animationInProgress != animInProgress) {
            emojiOffset = offset
            animationInProgress = animInProgress
            backgroundView?.invalidate()
        }
    }

    fun getEmojiOffset(): Float = emojiOffset

    fun setSkipBackgroundDrawing(value: Boolean) {
        if (skipBackgroundDrawing != value) {
            skipBackgroundDrawing = value
            backgroundView?.invalidate()
        }
    }

    fun setBottomClip(value: Int) {
        if (value != bottomClip) {
            bottomClip = value
            backgroundView?.invalidate()
        }
    }

    fun setBackgroundTranslation(translation: Int) {
        if (translation != backgroundTranslationY) {
            backgroundTranslationY = translation
            backgroundView?.invalidate()
        }
    }

    fun getBackgroundTranslationY(): Int = backgroundTranslationY

    fun getBackgroundSizeY(): Int {
        val offset = if (occupyStatusBar) AndroidUtilities.statusBarHeight else 0
        return measuredHeight - offset + emojiHeight
    }

    open fun setBackgroundImage(drawable: Drawable?, motion: Boolean) {
        backgroundDrawable = drawable
        backgroundView?.invalidate()
    }

    fun invalidateBackground() {
        backgroundView?.invalidate()
    }

    open fun getBottomPadding(): Int = 0

    fun setParentLayout(layout: INavigationLayout?) {
        parentLayout = layout
    }

    open fun getResourceProvider(): ThemeColors.ResourcesProvider? = null

    open fun updateColors() {}

    protected open fun createAdjustPanLayoutHelper(): Any? = null

    override fun dispatchDraw(canvas: Canvas) {
        if (backgroundView == null && backgroundDrawable != null && !skipBackgroundDrawing) {
            drawBackground(canvas)
        }
        super.dispatchDraw(canvas)
    }

    protected open fun drawBackground(canvas: Canvas) {
        val bg = backgroundDrawable ?: return
        val offset = if (occupyStatusBar) AndroidUtilities.statusBarHeight else 0
        bg.setBounds(0, offset + backgroundTranslationY, measuredWidth, measuredHeight)
        bg.draw(canvas)
    }
}
