package com.mezon.mobile.home.chat

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
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
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mezon.api.Friend
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ChatController
import com.mezon.mobile.home.friends.FRIEND_STATE_FRIEND
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.messages.FriendPickerAdapter
import com.mezon.mobile.search.SearchController
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.theme.ThemeMode
import com.mezon.mobile.util.ShareContactData
import java.util.Locale

class ShareContactFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_IS_PRIVATE = "isPrivate"
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun newInstance(
            channelId: Long,
            clanId: Long,
            channelType: Int,
            isChannelPrivate: Boolean
        ): ShareContactFragment = ShareContactFragment().apply {
            arguments = android.os.Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putLong(ARG_CLAN_ID, clanId)
                putInt(ARG_CHANNEL_TYPE, channelType)
                putBoolean(ARG_IS_PRIVATE, isChannelPrivate)
            }
        }
    }

    private lateinit var chatController: ChatController
    private lateinit var friendController: FriendController
    private lateinit var recyclerView: RecyclerListView
    private lateinit var adapter: FriendPickerAdapter
    private lateinit var emptyView: LinearLayout
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var rootFrame: FrameLayout

    private var channelId = 0L
    private var clanId = 0L
    private var channelType = 0
    private var isChannelPrivate = false

    private var allFriends = emptyList<Friend>()
    private var filteredFriends = emptyList<Friend>()
    private var searchText = ""
    private var debouncedSearchText = ""
    private var isSending = false

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: 0
        isChannelPrivate = arguments?.getBoolean(ARG_IS_PRIVATE) ?: false
        observe(NotificationCenter.friendsLoaded) { _, _, _ ->
            if (isPaused || fragmentView == null) return@observe
            reloadFriends()
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (isPaused || fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.serverRailBg)
            if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        }
        friendController.loadFriendRelations(noCache = true)
        return true
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        chatController = entryPoint.chatController()
        friendController = entryPoint.friendController()
    }

    override fun createView(context: Context): View {
        rootFrame = FrameLayout(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.serverRailBg)
        }
        rootFrame.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        actionBar = ActionBarView(context, themeColors).apply {
            setBackClickListener { finishFragment() }
            setTitle(getString(R.string.share_contact_title))
            setCenterTitle(true)
            setTitleColor(themeColors.colorText)
            setItemsColor(themeColors.colorText)
            setBackgroundColor(themeColors.serverRailBg)
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(16), LayoutHelper.dp(20), 0)
        }
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        content.addView(buildSearchRow(context), LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, 40, bottomMargin = 18f
        ))

        val listFrame = FrameLayout(context)
        content.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f, topMargin = 12f))

        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            itemAnimator = null
        }
        adapter = FriendPickerAdapter(
            context = context,
            themeColors = themeColors,
            selectMode = false,
            onFriendClick = { onFriendSelected(it) },
            onSelectionChanged = {}
        )
        recyclerView.adapter = adapter
        listFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emptyView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            val label = TextView(context).apply {
                text = getString(R.string.no_contacts_found)
                setTextColor(themeColors.onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
            }
            addView(label, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                gravity = Gravity.CENTER_HORIZONTAL, topMargin = 20f
            ))
        }
        listFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingOverlay = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            addView(ProgressBar(context), LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER
            ))
        }
        rootFrame.addView(loadingOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        reloadFriends()
        fragmentView = rootFrame
        return rootFrame
    }

    override fun onFragmentDestroy() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        searchRunnable = null
        super.onFragmentDestroy()
    }

    private fun buildSearchRow(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(6), 0, LayoutHelper.dp(6), 0)
            background = roundedBg(themeColors.secondaryLight, 40f)
        }
        val searchIcon = ImageView(context).apply {
            val d = MezonIcon.searchIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        row.addView(searchIcon, LayoutHelper.createLinear(18, 18, gravity = Gravity.CENTER_VERTICAL, rightMargin = 6f))

        val input = EditText(context).apply {
            hint = getString(R.string.common_search_placeholder)
            setHintTextColor(rnTextDisabled())
            setTextColor(themeColors.textStrong)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = null
            isSingleLine = true
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchText = s?.toString().orEmpty()
                    scheduleSearchDebounce()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        row.addView(input, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))
        return row
    }

    private fun scheduleSearchDebounce() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        searchRunnable = Runnable {
            debouncedSearchText = searchText
            applyFilter()
        }
        searchHandler.postDelayed(searchRunnable!!, SEARCH_DEBOUNCE_MS)
    }

    private fun onFriendSelected(friend: Friend) {
        if (isSending || channelId == 0L) return
        isSending = true
        loadingOverlay.visibility = View.VISIBLE
        val data = ShareContactData(
            userId = friend.user.id,
            username = friend.user.username,
            displayName = friend.user.displayName.ifBlank { friend.user.username },
            avatarUrl = friend.user.avatarUrl
        )
        chatController.sendShareContact(channelId, clanId, channelType, isChannelPrivate, data)
        MezonToast.show(
            this,
            ToastOverlay.ToastType.INFO,
            getString(R.string.contact_shared_success)
        )
        finishFragment()
    }

    private fun reloadFriends() {
        val myId = chatController.getCurrentUserId()
        allFriends = friendController.friends.value
            .filter { it.state == FRIEND_STATE_FRIEND && it.user.id != myId }
            .sortedBy { f ->
                f.user.displayName.ifBlank { f.user.username }.lowercase(Locale.getDefault())
            }
        applyFilter()
    }

    private fun applyFilter() {
        if (!::adapter.isInitialized) return
        val query = normalize(debouncedSearchText)
        filteredFriends = if (query.isEmpty()) {
            allFriends
        } else {
            allFriends.filter {
                normalize(it.user.username).contains(query) ||
                    normalize(it.user.displayName).contains(query)
            }
        }
        adapter.submitFriends(filteredFriends, query.isNotEmpty())
        val empty = filteredFriends.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun normalize(value: String): String =
        SearchController.removeDiacritics(value.trim().lowercase(Locale.getDefault()))

    private fun rnTextDisabled(): Int = when (themeColors.resolvedMode) {
        ThemeMode.LIGHT -> 0xFF606065.toInt()
        ThemeMode.ABYSS -> 0xFFBDB4DC.toInt()
        else -> 0xFF7B7B83.toInt()
    }

    private fun roundedBg(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = LayoutHelper.dpf(radiusDp)
        }
}
