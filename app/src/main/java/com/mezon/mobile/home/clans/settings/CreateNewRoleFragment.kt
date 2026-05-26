package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROLE_NAME_MAX = 64
private const val DEFAULT_CREATE_ROLE_COLOR_HEX = "#99aab5"

class CreateNewRoleFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"

        fun newInstance(clanId: Long): CreateNewRoleFragment =
            CreateNewRoleFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L
    private lateinit var roleController: RoleController
    private lateinit var userClanController: UserClanController
    private lateinit var clansController: ClansController
    private lateinit var nameInput: InputCell
    private lateinit var createBtn: TextView
    private lateinit var colorRow: LinearLayout
    private lateinit var colorSwatch: View
    private lateinit var colorValue: TextView
    private var selectedColorHex = DEFAULT_CREATE_ROLE_COLOR_HEX
    private var colorPickerSheet: RoleColorPickerBottomSheet? = null
    private val roleColors = listOf(
        "#1abc9c", "#2ecc71", "#3498db", "#9b59b6", "#e91e63", "#f1c40f",
        "#e67e22", "#e74c3c", "#95a5a6", "#607d8b", "#11806a", "#1f8b4c",
        "#206694", "#71368a", "#ad1457", "#c27c0e", "#e84300", "#992d22",
        "#979c9f", "#546e7a",
    )

    override fun onInject(entryPoint: FragmentEntryPoint) {
        roleController = entryPoint.roleController()
        userClanController = entryPoint.userClanController()
        clansController = entryPoint.clansController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId != 0L) {
            userClanController.loadClanMembers(clanId)
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
            setTitle(getString(R.string.clan_roles_create_step_title))
            setBackButtonImage(R.drawable.ic_close_24)
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

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val padH = LayoutHelper.dp(14f)
        val scroll = NestedScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, 0, padH, LayoutHelper.dp(8f))
        }
        val descBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(10f))
        }
        descBlock.addView(
            TextView(context).apply {
                text = getString(R.string.clan_roles_create_heading)
                textSize = 24f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
                gravity = Gravity.CENTER_HORIZONTAL
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL)
        )
        descBlock.addView(
            TextView(context).apply {
                text = getString(R.string.clan_roles_create_body)
                textSize = 14f
                setTextColor(ClanRolesUiTheme.textOnScreenMuted(themeColors))
                gravity = Gravity.CENTER_HORIZONTAL
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL, 0f, 8f, 0f, 0f)
        )
        descBlock.addView(
            View(context).apply {
                setBackgroundColor(themeColors.borderDim)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.dp(1), 0f, Gravity.NO_GRAVITY, 0f, 10f, 0f, 0f)
        )
        inner.addView(descBlock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        nameInput = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.clan_roles_create_name_label))
            setHint(getString(R.string.clan_roles_create_name_placeholder))
            setMaxCharacter(ROLE_NAME_MAX)
        }
        inner.addView(nameInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 18f, 0f, 0f))
        colorRow = buildColorRow(context)
        inner.addView(colorRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 12f, 0f, 0f))
        scroll.addView(inner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        body.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        createBtn = TextView(context).apply {
            text = getString(R.string.clan_roles_create_button)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(8f)
                setColor(0xFF676B73.toInt())
            }
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(14f), LayoutHelper.dp(16f), LayoutHelper.dp(14f))
            isClickable = true
            setOnClickListener { attemptCreate() }
        }
        body.addView(
            createBtn,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 14f, 0f, 14f, 16f)
        )
        root.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        fragmentScope.launch(Dispatchers.Main.immediate) { refreshCreateEnabled() }
        nameInput.onTextChanged = { refreshCreateEnabledSync() }
        refreshCreateEnabledSync()
        fragmentView = root
        return root
    }

    override fun onFragmentDestroy() {
        colorPickerSheet?.dismiss()
        colorPickerSheet = null
        super.onFragmentDestroy()
    }

    private fun buildColorRow(context: Context): LinearLayout {
        colorSwatch = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(6f)
                setColor(parseColor(selectedColorHex))
            }
        }
        colorValue = TextView(context).apply {
            text = selectedColorHex
            textSize = 13f
            setTextColor(themeColors.textDisabled)
            setPadding(LayoutHelper.dp(10f), 0, 0, 0)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dpf(8f)
                setColor(com.mezon.mobile.home.clans.CreateClanRnUiTokens.menuItemBackground(themeColors))
            }
            isClickable = true
            setOnClickListener { showColorPicker(context) }
            addView(
                TextView(context).apply {
                    text = getString(R.string.clan_roles_detail_color)
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(ClanRolesUiTheme.secondaryCardTitleColor(themeColors))
                },
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL)
            )
            addView(colorSwatch, LayoutHelper.createLinear(40, 40, 0f, Gravity.CENTER_VERTICAL))
            addView(colorValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))
        }
    }

    private fun showColorPicker(context: Context) {
        colorPickerSheet?.dismiss()
        val sheet = RoleColorPickerBottomSheet(
            context,
            themeColors,
            roleColors,
            selectedColorHex,
        ) { picked ->
            selectedColorHex = normalizeColorForUi(picked)
            refreshColorUi()
        }
        colorPickerSheet = sheet
        sheet.show()
    }

    private fun refreshColorUi() {
        colorSwatch.background = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(6f)
            setColor(parseColor(selectedColorHex))
        }
        colorValue.text = selectedColorHex
    }

    private fun normalizeColorForUi(raw: String): String {
        val clean = raw.trim().removePrefix("#").lowercase()
        return if (clean.isEmpty()) DEFAULT_CREATE_ROLE_COLOR_HEX else "#$clean"
    }

    private fun parseColor(raw: String): Int {
        val clean = raw.trim().removePrefix("#")
        return runCatching { Color.parseColor("#$clean") }.getOrElse { Color.parseColor(DEFAULT_CREATE_ROLE_COLOR_HEX) }
    }

    private fun refreshCreateEnabled() {
        refreshCreateEnabledSync()
    }

    private fun refreshCreateEnabledSync() {
        val ok = nameInput.getText().trim().isNotEmpty()
        val bg = (createBtn.background as? GradientDrawable)
        if (ok) {
            bg?.setColor(themeColors.blurple)
            createBtn.setTextColor(android.graphics.Color.WHITE)
        } else {
            bg?.setColor(0xFF676B73.toInt())
            createBtn.setTextColor(android.graphics.Color.WHITE)
        }
        createBtn.isEnabled = ok
    }

    private fun attemptCreate() {
        val name = nameInput.getText().trim()
        if (name.isEmpty()) return
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return
        val members = userClanController.getClanMembers(clanId)
        fragmentScope.launch {
            val result = roleController.createRole(clanId, name, selectedColorHex, members, clan.creatorId)
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    val role = result.getOrNull()!!
                    MezonToast.show(this@CreateNewRoleFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.clan_roles_create_success, role.title))
                    parentLayout?.presentFragment(
                        RoleSetupPermissionsFragment.newInstanceWizard(clanId, role.roleId),
                        removeLast = true
                    )
                } else {
                    MezonToast.show(this@CreateNewRoleFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_roles_failed))
                }
            }
        }
    }
}
