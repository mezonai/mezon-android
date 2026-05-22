package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.settings.ClanRolesUiTheme
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.MezonSnowflake
import com.mezon.mezon.api.QuickMenuAccess
import com.mezon.mezon.api.quickMenuAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelQuickActionFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"

        const val MENU_TYPE_FLASH = 1
        const val MENU_TYPE_QUICK_MENU = 2

        fun newInstance(channelId: Long, channelName: String, clanId: Long): ChannelQuickActionFragment =
            ChannelQuickActionFragment().apply {
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
    private var activeMenuType = MENU_TYPE_FLASH

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager

    private lateinit var tabFlash: TextView
    private lateinit var tabQuickMenu: TextView
    private lateinit var listWrap: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private var loadingBar: ProgressBar? = null
    private val menuItems = ArrayList<QuickMenuAccess>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME).orEmpty()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        reloadMenus()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.channel_quick_action_title))
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

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
        }
        inner.addView(buildTabs(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 12f))

        contentFrame = FrameLayout(context)
        listWrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        loadingBar = ProgressBar(context)
        contentFrame.addView(listWrap, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        contentFrame.addView(loadingBar, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        inner.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        val addBtn = TextView(context).apply {
            text = getString(R.string.channel_quick_action_add)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(themeColors.blurple)
            setPadding(0, LayoutHelper.dp(14f), 0, 0)
            setOnClickListener { showEditDialog(context, null) }
        }
        inner.addView(addBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        root.addView(inner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        fragmentView = root
        return root
    }

    private fun buildTabs(context: Context): LinearLayout {
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(4f), LayoutHelper.dp(4f), LayoutHelper.dp(4f), LayoutHelper.dp(4f))
            background = rounded(themeColors.tertiary, 16f)
        }
        tabFlash = tabLabel(context, getString(R.string.channel_quick_action_flash_tab), MENU_TYPE_FLASH)
        tabQuickMenu = tabLabel(context, getString(R.string.channel_quick_action_menu_tab), MENU_TYPE_QUICK_MENU)
        tabs.addView(tabFlash, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 3f, 0f))
        tabs.addView(tabQuickMenu, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, Gravity.CENTER_VERTICAL, 3f, 0f, 0f, 0f))
        updateTabs()
        return tabs
    }

    private fun tabLabel(context: Context, label: String, type: Int): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                activeMenuType = type
                updateTabs()
                reloadMenus()
            }
        }

    private fun updateTabs() {
        if (!::tabFlash.isInitialized) return
        listOf(tabFlash to MENU_TYPE_FLASH, tabQuickMenu to MENU_TYPE_QUICK_MENU).forEach { (view, type) ->
            val selected = activeMenuType == type
            view.setTextColor(if (selected) 0xFFFFFFFF.toInt() else themeColors.colorText)
            view.background = rounded(if (selected) themeColors.blurple else themeColors.tertiary, 14f)
        }
    }

    private fun reloadMenus() {
        if (channelId == 0L || !::listWrap.isInitialized) return
        loadingBar?.visibility = View.VISIBLE
        fragmentScope.launch {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    withContext(Dispatchers.IO) {
                        api.listQuickMenuAccess(session.apiUrl, session.token, channelId, activeMenuType)
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                loadingBar?.visibility = View.GONE
                if (isFinished) return@withContext
                menuItems.clear()
                result.onSuccess { menuItems.addAll(it.listMenusList) }
                renderMenus()
            }
        }
    }

    private fun renderMenus() {
        listWrap.removeAllViews()
        val ctx = getContext() ?: return
        if (menuItems.isEmpty()) {
            listWrap.addView(
                TextView(ctx).apply {
                    text = getString(R.string.channel_quick_action_empty)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(themeColors.textDisabled)
                    setPadding(0, LayoutHelper.dp(32f), 0, LayoutHelper.dp(32f))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
            return
        }
        for (item in menuItems) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(12f), LayoutHelper.dp(14f), LayoutHelper.dp(12f))
                background = rounded(themeColors.channelPanelBg, 14f)
                isClickable = true
                setOnClickListener { showEditDialog(ctx, item) }
            }
            row.addView(
                TextView(ctx).apply {
                    text = item.menuName
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColors.textStrong)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
            row.addView(
                TextView(ctx).apply {
                    text = item.actionMsg
                    textSize = 13f
                    setTextColor(themeColors.textDisabled)
                    maxLines = 2
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 4f, 0f, 0f)
            )
            listWrap.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))
        }
    }

    private fun showEditDialog(context: Context, existing: QuickMenuAccess?) {
        val act = getParentActivity() ?: return
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(8f), LayoutHelper.dp(4f), LayoutHelper.dp(8f), 0)
        }
        val nameField = EditText(context).apply {
            hint = getString(R.string.channel_quick_action_name_hint)
            setText(existing?.menuName.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val msgField = EditText(context).apply {
            hint = getString(
                if (activeMenuType == MENU_TYPE_QUICK_MENU) R.string.channel_quick_action_msg_hint_menu
                else R.string.channel_quick_action_msg_hint_flash
            )
            setText(
                if (existing != null && activeMenuType == MENU_TYPE_QUICK_MENU && existing.actionMsg == "bot_event") ""
                else existing?.actionMsg.orEmpty()
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        body.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 12f))
        body.addView(msgField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val title = if (existing == null) getString(R.string.channel_quick_action_create_title)
        else getString(R.string.channel_quick_action_edit_title)

        val builder = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(body, LayoutHelper.WRAP_CONTENT)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_save), null)

        if (existing != null) {
            builder.setNeutralButton(getString(R.string.common_delete)) { _, _ ->
                deleteMenu(existing)
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val name = nameField.text.toString().trim()
                val msg = msgField.text.toString().trim()
                if (name.isEmpty()) return@setOnClickListener
                val actionMsg = when {
                    activeMenuType == MENU_TYPE_QUICK_MENU && msg.isEmpty() -> "bot_event"
                    msg.isEmpty() -> return@setOnClickListener
                    else -> msg
                }
                dialog.dismiss()
                if (existing == null) createMenu(name, actionMsg) else updateMenu(existing, name, actionMsg)
            }
        }
        dialog.show()
    }

    private fun createMenu(name: String, actionMsg: String) {
        fragmentScope.launch {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    val item = quickMenuAccess {
                        id = MezonSnowflake.generate()
                        botId = 0L
                        channelId = channelId
                        clanId = clanId
                        menuName = name
                        this.actionMsg = actionMsg
                        menuType = activeMenuType
                    }
                    withContext(Dispatchers.IO) {
                        api.addQuickMenuAccess(session.apiUrl, session.token, item)
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    MezonToast.show(this@ChannelQuickActionFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_settings_updated))
                    reloadMenus()
                } else {
                    MezonToast.show(this@ChannelQuickActionFragment, ToastOverlay.ToastType.ERROR, getString(R.string.common_something_went_wrong))
                }
            }
        }
    }

    private fun updateMenu(existing: QuickMenuAccess, name: String, actionMsg: String) {
        fragmentScope.launch {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    val item = existing.toBuilder()
                        .setMenuName(name)
                        .setActionMsg(actionMsg)
                        .build()
                    withContext(Dispatchers.IO) {
                        api.updateQuickMenuAccess(session.apiUrl, session.token, item)
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    MezonToast.show(this@ChannelQuickActionFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_settings_updated))
                    reloadMenus()
                } else {
                    MezonToast.show(this@ChannelQuickActionFragment, ToastOverlay.ToastType.ERROR, getString(R.string.common_something_went_wrong))
                }
            }
        }
    }

    private fun deleteMenu(existing: QuickMenuAccess) {
        fragmentScope.launch {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    withContext(Dispatchers.IO) {
                        api.deleteQuickMenuAccess(session.apiUrl, session.token, existing)
                    }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) reloadMenus()
                else MezonToast.show(this@ChannelQuickActionFragment, ToastOverlay.ToastType.ERROR, getString(R.string.common_something_went_wrong))
            }
        }
    }

    private fun rounded(color: Int, radiusDp: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(radiusDp)
            setColor(color)
        }
}
