package com.mezon.mobile.home.clans.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClanEntity
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.TextCheckCell
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.CLAN_OVERVIEW_NAME_MAX_LENGTH
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.util.isClanNameValid
import com.mezon.mezon.api.SystemMessage
import com.mezon.mezon.api.systemMessageRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object ClanOverviewNotif {
    const val ALL = 0
    const val MENTION = 1
    const val NOTHING = 2
}

class ClanOverviewSettingFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val REQ_PICK_BANNER = 3101

        fun newInstance(clanId: Long): ClanOverviewSettingFragment =
            ClanOverviewSettingFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher

    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController
    private lateinit var userClanController: UserClanController
    private lateinit var roleController: RoleController
    private lateinit var userController: UserController
    private lateinit var permissionPolicy: PermissionPolicy

    private lateinit var scrollContent: LinearLayout
    private lateinit var nameInput: InputCell
    private lateinit var bannerView: ImageView
    private var bannerClear: ImageView? = null
    private lateinit var channelSubtitle: TextView
    private lateinit var notifValue: TextView
    private lateinit var welcomeRandomCell: TextCheckCell
    private lateinit var welcomeStickerCell: TextCheckCell
    private lateinit var auditLogCell: TextCheckCell
    private lateinit var anonymousCell: TextCheckCell
    private var saveButton: TextView? = null

    private var clanSnapshot: ClanEntity? = null
    private var perm: ClanSettingsPermissionState = ClanSettingsPermissionState(false, false, false)

    private var sourceName = ""
    private var draftName = ""
    private var sourceBanner = ""
    private var draftBanner = ""
    private var sourcePrevent = false
    private var draftPrevent = false

    private var loadedSystem: SystemMessage? = null
    private var draftSysChannelId = 0L
    private var draftSysChannelLabel = ""
    private var draftWelcomeRandom = false
    private var draftWelcomeSticker = false
    private var draftHideAuditLog = false

    private var sourceNotif = ClanOverviewNotif.MENTION
    private var draftNotif = ClanOverviewNotif.MENTION
    private var bannerLoaderToken: MezonImageLoader.Cancellable? = null
    private var bannerRenderSeq = 0
    private var bannerPickTarget: View? = null
    private var bannerEditBadge: ImageView? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
        clansController = entryPoint.clansController()
        channelController = entryPoint.channelController()
        userClanController = entryPoint.userClanController()
        roleController = entryPoint.roleController()
        userController = entryPoint.userController()
        permissionPolicy = entryPoint.permissionPolicy()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId != 0L) {
            roleController.loadPermissionCatalogIfNeeded()
            roleController.loadUserMaxPermissionForClan(clanId, force = true)
            roleController.loadRolesForClan(clanId, force = true)
            userClanController.loadClanMembers(clanId)
        }
        observe(NotificationCenter.clanInfoUpdated) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) refreshLocalClanSnapshot()
        }
        observe(NotificationCenter.clanBannerCropped) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id != clanId) return@observe
            val url = args.getOrNull(1) as? String ?: return@observe
            val prev = draftBanner.trim()
            draftBanner = url
            if (prev.isNotBlank() && prev != url.trim()) {
                invalidateBannerCachesForSources(listOf(prev))
            }
            renderBanner()
            updateSaveUi()
        }
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (clanId != 0L) {
            roleController.loadPermissionCatalogIfNeeded()
            roleController.loadUserMaxPermissionForClan(clanId, force = true)
            roleController.loadRolesForClan(clanId, force = true)
            userClanController.loadClanMembers(clanId)
        }
        refreshLocalClanSnapshot()
        fetchRemoteSupplements()
    }

    override fun createView(context: Context): View {
        refreshLocalClanSnapshot()

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.menu_clan_overview_settings))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.common_close))
            setCenterTitle(true)

            val saveItem = createMenu().addItem(1, context.getString(R.string.common_save))
            val saveTv = TextView(context).apply {
                text = context.getString(R.string.common_save)
                setTextColor(themeColors.primary)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
            }
            saveButton = saveTv
            saveItem.addView(
                saveTv,
                LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.MATCH_PARENT,
                    Gravity.CENTER_VERTICAL,
                    0f,
                    3f,
                    0f,
                    0f,
                ),
            )
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        1 -> onSaveClicked()
                    }
                }
            })
        }
        checkNotNull(actionBar).backButton.apply {
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                width = LayoutHelper.dp(48f)
                height = LayoutHelper.dp(48f)
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(
            scrollContent,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        buildContent(context)
        renderBanner()
        applyPermissionUi()
        updateSaveUi()

        fragmentView = root
        return root
    }

    private fun refreshLocalClanSnapshot() {
        if (clanId == 0L) return
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return
        clanSnapshot = clan
        perm = permissionPolicy.clanSettingsPermissionState(clanId)
        val preserveNameDraft = draftName.trim() != sourceName.trim()
        val hadUnsavedBanner = draftBanner.trim() != sourceBanner.trim()
        val preservePreventDraft = draftPrevent != sourcePrevent
        sourceName = clan.clanName
        if (!preserveNameDraft) draftName = clan.clanName
        sourceBanner = clan.banner
        when {
            !hadUnsavedBanner -> draftBanner = clan.banner
            clan.banner.trim() == draftBanner.trim() -> draftBanner = clan.banner
        }
        sourcePrevent = clan.preventAnonymous
        if (!preservePreventDraft) draftPrevent = clan.preventAnonymous
        if (::nameInput.isInitialized) {
            nameInput.setText(draftName)
        }
        renderBanner()
        if (::anonymousCell.isInitialized) {
            anonymousCell.setChecked(draftPrevent)
        }
        applyPermissionUi()
        updateSaveUi()
    }

    private fun buildContent(context: Context) {
        scrollContent.removeAllViews()

        val bannerWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val bannerFrame = android.widget.FrameLayout(context).apply {
            val h = LayoutHelper.dp(200f)
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, h)
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(12f)
                setColor(themeColors.surfaceVariant)
            }
            clipToOutline = true
            setOnClickListener {
                if (!canEditClanFields()) {
                    MezonToast.show(this@ClanOverviewSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_overview_banner_permission))
                    return@setOnClickListener
                }
                pickBanner()
            }
        }
        bannerView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bannerFrame.addView(bannerView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val badgeSize = LayoutHelper.dp(44f)
        val bannerEdit = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            val pad = LayoutHelper.dp(11f)
            setPadding(pad, pad, pad, pad)
            setImageDrawable(MezonIcon.cameraIcon.getDrawable(context, 0xFFFFFFFF.toInt()))
            contentDescription = context.getString(R.string.clan_overview_banner_edit_cd)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            elevation = LayoutHelper.dpf(2f)
            setOnClickListener {
                if (!canEditClanFields()) {
                    MezonToast.show(this@ClanOverviewSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_overview_banner_permission))
                    return@setOnClickListener
                }
                pickBanner()
            }
        }
        bannerEditBadge = bannerEdit
        bannerFrame.addView(
            bannerEdit,
            FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.CENTER),
        )
        layoutBannerCameraBadge()
        val clearSize = LayoutHelper.dp(30f)
        val clear = ImageView(context).apply {
            visibility = if (draftBanner.isNotBlank()) View.VISIBLE else View.GONE
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(MezonIcon.closeSmallBold.getDrawable(context, themeColors.error))
            contentDescription = context.getString(R.string.clan_overview_clear_banner_cd)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
            }
            val ip = LayoutHelper.dp(5f)
            setPadding(ip, ip, ip, ip)
            elevation = LayoutHelper.dpf(4f)
            setOnClickListener {
                if (!canEditClanFields()) {
                    MezonToast.show(this@ClanOverviewSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_overview_banner_permission))
                    return@setOnClickListener
                }
                invalidateBannerCachesForSources(listOfNotNull(draftBanner.trim().takeIf { it.isNotBlank() }))
                draftBanner = ""
                renderBanner()
                updateSaveUi()
            }
        }
        bannerClear = clear
        bannerFrame.addView(
            clear,
            FrameLayout.LayoutParams(clearSize, clearSize, Gravity.END or Gravity.TOP).apply {
                topMargin = LayoutHelper.dp(6f)
                marginEnd = LayoutHelper.dp(6f)
            },
        )
        bannerPickTarget = bannerFrame
        bannerWrap.addView(bannerFrame)
        scrollContent.addView(bannerWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 16f))

        nameInput = InputCell(context, themeColors).apply {
            setLabel(context.getString(R.string.clan_overview_name_label))
            setCellBackgroundColor(themeColors.surfaceVariant)
            setCellStrokeColor(0x00000000)
            editText.filters = arrayOf(InputFilter.LengthFilter(CLAN_OVERVIEW_NAME_MAX_LENGTH))
            setText(draftName)
            onTextChanged = { s ->
                draftName = s
                updateSaveUi()
            }
        }
        scrollContent.addView(nameInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 8f))

        channelSubtitle = TextView(context).apply {
            textSize = 14f
            setTextColor(themeColors.onSurfaceVariant)
            maxLines = 2
        }
        welcomeRandomCell = buildSwitchRow(
            context,
            getString(R.string.clan_overview_sys_welcome_random),
            draftWelcomeRandom,
        ) { v ->
            draftWelcomeRandom = v
            updateSaveUi()
        }
        welcomeStickerCell = buildSwitchRow(
            context,
            getString(R.string.clan_overview_sys_welcome_sticker),
            draftWelcomeSticker,
        ) { v ->
            draftWelcomeSticker = v
            updateSaveUi()
        }
        auditLogCell = buildSwitchRow(
            context,
            getString(R.string.clan_overview_sys_audit_log),
            !draftHideAuditLog,
        ) { postAudit ->
            draftHideAuditLog = !postAudit
            updateSaveUi()
        }

        val sysSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        sysSection.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_overview_sys_section_title)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(themeColors.onSurface)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 6f),
        )
        sysSection.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_overview_sys_section_desc)
                textSize = 13f
                setTextColor(themeColors.onSurfaceVariant)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 10f),
        )
        val sysCard = ClanSettingsUiHelpers.buildMezonSection(
            context,
            themeColors,
            null,
            listOf(
                channelPickerRow(context),
                welcomeRandomCell,
                welcomeStickerCell,
                auditLogCell,
            ),
        )
        sysSection.addView(sysCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        scrollContent.addView(sysSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 18f, 0f, 0f))

        anonymousCell = buildSwitchRow(context, getString(R.string.clan_overview_anonymous_desc), draftPrevent) { v ->
            draftPrevent = v
            updateSaveUi()
        }
        val anonSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        anonSection.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_overview_anonymous_section)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(themeColors.onSurface)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 6f),
        )
        anonSection.addView(
            ClanSettingsUiHelpers.buildMezonSection(
                context,
                themeColors,
                null,
                listOf(anonymousCell),
            ),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )
        scrollContent.addView(anonSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 18f, 0f, 0f))

        notifValue = TextView(context).apply {
            textSize = 14f
            setTextColor(themeColors.onSurfaceVariant)
        }
        val notifSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        notifSection.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_overview_notif_section)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(themeColors.onSurface)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 6f),
        )
        notifSection.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_overview_notif_desc)
                textSize = 13f
                setTextColor(themeColors.onSurfaceVariant)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 0f, 0f, 10f),
        )
        notifSection.addView(
            ClanSettingsUiHelpers.buildMezonSection(
                context,
                themeColors,
                null,
                listOf(notifPickerRow(context)),
            ),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )
        scrollContent.addView(notifSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 18f, 0f, 0f))
    }

    private fun channelPickerRow(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(13f), LayoutHelper.dp(14f), LayoutHelper.dp(13f))
            setBackgroundColor(themeColors.border)
            isClickable = true
            setOnClickListener { openChannelPicker() }
            val titles = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titles.addView(
                TextView(context).apply {
                    text = context.getString(R.string.clan_overview_sys_channel)
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(themeColors.onSurface)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
            )
            channelSubtitle.text = draftSysChannelLabel.ifEmpty { context.getString(R.string.clan_overview_sys_pick_channel) }
            titles.addView(channelSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 0f))
            addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
            val chev = ImageView(context).apply {
                 val d = MezonIcon.chevronSmallRightIcon.getDrawable(context)
                d.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
                setImageDrawable(d)
            }
            addView(chev, LayoutHelper.createLinear(18, 18, 0f, Gravity.CENTER_VERTICAL))
        }
    }

    private fun notifPickerRow(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(13f), LayoutHelper.dp(14f), LayoutHelper.dp(13f))
            setBackgroundColor(themeColors.border)
            isClickable = true
            setOnClickListener { openNotifPicker() }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(
                TextView(context).apply {
                    text = context.getString(R.string.clan_overview_notif_pick)
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(themeColors.onSurface)
                },
            )
            notifValue.text = notificationTitle(context, draftNotif)
            col.addView(notifValue, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.START, 0f, 4f, 0f, 0f))
            addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
            val chev = ImageView(context).apply {
                val d = MezonIcon.chevronSmallRightIcon.getDrawable(context)
                d.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
                setImageDrawable(d)
            }
            addView(chev, LayoutHelper.createLinear(18, 18, 0f, Gravity.CENTER_VERTICAL))
        }
    }

    private fun buildSwitchRow(
        context: Context,
        title: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): TextCheckCell {
        return TextCheckCell(context, themeColors).apply {
            setTextAndCheck(title, "", initial, divider = false)
            onCheckedChange = { checked ->
                if (!canEditClanFields()) {
                    setChecked(!checked)
                } else {
                    onChange(checked)
                }
            }
        }
    }

    private fun canEditClanFields(): Boolean =
        perm.hasAdminPermission || perm.hasManageClanPermission || perm.isClanOwner

    private fun applyPermissionUi() {
        val allow = canEditClanFields()
        val alpha = if (allow) 1f else 0.45f
        if (::nameInput.isInitialized && ::scrollContent.isInitialized) {
            nameInput.editText.isEnabled = allow
            nameInput.alpha = alpha
            bannerPickTarget?.isEnabled = allow
            bannerPickTarget?.alpha = alpha
            bannerEditBadge?.isEnabled = allow
            bannerEditBadge?.alpha = alpha
            bannerClear?.isEnabled = allow
            bannerClear?.alpha = alpha
            listOf(welcomeRandomCell, welcomeStickerCell, auditLogCell, anonymousCell).forEach { c ->
                c.isEnabled = allow
                c.alpha = alpha
            }
            saveButton?.isEnabled = allow
        }
    }

    private fun fetchRemoteSupplements() {
        if (clanId == 0L) return
        fragmentScope.launch(mainDispatcher) {
            runCatching {
                val notifDef = asyncFetchDefaultNotification()
                val sys = asyncFetchSystemMessage()
                Pair(notifDef, sys)
            }.onSuccess { (n, s) ->
                sourceNotif = n
                draftNotif = n
                notifValue.text = notificationTitle(getContext() ?: return@onSuccess, draftNotif)
                loadedSystem = s
                if (s.channelId != 0L) {
                    draftSysChannelId = s.channelId
                    draftSysChannelLabel = channelController.getChannels(clanId)
                        .firstOrNull { it.channelId == s.channelId }
                        ?.channelLabel.orEmpty()
                }
                draftWelcomeRandom = s.welcomeRandom == "1"
                draftWelcomeSticker = s.welcomeSticker == "1"
                draftHideAuditLog = s.hideAuditLog
                if (::welcomeRandomCell.isInitialized) {
                    welcomeRandomCell.setChecked(draftWelcomeRandom)
                    welcomeStickerCell.setChecked(draftWelcomeSticker)
                    auditLogCell.setChecked(!draftHideAuditLog)
                }
                channelSubtitle.text = draftSysChannelLabel.ifEmpty {
                    getString(R.string.clan_overview_sys_pick_channel)
                }
                updateSaveUi()
            }.onFailure {
                MezonToast.show(this@ClanOverviewSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_overview_load_failed))
            }
        }
    }

    private suspend fun asyncFetchDefaultNotification(): Int = withContext(ioDispatcher) {
        sessionManager.withAutoRefresh { session ->
            api.getClanDefaultNotification(session.apiUrl, session.token, clanId).notificationSettingType
        }
    }

    private suspend fun asyncFetchSystemMessage(): SystemMessage = withContext(ioDispatcher) {
        sessionManager.withAutoRefresh { session ->
            api.getSystemMessageForClan(session.apiUrl, session.token, clanId)
        }
    }

    private fun openChannelPicker() {
        if (!canEditClanFields()) return
        val ctx = getContext() ?: return
        val selectedId = draftSysChannelId
        val candidates = channelController.getChannels(clanId).filter { ch ->
            !ch.isPrivate &&
                ch.type == CHANNEL_TYPE_CHANNEL &&
                (selectedId == 0L || ch.channelId != selectedId)
        }
        if (candidates.isEmpty()) {
            MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.clan_invite_need_channel))
            return
        }
        val labels: Array<CharSequence> = Array(candidates.size) { i ->
            val ch = candidates[i]
            "${ch.channelLabel} · ${ch.categoryName}"
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.clan_overview_sys_pick_channel))
            .setItems(labels) { _, which ->
                val ch = candidates[which]
                draftSysChannelId = ch.channelId
                draftSysChannelLabel = ch.channelLabel
                channelSubtitle.text = draftSysChannelLabel
                updateSaveUi()
            }
            .show()
    }

    private fun openNotifPicker() {
        if (!canEditClanFields()) return
        val ctx = getContext() ?: return
        val opts: Array<CharSequence> = arrayOf(
            getString(R.string.clan_overview_notif_all),
            getString(R.string.clan_overview_notif_mentions),
            getString(R.string.clan_overview_notif_nothing),
        )
        val values = intArrayOf(ClanOverviewNotif.ALL, ClanOverviewNotif.MENTION, ClanOverviewNotif.NOTHING)
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.clan_overview_notif_pick))
            .setItems(opts) { _, which ->
                val next = values[which]
                fragmentScope.launch(mainDispatcher) {
                    val prev = draftNotif
                    draftNotif = next
                    notifValue.text = notificationTitle(ctx, draftNotif)
                    runCatching {
                        sessionManager.withAutoRefresh { session ->
                            withContext(ioDispatcher) {
                                api.setClanDefaultNotification(session.apiUrl, session.token, clanId, next)
                            }
                        }
                        sourceNotif = next
                        updateSaveUi()
                    }.onSuccess {
                        MezonToast.show(
                            this@ClanOverviewSettingFragment,
                            ToastOverlay.ToastType.SUCCESS,
                            getString(R.string.clan_overview_notif_update_success),
                        )
                    }.onFailure {
                        draftNotif = prev
                        notifValue.text = notificationTitle(ctx, draftNotif)
                        MezonToast.show(this@ClanOverviewSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_overview_notif_update_failed))
                    }
                }
            }
            .show()
    }

    private fun notificationTitle(context: Context, type: Int): String = when (type) {
        ClanOverviewNotif.ALL -> context.getString(R.string.clan_overview_notif_all)
        ClanOverviewNotif.NOTHING -> context.getString(R.string.clan_overview_notif_nothing)
        else -> context.getString(R.string.clan_overview_notif_mentions)
    }

    private fun clanFieldsDirty(): Boolean =
        draftName.trim() != sourceName.trim() ||
            draftBanner.trim() != sourceBanner.trim() ||
            draftPrevent != sourcePrevent

    private fun systemMessageDirty(): Boolean {
        val s = loadedSystem ?: return draftSysChannelId != 0L ||
            draftWelcomeRandom || draftWelcomeSticker || draftHideAuditLog
        return draftSysChannelId != s.channelId ||
            (if (draftWelcomeRandom) "1" else "0") != s.welcomeRandom ||
            (if (draftWelcomeSticker) "1" else "0") != s.welcomeSticker ||
            draftHideAuditLog != s.hideAuditLog
    }

    private fun updateSaveUi() {
        val dirty = (clanFieldsDirty() || systemMessageDirty()) && isClanNameValid(draftName)
        saveButton?.alpha = if (dirty && canEditClanFields()) 1f else 0.45f
    }

    private fun invalidateBannerCachesForSources(urls: Collection<String>) {
        if (urls.isEmpty()) return
        val extraW = if (::scrollContent.isInitialized) scrollContent.width else 0
        clansController.invalidateBannerImageCaches(urls, extraW)
    }

    private fun pickBanner() {
        val pick = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(getContent, getString(R.string.clan_upload_label)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pick))
        }
        startActivityForResult(chooser, REQ_PICK_BANNER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_BANNER || resultCode != Activity.RESULT_OK) return
        val uri = data?.clipData?.getItemAt(0)?.uri ?: data?.data ?: return
        presentFragment(ClanBannerTransformFragment.newInstance(clanId, uri.toString()))
    }

    private fun layoutBannerCameraBadge() {
        val badge = bannerEditBadge ?: return
        if (draftBanner.isNotBlank()) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        val lp = badge.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(LayoutHelper.dp(44f), LayoutHelper.dp(44f)).also { badge.layoutParams = it }
        val size = LayoutHelper.dp(44f)
        lp.width = size
        lp.height = size
        lp.gravity = Gravity.CENTER
        lp.setMargins(0, 0, 0, 0)
        lp.topMargin = 0
        lp.marginStart = 0
        lp.marginEnd = 0
        badge.layoutParams = lp
    }

    private fun renderBanner() {
        if (!::bannerView.isInitialized) return
        bannerLoaderToken?.cancel()
        bannerLoaderToken = null
        val seq = ++bannerRenderSeq
        bannerClear?.visibility = if (draftBanner.isNotBlank()) View.VISIBLE else View.GONE
        layoutBannerCameraBadge()
        bannerPickTarget?.contentDescription = if (draftBanner.isBlank()) {
            getString(R.string.clan_overview_banner_cd)
        } else {
            getString(R.string.clan_overview_banner_tap_to_change)
        }
        if (draftBanner.isBlank()) {
            bannerView.setImageDrawable(null)
            return
        }
        val ctx = getContext() ?: return
        val w = scrollContent.width.coerceAtLeast(LayoutHelper.dp(300f))
        val h = LayoutHelper.dp(200f)
        val url = createImgproxyUrl(draftBanner.trim(), w, h, "fit")
        bannerLoaderToken = MezonImageLoader.getInstance(ctx).load(url, w, h, onSuccess = { bmp ->
            if (seq != bannerRenderSeq) return@load
            bannerView.setImageBitmap(bmp)
        })
    }

    private fun onSaveClicked() {
        if (!canEditClanFields()) return
        if (!isClanNameValid(draftName)) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.clan_overview_name_invalid))
            return
        }
        val trimmed = draftName.trim()
        if (!clanFieldsDirty() && !systemMessageDirty()) return
        fragmentScope.launch(mainDispatcher) {
            if (trimmed != sourceName.trim()) {
                val dup = clansController.isDuplicateClanName(trimmed)
                if (dup) {
                    MezonToast.show(this@ClanOverviewSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_overview_name_duplicate))
                    return@launch
                }
            }
            runCatching {
                if (clanFieldsDirty()) {
                    val trimmedSourceBanner = sourceBanner.trim()
                    val trimmedDraftBanner = draftBanner.trim()
                    val clanBannerUpdatedUrl: String? =
                        if (trimmedDraftBanner != trimmedSourceBanner) trimmedDraftBanner else null
                    val extraW = if (::scrollContent.isInitialized) scrollContent.width else 0
                    val invalidateBannerSources =
                        if (clanBannerUpdatedUrl != null) {
                            listOfNotNull(
                                trimmedSourceBanner.takeIf { it.isNotBlank() },
                                trimmedDraftBanner.takeIf { it.isNotBlank() },
                            ).takeIf { it.isNotEmpty() }
                        } else null

                    clansController.updateClanOverviewDesc(
                        clanId = clanId,
                        clanName = trimmed,
                        clanBannerUrl = clanBannerUpdatedUrl,
                        preventAnonymous = draftPrevent,
                        welcomeChannelId = clanSnapshot?.welcomeChannelId?.takeIf { it != 0L },
                        isOnboarding = clanSnapshot?.isOnboarding,
                        invalidateBannerSources = invalidateBannerSources,
                        bannerExtraWidthPx = extraW,
                    )
                    sourceName = trimmed
                    sourceBanner = trimmedDraftBanner
                    draftBanner = trimmedDraftBanner
                    sourcePrevent = draftPrevent
                    renderBanner()
                }
                if (systemMessageDirty()) {
                    val base = loadedSystem
                    val boost = base?.boostMessage.orEmpty()
                    val tips = base?.setupTips.orEmpty()
                    loadedSystem = clansController.updateClanSystemMessage(
                        systemMessageRequest {
                            clanId = this@ClanOverviewSettingFragment.clanId
                            channelId = draftSysChannelId
                            welcomeRandom = if (draftWelcomeRandom) "1" else "0"
                            welcomeSticker = if (draftWelcomeSticker) "1" else "0"
                            boostMessage = boost
                            setupTips = tips
                            hideAuditLog = draftHideAuditLog
                        },
                    )
                }
                MezonToast.show(this@ClanOverviewSettingFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.clan_overview_save_success))
                updateSaveUi()
            }.onFailure {
                MezonToast.show(this@ClanOverviewSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_overview_save_error))
            }
        }
    }

    override fun clearViews() {
        bannerLoaderToken?.cancel()
        bannerLoaderToken = null
        super.clearViews()
    }
}
