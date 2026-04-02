package com.mezon.mobile.home.sharing

import android.content.Context
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.chat.AttachmentPickerItem
import com.mezon.mobile.home.messages.DialogCell
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.home.messages.DmListAdapter
import com.mezon.mobile.network.CHANNEL_TYPE_DM

class SharingFragment(
    private val sharedUris: List<Uri>,
    private val sharedText: String?,
    private val sharedMimeType: String?
) : BaseFragment() {

    private lateinit var chatController: ChatController
    private lateinit var dialogsController: DialogsController

    private lateinit var recyclerView: RecyclerListView
    private lateinit var adapter: DmListAdapter
    private lateinit var searchEditText: EditText
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var emptyView: TextView

    private val allDialogs = ArrayList<DirectMessage>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        chatController = entryPoint.chatController()
        dialogsController = entryPoint.dialogsController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            if (fragmentView == null) return@observe
            refreshDialogs()
        }
        return true
    }

    override fun createView(context: Context): View {
        val content = buildContent(context)
        return wrapWithActionBar(getString(R.string.sharing_title), content)
    }

    private fun buildContent(context: Context): View {
        val root = FrameLayout(context)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        val searchBar = buildSearchBar(context)
        column.addView(searchBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val listFrame = FrameLayout(context)

        emptyView = TextView(context).apply {
            text = getString(R.string.sharing_no_conversations)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        listFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
        }
        recyclerView.setEmptyView(emptyView)
        recyclerView.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
            if (view is DialogCell) {
                val dm = view.directMessage ?: return@OnItemClickListener
                onDialogSelected(dm)
            }
        })
        listFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        column.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingOverlay = FrameLayout(context).apply {
            setBackgroundColor(0x80000000.toInt())
            visibility = View.GONE
        }
        val loadingColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val spinner = ProgressBar(context)
        loadingColumn.addView(spinner, LayoutHelper.createLinear(48, 48))
        statusText = TextView(context).apply {
            text = getString(R.string.sharing_sending)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(8), 0, 0)
        }
        loadingColumn.addView(statusText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        loadingOverlay.addView(loadingColumn, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        root.addView(loadingOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        adapter = DmListAdapter(themeColors)
        recyclerView.adapter = adapter

        allDialogs.clear()
        allDialogs.addAll(dialogsController.getDialogs())
        adapter.setData(allDialogs)

        return root
    }

    private fun buildSearchBar(context: Context): View {
        val container = FrameLayout(context).apply {
            setBackgroundColor(themeColors.surface)
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(12), LayoutHelper.dp(8))
        }
        searchEditText = EditText(context).apply {
            hint = getString(R.string.sharing_search_placeholder)
            setHintTextColor(themeColors.onSurfaceVariant)
            setTextColor(themeColors.onSurface)
            setBackgroundColor(themeColors.tertiary)
            textSize = 14f
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(12), LayoutHelper.dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterDialogs(s?.toString() ?: "")
                }
            })
        }
        container.addView(
            searchEditText,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        return container
    }

    private fun filterDialogs(query: String) {
        if (query.isBlank()) {
            adapter.setData(allDialogs)
            return
        }
        val lower = query.lowercase()
        val filtered = allDialogs.filter { dm ->
            dm.displayName.lowercase().contains(lower) || dm.label.lowercase().contains(lower)
        }
        adapter.setData(filtered)
    }

    private fun refreshDialogs() {
        allDialogs.clear()
        allDialogs.addAll(dialogsController.getDialogs())
        val query = searchEditText.text?.toString() ?: ""
        filterDialogs(query)
    }

    private fun onDialogSelected(dm: DirectMessage) {
        AndroidUtilities.hideKeyboard(searchEditText)
        showSending()

        val contentResolver = getParentActivity()?.contentResolver ?: run {
            hideSending()
            finishFragment()
            return
        }

        val isDmPrivate = dm.type == CHANNEL_TYPE_DM
        if (sharedUris.isNotEmpty()) {
            val attachments = sharedUris.mapIndexed { index, uri ->
                val mimeType = sharedMimeType ?: contentResolver.getType(uri) ?: "application/octet-stream"
                val filename = resolveFilename(contentResolver, uri, index, mimeType)
                AttachmentPickerItem(
                    id = index.toLong(),
                    uri = uri,
                    path = uri.toString(),
                    filename = filename,
                    mimeType = mimeType,
                    width = 0,
                    height = 0,
                    size = resolveFileSize(contentResolver, uri),
                    duration = 0,
                    isVideo = mimeType.startsWith("video/")
                )
            }
            chatController.sendMessageWithAttachments(
                channelId = dm.channelId,
                clanId = 0L,
                channelType = dm.type,
                isChannelPrivate = isDmPrivate,
                text = sharedText ?: "",
                attachments = attachments,
                contentResolver = contentResolver
            )
        } else if (!sharedText.isNullOrBlank()) {
            chatController.sendMessage(
                channelId = dm.channelId,
                clanId = 0L,
                channelType = dm.type,
                isChannelPrivate = isDmPrivate,
                text = sharedText
            )
        }

        AndroidUtilities.runOnUIThread({
            hideSending()
            finishFragment()
        }, 800)
    }

    private fun showSending() {
        loadingOverlay.visibility = View.VISIBLE
        statusText.text = getString(R.string.sharing_sending)
    }

    private fun hideSending() {
        loadingOverlay.visibility = View.GONE
    }

    private fun resolveFilename(
        contentResolver: android.content.ContentResolver,
        uri: Uri,
        index: Int,
        mimeType: String
    ): String {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) it.getString(nameIndex) else null
                } else null
            } ?: "file_${index}_${System.currentTimeMillis()}.${mimeType.substringAfterLast('/')}"
        } catch (e: Exception) {
            "file_${index}_${System.currentTimeMillis()}.${mimeType.substringAfterLast('/')}"
        }
    }

    private fun resolveFileSize(contentResolver: android.content.ContentResolver, uri: Uri): Long {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0) it.getLong(sizeIndex) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
