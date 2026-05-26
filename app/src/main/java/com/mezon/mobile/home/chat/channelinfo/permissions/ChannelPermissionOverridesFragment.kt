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
import com.mezon.mobile.home.clans.CHANNEL_PERMISSION_STATUS_ALLOW
import com.mezon.mobile.home.clans.CHANNEL_PERMISSION_STATUS_DENY
import com.mezon.mobile.home.clans.CHANNEL_PERMISSION_STATUS_NONE
import com.mezon.mobile.home.clans.CHANNEL_PERMISSION_TARGET_ROLE
import com.mezon.mobile.home.clans.ChannelPermissionController
import com.mezon.mobile.home.clans.ChannelPermissionUpdate
import com.mezon.mobile.home.clans.PermissionCatalogEntry
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.clans.settings.ClanRolesUiTheme
import com.mezon.mobile.home.clans.settings.RolePermissionLabels
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MENU_SAVE = 1
private const val CHANNEL_PERMISSION_SCOPE = 2

class ChannelPermissionOverridesFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_TARGET_ID = "targetId"
        private const val ARG_TARGET_TYPE = "targetType"
        private const val ARG_TARGET_TITLE = "targetTitle"

        fun newInstance(
            clanId: Long,
            channelId: Long,
            targetId: Long,
            targetType: Int,
            targetTitle: String,
        ): ChannelPermissionOverridesFragment =
            ChannelPermissionOverridesFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_TARGET_ID, targetId)
                    putInt(ARG_TARGET_TYPE, targetType)
                    putString(ARG_TARGET_TITLE, targetTitle)
                }
            }
    }

    private var clanId = 0L
    private var channelId = 0L
    private var targetId = 0L
    private var targetType = CHANNEL_PERMISSION_TARGET_ROLE
    private var targetTitle = ""
    private lateinit var permissionController: ChannelPermissionController
    private lateinit var roleController: RoleController
    private lateinit var content: LinearLayout
    private var saveItem: View? = null
    private var catalog: List<PermissionCatalogEntry> = emptyList()
    private var originStates: Map<Long, Int> = emptyMap()
    private val currentStates = LinkedHashMap<Long, Int>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        permissionController = entryPoint.channelPermissionController()
        roleController = entryPoint.roleController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        targetId = arguments?.getLong(ARG_TARGET_ID) ?: 0L
        targetType = arguments?.getInt(ARG_TARGET_TYPE) ?: CHANNEL_PERMISSION_TARGET_ROLE
        targetTitle = arguments?.getString(ARG_TARGET_TITLE).orEmpty()
        observe(NotificationCenter.channelPermissionOverridesDidLoad) { _, _, args ->
            val eventChannelId = args.getOrNull(0) as? Long ?: return@observe
            val eventRoleId = args.getOrNull(1) as? Long ?: 0L
            val eventUserId = args.getOrNull(2) as? Long ?: 0L
            val roleId = if (targetType == CHANNEL_PERMISSION_TARGET_ROLE) targetId else 0L
            val userId = if (targetType == CHANNEL_PERMISSION_TARGET_ROLE) 0L else targetId
            if (eventChannelId == channelId && eventRoleId == roleId && eventUserId == userId && ::content.isInitialized && !hasChanges()) {
                val updates = (args.getOrNull(3) as? List<*>)
                    ?.filterIsInstance<ChannelPermissionUpdate>()
                    .orEmpty()
                if (updates.isNotEmpty() && catalog.isNotEmpty()) {
                    applyRemoteUpdates(updates)
                } else {
                    loadData()
                }
            }
        }
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)
        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.channel_permissions_overrides))
            setSubtitle(targetTitle)
            setBackButtonImage(R.drawable.ic_arrow_back)
            setBackButtonContentDescription(getString(R.string.clan_roles_back_content_desc))
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            val item = createMenu().addItem(MENU_SAVE, getString(R.string.channel_permissions_save))
            saveItem = item
            val label = TextView(context).apply {
                text = getString(R.string.channel_permissions_save)
                setTextColor(themeColors.blurple)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
            }
            item.addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 0f, 3f, 0f, 0f))
            item.visibility = View.GONE
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> handleBack()
                        MENU_SAVE -> saveChanges()
                    }
                }
            })
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
        }
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(8f), LayoutHelper.dp(12f), LayoutHelper.dp(24f))
        }
        scroll.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        fragmentView = root
        showLoading(context)
        loadData()
        return root
    }

    private fun loadData() {
        fragmentScope.launch {
            roleController.ensurePermissionCatalogLoaded()
            val roleId = if (targetType == CHANNEL_PERMISSION_TARGET_ROLE) targetId else 0L
            val userId = if (targetType == CHANNEL_PERMISSION_TARGET_ROLE) 0L else targetId
            val overrides = permissionController.fetchOverrides(channelId, roleId, userId)
            withContext(Dispatchers.Main.immediate) {
                catalog = roleController.getPermissionCatalog()
                    .filter { it.scope == CHANNEL_PERMISSION_SCOPE }
                    .sortedWith(
                        compareBy<PermissionCatalogEntry> { RolePermissionLabels.sortWeight(it.slug) }
                            .thenBy { it.level }
                            .thenBy { it.title.lowercase() }
                    )
                val mapped = overrides.getOrNull()?.associate {
                    it.permissionId to if (it.active) CHANNEL_PERMISSION_STATUS_ALLOW else CHANNEL_PERMISSION_STATUS_DENY
                }.orEmpty()
                originStates = catalog.associate { item ->
                    item.permissionId to (mapped[item.permissionId] ?: CHANNEL_PERMISSION_STATUS_NONE)
                }
                currentStates.clear()
                currentStates.putAll(originStates)
                rebuildContent()
                updateSaveVisibility()
            }
        }
    }

    private fun showLoading(context: Context) {
        content.removeAllViews()
        content.addView(
            TextView(context).apply {
                text = getString(R.string.common_loading_data)
                textSize = 14f
                setTextColor(themeColors.colorText)
                gravity = Gravity.CENTER
                setPadding(0, LayoutHelper.dp(30f), 0, LayoutHelper.dp(30f))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
    }

    private fun rebuildContent() {
        val ctx = getContext() ?: return
        content.removeAllViews()
        content.addView(
            TextView(ctx).apply {
                text = getString(R.string.channel_permissions_general_channel_permissions)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.colorText)
                setPadding(0, 0, 0, LayoutHelper.dp(10f))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        catalog.forEach { item ->
            content.addView(buildPermissionItem(ctx, item), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 10f))
        }
    }

    private fun applyRemoteUpdates(updates: List<ChannelPermissionUpdate>) {
        val nextOrigin = LinkedHashMap(originStates)
        updates.forEach { update ->
            if (!nextOrigin.containsKey(update.permissionId)) return@forEach
            nextOrigin[update.permissionId] = when (update.type) {
                CHANNEL_PERMISSION_STATUS_ALLOW -> CHANNEL_PERMISSION_STATUS_ALLOW
                CHANNEL_PERMISSION_STATUS_DENY -> CHANNEL_PERMISSION_STATUS_DENY
                else -> CHANNEL_PERMISSION_STATUS_NONE
            }
        }
        originStates = nextOrigin
        currentStates.clear()
        currentStates.putAll(originStates)
        rebuildContent()
        updateSaveVisibility()
    }

    private fun buildPermissionItem(context: Context, item: PermissionCatalogEntry): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f))
            background = GradientDrawable().apply {
                setColor(themeColors.channelPanelBg)
                cornerRadius = LayoutHelper.dpf(14f)
            }
        }
        row.addView(
            TextView(context).apply {
                text = RolePermissionLabels.titleForSlug(context, item.slug, item.title)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.textStrong)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        val desc = channelPermissionDescription(context, item)
        if (desc.isNotBlank()) {
            row.addView(
                TextView(context).apply {
                    text = desc
                    textSize = 13f
                    setTextColor(themeColors.colorText)
                    setPadding(0, LayoutHelper.dp(4f), 0, 0)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, LayoutHelper.dp(12f), 0, 0)
        }
        buttons.addView(statusButton(context, item.permissionId, CHANNEL_PERMISSION_STATUS_DENY, MezonIcon.closeIcon, themeColors.redStrong), statusLp())
        buttons.addView(statusButton(context, item.permissionId, CHANNEL_PERMISSION_STATUS_NONE, MezonIcon.slashIcon, 0xFF404249.toInt()), statusLp())
        buttons.addView(statusButton(context, item.permissionId, CHANNEL_PERMISSION_STATUS_ALLOW, MezonIcon.checkmarkSmallIcon, themeColors.connectedColor), statusLp())
        row.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        return row
    }

    private fun statusButton(context: Context, permissionId: Long, status: Int, icon: MezonIcon, color: Int): FrameLayout {
        val selected = currentStates[permissionId] == status
        val button = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(if (selected) color else themeColors.tertiary)
                cornerRadius = LayoutHelper.dpf(8f)
            }
            isClickable = true
            setOnClickListener {
                currentStates[permissionId] = status
                rebuildContent()
                updateSaveVisibility()
            }
        }
        button.addView(
            ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(if (selected) 0xFFFFFFFF.toInt() else color, PorterDuff.Mode.SRC_IN)
                })
            },
            LayoutHelper.createFrame(18, 18, Gravity.CENTER)
        )
        return button
    }

    private fun statusLp(): LinearLayout.LayoutParams =
        LayoutHelper.createLinear(0, 42, 1f, Gravity.CENTER_VERTICAL, 3f, 0f, 3f, 0f)

    private fun channelPermissionDescription(context: Context, item: PermissionCatalogEntry): String {
        return when (item.slug) {
            "view-channel" -> context.getString(R.string.channel_permissions_desc_view_channel)
            "manage-channel" -> context.getString(R.string.channel_permissions_desc_manage_channel)
            else -> RolePermissionLabels.descForSlug(context, item.slug, item.description)
        }
    }

    private fun hasChanges(): Boolean = originStates != currentStates

    private fun updateSaveVisibility() {
        saveItem?.visibility = if (hasChanges()) View.VISIBLE else View.GONE
    }

    private fun saveChanges() {
        if (catalog.isEmpty()) return
        val roleId = if (targetType == CHANNEL_PERMISSION_TARGET_ROLE) targetId else 0L
        val userId = if (targetType == CHANNEL_PERMISSION_TARGET_ROLE) 0L else targetId
        val updates = catalog.map { item ->
            ChannelPermissionUpdate(
                permissionId = item.permissionId,
                slug = item.slug,
                type = currentStates[item.permissionId] ?: CHANNEL_PERMISSION_STATUS_NONE,
            )
        }
        fragmentScope.launch {
            val result = permissionController.setOverrides(
                channelId,
                roleId,
                userId,
                permissionController.maxPermissionIdForCurrentUser(clanId),
                updates,
            )
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    originStates = LinkedHashMap(currentStates)
                    updateSaveVisibility()
                    MezonToast.show(this@ChannelPermissionOverridesFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_permissions_toast_success))
                    finishFragment()
                } else {
                    MezonToast.show(this@ChannelPermissionOverridesFragment, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permissions_toast_failed))
                }
            }
        }
    }

    private fun handleBack() {
        if (!hasChanges()) {
            finishFragment()
            return
        }
        promptUnsaved()
    }

    private fun promptUnsaved() {
        val ctx = getContext() ?: return
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.channel_permissions_unsaved_title))
            .setMessage(getString(R.string.channel_permissions_unsaved_content))
            .setPositiveButton(getString(R.string.channel_permissions_unsaved_confirm)) { _, _ -> saveChanges() }
            .setNegativeButton(getString(R.string.common_close)) { _, _ -> finishFragment() }
            .show()
    }

    override fun onBackPressed(): Boolean {
        if (!hasChanges()) return true
        promptUnsaved()
        return false
    }
}
