package com.mezon.mobile.home.chat.channelinfo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ChannelFilesController
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.ui.cells.MezonIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilesTabHelper(
    private val channelId: Long,
    private val clanId: Long,
    private val channelType: Int,
    private val themeColors: ThemeColors,
    private val channelFilesController: ChannelFilesController,
    private val memberResolver: MemberResolver,
    private val hostActivity: Activity,
    private val isVietnameseLocale: Boolean,
    private val getString: (Int) -> String,
    private val getStringArg: (Int, String) -> String
) : TabHelper {

    private var adapter: ChannelFilesAdapter? = null
    private var recyclerView: RecyclerListView? = null
    private var emptyView: View? = null
    private var loadingView: ProgressBar? = null
    private var searchText: String = ""
    private val debounce = Handler(Looper.getMainLooper())
    private var debounceRun: Runnable? = null
    private val anonymousUserId: Long = BuildConfig.MEZON_ANONYMOUS_USER_ID.toLongOrNull() ?: 0L
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val rowResolver = object : FilesTabRowResolver {
        override fun resolveSharerName(item: ChannelDocumentItem): String {
            if (item.uploader == anonymousUserId) return "Anonymous"
            val m = memberResolver.resolveMember(item.uploader, clanId, channelId, channelType)
            return m?.username.orEmpty()
        }

        override fun formatTime(createTimeSeconds: Int): String {
            if (createTimeSeconds <= 0) return ""
            return timeFormat.format(Date(createTimeSeconds.toLong() * 1000L))
        }

        override fun formatSharedByLine(displayName: String): String {
            return getStringArg(R.string.channel_files_shared_by, displayName.ifBlank { " " }.trim())
        }

        override fun openUrl(url: String) {
            try {
                hostActivity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
            }
        }
    }

    override fun buildView(context: Context): View {
        val root = FrameLayout(context)

        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = LayoutHelper.dp(10f)
            minimumHeight = LayoutHelper.dp(40f)
            setPadding(pad, 0, pad, 0)
            background = GradientDrawable().apply {
                setColor(themeColors.surfaceVariant)
                cornerRadius = LayoutHelper.dpf(10f)
            }
        }
        val searchIcon = ImageView(context).apply {
            val d = MezonIcon.magnifyingIcon.getDrawable(context).mutate()
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        searchRow.addView(searchIcon, LayoutHelper.createLinear(20, 20, 0f, Gravity.CENTER_VERTICAL))
        EditText(context).apply {
            hint = getString(R.string.channel_files_search_placeholder)
            setHintTextColor(themeColors.textDisabled)
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setBackgroundColor(0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val txt = s?.toString().orEmpty()
                    debounceRun?.let { debounce.removeCallbacks(it) }
                    val r = Runnable { applySearchAndDocs(txt) }
                    debounceRun = r
                    debounce.postDelayed(r, 500)
                }
            })
            searchRow.addView(this, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 8f, 0f, 2f, 0f))
        }

        root.addView(searchRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 10f, 8f, 10f, 0f))

        adapter = ChannelFilesAdapter(themeColors, rowResolver)
        val ad = adapter!!
        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ad
            clipToPadding = false
            setPadding(LayoutHelper.dp(10f), 0, LayoutHelper.dp(10f), LayoutHelper.dp(6f))
        }
        root.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0f, 56f, 0f, 0f))

        emptyView = buildEmptyView(context)
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0f, 56f, 0f, 0f))

        loadingView = ProgressBar(context).apply { visibility = View.VISIBLE }
        root.addView(loadingView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        channelFilesController.loadChannelFiles(channelId, clanId)
        refreshListUi()
        return root
    }

    private fun applySearchAndDocs(query: String) {
        searchText = query
        refreshListUi()
    }

    override fun reload() {
        refreshListUi()
    }

    private fun refreshListUi() {
        val docs = channelFilesController.getDocuments(channelId)
        val rows = buildChannelFileRows(docs, searchText, isVietnameseLocale)
        adapter?.setRows(rows)
        val fetching = channelFilesController.isFetching(channelId)
        loadingView?.visibility = if (fetching) View.VISIBLE else View.GONE
        if (rows.isNotEmpty()) {
            emptyView?.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
            return
        }
        recyclerView?.visibility = View.GONE
        emptyView?.visibility = if (!fetching) View.VISIBLE else View.GONE
    }

    private fun buildEmptyView(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, LayoutHelper.dp(60f), 0, 0)
        }
        val icon = ImageView(context).apply {
            setImageDrawable(MezonIcon.emptySearchIcon.getDrawable(context))
        }
        container.addView(icon, LayoutHelper.createLinear(100, 100, 0f, Gravity.CENTER_HORIZONTAL))
        val text = TextView(context).apply {
            text = getString(R.string.channel_files_media_empty_description)
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(10f), LayoutHelper.dp(16f), 0)
        }
        container.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL))
        return container
    }

    fun onRemoteChannelFiles(forChannelId: Long) {
        if (forChannelId != channelId) return
        refreshListUi()
    }
}
