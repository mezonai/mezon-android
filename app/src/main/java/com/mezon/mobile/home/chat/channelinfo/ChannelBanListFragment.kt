package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.settings.ClanRolesUiTheme
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mezon.api.BannedUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelBanListFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"

        fun newInstance(channelId: Long, channelName: String, clanId: Long): ChannelBanListFragment =
            ChannelBanListFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putString(ARG_CHANNEL_NAME, channelName)
                    putLong(ARG_CLAN_ID, clanId)
                }
            }
    }

    private var channelId = 0L
    private var channelName = ""
    private var clanId = 0L

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var userClanController: UserClanController
    private lateinit var listFrame: FrameLayout
    private lateinit var listWrap: LinearLayout
    private lateinit var emptyView: TextView
    private var loadingBar: ProgressBar? = null
    private val bannedItems = ArrayList<BannedUser>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        userClanController = entryPoint.userClanController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME).orEmpty()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId != 0L) userClanController.loadClanMembers(clanId)
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (clanId != 0L) userClanController.loadClanMembers(clanId)
        reloadBanList()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.channel_ban_list_title))
            setSubtitle(channelName)
            setBackButtonImage(R.drawable.ic_arrow_back)
            setCenterTitle(true)
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                }
            })
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        listFrame = FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
        }
        listWrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        emptyView = TextView(context).apply {
            text = getString(R.string.channel_ban_list_empty)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(themeColors.textDisabled)
            setPadding(0, LayoutHelper.dp(48f), 0, LayoutHelper.dp(48f))
            visibility = View.GONE
        }
        loadingBar = ProgressBar(context)
        listFrame.addView(listWrap, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        listFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        listFrame.addView(loadingBar, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        root.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        fragmentView = root
        return root
    }

    private fun reloadBanList() {
        if (clanId == 0L || channelId == 0L || !::listWrap.isInitialized) return
        loadingBar?.visibility = View.VISIBLE
        fragmentScope.launch {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    withContext(Dispatchers.IO) {
                        api.listBannedUsers(session.apiUrl, session.token, clanId, channelId)
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                loadingBar?.visibility = View.GONE
                if (isFinished) return@withContext
                bannedItems.clear()
                result.onSuccess { list -> bannedItems.addAll(list.bannedUsersList) }
                renderList()
            }
        }
    }

    private fun renderList() {
        listWrap.removeAllViews()
        if (bannedItems.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE
        val ctx = getContext() ?: return
        val clanMembers = userClanController.getClanMembers(clanId)
        for (user in bannedItems) {
            val userId = user.bannedId.takeIf { it != 0L } ?: user.bannerId
            if (userId == 0L) continue
            val member = clanMembers.firstOrNull { it.userId == userId }
            val displayName = member?.displayName?.ifBlank { member.username }.orEmpty().ifBlank { "#$userId" }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
                background = rounded(themeColors.channelPanelBg, 14f)
                isClickable = true
                setOnClickListener { confirmUnban(userId, displayName) }
            }
            row.addView(
                AvatarView(ctx).apply {
                    setSizeDp(40)
                    setInfo(userId, displayName)
                    setImageUrl(member?.clanAvatar?.ifBlank { member.avatarUrl }.orEmpty())
                },
                LayoutHelper.createLinear(40, 40, 0f, Gravity.CENTER_VERTICAL)
            )
            val copy = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(
                TextView(ctx).apply {
                    text = displayName
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColors.textStrong)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
            if (user.reason.isNotBlank()) {
                copy.addView(
                    TextView(ctx).apply {
                        text = user.reason
                        textSize = 13f
                        setTextColor(themeColors.textDisabled)
                    },
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 4f, 0f, 0f)
                )
            }
            row.addView(copy, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12f, 0f, 8f, 0f))
            row.addView(
                TextView(ctx).apply {
                    text = getString(R.string.channel_ban_unban)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColors.blurple)
                },
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL)
            )
            listWrap.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))
        }
    }

    private fun confirmUnban(userId: Long, displayName: String) {
        val act = getParentActivity() ?: return
        AlertDialog.Builder(act)
            .setTitle(getString(R.string.channel_ban_unban_confirm_title))
            .setMessage(getString(R.string.channel_ban_unban_confirm_message, displayName))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.channel_ban_unban)) { _, _ -> unbanUser(userId) }
            .show()
    }

    private fun unbanUser(userId: Long) {
        fragmentScope.launch {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    withContext(Dispatchers.IO) {
                        api.unbanClanUsers(session.apiUrl, session.token, clanId, channelId, listOf(userId))
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    MezonToast.show(this@ChannelBanListFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_ban_unban_success))
                    reloadBanList()
                } else {
                    MezonToast.show(this@ChannelBanListFragment, ToastOverlay.ToastType.ERROR, getString(R.string.common_something_went_wrong))
                }
            }
        }
    }

    private fun rounded(color: Int, radiusDp: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(radiusDp)
            setColor(color)
        }
}
