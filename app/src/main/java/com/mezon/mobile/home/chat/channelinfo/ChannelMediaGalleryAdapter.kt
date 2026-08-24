package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ChannelGalleryController
import com.mezon.mobile.home.ChannelGalleryMediaItem
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.PhotoViewer
import com.mezon.mobile.home.chat.VideoPlayerDialog
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.BackupImageView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.createImgproxyUrl
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VIEW_HEADER = 1
private const val VIEW_GRID = 2
private const val VIEW_FOOTER = 3

internal sealed class MediaGalleryRow {
    data class HeaderRow(val epochDay: Long, val label: CharSequence) : MediaGalleryRow()
    data class GridRow(
        val epochDay: Long,
        val rowIndex: Int,
        val a: ChannelGalleryMediaItem?,
        val b: ChannelGalleryMediaItem?,
        val c: ChannelGalleryMediaItem?
    ) : MediaGalleryRow()

    object LoadingFooter : MediaGalleryRow()
}

internal class ChannelMediaGalleryAdapter(
    private val channelId: Long,
    private val theme: ThemeColors,
    private val cellSizePx: () -> Int,
    private val isDm: Boolean,
    private val members: () -> Map<Long, ClanMember>,
    private val galleryController: ChannelGalleryController,
    private val hostContext: () -> Context?,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows = listOf<MediaGalleryRow>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var diffJob: Job? = null

    init {
        setHasStableIds(true)
    }

    fun submit(newRows: List<MediaGalleryRow>) {
        diffJob?.cancel()
        if (rows.size < 50 && newRows.size < 50) {
            applyRows(newRows, DiffUtil.calculateDiff(GalleryDiffCallback(rows, newRows)))
        } else {
            val oldRows = rows
            diffJob = scope.launch {
                val result = withContext(Dispatchers.Default) {
                    DiffUtil.calculateDiff(GalleryDiffCallback(oldRows, newRows))
                }
                applyRows(newRows, result)
            }
        }
    }

    private fun applyRows(newRows: List<MediaGalleryRow>, result: DiffUtil.DiffResult) {
        rows = newRows
        result.dispatchUpdatesTo(this)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        diffJob?.cancel()
        scope.cancel()
    }

    private class GalleryDiffCallback(
        private val old: List<MediaGalleryRow>,
        private val new: List<MediaGalleryRow>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val o = old[oldItemPosition]
            val n = new[newItemPosition]
            return when {
                o is MediaGalleryRow.HeaderRow && n is MediaGalleryRow.HeaderRow ->
                    o.epochDay == n.epochDay
                o is MediaGalleryRow.GridRow && n is MediaGalleryRow.GridRow ->
                    o.epochDay == n.epochDay && o.rowIndex == n.rowIndex &&
                        stable(o.a) == stable(n.a) &&
                        stable(o.b) == stable(n.b) &&
                        stable(o.c) == stable(n.c)
                o is MediaGalleryRow.LoadingFooter && n is MediaGalleryRow.LoadingFooter ->
                    true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            old[oldItemPosition] == new[newItemPosition]

        private fun stable(it: ChannelGalleryMediaItem?) = it?.id ?: 0L
    }

    override fun getItemId(position: Int): Long {
        return when (val r = rows[position]) {
            is MediaGalleryRow.HeaderRow ->
                10_000_000_000L + r.epochDay

            is MediaGalleryRow.GridRow -> {
                val base = 20_000_000_000L + r.epochDay * 10_000L + r.rowIndex * 100L
                base + (r.a?.id ?: 0L) + (r.b?.id ?: 0L) * 3 + (r.c?.id ?: 0L) * 7
            }

            is MediaGalleryRow.LoadingFooter ->
                30_000_000_000L
        }
    }

    override fun getItemViewType(position: Int): Int =
        when (rows[position]) {
            is MediaGalleryRow.HeaderRow -> VIEW_HEADER
            is MediaGalleryRow.GridRow -> VIEW_GRID
            is MediaGalleryRow.LoadingFooter -> VIEW_FOOTER
        }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return when (viewType) {
            VIEW_HEADER -> {
                val fl = FrameLayout(ctx)
                val tv = TextView(ctx).apply {
                    setTextColor(theme.onSurface)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val p = LayoutHelper.dp(10f)
                    setPadding(p, p, p, p)
                }
                fl.addView(tv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                HeaderVH(fl, tv)
            }

            VIEW_GRID -> {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                val slots = Array(3) { buildSlot(ctx, row) }
                GridVH(row, slots)
            }

            VIEW_FOOTER -> {
                val progress = android.widget.ProgressBar(ctx)
                val padV = LayoutHelper.dp(16f)
                val fl = FrameLayout(ctx).apply {
                    setPadding(0, padV, 0, padV)
                    addView(progress, LayoutHelper.createFrame(48, 48, Gravity.CENTER))
                }
                object : RecyclerView.ViewHolder(fl) {}
            }

            else -> throw IllegalStateException()
        }.apply {
            itemView.layoutParams =
                RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
        }
    }

    private fun buildSlot(ctx: Context, row: LinearLayout): MediaSlot {
        val root = FrameLayout(ctx).apply {
            val border = LayoutHelper.dp(1f)
            setPadding(border, border, border, border)
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.TRANSPARENT)
                    cornerRadius = LayoutHelper.dp(10f).toFloat()
                    setStroke(border, 0x664E4E4E)
                }
            setOnClickListener { v ->
                val item = v.tag as? ChannelGalleryMediaItem ?: return@setOnClickListener
                onMediaCellClicked(item)
            }
        }
        val lp = LinearLayout.LayoutParams(0, cellSizePx(), 1f)
        val margin = LayoutHelper.dp(4f)
        lp.setMargins(margin, margin, margin, margin)
        row.addView(root, lp)

        val thumb =
            BackupImageView(ctx).apply {
                setRoundRadius(LayoutHelper.dp(10f))
                setOmitEmptyPlaceholder(true)
                setAspectFill(true)
            }
        root.addView(thumb, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        val videoDimmer =
            View(ctx).apply {
                visibility = View.GONE
                setBackgroundColor(0x80000000.toInt())
            }
        root.addView(videoDimmer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        val play =
            ImageView(ctx).apply {
                visibility = View.GONE
                val d = MezonIcon.playCircleIcon.getDrawable(context).mutate()
                DrawableCompat.setTint(d, Color.WHITE)
                setImageDrawable(d)
            }
        root.addView(
            play,
            LayoutHelper.createFrame(30, 30, Gravity.CENTER)
        )

        val avPad = LayoutHelper.dp(26f)
        val avRing =
            FrameLayout(ctx).apply {
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x00000000)
                        setStroke(LayoutHelper.dp(1f), Color.parseColor("#242427"))
                    }
            }

        val av = AvatarView(ctx).apply {
            setSizeDp(24)
            setRoundRadius(12f)
        }
        avRing.addView(av, LayoutHelper.createFrame(24, 24, Gravity.CENTER))

        val anonymousAv = ImageView(ctx).apply {
            visibility = View.GONE
            val drawable = com.mezon.mobile.ui.cells.MezonIcon.anonymousAvatar.getDrawable(ctx)
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        avRing.addView(anonymousAv, LayoutHelper.createFrame(24, 24, Gravity.CENTER))

        val avLp = LayoutHelper.createFrame(26, 26, Gravity.TOP or Gravity.END)
        avLp.setMargins(0, LayoutHelper.dp(8f), LayoutHelper.dp(8f), 0)
        root.addView(avRing, avLp)

        return MediaSlot(root, thumb, videoDimmer, play, av, anonymousAv)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is MediaGalleryRow.HeaderRow -> {
                (holder as HeaderVH).title.text = row.label
            }

            is MediaGalleryRow.GridRow -> {
                val h = holder as GridVH
                bindSlot(h.slots[0], row.a)
                bindSlot(h.slots[1], row.b)
                bindSlot(h.slots[2], row.c)
            }

            is MediaGalleryRow.LoadingFooter -> Unit
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is GridVH) {
            holder.slots.forEach {
                it.thumbJob?.cancel()
                it.thumbJob = null
                it.thumb.cancelLoad()
                it.av.visibility = View.VISIBLE
                it.videoDimmer.visibility = View.GONE
                it.play.visibility = View.GONE
            }
        }
        super.onViewRecycled(holder)
    }

    private fun bindSlot(slot: MediaSlot, item: ChannelGalleryMediaItem?) {
        slot.root.tag = item
        if (item == null) {
            slot.root.visibility = View.INVISIBLE
            slot.thumbJob?.cancel()
            slot.thumbJob = null
            slot.thumb.cancelLoad()
            slot.thumb.setImageDrawable(null)
            slot.av.visibility = View.GONE
            slot.anonymousAv.visibility = View.GONE
            slot.videoDimmer.visibility = View.GONE
            slot.play.visibility = View.GONE
            return
        }
        slot.root.visibility = View.VISIBLE
        slot.av.visibility = View.VISIBLE

        val anonymousUserId = com.mezon.mobile.BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: 0L
        if (item.uploaderId == anonymousUserId) {
            slot.av.visibility = View.GONE
            slot.anonymousAv.visibility = View.VISIBLE
        } else {
            slot.av.visibility = View.VISIBLE
            slot.anonymousAv.visibility = View.GONE
            val m = members()[item.uploaderId]
            val avatarUrl =
                when {
                    isDm ->
                        m?.avatarUrl ?: ""
                    else ->
                        (m?.clanAvatar?.ifBlank { null } ?: m?.avatarUrl) ?: ""
                }
            val avatarUsername = m?.username.orEmpty()
            slot.av.setInfo(item.uploaderId, avatarUsername.ifEmpty { "_" })
            slot.av.setImageUrl(avatarUrl)
        }

        val showVideo = item.isVideo
        slot.videoDimmer.visibility = if (showVideo) View.VISIBLE else View.GONE
        slot.play.visibility = if (showVideo) View.VISIBLE else View.GONE

        val cs = cellSizePx()
        slot.thumb.setSize(cs, cs)

        slot.thumbJob?.cancel()
        slot.thumbJob = null
        if (showVideo) {
            val cached = videoThumbCache.get(item.url)
            if (cached != null) {
                slot.thumb.setImageDrawable(BitmapDrawable(slot.thumb.resources, cached))
            } else {
                slot.thumb.setImageDrawable(null)
                slot.thumbJob = scope.launch {
                    val frame = withContext(Dispatchers.IO) { extractRemoteVideoFrame(item.url, cs) }
                    if (frame != null &&
                        (slot.root.tag as? ChannelGalleryMediaItem)?.url == item.url
                    ) {
                        slot.thumb.setImageDrawable(BitmapDrawable(slot.thumb.resources, frame))
                    }
                }
            }
        } else {
            slot.thumb.setImage(galleryThumbLoadUrl(item, cs))
        }
    }

    private fun extractRemoteVideoFrame(url: String, targetPx: Int): Bitmap? {
        if (url.isBlank()) return null
        videoThumbCache.get(url)?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            if (url.startsWith("content://") || url.startsWith("file://")) {
                val ctx = hostContext() ?: return null
                retriever.setDataSource(ctx, android.net.Uri.parse(url))
            } else {
                retriever.setDataSource(url, HashMap())
            }
            val frame = retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return null
            val maxEdge = (targetPx * 2).coerceAtLeast(320)
            val scaled =
                if (frame.width <= maxEdge && frame.height <= maxEdge) {
                    frame
                } else {
                    val scale = maxEdge.toFloat() / maxOf(frame.width, frame.height)
                    val dw = (frame.width * scale).toInt().coerceAtLeast(1)
                    val dh = (frame.height * scale).toInt().coerceAtLeast(1)
                    val out = Bitmap.createScaledBitmap(frame, dw, dh, true)
                    if (out !== frame) frame.recycle()
                    out
                }
            videoThumbCache.put(url, scaled)
            scaled
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun onMediaCellClicked(tapped: ChannelGalleryMediaItem) {
        val ctx = hostContext() ?: return
        val url = tapped.url.ifBlank { return }

        val anonymousUserId = com.mezon.mobile.BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: 0L
        val orderedImages =
            galleryController.getItems(channelId)
                .sortedWith(
                    compareByDescending<ChannelGalleryMediaItem> { it.createTimeSeconds }
                        .thenByDescending { it.id }
                )
                .filter { !it.isVideo && it.url.isNotEmpty() }
                .map { mediaItem ->
                    val m = members()[mediaItem.uploaderId]
                    val resolvedName = if (mediaItem.uploaderId == anonymousUserId) {
                        ctx.getString(com.mezon.mobile.R.string.advanced_anonymous)
                    } else if (isDm) {
                        m?.displayName?.takeIf { it.isNotBlank() } ?: m?.username?.takeIf { it.isNotBlank() } ?: "User"
                    } else {
                        m?.clanNick?.takeIf { it.isNotBlank() } ?: m?.displayName?.takeIf { it.isNotBlank() } ?: m?.username?.takeIf { it.isNotBlank() } ?: "User"
                    }
                    val resolvedAvatar = if (isDm) {
                        m?.avatarUrl
                    } else {
                        m?.clanAvatar?.takeIf { it.isNotBlank() } ?: m?.avatarUrl
                    }

                    PhotoViewer.GalleryItem(
                        url = mediaItem.url,
                        senderName = resolvedName,
                        senderAvatarUrl = resolvedAvatar,
                        timestamp = mediaItem.createTimeSeconds.toLong(),
                        isVideo = mediaItem.isVideo,
                        uploaderId = mediaItem.uploaderId
                    )
                }

        when {
            tapped.isVideo ->
                VideoPlayerDialog(ctx).play(url)

            tapped.filetype.contains("gif", true) -> {
                val item = orderedImages.firstOrNull { it.url == url } ?: PhotoViewer.GalleryItem(url, "User", null, 0)
                PhotoViewer(ctx).show(item, thumbBitmap = null, preferDrawableLoader = true)
            }
            else -> {
                val idx = orderedImages.indexOfFirst { it.url == url }.takeIf { it >= 0 } ?: 0
                val item = orderedImages.getOrNull(idx) ?: PhotoViewer.GalleryItem(url, "User", null, 0)
                PhotoViewer(ctx).show(
                    item = item,
                    gallery = orderedImages,
                    index = idx,
                    thumbBitmap = null
                )
            }
        }
    }

    class HeaderVH(val container: FrameLayout, val title: TextView) : RecyclerView.ViewHolder(container)
    class GridVH(val row: LinearLayout, val slots: Array<MediaSlot>) : RecyclerView.ViewHolder(row)

    internal class MediaSlot(
        val root: FrameLayout,
        val thumb: BackupImageView,
        val videoDimmer: View,
        val play: ImageView,
        val av: AvatarView,
        val anonymousAv: ImageView
    ) {
        var thumbJob: Job? = null
    }
}

private val videoThumbCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

private fun galleryThumbLoadUrl(item: ChannelGalleryMediaItem, cellPx: Int): String {
    val u = item.url.trim()
    if (u.isEmpty()) return u
    val ft = item.filetype.lowercase(Locale.US)
    val pathLower = u.lowercase(Locale.US)
    val isGif =
        ft.contains("gif") ||
            pathLower.endsWith(".gif") ||
            pathLower.contains(".gif?")
    if (isGif) {
        return u
    }
    val isCdn = u.startsWith("https://cdn.mezon") || u.startsWith("https://cdn.komu") || u.startsWith("https://profile.mezon")
    if (!isCdn) {
        return u
    }
    return createImgproxyUrl(u, cellPx, cellPx, "fill")
}

private fun BackupImageView.cancelLoad() {
    setImageDrawable(null)
}
