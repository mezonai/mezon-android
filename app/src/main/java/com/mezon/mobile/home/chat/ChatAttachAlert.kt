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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.PhotoAttachPhotoCell

private const val TAG = "ChatAttachAlert"
private const val ITEMS_PER_ROW = 3
private const val GRID_GAP = 2

class ChatAttachAlert(
    context: Context,
    private val mediaController: MediaController,
    private val theme: ThemeColors
) : BottomSheet(context), MediaController.GalleryLoadListener {

    interface ChatAttachAlertDelegate {
        fun onAttachmentsSelected(items: List<AttachmentPickerItem>)
    }

    var attachDelegate: ChatAttachAlertDelegate? = null

    private val selectedPhotos = LinkedHashMap<Long, AttachmentPickerItem>()
    private val selectedPhotosOrder = ArrayList<Long>()

    private var gridView: RecyclerView? = null
    private var adapter: PhotoAttachAdapter? = null
    private var emptyView: TextView? = null
    private var sendBtn: SendButtonView? = null

    private var allPhotos = ArrayList<AttachmentPickerItem>()
    private var itemSize = 0

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
    }

    private val LOAD_MORE_THRESHOLD = 12

    private fun buildGalleryView(parent: FrameLayout) {
        val availableWidth = AndroidUtilities.displaySize.x
        itemSize = (availableWidth - LayoutHelper.dp(GRID_GAP.toFloat()) * (ITEMS_PER_ROW - 1)) / ITEMS_PER_ROW

        adapter = PhotoAttachAdapter()

        val layoutManager = GridLayoutManager(context, ITEMS_PER_ROW)
        val gap = LayoutHelper.dp(GRID_GAP.toFloat())
        gridView = RecyclerView(context).apply {
            this.layoutManager = layoutManager
            this.adapter = this@ChatAttachAlert.adapter
            clipToPadding = false
            setPadding(0, 0, 0, LayoutHelper.dp(56f))
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

        emptyView = TextView(context).apply {
            text = "No photos or videos"
            setTextColor(theme.getColor(ThemeColors.key_text_secondary))
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        parent.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        sendBtn = SendButtonView(context, theme)
        sendBtn!!.visibility = View.GONE
        sendBtn!!.setOnClickListener { onSendClicked() }
        parent.addView(
            sendBtn,
            FrameLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT, LayoutHelper.dp(48f),
                Gravity.BOTTOM
            ).apply {
                bottomMargin = LayoutHelper.dp(4f)
                leftMargin = LayoutHelper.dp(12f)
                rightMargin = LayoutHelper.dp(12f)
            }
        )
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
                adapter?.notifyItemRangeInserted(previousSize, insertedCount)
            }
            emptyView?.visibility = if (allPhotos.isEmpty()) View.VISIBLE else View.GONE
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
        } else {
            if (selectedPhotosOrder.size >= AttachmentPickerItem.GALLERY_MAX_SELECTION) {
                Toast.makeText(context, "Maximum ${AttachmentPickerItem.GALLERY_MAX_SELECTION} items", Toast.LENGTH_SHORT).show()
                return
            }

            val maxSize = if (item.isVideo) AttachmentPickerItem.MAX_FILE_SIZE else AttachmentPickerItem.IMAGE_MAX_FILE_SIZE
            if (item.size > maxSize) {
                val limitMB = maxSize / (1024 * 1024)
                Toast.makeText(context, "File exceeds ${limitMB}MB limit", Toast.LENGTH_SHORT).show()
                return
            }

            selectedPhotos[id] = item
            selectedPhotosOrder.add(id)
            cell.setChecked(selectedPhotosOrder.size - 1, true, true)
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
    }

    private fun onSendClicked() {
        if (selectedPhotosOrder.isEmpty()) return
        val result = selectedPhotosOrder.mapNotNull { selectedPhotos[it] }
        dismiss()
        attachDelegate?.onAttachmentsSelected(result)
    }

    override fun dismiss() {
        mediaController.setGalleryLoadListener(null)
        super.dismiss()
    }

    inner class PhotoAttachAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = PhotoAttachPhotoCell(parent.context, theme)
            cell.onCheckClickListener = { onCheckClicked(it) }
            cell.layoutParams = RecyclerView.LayoutParams(itemSize, itemSize)
            return object : RecyclerView.ViewHolder(cell) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val cell = holder.itemView as PhotoAttachPhotoCell
            val item = allPhotos[position]
            cell.setPhotoEntry(item)
            val idx = selectedPhotosOrder.indexOf(item.id)
            cell.setChecked(idx, idx >= 0, false)
        }

        override fun getItemCount(): Int = allPhotos.size
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
        const val REQUEST_CODE_MEDIA_PERMISSION = 1001
    }
}
