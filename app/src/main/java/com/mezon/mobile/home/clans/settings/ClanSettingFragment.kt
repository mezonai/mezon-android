package com.mezon.mobile.home.clans.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClanEntity
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.launch

class ClanSettingFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val REQUEST_CODE_PICK_CLAN_LOGO = 2010

        fun newInstance(clanId: Long): ClanSettingFragment = ClanSettingFragment().apply {
            arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
        }
    }

    private var clanId = 0L
    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController
    private lateinit var userClanController: UserClanController
    private lateinit var roleController: RoleController
    private lateinit var userController: UserController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var invitePeopleController: InvitePeopleController

    private lateinit var scrollInner: LinearLayout
    private lateinit var logoContainer: LinearLayout
    private var logoUploadOverlay: FrameLayout? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        clansController = entryPoint.clansController()
        channelController = entryPoint.channelController()
        userClanController = entryPoint.userClanController()
        roleController = entryPoint.roleController()
        userController = entryPoint.userController()
        permissionPolicy = entryPoint.permissionPolicy()
        invitePeopleController = entryPoint.invitePeopleController()
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
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) refreshMenu()
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) refreshMenu()
        }
        observe(NotificationCenter.clanInfoUpdated) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) refreshMenu()
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
        refreshMenu()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = CreateClanRnUiTokens.clanSettingDiagonalGradient(themeColors)
            clipChildren = false
        }

        val headerTop = AndroidUtilities.statusBarHeight + LayoutHelper.dp(10f)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(20f), headerTop, LayoutHelper.dp(20f), LayoutHelper.dp(8f))
        }
        val closeTargetDp = 44
        val closeIconDp = 28
        val closeWrap = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            clipChildren = false
            val rippleMask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            background = RippleDrawable(
                ColorStateList.valueOf(CreateClanRnUiTokens.menuText(themeColors) and 0x1AFFFFFF),
                ColorDrawable(Color.TRANSPARENT),
                rippleMask
            )
            setOnClickListener { finishFragment() }
        }
        closeWrap.addView(
            ImageView(context).apply {
                setImageDrawable(MezonIcon.closeIcon.getDrawable(context, CreateClanRnUiTokens.closeIcon(themeColors)))
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            },
            FrameLayout.LayoutParams(LayoutHelper.dp(closeIconDp), LayoutHelper.dp(closeIconDp), Gravity.CENTER)
        )
        header.addView(
            closeWrap,
            LayoutHelper.createLinear(closeTargetDp, closeTargetDp, 0f, Gravity.CENTER_VERTICAL)
        )

        header.addView(
            TextView(context).apply {
                text = getString(R.string.clan_settings_title)
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(themeColors.colorText)
                gravity = android.view.Gravity.CENTER
            },
            LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        )

        header.addView(
            View(context),
            LayoutHelper.createLinear(closeTargetDp, closeTargetDp, 0f, Gravity.CENTER_VERTICAL)
        )

        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        logoContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        root.addView(logoContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }
        scrollInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(20f), 0, LayoutHelper.dp(20f), LayoutHelper.dp(24f))
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(scrollInner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        refreshMenu()
        fragmentScope.launch(entryPoint().mainDispatcher()) {
            clansController.clanLogoUpdateInFlight.collect { ids ->
                val show = clanId != 0L && clanId in ids
                logoUploadOverlay?.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        fragmentView = root
        return root
    }

    private fun refreshMenu() {
        if (!::scrollInner.isInitialized || !::logoContainer.isInitialized) return
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return
        val perm = permissionPolicy.clanSettingsPermissionState(clanId)

        logoContainer.removeAllViews()
        logoContainer.addView(
            buildClanLogoStrip(clan, perm.isShowOverviewOption),
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                0f,
                Gravity.CENTER_HORIZONTAL,
            )
        )

        scrollInner.removeAllViews()

        val ctx = scrollInner.context
        scrollInner.addView(
            ClanSettingsUiHelpers.buildMezonSection(
                ctx,
                themeColors,
                getString(R.string.clan_settings_section_settings),
                buildSettingsRows(ctx, perm)
            ),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, android.view.Gravity.NO_GRAVITY, 0f, 18f, 0f, 0f)
        )

        scrollInner.addView(
            ClanSettingsUiHelpers.buildMezonSection(
                ctx,
                themeColors,
                getString(R.string.clan_settings_section_user_management),
                buildUserRows(ctx, perm)
            ),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, android.view.Gravity.NO_GRAVITY, 0f, 18f, 0f, 0f)
        )
    }

    private fun buildClanLogoStrip(clan: ClanEntity, canEditClanLogo: Boolean): LinearLayout {
        val ctx = logoContainer.context
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            setPadding(0, LayoutHelper.dp(40f), 0, LayoutHelper.dp(40f))
        }
        val tileDp = 76
        val logoInsetDpInt = 4
        val avatarInnerDp = (tileDp - 2 * logoInsetDpInt).coerceAtLeast(24)
        val logoInsetPx = LayoutHelper.dp(logoInsetDpInt.toFloat())
        val tilePx = LayoutHelper.dp(tileDp)
        val avPx = LayoutHelper.dp(avatarInnerDp)
        val tileCornerDp = 20f * tileDp / 60f
        val padding = 12
        val wrapperSize = tileDp + padding
        val logoWrap = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val innerHolder = FrameLayout(ctx).apply {
            clipChildren = false
            setPadding(logoInsetPx, logoInsetPx, logoInsetPx, logoInsetPx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = LayoutHelper.dpf(tileCornerDp)
                setColor(Color.TRANSPARENT)
                setStroke(LayoutHelper.dp(1), themeColors.borderDim)
            }
        }
        val avatar = AvatarView(ctx).apply {
            setSizeDp(avatarInnerDp)
            setRoundRadius((tileCornerDp - logoInsetDpInt).coerceAtLeast(6f))
            setInfo(clan.clanId, clan.clanName)
            if (clan.logo.isNotEmpty()) setImageUrl(clan.logo)
        }
        innerHolder.addView(
            avatar,
            FrameLayout.LayoutParams(avPx, avPx, Gravity.CENTER)
        )
        if (canEditClanLogo) {
            avatar.isClickable = true
            avatar.setOnClickListener { openClanLogoPicker() }
        }
        logoWrap.addView(
            innerHolder,
            FrameLayout.LayoutParams(tilePx, tilePx, Gravity.CENTER)
        )
        if (canEditClanLogo && clan.logo.isNotEmpty()) {
            val removeSzDp = 20f
            val removeSz = LayoutHelper.dp(removeSzDp)
            val gapDpF = (wrapperSize - tileDp) / 2f
            val innerRightDp = gapDpF + tileDp
            val innerTopDp = gapDpF
            val removeBtn = ImageView(ctx).apply {
                setImageDrawable(MezonIcon.circleXIcon.getDrawable(context, themeColors.error))
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                contentDescription = context.getString(R.string.clan_settings_remove_logo)
                setOnClickListener {
                    removeClanLogo()
                }
                elevation = LayoutHelper.dpf(4f)
            }

            val badgeLeftDp = innerRightDp - removeSzDp
            val badgeTopDp = innerTopDp
            logoWrap.addView(
                removeBtn,
                FrameLayout.LayoutParams(removeSz, removeSz).apply {
                    gravity = Gravity.NO_GRAVITY
                    leftMargin = LayoutHelper.dp(badgeLeftDp)
                    topMargin = LayoutHelper.dp(badgeTopDp)
                }
            )
        }
        val uploading = clanId != 0L && clanId in clansController.clanLogoUpdateInFlight.value
        val logoBusyOverlay = FrameLayout(ctx).apply {
            visibility = if (uploading) View.VISIBLE else View.GONE
            setBackgroundColor(0x66000000)
            isClickable = true
            val pb = ProgressBar(ctx).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(themeColors.colorText)
            }
            addView(
                pb,
                FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER)
            )
        }
        logoWrap.addView(
            logoBusyOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        logoUploadOverlay = logoBusyOverlay
        outer.addView(
            logoWrap,
            LayoutHelper.createLinear(
                wrapperSize,
                wrapperSize,
                0f,
                Gravity.CENTER_HORIZONTAL,
            )
        )
        outer.addView(
            TextView(ctx).apply {
                text = clan.clanName
                textSize = 14f
                setTextColor(CreateClanRnUiTokens.textStrong(themeColors))
                setPadding(0, LayoutHelper.dp(10f), 0, 0)
                gravity = Gravity.CENTER_HORIZONTAL
            },
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                0f,
                Gravity.CENTER_HORIZONTAL,
            )
        )
        return outer
    }

    private fun openClanLogoPicker() {
        val pick = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(getContent, getString(R.string.clan_settings_change_logo)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pick))
        }
        startActivityForResult(chooser, REQUEST_CODE_PICK_CLAN_LOGO)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || requestCode != REQUEST_CODE_PICK_CLAN_LOGO) return
        val clip = data?.clipData
        val uri = clip?.getItemAt(0)?.uri ?: data?.data ?: return
        presentFragment(ClanLogoTransformFragment.newInstance(clanId, uri.toString()))
    }
    private fun removeClanLogo() {
        clansController.updateClanLogo(clanId, "") { ok, msg ->
            if (!ok) {
                val text = msg?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.clan_settings_logo_update_failed)
                MezonToast.show(this, ToastOverlay.ToastType.ERROR, text)
            }
        }
    }

    private fun buildSettingsRows(ctx: Context, perm: ClanSettingsPermissionState): List<View> {
        return ClanSetting.settingsSectionRows(perm).map { menuRowToView(ctx, it) }
    }

    private fun buildUserRows(ctx: Context, perm: ClanSettingsPermissionState): List<View> {
        return ClanSetting.userManagementSectionRows(perm).map { menuRowToView(ctx, it) }
    }

    private fun menuRowToView(ctx: Context, row: ClanSetting.MenuRow): View {
        return when (row) {
            is ClanSetting.MenuRow.Navigate ->
                when (row.labelRes) {
                    R.string.clan_settings_overview ->
                        navigationRow(ctx, row.icon, row.labelRes, Runnable {
                            presentFragment(ClanOverviewSettingFragment.newInstance(clanId))
                        })
                    R.string.clan_settings_audit_log ->
                        navigationRow(ctx, row.icon, row.labelRes, Runnable {
                            presentFragment(AuditLogSettingFragment.newInstance(clanId))
                        })
                    R.string.clan_settings_integrations ->
                        navigationRow(ctx, row.icon, row.labelRes, Runnable {
                            presentFragment(IntegrationSettingFragment.newInstance(clanId))
                        })
                    R.string.clan_settings_roles ->
                        navigationRow(ctx, row.icon, row.labelRes, Runnable {
                            presentFragment(ServerRolesFragment.newInstance(clanId))
                        })
                    R.string.clan_settings_emoji ->
                        navigationRow(ctx, row.icon, row.labelRes, Runnable {
                            presentFragment(EmojiSettingFragment.newInstance(clanId))
                        })
                    else ->
                        navigationRow(ctx, row.icon, row.labelRes, Runnable {
                            MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.feature_coming_soon))
                        })
                }
            ClanSetting.MenuRow.InvitePeople ->
                ClanSettingsUiHelpers.buildMezonChevronRow(
                    ctx,
                    themeColors,
                    MezonIcon.linkIcon,
                    getString(R.string.clan_settings_invites),
                    null,
                    Runnable { openInvitePeople() }
                )
        }
    }

    private fun navigationRow(ctx: Context, icon: MezonIcon, labelRes: Int, onNavigate: Runnable): View {
        return ClanSettingsUiHelpers.buildMezonChevronRow(
            ctx,
            themeColors,
            icon,
            getString(labelRes),
            null,
            onNavigate,
        )
    }

    private fun openInvitePeople() {
        val ctx = getContext() ?: return
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return
        InvitePeopleBottomSheet(
            ctx,
            invitePeopleController,
            clanId,
            clan.clanName,
            clan.logo,
        ).apply {
            setDrawNavigationBar(true)
            show()
        }
    }
}
