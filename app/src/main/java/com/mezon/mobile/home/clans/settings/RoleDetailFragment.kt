package com.mezon.mobile.home.clans.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ClanRole
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.clans.isEveryoneRole
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.FileUtils
import com.mezon.mobile.util.ContentUriTooLargeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROLE_NAME_MAX = 64
private const val ROLE_ICON_MAX_BYTES = 256 * 1024
private const val DEFAULT_ROLE_COLOR_HEX = "#99aab5"
private const val REQ_PICK_ROLE_ICON = 3012
private const val MENU_SAVE = 3

class RoleDetailFragment : BaseFragment() {

    companion object {
        private const val TAG = "RoleDetailEdit"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_ROLE_ID = "roleId"

        fun newInstance(clanId: Long, roleId: Long): RoleDetailFragment =
            RoleDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_ROLE_ID, roleId)
                }
            }
    }

    private var clanId = 0L
    private var roleId = 0L
    private lateinit var roleController: RoleController
    private lateinit var userClanController: UserClanController
    private lateinit var clansController: ClansController
    private lateinit var userController: UserController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var nameInput: InputCell
    private lateinit var nameLock: ImageView
    private var saveItem: View? = null
    private var saveText: TextView? = null
    private lateinit var colorRow: LinearLayout
    private lateinit var colorSwatch: View
    private lateinit var colorHexLabel: TextView
    private lateinit var colorChevron: ImageView
    private lateinit var colorLock: ImageView
    private lateinit var deleteBtn: TextView
    private lateinit var iconAvatar: com.mezon.mobile.ui.cells.CdnIconView
    private lateinit var iconPlaceholder: ImageView
    private lateinit var iconRemoveBtn: TextView
    private lateinit var iconLock: ImageView
    private lateinit var iconPickerHit: FrameLayout
    private lateinit var iconRow: LinearLayout
    private lateinit var permActionRow: LinearLayout
    private lateinit var memActionRow: LinearLayout
    private lateinit var permLock: ImageView
    private lateinit var memLock: ImageView
    private var colorPickerSheet: RoleColorPickerBottomSheet? = null
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher
    private var draftIconUrl = ""
    private var originIconUrl = ""
    private var draftName = ""
    private var originName = ""
    private var draftColorHex = ""
    private var originColorHex = ""
    private val roleColors = listOf(
        "#1abc9c", "#2ecc71", "#3498db", "#9b59b6", "#e91e63", "#f1c40f",
        "#e67e22", "#e74c3c", "#95a5a6", "#607d8b", "#11806a", "#1f8b4c",
        "#206694", "#71368a", "#ad1457", "#c27c0e", "#e84300", "#992d22",
        "#979c9f", "#546e7a",
    )

    override fun onInject(entryPoint: FragmentEntryPoint) {
        roleController = entryPoint.roleController()
        userClanController = entryPoint.userClanController()
        clansController = entryPoint.clansController()
        userController = entryPoint.userController()
        permissionPolicy = entryPoint.permissionPolicy()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        roleId = arguments?.getLong(ARG_ROLE_ID) ?: 0L
        if (clanId != 0L) {
            userClanController.loadClanMembers(clanId)
            roleController.loadPermissionCatalogIfNeeded()
            roleController.loadUserMaxPermissionForClan(clanId, force = true)
            roleController.loadRolesForClan(clanId)
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) updateEditableChrome()
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) updateEditableChrome()
        }
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)
        actionBar = ActionBarView(context, themeColors).apply {
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.clan_roles_back_content_desc))
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            val menu = createMenu()
            val saveItem = menu.addItem(MENU_SAVE, getString(R.string.clan_roles_detail_save))
            this@RoleDetailFragment.saveItem = saveItem
            saveItem.visibility = View.GONE
            val saveLabel = TextView(context).apply {
                text = getString(R.string.clan_roles_detail_save)
                setTextColor(themeColors.blurple)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
            }
            saveText = saveLabel
            saveItem.addView(
                saveLabel,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 0f, 3f, 0f, 0f)
            )
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> handleCloseRequested()
                        MENU_SAVE -> saveChanges()
                    }
                }
            })
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(14f), 0, LayoutHelper.dp(14f), LayoutHelper.dp(24f))
        }

        nameInput = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.clan_roles_detail_role_name))
            setMaxCharacter(ROLE_NAME_MAX)
            setShowCharacterCount(true)
        }
        nameInput.onTextChanged = {
            draftName = it
            updateSaveActionState()
        }
        val nameWrap = FrameLayout(context)
        nameWrap.addView(nameInput, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        nameLock = ImageView(context).apply {
            visibility = View.GONE
            setImageDrawable(
                MezonIcon.lockIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
                }
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        nameWrap.addView(
            nameLock,
            LayoutHelper.createFrame(16, 16, Gravity.END or Gravity.CENTER_VERTICAL, 0f, 12f, 14f, 0f),
        )
        inner.addView(nameWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 14f, 0f, 14f))

        colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(8f)
                setColor(CreateClanRnUiTokens.menuItemBackground(themeColors))
            }
            setOnClickListener { showRoleColorPickerSheet() }
        }
        val colorLabelWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        colorLabelWrap.addView(
            TextView(context).apply {
                text = getString(R.string.clan_roles_detail_color)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ClanRolesUiTheme.secondaryCardTitleColor(themeColors))
            },
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL),
        )
        colorLock = ImageView(context).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(
                MezonIcon.lockIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
                },
            )
        }
        colorLabelWrap.addView(
            colorLock,
            LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL, 6f, 0f, 0f, 0f),
        )
        colorRow.addView(colorLabelWrap, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
        val colorRight = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        colorSwatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(40f), LayoutHelper.dp(40f))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(6f)
                setColor(Color.parseColor(DEFAULT_ROLE_COLOR_HEX))
            }
        }
        colorRight.addView(colorSwatch)
        colorHexLabel = TextView(context).apply {
            textSize = 13f
            setTextColor(themeColors.textDisabled)
            setPadding(LayoutHelper.dp(10f), 0, 0, 0)
        }
        colorRight.addView(colorHexLabel)
        colorChevron = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(
                MezonIcon.chevronSmallRightIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
                },
            )
        }
        colorRight.addView(
            colorChevron,
            LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL, 10f, 0f, 0f, 0f),
        )
        colorRow.addView(colorRight)
        inner.addView(
            colorRow,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 10f, 0f, 10f),
        )

        iconRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(8f)
                setColor(CreateClanRnUiTokens.menuItemBackground(themeColors))
            }
            isClickable = true
            setOnClickListener { showRoleIconPickerFromHit() }
        }
        val iconLabelWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        iconLabelWrap.addView(
            TextView(context).apply {
                text = getString(R.string.clan_roles_detail_icon)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ClanRolesUiTheme.secondaryCardTitleColor(themeColors))
                isClickable = false
                isFocusable = false
            },
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL),
        )
        iconLock = ImageView(context).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(
                MezonIcon.lockIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
                },
            )
        }
        iconLabelWrap.addView(
            iconLock,
            LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL, 6f, 0f, 0f, 0f),
        )
        iconRow.addView(
            iconLabelWrap,
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL),
        )
        val iconTail = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
        }
        iconRemoveBtn = TextView(context).apply {
            visibility = View.GONE
            text = getString(R.string.clan_roles_icon_remove)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(themeColors.redStrong)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(4f)
                setStroke(LayoutHelper.dp(1f), themeColors.redStrong)
            }
            setPadding(LayoutHelper.dp(8f), LayoutHelper.dp(8f), LayoutHelper.dp(8f), LayoutHelper.dp(8f))
            isClickable = true
            setOnClickListener { removeDraftRoleIcon() }
        }
        iconTail.addView(
            iconRemoveBtn,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 20f, 0f),
        )
        iconPickerHit = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(50f), LayoutHelper.dp(50f))
            isClickable = false
            isFocusable = false
        }
        iconPlaceholder = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
            setImageDrawable(
                MezonIcon.imageIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
                },
            )
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f))
        }
        iconPickerHit.addView(iconPlaceholder, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        iconAvatar = com.mezon.mobile.ui.cells.CdnIconView(context, themeColors).apply {
            setSizeDp(50)
            setCircular(true)
            visibility = View.GONE
        }
        iconPickerHit.addView(iconAvatar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        iconTail.addView(iconPickerHit)
        iconRow.addView(iconTail)
        inner.addView(
            iconRow,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 10f),
        )

        val actionPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(10f)
                setColor(CreateClanRnUiTokens.menuItemBackground(themeColors))
            }
            clipToOutline = true
        }
        val permPair = buildDetailActionRow(context, getString(R.string.clan_roles_detail_permissions)) {
            presentFragment(RoleSetupPermissionsFragment.newInstanceEdit(clanId, roleId))
        }
        permActionRow = permPair.first
        permLock = permPair.second
        actionPanel.addView(permActionRow)
        actionPanel.addView(
            View(context).apply { setBackgroundColor(themeColors.borderDim) },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0f, Gravity.NO_GRAVITY, 12f, 0f, 12f, 0f)
        )
        val memPair = buildDetailActionRow(context, getString(R.string.clan_roles_detail_members)) {
            presentFragment(RoleSetupMembersFragment.newInstanceEdit(clanId, roleId))
        }
        memActionRow = memPair.first
        memLock = memPair.second
        actionPanel.addView(memActionRow)
        inner.addView(actionPanel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 10f, 0f, 0f))

        deleteBtn = TextView(context).apply {
            text = getString(R.string.clan_roles_detail_delete)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(themeColors.redStrong)
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(10f)
                setColor(CreateClanRnUiTokens.menuItemBackground(themeColors))
            }
            isClickable = true
            setOnClickListener { confirmDelete() }
        }
        inner.addView(deleteBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 10f, 0f, 0f))

        scroll.addView(inner)
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        bindRoleFromServer()
        fragmentView = root
        return root
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (clanId != 0L) {
            roleController.loadRolesForClan(clanId, force = true)
            userClanController.loadClanMembers(clanId)
        }
        updateEditableChrome()
    }

    private fun bindRoleFromServer() {
        val role = roleController.getRole(clanId, roleId) ?: return
        originName = role.title
        draftName = role.title
        originColorHex = role.colorHexRaw
        draftColorHex = role.colorHexRaw
        originIconUrl = role.iconUrl.trim()
        draftIconUrl = role.iconUrl.trim()
        actionBar?.setTitle(role.title)
        actionBar?.setSubtitle(getString(R.string.clan_roles_detail_role))
        val headerTone = ClanRolesUiTheme.secondaryCardTitleColor(themeColors)
        actionBar?.setTitleColor(headerTone)
        actionBar?.setSubtitleColor(headerTone)
        nameInput.setText(role.title)
        applyDetailEditableState(role)
        updateSaveActionState(role)
        refreshColorRowUi()
        refreshIconPreview()
        refreshRemoveIconVisibility()
    }

    private fun applyDetailEditableState(role: ClanRole) {
        val can = canEdit(role)
        logEditabilityDiag(role, can)
        nameInput.isEnabled = can && !role.isEveryoneRole()
        nameLock.visibility = if (can && !role.isEveryoneRole()) View.GONE else View.VISIBLE
        colorRow.isClickable = can
        colorRow.alpha = if (can) 1f else 0.55f
        colorLock.visibility = if (can) View.GONE else View.VISIBLE
        colorChevron.visibility = if (can) View.VISIBLE else View.GONE
        val canMem = can && !role.isEveryoneRole()
        permActionRow.isClickable = can
        permLock.visibility = if (can) View.GONE else View.VISIBLE
        memActionRow.isClickable = canMem
        memLock.visibility = if (canMem) View.GONE else View.VISIBLE
        iconRow.isClickable = can
        iconRow.alpha = if (can) 1f else 0.55f
        iconRemoveBtn.isEnabled = can
        iconRemoveBtn.visibility = if (can && draftIconUrl.isNotBlank()) View.VISIBLE else View.GONE
        iconLock.visibility = if (can) View.GONE else View.VISIBLE
        val showDelete = !role.isEveryoneRole() && can
        deleteBtn.visibility = if (showDelete) View.VISIBLE else View.GONE
        updateSaveActionState(role)
    }

    private fun updateEditableChrome() {
        val role = roleController.getRole(clanId, roleId) ?: return
        applyDetailEditableState(role)
    }

    private fun logEditabilityDiag(role: ClanRole, can: Boolean) {
        val selfId = userController.userId
        val members = userClanController.getClanMembers(clanId)
        val selfMember = members.firstOrNull { it.userId == selfId }
        val fromMember = maxSelfRoleLevelFromMemberAssignments()
        val fromApiMerged = roleController.effectiveUserMaxPermissionLevel(clanId)
        val effective = maxOf(fromApiMerged, fromMember)
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
        val perm = permissionPolicy.clanSettingsPermissionState(clanId)
        val creatorMatch = clan != null && selfId != 0L && selfId == clan.creatorId
        val coarseBypass = perm.isCanEditRole || creatorMatch
        val levelOk = effective >= role.maxLevelPermission
        Log.d(
            TAG,
            "editable=$can clanId=$clanId roleId=${role.roleId} slug=${role.slug} roleMaxLevel=${role.maxLevelPermission} isEveryone=${role.isEveryoneRole()} " +
                "selfUserId=$selfId creatorMatch=$creatorMatch permIsClanOwner=${perm.isClanOwner} permAdmin=${perm.hasAdminPermission} permManageClan=${perm.hasManageClanPermission} " +
                "permIsCanEditRole=${perm.isCanEditRole} coarseSettingsBypass=$coarseBypass memberCount=${members.size} selfInMembers=${selfMember != null} selfRoleIds=${selfMember?.roleIds} " +
                "maxFromAssignments=$fromMember ${roleController.userMaxPermissionSourceLog(clanId)} mergedApi=${fromApiMerged} effectiveMax=$effective " +
                "levelGate(${effective}>=${role.maxLevelPermission})=$levelOk",
        )
    }

    private fun maxSelfRoleLevelFromMemberAssignments(): Int {
        val selfId = userController.userId
        val members = userClanController.getClanMembers(clanId)
        val self = members.firstOrNull { it.userId == selfId } ?: return 0
        val all = roleController.getRoles(clanId) + listOfNotNull(roleController.getEveryoneRole(clanId))
        val byId = all.associateBy { it.roleId }
        return self.roleIds.mapNotNull { byId[it]?.maxLevelPermission }.maxOrNull() ?: 0
    }

    private fun effectiveSelfMaxPermissionLevel(): Int {
        val fromUserEndpoint = roleController.effectiveUserMaxPermissionLevel(clanId)
        return maxOf(fromUserEndpoint, maxSelfRoleLevelFromMemberAssignments())
    }

    private fun canEdit(role: ClanRole): Boolean {
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return false
        val perm = permissionPolicy.clanSettingsPermissionState(clanId)
        if (userController.userId != 0L && userController.userId == clan.creatorId) return true
        if (!perm.isCanEditRole) return false
        return effectiveSelfMaxPermissionLevel() >= role.maxLevelPermission
    }

    private fun draftFillColorInt(): Int {
        val raw = draftColorHex.trim().removePrefix("#")
        if (raw.isEmpty()) return Color.parseColor(DEFAULT_ROLE_COLOR_HEX)
        return runCatching { Color.parseColor("#$raw") }.getOrElse { Color.parseColor(DEFAULT_ROLE_COLOR_HEX) }
    }

    private fun refreshColorRowUi() {
        colorSwatch.background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(6f)
            setColor(draftFillColorInt())
        }
        val raw = draftColorHex.trim().removePrefix("#").lowercase()
        colorHexLabel.text = if (raw.isEmpty()) "" else "#$raw"
    }

    private fun refreshRemoveIconVisibility() {
        val role = roleController.getRole(clanId, roleId) ?: return
        val can = canEdit(role)
        iconRemoveBtn.visibility = if (can && draftIconUrl.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun showRoleColorPickerSheet() {
        val role = roleController.getRole(clanId, roleId) ?: return
        if (!canEdit(role)) return
        val ctx = getContext() ?: return
        colorPickerSheet?.dismiss()
        val sheet = RoleColorPickerBottomSheet(
            ctx,
            themeColors,
            roleColors,
            draftColorHex,
        ) { picked ->
            draftColorHex = normalizeColorForApi(picked)
            refreshColorRowUi()
            updateSaveActionState()
        }
        colorPickerSheet = sheet
        sheet.show()
    }

    private fun showRoleIconPickerFromHit() {
        val role = roleController.getRole(clanId, roleId) ?: return
        if (!canEdit(role)) return
        openRoleIconPicker()
    }

    private fun openRoleIconPicker() {
        val pick = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(getContent, getString(R.string.clan_roles_icon_picker_title)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pick))
        }
        startActivityForResult(chooser, REQ_PICK_ROLE_ICON)
    }

    private fun removeDraftRoleIcon() {
        draftIconUrl = ""
        refreshIconPreview()
        refreshRemoveIconVisibility()
        updateSaveActionState()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_ROLE_ICON || resultCode != Activity.RESULT_OK) return
        val uri = data?.clipData?.getItemAt(0)?.uri ?: data?.data ?: return
        uploadPickedRoleIcon(uri)
    }

    private fun uploadPickedRoleIcon(uri: Uri) {
        val ctx = getContext() ?: return
        val cr = ctx.contentResolver
        fragmentScope.launch(mainDispatcher) {
            iconRow.isEnabled = false
            try {
                runCatching {
                    val fileSize = withContext(ioDispatcher) { FileUtils.getPickedFileSize(cr, uri) }
                    if (fileSize >= 0 && fileSize > ROLE_ICON_MAX_BYTES) {
                        MezonToast.show(this@RoleDetailFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_roles_icon_too_large))
                        return@runCatching
                    }
                    val bytes: ByteArray = withContext(ioDispatcher) {
                        FileUtils.readContentUriBytesCapped(cr, uri, ROLE_ICON_MAX_BYTES)
                    }
                    if (bytes.isEmpty()) {
                        MezonToast.show(this@RoleDetailFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_roles_failed))
                        return@runCatching
                    }
                    val mime = cr.getType(uri) ?: "image/jpeg"
                    val url = withContext(ioDispatcher) { clansController.uploadRoleIconImage(bytes, mime) }
                    draftIconUrl = url
                    refreshIconPreview()
                    refreshRemoveIconVisibility()
                    updateSaveActionState()
                }.onFailure { e ->
                    if (e is ContentUriTooLargeException) {
                        MezonToast.show(this@RoleDetailFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_roles_icon_too_large))
                    } else {
                        MezonToast.show(this@RoleDetailFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_roles_failed))
                    }
                }
            } finally {
                iconRow.isEnabled = true
                roleController.getRole(clanId, roleId)?.let { applyDetailEditableState(it) }
            }
        }
    }

    private fun buildDetailActionRow(
        context: Context,
        title: String,
        onClick: () -> Unit,
    ): Pair<LinearLayout, ImageView> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutHelper.dp(50f)
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(14f), LayoutHelper.dp(12f), LayoutHelper.dp(14f))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(ClanRolesUiTheme.secondaryCardTitleColor(themeColors))
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL)
        )
        val lock = ImageView(context).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(
                MezonIcon.lockIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
                }
            )
        }
        row.addView(
            lock,
            LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL, 8f, 0f, 8f, 0f)
        )
        row.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(
                    MezonIcon.chevronSmallRightIcon.getDrawable(context).apply {
                        colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
                    }
                )
            },
            LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL)
        )
        return row to lock
    }

    private fun updateSaveActionState(role: ClanRole? = roleController.getRole(clanId, roleId)) {
        val canSave = role != null && canEdit(role) && hasUnsavedChanges()
        saveItem?.visibility = if (canSave) View.VISIBLE else View.GONE
        saveItem?.isEnabled = canSave
        saveItem?.alpha = if (canSave) 1f else 0.4f
        saveText?.setTextColor(if (canSave) themeColors.blurple else themeColors.textDisabled)
    }

    private fun hasUnsavedChanges(): Boolean =
        draftName.trim() != originName.trim() ||
            normHex(draftColorHex) != normHex(originColorHex) ||
            draftIconUrl.trim() != originIconUrl.trim()

    private fun promptSaveOrDiscard() {
        val ctx = getContext() ?: return
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.clan_roles_confirm_save_title))
            .setMessage(getString(R.string.clan_roles_confirm_save_message))
            .setPositiveButton(getString(R.string.clan_roles_confirm_yes)) { _, _ -> saveChanges() }
            .setNegativeButton(getString(R.string.common_close)) { _, _ -> finishFragment() }
            .show()
    }

    private fun handleCloseRequested() {
        if (!hasUnsavedChanges()) {
            finishFragment()
            return
        }
        promptSaveOrDiscard()
    }

    override fun onBackPressed(): Boolean {
        if (!hasUnsavedChanges()) return true
        promptSaveOrDiscard()
        return false
    }

    override fun onFragmentDestroy() {
        colorPickerSheet?.dismiss()
        colorPickerSheet = null
        super.onFragmentDestroy()
    }

    private fun normHex(h: String): String {
        val t = h.trim()
        if (t.isEmpty()) return ""
        return normalizeColorForApi(t)
    }

    private fun normalizeColorForApi(h: String): String {
        val clean = h.trim().removePrefix("#").lowercase()
        return if (clean.isEmpty()) "" else "#$clean"
    }

    private fun refreshIconPreview() {
        val hasIcon = draftIconUrl.isNotBlank()
        iconPlaceholder.visibility = if (hasIcon) View.GONE else View.VISIBLE
        iconAvatar.visibility = if (hasIcon) View.VISIBLE else View.GONE
        if (hasIcon) {
            iconAvatar.setImageUrl(draftIconUrl)
        } else {
            iconAvatar.setImageUrl(null)
        }
    }

    private fun saveChanges() {
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return
        val members = userClanController.getClanMembers(clanId)
        val role = roleController.getRole(clanId, roleId) ?: return
        if (!canEdit(role) || !hasUnsavedChanges()) {
            updateSaveActionState(role)
            return
        }
        val title = draftName.trim().ifEmpty { role.title }
        val color = normHex(draftColorHex)
        val trimmedIcon = draftIconUrl.trim()
        val originIconTrimmed = originIconUrl.trim()
        val roleIconForApi: String? = when {
            trimmedIcon.isNotEmpty() -> trimmedIcon
            originIconTrimmed.isNotEmpty() -> ""
            else -> null
        }
        fragmentScope.launch {
            val result = roleController.updateRoleSimple(
                clanId = clanId,
                roleId = roleId,
                title = title,
                colorHex = color.ifEmpty { normHex(role.colorHexRaw) },
                roleIcon = roleIconForApi,
                addUserIds = emptyList(),
                removeUserIds = emptyList(),
                addPermissionIds = emptyList(),
                removePermissionIds = emptyList(),
                members = members,
                clanCreatorId = clan.creatorId,
            )
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    MezonToast.show(this@RoleDetailFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.clan_roles_changes_saved))
                    finishFragment()
                } else {
                    MezonToast.show(this@RoleDetailFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_roles_failed))
                }
            }
        }
    }

    private fun confirmDelete() {
        val role = roleController.getRole(clanId, roleId) ?: return
        if (role.isEveryoneRole() || !canEdit(role)) return
        val ctx = getContext() ?: return
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.clan_roles_confirm_delete_title))
            .setMessage(getString(R.string.clan_roles_confirm_delete_message))
            .setPositiveButton(getString(R.string.clan_roles_confirm_yes)) { _, _ ->
                fragmentScope.launch {
                    val r = roleController.deleteRole(clanId, roleId, role.title)
                    withContext(Dispatchers.Main.immediate) {
                        if (r.isSuccess) {
                            finishFragment()
                        } else {
                            MezonToast.show(this@RoleDetailFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_roles_failed))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.common_close), null)
            .show()
    }
}
