package com.mezon.mobile.home.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.PhotoAttachPhotoCell
import com.mezon.mobile.ui.cells.PopupMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val ITEMS_PER_ROW = 3
private const val GRID_GAP = 2
private const val LOAD_MORE_THRESHOLD = 12
private const val COLLAPSED_HEIGHT_FRACTION = 0.55f

class ChatAttachAlert(
    context: Context,
    private val mediaController: MediaController,
    private val theme: ThemeColors,
    preselectedItems: List<AttachmentPickerItem> = emptyList()
) : BottomSheet(context) {

    interface ChatAttachAlertDelegate {
        fun canSelectMore(): Boolean = true
        fun onSelectionChanged(item: AttachmentPickerItem, selected: Boolean)
        fun onCameraRequested() {}
        fun onFilesRequested() {}
        fun canEdit(item: AttachmentPickerItem): Boolean = false
        fun onEditRequested(item: AttachmentPickerItem) {}
        fun onSendRequested() {}
        fun onDismissed() {}
    }

    var attachDelegate: ChatAttachAlertDelegate? = null

    private val selectedPhotos = LinkedHashMap<Long, AttachmentPickerItem>()
    private val selectedPhotosOrder = ArrayList<Long>()

    private var gridView: RecyclerView? = null
    private lateinit var albumButton: TextView
    private var albumMenu: PopupMenu? = null
    private var adapter: PhotoAttachAdapter? = null
    private var actionRow: LinearLayout? = null
    private var sendBtn: SendButtonView? = null
    private var editBtn: View? = null
    private var didNotifyDismiss = false

    private val galleryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var albums: List<AlbumEntry> = emptyList()
    private var selectedAlbum: AlbumEntry? = null
    private var galleryPager = mediaController.GalleryPager(bucketId = null)
    private var photosLoadJob: Job? = null
    private val visiblePhotos = ArrayList<AttachmentPickerItem>()
    private var itemSize = 0

    init {
        for (item in preselectedItems) {
            if (selectedPhotos.containsKey(item.id)) continue
            selectedPhotos[item.id] = item
            selectedPhotosOrder.add(item.id)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        setSnapPoints(COLLAPSED_HEIGHT_FRACTION, 1f)
        super.onCreate(savedInstanceState)
        fixNavigationBar()

        val contentFrame = FrameLayout(context)
        buildGalleryView(contentFrame)

        contentLayout?.apply {
            layoutParams.height = LayoutHelper.MATCH_PARENT
            addView(contentFrame, LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f))
        }

        loadMorePhotos()
        galleryScope.launch {
            albums = mediaController.loadAlbums()
            albumButton.isEnabled = albums.isNotEmpty()
        }
        updateSendButton()
    }

    private fun buildGalleryView(parent: FrameLayout) {
        val availableWidth = AndroidUtilities.displaySize.x
        itemSize = (availableWidth - LayoutHelper.dp(GRID_GAP.toFloat()) * (ITEMS_PER_ROW - 1)) / ITEMS_PER_ROW

        val headerHeight = LayoutHelper.dp(44f)

        val uploadButton = buildHeaderButton(MezonIcon.fileIconGray, R.string.file_selection_upload) {
            dismiss()
            attachDelegate?.onFilesRequested()
        }
        albumButton = TextView(context).apply {
            setText(R.string.file_selection_recent_photos)
            setTextColor(theme.onSurface)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setPaddingRelative(LayoutHelper.dp(8f), 0, LayoutHelper.dp(8f), 0)
            compoundDrawablePadding = LayoutHelper.dp(6f)
            val chevron = MezonIcon.chevronDownSmallIcon.getDrawable(context, theme.onSurface)
            val iconSize = LayoutHelper.dp(16f)
            chevron.setBounds(0, 0, iconSize, iconSize)
            setCompoundDrawablesRelative(null, null, chevron, null)
            foreground = RippleDrawable(
                ColorStateList.valueOf(theme.onSurface and 0x1AFFFFFF),
                null,
                ColorDrawable(Color.WHITE)
            )
            isEnabled = false
            isFocusable = true
            setOnClickListener { showAlbumMenu() }
        }
        val headerRow = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val width = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
                val sideWidth = minOf(uploadButton.measuredWidth, width / 3)
                val heightSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
                uploadButton.measure(MeasureSpec.makeMeasureSpec(sideWidth, MeasureSpec.EXACTLY), heightSpec)
                // Reserve equal space on both sides of the album title to keep it centered.
                albumButton.measure(MeasureSpec.makeMeasureSpec(width - 2 * sideWidth, MeasureSpec.AT_MOST), heightSpec)
            }
        }.apply {
            setPadding(LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f), 0)
            setBackgroundColor(sheetBackgroundColor)
            addView(uploadButton, FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.START))
            addView(albumButton, FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER))
        }

        adapter = PhotoAttachAdapter()

        val layoutManager = GridLayoutManager(context, ITEMS_PER_ROW)
        val gap = LayoutHelper.dp(GRID_GAP.toFloat())
        val navBarInset = AndroidUtilities.navigationBarHeight
        gridView = RecyclerView(context).apply {
            this.layoutManager = layoutManager
            this.adapter = this@ChatAttachAlert.adapter
            clipToPadding = false
            setPadding(0, headerHeight, 0, LayoutHelper.dp(56f) + navBarInset)
            clipChildren = false
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    val pos = parent.getChildAdapterPosition(view)
                    val col = pos % ITEMS_PER_ROW
                    outRect.left = if (col > 0) gap else 0
                    outRect.bottom = gap
                }
            })
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    val total = this@ChatAttachAlert.adapter?.itemCount ?: 0
                    if (lastVisible >= total - LOAD_MORE_THRESHOLD) {
                        loadMorePhotos()
                    }
                }
            })
        }
        parent.addView(gridView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        // Header must be above grid — RecyclerView is MATCH_PARENT and steals touches otherwise.
        headerRow.elevation = LayoutHelper.dp(8f).toFloat()
        headerRow.isClickable = true
        parent.addView(headerRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, headerHeight, Gravity.TOP
        ))

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        this.actionRow = actionRow
        editBtn = TextView(context).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            text = context.getString(R.string.image_editor_edit)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(8f)
                setColor(Color.WHITE)
            }
            setOnClickListener { onEditClicked() }
        }
        actionRow.addView(editBtn, LinearLayout.LayoutParams(0, LayoutHelper.dp(48f), 1f).apply {
            rightMargin = LayoutHelper.dp(8f)
        })

        sendBtn = SendButtonView(context, theme).apply {
            visibility = View.GONE
            setOnClickListener { onSendClicked() }
        }
        actionRow.addView(sendBtn, LinearLayout.LayoutParams(0, LayoutHelper.dp(48f), 3f))
        parent.addView(
            actionRow,
            FrameLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT, LayoutHelper.dp(48f),
                Gravity.BOTTOM
            ).apply {
                bottomMargin = LayoutHelper.dp(4f) + navBarInset
                leftMargin = LayoutHelper.dp(12f)
                rightMargin = LayoutHelper.dp(12f)
            }
        )
    }

    private fun buildHeaderButton(
        icon: MezonIcon,
        textRes: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = LayoutHelper.dp(10f)
            val vPad = LayoutHelper.dp(6f)
            setPadding(hPad, vPad, hPad, vPad)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }

        val iconView = ImageView(context).apply {
            setImageDrawable(icon.getDrawable(context).mutate())
        }
        val iconSize = LayoutHelper.dp(20f)
        btn.addView(iconView, LinearLayout.LayoutParams(iconSize, iconSize))

        val label = TextView(context).apply {
            setText(textRes)
            setTextColor(theme.onSurface)
            textSize = 13f
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            setPadding(LayoutHelper.dp(6f), 0, 0, 0)
        }
        btn.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return btn
    }

    private fun showAlbumMenu() {
        if (albumMenu != null) return
        val entries = listOf(null) + albums
        gridView?.foreground = ColorDrawable(0x4D000000)
        albumMenu = PopupMenu(
            context,
            theme,
            focusable = true,
            itemTextSizeSp = 14f,
            wrapContentWidth = true
        ).apply {
            for (album in entries) {
                val name = album?.bucketName ?: context.getString(R.string.file_selection_recent_photos)
                val icon = if (album == selectedAlbum) MezonIcon.checkmarkSmallIcon.resId else 0
                addItem(name, icon)
            }
            setOnItemClickListener { selectAlbum(entries[it]) }
            setOnDismissListener {
                gridView?.foreground = null
                albumMenu = null
            }
            show(albumButton)
        }
    }

    private fun selectAlbum(album: AlbumEntry?) {
        if (selectedAlbum == album) return
        selectedAlbum = album
        albumButton.text = album?.bucketName ?: context.getString(R.string.file_selection_recent_photos)
        photosLoadJob?.cancel()
        photosLoadJob = null
        galleryPager = mediaController.GalleryPager(album?.bucketId)

        gridView?.stopScroll()
        visiblePhotos.clear()
        adapter?.notifyDataSetChanged()
        gridView?.scrollToPosition(0)
        loadMorePhotos()
    }

    private fun loadMorePhotos() {
        if (photosLoadJob?.isActive == true || !galleryPager.hasMore) return
        val pager = galleryPager
        photosLoadJob = galleryScope.launch {
            val page = pager.loadNextPage()
            if (page.isEmpty()) return@launch

            val insertPosition = visiblePhotos.size + 1 // The first tile is the camera.
            visiblePhotos.addAll(page)
            adapter?.notifyItemRangeInserted(insertPosition, page.size)
        }
    }

    private fun onCheckClicked(cell: PhotoAttachPhotoCell) {
        val item = cell.getItem() ?: return
        val id = item.id

        if (selectedPhotos.containsKey(id)) {
            selectedPhotos.remove(id)
            selectedPhotosOrder.remove(id)
            cell.setChecked(-1, false, true)
            updateCheckedPhotoIndices()
            attachDelegate?.onSelectionChanged(item, false)
        } else {
            if (attachDelegate?.canSelectMore() == false ||
                selectedPhotosOrder.size >= AttachmentPickerItem.GALLERY_MAX_SELECTION
            ) {
                Toast.makeText(context, "Maximum ${AttachmentPickerItem.GALLERY_MAX_SELECTION} items", Toast.LENGTH_SHORT).show()
                return
            }

            val maxSize = AttachmentPickerItem.maxFileSizeBytes(item.mimeType)
            if (item.size > maxSize) {
                val limitMB = maxSize / (1024 * 1024)
                Toast.makeText(context, "File exceeds ${limitMB}MB limit", Toast.LENGTH_SHORT).show()
                return
            }

            selectedPhotos[id] = item
            selectedPhotosOrder.add(id)
            cell.setChecked(selectedPhotosOrder.size - 1, true, true)
            attachDelegate?.onSelectionChanged(item, true)
        }

        updateSendButton()
    }

    private fun updateCheckedPhotoIndices() {
        val count = gridView?.childCount ?: return
        for (i in 0 until count) {
            val child = gridView?.getChildAt(i) ?: continue
            if (child is PhotoAttachPhotoCell) {
                val item = child.getItem() ?: continue
                val idx = selectedPhotosOrder.indexOf(item.id)
                child.setChecked(idx, idx >= 0, false)
            }
        }
    }

    private fun updateSendButton() {
        val count = selectedPhotosOrder.size
        sendBtn?.visibility = if (count > 0) View.VISIBLE else View.GONE
        sendBtn?.setCount(count)
        val editableItem = selectedPhotosOrder.singleOrNull()?.let { selectedPhotos[it] }
        editBtn?.visibility = if (
            editableItem != null &&
            !editableItem.isVideo &&
            editableItem.mimeType.startsWith("image/", ignoreCase = true) &&
            attachDelegate?.canEdit(editableItem) == true
        ) View.VISIBLE else View.GONE
    }

    private fun onEditClicked() {
        val item = selectedPhotosOrder.singleOrNull()?.let { selectedPhotos[it] } ?: return
        if (attachDelegate?.canEdit(item) != true) return
        attachDelegate?.onEditRequested(item)
    }

    private fun onSendClicked() {
        if (selectedPhotosOrder.isEmpty()) return
        dismiss()
        attachDelegate?.onSendRequested()
    }

    override fun dismiss() {
        albumMenu?.dismiss()
        galleryScope.cancel()
        if (!didNotifyDismiss) {
            didNotifyDismiss = true
            attachDelegate?.onDismissed()
        }
        super.dismiss()
    }

    override fun onContainerViewTranslation() {
        val translation = containerView?.translationY ?: return
        // Keep the actions at the visible bottom while the gallery moves between snap points.
        actionRow?.translationY = -translation.coerceIn(0f, snapTranslationY(0))
    }

    inner class PhotoAttachAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == VIEW_TYPE_CAMERA) {
                val cell = FrameLayout(parent.context).apply {
                    setBackgroundColor(0xFF303238.toInt())
                    isClickable = true
                    setOnClickListener { attachDelegate?.onCameraRequested() }
                }
                val icon = ImageView(parent.context).apply {
                    setImageDrawable(MezonIcon.cameraIcon.getDrawable(parent.context, Color.WHITE))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                cell.addView(
                    icon,
                    FrameLayout.LayoutParams(LayoutHelper.dp(34f), LayoutHelper.dp(34f), Gravity.CENTER)
                )
                cell.layoutParams = RecyclerView.LayoutParams(itemSize, itemSize)
                return object : RecyclerView.ViewHolder(cell) {}
            }

            val cell = PhotoAttachPhotoCell(parent.context, theme)
            cell.onCheckClickListener = { onCheckClicked(it) }
            cell.layoutParams = RecyclerView.LayoutParams(itemSize, itemSize)
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == VIEW_TYPE_CAMERA) return
            val cell = holder.itemView as PhotoAttachPhotoCell
            val item = visiblePhotos[position - 1]
            cell.setPhotoEntry(item)
            val idx = selectedPhotosOrder.indexOf(item.id)
            cell.setChecked(idx, idx >= 0, false)
        }

        override fun getItemViewType(position: Int): Int =
            if (position == 0) VIEW_TYPE_CAMERA else VIEW_TYPE_MEDIA

        override fun getItemCount(): Int = visiblePhotos.size + 1
    }

    private class SendButtonView(context: Context, private val theme: ThemeColors) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.primary
        }
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LayoutHelper.sp(15f)
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val tmpRect = RectF()
        private var count = 0

        fun setCount(c: Int) {
            count = c
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val radius = LayoutHelper.dp(8f).toFloat()
            tmpRect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(tmpRect, radius, radius, bgPaint)

            val text = if (count > 0) "Send ($count)" else "Send"
            val ty = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, width / 2f, ty, textPaint)
        }
    }

    companion object {
        const val REQUEST_CODE_MEDIA_PERMISSION = 1004
        private const val VIEW_TYPE_CAMERA = 0
        private const val VIEW_TYPE_MEDIA = 1
    }
}
