package com.mezon.mobile.home.chat

import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.InAppOverlayHost
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.BackupImageView
import com.mezon.mobile.ui.cells.ToastOverlay
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
private val ANONYMOUS_USER_ID = com.mezon.mobile.BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: 0L

class PhotoViewer(context: Context) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    companion object {
        private var activeInstance: java.lang.ref.WeakReference<PhotoViewer>? = null

        fun dismissActiveIfShowing() {
            activeInstance?.get()?.takeIf { it.isShowing }?.dismiss()
        }
    }

    data class GalleryItem(
        val url: String,
        val senderName: String,
        val senderAvatarUrl: String?,
        val timestamp: Long,
        val isVideo: Boolean = false,
        val uploaderId: Long = 0L
    )

    private val backgroundDrawable = ColorDrawable(Color.BLACK)
    private val viewPager: ViewPager2
    private val topBar: FrameLayout
    private val bottomBar: LinearLayout
    private val counterView: android.widget.TextView
    private var thumbnailRecyclerView: RecyclerView? = null
    private var toolbarVisible = true

    private var items = emptyList<GalleryItem>()
    private var currentIndex = 0
    private var preferDrawableLoaderForSingle = false
    private var oldestEdgeBaselinePosition = -1
    private val currentUrl get() = items.getOrNull(currentIndex)?.url ?: ""
    private val currentItem get() = items.getOrNull(currentIndex)

    var onReachedOldestEdge: (() -> Unit)? = null
    var onCurrentUrlChanged: ((String) -> Unit)? = null

    private var activeTouchCount = 0
    private var viewPagerSwipeAllowed = true

    private var avatarView: BackupImageView? = null
    private var nameLabel: android.widget.TextView? = null
    private var dateLabel: android.widget.TextView? = null

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

        topBar = FrameLayout(context).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
        }
        
        val backBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(Color.WHITE)
            val p = LayoutHelper.dp(16)
            setPadding(p, p, p, p)
            setOnClickListener { dismissWithAnimation() }
        }
        topBar.addView(backBtn, FrameLayout.LayoutParams(LayoutHelper.dp(56), LayoutHelper.dp(56), Gravity.START or Gravity.CENTER_VERTICAL))

        avatarView = BackupImageView(context).apply {
            setRoundRadius(LayoutHelper.dp(20))
        }
        val avatarParams = FrameLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40), Gravity.START or Gravity.CENTER_VERTICAL)
        avatarParams.marginStart = LayoutHelper.dp(56)
        topBar.addView(avatarView, avatarParams)

        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameLabel = android.widget.TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isSingleLine = true
        }
        dateLabel = android.widget.TextView(context).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
            isSingleLine = true
        }
        textLayout.addView(nameLabel, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        textLayout.addView(dateLabel, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        
        val textParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL)
        textParams.marginStart = LayoutHelper.dp(108)
        textParams.marginEnd = LayoutHelper.dp(56)
        topBar.addView(textLayout, textParams)

        val moreBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_more_vertical_24)
            setColorFilter(Color.WHITE)
            val p = LayoutHelper.dp(16)
            setPadding(p, p, p, p)
            setOnClickListener { showMoreOptions(it) }
        }
        topBar.addView(moreBtn, FrameLayout.LayoutParams(LayoutHelper.dp(56), LayoutHelper.dp(56), Gravity.END or Gravity.CENTER_VERTICAL))

        val topBarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            LayoutHelper.dp(56)
        )
        topBarParams.gravity = Gravity.TOP
        topBarParams.topMargin = AndroidUtilities.statusBarHeight
        root.addView(topBar, topBarParams)

        val navBarInset = AndroidUtilities.navigationBarHeight
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(0, LayoutHelper.dp(12), 0, LayoutHelper.dp(12) + navBarInset)
            isClickable = true
        }
        
        counterView = android.widget.TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val counterParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        counterParams.bottomMargin = LayoutHelper.dp(8)
        bottomBar.addView(counterView, counterParams)

        thumbnailRecyclerView = RecyclerView(context).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            clipToPadding = false
            setPadding(LayoutHelper.dp(12), 0, LayoutHelper.dp(12), 0)
        }
        bottomBar.addView(thumbnailRecyclerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(64)))

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
                updateHeader()
                updateCounter()
                updateThumbnailSelection()
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
        setViewPagerSwipeAllowed(items.size > 1 && scale < SWIPE_ENABLE_SCALE)
    }

    private fun syncViewPagerSwipeWithCurrentPhoto() {
        val photoView = photoViewAt(currentIndex)
        if (photoView != null) {
            refreshPhotoSwipeState(photoView)
        } else {
            setViewPagerSwipeAllowed(items.size > 1)
        }
    }

    private fun setViewPagerSwipeAllowed(allowed: Boolean) {
        if (viewPagerSwipeAllowed == allowed) return
        viewPagerSwipeAllowed = allowed
        viewPager.isUserInputEnabled = items.size > 1 && allowed
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

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleToolbar()
                return true
            }
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
        if (items.size < 2) return
        if (oldestEdgeBaselinePosition < 0) {
            oldestEdgeBaselinePosition = position
            return
        }
        if (position <= oldestEdgeBaselinePosition) return
        if (position < items.size - 2) return
        oldestEdgeBaselinePosition = position
        onReachedOldestEdge?.invoke()
    }

    fun show(
        item: GalleryItem,
        gallery: List<GalleryItem> = emptyList(),
        index: Int = 0,
        thumbBitmap: Bitmap? = null,
        preferDrawableLoader: Boolean = false
    ) {
        items = if (gallery.isEmpty()) listOf(item) else gallery
        currentIndex = if (index in items.indices) index else 0
        preferDrawableLoaderForSingle = preferDrawableLoader && items.size == 1

        val single = items.size == 1
        val thumbUrl = items[0].url
        val singleAnimated = urlNeedsAnimatedDrawable(thumbUrl) || preferDrawableLoaderForSingle
        val singleShowsThumb = single && !singleAnimated
        oldestEdgeBaselinePosition = -1
        viewPager.adapter = PhotoPagerAdapter(thumbBitmap.takeIf { singleShowsThumb })
        viewPager.setCurrentItem(currentIndex, false)
        
        thumbnailRecyclerView?.adapter = ThumbnailAdapter()
        
        activeTouchCount = 0
        setViewPagerSwipeAllowed(items.size > 1)

        updateHeader()
        updateCounter()
        updateThumbnailSelection()
        backgroundDrawable.alpha = 0
        activeInstance = java.lang.ref.WeakReference(this)
        super.show()
        InAppOverlayHost.register(this, dismissOnOverlayTap = true)
        ObjectAnimator.ofInt(backgroundDrawable, "alpha", 0, 255).setDuration(200).start()
    }

    override fun dismiss() {
        InAppOverlayHost.unregister(this)
        if (activeInstance?.get() === this) {
            activeInstance = null
        }
        super.dismiss()
    }

    private fun updateCounter() {
        if (items.size > 1) {
            counterView.text = "${currentIndex + 1} / ${items.size}"
            counterView.visibility = View.VISIBLE
        } else {
            counterView.visibility = View.GONE
        }
    }

    fun updateGallery(newItems: List<GalleryItem>, keepUrl: String) {
        if (newItems.isEmpty() || newItems == items) return
        val appendOnly = newItems.size > items.size && newItems.subList(0, items.size) == items
        val keepIndex = newItems.indexOfFirst { it.url == keepUrl }.let {
            if (it >= 0) it else currentIndex.coerceIn(0, newItems.size - 1)
        }
        val prevSize = items.size
        items = newItems
        currentIndex = keepIndex
        if (appendOnly) {
            viewPager.adapter?.notifyItemRangeInserted(prevSize, newItems.size - prevSize)
            thumbnailRecyclerView?.adapter?.notifyItemRangeInserted(prevSize, newItems.size - prevSize)
        } else {
            viewPager.adapter?.notifyDataSetChanged()
            thumbnailRecyclerView?.adapter?.notifyDataSetChanged()
            viewPager.setCurrentItem(currentIndex, false)
        }
        updateCounter()
    }

    private fun toggleToolbar() {
        toolbarVisible = !toolbarVisible
        val targetAlpha = if (toolbarVisible) 1f else 0f
        
        if (toolbarVisible) {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
        }
        
        topBar.animate().alpha(targetAlpha).setDuration(200).withEndAction {
            if (!toolbarVisible) topBar.visibility = View.INVISIBLE
        }.start()
        
        bottomBar.animate().alpha(targetAlpha).setDuration(200).withEndAction {
            if (!toolbarVisible) bottomBar.visibility = View.INVISIBLE
        }.start()
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

    private fun updateHeader() {
        val item = currentItem ?: return
        nameLabel?.text = item.senderName.takeIf { it.isNotBlank() } ?: "User"
        
        val formattedDate = formatViewerHeaderDate(context, item.timestamp)
        dateLabel?.apply {
            if (formattedDate != null) {
                text = formattedDate
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        avatarView?.let { view ->
            if (item.uploaderId == ANONYMOUS_USER_ID) {
                view.setImageDrawable(com.mezon.mobile.ui.cells.MezonIcon.anonymousAvatar.getDrawable(context))
            } else if (!item.senderAvatarUrl.isNullOrEmpty()) {
                val proxyUrl = com.mezon.mobile.util.avatarImgproxyUrl(item.senderAvatarUrl, LayoutHelper.dp(40))
                view.setImage(proxyUrl, item.uploaderId, item.senderName)
            } else {
                view.setImage(null, item.uploaderId, item.senderName)
            }
        }
    }

    private fun updateThumbnailSelection() {
        thumbnailRecyclerView?.adapter?.notifyDataSetChanged()
        if (items.isNotEmpty()) {
            thumbnailRecyclerView?.scrollToPosition(currentIndex)
        }
    }

    private var optionsMenu: com.mezon.mobile.ui.cells.PopupMenu? = null
    private var lastMenuDismissTime = 0L

    private fun showMoreOptions(anchorView: View) {
        if (System.currentTimeMillis() - lastMenuDismissTime < 200) {
            return
        }
        val url = currentUrl
        optionsMenu = com.mezon.mobile.ui.cells.PopupMenu(
            context,
            ThemeColors.instance,
            fullWidthDividers = true
        ).apply {
            addItem(context.getString(R.string.action_save_media))
            addItem(context.getString(R.string.action_copy_image))
            addItem(context.getString(R.string.action_share_image))
            setOnItemClickListener { index ->
                when (index) {
                    0 -> downloadUrl(url)
                    1 -> copyImageFromCurrentUrl()
                    2 -> shareUrl(url)
                }
            }
            setOnDismissListener {
                optionsMenu = null
                lastMenuDismissTime = System.currentTimeMillis()
            }
            show(anchorView)
        }
    }

    private inner class ThumbnailAdapter : RecyclerView.Adapter<ThumbnailAdapter.ThumbViewHolder>() {
        inner class ThumbViewHolder(val container: FrameLayout, val view: ImageView, val borderView: View) : RecyclerView.ViewHolder(container) {
            var pendingLoad: MezonImageLoader.Cancellable? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
            val container = FrameLayout(context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(LayoutHelper.dp(44), LayoutHelper.dp(44)).apply {
                    marginEnd = LayoutHelper.dp(2)
                }
            }
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            container.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val borderView = View(context).apply {
                val drawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setStroke(LayoutHelper.dp(2), 0xFF007AFF.toInt())
                }
                background = drawable
                visibility = View.GONE
            }
            container.addView(borderView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val holder = ThumbViewHolder(container, iv, borderView)
            container.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val rv = viewPager.getChildAt(0) as? RecyclerView
                    if (rv != null) {
                        rv.scrollToPosition(pos)
                    } else {
                        viewPager.setCurrentItem(pos, false)
                    }
                }
            }
            return holder
        }

        override fun onViewRecycled(holder: ThumbViewHolder) {
            super.onViewRecycled(holder)
            holder.pendingLoad?.cancel()
            holder.pendingLoad = null
            (holder.view.drawable as? AnimatedImageDrawable)?.stop()
            holder.view.setImageDrawable(null)
        }

        override fun onBindViewHolder(holder: ThumbViewHolder, position: Int) {
            val item = items[position]

            holder.pendingLoad?.cancel()
            holder.pendingLoad = null
            (holder.view.drawable as? AnimatedImageDrawable)?.stop()
            holder.view.setImageDrawable(null)

            val loader = MezonImageLoader.getInstance(context)
            if (urlNeedsAnimatedDrawable(item.url)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    holder.pendingLoad = loader.loadDrawable(item.url, 150, 150,
                        onSuccess = { drawable ->
                            holder.view.setImageDrawable(drawable)
                            if (drawable is AnimatedImageDrawable) {
                                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                drawable.start()
                            }
                        },
                        onError = {}
                    )
                } else {
                    holder.pendingLoad = loader.load(item.url, 150, 150, onSuccess = { holder.view.setImageBitmap(it) }, onError = {})
                }
            } else {
                val proxyUrl = createImgproxyUrl(item.url, 150, 150, "fill", 150)
                holder.pendingLoad = loader.load(proxyUrl, 150, 150, onSuccess = { holder.view.setImageBitmap(it) }, onError = {})
            }
            
            if (position == currentIndex) {
                holder.view.alpha = 1.0f
                holder.borderView.visibility = View.VISIBLE
            } else {
                holder.view.alpha = 0.5f
                holder.borderView.visibility = View.GONE
            }
        }

        override fun getItemCount() = items.size
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
            val url = items[position].url
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
                (preferDrawableLoaderForSingle && items.size == 1)
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

        override fun getItemCount() = items.size

        override fun getItemId(position: Int): Long =
            items.getOrNull(position)?.url?.hashCode()?.toLong() ?: RecyclerView.NO_ID
    }

    private val entryPoint: FragmentEntryPoint by lazy {
        EntryPointAccessors.fromApplication(context.applicationContext, FragmentEntryPoint::class.java)
    }

    private fun showToast(type: ToastOverlay.ToastType, msgResId: Int) {
        val parent = window?.decorView as? ViewGroup ?: return
        ToastOverlay(context, ThemeColors.instance).show(parent, type, context.getString(msgResId))
    }

    private fun copyImageFromCurrentUrl() {
        val url = currentUrl
        if (url.isEmpty()) return
        val coordinator = entryPoint.imageClipboardCoordinator()
        entryPoint.applicationScope().launch {
            val ok = coordinator.copyRemoteUrlToClipboard(context, url, guessMimeHintForCopy(url))
            withContext(entryPoint.mainDispatcher()) {
                if (ok) {
                    showToast(ToastOverlay.ToastType.SUCCESS, R.string.message_toast_copy_image_done)
                } else {
                    showToast(ToastOverlay.ToastType.ERROR, R.string.message_toast_copy_image_failed)
                }
            }
        }
    }

    private fun guessMimeHintForCopy(url: String): String {
        val lower = url.lowercase(java.util.Locale.US)
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") || lower.contains("tenor.com", ignoreCase = true) -> "image/gif"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    private fun downloadUrl(url: String) {
        entryPoint.applicationScope().launch(entryPoint.ioDispatcher()) {
            val success = com.mezon.mobile.util.FileUtils.downloadMediaToGallery(context, url)
            withContext(entryPoint.mainDispatcher()) {
                if (success) {
                    showToast(ToastOverlay.ToastType.SUCCESS, R.string.message_toast_save_success)
                } else {
                    showToast(ToastOverlay.ToastType.ERROR, R.string.message_toast_save_failed)
                }
            }
        }
    }

    private fun downloadToFile(url: String, destFile: java.io.File) {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw Exception("HTTP Error: ${conn.responseCode}")
        }
        conn.inputStream.use { input ->
            java.io.FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun shareUrl(url: String) {
        entryPoint.applicationScope().launch(entryPoint.ioDispatcher()) {
            try {
                val mime = guessMimeHintForCopy(url)
                val ext = when (mime) {
                    "image/gif" -> "gif"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }

                val dir = java.io.File(context.cacheDir, "clipboard_images").apply { mkdirs() }
                val destFile = java.io.File(dir, "share_${System.currentTimeMillis()}.$ext")
                downloadToFile(url, destFile)

                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, destFile)

                withContext(entryPoint.mainDispatcher()) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("", uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    val resInfoList = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    for (resolveInfo in resInfoList) {
                        context.grantUriPermission(resolveInfo.activityInfo.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    val chooser = android.content.Intent.createChooser(intent, context.getString(R.string.action_share_image))
                    chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(chooser)
                }
            } catch (e: Exception) {
                withContext(entryPoint.mainDispatcher()) {
                    showToast(ToastOverlay.ToastType.ERROR, R.string.message_toast_save_failed)
                }
            }
        }
    }
}
