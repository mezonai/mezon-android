package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ChannelGalleryController
import com.mezon.mobile.home.ChannelGalleryMediaItem
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.ui.cells.ScreenStateView
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

class MediaTabHelper(
    private val channelId: Long,
    private val clanId: Long,
    private val channelType: Int,
    private val isDm: Boolean,
    private val themeColors: ThemeColors,
    private val galleryController: ChannelGalleryController,
    private val memberResolver: MemberResolver,
    private val getString: (Int) -> String,
    private val hostContext: () -> Context?,
) : TabHelper {

    private companion object {
        private const val DBG_TAG = "MediaTabGallery"
    }

    private var cellSizePx = 100
    private var memberMap: Map<Long, ClanMember> = emptyMap()

    private lateinit var rowsAdapter: ChannelMediaGalleryAdapter
    private var recyclerView: RecyclerListView? = null
    private var screenState: ScreenStateView? = null
    private var gallerySkeleton: ChannelMediaGallerySkeletonView? = null

    private var lastOlderFetchMs = 0L

    override fun buildView(context: Context): View {
        cellSizePx = computeCellSizePx(context)

        rowsAdapter =
            ChannelMediaGalleryAdapter(
                channelId = channelId,
                theme = themeColors,
                cellSizePx = ::cellSizePx,
                isDm = isDm,
                members = { memberMap },
                galleryController = galleryController,
                hostContext = hostContext
            )

        val root = FrameLayout(context)

        val screen =
            ScreenStateView(context, themeColors).apply {
                screenState = this
                onRetry = {
                    galleryController.clearAndReload(channelId, clanId)
                    applySync(context)
                }
            }
        root.addView(screen, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        val skeleton =
            ChannelMediaGallerySkeletonView(context, themeColors) {
                computeCellSizePx(context)
            }
        gallerySkeleton = skeleton
        root.addView(skeleton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        skeleton.visibility = View.GONE

        val lv =
            RecyclerListView(context).apply {
                recyclerView = this
                layoutManager = LinearLayoutManager(context)
                clipToPadding = false
                val padH = LayoutHelper.dp(8f)
                setPadding(padH, 0, padH, LayoutHelper.dp(50f))
                adapter = rowsAdapter
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                            tryLoadOlder()
                        }
                    }

                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                        val ada = recyclerView.adapter ?: return
                        val total = ada.itemCount
                        if (total == 0) return
                        val last = lm.findLastVisibleItemPosition()
                        if (last >= total - 3) {
                            tryLoadOlder()
                        }
                    }
                })
            }

        root.addView(lv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        recyclerView?.visibility = View.GONE

        galleryController.ensureLoaded(channelId, clanId)
        applySync(context)

        return root
    }

    override fun reload() {
        val ctx = hostContext() ?: return
        galleryController.ensureLoaded(channelId, clanId)
        recyclerView?.visibility = View.GONE
        applySync(ctx)
    }

    fun syncFromApi(context: Context) {
        applySync(context)
    }

    fun onGalleryLoadFailure(context: Context) {
        gallerySkeleton?.visibility = View.GONE
        screenState?.showError(context.getString(R.string.channel_gallery_load_error))
        recyclerView?.visibility = View.GONE
    }

    private fun computeCellSizePx(context: Context): Int {
        val w = context.resources.displayMetrics.widthPixels
        return ((w - LayoutHelper.dp(42f)) / 3).coerceAtLeast(LayoutHelper.dp(48f))
    }

    private fun applySync(context: Context) {
        val screen = screenState ?: return

        val items = galleryController.getItems(channelId)
        val paging = galleryController.isPagingLoading(channelId)
        val initialDone = galleryController.isInitialLoadFinished(channelId)
        val fetching = galleryController.isFetching(channelId) || galleryController.isInitialLoading(channelId)

        val resolverIds = LinkedHashSet<Long>()
        items.forEach {
            if (it.uploaderId != 0L) resolverIds.add(it.uploaderId)
        }

        memberMap =
            if (resolverIds.isEmpty()) {
                emptyMap()
            } else {
                memberResolver.resolveMembers(resolverIds, clanId, channelId, channelType)
            }

        rowsAdapter.submit(buildDisplayRows(items, paging))

        when {
            items.isEmpty() && (!initialDone || fetching) -> {
                screen.hide()
                gallerySkeleton?.visibility = View.VISIBLE
                recyclerView?.visibility = View.GONE
            }

            items.isEmpty() && initialDone -> {
                gallerySkeleton?.visibility = View.GONE
                screen.showEmpty(getString(R.string.channel_gallery_empty), R.drawable.ic_empty_search)
                recyclerView?.visibility = View.GONE
            }

            else -> {
                gallerySkeleton?.visibility = View.GONE
                screen.hide()
                recyclerView?.visibility = View.VISIBLE
            }
        }
    }

    private fun tryLoadOlder() {
        if (!galleryController.hasMoreBefore(channelId)) {
            dbgTryLoadOlderSkip("hasMoreBefore_false")
            return
        }
        if (galleryController.isPagingLoading(channelId)) {
            dbgTryLoadOlderSkip("paging_loading")
            return
        }
        if (galleryController.isInitialLoading(channelId)) {
            dbgTryLoadOlderSkip("initial_loading")
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastOlderFetchMs < 320L) {
            dbgTryLoadOlderSkip("throttle_${now - lastOlderFetchMs}ms")
            return
        }

        if (BuildConfig.DEBUG) {
            Log.d(DBG_TAG, "tryLoadOlder fetch ch=$channelId clan=$clanId")
        }

        galleryController.fetchOlderIfNeeded(channelId, clanId)
        lastOlderFetchMs = now
    }

    private fun dbgTryLoadOlderSkip(reason: String) {
        if (BuildConfig.DEBUG) {
            Log.d(DBG_TAG, "tryLoadOlder skip ch=$channelId $reason")
        }
    }

    private fun buildDisplayRows(items: List<ChannelGalleryMediaItem>, showFooter: Boolean): List<MediaGalleryRow> {
        if (items.isEmpty()) {
            return if (showFooter) listOf(MediaGalleryRow.LoadingFooter) else emptyList()
        }

        val sorted =
            items.sortedWith(
                compareByDescending<ChannelGalleryMediaItem> { it.createTimeSeconds }
                    .thenByDescending { it.id }
            )

        val groups = LinkedHashMap<Long, ArrayList<ChannelGalleryMediaItem>>()
        sorted.forEach { attachment ->
            val stamp = dayStampFromSeconds(attachment.createTimeSeconds.toLong().coerceAtLeast(0L))
            groups.getOrPut(stamp) { ArrayList() }.add(attachment)
        }

        val out = ArrayList<MediaGalleryRow>(groups.size * 4 + 2)
        val dateFmt = DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault())

        groups.forEach { (dayStamp, dayItems) ->
            val cal = Calendar.getInstance(Locale.getDefault())
            cal.timeInMillis = dayItems.firstOrNull()?.createTimeSeconds?.toLong()?.times(1000) ?: 0L
            val label = dateFmt.format(Date(cal.timeInMillis))

            out.add(MediaGalleryRow.HeaderRow(dayStamp, label))

            var rowIdx = 0
            var idx = 0
            while (idx < dayItems.size) {
                val a = dayItems.getOrNull(idx)
                val b = dayItems.getOrNull(idx + 1)
                val c = dayItems.getOrNull(idx + 2)
                out.add(MediaGalleryRow.GridRow(dayStamp, rowIdx, a, b, c))
                rowIdx++
                idx += 3
            }
        }

        if (showFooter) out.add(MediaGalleryRow.LoadingFooter)

        return out
    }

    private fun dayStampFromSeconds(seconds: Long): Long {
        val cal = Calendar.getInstance(Locale.getDefault())
        cal.timeInMillis = seconds * 1000L
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return y * 10_000L + m * 100L + d
    }
}
