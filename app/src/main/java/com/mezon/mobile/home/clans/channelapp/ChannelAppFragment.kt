package com.mezon.mobile.home.clans.channelapp

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.animation.doOnEnd
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.ui.theme.ThemeMode
import com.mezon.mobile.home.chat.ShimmerEffect
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.cells.MezonIcon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class ChannelAppFragment : BaseFragment() {

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager

    private var channelId: Long = 0L
    private var clanId: Long = 0L
    private var appId: Long = 0L
    private var appUrl: String = ""
    private var appName: String = ""

    private var webView: WebView? = null
    private var skeletonView: SkeletonView? = null
    private var webContentContainer: FrameLayout? = null
    private var headerBar: View? = null
    private var headerContentRow: View? = null
    private var floatingExpandButton: View? = null
    private var headerAnimator: ValueAnimator? = null
    private var isHeaderVisible: Boolean = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoCollapseRunnable: Runnable? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private val fullUrlDeferred = CompletableDeferred<String>()
    private val statusBarInset: Int
        get() = AndroidUtilities.statusBarHeight.coerceAtLeast(0)
    private val headerPrimaryColor: Int
        get() = themeColors.serverRailBg

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        appId = arguments?.getLong(ARG_APP_ID) ?: 0L
        appUrl = arguments?.getString(ARG_APP_URL).orEmpty()
        appName = arguments?.getString(ARG_APP_NAME).orEmpty()
        prefetchFullUrl()
        return true
    }

    private fun prefetchFullUrl() {
        if (appUrl.isBlank() || appId == 0L) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            try {
                val resp = sessionManager.withAutoRefresh { session ->
                    withContext(Dispatchers.IO) {
                        api.generateHashChannelApps(session.apiUrl, session.token, appId)
                    }
                }
                val encoded = URLEncoder.encode(resp.webAppData, "UTF-8")
                val sep = if (appUrl.contains("?")) "&" else "?"
                fullUrlDeferred.complete("$appUrl${sep}data=$encoded")
            } catch (e: Exception) {
                fullUrlDeferred.completeExceptionally(e)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun createView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        headerBar = buildHeader(context)
        container.addView(
            headerBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val content = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
        }
        webContentContainer = content

        val skeleton = SkeletonView(context).apply {
            isDark = themeColors.resolvedMode == ThemeMode.DARK
        }
        skeletonView = skeleton
        content.addView(
            skeleton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        )
        root.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        floatingExpandButton = buildFloatingExpandButton(context).also { btn ->
            btn.visibility = View.GONE
            root.addView(
                btn,
                FrameLayout.LayoutParams(
                    LayoutHelper.dp(44f),
                    LayoutHelper.dp(44f),
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = statusBarInset + LayoutHelper.dp(8)
                    marginEnd = LayoutHelper.dp(8)
                }
            )
            btn.bringToFront()
        }

        showHeader(resetTimer = true, animated = false)
        root.post { inflateWebViewAndLoad(context) }
        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun inflateWebViewAndLoad(context: Context) {
        val content = webContentContainer ?: return
        if (webView != null) return
        val wv = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                @Suppress("DEPRECATION")
                setRenderPriority(WebSettings.RenderPriority.HIGH)
            }
            setBackgroundColor(themeColors.background)
            alpha = 0f
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    hideSkeleton()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 100) hideSkeleton()
                }
            }
        }
        webView = wv
        content.addView(
            wv,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        loadApp()
    }

    private fun buildHeader(context: Context): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(headerPrimaryColor)
        }
        val topInset = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                statusBarInset
            )
            setBackgroundColor(headerPrimaryColor)
        }
        header.addView(topInset)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(8), 0, LayoutHelper.dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(HEADER_CONTENT_HEIGHT_DP)
            )
        }
        headerContentRow = row
        val closeBtn = android.widget.ImageView(context).apply {
            setImageDrawable(MezonIcon.closeIcon.getDrawable(context, themeColors.colorText))
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40))
            setPadding(
                LayoutHelper.dp(8),
                LayoutHelper.dp(8),
                LayoutHelper.dp(2),
                LayoutHelper.dp(8)
            )
            isClickable = true
            isFocusable = true
            applyCircleRipple(this)
            setOnClickListener { finishFragment() }
        }
        row.addView(closeBtn)

        val title = TextView(context).apply {
            text = appName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 16f
            setTextColor(themeColors.colorText)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = LayoutHelper.dp(8)
            layoutParams = lp
        }
        row.addView(title)

        val collapseBtn = ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronDownSmallIcon.getDrawable(context, themeColors.colorText))
            val sz = LayoutHelper.dp(32)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            val pad = LayoutHelper.dp(4)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            applyCircleRipple(this)
            setOnClickListener { collapseHeader() }
        }
        row.addView(collapseBtn)
        header.addView(row)
        return header
    }

    private fun buildFloatingExpandButton(context: Context): View {
        return ImageView(context).apply {
            val drawable = MezonIcon.chevronDownSmallIcon.getDrawable(context)
            drawable.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            rotation = 180f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.textDisabled)
            }
            val pad = LayoutHelper.dp(6)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            elevation = LayoutHelper.dp(4f).toFloat()
            applyCircleRipple(this)
            setOnClickListener { showHeader(resetTimer = true, animated = true) }
        }
    }

    private fun applyCircleRipple(view: View) {
        val rippleColor = ColorStateList.valueOf(0x33FFFFFF)
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        view.foreground = RippleDrawable(rippleColor, null, mask)
    }

    private fun collapseHeader() {
        if (!isHeaderVisible) return
        isHeaderVisible = false
        cancelAutoCollapse()
        val row = headerContentRow ?: return
        row.clearAnimation()
        val lp = row.layoutParams as? LinearLayout.LayoutParams ?: return
        val startHeight = lp.height.coerceAtLeast(LayoutHelper.dp(HEADER_CONTENT_HEIGHT_DP))
        animateHeaderRow(
            row = row,
            fromHeight = startHeight,
            toHeight = 0,
            onEnd = {
                row.visibility = View.GONE
                floatingExpandButton?.visibility = View.VISIBLE
                floatingExpandButton?.alpha = 0f
                floatingExpandButton?.animate()?.alpha(1f)?.setDuration(180L)?.start()
            }
        )
    }

    private fun showHeader(resetTimer: Boolean, animated: Boolean) {
        val row = headerContentRow ?: return
        isHeaderVisible = true
        headerAnimator?.cancel()
        row.visibility = View.VISIBLE
        val targetHeight = LayoutHelper.dp(HEADER_CONTENT_HEIGHT_DP)
        val lp = row.layoutParams as? LinearLayout.LayoutParams ?: return
        if (animated) {
            val startHeight = lp.height.coerceAtLeast(0)
            animateHeaderRow(row, startHeight, targetHeight, onEnd = null)
        } else {
            lp.height = targetHeight
            row.layoutParams = lp
            row.alpha = 1f
        }
        floatingExpandButton?.animate()?.cancel()
        floatingExpandButton?.visibility = View.GONE
        if (resetTimer) scheduleAutoCollapse()
    }

    private fun scheduleAutoCollapse() {
        cancelAutoCollapse()
        autoCollapseRunnable = Runnable { collapseHeader() }.also {
            mainHandler.postDelayed(it, AUTO_COLLAPSE_MS)
        }
    }

    private fun cancelAutoCollapse() {
        autoCollapseRunnable?.let(mainHandler::removeCallbacks)
        autoCollapseRunnable = null
    }

    private fun animateHeaderRow(
        row: View,
        fromHeight: Int,
        toHeight: Int,
        onEnd: (() -> Unit)?
    ) {
        headerAnimator?.cancel()
        val animator = ValueAnimator.ofInt(fromHeight, toHeight).apply {
            duration = HEADER_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val h = va.animatedValue as Int
                val lp = row.layoutParams as? LinearLayout.LayoutParams ?: return@addUpdateListener
                lp.height = h
                row.layoutParams = lp
                row.alpha = if (HEADER_CONTENT_HEIGHT_DP == 0) 1f else {
                    (h / LayoutHelper.dp(HEADER_CONTENT_HEIGHT_DP).toFloat()).coerceIn(0f, 1f)
                }
            }
            doOnEnd {
                onEnd?.invoke()
            }
            start()
        }
        headerAnimator = animator
    }

    private fun loadApp() {
        if (appUrl.isBlank() || appId == 0L) {
            finishFragment()
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val fullUrl = fullUrlDeferred.await()
                webView?.loadUrl(fullUrl)
            } catch (e: Exception) {
                val ctx = getParentActivity() ?: return@launch
                Toast.makeText(ctx, e.message ?: "Error", Toast.LENGTH_SHORT).show()
                finishFragment()
            }
        }
    }

    override fun onFragmentDestroy() {
        loadJob?.cancel()
        prefetchJob?.cancel()
        fullUrlDeferred.cancel()
        headerAnimator?.cancel()
        cancelAutoCollapse()
        scope.cancel()
        webView?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            loadUrl("about:blank")
            clearHistory()
            clearCache(true)
            clearFormData()
            clearSslPreferences()
            onPause()
            pauseTimers()
            removeAllViews()
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        skeletonView?.stop()
        skeletonView = null
        webContentContainer = null
        headerBar = null
        headerContentRow = null
        floatingExpandButton = null
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        super.onFragmentDestroy()
    }

    private var skeletonHidden: Boolean = false

    private fun hideSkeleton() {
        if (skeletonHidden) return
        skeletonHidden = true
        val sk = skeletonView
        sk?.animate()?.cancel()
        sk?.animate()?.alpha(0f)?.setDuration(SKELETON_FADE_MS)?.withEndAction {
            sk.visibility = View.GONE
            sk.stop()
        }?.start()
        webView?.animate()?.cancel()
        webView?.animate()?.alpha(1f)?.setDuration(SKELETON_FADE_MS)?.start()
    }

    private class SkeletonView(context: Context) : View(context) {
        var isDark: Boolean = true
        private val shimmer = ShimmerEffect()
        private var running: Boolean = true

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            running = true
            postInvalidateOnAnimation()
        }

        override fun onDetachedFromWindow() {
            running = false
            super.onDetachedFromWindow()
        }

        fun stop() {
            running = false
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            if (w <= 0f) return
            val padH = LayoutHelper.dp(16).toFloat()
            val rowH = LayoutHelper.dp(14).toFloat()
            val gap = LayoutHelper.dp(12).toFloat()
            val radius = LayoutHelper.dp(8).toFloat()
            var top = LayoutHelper.dp(24).toFloat()

            shimmer.draw(canvas, padH, top, w - padH, top + LayoutHelper.dp(28), radius, isDark)
            top += LayoutHelper.dp(28) + LayoutHelper.dp(20)

            val blockGap = LayoutHelper.dp(16).toFloat()
            val blockH = LayoutHelper.dp(72).toFloat()
            repeat(3) {
                shimmer.draw(canvas, padH, top, w - padH, top + blockH, radius, isDark)
                top += blockH + blockGap
            }

            top += LayoutHelper.dp(4)
            repeat(4) {
                val right = w - padH - (if (it == 3) LayoutHelper.dp(80) else 0).toFloat()
                shimmer.draw(canvas, padH, top, right, top + rowH, radius / 2f, isDark)
                top += rowH + gap
            }

            if (running && visibility == VISIBLE) {
                postInvalidateOnAnimation()
            }
        }
    }

    companion object {
        private const val AUTO_COLLAPSE_MS = 5000L
        private const val SKELETON_FADE_MS = 180L
        private const val HEADER_ANIM_DURATION_MS = 280L
        private const val HEADER_CONTENT_HEIGHT_DP = 50
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_APP_ID = "appId"
        private const val ARG_APP_URL = "appUrl"
        private const val ARG_APP_NAME = "appName"

        fun newInstance(
            channelId: Long,
            clanId: Long,
            appId: Long,
            appUrl: String,
            appName: String
        ): ChannelAppFragment {
            return ChannelAppFragment().apply {
                arguments = android.os.Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_APP_ID, appId)
                    putString(ARG_APP_URL, appUrl)
                    putString(ARG_APP_NAME, appName)
                }
            }
        }
    }
}
