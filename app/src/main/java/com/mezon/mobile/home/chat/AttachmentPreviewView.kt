package com.mezon.mobile.home.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.MezonIcon

internal class AttachmentPreviewView(
    context: Context,
    private val theme: ThemeColors,
    private val onRemove: (Int) -> Unit
) : LinearLayout(context) {

    private val thumbnailTasks = ArrayList<Runnable>()
    private val strip = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val divider = View(context)

    init {
        orientation = VERTICAL
        visibility = GONE

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPaddingRelative(LayoutHelper.dp(12), LayoutHelper.dp(4), LayoutHelper.dp(12), LayoutHelper.dp(8))
            addView(strip, FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        addView(divider, LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(1)))
    }

    fun bind(attachments: List<AttachmentPickerItem>, panelColor: Int) {
        clear()
        if (attachments.isEmpty()) return

        setBackgroundColor(panelColor)
        divider.setBackgroundColor(theme.dividerColor)
        attachments.forEachIndexed { index, item ->
            val tile = FrameLayout(context)
            val width = LayoutHelper.dp(if (item.isFileType) 168 else 60)
            val height = LayoutHelper.dp(if (item.isFileType) 44 else 60)
            val badgeOverhang = LayoutHelper.dp(12)
            val preview = if (item.isFileType) filePreview(item) else mediaPreview(item)
            tile.addView(preview, FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.START).apply {
                topMargin = badgeOverhang
            })

            val remove = removeButton(item.filename, panelColor) {
                if (tile.parent === strip) onRemove(index)
            }
            val touchSize = LayoutHelper.dp(32)
            tile.addView(remove, FrameLayout.LayoutParams(touchSize, touchSize, Gravity.TOP or Gravity.END))
            strip.addView(tile, LayoutParams(width + badgeOverhang, height + badgeOverhang).apply {
                if (index < attachments.lastIndex) marginEnd = LayoutHelper.dp(2)
            })
        }
        visibility = VISIBLE
    }

    fun clear() {
        thumbnailTasks.forEach { ThumbnailCache.cancel(it) }
        thumbnailTasks.clear()
        strip.removeAllViews()
        visibility = GONE
    }

    private fun mediaPreview(item: AttachmentPickerItem): ImageView {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground()
            clipToOutline = true
        }
        val task = ThumbnailCache.load(context.contentResolver, item, object : ThumbnailCache.Callback {
            override fun onThumbnailLoaded(id: Long, bitmap: Bitmap) {
                image.setImageBitmap(bitmap)
            }
        })
        if (task != null) thumbnailTasks.add(task)
        return image
    }

    private fun filePreview(item: AttachmentPickerItem): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBackground()
        setPaddingRelative(LayoutHelper.dp(10), 0, LayoutHelper.dp(10), 0)

        val icon = ImageView(context).apply {
            setImageDrawable(MezonIcon.loadDrawable(context, R.drawable.mz_file_24, theme.iconTint))
        }
        val iconSize = LayoutHelper.dp(22)
        addView(icon, LayoutParams(iconSize, iconSize).apply { marginEnd = LayoutHelper.dp(8) })

        val labels = LinearLayout(context).apply { orientation = VERTICAL }
        labels.addView(TextView(context).apply {
            text = item.filename
            setTextColor(theme.onSurface)
            textSize = 12f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val subtype = item.mimeType.substringAfterLast('/', "").uppercase()
        if (subtype.isNotEmpty()) {
            labels.addView(TextView(context).apply {
                text = subtype
                setTextColor(theme.textDisabled)
                textSize = 10f
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        addView(labels, LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
    }

    private fun roundedBackground() = GradientDrawable().apply {
        setColor(theme.secondaryLight)
        cornerRadius = LayoutHelper.dpf(8f)
    }

    private fun removeButton(filename: String, panelColor: Int, onClick: () -> Unit) = ImageButton(context).apply {
        contentDescription = context.getString(R.string.attachment_preview_remove, filename)
        setImageDrawable(MezonIcon.loadDrawable(context, R.drawable.mz_close_16, Color.WHITE))
        scaleType = ImageView.ScaleType.FIT_CENTER
        val badge = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF676B73.toInt())
            setStroke(LayoutHelper.dp(2), panelColor)
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), badge, null)
        background = InsetDrawable(ripple, LayoutHelper.dp(4))
        val pad = LayoutHelper.dp(10)
        setPadding(pad, pad, pad, pad)
        setOnClickListener { onClick() }
    }
}
