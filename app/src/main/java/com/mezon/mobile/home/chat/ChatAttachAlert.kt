package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
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

private const val TAG = "ChatAttachAlert"
private const val ITEMS_PER_ROW = 3
private const val GRID_GAP = 2

class ChatAttachAlert(
    context: Context,
    private val mediaController: MediaController,
    private val theme: ThemeColors,
    preselectedItems: List<AttachmentPickerItem> = emptyList()
) : BottomSheet(context), MediaController.GalleryLoadListener {

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
    private var headerRowView: LinearLayout? = null
    private var adapter: PhotoAttachAdapter? = null
    private var sendBtn: SendButtonView? = null
    private var editBtn: View? = null
    private var swipeDismissFromHandle = false
    private var didNotifyDismiss = false

    private var allPhotos = ArrayList<AttachmentPickerItem>()
    private var itemSize = 0

    init {
        for (item in preselectedItems) {
            if (selectedPhotos.containsKey(item.id)) continue
            selectedPhotos[item.id] = item
            selectedPhotosOrder.add(item.id)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        fixNavigationBar()
        setAllowNestedScroll(true)

        val contentHeight = (AndroidUtilities.displaySize.y * 0.55f).toInt()

        val contentFrame = FrameLayout(context)
        buildGalleryView(contentFrame)

        contentLayout?.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, contentHeight
        ))

        mediaController.setGalleryLoadListener(this)
        mediaController.loadGalleryPhotos()
        updateSendButton()
    }

    private val LOAD_MORE_THRESHOLD = 12

    private fun buildGalleryView(parent: FrameLayout) {
        val availableWidth = AndroidUtilities.displaySize.x
        itemSize = (availableWidth - LayoutHelper.dp(GRID_GAP.toFloat()) * (ITEMS_PER_ROW - 1)) / ITEMS_PER_ROW

        val headerHeight = LayoutHelper.dp(44f)

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f), 0)
        }
        headerRowView = headerRow
        buildHeaderButton(headerRow, MezonIcon.fileIconGray, R.string.file_selection_upload) {
            dismiss()
            attachDelegate?.onFilesRequested()
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
                    if (lastVisible >= total - LOAD_MORE_THRESHOLD && mediaController.hasMore) {
                        mediaController.loadMorePhotos()
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
        parent: LinearLayout,
        icon: MezonIcon,
        textRes: Int,
        onClick: () -> Unit
    ) {
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
            setPadding(LayoutHelper.dp(6f), 0, 0, 0)
        }
        btn.addView(label, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        parent.addView(btn)
    }

    override fun onGalleryLoaded(photos: List<AttachmentPickerItem>, totalLoaded: Int, hasMore: Boolean) {
        AndroidUtilities.runOnUIThread {
            val previousSize = allPhotos.size
            allPhotos.clear()
            allPhotos.addAll(photos)
            val insertedCount = allPhotos.size - previousSize
            if (previousSize == 0) {
                adapter?.notifyDataSetChanged()
            } else if (insertedCount > 0) {
                adapter?.notifyItemRangeInserted(previousSize + 1, insertedCount)
            }
            Log.d(TAG, "Gallery page loaded: $totalLoaded items total, hasMore=$hasMore")
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
        mediaController.setGalleryLoadListener(null)
        if (!didNotifyDismiss) {
            didNotifyDismiss = true
            attachDelegate?.onDismissed()
        }
        super.dismiss()
    }

    override fun canDismissWithSwipe(): Boolean {
        if (swipeDismissFromHandle) return true
        val rv = gridView ?: return super.canDismissWithSwipe()
        return !rv.canScrollVertically(-1)
    }

    override fun onContainerTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> swipeDismissFromHandle = isInSwipeDismissHandleZone(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeDismissFromHandle = false
        }
        return false
    }

    private fun isInSwipeDismissHandleZone(ev: MotionEvent): Boolean {
        val header = headerRowView
        if (header != null && header.isShown) {
            val headerLoc = IntArray(2)
            header.getLocationOnScreen(headerLoc)
            if (ev.rawY <= headerLoc[1] + header.height) return true
        }
        val handle = contentLayout?.getChildAt(0) ?: return false
        if (!handle.isShown) return false
        val handleLoc = IntArray(2)
        handle.getLocationOnScreen(handleLoc)
        return ev.rawY <= handleLoc[1] + handle.height
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
            val item = allPhotos[position - 1]
            cell.setPhotoEntry(item)
            val idx = selectedPhotosOrder.indexOf(item.id)
            cell.setChecked(idx, idx >= 0, false)
        }

        override fun getItemViewType(position: Int): Int =
            if (position == 0) VIEW_TYPE_CAMERA else VIEW_TYPE_MEDIA

        override fun getItemCount(): Int = allPhotos.size + 1
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
