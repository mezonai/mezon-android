package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.text.TextUtils
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
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
import com.mezon.mobile.ui.cells.MezonIcon
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
        private const val BOT_EVENT_ACTION = "bot_event"

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
    private lateinit var emptyWrap: LinearLayout
    private lateinit var emptyTitleView: TextView
    private lateinit var emptySubtitleView: TextView
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
        val root = FrameLayout(context)

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ClanRolesUiTheme.applyPrimaryFlowRoot(column, themeColors)

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.channel_quick_action_title))
            setBackButtonImage(R.drawable.ic_arrow_back)
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                }
            })
        }
        column.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(88f))
        }
        inner.addView(buildTabs(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 16f))

        contentFrame = FrameLayout(context)
        val scroll = NestedScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        listWrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        contentFrame.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emptyWrap = buildEmptyState(context)
        contentFrame.addView(
            emptyWrap,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER),
        )

        loadingBar = ProgressBar(context)
        contentFrame.addView(loadingBar, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        inner.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        column.addView(inner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        root.addView(buildFab(context), LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END, 0f, 0f, 20f, 24f))

        fragmentView = root
        return root
    }

    private fun buildTabs(context: Context): LinearLayout {
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(4f), LayoutHelper.dp(4f), LayoutHelper.dp(4f), LayoutHelper.dp(4f))
            background = rounded(themeColors.channelPanelBg, 22f)
        }
        tabFlash = tabLabel(context, getString(R.string.channel_quick_action_flash_tab), MENU_TYPE_FLASH)
        tabQuickMenu = tabLabel(context, getString(R.string.channel_quick_action_menu_tab), MENU_TYPE_QUICK_MENU)
        tabs.addView(tabFlash, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))
        tabs.addView(tabQuickMenu, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, Gravity.CENTER_VERTICAL, 4f, 0f, 0f, 0f))
        updateTabs()
        return tabs
    }

    private fun tabLabel(context: Context, label: String, type: Int): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(8f), LayoutHelper.dp(10f), LayoutHelper.dp(8f), LayoutHelper.dp(10f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (activeMenuType != type) {
                    activeMenuType = type
                    updateTabs()
                    reloadMenus()
                }
            }
        }

    private fun updateTabs() {
        if (!::tabFlash.isInitialized) return
        listOf(tabFlash to MENU_TYPE_FLASH, tabQuickMenu to MENU_TYPE_QUICK_MENU).forEach { (view, type) ->
            val selected = activeMenuType == type
            view.setTextColor(if (selected) 0xFFFFFFFF.toInt() else themeColors.textStrong)
            view.background = rounded(if (selected) themeColors.blurple else android.graphics.Color.TRANSPARENT, 18f)
        }
    }

    private fun buildEmptyState(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(LayoutHelper.dp(24f), LayoutHelper.dp(48f), LayoutHelper.dp(24f), LayoutHelper.dp(48f))

            val iconCircle = FrameLayout(context).apply {
                background = rounded(themeColors.channelPanelBg, 48f)
            }
            val iconSize = LayoutHelper.dp(40f)
            iconCircle.addView(
                ImageView(context).apply {
                    val d = MezonIcon.quickAction.getDrawable(context).mutate()
                    d.colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
                    setImageDrawable(d)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                },
                FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER).apply {
                    val pad = LayoutHelper.dp(28f)
                    setMargins(pad, pad, pad, pad)
                },
            )
            addView(iconCircle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

            emptyTitleView = TextView(context).apply {
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.textStrong)
                gravity = Gravity.CENTER
                setPadding(0, LayoutHelper.dp(20f), 0, LayoutHelper.dp(8f))
            }
            addView(emptyTitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            emptySubtitleView = TextView(context).apply {
                textSize = 14f
                setTextColor(themeColors.textDisabled)
                gravity = Gravity.CENTER
                setLineSpacing(LayoutHelper.dpf(2f), 1f)
            }
            addView(emptySubtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
    }

    private fun updateEmptyCopy() {
        if (!::emptyTitleView.isInitialized) return
        if (activeMenuType == MENU_TYPE_FLASH) {
            emptyTitleView.text = getString(R.string.channel_quick_action_empty_flash_title)
            emptySubtitleView.text = getString(R.string.channel_quick_action_empty_flash_subtitle)
        } else {
            emptyTitleView.text = getString(R.string.channel_quick_action_empty_menu_title)
            emptySubtitleView.text = getString(R.string.channel_quick_action_empty_menu_subtitle)
        }
    }

    private fun buildFab(context: Context): View {
        val size = LayoutHelper.dp(52f)
        return FrameLayout(context).apply {
            minimumWidth = size
            minimumHeight = size
            background = rounded(0xFF000000.toInt(), 12f)
            elevation = LayoutHelper.dp(6f).toFloat()
            setOnClickListener { showCreateDialog(context) }
            addView(
                TextView(context).apply {
                    text = "+"
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
        }
    }

    private fun reloadMenus() {
        if (channelId == 0L || !::listWrap.isInitialized) return
        loadingBar?.visibility = View.VISIBLE
        listWrap.visibility = View.INVISIBLE
        emptyWrap.visibility = View.GONE
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
        if (!::listWrap.isInitialized) return
        updateEmptyCopy()
        listWrap.removeAllViews()
        val ctx = getContext() ?: return
        if (menuItems.isEmpty()) {
            listWrap.visibility = View.INVISIBLE
            emptyWrap.visibility = View.VISIBLE
            return
        }
        listWrap.visibility = View.VISIBLE
        emptyWrap.visibility = View.GONE
        for (item in menuItems) {
            listWrap.addView(
                buildMenuRow(ctx, item),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 10f),
            )
        }
    }

    private fun buildMenuRow(context: Context, item: QuickMenuAccess): View {
        val isFlash = activeMenuType == MENU_TYPE_FLASH
        val badgeLabel = if (isFlash) "/${item.menuName}" else item.menuName
        val subtitle = if (isFlash) item.actionMsg else getString(R.string.channel_quick_action_triggers_bot)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f))
            background = rounded(themeColors.channelPanelBg, 14f)
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(
            TextView(context).apply {
                text = badgeLabel
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF2E7D32.toInt())
                setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(4f), LayoutHelper.dp(10f), LayoutHelper.dp(4f))
                background = rounded(0xFFE8F5E9.toInt(), 8f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT),
        )
        textCol.addView(
            TextView(context).apply {
                text = subtitle
                textSize = 14f
                setTextColor(themeColors.textStrong)
                if (!isFlash) {
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                }
                maxLines = 4
                setPadding(0, LayoutHelper.dp(8f), 0, 0)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )
        row.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        row.addView(iconActionButton(context, MezonIcon.pencilIcon) { showEditDialog(context, item) })
        row.addView(iconActionButton(context, MezonIcon.trashIcon, tintRed = true) { confirmDelete(context, item) })
        return row
    }

    private fun iconActionButton(context: Context, icon: MezonIcon, tintRed: Boolean = false, onClick: () -> Unit): View {
        return FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(6f), LayoutHelper.dp(6f), LayoutHelper.dp(6f), LayoutHelper.dp(6f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                ImageView(context).apply {
                    val d = icon.getDrawable(context).mutate()
                    val tint = if (tintRed) themeColors.redStrong else themeColors.textStrong
                    d.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                    setImageDrawable(d)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                },
                LayoutHelper.createFrame(22, 22, Gravity.CENTER),
            )
        }
    }

    private fun confirmDelete(context: Context, item: QuickMenuAccess) {
        val act = getParentActivity() ?: return
        val commandLabel = if (activeMenuType == MENU_TYPE_FLASH) "/${item.menuName}" else item.menuName
        AlertDialog.Builder(act)
            .setTitle(getString(R.string.common_delete))
            .setMessage(getString(R.string.channel_quick_action_delete_message, commandLabel))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_delete)) { _, _ -> deleteMenu(item) }
            .show()
    }

    private fun normalizeMenuName(raw: String): String =
        raw.trim().removePrefix("/").trim()

    private fun showCreateDialog(context: Context) {
        showEditDialog(context, null)
    }

    private fun showEditDialog(context: Context, existing: QuickMenuAccess?) {
        if (activeMenuType == MENU_TYPE_FLASH) {
            showFlashDialog(context, existing)
        } else {
            showQuickMenuDialog(context, existing)
        }
    }

    private fun showFlashDialog(context: Context, existing: QuickMenuAccess?) {
        val act = getParentActivity() ?: return
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(4f), LayoutHelper.dp(8f), LayoutHelper.dp(4f), 0)
        }
        val keyField = dialogField(context, getString(R.string.channel_quick_action_flash_key_hint), existing?.menuName.orEmpty(), singleLine = true)
        val contentField = dialogField(
            context,
            getString(R.string.channel_quick_action_flash_content_hint),
            existing?.actionMsg.orEmpty(),
            singleLine = false,
        )
        body.addView(keyField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 12f))
        body.addView(contentField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val titleRes = if (existing == null) R.string.channel_quick_action_create_flash_title
        else R.string.channel_quick_action_edit_flash_title

        val builder = AlertDialog.Builder(act)
            .setTitle(getString(titleRes))
            .setView(body, LayoutHelper.WRAP_CONTENT)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(
                if (existing == null) getString(R.string.common_create) else getString(R.string.common_save),
                null,
            )

        if (existing != null) {
            builder.setNeutralButton(getString(R.string.common_delete)) { _, _ -> deleteMenu(existing) }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val key = normalizeMenuName(keyField.text.toString())
                val content = contentField.text.toString().trim()
                if (key.isEmpty() || content.isEmpty()) {
                    MezonToast.show(this@ChannelQuickActionFragment, ToastOverlay.ToastType.ERROR, getString(R.string.channel_quick_action_fields_required))
                    return@setOnClickListener
                }
                dialog.dismiss()
                if (existing == null) createMenu(key, content) else updateMenu(existing, key, content)
            }
        }
        dialog.show()
    }

    private fun showQuickMenuDialog(context: Context, existing: QuickMenuAccess?) {
        val act = getParentActivity() ?: return
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(4f), LayoutHelper.dp(8f), LayoutHelper.dp(4f), 0)
        }

        body.addView(
            TextView(context).apply {
                text = getString(R.string.channel_quick_action_menu_name_label)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.textStrong)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f),
        )
        val nameField = dialogField(context, getString(R.string.channel_quick_action_menu_name_hint), existing?.menuName.orEmpty(), singleLine = true)
        body.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 6f))
        body.addView(
            TextView(context).apply {
                text = getString(R.string.channel_quick_action_menu_name_desc)
                textSize = 13f
                setTextColor(themeColors.textDisabled)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 14f),
        )

        val infoBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(12f), LayoutHelper.dp(14f), LayoutHelper.dp(12f))
            background = rounded(0x1A5865F2.toInt(), 12f)
        }
        infoBox.addView(
            TextView(context).apply {
                text = getString(R.string.channel_quick_action_bot_event_title)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.blurple)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )
        infoBox.addView(
            TextView(context).apply {
                text = getString(R.string.channel_quick_action_bot_event_desc)
                textSize = 13f
                setTextColor(themeColors.textDisabled)
                setPadding(0, LayoutHelper.dp(6f), 0, 0)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )
        body.addView(infoBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val titleRes = if (existing == null) R.string.channel_quick_action_create_menu_title
        else R.string.channel_quick_action_edit_menu_title

        val builder = AlertDialog.Builder(act)
            .setTitle(getString(titleRes))
            .setView(body, LayoutHelper.WRAP_CONTENT)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(
                if (existing == null) getString(R.string.common_create) else getString(R.string.common_save),
                null,
            )

        if (existing != null) {
            builder.setNeutralButton(getString(R.string.common_delete)) { _, _ -> deleteMenu(existing) }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val name = normalizeMenuName(nameField.text.toString())
                if (name.isEmpty()) return@setOnClickListener
                dialog.dismiss()
                if (existing == null) createMenu(name, BOT_EVENT_ACTION) else updateMenu(existing, name, BOT_EVENT_ACTION)
            }
        }
        dialog.show()
    }

    private fun dialogField(context: Context, hint: String, value: String, singleLine: Boolean): EditText {
        return EditText(context).apply {
            setText(value)
            this.hint = hint
            textSize = 15f
            setTextColor(themeColors.textStrong)
            setHintTextColor(themeColors.textDisabled)
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(12f), LayoutHelper.dp(14f), LayoutHelper.dp(12f))
            background = rounded(themeColors.channelPanelBg, 12f)
            inputType = if (singleLine) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            if (!singleLine) {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            }
        }
    }

    private fun createMenu(name: String, actionMsg: String) {
        if (channelId == 0L || clanId == 0L) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.common_something_went_wrong))
            return
        }
        val targetChannelId = channelId
        val targetClanId = clanId
        val menuType = activeMenuType
        fragmentScope.launch {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    val item = quickMenuAccess {
                        id = MezonSnowflake.generate()
                        botId = 0L
                        channelId = targetChannelId
                        clanId = targetClanId
                        menuName = name
                        this.actionMsg = actionMsg
                        this.menuType = menuType
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
                    showApiError(result.exceptionOrNull())
                }
            }
        }
    }

    private fun updateMenu(existing: QuickMenuAccess, name: String, actionMsg: String) {
        val targetChannelId = channelId
        val targetClanId = clanId
        val menuType = activeMenuType
        fragmentScope.launch {
            val result = runCatching {
                sessionManager.withAutoRefresh { session ->
                    val item = quickMenuAccess {
                        id = existing.id
                        botId = if (existing.botId != 0L) existing.botId else 0L
                        channelId = if (existing.channelId != 0L) existing.channelId else targetChannelId
                        clanId = if (existing.clanId != 0L) existing.clanId else targetClanId
                        menuName = name
                        this.actionMsg = actionMsg
                        this.menuType = menuType
                    }
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
                    showApiError(result.exceptionOrNull())
                }
            }
        }
    }

    private fun showApiError(error: Throwable?) {
        val detail = error?.message?.takeIf { it.isNotBlank() }?.take(120)
        val msg = detail ?: getString(R.string.common_something_went_wrong)
        MezonToast.show(this@ChannelQuickActionFragment, ToastOverlay.ToastType.ERROR, msg)
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
                if (result.isSuccess) {
                    MezonToast.show(this@ChannelQuickActionFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_settings_updated))
                    reloadMenus()
                } else {
                    showApiError(result.exceptionOrNull())
                }
            }
        }
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(radiusDp)
            setColor(color)
        }
}
