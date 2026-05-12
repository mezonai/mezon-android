package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.TextCheckCell
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mezon.api.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port of RN `ChannelPermissionSetting`: Basic (private + access list) and Advanced (override navigation).
 */
class ChannelPermissionFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CLAN_ID = "clanId"

        fun newInstance(channelId: Long, clanId: Long): ChannelPermissionFragment =
            ChannelPermissionFragment().apply {
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
    private lateinit var mezonApi: MezonApi
    private lateinit var sessionManager: SessionManager

    private lateinit var loading: ProgressBar
    private lateinit var tabBasic: TextView
    private lateinit var tabAdvanced: TextView
    private lateinit var contentHost: LinearLayout

    private var selectedTab = 0
    private var rawRoles: List<Role> = emptyList()
    private var channelUsers: List<ChannelUserRow> = emptyList()

    private data class ChannelUserRow(val userId: Long, val displayName: String)

    override fun onInject(entryPoint: FragmentEntryPoint) {
        channelController = entryPoint.channelController()
        clansController = entryPoint.clansController()
        userController = entryPoint.userController()
        userClanController = entryPoint.userClanController()
        mezonApi = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
    }

    override fun onFragmentCreate(): Boolean {
        routeChannelId = arguments?.getLong(ARG_CHANNEL_ID, 0L) ?: 0L
        clanId = arguments?.getLong(ARG_CLAN_ID, 0L) ?: 0L
        return super.onFragmentCreate()
    }

    override fun createView(context: Context): View {
        actionBar = ActionBarView(context, themeColors).apply {
            occupyStatusBar = false
            setBackClickListener { finishFragment() }
            setTitle(getString(R.string.channel_permission_title))
            setCenterTitle(true)
        }

        tabBasic = tabChip(context, getString(R.string.channel_permission_tab_basic), true) {
            selectTab(0)
        }
        tabAdvanced = tabChip(context, getString(R.string.channel_permission_tab_advanced), false) {
            selectTab(1)
        }
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tabBasic, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            addView(tabAdvanced, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        }

        contentHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        loading = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(LayoutHelper.dp(16), LayoutHelper.dp(8), LayoutHelper.dp(16), LayoutHelper.dp(16))
                    addView(tabRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                    addView(contentHost, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(themeColors.background)
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
                    addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
                },
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT)
            )
            addView(loading, FrameLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48), Gravity.CENTER))
        }

        fragmentView = root
        fragmentScope.launch(Dispatchers.Main) {
            refreshAll()
        }
        return root
    }

    private fun tabChip(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(12), 0, LayoutHelper.dp(12))
            setOnClickListener { onClick() }
            applyTabStyle(this, selected)
        }

    private fun applyTabStyle(tv: TextView, selected: Boolean) {
        tv.setTextColor(if (selected) themeColors.primary else themeColors.onSurfaceVariant)
        tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun selectTab(i: Int) {
        selectedTab = i
        applyTabStyle(tabBasic, i == 0)
        applyTabStyle(tabAdvanced, i == 1)
        val ctx = contentHost.context
        contentHost.removeAllViews()
        if (i == 0) contentHost.addView(buildBasicTab(ctx)) else contentHost.addView(buildAdvancedTab(ctx))
    }

    private fun entity(): ClanChannelEntity? =
        channelController.findChannelById(routeChannelId, clanId)

    private fun everyoneSlug(): String = "everyone-$clanId"

    private fun clanOwnerId(): Long =
        clansController.clans.value.firstOrNull { it.clanId == clanId }?.creatorId ?: 0L

    private suspend fun loadLists() {
        val rolesResp = withContext(Dispatchers.IO) {
            sessionManager.withAutoRefresh { session ->
                mezonApi.listRoles(session.apiUrl, session.token, clanId)
            }
        }
        rawRoles = rolesResp.roles.rolesList
        val uc = withContext(Dispatchers.IO) {
            sessionManager.withAutoRefresh { session ->
                mezonApi.listChannelUsersUC(session.apiUrl, session.token, routeChannelId)
            }
        }
        val rows = ArrayList<ChannelUserRow>(uc.userIdsCount)
        for (i in 0 until uc.userIdsCount) {
            rows.add(
                ChannelUserRow(
                    userId = uc.getUserIds(i),
                    displayName = uc.displayNamesList.getOrElse(i) { "" }
                        .ifBlank { uc.usernamesList.getOrElse(i) { "" } }
                )
            )
        }
        channelUsers = rows
    }

    private fun refreshAll() {
        fragmentScope.launch(Dispatchers.Main) {
            loading.visibility = View.VISIBLE
            val err = runCatching {
                withContext(Dispatchers.IO) { loadLists() }
            }.exceptionOrNull()
            loading.visibility = View.GONE
            if (err != null) {
                MezonToast.show(this@ChannelPermissionFragment, ToastOverlay.ToastType.ERROR, err.message ?: "")
                finishFragment()
                return@launch
            }
            userClanController.loadClanMembers(clanId)
            selectTab(selectedTab)
        }
    }

    private fun buildBasicTab(context: Context): View {
        val e = entity() ?: return TextView(context).apply { text = getString(R.string.common_something_went_wrong) }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val privateCell = TextCheckCell(context, themeColors).apply {
            setTextAndCheck(
                getString(R.string.channel_permission_private_channel),
                getString(R.string.channel_permission_private_desc),
                checked = e.isPrivate,
                divider = false
            )
            onCheckedChange = { checked ->
                setChannelPrivate(checked)
            }
        }
        col.addView(privateCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        if (e.isPrivate) {
            val addBtn = Button(context).apply {
                text = getString(R.string.channel_permission_add_members_roles)
                setOnClickListener { showAddPicker(context) }
            }
            col.addView(addBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(12)
            })
        }

        col.addView(
            TextView(context).apply {
                text = getString(R.string.channel_permission_who_can_access)
                textSize = 14f
                setTextColor(themeColors.onSurfaceVariant)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(16)
            }
        )

        val ownerId = clanOwnerId()
        val selfId = userController.userId
        val membersToShow: List<ChannelUserRow> = if (e.isPrivate) {
            channelUsers
        } else {
            channelUsers.filter { it.userId == ownerId }
        }

        for (u in membersToShow) {
            col.addView(memberRow(context, u, ownerId, selfId))
        }

        val rolesToShow: List<Role> = if (!e.isPrivate) {
            emptyList()
        } else {
            val ex = everyoneSlug()
            rawRoles.filter { r ->
                r.slug != ex && r.roleChannelActive == 1 && r.channelIdsList.contains(routeChannelId)
            }
        }
        if (rolesToShow.isNotEmpty()) {
            col.addView(
                TextView(context).apply {
                    text = getString(R.string.channel_permission_roles_heading)
                    textSize = 14f
                    setTextColor(themeColors.onSurfaceVariant)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(16)
                }
            )
            for (r in rolesToShow) {
                col.addView(roleRow(context, r, ex = everyoneSlug()))
            }
        }

        return col
    }

    private fun memberRow(
        context: Context,
        u: ChannelUserRow,
        ownerId: Long,
        selfId: Long
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, LayoutHelper.dp(8), 0, LayoutHelper.dp(8))
        }
        row.addView(
            TextView(context).apply {
                text = u.displayName.ifBlank { "User ${u.userId}" }
                setTextColor(themeColors.onSurface)
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f)
        )
        val canRemove = u.userId != ownerId && u.userId != selfId
        val remove = TextView(context).apply {
            text = getString(R.string.channel_permission_remove)
            setTextColor(if (canRemove) themeColors.error else themeColors.onSurfaceVariant)
            isClickable = canRemove
            setOnClickListener {
                if (!canRemove) return@setOnClickListener
                removeMember(u.userId)
            }
        }
        row.addView(remove, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        return row
    }

    private fun roleRow(context: Context, r: Role, ex: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, LayoutHelper.dp(8), 0, LayoutHelper.dp(8))
        }
        row.addView(
            TextView(context).apply {
                text = r.title.ifBlank { r.slug }
                setTextColor(themeColors.onSurface)
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f)
        )
        val canRemove = r.slug != ex
        row.addView(
            TextView(context).apply {
                text = getString(R.string.channel_permission_remove)
                setTextColor(if (canRemove) themeColors.error else themeColors.onSurfaceVariant)
                isClickable = canRemove
                setOnClickListener {
                    if (!canRemove) return@setOnClickListener
                    removeRole(r.id)
                }
            },
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT)
        )
        return row
    }

    private fun buildAdvancedTab(context: Context): View {
        val e = entity() ?: return TextView(context).apply { text = getString(R.string.common_something_went_wrong) }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (!e.isPrivate) {
            col.addView(
                TextView(context).apply {
                    text = getString(R.string.channel_permission_advanced_need_private)
                    setTextColor(themeColors.onSurfaceVariant)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
            return col
        }
        val ex = everyoneSlug()
        val rolesOnChannel = rawRoles.filter { r ->
            r.slug != ex && r.roleChannelActive == 1 && r.channelIdsList.contains(routeChannelId)
        }
        col.addView(
            TextView(context).apply {
                text = getString(R.string.channel_permission_members_heading)
                textSize = 14f
                setTextColor(themeColors.onSurfaceVariant)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        for (u in channelUsers) {
            col.addView(advancedNavRow(context, title = u.displayName.ifBlank { "User ${u.userId}" }) {
                presentFragment(
                    ChannelPermissionOverridesFragment.newInstance(
                        channelId = routeChannelId,
                        clanId = clanId,
                        subjectId = u.userId,
                        isRole = false,
                        maxPermissionId = 0L
                    )
                )
            })
        }
        if (rolesOnChannel.isNotEmpty()) {
            col.addView(
                TextView(context).apply {
                    text = getString(R.string.channel_permission_roles_heading)
                    textSize = 14f
                    setTextColor(themeColors.onSurfaceVariant)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(12)
                }
            )
            for (r in rolesOnChannel) {
                col.addView(advancedNavRow(context, title = r.title.ifBlank { r.slug }) {
                    presentFragment(
                        ChannelPermissionOverridesFragment.newInstance(
                            channelId = routeChannelId,
                            clanId = clanId,
                            subjectId = r.id,
                            isRole = true,
                            maxPermissionId = r.maxLevelPermission.toLong()
                        )
                    )
                })
            }
        }
        return col
    }

    private fun advancedNavRow(context: Context, title: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = title
            setTextColor(themeColors.primary)
            textSize = 16f
            setPadding(0, LayoutHelper.dp(12), 0, LayoutHelper.dp(12))
            setOnClickListener { onClick() }
        }

    private fun setChannelPrivate(wantPrivate: Boolean) {
        if (entity() == null) return
        fragmentScope.launch(Dispatchers.Main) {
            loading.visibility = View.VISIBLE
            val err = runCatching {
                withContext(Dispatchers.IO) {
                    sessionManager.withAutoRefresh { session ->
                        val uid = userController.userId
                        if (uid == 0L) throw IllegalStateException("Not signed in")
                        // Same as CreateChannelFragment: 1 = private, 0 = public
                        val channelPrivate = if (wantPrivate) 1 else 0
                        mezonApi.changeChannelPrivate(
                            apiUrl = session.apiUrl,
                            token = session.token,
                            clanId = clanId,
                            channelId = routeChannelId,
                            channelPrivate = channelPrivate,
                            userIds = listOf(uid),
                            roleIds = emptyList()
                        )
                    }
                }
            }.exceptionOrNull()
            loading.visibility = View.GONE
            if (err != null) {
                val detail = err.message?.trim()?.take(180)
                val msg = if (!detail.isNullOrEmpty()) {
                    "${getString(R.string.channel_permission_private_failed)}: $detail"
                } else {
                    getString(R.string.channel_permission_private_failed)
                }
                MezonToast.show(this@ChannelPermissionFragment, ToastOverlay.ToastType.ERROR, msg)
                refreshAll()
            } else {
                channelController.setChannelPrivateFlag(clanId, routeChannelId, wantPrivate)
                MezonToast.show(this@ChannelPermissionFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_permission_private_updated))
                refreshAll()
            }
        }
    }

    private fun removeMember(userId: Long) {
        fragmentScope.launch(Dispatchers.Main) {
            loading.visibility = View.VISIBLE
            val err = runCatching {
                withContext(Dispatchers.IO) {
                    sessionManager.withAutoRefresh { session ->
                        mezonApi.removeChannelUsers(session.apiUrl, session.token, routeChannelId, listOf(userId))
                    }
                    loadLists()
                }
            }.exceptionOrNull()
            loading.visibility = View.GONE
            if (err != null) {
                MezonToast.show(this@ChannelPermissionFragment, ToastOverlay.ToastType.ERROR, err.message ?: "")
            } else {
                MezonToast.show(this@ChannelPermissionFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_permission_member_removed))
                refreshAll()
            }
        }
    }

    private fun removeRole(roleId: Long) {
        fragmentScope.launch(Dispatchers.Main) {
            loading.visibility = View.VISIBLE
            val err = runCatching {
                withContext(Dispatchers.IO) {
                    sessionManager.withAutoRefresh { session ->
                        mezonApi.deleteRoleChannelDesc(
                            session.apiUrl, session.token, clanId, routeChannelId, roleId
                        )
                    }
                    loadLists()
                }
            }.exceptionOrNull()
            loading.visibility = View.GONE
            if (err != null) {
                MezonToast.show(this@ChannelPermissionFragment, ToastOverlay.ToastType.ERROR, err.message ?: "")
            } else {
                MezonToast.show(this@ChannelPermissionFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_permission_role_removed))
                refreshAll()
            }
        }
    }

    private fun showAddPicker(context: Context) {
        val items = arrayOf(
            getString(R.string.channel_permission_add_pick_members),
            getString(R.string.channel_permission_add_pick_roles)
        )
        AlertDialog.Builder(context)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAddMembersDialog(context)
                    1 -> showAddRolesDialog(context)
                }
            }
            .show()
    }

    private fun showAddMembersDialog(context: Context) {
        val candidates = userClanController.getClanMembers(clanId).filter { m ->
            channelUsers.none { it.userId == m.userId }
        }
        if (candidates.isEmpty()) {
            MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.channel_permission_add_none_available))
            return
        }
        val labels = candidates.map { it.displayName.ifBlank { it.username } }.toTypedArray()
        val checked = BooleanArray(candidates.size)
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.channel_permission_add_pick_members))
            .setMultiChoiceItems(labels, checked) { _, index, isChecked -> checked[index] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ids = candidates.filterIndexed { i, _ -> checked[i] }.map { it.userId }
                if (ids.isEmpty()) return@setPositiveButton
                fragmentScope.launch(Dispatchers.Main) {
                    loading.visibility = View.VISIBLE
                    val err = runCatching {
                        withContext(Dispatchers.IO) {
                            sessionManager.withAutoRefresh { session ->
                                mezonApi.addChannelUsers(session.apiUrl, session.token, routeChannelId, ids)
                            }
                            loadLists()
                        }
                    }.exceptionOrNull()
                    loading.visibility = View.GONE
                    if (err != null) MezonToast.show(this@ChannelPermissionFragment, ToastOverlay.ToastType.ERROR, err.message ?: "")
                    else refreshAll()
                }
            }
            .show()
    }

    private fun showAddRolesDialog(context: Context) {
        val candidates = rawRoles.filter { r ->
            r.slug != everyoneSlug() && !r.channelIdsList.contains(routeChannelId)
        }
        if (candidates.isEmpty()) {
            MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.channel_permission_add_none_available))
            return
        }
        val labels = candidates.map { it.title.ifBlank { it.slug } }.toTypedArray()
        val checked = BooleanArray(candidates.size)
        AlertDialog.Builder(context)
            .setTitle(getString(R.string.channel_permission_add_pick_roles))
            .setMultiChoiceItems(labels, checked) { _, index, isChecked -> checked[index] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ids = candidates.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.isEmpty()) return@setPositiveButton
                fragmentScope.launch(Dispatchers.Main) {
                    loading.visibility = View.VISIBLE
                    val err = runCatching {
                        withContext(Dispatchers.IO) {
                            sessionManager.withAutoRefresh { session ->
                                mezonApi.addRolesChannelDesc(session.apiUrl, session.token, routeChannelId, ids)
                            }
                            loadLists()
                        }
                    }.exceptionOrNull()
                    loading.visibility = View.GONE
                    if (err != null) MezonToast.show(this@ChannelPermissionFragment, ToastOverlay.ToastType.ERROR, err.message ?: "")
                    else refreshAll()
                }
            }
            .show()
    }
}
