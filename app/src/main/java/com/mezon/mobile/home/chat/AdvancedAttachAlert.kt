package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

private const val ITEMS_PER_ROW = 4
private const val ICON_BG_SIZE = 42
private const val ICON_SIZE = 24

class AdvancedAttachAlert(
    context: Context,
    private val theme: ThemeColors,
    private val clanId: Long = 0L,
    private val isAnonymousMode: Boolean = false,
    /**
     * Web `FileSelectionButton`: poll hidden only for `CHANNEL_TYPE_DM` (1:1 DM).
     * Caller must set this from [com.mezon.mobile.home.chat.poll.canCreatePoll] — not from [clanId].
     */
    private val showCreatePoll: Boolean = false
) : BottomSheet(context) {

    interface AdvancedAttachAlertDelegate {
        fun onLocationSelected()
        fun onFilesSelected()
        fun onBuzzSelected()
        fun onAnonymousToggled()
        fun onCreatePollRequested() {}
        fun onShareContactSelected() {}
    }

    var advancedDelegate: AdvancedAttachAlertDelegate? = null

    private data class FunctionItem(
        val id: String,
        val labelResId: Int,
        val icon: MezonIcon
    )

    private val functions: List<FunctionItem> = buildFunctions()

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
        // Poll: non-DM channels only (group DM + clan). Threads/ephemeral still use clanId above.
        if (showCreatePoll) {
            list.add(FunctionItem("poll", R.string.advanced_poll, MezonIcon.pollIconGray))
        }
        if (!isAnonymousMode) {
            list.add(FunctionItem("share_contact", R.string.advanced_share_contact, MezonIcon.shareContactIconGray))
        }
        return list
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        fixNavigationBar()

        val gridView = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, ITEMS_PER_ROW)
            adapter = FunctionGridAdapter()
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f))
        }

        val contentFrame = FrameLayout(context)
        contentFrame.addView(gridView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        contentLayout?.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private inner class FunctionGridAdapter : RecyclerView.Adapter<FunctionGridAdapter.Holder>() {

        inner class Holder(val cell: FunctionCell) : RecyclerView.ViewHolder(cell)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cell = FunctionCell(parent.context, theme)
            cell.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, LayoutHelper.dp(100f)
            )
            return Holder(cell)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = functions[position]
            holder.cell.setData(item.labelResId, item.icon)
            holder.cell.setOnClickListener { onFunctionClicked(item) }
        }

        override fun getItemCount() = functions.size
    }

    private fun onFunctionClicked(item: FunctionItem) {
        dismiss()
        when (item.id) {
            "location" -> advancedDelegate?.onLocationSelected()
            "files" -> advancedDelegate?.onFilesSelected()
            "buzz" -> advancedDelegate?.onBuzzSelected()
            "anonymous" -> advancedDelegate?.onAnonymousToggled()
            "poll" -> advancedDelegate?.onCreatePollRequested()
            "share_contact" -> advancedDelegate?.onShareContactSelected()
            else -> Toast.makeText(context, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
        }
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
        val w = MeasureSpec.getSize(widthMeasureSpec)
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
