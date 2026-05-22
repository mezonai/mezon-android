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
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ClanRole
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.PermissionCatalogEntry
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.clans.everyoneSlugForClan
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.TextCheckCell
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RoleSetupPermissionsFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_ROLE_ID = "roleId"
        private const val ARG_WIZARD = "wizard"

        fun newInstanceWizard(clanId: Long, roleId: Long): RoleSetupPermissionsFragment =
            RoleSetupPermissionsFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_ROLE_ID, roleId)
                    putBoolean(ARG_WIZARD, true)
                }
            }

        fun newInstanceEdit(clanId: Long, roleId: Long): RoleSetupPermissionsFragment =
            RoleSetupPermissionsFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_ROLE_ID, roleId)
                    putBoolean(ARG_WIZARD, false)
                }
            }
    }

    private var clanId = 0L
    private var roleId = 0L
    private var wizardMode = false

    private lateinit var roleController: RoleController
    private lateinit var userClanController: UserClanController
    private lateinit var clansController: ClansController
    private lateinit var userController: UserController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PermAdapter
    private lateinit var searchInput: InputCell
    private var catalog: List<PermissionCatalogEntry> = emptyList()
    private var selectedIds: MutableSet<Long> = LinkedHashSet()
    private var originIds: Set<Long> = emptySet()
    private var roleSnapshot: ClanRole? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        roleController = entryPoint.roleController()
        userClanController = entryPoint.userClanController()
        clansController = entryPoint.clansController()
        userController = entryPoint.userController()
        permissionPolicy = entryPoint.permissionPolicy()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        roleId = arguments?.getLong(ARG_ROLE_ID) ?: 0L
        wizardMode = arguments?.getBoolean(ARG_WIZARD) == true
        roleController.loadPermissionCatalogIfNeeded()
        if (clanId != 0L) {
            roleController.loadUserMaxPermissionForClan(clanId)
        }
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)
        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(if (wizardMode) R.string.clan_roles_perm_step_title else R.string.clan_roles_detail_permissions))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.clan_roles_back_content_desc))
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            val nextLabel = getString(if (wizardMode) R.string.clan_roles_perm_next else R.string.clan_roles_detail_save)
            val nextItem = createMenu().addItem(1, nextLabel)
            val nextText = TextView(context).apply {
                text = nextLabel
                setTextColor(themeColors.blurple)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
            }
            nextItem.addView(
                nextText,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, android.view.Gravity.CENTER_VERTICAL, 0f, 3f, 0f, 0f)
            )
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        1 -> if (wizardMode) runWizardNext() else runSaveEdit()
                    }
                }
            })
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        if (wizardMode) {
            val permIntro = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(LayoutHelper.dp(14f), 0, LayoutHelper.dp(14f), LayoutHelper.dp(20f))
            }
            permIntro.addView(
                TextView(context).apply {
                    text = getString(R.string.clan_roles_perm_heading)
                    textSize = 24f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setTextColor(ClanRolesUiTheme.secondaryCardTitleColor(themeColors))
                    setPadding(0, 0, 0, LayoutHelper.dp(10f))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
            permIntro.addView(
                View(context).apply { setBackgroundColor(themeColors.borderDim) },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(1))
            )
            root.addView(permIntro, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        searchInput = InputCell(context, themeColors).apply {
            setHint(getString(R.string.clan_roles_perm_search))
        }
        root.addView(searchInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, android.view.Gravity.NO_GRAVITY, 14f, 8f, 14f, 0f))

        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
        }
        adapter = PermAdapter()
        recyclerView.adapter = adapter
        root.addView(recyclerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        searchInput.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.setFilter(s?.toString().orEmpty())
            }
        })

        fragmentScope.launch {
            roleController.ensurePermissionCatalogLoaded()
            withContext(Dispatchers.Main.immediate) {
                catalog = roleController.getPermissionCatalog()
                roleSnapshot = roleController.getRole(clanId, roleId)
                initSelection(roleSnapshot)
                adapter.refreshAfterCatalogLoad()
            }
        }
        fragmentView = root
        return root
    }

    private fun initSelection(role: ClanRole?) {
        val fromRole = role?.rolePermissions?.filter { it.active }?.map { it.permissionId }?.toSet().orEmpty()
        originIds = fromRole
        selectedIds = LinkedHashSet(fromRole)
    }

    private fun permState(): ClanSettingsPermissionState =
        permissionPolicy.clanSettingsPermissionState(clanId)

    private fun disabledForSlug(slug: String, role: ClanRole?, perm: ClanSettingsPermissionState): Boolean {
        if (!(perm.hasAdminPermission || perm.isClanOwner || perm.hasManageClanPermission)) return true
        return when (slug) {
            "administrator" -> !perm.isClanOwner
            "manage-clan" -> !perm.isClanOwner && !perm.hasAdminPermission
            "send-message" -> role?.slug == everyoneSlugForClan(clanId)
            else -> false
        }
    }

    private fun runWizardNext() {
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return
        val members = userClanController.getClanMembers(clanId)
        val role = roleController.getRole(clanId, roleId) ?: return
        val toAdd = selectedIds.filter { it !in originIds }
        fragmentScope.launch {
            val result = roleController.updateRoleSimple(
                clanId = clanId,
                roleId = roleId,
                title = role.title,
                colorHex = role.colorHexRaw,
                roleIcon = role.iconUrl.ifBlank { null },
                addUserIds = emptyList(),
                removeUserIds = emptyList(),
                addPermissionIds = toAdd,
                removePermissionIds = emptyList(),
                members = members,
                clanCreatorId = clan.creatorId,
            )
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    parentLayout?.presentFragment(
                        RoleSetupMembersFragment.newInstanceWizard(clanId, roleId),
                        removeLast = true
                    )
                } else {
                    MezonToast.show(this@RoleSetupPermissionsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_roles_failed))
                }
            }
        }
    }

    private fun runSaveEdit() {
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return
        val members = userClanController.getClanMembers(clanId)
        val role = roleController.getRole(clanId, roleId) ?: return
        val add = selectedIds.filter { it !in originIds }
        val rem = originIds.filter { it !in selectedIds }
        fragmentScope.launch {
            val result = roleController.updateRoleSimple(
                clanId = clanId,
                roleId = roleId,
                title = role.title,
                colorHex = role.colorHexRaw,
                roleIcon = role.iconUrl.ifBlank { null },
                addUserIds = emptyList(),
                removeUserIds = emptyList(),
                addPermissionIds = add,
                removePermissionIds = rem,
                members = members,
                clanCreatorId = clan.creatorId,
            )
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    MezonToast.show(this@RoleSetupPermissionsFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.clan_roles_changes_saved))
                    finishFragment()
                } else {
                    MezonToast.show(this@RoleSetupPermissionsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_roles_failed))
                }
            }
        }
    }

    private inner class PermAdapter : RecyclerView.Adapter<PermAdapter.Holder>() {

        private var filter = ""
        private var rows: List<PermissionCatalogEntry> = emptyList()

        init {
            recomputeRows()
        }

        fun setFilter(q: String) {
            filter = q.trim().lowercase()
            recomputeRows()
            notifyDataSetChanged()
        }

        fun refreshAfterCatalogLoad() {
            recomputeRows()
            notifyDataSetChanged()
        }

        private fun recomputeRows() {
            rows = if (filter.isEmpty()) catalog
            else catalog.filter {
                it.title.lowercase().contains(filter) || it.slug.lowercase().contains(filter)
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cell = TextCheckCell(parent.context, themeColors)
            cell.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            return Holder(cell)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = rows[position]
            val role = roleSnapshot
            val perm = permState()
            val checked = selectedIds.contains(item.permissionId)
            val disabled = disabledForSlug(item.slug, role, perm)
            val title = RolePermissionLabels.titleForSlug(holder.cell.context, item.slug, item.title)
            val desc = RolePermissionLabels.descForSlug(holder.cell.context, item.slug, item.description)
            holder.cell.setTextAndCheck(title, desc, checked, position < rows.lastIndex)
            holder.cell.isClickable = !disabled
            holder.cell.alpha = if (disabled) 0.45f else 1f
            holder.cell.setOnClickListener {
                if (disabled) return@setOnClickListener
                val next = !holder.cell.isChecked()
                holder.cell.setChecked(next)
                if (next) selectedIds.add(item.permissionId) else selectedIds.remove(item.permissionId)
            }
        }

        inner class Holder(val cell: TextCheckCell) : RecyclerView.ViewHolder(cell)
    }
}
