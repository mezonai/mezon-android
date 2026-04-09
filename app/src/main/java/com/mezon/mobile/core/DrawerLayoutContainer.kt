package com.mezon.mobile.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class DrawerLayoutContainer(context: Context) : FrameLayout(context) {

    var parentActionBarLayout: ActionBarLayout? = null

    private val backgroundPaint = Paint()
    private val internalNavbarPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var behindKeyboardColor = 0xFF000000.toInt()

    private var hasCutout = false
    private var inLayout = false
    private var firstLayout = true

    var allowDrawContent = true
        private set

    private var keyboardVisibility = false
    private var imeHeight = 0

    fun resetImeHeight() {
        if (imeHeight != 0) {
            imeHeight = 0
            keyboardVisibility = false
            requestLayout()
        }
    }

    private var lastWindowInsetsCompat: WindowInsetsCompat? = null

    init {
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        isFocusableInTouchMode = true
        setWillNotDraw(false)

        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val newKeyboardVisibility = insets.isVisible(WindowInsetsCompat.Type.ime())
                val newImeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                if (keyboardVisibility != newKeyboardVisibility || imeHeight != newImeHeight) {
                    keyboardVisibility = newKeyboardVisibility
                    imeHeight = newImeHeight
                    requestLayout()
                }
            }

            val newTopInset = insets.systemWindowInsetTop
            if (AndroidUtilities.statusBarHeight != newTopInset) {
                (v as DrawerLayoutContainer).requestLayout()
            }
            if ((newTopInset != 0 || firstLayout) && AndroidUtilities.statusBarHeight != newTopInset) {
                AndroidUtilities.statusBarHeight = newTopInset
            }
            firstLayout = false
            (v as DrawerLayoutContainer).setWillNotDraw(insets.systemWindowInsetTop <= 0 && background == null)

            if (Build.VERSION.SDK_INT >= 28) {
                val cutout: DisplayCutoutCompat? = insets.displayCutout
                hasCutout = cutout != null && cutout.boundingRects.isNotEmpty()
            }
            invalidate()

            onApplyWindowInsetsInternal(insets)
        }

        systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    fun setAllowDrawContent(value: Boolean) {
        if (allowDrawContent != value) {
            allowDrawContent = value
            invalidate()
        }
    }

    fun setBehindKeyboardColor(color: Int) {
        behindKeyboardColor = color
        invalidate()
    }

    fun getInternalNavbarPaint(): Paint = internalNavbarPaint

    fun setInternalNavigationBarColor(color: Int) {
        if (internalNavbarPaint.color != color) {
            internalNavbarPaint.color = color
            invalidate()
            for (i in 0 until childCount) {
                getChildAt(i).invalidate()
            }
        }
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return parentActionBarLayout?.checkTransitionAnimation() == true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        setMeasuredDimension(widthSize, heightSize)

        val newSize = heightSize - AndroidUtilities.statusBarHeight - AndroidUtilities.navigationBarHeight
        if (newSize in 1..4095) {
            AndroidUtilities.displaySize.y = newSize
        }

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            val lp = child.layoutParams as LayoutParams
            val contentWidthSpec = MeasureSpec.makeMeasureSpec(
                widthSize - lp.leftMargin - lp.rightMargin, MeasureSpec.EXACTLY
            )
            val contentHeightSpec = if (lp.height > 0) {
                MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
            } else {
                MeasureSpec.makeMeasureSpec(
                    heightSize - lp.topMargin - lp.bottomMargin, MeasureSpec.EXACTLY
                )
            }
            child.measure(contentWidthSpec, contentHeightSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        inLayout = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            val lp = child.layoutParams as LayoutParams
            try {
                child.layout(
                    lp.leftMargin,
                    lp.topMargin + paddingTop,
                    lp.leftMargin + child.measuredWidth,
                    lp.topMargin + child.measuredHeight + paddingTop
                )
            } catch (_: Exception) {}
        }
        inLayout = false
    }

    override fun requestLayout() {
        if (!inLayout) {
            super.requestLayout()
        }
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        super.addView(child, index, params)
        lastWindowInsetsCompat?.let { dispatchInsetsToChild(child, it) }
    }

    override fun onDraw(canvas: Canvas) {
        val insets = lastWindowInsetsCompat ?: return

        val combined = insets.getInsets(
            WindowInsetsCompat.Type.ime() or
                    WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
        )

        if (combined.bottom > 0) {
            backgroundPaint.color = behindKeyboardColor
            canvas.drawRect(
                0f,
                (measuredHeight - combined.bottom).toFloat(),
                measuredWidth.toFloat(),
                measuredHeight.toFloat(),
                backgroundPaint
            )
        }

        if (hasCutout) {
            backgroundPaint.color = 0xFF000000.toInt()
            val cutoutLeft = combined.left
            if (cutoutLeft != 0) {
                canvas.drawRect(0f, 0f, cutoutLeft.toFloat(), measuredHeight.toFloat(), backgroundPaint)
            }
            val cutoutRight = combined.right
            if (cutoutRight != 0) {
                canvas.drawRect(
                    (measuredWidth - cutoutRight).toFloat(), 0f,
                    measuredWidth.toFloat(), measuredHeight.toFloat(), backgroundPaint
                )
            }
        }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (!allowDrawContent) return false
        return super.drawChild(canvas, child, drawingTime)
    }

    private fun onApplyWindowInsetsInternal(insets: WindowInsetsCompat): WindowInsetsCompat {
        lastWindowInsetsCompat = insets

        for (i in 0 until childCount) {
            dispatchInsetsToChild(getChildAt(i), insets)
        }

        invalidate()
        return WindowInsetsCompat.CONSUMED
    }

    private fun dispatchInsetsToChild(child: View, insets: WindowInsetsCompat) {
        val canApplyInsets = child is ActionBarLayout || child.tag == null
        if (!canApplyInsets) return

        val lp = child.layoutParams as? MarginLayoutParams ?: return

        val systemInsetsWithIme = insets.getInsets(
            WindowInsetsCompat.Type.ime() or
                    WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
        )

        val newBottom = systemInsetsWithIme.bottom
        val changed = lp.topMargin != 0
                || lp.bottomMargin != newBottom
                || lp.leftMargin != systemInsetsWithIme.left
                || lp.rightMargin != systemInsetsWithIme.right

        if (changed) {
            lp.leftMargin = systemInsetsWithIme.left
            lp.topMargin = 0
            lp.rightMargin = systemInsetsWithIme.right
            lp.bottomMargin = newBottom
            child.requestLayout()
        }

        val consumed = insets.inset(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
        ViewCompat.dispatchApplyWindowInsets(child, consumed)
    }
}
