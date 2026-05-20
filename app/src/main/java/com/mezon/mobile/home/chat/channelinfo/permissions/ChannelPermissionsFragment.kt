package com.mezon.mobile.home.chat.channelinfo.permissions

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.CHANNEL_PERMISSION_TARGET_MEMBER
import com.mezon.mobile.home.clans.CHANNEL_PERMISSION_TARGET_ROLE
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ChannelPermissionController
import com.mezon.mobile.home.clans.ClanRole
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.settings.ClanRolesUiTheme
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.SwitchView
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAB_BASIC = 0
private const val TAB_ADVANCED = 1

class ChannelPermissionsFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_CHANNEL_PRIVATE = "channelPrivate"
        private const val VIEW_TYPE_PRIVATE = 0
        private const val VIEW_TYPE_ADD = 1
        private const val VIEW_TYPE_TITLE = 2
        private const val VIEW_TYPE_SECTION = 3
        private const val VIEW_TYPE_ROLE = 4
        private const val VIEW_TYPE_MEMBER = 5
        private const val VIEW_TYPE_EMPTY = 6

        fun newInstance(
            channelId: Long,
            channelName: String,
            clanId: Long,
            channelType: Int,
            isChannelPrivate: Boolean,
        ): ChannelPermissionsFragment =
            ChannelPermissionsFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putString(ARG_CHANNEL_NAME, channelName)
                    putLong(ARG_CLAN_ID, clanId)
                    putInt(ARG_CHANNEL_TYPE, channelType)
                    putBoolean(ARG_CHANNEL_PRIVATE, isChannelPrivate)
                }
            }
    }

    private var channelId = 0L
    private var channelName = ""
    private var clanId = 0L
    private var channelType = 0
    private var routePrivate = false
    private var activeTab = TAB_BASIC
    private lateinit var permissionController: ChannelPermissionController
    private lateinit var channelController: ChannelController
    private lateinit var userClanController: UserClanController
    private lateinit var clansController: ClansController
    private lateinit var userController: UserController
    private lateinit var contentFrame: FrameLayout
    private lateinit var tabBasic: TextView
    private lateinit var tabAdvanced: TextView

    override fun onInject(entryPoint: FragmentEntryPoint) {
        permissionController = entryPoint.channelPermissionController()
        channelController = entryPoint.channelController()
        userClanController = entryPoint.userClanController()
        clansController = entryPoint.clansController()
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME).orEmpty()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: 0
        routePrivate = arguments?.getBoolean(ARG_CHANNEL_PRIVATE) == true
        observe(NotificationCenter.channelMembersDidLoad) { _, _, args ->
            if ((args.firstOrNull() as? Long) == channelId) renderCurrentTab()
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if ((args.firstOrNull() as? Long) == clanId) renderCurrentTab()
        }
        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            if ((args.firstOrNull() as? Long) == clanId) renderCurrentTab()
        }
        observe(NotificationCenter.channelPermissionsDidLoad) { _, _, args ->
            if ((args.firstOrNull() as? Long) == channelId) renderCurrentTab()
        }
        permissionController.loadChannelPermissionData(clanId, channelId, channelType)
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)
        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.channel_permissions_title))
            setSubtitle(channelName)
            setBackButtonImage(R.drawable.ic_arrow_back)
            setBackButtonContentDescription(getString(R.string.clan_roles_back_content_desc))
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                }
            })
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f), 0)
        }
        inner.addView(buildTabs(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0f, Gravity.NO_GRAVITY, 0f, 4f, 0f, 10f))
        contentFrame = FrameLayout(context)
        inner.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        root.addView(inner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        fragmentView = root
        renderCurrentTab()
        return root
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        permissionController.loadChannelPermissionData(clanId, channelId, channelType)
        renderCurrentTab()
    }

    private fun buildTabs(context: Context): LinearLayout {
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(4f), LayoutHelper.dp(4f), LayoutHelper.dp(4f), LayoutHelper.dp(4f))
            background = rounded(themeColors.tertiary, 16f)
        }
        tabBasic = tabText(context, getString(R.string.channel_permissions_basic_view), TAB_BASIC)
        tabAdvanced = tabText(context, getString(R.string.channel_permissions_advanced_view), TAB_ADVANCED)
        tabs.addView(tabBasic, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 3f, 0f))
        tabs.addView(tabAdvanced, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, Gravity.CENTER_VERTICAL, 3f, 0f, 0f, 0f))
        updateTabs()
        return tabs
    }

    private fun tabText(context: Context, label: String, tab: Int): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                activeTab = tab
                updateTabs()
                renderCurrentTab()
            }
        }

    private fun updateTabs() {
        if (!::tabBasic.isInitialized || !::tabAdvanced.isInitialized) return
        listOf(tabBasic to TAB_BASIC, tabAdvanced to TAB_ADVANCED).forEach { (view, tab) ->
            val selected = activeTab == tab
            view.setTextColor(if (selected) 0xFFFFFFFF.toInt() else themeColors.colorText)
            view.background = rounded(if (selected) themeColors.blurple else themeColors.tertiary, 14f)
        }
    }

    private fun renderCurrentTab() {
        if (!::contentFrame.isInitialized) return
        val ctx = getContext() ?: return
        contentFrame.removeAllViews()
        contentFrame.addView(
            if (activeTab == TAB_BASIC) buildBasicView(ctx) else buildAdvancedView(ctx),
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT)
        )
    }

    private fun buildBasicView(context: Context): View {
        val rows = ArrayList<PermissionRow>()
        rows.add(PermissionRow.PrivateChannel)
        if (isChannelPrivate()) {
            rows.add(PermissionRow.AddMembers)
        }
        rows.add(PermissionRow.Title(getString(R.string.channel_permissions_who_can_access)))
        val roles = if (isChannelPrivate()) permissionController.getChannelRoles(clanId, channelId) else emptyList()
        val members = if (isChannelPrivate()) userClanController.getDirectChannelMembers(channelId) else listOfNotNull(ownerMember())
        if (roles.isNotEmpty()) {
            rows.add(PermissionRow.Section(getString(R.string.channel_permissions_roles)))
            roles.forEach { rows.add(PermissionRow.RoleItem(it, advanced = false)) }
        }
        if (members.isNotEmpty()) {
            rows.add(PermissionRow.Section(getString(R.string.channel_permissions_members)))
            members.forEach { rows.add(PermissionRow.MemberItem(it, advanced = false)) }
        }
        return buildRowsRecycler(context, rows)
    }

    private fun buildPrivateChannelCard(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f))
            background = rounded(themeColors.channelPanelBg, 14f)
        }
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        copy.addView(
            TextView(context).apply {
                text = getString(R.string.channel_permissions_private_channel)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.textStrong)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        copy.addView(
            TextView(context).apply {
                text = getString(R.string.channel_permissions_basic_description)
                textSize = 13f
                setTextColor(themeColors.colorText)
                setPadding(0, LayoutHelper.dp(4f), 0, 0)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        row.addView(copy, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0f, 0f, 12f, 0f))
        val sw = SwitchView(context, themeColors).apply {
            setChecked(isChannelPrivate(), animated = false)
            onCheckedChange = { next -> updatePrivateState(next, this) }
        }
        row.addView(sw, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        return row
    }

    private fun buildAddMembersRow(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f), LayoutHelper.dp(14f))
            background = rounded(themeColors.channelPanelBg, 14f)
            isClickable = true
            setOnClickListener { showAddSheet(context) }
        }
        row.addView(iconCircle(context, MezonIcon.circlePlusPrimaryIcon, themeColors.blurple))
        row.addView(
            TextView(context).apply {
                text = getString(R.string.channel_permissions_add_members_or_roles)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.textStrong)
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12f, 0f, 8f, 0f)
        )
        row.addView(chevron(context), LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL))
        return row
    }

    private fun buildAdvancedView(context: Context): View {
        val roles = if (isChannelPrivate()) permissionController.getChannelRoles(clanId, channelId) else emptyList()
        val members = if (isChannelPrivate()) userClanController.getDirectChannelMembers(channelId) else emptyList()
        val rows = ArrayList<PermissionRow>()
        if (roles.isEmpty() && members.isEmpty()) {
            rows.add(PermissionRow.Empty(getString(R.string.channel_permissions_role_member_empty)))
        } else {
            if (roles.isNotEmpty()) {
                rows.add(PermissionRow.Section(getString(R.string.channel_permissions_roles)))
                roles.forEach { rows.add(PermissionRow.RoleItem(it, advanced = true)) }
            }
            if (members.isNotEmpty()) {
                rows.add(PermissionRow.Section(getString(R.string.channel_permissions_members)))
                members.forEach { rows.add(PermissionRow.MemberItem(it, advanced = true)) }
            }
        }
        return buildRowsRecycler(context, rows)
    }

    private fun buildRoleItem(context: Context, role: ClanRole, advanced: Boolean): LinearLayout {
        val row = baseItemRow(context).apply {
            if (advanced) setOnClickListener {
                presentFragment(ChannelPermissionOverridesFragment.newInstance(clanId, channelId, role.roleId, CHANNEL_PERMISSION_TARGET_ROLE, role.title))
            }
        }
        row.addView(iconCircle(context, MezonIcon.bravePermission, role.color.takeIf { it != 0 } ?: themeColors.blurple))
        row.addView(
            TextView(context).apply {
                text = role.title
                textSize = 15f
                setTextColor(themeColors.textStrong)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12f, 0f, 8f, 0f)
        )
        row.addView(chip(context, getString(R.string.channel_permissions_role_badge)), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        if (advanced) {
            row.addView(chevron(context), LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL))
        } else {
            row.addView(deleteIcon(context) { removeRole(role) }, LayoutHelper.createLinear(24, 24, 0f, Gravity.CENTER_VERTICAL))
        }
        return row
    }

    private fun buildMemberItem(context: Context, member: ClanMember, advanced: Boolean): LinearLayout {
        val row = baseItemRow(context).apply {
            if (advanced) setOnClickListener {
                presentFragment(
                    ChannelPermissionOverridesFragment.newInstance(
                        clanId,
                        channelId,
                        member.userId,
                        CHANNEL_PERMISSION_TARGET_MEMBER,
                        member.displayName.ifBlank { member.username },
                    )
                )
            }
        }
        row.addView(
            AvatarView(context).apply {
                setSizeDp(34)
                setInfo(member.userId, member.displayName.ifBlank { member.username })
                setImageUrl(member.clanAvatar.ifBlank { member.avatarUrl })
            },
            LayoutHelper.createLinear(34, 34, 0f, Gravity.CENTER_VERTICAL)
        )
        row.addView(
            TextView(context).apply {
                text = member.displayName.ifBlank { member.username }
                textSize = 15f
                setTextColor(themeColors.textStrong)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12f, 0f, 8f, 0f)
        )
        if (member.userId == ownerUserId()) {
            row.addView(chip(context, getString(R.string.channel_permissions_creator_badge)), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        }
        if (advanced) {
            row.addView(chevron(context), LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL))
        } else {
            val canDelete = member.userId != ownerUserId() && member.userId != userController.userId
            row.addView(
                deleteIcon(context) { if (canDelete) removeMember(member) }.apply { alpha = if (canDelete) 1f else 0.35f },
                LayoutHelper.createLinear(24, 24, 0f, Gravity.CENTER_VERTICAL)
            )
        }
        return row
    }

    private fun buildRowsRecycler(context: Context, rows: List<PermissionRow>): RecyclerView =
        RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = PermissionRowsAdapter(context, rows)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, 0, LayoutHelper.dp(24f))
    }

    private sealed class PermissionRow {
        object PrivateChannel : PermissionRow()
        object AddMembers : PermissionRow()
        data class Title(val text: String) : PermissionRow()
        data class Section(val text: String) : PermissionRow()
        data class RoleItem(val role: ClanRole, val advanced: Boolean) : PermissionRow()
        data class MemberItem(val member: ClanMember, val advanced: Boolean) : PermissionRow()
        data class Empty(val text: String) : PermissionRow()
    }

    private inner class PermissionRowsAdapter(
        private val context: Context,
        private val rows: List<PermissionRow>,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return when (rows[position]) {
                PermissionRow.PrivateChannel -> VIEW_TYPE_PRIVATE
                PermissionRow.AddMembers -> VIEW_TYPE_ADD
                is PermissionRow.Title -> VIEW_TYPE_TITLE
                is PermissionRow.Section -> VIEW_TYPE_SECTION
                is PermissionRow.RoleItem -> VIEW_TYPE_ROLE
                is PermissionRow.MemberItem -> VIEW_TYPE_MEMBER
                is PermissionRow.Empty -> VIEW_TYPE_EMPTY
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_TYPE_TITLE,
                VIEW_TYPE_SECTION,
                VIEW_TYPE_EMPTY -> TextHolder(TextView(context).apply { layoutParams = textParams(viewType) })
                else -> ContainerHolder(FrameLayout(context).apply { layoutParams = containerParams(viewType) })
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                PermissionRow.PrivateChannel -> (holder as ContainerHolder).bind(buildPrivateChannelCard(context))
                PermissionRow.AddMembers -> (holder as ContainerHolder).bind(buildAddMembersRow(context))
                is PermissionRow.Title -> (holder as TextHolder).bind(row.text, VIEW_TYPE_TITLE)
                is PermissionRow.Section -> (holder as TextHolder).bind(row.text, VIEW_TYPE_SECTION)
                is PermissionRow.RoleItem -> (holder as ContainerHolder).bind(buildRoleItem(context, row.role, row.advanced))
                is PermissionRow.MemberItem -> (holder as ContainerHolder).bind(buildMemberItem(context, row.member, row.advanced))
                is PermissionRow.Empty -> (holder as TextHolder).bind(row.text, VIEW_TYPE_EMPTY)
            }
        }

        override fun getItemCount(): Int = rows.size

        private fun containerParams(viewType: Int): RecyclerView.LayoutParams =
            RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = when (viewType) {
                    VIEW_TYPE_PRIVATE,
                    VIEW_TYPE_ADD -> LayoutHelper.dp(16f)
                    VIEW_TYPE_ROLE,
                    VIEW_TYPE_MEMBER -> LayoutHelper.dp(8f)
                    else -> 0
                }
            }

        private fun textParams(viewType: Int): RecyclerView.LayoutParams =
            RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = if (viewType == VIEW_TYPE_TITLE) LayoutHelper.dp(8f) else 0
            }

        private inner class ContainerHolder(private val container: FrameLayout) : RecyclerView.ViewHolder(container) {
            fun bind(view: View) {
                container.removeAllViews()
                container.addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            }
        }

        private inner class TextHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
            fun bind(text: String, viewType: Int) {
                textView.text = text
                when (viewType) {
                    VIEW_TYPE_TITLE -> {
                        textView.textSize = 14f
                        textView.typeface = Typeface.DEFAULT_BOLD
                        textView.gravity = Gravity.NO_GRAVITY
                        textView.setTextColor(themeColors.textStrong)
                        textView.setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(8f))
                        textView.background = rounded(themeColors.channelPanelBg, 14f)
                    }
                    VIEW_TYPE_SECTION -> {
                        textView.textSize = 14f
                        textView.typeface = Typeface.DEFAULT_BOLD
                        textView.gravity = Gravity.NO_GRAVITY
                        textView.setTextColor(themeColors.textStrong)
                        textView.setPadding(0, LayoutHelper.dp(10f), 0, LayoutHelper.dp(8f))
                        textView.background = null
                    }
                    else -> {
                        textView.textSize = 14f
                        textView.typeface = Typeface.DEFAULT
                        textView.gravity = Gravity.CENTER
                        textView.setTextColor(themeColors.colorText)
                        textView.setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(40f), LayoutHelper.dp(16f), LayoutHelper.dp(40f))
                        textView.background = rounded(themeColors.channelPanelBg, 14f)
                    }
                }
            }
        }
    }

    private fun updatePrivateState(next: Boolean, switchView: SwitchView) {
        fragmentScope.launch {
            val result = permissionController.updateChannelPrivate(clanId, channelId, channelType, next)
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    routePrivate = next
                    MezonToast.show(this@ChannelPermissionsFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_permissions_toast_success))
                    renderCurrentTab()
                } else {
                    switchView.setChecked(!next, animated = true)
                    MezonToast.show(this@ChannelPermissionsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permissions_toast_failed))
                }
            }
        }
    }

    private fun showAddSheet(context: Context) {
        val selectedMembers = userClanController.getDirectChannelMembers(channelId).map { it.userId }.toHashSet()
        ownerUserId().takeIf { it != 0L }?.let { selectedMembers.add(it) }
        val roles = permissionController.getAvailableRoles(clanId, channelId)
        val members = userClanController.getClanMembers(clanId).filter { it.userId !in selectedMembers }
        val sheet = AddMemberOrRoleBottomSheet(context, themeColors, roles, members) { memberIds, roleIds ->
            addMembersAndRoles(memberIds, roleIds)
        }
        showDialog(sheet)
    }

    private fun addMembersAndRoles(memberIds: List<Long>, roleIds: List<Long>) {
        fragmentScope.launch {
            val memberResult = if (memberIds.isEmpty()) Result.success(Unit) else permissionController.addMembers(clanId, channelId, channelType, memberIds)
            val roleResult = if (roleIds.isEmpty()) Result.success(Unit) else permissionController.addRoles(clanId, channelId, roleIds)
            withContext(Dispatchers.Main.immediate) {
                if (memberResult.isSuccess && roleResult.isSuccess) {
                    MezonToast.show(this@ChannelPermissionsFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_permissions_toast_success))
                    permissionController.loadChannelPermissionData(clanId, channelId, channelType, force = true)
                    renderCurrentTab()
                } else {
                    MezonToast.show(this@ChannelPermissionsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permissions_toast_failed))
                }
            }
        }
    }

    private fun removeRole(role: ClanRole) {
        fragmentScope.launch {
            val result = permissionController.removeRole(clanId, channelId, role)
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    MezonToast.show(this@ChannelPermissionsFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_permissions_toast_success))
                } else {
                    MezonToast.show(this@ChannelPermissionsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permissions_toast_failed))
                }
                renderCurrentTab()
            }
        }
    }

    private fun removeMember(member: ClanMember) {
        fragmentScope.launch {
            val result = permissionController.removeMember(clanId, channelId, channelType, member.userId)
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    MezonToast.show(this@ChannelPermissionsFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_permissions_toast_success))
                } else {
                    MezonToast.show(this@ChannelPermissionsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permissions_toast_failed))
                }
                renderCurrentTab()
            }
        }
    }

    private fun isChannelPrivate(): Boolean =
        channelController.findChannelById(channelId, clanId)?.isPrivate ?: routePrivate

    private fun ownerUserId(): Long =
        clansController.clans.value.firstOrNull { it.clanId == clanId }?.creatorId ?: 0L

    private fun ownerMember(): ClanMember? {
        val ownerId = ownerUserId()
        if (ownerId == 0L) return null
        return userClanController.getClanMembers(clanId).firstOrNull { it.userId == ownerId }
    }

    private fun baseItemRow(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f))
            background = rounded(themeColors.tertiary, 10f)
            isClickable = true
        }

    private fun iconCircle(context: Context, icon: MezonIcon, color: Int): View {
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        circle.addView(
            ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                })
            },
            LayoutHelper.createFrame(18, 18, Gravity.CENTER)
        )
        return circle
    }

    private fun chip(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColors.colorText)
            setPadding(LayoutHelper.dp(8f), LayoutHelper.dp(3f), LayoutHelper.dp(8f), LayoutHelper.dp(3f))
            background = rounded(themeColors.borderDim, 10f)
        }

    private fun chevron(context: Context): ImageView =
        ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronSmallRightIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
            })
        }

    private fun deleteIcon(context: Context, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageDrawable(MezonIcon.closeIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
            })
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = LayoutHelper.dpf(radius)
        }
}
