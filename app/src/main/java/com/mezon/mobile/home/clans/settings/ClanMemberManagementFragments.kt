package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ClanRole
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.TextCheckCell
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ARG_MEMBER_CLAN_ID = "clanId"
private const val ARG_MEMBER_USER_ID = "userId"
private val destructiveRed = Color.rgb(255, 59, 48)

class ClanMemberManageFragment : BaseFragment() {

    companion object {
        fun newInstance(clanId: Long, userId: Long) = ClanMemberManageFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_MEMBER_CLAN_ID, clanId)
                putLong(ARG_MEMBER_USER_ID, userId)
            }
        }
    }

    private var clanId = 0L
    private var userId = 0L
    private lateinit var userClanController: UserClanController
    private lateinit var roleController: RoleController
    private lateinit var clansController: ClansController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var userController: UserController
    private lateinit var content: LinearLayout
    private var editRoles = false
    private var roleUpdateInFlight = false
    private var hasBeenFullyVisible = false
    private val roleCells = LinkedHashMap<Long, Pair<ClanRole, TextCheckCell>>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userClanController = entryPoint.userClanController()
        roleController = entryPoint.roleController()
        clansController = entryPoint.clansController()
        permissionPolicy = entryPoint.permissionPolicy()
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_MEMBER_CLAN_ID) ?: 0L
        userId = arguments?.getLong(ARG_MEMBER_USER_ID) ?: 0L
        return clanId != 0L && userId != 0L
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)
        actionBar = simpleActionBar(context, getString(R.string.clan_member_edit_title))
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val scroll = ClanSettingsUiHelpers.newMezonScrollRoot(context)
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(16f), LayoutHelper.dp(14f), LayoutHelper.dp(32f))
        }
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        rebuildContent()
        fragmentView = root
        return root
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (hasBeenFullyVisible && ::content.isInitialized) rebuildContent()
        hasBeenFullyVisible = true
    }

    private fun rebuildContent() {
        val member = userClanController.getClanMembers(clanId).firstOrNull { it.userId == userId }
        if (member == null || !canManageMembers()) {
            finishFragment()
            return
        }
        if (content.childCount == 0) {
            content.addView(buildMemberCard(member), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        } else {
            while (content.childCount > 1) content.removeViewAt(content.childCount - 1)
        }
        content.addView(buildRolesSection(member), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 20f))
        buildActionsSection(member)?.let {
            content.addView(it, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 20f))
        }
    }

    private fun canManageMembers(): Boolean {
        return permissionPolicy.clanSettingsPermissionState(clanId).isCanEditRole
    }

    private fun buildMemberCard(member: ClanMember): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f))
            background = cardBackground()
        }
        val displayName = member.displayNameForManagement()
        row.addView(AvatarView(requireContext()).apply {
            setSizeDp(40)
            setRoundRadius(20f)
            setInfo(member.userId, displayName)
            setImageUrl(member.clanAvatar.ifBlank { member.avatarUrl })
        }, LayoutHelper.createLinear(40, 40, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 12f, 0f))
        val labels = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(managementLabel(displayName, 15f, themeColors.textStrong, true))
        labels.addView(managementLabel(member.username, 13f, themeColors.colorText, false))
        row.addView(labels, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
        return row
    }

    private fun buildRolesSection(member: ClanMember): View {
        roleCells.clear()
        val allRoles = roleController.getRoles(clanId)
            .sortedWith(compareBy<ClanRole> { it.orderRole }.thenBy { it.roleId })
        val rows = ArrayList<View>()
        if (editRoles) {
            allRoles.forEach { role ->
                val checked = role.roleId in member.roleIds
                val enabled = canEditRole(role)
                val cell = TextCheckCell(requireContext(), themeColors).apply {
                    setTextAndCheck(role.title, checked = checked)
                    setCheckEnabled(enabled)
                    setCheckInteractionEnabled(enabled && !roleUpdateInFlight)
                    isEnabled = enabled
                    alpha = if (enabled) 1f else 0.45f
                    onCheckedChange = { next -> updateMemberRole(member, role, next) }
                }
                roleCells[role.roleId] = role to cell
                rows.add(cell)
            }
        } else {
            allRoles.filter { it.roleId in member.roleIds }.forEach { role ->
                rows.add(ClanSettingsUiHelpers.buildMezonTextMenuRow(
                    requireContext(), themeColors, role.title, Runnable {}
                ).apply { isClickable = false })
            }
        }
        rows.add(ClanSettingsUiHelpers.buildMezonTextMenuRow(
            requireContext(), themeColors,
            getString(if (editRoles) R.string.common_cancel else R.string.clan_member_edit_roles),
            Runnable {
                if (!roleUpdateInFlight) {
                    editRoles = !editRoles
                    rebuildContent()
                }
            }
        ).apply {
            setBackgroundColor(themeColors.channelPanelBg)
        })
        return ClanSettingsUiHelpers.buildMezonSection(
            requireContext(), themeColors, getString(R.string.clan_member_roles), rows
        )
    }

    private fun canEditRole(role: ClanRole): Boolean {
        val permission = permissionPolicy.clanSettingsPermissionState(clanId)
        return permission.isCanEditRole &&
            (permission.isClanOwner || roleController.effectiveUserMaxPermissionLevel(clanId) > role.maxLevelPermission)
    }

    private fun updateMemberRole(member: ClanMember, role: ClanRole, add: Boolean) {
        if (roleUpdateInFlight || !canEditRole(role)) return
        roleUpdateInFlight = true
        updateRoleCellsEnabled()
        fragmentScope.launch {
            val clanOwnerId = clansController.clans.value.firstOrNull { it.clanId == clanId }?.creatorId ?: 0L
            val result = roleController.updateRoleSimple(
                clanId = clanId,
                roleId = role.roleId,
                title = null,
                colorHex = null,
                roleIcon = null,
                addUserIds = if (add) listOf(member.userId) else emptyList(),
                removeUserIds = if (add) emptyList() else listOf(member.userId),
                addPermissionIds = emptyList(),
                removePermissionIds = emptyList(),
                members = userClanController.getClanMembers(clanId),
                clanCreatorId = clanOwnerId,
            )
            withContext(Dispatchers.Main.immediate) {
                roleUpdateInFlight = false
                if (result.isFailure) {
                    roleCells[role.roleId]?.second?.setChecked(!add)
                }
                updateRoleCellsEnabled()
                MezonToast.show(
                    this@ClanMemberManageFragment,
                    if (result.isSuccess) ToastOverlay.ToastType.SUCCESS else ToastOverlay.ToastType.ERROR,
                    getString(if (result.isSuccess) R.string.clan_roles_changes_saved else R.string.clan_roles_failed)
                )
            }
        }
    }

    private fun updateRoleCellsEnabled() {
        roleCells.values.forEach { (role, cell) ->
            val editable = canEditRole(role)
            cell.setCheckEnabled(editable)
            cell.setCheckInteractionEnabled(editable && !roleUpdateInFlight)
            cell.isEnabled = editable
            cell.alpha = if (editable) 1f else 0.45f
        }
    }

    private fun buildActionsSection(member: ClanMember): View? {
        val permission = permissionPolicy.clanSettingsPermissionState(clanId)
        val clanOwnerId = clansController.clans.value.firstOrNull { it.clanId == clanId }?.creatorId ?: 0L
        val isSelf = member.userId == userController.userId
        val rows = ArrayList<View>()
        if (!isSelf && permission.isClanOwner) {
            rows.add(ClanSettingsUiHelpers.buildMezonMenuRow(
                requireContext(), themeColors, MezonIcon.transferOwnershipIcon,
                getString(R.string.clan_member_transfer_ownership), destructiveRed, destructiveRed,
                Runnable { presentFragment(ClanTransferOwnershipFragment.newInstance(clanId, member.userId)) }
            ).apply {
                setBackgroundColor(themeColors.channelPanelBg)
            })
        }
        if (!isSelf && member.userId != clanOwnerId && (permission.isClanOwner || permission.hasAdminPermission)) {
            rows.add(ClanSettingsUiHelpers.buildMezonMenuRow(
                requireContext(), themeColors, MezonIcon.removeFriend,
                getString(R.string.clan_member_kick), destructiveRed, destructiveRed,
                Runnable { presentFragment(ClanKickMemberFragment.newInstance(clanId, member.userId)) }
            ).apply {
                setBackgroundColor(themeColors.channelPanelBg)
            })
        }
        if (rows.isEmpty()) return null
        return ClanSettingsUiHelpers.buildMezonSection(
            requireContext(), themeColors, getString(R.string.clan_member_actions), rows
        )
    }

    private fun cardBackground() = GradientDrawable().apply {
        setColor(themeColors.channelPanelBg)
        cornerRadius = LayoutHelper.dpf(10f)
    }

    private fun managementLabel(textValue: String, size: Float, color: Int, bold: Boolean) = TextView(requireContext()).apply {
        text = textValue
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
    }
}

class ClanTransferOwnershipFragment : BaseFragment() {

    companion object {
        fun newInstance(clanId: Long, userId: Long) = ClanTransferOwnershipFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_MEMBER_CLAN_ID, clanId)
                putLong(ARG_MEMBER_USER_ID, userId)
            }
        }
    }

    private var clanId = 0L
    private var userId = 0L
    private var acknowledged = false
    private var submitting = false
    private lateinit var userClanController: UserClanController
    private lateinit var clansController: ClansController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var userController: UserController
    private lateinit var transferButton: TextView

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userClanController = entryPoint.userClanController()
        clansController = entryPoint.clansController()
        permissionPolicy = entryPoint.permissionPolicy()
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_MEMBER_CLAN_ID) ?: 0L
        userId = arguments?.getLong(ARG_MEMBER_USER_ID) ?: 0L
        return clanId != 0L && userId != 0L
    }

    override fun createView(context: Context): View {
        if (!permissionPolicy.clanSettingsPermissionState(clanId).isClanOwner || userId == userController.userId) {
            finishFragment()
        }
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)
        actionBar = simpleActionBar(context, getString(R.string.clan_member_transfer_ownership))
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val scroll = ClanSettingsUiHelpers.newMezonScrollRoot(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(40f), LayoutHelper.dp(16f), LayoutHelper.dp(32f))
        }
        val member = userClanController.getClanMembers(clanId).firstOrNull { it.userId == userId }
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
        if (member != null && clan != null) {
            val currentOwner = userClanController.getClanMembers(clanId)
                .firstOrNull { it.userId == clan.creatorId }
            val ownerId = currentOwner?.userId ?: userController.userId
            val ownerName = currentOwner?.displayNameForManagement()
                ?: userController.displayName.ifBlank { userController.username }
            val ownerAvatarUrl = currentOwner?.let { it.clanAvatar.ifBlank { it.avatarUrl } }
                ?: userController.avatarUrl
            content.addView(
                buildTransferParticipants(context, ownerId, ownerName, ownerAvatarUrl, member),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 96, 0f, Gravity.CENTER_HORIZONTAL)
            )
            content.addView(managementText(context, clan.clanName, 24f, themeColors.textStrong, true, Gravity.CENTER),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 16f))
            content.addView(managementText(
                context,
                getString(R.string.clan_member_transfer_warning, clan.clanName, member.displayNameForManagement()),
                15f, themeColors.textStrong, false, Gravity.CENTER
            ), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 12f))
            val acknowledgment = TextCheckCell(context, themeColors).apply {
                setTextAndCheck(
                    getString(R.string.clan_member_transfer_acknowledgment, member.displayNameForManagement()),
                    checked = false
                )
                onCheckedChange = {
                    acknowledged = it
                    updateTransferButton()
                }
            }
            content.addView(ClanSettingsUiHelpers.buildMezonSection(
                context, themeColors, getString(R.string.clan_member_transfer_ownership), listOf(acknowledgment)
            ), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 40f))
            transferButton = actionButton(context, getString(R.string.clan_member_transfer_action)) { transfer() }
            content.addView(transferButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, topMargin = 20f))
            updateTransferButton()
        }
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        fragmentView = root
        return root
    }

    private fun buildTransferParticipants(
        context: Context,
        ownerId: Long,
        ownerName: String,
        ownerAvatarUrl: String,
        target: ClanMember
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false

        addView(
            transferAvatar(context, ownerId, ownerName, ownerAvatarUrl, showCrown = false),
            LayoutHelper.createLinear(80, 96)
        )
        addView(
            managementText(context, "→", 28f, themeColors.colorText, false, Gravity.CENTER),
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, 0f, Gravity.CENTER_VERTICAL, 18f, 0f, 18f, 0f)
        )
        addView(
            transferAvatar(
                context,
                target.userId,
                target.displayNameForManagement(),
                target.clanAvatar.ifBlank { target.avatarUrl },
                showCrown = true
            ),
            LayoutHelper.createLinear(80, 96)
        )
    }

    private fun transferAvatar(
        context: Context,
        id: Long,
        name: String,
        avatarUrl: String,
        showCrown: Boolean
    ): View = FrameLayout(context).apply {
        clipChildren = false
        addView(AvatarView(context).apply {
            setSizeDp(80)
            setRoundRadius(40f)
            setInfo(id, name)
            setImageUrl(avatarUrl)
        }, LayoutHelper.createFrame(80, 80, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        if (showCrown) {
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(MezonIcon.ownerIcon.getDrawable(context, Color.rgb(255, 204, 0)))
            }, LayoutHelper.createFrame(30, 30, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun updateTransferButton() {
        if (!::transferButton.isInitialized) return
        val enabled = acknowledged && !submitting
        transferButton.isEnabled = enabled
        transferButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun transfer() {
        if (!acknowledged || submitting || userId == userController.userId ||
            !permissionPolicy.clanSettingsPermissionState(clanId).isClanOwner) return
        submitting = true
        updateTransferButton()
        fragmentScope.launch {
            val result = clansController.transferOwnership(clanId, userId)
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    MezonToast.show(this@ClanTransferOwnershipFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.clan_member_transfer_success))
                    notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
                    val activity = getParentActivity() as? MainActivity
                    if (activity != null) {
                        activity.popToMainTabsIfPresent()
                    } else {
                        finishFragment()
                    }
                } else {
                    submitting = false
                    updateTransferButton()
                    MezonToast.show(this@ClanTransferOwnershipFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_member_transfer_failed))
                }
            }
        }
    }
}

class ClanKickMemberFragment : BaseFragment() {

    companion object {
        fun newInstance(clanId: Long, userId: Long) = ClanKickMemberFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_MEMBER_CLAN_ID, clanId)
                putLong(ARG_MEMBER_USER_ID, userId)
            }
        }
    }

    private var clanId = 0L
    private var userId = 0L
    private var submitting = false
    private lateinit var userClanController: UserClanController
    private lateinit var clansController: ClansController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var userController: UserController
    private lateinit var kickButton: TextView

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userClanController = entryPoint.userClanController()
        clansController = entryPoint.clansController()
        permissionPolicy = entryPoint.permissionPolicy()
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_MEMBER_CLAN_ID) ?: 0L
        userId = arguments?.getLong(ARG_MEMBER_USER_ID) ?: 0L
        return clanId != 0L && userId != 0L
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)
        actionBar = simpleActionBar(context, getString(R.string.clan_member_kick_title))
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(20f), LayoutHelper.dp(16f), LayoutHelper.dp(32f))
        }
        val member = userClanController.getClanMembers(clanId).firstOrNull { it.userId == userId }
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
        if (member == null || clan == null || !canKick(member.userId)) {
            finishFragment()
        } else {
            content.addView(managementText(
                context,
                getString(R.string.clan_member_kick_from_clan, member.displayNameForManagement(), clan.clanName),
                14f, destructiveRed, true, Gravity.CENTER
            ).apply {
                setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f))
                background = GradientDrawable().apply {
                    setColor(themeColors.channelPanelBg)
                    cornerRadius = LayoutHelper.dpf(10f)
                }
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            content.addView(managementText(
                context,
                getString(R.string.clan_member_kick_confirmation, member.displayNameForManagement()),
                14f, themeColors.colorText, false, Gravity.START
            ), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 20f))
            content.addView(managementText(context, getString(R.string.clan_member_kick_reason), 13f, themeColors.colorText, false, Gravity.START),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 20f))
            content.addView(EditText(context).apply {
                setTextColor(themeColors.textStrong)
                setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                background = GradientDrawable().apply {
                    setColor(themeColors.channelPanelBg)
                    cornerRadius = LayoutHelper.dpf(10f)
                }
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 100, topMargin = 4f))
            kickButton = actionButton(context, getString(R.string.clan_member_kick), destructiveRed) { kick(member.userId) }
            content.addView(kickButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, topMargin = 20f))
        }
        root.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        fragmentView = root
        return root
    }

    private fun canKick(targetId: Long): Boolean {
        val permission = permissionPolicy.clanSettingsPermissionState(clanId)
        val ownerId = clansController.clans.value.firstOrNull { it.clanId == clanId }?.creatorId ?: 0L
        return targetId != userController.userId && targetId != ownerId &&
            (permission.isClanOwner || permission.hasAdminPermission)
    }

    private fun kick(targetId: Long) {
        if (submitting || !canKick(targetId)) return
        submitting = true
        kickButton.isEnabled = false
        kickButton.alpha = 0.45f
        fragmentScope.launch {
            val result = clansController.kickMember(clanId, targetId)
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    MezonToast.show(this@ClanKickMemberFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.clan_member_kick_success))
                    finishFragment()
                } else {
                    submitting = false
                    kickButton.isEnabled = true
                    kickButton.alpha = 1f
                    MezonToast.show(this@ClanKickMemberFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_member_kick_failed))
                }
            }
        }
    }
}

private fun BaseFragment.simpleActionBar(context: Context, title: String) = ActionBarView(context, themeColors).apply {
    setTitle(title)
    setBackButtonImage(R.drawable.ic_arrow_back)
    setCenterTitle(true)
    ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
    setBackClickListener { finishFragment() }
}

private fun BaseFragment.actionButton(
    context: Context,
    title: String,
    color: Int = themeColors.blurple,
    action: () -> Unit
) = TextView(context).apply {
    text = title
    gravity = Gravity.CENTER
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    typeface = Typeface.DEFAULT_BOLD
    background = GradientDrawable().apply {
        setColor(color)
        cornerRadius = LayoutHelper.dpf(10f)
    }
    setOnClickListener { action() }
}

private fun managementText(
    context: Context,
    value: String,
    size: Float,
    color: Int,
    bold: Boolean,
    textGravity: Int
) = TextView(context).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    gravity = textGravity
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

private fun ClanMember.displayNameForManagement(): String =
    clanNick.ifBlank { displayName.ifBlank { username } }
