package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.InputCell
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
    private lateinit var searchInput: InputCell
    private lateinit var adapter: MembersAdapter
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
        if (clanId != 0L) userClanController.loadClanMembers(clanId)
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)
        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(
                if (isEditMode) getString(R.string.clan_roles_detail_members)
                else getString(R.string.clan_roles_members_step_title)
            )
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.clan_roles_back_content_desc))
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            val actionLabel = if (isEditMode) getString(R.string.clan_roles_detail_save) else getString(R.string.clan_roles_members_finish)
            val actionItem = createMenu().addItem(1, actionLabel)
            val actionText = TextView(context).apply {
                text = actionLabel
                setTextColor(themeColors.blurple)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
            }
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
                    setTextColor(ClanRolesUiTheme.secondaryCardTitleColor(themeColors))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
            introBlock.addView(
                android.widget.TextView(context).apply {
                    text = getString(R.string.clan_roles_members_body)
                    textSize = 14f
                    setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, android.view.Gravity.NO_GRAVITY, 0f, 8f, 0f, 0f)
            )
            root.addView(introBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        searchInput = InputCell(context, themeColors).apply {
            setHint(getString(R.string.clan_roles_members_search))
        }
        root.addView(searchInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, android.view.Gravity.NO_GRAVITY, 14f, 8f, 14f, 0f))

        searchInput.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString().orEmpty().trim()
                adapter.refresh()
            }
        })

        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
        }
        adapter = MembersAdapter()
        recyclerView.adapter = adapter
        root.addView(recyclerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        if (isEditMode) {
            fragmentScope.launch {
                val ids = roleController.loadAllRoleMemberUserIds(roleId)
                withContext(Dispatchers.Main) {
                    initialMemberIds.clear()
                    initialMemberIds.addAll(ids)
                    selectedUserIds.clear()
                    selectedUserIds.addAll(ids)
                    adapter.refresh()
                }
            }
        }
        adapter.refresh()
        fragmentView = root
        return root
    }

    private fun applyMemberChanges() {
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

    private inner class MembersAdapter : RecyclerView.Adapter<MembersAdapter.Holder>() {

        private var rows: List<ClanMember> = emptyList()

        fun refresh() {
            val all = userClanController.getClanMembers(clanId)
            val q = filter.lowercase()
            rows = if (q.isEmpty()) all else all.filter { m ->
                m.displayName.lowercase().contains(q) ||
                    m.username.lowercase().contains(q) ||
                    m.clanNick.lowercase().contains(q)
            }
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = if (rows.isEmpty()) 1 else rows.size

        override fun getItemViewType(position: Int): Int = if (rows.isEmpty()) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == 1) {
                val tv = android.widget.TextView(parent.context).apply {
                    text = getString(R.string.clan_roles_members_none)
                    textSize = 14f
                    setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
                    setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(24f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
                }
                return Holder(tv, null)
            }
            val cell = TextCheckCell(parent.context, themeColors)
            cell.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            return Holder(cell, cell)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (rows.isEmpty()) return
            val m = rows[position]
            val cell = holder.cell ?: return
            val label = m.clanNick.ifBlank { m.displayName.ifBlank { m.username } }
            val checked = selectedUserIds.contains(m.userId)
            cell.setTextAndCheck(label, m.username, checked, position < rows.lastIndex)
            cell.setOnClickListener {
                val next = !cell.isChecked()
                cell.setChecked(next)
                if (next) selectedUserIds.add(m.userId) else selectedUserIds.remove(m.userId)
            }
        }

        inner class Holder(itemView: View, val cell: TextCheckCell?) : RecyclerView.ViewHolder(itemView)
    }
}
