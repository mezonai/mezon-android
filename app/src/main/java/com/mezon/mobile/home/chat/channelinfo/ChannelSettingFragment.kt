package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.CHANNEL_TYPE_APP
import com.mezon.mobile.home.clans.CHANNEL_TYPE_STREAMING
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.clans.settings.ClanSettingsPermissionState
import com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers
import com.mezon.mobile.home.clans.settings.WebhooksListFragment
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.CreateChannelNameValidator
import com.mezon.mobile.ui.MezonToast
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port of RN `ChannelSetting.tsx` (see `app/src/main/readme.md`): edit name/topic, save, delete/leave, menus.
 */
class ChannelSettingFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CLAN_ID = "clanId"

        fun newInstance(channelId: Long, clanId: Long): ChannelSettingFragment =
            ChannelSettingFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_CLAN_ID, clanId)
                }
            }
    }

    private var routeChannelId = 0L
    private var clanId = 0L

    private lateinit var channelController: ChannelController
    private lateinit var clansController: ClansController
    private lateinit var userController: UserController
    private lateinit var userClanController: UserClanController
    private lateinit var roleController: RoleController
    private lateinit var mezonApi: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var ioDispatcher: CoroutineDispatcher

    private lateinit var saveButtonText: TextView
    private lateinit var nameCell: InputCell
    private var topicCell: InputCell? = null
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var dynamicMenuHost: LinearLayout

    private var originName = ""
    private var originTopic = ""
    private var resolvedCreatorId = 0L
    private var systemMessageChannelId = 0L
    private var perm: ClanSettingsPermissionState = ClanSettingsPermissionState(false, false, false)

    private var saving = false
    private var isCheckDuplicateName = false

    override fun onInject(entryPoint: FragmentEntryPoint) {
        channelController = entryPoint.channelController()
        clansController = entryPoint.clansController()
        userController = entryPoint.userController()
        userClanController = entryPoint.userClanController()
        roleController = entryPoint.roleController()
        mezonApi = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        ioDispatcher = entryPoint.ioDispatcher()
    }

    override fun onFragmentCreate(): Boolean {
        routeChannelId = arguments?.getLong(ARG_CHANNEL_ID, 0L) ?: 0L
        clanId = arguments?.getLong(ARG_CLAN_ID, 0L) ?: 0L
        return super.onFragmentCreate()
    }

    override fun createView(context: Context): View {
        saveButtonText = TextView(context).apply {
            text = getString(R.string.common_save)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(20), 0, LayoutHelper.dp(20), 0)
            setOnClickListener { saveIfPossible() }
        }

        actionBar = ActionBarView(context, themeColors).apply {
            occupyStatusBar = false
            setBackClickListener { finishFragment() }
            setTitle("")
            createMenu().addItem(1, "").also { cell ->
                cell.addView(
                    saveButtonText,
                    LayoutHelper.createFrame(
                        LayoutHelper.WRAP_CONTENT,
                        LayoutHelper.MATCH_PARENT,
                        Gravity.CENTER_VERTICAL,
                        0f, 3f, 0f, 0f
                    )
                )
            }
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        1 -> saveIfPossible()
                    }
                }
            })
        }

        val entity = currentEntity()
        if (entity == null) {
            val pad = LayoutHelper.dp(20)
            val err = TextView(context).apply {
                text = getString(R.string.common_something_went_wrong)
                setTextColor(themeColors.error)
                setPadding(pad, pad, pad, pad)
            }
            fragmentView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                addView(err)
            }
            return fragmentView!!
        }

        originName = entity.channelLabel
        originTopic = entity.topic

        val pad = LayoutHelper.dp(20)
        nameCell = InputCell(context, themeColors).apply {
            setMaxCharacter(64)
            val isCh = isChannelEntity(entity)
            setLabel(
                getString(
                    if (isCh) R.string.channel_creator_channel_name_title
                    else R.string.channel_settings_thread_name_title
                ),
                false,
                false
            )
            setHint(getString(R.string.channel_creator_channel_name_placeholder))
            setText(originName)
            onTextChanged = {
                refreshSaveUi()
                applyNameValidationUi(entity)
            }
            editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val formBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        if (isChannelEntity(entity) && entity.type != CHANNEL_TYPE_APP) {
            topicCell = InputCell(context, themeColors).apply {
                setLabel(getString(R.string.channel_settings_topic_title), false, false)
                setTextarea(true, 4096)
                setText(originTopic)
                onTextChanged = { refreshSaveUi() }
            }
            formBlock.addView(
                topicCell,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(12)
                }
            )
        }

        dynamicMenuHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(formBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(dynamicMenuHost, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        loadingOverlay = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(0x40000000)
            isClickable = true
            addView(
                ProgressBar(context).apply { isIndeterminate = true },
                FrameLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48), Gravity.CENTER)
            )
        }

        val bodyRoot = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
            addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
            addView(loadingOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(bodyRoot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        }

        fragmentView = root
        refreshActionBarTitle(entity)
        renderMenus(entity)
        refreshSaveUi()
        applyNameValidationUi(entity)
        loadSupplementalState(entity)
        return root
    }

    private fun currentEntity(): ClanChannelEntity? =
        channelController.findChannelById(routeChannelId, clanId)
            ?: channelController.findChannelById(routeChannelId)

    private fun isThreadEntity(e: ClanChannelEntity): Boolean =
        e.type == CHANNEL_TYPE_THREAD || e.parentId != 0L

    private fun isChannelEntity(e: ClanChannelEntity): Boolean = !isThreadEntity(e)

    private fun draftName(): String = nameCell.getText()
    private fun draftTopic(): String = topicCell?.getText().orEmpty()

    private fun isNotChanged(): Boolean =
        draftName() == originName && draftTopic() == originTopic

    private fun refreshSaveUi() {
        val enabled = !isNotChanged()
        saveButtonText.isEnabled = enabled
        saveButtonText.setTextColor(
            if (enabled) themeColors.primary else themeColors.onSurfaceVariant
        )
        saveButtonText.alpha = if (enabled) 1f else 0.5f
    }

    private fun refreshActionBarTitle(e: ClanChannelEntity) {
        (actionBar as? ActionBarView)?.setTitle(
            getString(
                if (isChannelEntity(e)) R.string.channel_settings_title_channel
                else R.string.channel_settings_title_thread
            )
        )
        (actionBar as? ActionBarView)?.setCenterTitle(true)
    }

    private fun applyNameValidationUi(entity: ClanChannelEntity) {
        val trimmed = draftName().trim()
        val isCh = isChannelEntity(entity)
        isCheckDuplicateName = channelController.getChannels(clanId).any {
            it.channelId != routeChannelId && it.channelLabel.trim() == trimmed
        }
        val isValid = CreateChannelNameValidator.isValid(trimmed)
        nameCell.setError(
            when {
                isCheckDuplicateName ->
                    getString(
                        if (isCh) R.string.channel_settings_name_duplicate_channel
                        else R.string.channel_settings_name_duplicate_thread
                    )
                !isValid && trimmed.isNotEmpty() ->
                    getString(
                        if (isCh) R.string.channel_settings_name_invalid_channel
                        else R.string.channel_settings_name_invalid_thread
                    )
                else -> null
            }
        )
    }

    private fun renderMenus(e: ClanChannelEntity) {
        if (!::dynamicMenuHost.isInitialized) return
        dynamicMenuHost.removeAllViews()
        val ctx = dynamicMenuHost.context
        val isCh = isChannelEntity(e)
        val t = e.type
        val canManage = perm.hasAdminPermission || perm.isClanOwner || perm.hasManageClanPermission
        val isAdmin = perm.hasAdminPermission
        val clanOwner = perm.isClanOwner

        val topRows = mutableListOf<View>()
        if (isCh && t != CHANNEL_TYPE_APP) {
            topRows.add(
                menuRow(ctx, MezonIcon.clipboardIcon, getString(R.string.channel_settings_menu_change_category)) {
                    toastComingSoon()
                }
            )
        }
        if (isCh && systemMessageChannelId != e.channelId &&
            t != CHANNEL_TYPE_APP && t != CHANNEL_TYPE_STREAMING && t != CHANNEL_TYPE_VOICE
        ) {
            topRows.add(
                ClanSettingsUiHelpers.buildMezonChevronSubtitleRow(
                    ctx,
                    themeColors,
                    MezonIcon.shieldUserIcon,
                    getString(R.string.channel_settings_menu_channel_permission),
                    getString(R.string.channel_settings_menu_channel_permission_desc)
                ) {
                    if (routeChannelId != 0L && clanId != 0L) {
                        presentFragment(ChannelPermissionFragment.newInstance(routeChannelId, clanId))
                    } else {
                        toastComingSoon()
                    }
                }
            )
        }
        if (isCh && t != CHANNEL_TYPE_APP && t != CHANNEL_TYPE_STREAMING && t != CHANNEL_TYPE_VOICE) {
            topRows.add(
                menuRow(ctx, MezonIcon.shopSparkleIcon, getString(R.string.channel_settings_menu_quick_action)) {
                    toastComingSoon()
                }
            )
        }
        if (isCh && e.isPrivate && t != CHANNEL_TYPE_APP) {
            topRows.add(
                menuRow(ctx, MezonIcon.userPlusIcon, getString(R.string.channel_settings_menu_add_members)) {
                    toastComingSoon()
                }
            )
        }
        if (isCh && t == CHANNEL_TYPE_STREAMING) {
            topRows.add(
                menuRow(ctx, MezonIcon.channelStream, getString(R.string.channel_settings_menu_stream_banner)) {
                    toastComingSoon()
                }
            )
        }
        if (isAdmin) {
            topRows.add(
                menuRow(ctx, MezonIcon.circleXIcon, getString(R.string.channel_settings_menu_ban_list)) {
                    toastComingSoon()
                }
            )
        }

        if (topRows.isNotEmpty()) {
            val topPad = if (t == CHANNEL_TYPE_STREAMING || t == CHANNEL_TYPE_VOICE) 0 else LayoutHelper.dp(18)
            dynamicMenuHost.addView(
                ClanSettingsUiHelpers.buildMezonSection(ctx, themeColors, null, topRows),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = topPad
                }
            )
        }

        val bottomRows = mutableListOf<View>()
        if (t != CHANNEL_TYPE_APP && t != CHANNEL_TYPE_STREAMING && t != CHANNEL_TYPE_VOICE) {
            bottomRows.add(
                menuRow(ctx, MezonIcon.webhookIcon, getString(R.string.channel_settings_menu_webhook)) {
                    if (clanId != 0L && routeChannelId != 0L) {
                        presentFragment(
                            WebhooksListFragment.newInstance(clanId, isClanScope = false, fixedHookChannelId = routeChannelId)
                        )
                    } else {
                        toastComingSoon()
                    }
                }
            )
        }

        val showDelete =
            (resolvedCreatorId != 0L && resolvedCreatorId == userController.userId && canManage) ||
                isAdmin || clanOwner
        if (showDelete) {
            bottomRows.add(
                ClanSettingsUiHelpers.buildMezonChevronRow(
                    ctx,
                    themeColors,
                    MezonIcon.trashIcon,
                    getString(
                        if (isCh) R.string.channel_settings_menu_delete_channel
                        else R.string.channel_settings_menu_delete_thread
                    ),
                    themeColors.redStrong
                ) { confirmDelete(e) }
            )
        }

        val showLeave = isThreadEntity(e) && resolvedCreatorId != 0L &&
            resolvedCreatorId != userController.userId && t != CHANNEL_TYPE_APP
        if (showLeave) {
            bottomRows.add(
                ClanSettingsUiHelpers.buildMezonChevronRow(
                    ctx,
                    themeColors,
                    MezonIcon.doorExitIcon,
                    getString(R.string.channel_settings_menu_leave_thread),
                    themeColors.redStrong
                ) { confirmLeaveThread(e) }
            )
        }

        if (bottomRows.isNotEmpty()) {
            dynamicMenuHost.addView(
                ClanSettingsUiHelpers.buildMezonSection(ctx, themeColors, null, bottomRows),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(18)
                }
            )
        }
    }

    private fun menuRow(ctx: Context, icon: MezonIcon, title: String, onPress: () -> Unit): LinearLayout =
        ClanSettingsUiHelpers.buildMezonChevronRow(ctx, themeColors, icon, title, null) { onPress() }

    private fun toastComingSoon() {
        MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.feature_coming_soon))
    }

    private fun loadSupplementalState(entity: ClanChannelEntity) {
        val cid = clanId
        if (cid == 0L) return
        userClanController.loadClanMembers(cid)
        roleController.loadRolesForClanThen(cid, force = false, Runnable {
            val clan = clansController.clans.value.firstOrNull { it.clanId == cid }
            perm = ClanSettingsPermissionState.evaluateForClanSettings(
                userController,
                cid,
                userClanController.getClanMembers(cid),
                roleController.getRoles(cid),
                clan?.creatorId ?: 0L,
            )
            fragmentView?.post {
                currentEntity()?.let { renderMenus(it) }
            }
        })

        fragmentScope.launch(ioDispatcher) {
            runCatching {
                resolvedCreatorId = channelController.fetchCreatorId(cid, routeChannelId)
            }
            runCatching {
                sessionManager.withAutoRefresh { session ->
                    mezonApi.getSystemMessageForClan(session.apiUrl, session.token, cid)
                }.let { sys ->
                    systemMessageChannelId = sys.channelId
                }
            }
            withContext(Dispatchers.Main) {
                currentEntity()?.let { renderMenus(it) }
            }
        }
    }

    private fun saveIfPossible() {
        val entity = currentEntity() ?: return
        if (saving || isNotChanged()) return
        val trimmed = draftName().trim()
        isCheckDuplicateName = channelController.getChannels(clanId).any {
            it.channelId != routeChannelId && it.channelLabel.trim() == trimmed
        }
        if (isCheckDuplicateName || !CreateChannelNameValidator.isValid(trimmed)) {
            applyNameValidationUi(entity)
            return
        }
        fragmentScope.launch(Dispatchers.Main) {
            saving = true
            loadingOverlay.visibility = View.VISIBLE
            val err = runCatching {
                withContext(ioDispatcher) {
                    channelController.updateChannelFromSettings(entity, draftName(), draftTopic())
                }
            }.exceptionOrNull()
            saving = false
            loadingOverlay.visibility = View.GONE
            if (err != null) {
                MezonToast.show(this@ChannelSettingFragment, ToastOverlay.ToastType.ERROR, err.message ?: "")
                return@launch
            }
            originName = draftName()
            originTopic = draftTopic()
            refreshSaveUi()
            MezonToast.show(
                this@ChannelSettingFragment,
                ToastOverlay.ToastType.SUCCESS,
                getString(R.string.channel_settings_updated)
            )
            finishFragment()
        }
    }

    private fun confirmDelete(e: ClanChannelEntity) {
        val ctx = getContext() ?: return
        val title = getString(
            if (isChannelEntity(e)) R.string.channel_settings_delete_confirm_channel_title
            else R.string.channel_settings_delete_confirm_thread_title
        )
        val msg = getString(R.string.channel_settings_delete_confirm_message, e.channelLabel)
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.common_delete) { _, _ -> performDelete(e) }
            .show()
    }

    private fun confirmLeaveThread(e: ClanChannelEntity) {
        val ctx = getContext() ?: return
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.channel_settings_leave_confirm_title))
            .setMessage(getString(R.string.channel_settings_leave_confirm_message, e.channelLabel))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.channel_settings_menu_leave_thread) { _, _ -> performLeaveThread(e) }
            .show()
    }

    private fun performDelete(e: ClanChannelEntity) {
        fragmentScope.launch(Dispatchers.Main) {
            if (!::loadingOverlay.isInitialized) return@launch
            loadingOverlay.visibility = View.VISIBLE
            if (e.channelId == systemMessageChannelId) {
                loadingOverlay.visibility = View.GONE
                MezonToast.show(
                    this@ChannelSettingFragment,
                    ToastOverlay.ToastType.ERROR,
                    getString(R.string.channel_settings_delete_system_channel)
                )
                return@launch
            }
            val err = runCatching {
                withContext(ioDispatcher) {
                    channelController.deleteChannelRemote(e.clanId, e.channelId)
                }
            }.exceptionOrNull()
            loadingOverlay.visibility = View.GONE
            if (err != null) {
                MezonToast.show(
                    this@ChannelSettingFragment,
                    ToastOverlay.ToastType.ERROR,
                    getString(R.string.channel_settings_delete_failed, err.message ?: "")
                )
                return@launch
            }
            dismissDeepStackToClans()
            notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
            if (isThreadEntity(e) && e.parentId != 0L) {
                val parent = channelController.findChannelById(e.parentId, e.clanId)
                val act = getParentActivity() as? MainActivity
                if (parent != null && act != null) {
                    act.openChat(
                        parent.channelId,
                        parent.channelLabel,
                        e.clanId,
                        parent.type
                    )
                }
            }
        }
    }

    private fun performLeaveThread(e: ClanChannelEntity) {
        fragmentScope.launch(Dispatchers.Main) {
            if (!::loadingOverlay.isInitialized) return@launch
            loadingOverlay.visibility = View.VISIBLE
            val err = runCatching {
                withContext(ioDispatcher) {
                    channelController.leaveThreadRemote(e.clanId, e.channelId)
                }
            }.exceptionOrNull()
            loadingOverlay.visibility = View.GONE
            if (err != null) {
                MezonToast.show(
                    this@ChannelSettingFragment,
                    ToastOverlay.ToastType.ERROR,
                    getString(R.string.channel_settings_leave_failed, err.message ?: "")
                )
            } else {
                MezonToast.show(
                    this@ChannelSettingFragment,
                    ToastOverlay.ToastType.SUCCESS,
                    getString(R.string.channel_settings_updated)
                )
            }
        }
    }

    private fun dismissDeepStackToClans() {
        val layout = parentLayout ?: run {
            finishFragment()
            return
        }
        while (true) {
            val stack = layout.getFragmentStack()
            if (stack.isEmpty()) break
            val clanIdx = stack.indexOfLast { it is com.mezon.mobile.home.clans.ClansFragment }
            if (clanIdx < 0) {
                finishFragment()
                return
            }
            if (stack.lastIndex <= clanIdx) return
            layout.removeFragmentFromStack(stack.last())
        }
    }
}
