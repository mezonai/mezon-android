package com.mezon.mobile.home.chat

import android.animation.ObjectAnimator
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.util.createImgproxyUrl
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

private const val LOADING_SHOW_DELAY_MS = 300L
private const val MIN_LOADING_VISIBLE_MS = 300L
private const val LOADING_FADE_MS = 150L
private const val SWIPE_DISABLE_SCALE = 1.05f
private const val SWIPE_ENABLE_SCALE = 1.01f
private const val DOUBLE_TAP_SCALE_THRESHOLD = 0.05f
private const val FLING_DISMISS_SCALE_THRESHOLD = 0.06f
private const val FLING_DISMISS_MIN_VELOCITY = 800f

class PhotoViewer(context: Context) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    companion object {
        private var activeInstance: java.lang.ref.WeakReference<PhotoViewer>? = null

        fun dismissActiveIfShowing() {
            activeInstance?.get()?.takeIf { it.isShowing }?.dismiss()
        }
    }

    private val backgroundDrawable = ColorDrawable(Color.BLACK)
    private val viewPager: ViewPager2
    private val topBar: FrameLayout
    private val bottomBar: LinearLayout
    private val counterView: android.widget.TextView
    private var toolbarVisible = true

    private var urls = emptyList<String>()
    private var currentIndex = 0
    private var preferDrawableLoaderForSingle = false
    private var oldestEdgeBaselinePosition = -1
    private val currentUrl get() = urls.getOrElse(currentIndex) { "" }

    var onReachedOldestEdge: (() -> Unit)? = null
    var onCurrentUrlChanged: ((String) -> Unit)? = null

    private var activeTouchCount = 0
    private var viewPagerSwipeAllowed = true

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window?.setBackgroundDrawable(backgroundDrawable)

        val root = FrameLayout(context)

        viewPager = ViewPager2(context).apply {
            offscreenPageLimit = 1
            setBackgroundColor(Color.BLACK)
        }
        root.addView(viewPager, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        topBar = FrameLayout(context)
        counterView = android.widget.TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val closeDiameter = LayoutHelper.dp(44)
        val closeWrap = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x80000000.toInt())
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.common_close)
            val tvRipple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tvRipple, true)) {
                foreground = ContextCompat.getDrawable(context, tvRipple.resourceId)
            }
            setOnClickListener { dismissWithAnimation() }
            val closeBtn = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.WHITE)
                val p = LayoutHelper.dp(10)
                setPadding(p, p, p, p)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(closeBtn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        val counterParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        topBar.addView(counterView, counterParams)
        val closeBtnParams = FrameLayout.LayoutParams(closeDiameter, closeDiameter, Gravity.END or Gravity.CENTER_VERTICAL)
        closeBtnParams.marginEnd = LayoutHelper.dp(8)
        topBar.addView(closeWrap, closeBtnParams)

        val topBarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(56)
        )
        topBarParams.gravity = Gravity.TOP
        topBarParams.topMargin = AndroidUtilities.statusBarHeight + LayoutHelper.dp(8)
        root.addView(topBar, topBarParams)

        val navBarInset = AndroidUtilities.navigationBarHeight
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            val pad = LayoutHelper.dp(12)
            setPadding(pad, pad, pad, pad + navBarInset)
        }
        bottomBar.addView(createToolbarButton(context, android.R.drawable.ic_menu_share, "Share") {
            shareUrl(currentUrl)
        })
        bottomBar.addView(createToolbarButton(context, android.R.drawable.ic_menu_save, "Save") {
            downloadUrl(currentUrl)
        })
        bottomBar.addView(createToolbarButton(context, android.R.drawable.ic_menu_agenda, "Copy") {
            copyImageFromCurrentUrl()
        })
        val bottomBarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        bottomBarParams.gravity = Gravity.BOTTOM
        root.addView(bottomBar, bottomBarParams)

        setContentView(root)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateCounter()
                onCurrentUrlChanged?.invoke(currentUrl)
                maybeNotifyOldestEdge(position)
                syncViewPagerSwipeWithCurrentPhoto()
            }
        })
    }

    private fun isZoomedScale(scale: Float) = scale >= SWIPE_ENABLE_SCALE

    private fun refreshPhotoSwipeState(photoView: PhotoView) {
        val scale = photoView.scale
        photoView.setAllowParentInterceptOnEdge(!isZoomedScale(scale))
        if (activeTouchCount > 0) {
            if (activeTouchCount > 1 || scale >= SWIPE_DISABLE_SCALE) {
                setViewPagerSwipeAllowed(false)
            }
            return
        }
        setViewPagerSwipeAllowed(urls.size > 1 && scale < SWIPE_ENABLE_SCALE)
    }

    private fun syncViewPagerSwipeWithCurrentPhoto() {
        val photoView = photoViewAt(currentIndex)
        if (photoView != null) {
            refreshPhotoSwipeState(photoView)
        } else {
            setViewPagerSwipeAllowed(urls.size > 1)
        }
    }

    private fun setViewPagerSwipeAllowed(allowed: Boolean) {
        if (viewPagerSwipeAllowed == allowed) return
        viewPagerSwipeAllowed = allowed
        viewPager.isUserInputEnabled = urls.size > 1 && allowed
    }

    private fun onPhotoTouchEvent(ev: MotionEvent, photoView: PhotoView) {
        activeTouchCount = when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
            MotionEvent.ACTION_POINTER_UP -> (ev.pointerCount - 1).coerceAtLeast(0)
            else -> ev.pointerCount
        }
        if (activeTouchCount == 0) {
            photoView.post { refreshPhotoSwipeState(photoView) }
        } else {
            refreshPhotoSwipeState(photoView)
        }
    }

    private fun photoViewAt(position: Int): PhotoView? {
        val rv = viewPager.getChildAt(0) as? RecyclerView ?: return null
        val holder = rv.findViewHolderForAdapterPosition(position) as? PhotoPagerAdapter.ViewHolder
        return holder?.photoView
    }

    private fun wirePhotoViewGestures(photoView: PhotoView) {
        installDoubleTapToggle(photoView)
        photoView.setOnScaleChangeListener { _, _, _ ->
            refreshPhotoSwipeState(photoView)
        }
    }

    private fun installDoubleTapToggle(photoView: PhotoView) {
        photoView.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val min = photoView.minimumScale
                val target = if (photoView.scale > min + DOUBLE_TAP_SCALE_THRESHOLD) {
                    min
                } else {
                    photoView.mediumScale
                }
                photoView.setScale(target, e.x, e.y, true)
                return true
            }

            override fun onDoubleTapEvent(e: MotionEvent): Boolean = false

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false
        })
    }

    private inner class PhotoZoomHost(context: Context) : FrameLayout(context) {
        var photoView: PhotoView? = null
        private var initialX = 0f
        private var initialY = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private fun isZoomed() = isZoomedScale(photoView?.scale ?: 1f)

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            if (disallowIntercept && !isZoomed()) return
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = ev.x
                    initialY = ev.y
                    if (isZoomed() || ev.pointerCount > 1) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN ->
                    parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_MOVE -> {
                    if (isZoomed() || ev.pointerCount > 1) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return false
                    }
                    val dx = ev.x - initialX
                    val dy = ev.y - initialY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        parent?.requestDisallowInterceptTouchEvent(abs(dy) > abs(dx))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            return false
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            photoView?.let { onPhotoTouchEvent(ev, it) }
            return super.dispatchTouchEvent(ev)
        }
    }

    private fun maybeNotifyOldestEdge(position: Int) {
        if (urls.size < 2) return
        if (oldestEdgeBaselinePosition < 0) {
            oldestEdgeBaselinePosition = position
            return
        }
        if (position <= oldestEdgeBaselinePosition) return
        if (position < urls.size - 2) return
        oldestEdgeBaselinePosition = position
        onReachedOldestEdge?.invoke()
    }

    fun show(
        url: String,
        gallery: List<String> = emptyList(),
        index: Int = 0,
        thumbBitmap: Bitmap? = null,
        preferDrawableLoader: Boolean = false
    ) {
        urls = if (gallery.isEmpty()) listOf(url) else gallery
        currentIndex = if (index in urls.indices) index else 0
        preferDrawableLoaderForSingle = preferDrawableLoader && urls.size == 1

        val single = urls.size == 1
        val thumbUrl = urls[0]
        val singleAnimated = urlNeedsAnimatedDrawable(thumbUrl) || preferDrawableLoaderForSingle
        val singleShowsThumb = single && !singleAnimated
        oldestEdgeBaselinePosition = -1
        viewPager.adapter = PhotoPagerAdapter(thumbBitmap.takeIf { singleShowsThumb })
        viewPager.setCurrentItem(currentIndex, false)
        activeTouchCount = 0
        setViewPagerSwipeAllowed(urls.size > 1)

        updateCounter()
        backgroundDrawable.alpha = 0
        activeInstance = java.lang.ref.WeakReference(this)
        super.show()
        ObjectAnimator.ofInt(backgroundDrawable, "alpha", 0, 255).setDuration(200).start()
    }

    override fun dismiss() {
        if (activeInstance?.get() === this) {
            activeInstance = null
        }
        super.dismiss()
    }

    private fun updateCounter() {
        if (urls.size > 1) {
            counterView.text = "${currentIndex + 1} / ${urls.size}"
            counterView.visibility = View.VISIBLE
        } else {
            counterView.visibility = View.GONE
        }
    }

    fun updateGallery(newUrls: List<String>, keepUrl: String) {
        if (newUrls.isEmpty() || newUrls == urls) return
        val appendOnly = newUrls.size > urls.size && newUrls.subList(0, urls.size) == urls
        val keepIndex = newUrls.indexOf(keepUrl).let {
            if (it >= 0) it else currentIndex.coerceIn(0, newUrls.size - 1)
        }
        val prevSize = urls.size
        urls = newUrls
        currentIndex = keepIndex
        if (appendOnly) {
            viewPager.adapter?.notifyItemRangeInserted(prevSize, newUrls.size - prevSize)
        } else {
            viewPager.adapter?.notifyDataSetChanged()
            viewPager.setCurrentItem(currentIndex, false)
        }
        updateCounter()
    }

    private fun toggleToolbar() {
        toolbarVisible = !toolbarVisible
        val targetAlpha = if (toolbarVisible) 1f else 0f
        topBar.animate().alpha(targetAlpha).setDuration(200).start()
        bottomBar.animate().alpha(targetAlpha).setDuration(200).start()
    }

    private fun dismissWithAnimation() {
        val anim = ObjectAnimator.ofInt(backgroundDrawable, "alpha", 255, 0)
        anim.duration = 150
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { dismiss() }
        })
        anim.start()
        viewPager.animate().alpha(0f).setDuration(150).start()
    }

    override fun onBackPressed() {
        dismissWithAnimation()
    }

    private fun urlNeedsAnimatedDrawable(url: String): Boolean {
        if (url.isEmpty()) return false
        val u = url.lowercase(Locale.US)
        if (u.contains("tenor.com")) return true
        if (u.contains("/stickers/")) return true
        if (u.contains(".gif")) return true
        return u.contains(".webp") && !u.endsWith("@webp")
    }

    private inner class PhotoPagerAdapter(
        private val initialThumb: Bitmap?
    ) : RecyclerView.Adapter<PhotoPagerAdapter.ViewHolder>() {

        init {
            setHasStableIds(true)
        }

        inner class ViewHolder(
            val container: PhotoZoomHost,
            val photoView: PhotoView,
            val progressBar: ProgressBar
        ) : RecyclerView.ViewHolder(container) {
            var pendingLoad: MezonImageLoader.Cancellable? = null
            var bindGeneration: Long = 0L
            var progressShown = false
            var progressShownAt = 0L
            var showProgressRunnable: Runnable? = null
            var hideProgressRunnable: Runnable? = null
        }

        private fun resetPhotoView(photoView: PhotoView) {
            (photoView.drawable as? AnimatedImageDrawable)?.stop()
            photoView.setImageDrawable(null)
            photoView.getAttacher().update()
            photoView.setScale(1f, false)
        }

        private fun applyLoadedImage(photoView: PhotoView, image: Any) {
            (photoView.drawable as? AnimatedImageDrawable)?.stop()
            when (image) {
                is Drawable -> {
                    photoView.setImageDrawable(image)
                    if (image is AnimatedImageDrawable) {
                        image.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        image.start()
                    }
                }
                is Bitmap -> photoView.setImageDrawable(BitmapDrawable(photoView.context.resources, image))
            }
            photoView.getAttacher().update()
            photoView.setScale(1f, false)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx = parent.context
            val photoView = PhotoView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                maximumScale = 5f
                mediumScale = 2.5f
                minimumScale = 1f
                setOnSingleFlingListener { _, _, velocityX, velocityY ->
                    val absX = abs(velocityX)
                    val absY = abs(velocityY)
                    val nearDefault = abs(scale - 1f) < FLING_DISMISS_SCALE_THRESHOLD
                    if (absY > absX && absY > FLING_DISMISS_MIN_VELOCITY && velocityY > 0 && nearDefault) {
                        dismissWithAnimation()
                        true
                    } else {
                        false
                    }
                }
                setOnPhotoTapListener { _, _, _ -> toggleToolbar() }
                setOnOutsidePhotoTapListener { toggleToolbar() }
            }
            val progress = ProgressBar(ctx).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                isClickable = false
                isFocusable = false
                val size = LayoutHelper.dp(48)
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                visibility = View.GONE
            }
            val container = PhotoZoomHost(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                this.photoView = photoView
                addView(photoView)
                addView(progress)
            }
            wirePhotoViewGestures(photoView)
            return ViewHolder(container, photoView, progress)
        }

        private fun cancelProgressAnim(holder: ViewHolder) {
            holder.showProgressRunnable?.let { holder.progressBar.removeCallbacks(it) }
            holder.showProgressRunnable = null
            holder.hideProgressRunnable?.let { holder.progressBar.removeCallbacks(it) }
            holder.hideProgressRunnable = null
            holder.progressBar.animate().cancel()
        }

        private fun scheduleShowProgress(holder: ViewHolder, bindGen: Long) {
            cancelProgressAnim(holder)
            holder.progressShown = false
            holder.progressBar.visibility = View.GONE
            holder.progressBar.alpha = 1f
            val runnable = Runnable {
                if (holder.bindGeneration != bindGen) return@Runnable
                holder.progressShown = true
                holder.progressShownAt = android.os.SystemClock.elapsedRealtime()
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.alpha = 0f
                holder.progressBar.animate().alpha(1f).setDuration(LOADING_FADE_MS).start()
            }
            holder.showProgressRunnable = runnable
            holder.progressBar.postDelayed(runnable, LOADING_SHOW_DELAY_MS)
        }

        private fun hideProgressBar(holder: ViewHolder, bindGen: Long) {
            holder.showProgressRunnable?.let { holder.progressBar.removeCallbacks(it) }
            holder.showProgressRunnable = null
            if (!holder.progressShown) {
                holder.progressBar.visibility = View.GONE
                return
            }
            val elapsedVisible = android.os.SystemClock.elapsedRealtime() - holder.progressShownAt
            val delay = (MIN_LOADING_VISIBLE_MS - elapsedVisible).coerceAtLeast(0L)
            holder.hideProgressRunnable?.let { holder.progressBar.removeCallbacks(it) }
            val runnable = Runnable {
                if (holder.bindGeneration != bindGen) return@Runnable
                holder.hideProgressRunnable = null
                holder.progressBar.animate().cancel()
                holder.progressBar.animate()
                    .alpha(0f)
                    .setDuration(LOADING_FADE_MS)
                    .withEndAction {
                        if (holder.bindGeneration == bindGen) {
                            holder.progressBar.visibility = View.GONE
                            holder.progressBar.alpha = 1f
                            holder.progressShown = false
                        }
                    }
                    .start()
            }
            holder.hideProgressRunnable = runnable
            if (delay > 0) holder.progressBar.postDelayed(runnable, delay) else runnable.run()
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            holder.pendingLoad?.cancel()
            holder.pendingLoad = null
            holder.bindGeneration++
            cancelProgressAnim(holder)
            holder.progressShown = false
            holder.progressBar.visibility = View.GONE
            resetPhotoView(holder.photoView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val url = urls[position]
            val photoView = holder.photoView

            holder.pendingLoad?.cancel()
            holder.pendingLoad = null
            holder.bindGeneration++
            val bindGen = holder.bindGeneration
            cancelProgressAnim(holder)
            resetPhotoView(photoView)

            if (position == currentIndex && initialThumb != null) {
                photoView.setImageBitmap(initialThumb)
                photoView.getAttacher().update()
                photoView.setScale(1f, false)
            } else {
                scheduleShowProgress(holder, bindGen)
            }

            startLoad(holder, url, bindGen, allowRetry = true)
        }

        private fun startLoad(holder: ViewHolder, url: String, bindGen: Long, allowRetry: Boolean) {
            val loader = MezonImageLoader.getInstance(context)
            val onErr: () -> Unit = {
                if (holder.bindGeneration == bindGen) {
                    if (allowRetry) {
                        startLoad(holder, url, bindGen, allowRetry = false)
                    } else {
                        hideProgressBar(holder, bindGen)
                    }
                }
            }
            val drawableLoader = urlNeedsAnimatedDrawable(url) ||
                (preferDrawableLoaderForSingle && urls.size == 1)
            if (drawableLoader) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    holder.pendingLoad = loader.loadDrawable(url, 0, 0,
                        onSuccess = { drawable ->
                            if (holder.bindGeneration != bindGen) return@loadDrawable
                            hideProgressBar(holder, bindGen)
                            applyLoadedImage(holder.photoView, drawable)
                        },
                        onError = { onErr() }
                    )
                } else {
                    holder.pendingLoad = loader.load(url, 0, 0,
                        onSuccess = { bmp ->
                            if (holder.bindGeneration != bindGen) return@load
                            hideProgressBar(holder, bindGen)
                            applyLoadedImage(holder.photoView, bmp)
                        },
                        onError = { onErr() }
                    )
                }
            } else {
                val screenW = context.resources.displayMetrics.widthPixels
                val screenH = context.resources.displayMetrics.heightPixels
                val proxyMaxEdge = maxOf(screenW, screenH).coerceAtMost(2560)
                val loadUrl = createImgproxyUrl(url, screenW, screenH, "fit", proxyMaxEdge)
                holder.pendingLoad = loader.load(loadUrl, screenW, screenH,
                    onSuccess = { bmp ->
                        if (holder.bindGeneration != bindGen) return@load
                        hideProgressBar(holder, bindGen)
                        applyLoadedImage(holder.photoView, bmp)
                    },
                    onError = { onErr() },
                    noCache = true
                )
            }
        }

        override fun getItemCount() = urls.size

        override fun getItemId(position: Int): Long =
            urls.getOrNull(position)?.hashCode()?.toLong() ?: RecyclerView.NO_ID
    }

    private fun createToolbarButton(ctx: Context, iconRes: Int, desc: String, onClick: () -> Unit): ImageView {
        return ImageView(ctx).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            contentDescription = desc
            val p = LayoutHelper.dp(16)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(56), LayoutHelper.dp(56)).apply {
                marginStart = LayoutHelper.dp(8)
                marginEnd = LayoutHelper.dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun copyImageFromCurrentUrl() {
        val url = currentUrl
        if (url.isEmpty()) return
        val app = context.applicationContext
        val ep = EntryPointAccessors.fromApplication(app, FragmentEntryPoint::class.java)
        val coordinator = ep.imageClipboardCoordinator()
        ep.applicationScope().launch {
            val ok = coordinator.copyRemoteUrlToClipboard(context, url, guessMimeHintForCopy(url))
            withContext(ep.mainDispatcher()) {
                if (ok) {
                    Toast.makeText(context, context.getString(R.string.message_toast_copy_image_done), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.message_toast_copy_image_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun guessMimeHintForCopy(url: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") || lower.contains("tenor.com", ignoreCase = true) -> "image/gif"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    private fun downloadUrl(url: String) {
        try {
            val filename = url.substringAfterLast('/').substringBefore('?').ifEmpty { "download" }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareUrl(url: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, url)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share"))
        } catch (_: Exception) {}
    }
}
