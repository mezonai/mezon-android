package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.LayoutHelper
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
import com.mezon.mobile.ui.cells.SearchCell
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoleSetupMembersFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_ROLE_ID = "roleId"
        private const val ARG_EDIT_MODE = "editMode"

        fun newInstanceWizard(clanId: Long, roleId: Long): RoleSetupMembersFragment =
            RoleSetupMembersFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_ROLE_ID, roleId)
                    putBoolean(ARG_EDIT_MODE, false)
                }
            }

        fun newInstanceEdit(clanId: Long, roleId: Long): RoleSetupMembersFragment =
            RoleSetupMembersFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_ROLE_ID, roleId)
                    putBoolean(ARG_EDIT_MODE, true)
                }
            }
    }

    private var clanId = 0L
    private var roleId = 0L
    private var isEditMode = false
    private lateinit var roleController: RoleController
    private lateinit var userClanController: UserClanController
    private lateinit var clansController: ClansController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var userController: UserController
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: SearchCell
    private lateinit var adapter: MembersAdapter
    private var addMemberRow: View? = null
    private var filter = ""
    private val selectedUserIds = LinkedHashSet<Long>()
    private val assignedUserIds = LinkedHashSet<Long>()
    private val pendingRemoveIds = HashSet<Long>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        roleController = entryPoint.roleController()
        userClanController = entryPoint.userClanController()
        clansController = entryPoint.clansController()
        permissionPolicy = entryPoint.permissionPolicy()
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        roleId = arguments?.getLong(ARG_ROLE_ID) ?: 0L
        isEditMode = arguments?.getBoolean(ARG_EDIT_MODE, false) == true
        if (clanId != 0L) {
            userClanController.loadClanMembers(clanId)
            roleController.loadUserMaxPermissionForClan(clanId)
            roleController.loadRolesForClan(clanId)
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id != clanId || !::adapter.isInitialized) return@observe
            applyHeader(roleController.getRole(clanId, roleId))
            refreshEditControls()
            adapter.refresh()
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId && ::adapter.isInitialized) adapter.refresh()
        }
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (isEditMode && ::adapter.isInitialized) {
            reloadAssignedMembers()
        }
    }

    private fun selfMaxPermissionLevel(): Int {
        val self = userClanController.getClanMembers(clanId).firstOrNull { it.userId == userController.userId }
        val roles = roleController.getRoles(clanId) + listOfNotNull(roleController.getEveryoneRole(clanId))
        val byId = roles.associateBy { it.roleId }
        val fromAssignments = self?.roleIds?.mapNotNull { byId[it]?.maxLevelPermission }?.maxOrNull() ?: 0
        return maxOf(roleController.effectiveUserMaxPermissionLevel(clanId), fromAssignments)
    }

    private fun canEdit(): Boolean {
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return false
        val role = roleController.getRole(clanId, roleId) ?: return false
        if (userController.userId != 0L && userController.userId == clan.creatorId) return true
        if (!permissionPolicy.clanSettingsPermissionState(clanId).isCanEditRole) return false
        return selfMaxPermissionLevel() > role.maxLevelPermission
    }

    private fun reloadAssignedMembers() {
        if (!isEditMode || !::adapter.isInitialized) return
        fragmentScope.launch {
            try {
                val ids = roleController.loadAllRoleMemberUserIds(roleId)
                withContext(Dispatchers.Main.immediate) {
                    assignedUserIds.clear()
                    assignedUserIds.addAll(ids)
                    assignedUserIds.removeAll(pendingRemoveIds)
                    adapter.refresh()
                }
            } catch (e: Exception) {
            }
        }
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)
        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(if (isEditMode) R.string.clan_roles_detail_members else R.string.clan_roles_members_step_title))
            if (isEditMode) setSubtitle(getString(R.string.clan_roles_detail_role))
            setBackButtonImage(if (isEditMode) R.drawable.ic_arrow_back else R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.clan_roles_back_content_desc))
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            if (!isEditMode) {
                val actionLabel = getString(R.string.clan_roles_members_finish)
                val actionItem = createMenu().addItem(1, actionLabel)
                actionItem.addView(
                    TextView(context).apply {
                        text = actionLabel
                        setTextColor(themeColors.blurple)
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
                    },
                    LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 0f, 3f, 0f, 0f)
                )
            }
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        1 -> finishWizard()
                    }
                }
            })
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        applyHeader(roleController.getRole(clanId, roleId))

        if (!isEditMode) {
            val introBlock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), 0)
            }
            introBlock.addView(
                TextView(context).apply {
                    text = getString(R.string.clan_roles_members_heading)
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(ClanRolesUiTheme.secondaryCardTitleColor(themeColors))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL)
            )
            introBlock.addView(
                TextView(context).apply {
                    text = getString(R.string.clan_roles_members_body)
                    textSize = 14f
                    setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
                    gravity = Gravity.CENTER_HORIZONTAL
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 8f, 0f, 0f)
            )
            root.addView(introBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        searchInput = SearchCell(context, themeColors, 10f).apply {
            setPlaceholder(getString(R.string.clan_roles_members_search))
            onTextChanged = {
                filter = it.trim()
                adapter.refresh()
            }
        }
        root.addView(searchInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 16f, 10f, 16f, 0f))

        if (isEditMode) {
            val row = buildAddMemberRow(context)
            addMemberRow = row
            root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0f, Gravity.NO_GRAVITY, 16f, 12f, 16f, 0f))
            userClanController.getClanMembers(clanId)
                .filter { roleId in it.roleIds }
                .forEach { assignedUserIds.add(it.userId) }
        }

        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            setPadding(0, LayoutHelper.dp(8f), 0, LayoutHelper.dp(16f))
        }
        adapter = MembersAdapter()
        recyclerView.adapter = adapter
        root.addView(recyclerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        refreshEditControls()
        adapter.refresh()
        if (isEditMode) reloadAssignedMembers()
        fragmentView = root
        return root
    }

    private fun buildAddMemberRow(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), 0, LayoutHelper.dp(10f), 0)
            background = GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dpf(12f)
            }
            setOnClickListener { openAddMemberSheet(context) }
        }
        row.addView(
            RoleCircleGlyphView(context).apply {
                setGlyph(RoleCircleGlyphView.Glyph.PLUS, themeColors.blurple)
            },
            LayoutHelper.createLinear(22, 22, 0f, Gravity.CENTER_VERTICAL)
        )
        row.addView(
            TextView(context).apply {
                text = getString(R.string.clan_roles_members_add)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(themeColors.textStrong)
                maxLines = 1
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10f, 0f, 8f, 0f)
        )
        row.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right_24)
                setColorFilter(themeColors.textDisabled)
            },
            LayoutHelper.createLinear(20, 20, 0f, Gravity.CENTER_VERTICAL)
        )
        return row
    }

    private fun refreshEditControls() {
        addMemberRow?.visibility = if (isEditMode && canEdit()) View.VISIBLE else View.GONE
    }

    private fun applyHeader(role: ClanRole?) {
        if (!isEditMode || role == null) return
        actionBar?.setTitle(role.title)
        actionBar?.setSubtitle(getString(R.string.clan_roles_detail_role))
        actionBar?.setSubtitleColor(themeColors.textDisabled)
    }

    private fun openAddMemberSheet(context: Context) {
        if (!canEdit()) return
        val candidates = userClanController.getClanMembers(clanId).filter { it.userId !in assignedUserIds }
        val sheet = RoleAddMembersBottomSheet(context, themeColors, candidates) { ids, onFinished ->
            addMembers(ids, onFinished)
        }
        showDialog(sheet)
    }

    private suspend fun updateRoleMembers(addIds: List<Long>, removeIds: List<Long>): Result<Unit> {
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
            ?: return Result.failure(IllegalStateException("clan not loaded"))
        val role = roleController.getRole(clanId, roleId)
            ?: return Result.failure(IllegalStateException("role not loaded"))
        return roleController.updateRoleSimple(
            clanId = clanId,
            roleId = roleId,
            title = role.title,
            colorHex = role.colorHexRaw,
            roleIcon = role.iconUrl.ifBlank { null },
            addUserIds = addIds,
            removeUserIds = removeIds,
            addPermissionIds = emptyList(),
            removePermissionIds = emptyList(),
            members = userClanController.getClanMembers(clanId),
            clanCreatorId = clan.creatorId,
        )
    }

    private fun memberLabel(userId: Long): String {
        val member = userClanController.getClanMembers(clanId).firstOrNull { it.userId == userId } ?: return ""
        return member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
    }

    private fun showSuccess(message: String) {
        MezonToast.show(this, ToastOverlay.ToastType.SUCCESS, message)
    }

    private fun showError(result: Result<Unit>, title: String) {
        RoleErrorToast.show(this, title, result.exceptionOrNull())
    }

    private fun addMembers(ids: List<Long>, onFinished: (Boolean) -> Unit) {
        fragmentScope.launch {
            val result = updateRoleMembers(ids, emptyList())
            withContext(Dispatchers.Main.immediate) {
                onFinished(result.isSuccess)
                if (result.isSuccess) {
                    assignedUserIds.addAll(ids)
                    adapter.refresh()
                    val label = if (ids.size == 1) memberLabel(ids.first()) else ""
                    showSuccess(
                        if (label.isNotEmpty()) {
                            getString(R.string.clan_roles_member_added_one, label)
                        } else {
                            getString(R.string.clan_roles_members_added_many, ids.size)
                        }
                    )
                } else {
                    showError(result, getString(R.string.clan_roles_error_add_members))
                }
            }
        }
    }

    private fun removeMember(userId: Long) {
        if (!canEdit() || !pendingRemoveIds.add(userId)) return
        adapter.refresh()
        val label = memberLabel(userId)
        fragmentScope.launch {
            val result = updateRoleMembers(emptyList(), listOf(userId))
            withContext(Dispatchers.Main.immediate) {
                pendingRemoveIds.remove(userId)
                if (result.isSuccess) {
                    assignedUserIds.remove(userId)
                    adapter.refresh()
                    showSuccess(getString(R.string.clan_roles_member_removed, label))
                } else {
                    adapter.refresh()
                    showError(result, getString(R.string.clan_roles_error_remove_member, label))
                }
            }
        }
    }

    private fun finishWizard() {
        val addIds = selectedUserIds.toList()
        fragmentScope.launch {
            val result = updateRoleMembers(addIds, emptyList())
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    showSuccess(getString(R.string.clan_roles_members_added))
                    parentLayout?.closeLastFragment(animated = true)
                } else {
                    showError(result, getString(R.string.clan_roles_error_add_members))
                }
            }
        }
    }

    private data class RowState(val checked: Boolean, val removable: Boolean, val removing: Boolean = false)

    private inner class MembersAdapter : RecyclerView.Adapter<MembersAdapter.Holder>() {

        private var rows: List<ClanMember> = emptyList()
        private var states: Map<Long, RowState> = emptyMap()
        private var showEmpty = false

        init {
            setHasStableIds(true)
        }

        private fun accessoryFor(state: RowState?): RoleMemberRowCell.Accessory = when {
            !isEditMode -> RoleMemberRowCell.Accessory.Checkbox(state?.checked == true)
            state?.removing == true -> RoleMemberRowCell.Accessory.Loading
            state?.removable == true -> RoleMemberRowCell.Accessory.Remove
            else -> RoleMemberRowCell.Accessory.None
        }

        fun refresh() {
            val all = userClanController.getClanMembers(clanId)
            val source = if (isEditMode) all.filter { it.userId in assignedUserIds } else all
            val q = filter.lowercase()
            val nextRows = if (q.isEmpty()) source else source.filter { m ->
                m.displayName.lowercase().contains(q) ||
                    m.username.lowercase().contains(q) ||
                    m.clanNick.lowercase().contains(q)
            }
            val removable = isEditMode && canEdit()
            val nextStates = nextRows.associate {
                it.userId to RowState(
                    checked = it.userId in selectedUserIds,
                    removable = removable,
                    removing = it.userId in pendingRemoveIds,
                )
            }
            val nextEmpty = nextRows.isEmpty()
            if (showEmpty != nextEmpty) {
                showEmpty = nextEmpty
                rows = nextRows
                states = nextStates
                notifyDataSetChanged()
                return
            }
            val diff = DiffUtil.calculateDiff(MemberDiffCallback(rows, nextRows, states, nextStates))
            rows = nextRows
            states = nextStates
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = if (showEmpty) 1 else rows.size

        override fun getItemViewType(position: Int): Int = if (showEmpty) 1 else 0

        override fun getItemId(position: Int): Long =
            if (showEmpty) Long.MIN_VALUE else rows[position].userId

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == 1) {
                val tv = TextView(parent.context).apply {
                    text = getString(R.string.clan_roles_members_none)
                    textSize = 13f
                    setTextColor(themeColors.textDisabled)
                    gravity = Gravity.CENTER
                    setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(30f), LayoutHelper.dp(16f), LayoutHelper.dp(30f))
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                return Holder(tv, null)
            }
            val cell = RoleMemberRowCell(parent.context, themeColors)
            cell.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            val holder = Holder(cell, cell)
            cell.onAccessoryClick = {
                val pos = holder.bindingAdapterPosition
                if (isEditMode && !showEmpty) rows.getOrNull(pos)?.let { removeMember(it.userId) }
            }
            if (!isEditMode) {
                cell.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    val member = if (showEmpty) null else rows.getOrNull(pos)
                    if (member != null) {
                        val checked = !selectedUserIds.remove(member.userId)
                        if (checked) selectedUserIds.add(member.userId)
                        val state = RowState(checked = checked, removable = false)
                        states = states + (member.userId to state)
                        cell.setAccessory(accessoryFor(state))
                    }
                }
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (showEmpty) return
            val m = rows[position]
            val cell = holder.cell ?: return
            cell.bind(m, accessoryFor(states[m.userId]))
        }

        inner class Holder(itemView: View, val cell: RoleMemberRowCell?) : RecyclerView.ViewHolder(itemView)
    }

    private class MemberDiffCallback(
        private val old: List<ClanMember>,
        private val new: List<ClanMember>,
        private val oldStates: Map<Long, RowState>,
        private val newStates: Map<Long, RowState>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos].userId == new[newPos].userId
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val o = old[oldPos]
            val n = new[newPos]
            return o == n && oldStates[o.userId] == newStates[n.userId]
        }
    }
}
