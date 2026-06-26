package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

private const val ITEMS_PER_ROW = 4
private const val ICON_BG_SIZE = 42
private const val ICON_SIZE = 24

class AdvancedMenuView(
    context: Context,
    private val theme: ThemeColors,
    private val clanId: Long = 0L,
    private val isAnonymousMode: Boolean = false,
    private val showCreatePoll: Boolean = false
) : FrameLayout(context) {

    interface AdvancedMenuViewDelegate {
        fun onLocationSelected()
        fun onFilesSelected()
        fun onBuzzSelected()
        fun onAnonymousToggled()
        fun onCreatePollRequested() {}
        fun onShareContactSelected() {}
        fun onDragY(dy: Float) {}
        fun onAnimateExpand(expand: Boolean) {}
    }

    var delegate: AdvancedMenuViewDelegate? = null
    
    private var dragStartY = 0f
    private var dragging = false
    private var dragTranslation = 0f
    private var velocityTracker: android.view.VelocityTracker? = null
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val dismissVelocity = 1000f

    private data class FunctionItem(
        val id: String,
        val labelResId: Int,
        val icon: MezonIcon
    )

    private val functions: List<FunctionItem> = buildFunctions()

    init {
        setBackgroundColor(theme.getColor(ThemeColors.key_sheetBackground))

        val gridView = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, ITEMS_PER_ROW)
            adapter = FunctionGridAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f))
        }

        addView(gridView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
    }

    private fun buildFunctions(): List<FunctionItem> {
        val list = ArrayList<FunctionItem>()
        if (!isAnonymousMode) {
            list.add(FunctionItem("location", R.string.advanced_location, MezonIcon.locationIconGray))
        }
        list.add(FunctionItem("files", R.string.advanced_files, MezonIcon.fileIconGray))
        if (clanId != 0L) {
            list.add(FunctionItem("create_thread", R.string.advanced_threads, MezonIcon.threadPlusIconGray))
            val anonLabel = if (isAnonymousMode) R.string.advanced_anonymous_off else R.string.advanced_anonymous
            list.add(FunctionItem("anonymous", anonLabel, MezonIcon.anonymousIconGray))
        }
        list.add(FunctionItem("buzz", R.string.advanced_buzz, MezonIcon.buzzAdvancedIcon))
        if (clanId != 0L) {
            list.add(FunctionItem("ephemeral", R.string.advanced_ephemeral, MezonIcon.ephemeralIconGray))
        }
        list.add(FunctionItem("transfer_funds", R.string.advanced_transfer_funds, MezonIcon.sendMoneyAdvancedIcon))
        if (showCreatePoll) {
            list.add(FunctionItem("poll", R.string.advanced_poll, MezonIcon.pollIconGray))
        }
        if (!isAnonymousMode) {
            list.add(FunctionItem("share_contact", R.string.advanced_share_contact, MezonIcon.shareContactIconGray))
        }
        return list
    }

    private inner class FunctionGridAdapter : RecyclerView.Adapter<FunctionGridAdapter.Holder>() {

        inner class Holder(val cell: FunctionCell) : RecyclerView.ViewHolder(cell)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cell = FunctionCell(parent.context, theme)
            cell.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, LayoutHelper.dp(100f)
            )
            val holder = Holder(cell)
            cell.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onFunctionClicked(functions[pos])
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = functions[position]
            holder.cell.setData(item.labelResId, item.icon)
        }

        override fun getItemCount() = functions.size
    }

    private fun onFunctionClicked(item: FunctionItem) {
        val del = delegate
        var dismiss = true
        when (item.id) {
            "location" -> del?.onLocationSelected()
            "files" -> del?.onFilesSelected()
            "buzz" -> del?.onBuzzSelected()
            "anonymous" -> {
                del?.onAnonymousToggled()
                dismiss = false
            }
            "poll" -> del?.onCreatePollRequested()
            "share_contact" -> del?.onShareContactSelected()
            else -> {
                android.widget.Toast.makeText(context, R.string.feature_coming_soon, android.widget.Toast.LENGTH_SHORT).show()
                dismiss = false
            }
        }
        if (dismiss) {
            del?.onAnimateExpand(false)
        }
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            dragStartY = ev.rawY
            dragging = false
            velocityTracker?.recycle()
            velocityTracker = android.view.VelocityTracker.obtain()
            velocityTracker?.addMovement(ev)
        } else if (ev.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
            velocityTracker?.addMovement(ev)
            val dy = ev.rawY - dragStartY
            if (!dragging && dy > touchSlop) {
                val grid = getChildAt(0) as? RecyclerView
                if (grid == null || !grid.canScrollVertically(-1)) {
                    dragging = true
                    dragStartY = ev.rawY
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.rawY
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - dragStartY
                if (!dragging && dy > touchSlop) {
                    val grid = getChildAt(0) as? RecyclerView
                    if (grid == null || !grid.canScrollVertically(-1)) {
                        dragging = true
                        dragStartY = ev.rawY
                    }
                }
                if (dragging) {
                    dragTranslation = (ev.rawY - dragStartY).coerceAtLeast(0f)
                    val child = getChildAt(0)
                    child?.translationY = dragTranslation
                    delegate?.onDragY(dragTranslation)
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    val child = getChildAt(0) ?: return true
                    val h = child.height.toFloat()
                    if (vy > dismissVelocity || dragTranslation > h * 0.4f) {
                        delegate?.onAnimateExpand(false)
                    } else {
                        delegate?.onAnimateExpand(true)
                        child.animate()
                            .translationY(0f)
                            .setDuration(200)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}

private class FunctionCell(context: Context, private val theme: ThemeColors) : View(context) {

    private var label = ""
    private var iconDrawable: Drawable? = null
    private var labelLayout: StaticLayout? = null

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LayoutHelper.dpf(12f)
        typeface = Typeface.DEFAULT
    }

    fun setData(labelResId: Int, icon: MezonIcon) {
        label = context.getString(labelResId)
        iconDrawable = icon.getDrawable(context).mutate()
        labelPaint.color = theme.onSurface
        buildLayout()
        invalidate()
    }

    private fun buildLayout() {
        labelPaint.color = theme.onSurface
        val w = if (measuredWidth > 0) measuredWidth else AndroidUtilities.displaySize.x / ITEMS_PER_ROW
        val textW = (w - LayoutHelper.dp(8f)).coerceAtLeast(1)
        labelLayout = StaticLayout.Builder.obtain(label, 0, label.length, labelPaint, textW)
            .setMaxLines(2)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, LayoutHelper.dp(100f))
        buildLayout()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val bgSize = LayoutHelper.dp(ICON_BG_SIZE.toFloat())
        val iconSize = LayoutHelper.dp(ICON_SIZE.toFloat())
        val topPad = LayoutHelper.dp(8f).toFloat()

        iconDrawable?.let {
            val iconLeft = (cx - iconSize / 2f).toInt()
            val iconTop = topPad.toInt() + (bgSize - iconSize) / 2
            it.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            it.draw(canvas)
        }

        labelLayout?.let {
            val textY = topPad + bgSize + LayoutHelper.dp(8f)
            canvas.save()
            canvas.translate((width - it.width) / 2f, textY)
            it.draw(canvas)
            canvas.restore()
        }
    }
}
