package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
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
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon

class ServerRolesFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val MENU_ADD_ROLE = 2
        private const val ROLE_ROW_ICON_DP = 32
        private const val ROLE_ICON_GAP_END_DP = 10
        private const val EVERYONE_LEAD_PAD_DP = 8
        private const val EVERYONE_GROUP_ICON_DP = 20
        private const val MENU_PLUS_INSET_DP = 14

        fun newInstance(clanId: Long): ServerRolesFragment =
            ServerRolesFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L
    private lateinit var roleController: RoleController
    private lateinit var userClanController: UserClanController
    private lateinit var clansController: ClansController
    private lateinit var userController: UserController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var content: LinearLayout

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
        if (clanId != 0L) {
            roleController.loadPermissionCatalogIfNeeded()
            roleController.loadUserMaxPermissionForClan(clanId, force = true)
            roleController.loadRolesForClan(clanId, force = true)
            userClanController.loadClanMembers(clanId)
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) rebuildContent()
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) rebuildContent()
        }
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (clanId != 0L) {
            roleController.loadPermissionCatalogIfNeeded()
            roleController.loadUserMaxPermissionForClan(clanId, force = true)
            roleController.loadRolesForClan(clanId, force = true)
            userClanController.loadClanMembers(clanId)
        }
        rebuildContent()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)
        val perm = permissionsState()
        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.menu_clan_roles))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.clan_roles_back_content_desc))
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            if (perm.hasAdminPermission || perm.hasManageClanPermission || perm.isClanOwner) {
                val addItem = createMenu().addItem(
                    MENU_ADD_ROLE,
                    MezonIcon.plusLargeIcon.getDrawable(context).apply {
                        colorFilter = PorterDuffColorFilter(themeColors.textStrong, PorterDuff.Mode.SRC_IN)
                    }
                )
                addItem.contentDescription = context.getString(R.string.clan_roles_add_content_desc)
                val inset = LayoutHelper.dp(MENU_PLUS_INSET_DP.toFloat())
                addItem.iconView.apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(inset, inset, inset, inset)
                }
            }
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        MENU_ADD_ROLE -> presentFragment(CreateNewRoleFragment.newInstance(clanId))
                    }
                }
            })
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val scroll = ClanSettingsUiHelpers.newMezonScrollRoot(context)
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(14f), 0, LayoutHelper.dp(14f), LayoutHelper.dp(24f))
        }
        scroll.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        rebuildContent()
        fragmentView = root
        return root
    }

    private fun permissionsState(): ClanSettingsPermissionState {
        if (clansController.clans.value.firstOrNull { it.clanId == clanId } == null) {
            return ClanSettingsPermissionState(false, false, false)
        }
        return permissionPolicy.clanSettingsPermissionState(clanId)
    }

    private fun rebuildContent() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        if (clansController.clans.value.firstOrNull { it.clanId == clanId } == null) return
        val perm = permissionPolicy.clanSettingsPermissionState(clanId)
        val isViewOnly = !(perm.hasAdminPermission || perm.hasManageClanPermission || perm.isClanOwner)
        val everyone = roleController.getEveryoneRole(clanId)
        val displayRoles = roleController.getRoles(clanId)

        content.addView(
            TextView(content.context).apply {
                text = getString(R.string.clan_roles_description)
                textSize = 14f
                setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, LayoutHelper.dp(14f), 0, LayoutHelper.dp(14f))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 0f)
        )

        if (everyone != null) {
            content.addView(
                buildEveryoneRow(content.context, everyone.roleId, isViewOnly),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 0f)
            )
        }

        content.addView(
            TextView(content.context).apply {
                text = getString(R.string.clan_roles_header_count, displayRoles.size)
                textSize = 14f
                setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 10f, 0f, 0f)
        )

        if (displayRoles.isEmpty()) {
            content.addView(
                TextView(content.context).apply {
                    text = getString(R.string.clan_roles_no_roles)
                    textSize = 14f
                    setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
                    gravity = Gravity.CENTER_HORIZONTAL
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 24f, 0f, 0f)
            )
        } else {
            val panel = LinearLayout(content.context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dpf(10f)
                    setColor(CreateClanRnUiTokens.menuItemBackground(themeColors))
                }
                clipToOutline = true
            }
            displayRoles.forEachIndexed { index, role ->
                panel.addView(
                    buildRoleRow(content.context, role, isViewOnly, index < displayRoles.lastIndex)
                )
            }
            content.addView(
                panel,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 10f, 0f, 10f)
            )
        }
    }

    private fun buildEveryoneRow(context: Context, everyoneRoleId: Long, isViewOnly: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(12f)
                setColor(CreateClanRnUiTokens.menuItemBackground(themeColors))
            }
            isClickable = true
            setOnClickListener {
                presentFragment(
                    RoleSetupPermissionsFragment.newInstanceEdit(clanId, everyoneRoleId)
                )
            }
        }
        val lead = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        }
        val circle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(EVERYONE_LEAD_PAD_DP.toFloat()), LayoutHelper.dp(EVERYONE_LEAD_PAD_DP.toFloat()), LayoutHelper.dp(EVERYONE_LEAD_PAD_DP.toFloat()), LayoutHelper.dp(EVERYONE_LEAD_PAD_DP.toFloat()))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.channelPanelBg)
            }
        }
        val gIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.groupIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        circle.addView(gIcon, LayoutHelper.createLinear(LayoutHelper.dp(EVERYONE_GROUP_ICON_DP), LayoutHelper.dp(EVERYONE_GROUP_ICON_DP)))
        lead.addView(circle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 10f, 0f))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        }
        texts.addView(
            TextView(context).apply {
                text = "@everyone"
                textSize = 16f
                setTextColor(ClanRolesUiTheme.secondaryCardTitleColor(themeColors))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        texts.addView(
            TextView(context).apply {
                text = getString(R.string.clan_roles_default_role)
                textSize = 13f
                setTextColor(themeColors.colorText)
                maxLines = 1
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        lead.addView(texts)
        row.addView(lead)
        if (isViewOnly) {
            row.addView(
                ImageView(context).apply {
                    setImageDrawable(MezonIcon.lockIcon.getDrawable(context).apply {
                        colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
                    })
                },
                LayoutHelper.createLinear(LayoutHelper.dp(16), LayoutHelper.dp(16), 0f, Gravity.CENTER_VERTICAL, 6f, 0f, 0f, 0f)
            )
        }
        row.addView(
            ImageView(context).apply {
                setImageDrawable(MezonIcon.chevronSmallRightIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
                })
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LayoutHelper.createLinear(LayoutHelper.dp(16), LayoutHelper.dp(16), 0f, Gravity.CENTER_VERTICAL)
        )
        return row
    }

    private fun buildRoleRow(
        context: Context,
        role: ClanRole,
        isViewOnly: Boolean,
        showDivider: Boolean
    ): LinearLayout {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f), LayoutHelper.dp(12f))
            isClickable = true
            setOnClickListener {
                presentFragment(RoleDetailFragment.newInstance(clanId, role.roleId))
            }
        }
        val iconSlot = FrameLayout(context)
        if (role.iconUrl.isNotBlank()) {
            val av = AvatarView(context).apply {
                setSizeDp(ROLE_ROW_ICON_DP)
                setRoundRadius(6f)
                setInfo(role.roleId, role.title)
                setImageUrl(role.iconUrl)
            }
            iconSlot.addView(av, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER))
        } else {
            val shield = ImageView(context).apply {
                val d = MezonIcon.shieldUserIcon.getDrawable(context).mutate()
                val tint = if (role.color != 0) role.color else themeColors.textDisabled
                d.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                setImageDrawable(d)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            iconSlot.addView(shield, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER))
        }
        row.addView(
            iconSlot,
            LayoutHelper.createLinear(ROLE_ROW_ICON_DP, ROLE_ROW_ICON_DP, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, ROLE_ICON_GAP_END_DP.toFloat(), 0f)
        )
        val mid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(context).apply {
                text = role.title
                textSize = 16f
                setTextColor(ClanRolesUiTheme.secondaryCardTitleColor(themeColors))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL)
        )
        if (isViewOnly) {
            titleRow.addView(
                ImageView(context).apply {
                    setImageDrawable(MezonIcon.lockIcon.getDrawable(context).apply {
                        colorFilter = PorterDuffColorFilter(themeColors.textDisabled, PorterDuff.Mode.SRC_IN)
                    })
                },
                LayoutHelper.createLinear(LayoutHelper.dp(16), LayoutHelper.dp(16), 0f, Gravity.CENTER_VERTICAL, 6f, 0f, 0f, 0f)
            )
        }
        mid.addView(titleRow)
        val sub = if (role.memberCount == 1) {
            getString(R.string.clan_roles_member_count_one)
        } else {
            getString(R.string.clan_roles_member_count, role.memberCount)
        }
        mid.addView(
            TextView(context).apply {
                text = sub
                textSize = 13f
                setTextColor(themeColors.colorText)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        row.addView(mid)
        row.addView(
            ImageView(context).apply {
                setImageDrawable(MezonIcon.chevronSmallRightIcon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
                })
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LayoutHelper.createLinear(LayoutHelper.dp(16), LayoutHelper.dp(16), 0f, Gravity.CENTER_VERTICAL)
        )
        wrap.addView(row)
        if (showDivider) {
            val div = View(context).apply {
                setBackgroundColor(themeColors.borderDim)
            }
            wrap.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(1), 0f, Gravity.NO_GRAVITY, 12f, 0f, 12f, 0f))
        }
        return wrap
    }
}
