package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.SearchCell
import com.mezon.mobile.ui.cells.TextCheckCell
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
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: SearchCell
    private lateinit var adapter: MembersAdapter
    private var actionItem: View? = null
    private var actionText: TextView? = null
    private var filter = ""
    private val selectedUserIds = LinkedHashSet<Long>()
    private val initialMemberIds = LinkedHashSet<Long>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        roleController = entryPoint.roleController()
        userClanController = entryPoint.userClanController()
        clansController = entryPoint.clansController()
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
            if (!hasMemberChanges()) reloadRoleMemberSelection()
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
            reloadRoleMemberSelection()
        }
    }

    private fun reloadRoleMemberSelection() {
        if (!isEditMode || !::adapter.isInitialized) return
        fragmentScope.launch {
            val ids = roleController.loadAllRoleMemberUserIds(roleId)
            withContext(Dispatchers.Main.immediate) {
                initialMemberIds.clear()
                initialMemberIds.addAll(ids)
                selectedUserIds.clear()
                selectedUserIds.addAll(ids)
                adapter.refresh()
                updateActionState()
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
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.clan_roles_back_content_desc))
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            val actionLabel = if (isEditMode) getString(R.string.clan_roles_detail_save) else getString(R.string.clan_roles_members_finish)
            val actionItem = createMenu().addItem(1, actionLabel)
            this@RoleSetupMembersFragment.actionItem = actionItem
            val actionText = TextView(context).apply {
                text = actionLabel
                setTextColor(themeColors.blurple)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
            }
            this@RoleSetupMembersFragment.actionText = actionText
            actionItem.addView(
                actionText,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, android.view.Gravity.CENTER_VERTICAL, 0f, 3f, 0f, 0f)
            )
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        1 -> applyMemberChanges()
                    }
                }
            })
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        applyHeader(roleController.getRole(clanId, roleId))

        val pad = LayoutHelper.dp(14f)
        if (!isEditMode) {
            val introBlock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, LayoutHelper.dp(8f), pad, 0)
            }
            introBlock.addView(
                android.widget.TextView(context).apply {
                    text = getString(R.string.clan_roles_members_heading)
                    textSize = 24f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(ClanRolesUiTheme.secondaryCardTitleColor(themeColors))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL)
            )
            introBlock.addView(
                android.widget.TextView(context).apply {
                    text = getString(R.string.clan_roles_members_body)
                    textSize = 14f
                    setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
                    gravity = Gravity.CENTER_HORIZONTAL
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 8f, 0f, 0f)
            )
            root.addView(introBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        searchInput = SearchCell(context, themeColors).apply {
            setPlaceholder(getString(R.string.clan_roles_members_search))
            onTextChanged = {
                filter = it.trim()
                adapter.refresh()
            }
        }
        root.addView(searchInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 14f, 8f, 14f, 0f))

        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
        }
        adapter = MembersAdapter()
        recyclerView.adapter = adapter
        root.addView(recyclerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        if (isEditMode) {
            reloadRoleMemberSelection()
        } else {
            adapter.refresh()
        }
        updateActionState()
        fragmentView = root
        return root
    }

    private fun applyHeader(role: ClanRole?) {
        if (!isEditMode || role == null) return
        actionBar?.setTitle(role.title)
        actionBar?.setSubtitle(getString(R.string.clan_roles_detail_role))
        actionBar?.setSubtitleColor(themeColors.colorText)
    }

    private fun hasMemberChanges(): Boolean = selectedUserIds != initialMemberIds

    private fun updateActionState() {
        val item = actionItem ?: return
        if (!isEditMode) {
            item.visibility = View.VISIBLE
            item.isEnabled = true
            item.alpha = 1f
            actionText?.setTextColor(themeColors.blurple)
            return
        }
        val changed = hasMemberChanges()
        item.visibility = if (changed) View.VISIBLE else View.GONE
        item.isEnabled = changed
        item.alpha = if (changed) 1f else 0.4f
        actionText?.setTextColor(if (changed) themeColors.blurple else themeColors.textDisabled)
    }

    private fun applyMemberChanges() {
        if (isEditMode && !hasMemberChanges()) {
            updateActionState()
            return
        }
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return
        val members = userClanController.getClanMembers(clanId)
        val role = roleController.getRole(clanId, roleId) ?: return
        val addIds: List<Long>
        val removeIds: List<Long>
        if (isEditMode) {
            addIds = selectedUserIds.filter { it !in initialMemberIds }
            removeIds = initialMemberIds.filter { it !in selectedUserIds }
        } else {
            addIds = selectedUserIds.toList()
            removeIds = emptyList()
        }
        fragmentScope.launch {
            val result = roleController.updateRoleSimple(
                clanId = clanId,
                roleId = roleId,
                title = role.title,
                colorHex = role.colorHexRaw,
                roleIcon = role.iconUrl.ifBlank { null },
                addUserIds = addIds,
                removeUserIds = removeIds,
                addPermissionIds = emptyList(),
                removePermissionIds = emptyList(),
                members = members,
                clanCreatorId = clan.creatorId,
            )
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    val ok = if (isEditMode) {
                        getString(R.string.clan_roles_changes_saved)
                    } else {
                        getString(R.string.clan_roles_members_added)
                    }
                    MezonToast.show(this@RoleSetupMembersFragment, ToastOverlay.ToastType.SUCCESS, ok)
                    if (isEditMode) {
                        initialMemberIds.clear()
                        initialMemberIds.addAll(selectedUserIds)
                        finishFragment()
                    } else {
                        parentLayout?.closeLastFragment(animated = true)
                    }
                } else {
                    MezonToast.show(this@RoleSetupMembersFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_roles_failed))
                }
            }
        }
    }

    private fun applyMemberChecked(userId: Long, checked: Boolean) {
        if (checked) selectedUserIds.add(userId) else selectedUserIds.remove(userId)
        updateActionState()
    }

    private inner class MembersAdapter : RecyclerView.Adapter<MembersAdapter.Holder>() {

        private var rows: List<ClanMember> = emptyList()
        private var showEmpty = false
        private var selectionSnapshot: Set<Long> = emptySet()

        init {
            setHasStableIds(true)
        }

        fun refresh() {
            val all = userClanController.getClanMembers(clanId)
            val q = filter.lowercase()
            val nextRows = if (q.isEmpty()) all else all.filter { m ->
                m.displayName.lowercase().contains(q) ||
                    m.username.lowercase().contains(q) ||
                    m.clanNick.lowercase().contains(q)
            }
            val nextEmpty = nextRows.isEmpty()
            if (showEmpty != nextEmpty) {
                showEmpty = nextEmpty
                rows = nextRows
                selectionSnapshot = selectedUserIds.toSet()
                notifyDataSetChanged()
                return
            }
            val diff = DiffUtil.calculateDiff(
                MemberDiffCallback(rows, nextRows, selectionSnapshot, selectedUserIds)
            )
            rows = nextRows
            selectionSnapshot = selectedUserIds.toSet()
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = if (showEmpty) 1 else rows.size

        override fun getItemViewType(position: Int): Int = if (showEmpty) 1 else 0

        override fun getItemId(position: Int): Long =
            if (showEmpty) Long.MIN_VALUE else rows[position].userId

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == 1) {
                val tv = android.widget.TextView(parent.context).apply {
                    text = getString(R.string.clan_roles_members_none)
                    textSize = 14f
                    setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
                    gravity = Gravity.CENTER
                    setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.MATCH_PARENT
                    )
                }
                return Holder(tv, null)
            }
            val cell = TextCheckCell(parent.context, themeColors)
            cell.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            val holder = Holder(cell, cell)
            cell.onCheckedChange = { next ->
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && !showEmpty) {
                    applyMemberChecked(rows[pos].userId, next)
                }
            }
            cell.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && !showEmpty) {
                    val next = !cell.isChecked()
                    cell.setChecked(next)
                    applyMemberChecked(rows[pos].userId, next)
                }
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (showEmpty) return
            val m = rows[position]
            val cell = holder.cell ?: return
            val label = m.clanNick.ifBlank { m.displayName.ifBlank { m.username } }
            cell.setTextAndCheck(label, m.username, selectedUserIds.contains(m.userId), position < rows.lastIndex)
            cell.setCheckEnabled(true)
        }

        inner class Holder(itemView: View, val cell: TextCheckCell?) : RecyclerView.ViewHolder(itemView)
    }

    private class MemberDiffCallback(
        private val old: List<ClanMember>,
        private val new: List<ClanMember>,
        private val oldSelected: Set<Long>,
        private val newSelected: Set<Long>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos].userId == new[newPos].userId
        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val o = old[oldPos]
            val n = new[newPos]
            return o == n &&
                (o.userId in oldSelected) == (n.userId in newSelected)
        }
    }
}
