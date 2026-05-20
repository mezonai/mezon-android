package com.mezon.mobile.home.clans.settings

import android.content.Context
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

    override fun onInject(entryPoint: FragmentEntryPoint) {
        roleController = entryPoint.roleController()
        userClanController = entryPoint.userClanController()
        clansController = entryPoint.clansController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
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
            val result = roleController.createRole(clanId, name, "", members, clan.creatorId)
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
