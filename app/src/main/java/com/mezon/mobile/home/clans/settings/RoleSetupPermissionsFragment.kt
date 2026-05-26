package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
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
    private var actionItem: View? = null
    private var actionText: TextView? = null
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
            roleController.loadRolesForClan(clanId)
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id != clanId || !::adapter.isInitialized) return@observe
            val role = roleController.getRole(clanId, roleId)
            if (roleSnapshot == null || !hasSelectionChanges()) {
                roleSnapshot = role
                initSelection(role)
                adapter.refreshAfterCatalogLoad()
            }
            applyHeader(role)
            updateActionState()
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
            if (!wizardMode) setSubtitle(getString(R.string.clan_roles_detail_role))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.clan_roles_back_content_desc))
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            val nextLabel = getString(if (wizardMode) R.string.clan_roles_skip_step else R.string.clan_roles_detail_save)
            val nextItem = createMenu().addItem(1, nextLabel)
            actionItem = nextItem
            val nextText = TextView(context).apply {
                text = nextLabel
                setTextColor(themeColors.blurple)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
            }
            actionText = nextText
            nextItem.addView(
                nextText,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 0f, 3f, 0f, 0f)
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
        roleSnapshot = roleController.getRole(clanId, roleId)
        applyHeader(roleSnapshot)

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
                    gravity = Gravity.CENTER_HORIZONTAL
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
        root.addView(searchInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 14f, 8f, 14f, 0f))

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
                catalog = orderedPermissions(roleController.getPermissionCatalog())
                roleSnapshot = roleController.getRole(clanId, roleId)
                initSelection(roleSnapshot)
                applyHeader(roleSnapshot)
                adapter.refreshAfterCatalogLoad()
                updateActionState()
            }
        }
        updateActionState()
        fragmentView = root
        return root
    }

    private fun initSelection(role: ClanRole?) {
        val fromRole = role?.rolePermissions?.filter { it.active }?.map { it.permissionId }?.toSet().orEmpty()
        originIds = fromRole
        selectedIds = LinkedHashSet(fromRole)
    }

    private fun orderedPermissions(items: List<PermissionCatalogEntry>): List<PermissionCatalogEntry> =
        items.sortedWith(
            compareBy<PermissionCatalogEntry> { RolePermissionLabels.sortWeight(it.slug) }
                .thenBy { it.level }
                .thenBy { it.title.lowercase() }
        )

    private fun applyHeader(role: ClanRole?) {
        if (wizardMode || role == null) return
        actionBar?.setTitle(role.title)
        actionBar?.setSubtitle(getString(R.string.clan_roles_detail_role))
    }

    private fun hasSelectionChanges(): Boolean = selectedIds != originIds

    private fun updateActionState() {
        val item = actionItem ?: return
        val label = actionText
        if (wizardMode) {
            val text = getString(if (selectedIds.isEmpty()) R.string.clan_roles_skip_step else R.string.clan_roles_perm_next)
            label?.text = text
            item.visibility = View.VISIBLE
            item.isEnabled = true
            item.alpha = 1f
        } else {
            val changed = hasSelectionChanges()
            item.visibility = if (changed) View.VISIBLE else View.GONE
            item.isEnabled = changed
            item.alpha = if (changed) 1f else 0.4f
        }
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
        if (toAdd.isEmpty()) {
            parentLayout?.presentFragment(
                RoleSetupMembersFragment.newInstanceWizard(clanId, roleId),
                removeLast = true
            )
            return
        }
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
        if (!hasSelectionChanges()) return
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
            setHasStableIds(true)
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
                val localTitle = RolePermissionLabels.titleForSlug(searchInput.context, it.slug, it.title)
                localTitle.lowercase().contains(filter) || it.title.lowercase().contains(filter) || it.slug.lowercase().contains(filter)
            }
        }

        override fun getItemCount(): Int = if (rows.isEmpty() && filter.isNotEmpty()) 1 else rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows.isEmpty() && filter.isNotEmpty()) 1 else 0

        override fun getItemId(position: Int): Long =
            if (getItemViewType(position) == 1) Long.MIN_VALUE else rows[position].permissionId

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == 1) {
                val tv = TextView(parent.context).apply {
                    text = getString(R.string.clan_roles_no_permissions_found)
                    textSize = 14f
                    setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
                    gravity = Gravity.CENTER
                    setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(120f), LayoutHelper.dp(16f), LayoutHelper.dp(120f))
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
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
            val item = rows[position]
            val role = roleSnapshot
            val perm = permState()
            val checked = selectedIds.contains(item.permissionId)
            val disabled = disabledForSlug(item.slug, role, perm)
            val cell = holder.cell ?: return
            cell.onCheckedChange = null
            val title = RolePermissionLabels.titleForSlug(cell.context, item.slug, item.title)
            val desc = RolePermissionLabels.descForSlug(cell.context, item.slug, item.description)
            cell.setTextAndCheck(title, desc, checked, position < rows.lastIndex)
            cell.isClickable = !disabled
            cell.alpha = if (disabled) 0.45f else 1f
            cell.setCheckEnabled(!disabled)
            val applyChecked: (Boolean) -> Unit = { next ->
                if (!disabled) {
                    if (next) selectedIds.add(item.permissionId) else selectedIds.remove(item.permissionId)
                    updateActionState()
                }
            }
            cell.onCheckedChange = { next ->
                if (disabled) {
                    cell.setChecked(checked)
                } else {
                    applyChecked(next)
                }
            }
            cell.setOnClickListener {
                if (disabled) return@setOnClickListener
                val next = !cell.isChecked()
                cell.setChecked(next)
                applyChecked(next)
            }
        }

        inner class Holder(itemView: View, val cell: TextCheckCell?) : RecyclerView.ViewHolder(itemView)
    }
}
