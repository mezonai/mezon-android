package com.mezon.mobile.core

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EdgeEffect
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ScrollView
import java.util.Hashtable
import java.util.concurrent.atomic.AtomicBoolean

object AndroidUtilities {

    @JvmStatic var density = 1f
        private set
    @JvmStatic val displaySize = Point()
    @JvmStatic val displayMetrics = DisplayMetrics()

    @JvmStatic var statusBarHeight = 0
    @JvmStatic var navigationBarHeight = 0
    @JvmStatic var screenRefreshRate = 60f
        private set
    @JvmStatic var screenMaxRefreshRate = 60f
        private set
    @JvmStatic var screenRefreshTime = 1000f / screenRefreshRate
        private set

    @JvmStatic var touchSlop = 0
        private set

    @JvmStatic var firstConfigurationWas = false
    @JvmStatic var isRTL = false
    @JvmStatic var leftBaseline = 72
    @JvmStatic var isInMultiwindow = false

    const val LIGHT_STATUS_BAR_OVERLAY = 0x0F000000
    const val DARK_STATUS_BAR_OVERLAY = 0x33000000

    @JvmField val accelerateInterpolator = android.view.animation.AccelerateInterpolator()
    @JvmField val decelerateInterpolator = android.view.animation.DecelerateInterpolator()
    @JvmField val overshootInterpolator = android.view.animation.OvershootInterpolator()
    @JvmField val linearInterpolator = android.view.animation.LinearInterpolator()
    @JvmField val accelerateDecelerateInterpolator = android.view.animation.AccelerateDecelerateInterpolator()

    @JvmField val rectTmp = RectF()
    @JvmField val rectTmp2 = Rect()
    @JvmField val pointTmp2 = IntArray(2)

    @JvmField val strokeTop = Paint(Paint.ANTI_ALIAS_FLAG)
    @JvmField val strokeBottom = Paint(Paint.ANTI_ALIAS_FLAG)

    private var isTablet: Boolean? = null
    private var isSmallScreen: Boolean? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun checkDisplaySize(context: Context, newConfig: Configuration? = null) {
        try {
            val oldDensity = density
            density = context.resources.displayMetrics.density

            val config = newConfig ?: context.resources.configuration

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = wm.defaultDisplay
            display.getMetrics(displayMetrics)
            display.getSize(displaySize)

            if (config.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
                val newW = Math.ceil(config.screenWidthDp * density.toDouble()).toInt()
                if (Math.abs(displaySize.x - newW) > 3) displaySize.x = newW
            }
            if (config.screenHeightDp != Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
                val newH = Math.ceil(config.screenHeightDp * density.toDouble()).toInt()
                if (Math.abs(displaySize.y - newH) > 3) displaySize.y = newH
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                screenRefreshRate = context.display?.refreshRate ?: 60f
                screenMaxRefreshRate = screenRefreshRate
            } else {
                @Suppress("DEPRECATION")
                screenRefreshRate = display.refreshRate
                screenMaxRefreshRate = screenRefreshRate
            }
            screenRefreshTime = 1000f / screenRefreshRate

            fillStatusBarHeight(context)
            touchSlop = ViewConfiguration.get(context).scaledTouchSlop

            if (oldDensity != density) {
                isSmallScreen = null
            }

            leftBaseline = if (isTablet()) 80 else 72

            val lang = config.locales.get(0)?.language ?: "en"
            isRTL = lang == "ar" || lang == "fa" || lang == "he" || lang == "iw" ||
                    lang.startsWith("ar_") || lang.startsWith("fa_") || lang.startsWith("he_")

            firstConfigurationWas = true
        } catch (_: Exception) {
        }
    }

    private fun fillStatusBarHeight(context: Context) {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarHeight = if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0

        val navId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        navigationBarHeight = if (navId > 0) context.resources.getDimensionPixelSize(navId) else 0
    }

    @JvmStatic
    fun isTablet(): Boolean {
        if (isTablet == null) {
            val minSide = Math.min(displaySize.x, displaySize.y) / density
            isTablet = minSide >= 600f
        }
        return isTablet!!
    }

    @JvmStatic
    fun isSmallTablet(): Boolean {
        val minSide = Math.min(displaySize.x, displaySize.y) / density
        return minSide <= 690f
    }

    @JvmStatic
    fun isSmallScreen(): Boolean {
        if (isSmallScreen == null) {
            isSmallScreen = (Math.max(displaySize.x, displaySize.y) - statusBarHeight - navigationBarHeight) / density <= 650
        }
        return isSmallScreen!!
    }

    @JvmStatic
    fun getMinTabletSide(): Int {
        if (!isSmallTablet()) {
            val smallSide = Math.min(displaySize.x, displaySize.y)
            var leftSide = smallSide * 35 / 100
            if (leftSide < dp(320f)) leftSide = dp(320f)
            return smallSide - leftSide
        }
        val smallSide = Math.min(displaySize.x, displaySize.y)
        val maxSide = Math.max(displaySize.x, displaySize.y)
        var leftSide = maxSide * 35 / 100
        if (leftSide < dp(320f)) leftSide = dp(320f)
        return Math.min(smallSide, maxSide - leftSide)
    }

    @JvmStatic
    fun dp(value: Float): Int = LayoutHelper.dp(value)

    @JvmStatic
    fun runOnUIThread(runnable: Runnable) {
        if (Thread.currentThread() === Looper.getMainLooper().thread) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    @JvmStatic
    fun runOnUIThread(runnable: Runnable, delay: Long) {
        mainHandler.postDelayed(runnable, delay)
    }

    @JvmStatic
    fun cancelRunOnUIThread(runnable: Runnable) {
        mainHandler.removeCallbacks(runnable)
    }

    @JvmStatic
    fun setLightStatusBar(window: android.view.Window, enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (enable) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }
    }

    @JvmStatic
    fun getShadowHeight(): Int = when {
        density >= 4f -> 3
        density >= 2f -> 2
        else -> 1
    }

    private val typefaceCache = Hashtable<String, Typeface>()
    private var mediumTypeface: Typeface? = null

    @JvmStatic
    fun bold(): Typeface {
        if (mediumTypeface == null) {
            mediumTypeface = getTypeface("sans-serif-medium")
        }
        return mediumTypeface!!
    }

    @JvmStatic
    fun getTypeface(key: String): Typeface {
        synchronized(typefaceCache) {
            var tf = typefaceCache[key]
            if (tf == null) {
                tf = when (key) {
                    "sans-serif-medium" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    "sans-serif-bold" -> Typeface.create("sans-serif", Typeface.BOLD)
                    "monospace" -> Typeface.MONOSPACE
                    else -> try {
                        Typeface.createFromAsset(
                            applicationContext?.assets, key
                        )
                    } catch (_: Exception) {
                        Typeface.DEFAULT
                    }
                }
                typefaceCache[key] = tf
            }
            return tf!!
        }
    }

    @JvmStatic
    fun setLightNavigationBar(window: android.view.Window, enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (enable) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
    }

    @JvmStatic
    fun setLightNavigationBar(view: View, enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val flags = view.systemUiVisibility
            view.systemUiVisibility = if (enable) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
    }

    @JvmStatic
    fun setLightNavigationBar(activity: Activity, enable: Boolean) {
        setLightNavigationBar(activity.window, enable)
    }

    @JvmStatic
    fun setLightNavigationBar(dialog: Dialog, enable: Boolean) {
        dialog.window?.let { setLightNavigationBar(it, enable) }
    }

    @JvmStatic
    fun showKeyboard(view: View?) {
        if (view == null) return
        try {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {}
    }

    @JvmStatic
    fun hideKeyboard(view: View?) {
        if (view == null) return
        try {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm?.isActive == true) {
                imm.hideSoftInputFromWindow(view.windowToken, 0)
            }
        } catch (_: Exception) {}
    }

    @JvmStatic
    fun lerp(a: Float, b: Float, f: Float): Float = a + f * (b - a)

    @JvmStatic
    fun lerp(a: Int, b: Int, f: Float): Int = (a + f * (b - a)).toInt()

    @JvmStatic
    fun clamp(value: Float, min: Float, max: Float): Float =
        if (value < min) min else if (value > max) max else value

    @JvmStatic
    fun clamp(value: Int, min: Int, max: Int): Int =
        if (value < min) min else if (value > max) max else value

    @JvmStatic
    fun getViewInset(view: View?): Int {
        if (view == null) return 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = view.rootWindowInsets ?: return 0
            return insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()).bottom
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val insets = view.rootWindowInsets ?: return 0
            @Suppress("DEPRECATION")
            return insets.stableInsetBottom
        }
        return 0
    }

    @JvmStatic var isLandscape = false
        private set

    var applicationContext: Context? = null
        private set

    @JvmStatic
    fun init(context: Context) {
        applicationContext = context.applicationContext
        checkDisplaySize(context)
    }

    @JvmStatic
    fun dp(value: Int): Int = LayoutHelper.dp(value.toFloat())

    @JvmStatic
    fun dpf2(value: Float): Float = LayoutHelper.dpf(value)

    @JvmStatic
    fun isPortrait(): Boolean = displaySize.y > displaySize.x

    @JvmStatic
    fun getPixelsInCM(cm: Float, isX: Boolean): Float {
        return cm / 2.54f * (if (isX) displayMetrics.xdpi else displayMetrics.ydpi)
    }

    @JvmStatic
    fun computePerceivedBrightness(color: Int): Float {
        return (Color.red(color) * 0.2126f + Color.green(color) * 0.7152f + Color.blue(color) * 0.0722f) / 255f
    }

    @JvmStatic
    fun getBrightness(color: Int): Float = computePerceivedBrightness(color)

    @JvmStatic
    fun isColorLight(color: Int): Boolean = computePerceivedBrightness(color) > 0.5f

    @JvmStatic
    fun findActivity(context: Context?): Activity? {
        if (context is Activity) return context
        if (context is ContextWrapper) return findActivity(context.baseContext)
        return null
    }

    @JvmStatic
    fun isActivityRunning(activity: Activity?): Boolean {
        if (activity == null) return false
        return !activity.isDestroyed && !activity.isFinishing
    }

    @JvmStatic
    fun isSafeToShow(context: Context?): Boolean {
        val activity = findActivity(context) ?: return true
        return isActivityRunning(activity)
    }

    @JvmStatic
    fun removeFromParent(child: View?) {
        if (child != null && child.parent != null) {
            (child.parent as? ViewGroup)?.removeView(child)
        }
    }

    @JvmStatic
    fun doOnLayout(view: View?, runnable: Runnable?) {
        if (runnable == null) return
        if (view == null) { runnable.run(); return }
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int) {
                view.removeOnLayoutChangeListener(this)
                runnable.run()
            }
        })
    }

    @JvmStatic
    fun doOnPreDraw(view: View, action: Runnable) {
        val observer = view.viewTreeObserver
        val completed = booleanArrayOf(false)
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (!completed[0]) { completed[0] = true; action.run() }
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
    }

    @JvmStatic
    fun setNavigationBarColor(dialog: Dialog?, color: Int) {
        dialog?.window?.navigationBarColor = color
    }

    @JvmStatic
    fun setNavigationBarColor(activity: Activity?, color: Int) {
        activity?.window?.navigationBarColor = color
    }

    @JvmStatic
    fun vibrateCursor(view: View?) {
        try {
            if (view == null || view.context == null) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val vibrator = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasAmplitudeControl()) return
            view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        } catch (_: Exception) {}
    }

    @JvmStatic
    fun vibrate(view: View?) {
        try {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Exception) {}
    }

    @JvmStatic
    fun shakeView(view: View?) {
        view ?: return
        val animator = android.animation.ObjectAnimator.ofFloat(view, "translationX", 0f, -6f, 6f, -5f, 5f, -3f, 3f, 0f)
        animator.duration = 400
        animator.start()
    }

    @JvmStatic
    fun updateImageViewImageAnimated(imageView: ImageView, newIcon: android.graphics.drawable.Drawable?) {
        if (imageView.drawable === newIcon) return
        val animator = ValueAnimator.ofFloat(0f, 1f).setDuration(150)
        val changed = AtomicBoolean()
        animator.addUpdateListener { animation ->
            val v = animation.animatedValue as Float
            val scale = 0.5f + Math.abs(v - 0.5f)
            imageView.scaleX = scale
            imageView.scaleY = scale
            if (v >= 0.5f && changed.compareAndSet(false, true)) {
                imageView.setImageDrawable(newIcon)
            }
        }
        animator.start()
    }

    @JvmStatic
    fun updateImageViewImageAnimated(imageView: ImageView, newIconRes: Int) {
        updateImageViewImageAnimated(imageView, androidx.core.content.ContextCompat.getDrawable(imageView.context, newIconRes))
    }

    @JvmStatic
    fun setScrollViewEdgeEffectColor(scrollView: ScrollView, color: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            scrollView.topEdgeEffectColor = color
            scrollView.bottomEdgeEffectColor = color
        } else {
            try {
                val field = ScrollView::class.java.getDeclaredField("mEdgeGlowTop")
                field.isAccessible = true
                (field.get(scrollView) as? EdgeEffect)?.color = color
                val field2 = ScrollView::class.java.getDeclaredField("mEdgeGlowBottom")
                field2.isAccessible = true
                (field2.get(scrollView) as? EdgeEffect)?.color = color
            } catch (_: Exception) {}
        }
    }

    @JvmStatic
    fun setScrollViewEdgeEffectColor(scrollView: HorizontalScrollView, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scrollView.setEdgeEffectColor(color)
        } else {
            try {
                val field = HorizontalScrollView::class.java.getDeclaredField("mEdgeGlowLeft")
                field.isAccessible = true
                (field.get(scrollView) as? EdgeEffect)?.color = color
                val field2 = HorizontalScrollView::class.java.getDeclaredField("mEdgeGlowRight")
                field2.isAccessible = true
                (field2.get(scrollView) as? EdgeEffect)?.color = color
            } catch (_: Exception) {}
        }
    }

    @JvmStatic
    fun pack(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

    @JvmStatic
    fun unpackA(packed: Long): Int = (packed shr 32).toInt()

    @JvmStatic
    fun unpackB(packed: Long): Int = packed.toInt()

    @JvmStatic
    fun makePhoneNumberLink(text: CharSequence): CharSequence = text

    @JvmStatic
    fun generateSearchName(name: String?, name2: String?, query: String?): CharSequence {
        if (query.isNullOrEmpty()) return name ?: name2 ?: ""
        return name ?: name2 ?: ""
    }

    @JvmStatic
    fun setRectToRect(src: RectF, dst: RectF, matrix: android.graphics.Matrix, fit: Int = 0) {
        matrix.setRectToRect(src, dst, android.graphics.Matrix.ScaleToFit.values()[fit])
    }

    @JvmStatic
    fun setEnabled(view: View?, enabled: Boolean) {
        if (view == null) return
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setEnabled(view.getChildAt(i), enabled)
            }
        }
    }

    @JvmStatic
    fun hideKeyboard(activity: Activity?) {
        if (activity == null) return
        val view = activity.currentFocus ?: activity.window?.decorView
        hideKeyboard(view)
    }

    @JvmStatic
    fun isKeyboardShowed(view: View?): Boolean {
        if (view == null) return false
        try {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            return imm?.isActive(view) ?: false
        } catch (_: Exception) {}
        return false
    }

    @JvmStatic
    fun lockOrientation(activity: Activity?) {
        if (activity == null) return
        try {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } catch (_: Exception) {}
    }

    @JvmStatic
    fun unlockOrientation(activity: Activity?) {
        if (activity == null) return
        try {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (_: Exception) {}
    }

    @JvmStatic
    fun setPreferredMaxRefreshRate(window: android.view.Window?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && window != null) {
            try {
                val params = window.attributes
                params.preferredDisplayModeId = 0
                window.attributes = params
            } catch (_: Exception) {}
        }
    }

    @JvmStatic
    fun getThemeColor(key: Int, provider: ThemeColors.ResourcesProvider? = null): Int {
        return provider?.getColor(key) ?: ThemeColors.instance.getColor(key)
    }
}
