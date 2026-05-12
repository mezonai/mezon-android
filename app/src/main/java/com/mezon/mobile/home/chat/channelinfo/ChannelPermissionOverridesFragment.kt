package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.MezonToast
import com.mezon.mezon.api.permissionUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port of RN `AdvancedPermissionOverrides`: load via `getPermissionByRoleIdChannelId`,
 * save via `setRoleChannelPermission` ([MezonApi.updateRoleChannelPermission]).
 */
class ChannelPermissionOverridesFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_SUBJECT_ID = "subjectId"
        private const val ARG_IS_ROLE = "isRole"
        private const val ARG_MAX_PERM = "maxPermissionId"

        /** RN-style [PermissionUpdate.type]: none / allow / deny (align with `mezon-js` if different). */
        private const val TYPE_NONE = 0
        private const val TYPE_ALLOW = 1
        private const val TYPE_DENY = 2

        fun newInstance(
            channelId: Long,
            clanId: Long,
            subjectId: Long,
            isRole: Boolean,
            maxPermissionId: Long = 0L
        ): ChannelPermissionOverridesFragment =
            ChannelPermissionOverridesFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_SUBJECT_ID, subjectId)
                    putBoolean(ARG_IS_ROLE, isRole)
                    putLong(ARG_MAX_PERM, maxPermissionId)
                }
            }
    }

    private var channelId = 0L
    private var clanId = 0L
    private var subjectId = 0L
    private var isRole = false
    private var maxPermissionIdArg = 0L

    private lateinit var mezonApi: MezonApi
    private lateinit var sessionManager: SessionManager

    private lateinit var body: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var saveButtonText: TextView
    private val rowStates = LinkedHashMap<Long, Int>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        mezonApi = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
    }

    override fun onFragmentCreate(): Boolean {
        arguments?.let {
            channelId = it.getLong(ARG_CHANNEL_ID, 0L)
            clanId = it.getLong(ARG_CLAN_ID, 0L)
            subjectId = it.getLong(ARG_SUBJECT_ID, 0L)
            isRole = it.getBoolean(ARG_IS_ROLE, false)
            maxPermissionIdArg = it.getLong(ARG_MAX_PERM, 0L)
        }
        return super.onFragmentCreate()
    }

    override fun createView(context: Context): View {
        loading = ProgressBar(context).apply { isIndeterminate = true }

        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(8), LayoutHelper.dp(16), LayoutHelper.dp(16))
        }

        val scroll = ScrollView(context).apply {
            addView(body, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        }

        saveButtonText = TextView(context).apply {
            text = getString(R.string.common_save)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(20), 0, LayoutHelper.dp(20), 0)
            setOnClickListener { save() }
        }

        actionBar = ActionBarView(context, themeColors).apply {
            occupyStatusBar = false
            setBackClickListener { finishFragment() }
            setTitle(getString(R.string.channel_permission_overrides_title))
            createMenu().addItem(1, "").also { cell ->
                cell.addView(
                    saveButtonText,
                    LayoutHelper.createFrame(
                        LayoutHelper.WRAP_CONTENT,
                        LayoutHelper.MATCH_PARENT,
                        Gravity.CENTER_VERTICAL,
                        0f, 3f, 0f, 0f
                    )
                )
            }
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        1 -> save()
                    }
                }
            })
        }

        fragmentScope.launch(Dispatchers.Main) {
            loadAndRender(context)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(
                FrameLayout(context).apply {
                    addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
                    addView(loading, FrameLayout.LayoutParams(LayoutHelper.dp(48), LayoutHelper.dp(48), Gravity.CENTER))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f)
            )
        }
        fragmentView = root
        return root
    }

    private suspend fun loadAndRender(context: Context) {
        loading.visibility = View.VISIBLE
        body.removeAllViews()
            rowStates.clear()
        val err = runCatching {
            val catalog = withContext(Dispatchers.IO) {
                sessionManager.withAutoRefresh { session ->
                    runCatching {
                        mezonApi.listPermissionsCatalog(session.apiUrl, session.token, 0L)
                    }.getOrNull()
                }
            }
            val listed = withContext(Dispatchers.IO) {
                sessionManager.withAutoRefresh { session ->
                    mezonApi.permissionRoleChannelList(
                        session.apiUrl,
                        session.token,
                        channelId,
                        roleId = if (isRole) subjectId else 0L,
                        userId = if (isRole) 0L else subjectId
                    )
                }
            }
            val activeByPid = HashMap<Long, Boolean>()
            for (pr in listed.permissionRoleChannelList) {
                activeByPid[pr.permissionId] = pr.active
            }
            val titlesById = HashMap<Long, String>()
            catalog?.permissionsList?.forEach { p ->
                titlesById[p.id] = p.title.ifBlank { p.slug }
            }
            val allIds = LinkedHashSet<Long>()
            titlesById.keys.forEach { allIds.add(it) }
            activeByPid.keys.forEach { allIds.add(it) }
            for (pid in allIds.sorted()) {
                val t = when (activeByPid[pid]) {
                    true -> TYPE_ALLOW
                    else -> TYPE_NONE
                }
                rowStates[pid] = t
            }
            withContext(Dispatchers.Main) {
                for (pid in allIds.sorted()) {
                    val title = titlesById[pid] ?: "Permission #$pid"
                    body.addView(buildRow(context, pid, title, rowStates[pid] ?: TYPE_NONE))
                }
            }
        }.exceptionOrNull()
        loading.visibility = View.GONE
        if (err != null) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permission_load_failed))
            finishFragment()
        }
    }

    private fun buildRow(context: Context, permissionId: Long, title: String, initial: Int): LinearLayout {
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(16))
        }
        block.addView(TextView(context).apply {
            text = title
            setTextColor(themeColors.onSurface)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val group = RadioGroup(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun rb(label: String, value: Int): RadioButton =
            RadioButton(context).apply {
                text = label
                tag = value
                id = View.generateViewId()
            }
        val none = rb(getString(R.string.channel_permission_none), TYPE_NONE)
        val allow = rb(getString(R.string.channel_permission_allow), TYPE_ALLOW)
        val deny = rb(getString(R.string.channel_permission_deny), TYPE_DENY)
        group.addView(none)
        group.addView(allow)
        group.addView(deny)
        when (initial) {
            TYPE_ALLOW -> group.check(allow.id)
            TYPE_DENY -> group.check(deny.id)
            else -> group.check(none.id)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val selected = group.findViewById<View>(checkedId)
            val v = (selected?.tag as? Int) ?: TYPE_NONE
            rowStates[permissionId] = v
        }
        block.addView(group, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        return block
    }

    private fun save() {
        fragmentScope.launch(Dispatchers.Main) {
            val updates = rowStates.map { (pid, type) ->
                permissionUpdate {
                    permissionId = pid
                    slug = ""
                    this.type = type
                }
            }
            val err = runCatching {
                withContext(Dispatchers.IO) {
                    sessionManager.withAutoRefresh { session ->
                        mezonApi.updateRoleChannelPermission(
                            apiUrl = session.apiUrl,
                            token = session.token,
                            channelId = channelId,
                            roleId = if (isRole) subjectId else 0L,
                            userId = if (isRole) 0L else subjectId,
                            maxPermissionId = maxPermissionIdArg,
                            permissionUpdates = updates
                        )
                    }
                }
            }.exceptionOrNull()
            if (err != null) {
                MezonToast.show(this@ChannelPermissionOverridesFragment, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permission_save_failed))
            } else {
                MezonToast.show(this@ChannelPermissionOverridesFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_permission_saved))
                finishFragment()
            }
        }
    }
}

