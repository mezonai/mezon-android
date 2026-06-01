package com.mezon.mobile.home.clans.settings
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.ColorUtils
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.AuditLogMemberRoleChannelVerb
import com.mezon.mobile.util.DateTimeUtil
import com.mezon.mobile.util.AuditLogWire
import com.mezon.mobile.util.auditLogActionDisplayLabel
import com.mezon.mobile.util.auditLogMemberRoleChannelVerb
import com.mezon.mezon.api.AuditLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class AuditLogSettingFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"

        fun newInstance(clanId: Long): AuditLogSettingFragment =
            AuditLogSettingFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher
    private lateinit var userClanController: UserClanController
    private lateinit var channelController: ChannelController

    private lateinit var recyclerView: RecyclerListView
    private lateinit var adapter: AuditLogAdapter
    private lateinit var filterUserTag: TextView
    private lateinit var filterActionTag: TextView
    private lateinit var dateValueView: TextView
    private lateinit var emptyBlock: LinearLayout
    private lateinit var listFrame: FrameLayout
    private var loadingBar: ProgressBar? = null

    private val logItems = ArrayList<AuditLog>()
    private val selectedDay = Calendar.getInstance()
    private var clanMembersByUserId = emptyMap<Long, ClanMember>()
    private var clanMembersSortedForPicker = emptyList<ClanMember>()
    private var filterUserId: Long = 0L
    private var filterUserLabel: String = ""
    private var filterActionWire: String = AuditLogWire.ALL_ACTION

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
        userClanController = entryPoint.userClanController()
        channelController = entryPoint.channelController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        filterUserLabel = getString(R.string.audit_log_filter_all_users)
        if (clanId != 0L) {
            channelController.loadChannelsForClan(clanId, force = true)
            userClanController.loadClanMembers(clanId)
            refreshClanMembersSnapshot()
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId) refreshClanMembersSnapshot()
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView != null) {
                fragmentView?.setBackgroundColor(themeColors.background)
                adapter.notifyDataSetChanged()
            }
        }
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (clanId != 0L) {
            channelController.loadChannelsForClan(clanId, force = false)
            userClanController.loadClanMembers(clanId)
            refreshClanMembersSnapshot()
        }
        reloadAuditLog()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.menu_clan_audit_log))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.common_close))
            setCenterTitle(true)
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                }
            })
        }
        checkNotNull(actionBar).backButton.apply {
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                width = LayoutHelper.dp(48f)
                height = LayoutHelper.dp(48f)
            }
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val pad = LayoutHelper.dp(16f)
        root.setPadding(pad, LayoutHelper.dp(8f), pad, pad)

        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
                setColor(themeColors.surfaceVariant)
            }
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
            setOnClickListener { showFilterMenu() }
        }
        filterUserTag = TextView(context).apply {
            textSize = 13f
            setTextColor(CreateClanRnUiTokens.menuText(themeColors))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        filterActionTag = TextView(context).apply {
            textSize = 13f
            setTextColor(CreateClanRnUiTokens.menuText(themeColors))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val tagWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(filterUserTag)
            addView(
                filterActionTag,
                LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(4f)
                }
            )
        }
        filterRow.addView(tagWrap, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
        val chev = ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronSmallRightIcon.getDrawable(context))
            setColorFilter(CreateClanRnUiTokens.textDisabled(themeColors), PorterDuff.Mode.SRC_IN)
        }
        filterRow.addView(chev, LayoutHelper.createLinear(18, 18, 0f, Gravity.CENTER_VERTICAL))
        root.addView(filterRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 12f))

        val dateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
                setColor(themeColors.surfaceVariant)
            }
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
            setOnClickListener { showDatePicker() }
        }
        dateRow.addView(
            TextView(context).apply {
                text = context.getString(R.string.audit_log_date_label)
                textSize = 14f
                setTextColor(CreateClanRnUiTokens.textStrong(themeColors))
            },
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL)
        )
        dateValueView = TextView(context).apply {
            textSize = 14f
            setTextColor(themeColors.primary)
            gravity = Gravity.END
        }
        dateRow.addView(
            dateValueView,
            LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        root.addView(dateRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 12f))

        listFrame = FrameLayout(context)
        root.addView(listFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        adapter = AuditLogAdapter(context, themeColors, ::resolveMember, ::resolveChannelLabel) { timeSeconds ->
            if (timeSeconds <= 0) return@AuditLogAdapter ""
            else DateTimeUtil.formatEpochSeconds(timeSeconds, DateTimeUtil.Patterns.DAY_MONTH_YEAR_COMMA_TIME)
        }
        recyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            adapter = this@AuditLogSettingFragment.adapter
        }
        listFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        emptyBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(LayoutHelper.dp(24f), LayoutHelper.dp(40f), LayoutHelper.dp(24f), LayoutHelper.dp(24f))
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.audit_log_empty_title)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(CreateClanRnUiTokens.textStrong(themeColors))
                    gravity = Gravity.CENTER
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.audit_log_empty_description)
                    textSize = 14f
                    setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
                    gravity = Gravity.CENTER
                    setPadding(0, LayoutHelper.dp(10f), 0, 0)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
        }
        listFrame.addView(emptyBlock, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        val progress = ProgressBar(context).apply {
            visibility = View.GONE
        }
        loadingBar = progress
        listFrame.addView(progress, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        fragmentView = root
        refreshFilterTags()
        refreshDateLabel()
        return root
    }

    private fun refreshFilterTags() {
        if (!::filterUserTag.isInitialized) return
        filterUserTag.text = filterUserLabel
        filterActionTag.text = actionDisplayLabel(filterActionWire)
    }

    private fun actionDisplayLabel(wire: String): String {
        val c = getContext() ?: return wire
        return auditLogActionDisplayLabel(c, wire)
    }

    private fun refreshDateLabel() {
        if (!::dateValueView.isInitialized) return
        val ctx = getContext() ?: return
        dateValueView.text = android.text.format.DateFormat.getMediumDateFormat(ctx).format(selectedDay.timeInMillis)
    }

    private fun wireDateForApi(): String {
        val d = selectedDay.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val m = (selectedDay.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val y = selectedDay.get(Calendar.YEAR)
        return "$d-$m-$y"
    }

    private fun showDatePicker() {
        val ctx = getContext() ?: return
        val dialog = DatePickerDialog(
            ctx,
            { _, y, m, d ->
                selectedDay.set(Calendar.YEAR, y)
                selectedDay.set(Calendar.MONTH, m)
                selectedDay.set(Calendar.DAY_OF_MONTH, d)
                refreshDateLabel()
                reloadAuditLog()
            },
            selectedDay.get(Calendar.YEAR),
            selectedDay.get(Calendar.MONTH),
            selectedDay.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    private fun showFilterMenu() {
        val ctx = getContext() ?: return
        val listMaxH = (AndroidUtilities.displaySize.y * 0.4f).toInt()
            .coerceIn(LayoutHelper.dp(180), LayoutHelper.dp(400))

        val userRows = ArrayList<PickerRow>()
        userRows.add(PickerRow("", getString(R.string.audit_log_filter_all_users)))
        for (m in clanMembersSortedForPicker) {
            userRows.add(PickerRow(m.userId.toString(), displayNameForMember(m)))
        }
        val actionRows = AuditLogWire.ACTION_OPTIONS.map { wire ->
            PickerRow(wire, auditLogActionDisplayLabel(ctx, wire))
        }
        val userInitial = if (filterUserId == 0L) "" else filterUserId.toString()

        lateinit var userListAdapter: AuditLogPickerListAdapter
        userListAdapter = AuditLogPickerListAdapter(userRows, userInitial, themeColors) { row ->
            filterUserId = row.value.toLongOrNull() ?: 0L
            filterUserLabel = userRows.firstOrNull { it.value == row.value }?.label
                ?: getString(R.string.audit_log_filter_all_users)
            refreshFilterTags()
            reloadAuditLog()
            userListAdapter.setSelectedValue(row.value)
        }

        lateinit var actionListAdapter: AuditLogPickerListAdapter
        actionListAdapter = AuditLogPickerListAdapter(actionRows, filterActionWire, themeColors) { row ->
            filterActionWire = row.value
            refreshFilterTags()
            reloadAuditLog()
            actionListAdapter.setSelectedValue(row.value)
        }

        fun searchField(hintRes: Int, onQuery: (String) -> Unit): EditText {
            return EditText(ctx).apply {
                hint = ctx.getString(hintRes)
                setHintTextColor(themeColors.getColor(ThemeColors.key_dialogIcon))
                setTextColor(themeColors.getColor(ThemeColors.key_dialogTextBlack))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(10f).toFloat()
                    setColor(themeColors.surfaceVariant)
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        onQuery(s?.toString().orEmpty())
                    }
                })
            }
        }

        val userSearch = searchField(R.string.audit_log_search_user) { userListAdapter.setQuery(it) }
        val userList = RecyclerListView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = userListAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(themeColors.surface)
        }
        val userPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                userSearch,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(10)
                }
            )
            addView(
                userList,
                LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, listMaxH).apply {
                    topMargin = LayoutHelper.dp(8)
                }
            )
        }

        val actionSearch = searchField(R.string.audit_log_search_action) { actionListAdapter.setQuery(it) }
        val actionList = RecyclerListView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = actionListAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(themeColors.surface)
        }
        val actionPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                actionSearch,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(10)
                }
            )
            addView(
                actionList,
                LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, listMaxH).apply {
                    topMargin = LayoutHelper.dp(8)
                }
            )
        }

        val flip = FrameLayout(ctx)
        flip.addView(userPanel)
        flip.addView(actionPanel)
        actionPanel.visibility = View.GONE

        val tabPadH = LayoutHelper.dp(8)
        val tabPadV = LayoutHelper.dp(12)
        val tabUser = TextView(ctx).apply {
            text = getString(R.string.audit_log_filter_by_user)
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(tabPadH, tabPadV, tabPadH, tabPadV)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
        }
        val tabAction = TextView(ctx).apply {
            text = getString(R.string.audit_log_filter_by_action)
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(tabPadH, tabPadV, tabPadH, tabPadV)
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
        }
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
                setColor(themeColors.surfaceVariant)
            }
            addView(tabUser, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
            addView(tabAction, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
        }

        fun paintTab(userSelected: Boolean) {
            val sel = themeColors.getColor(ThemeColors.key_dialogButton)
            val unsel = themeColors.getColor(ThemeColors.key_text_secondary)
            tabUser.setTextColor(if (userSelected) sel else unsel)
            tabAction.setTextColor(if (!userSelected) sel else unsel)
            tabUser.typeface = if (userSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tabAction.typeface = if (!userSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            userPanel.visibility = if (userSelected) View.VISIBLE else View.GONE
            actionPanel.visibility = if (userSelected) View.GONE else View.VISIBLE
        }
        paintTab(true)

        tabUser.setOnClickListener { paintTab(true) }
        tabAction.setOnClickListener { paintTab(false) }

        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            addView(tabBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(flip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.audit_log_filter_dialog_title))
            .setView(outer, LayoutHelper.WRAP_CONTENT)
            .setPositiveButton(getString(R.string.common_ok), null)
            .show()
    }

    private fun displayNameForMember(m: ClanMember): String =
        m.clanNick.ifBlank { m.displayName.ifBlank { m.username } }

    private fun refreshClanMembersSnapshot() {
        if (clanId == 0L) {
            clanMembersByUserId = emptyMap()
            clanMembersSortedForPicker = emptyList()
            return
        }
        val members = userClanController.getClanMembers(clanId)
        clanMembersByUserId = members.associateBy { it.userId }
        clanMembersSortedForPicker = members.sortedBy {
            displayNameForMember(it).lowercase(Locale.getDefault())
        }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun resolveMember(userId: Long): ClanMember? = clanMembersByUserId[userId]

    private fun resolveChannelLabel(channelId: Long, protoLabel: String): String {
        if (protoLabel.isNotEmpty()) return protoLabel
        return channelController.findChannelById(channelId, clanId)?.channelLabel.orEmpty()
    }

    private fun reloadAuditLog() {
        if (clanId == 0L || !::adapter.isInitialized) return
        loadingBar?.visibility = View.VISIBLE
        fragmentScope.launch(mainDispatcher) {
            try {
                runCatching {
                    val dateStr = wireDateForApi()
                    val actionApi = if (filterActionWire == AuditLogWire.ALL_ACTION) "" else filterActionWire
                    val uid = filterUserId
                    withContext(ioDispatcher) {
                        sessionManager.withAutoRefresh { session ->
                            api.listAuditLog(session.apiUrl, session.token, clanId, uid, actionApi, dateStr)
                        }
                    }
                }.onSuccess { resp ->
                    logItems.clear()
                    logItems.addAll(resp.logsList)
                    if (logItems.isEmpty()) {
                        adapter.submit(emptyList())
                        emptyBlock.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        adapter.submit(logItems)
                        emptyBlock.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    }
                }.onFailure {
                    MezonToast.show(this@AuditLogSettingFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_audit_log_load_failed))
                }
            } finally {
                loadingBar?.visibility = View.GONE
            }
        }
    }
}

private data class PickerRow(val value: String, val label: String)

private class AuditLogPickerListAdapter(
    private val source: List<PickerRow>,
    private var selectedValue: String,
    private val themeColors: ThemeColors,
    private val onRow: (PickerRow) -> Unit
) : RecyclerView.Adapter<AuditLogPickerListAdapter.VH>() {

    private val filtered = ArrayList<PickerRow>()

    init {
        filtered.addAll(source)
    }

    fun setSelectedValue(value: String) {
        if (selectedValue == value) return
        selectedValue = value
        notifyDataSetChanged()
    }

    fun setQuery(q: String) {
        val qq = q.trim().lowercase(Locale.getDefault())
        filtered.clear()
        if (qq.isEmpty()) {
            filtered.addAll(source)
        } else {
            for (r in source) {
                if (r.label.lowercase(Locale.getDefault()).contains(qq)) filtered.add(r)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14))
            textSize = 15f
            setTextColor(themeColors.getColor(ThemeColors.key_dialogTextBlack))
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        val row = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, LayoutHelper.dp(1), 0, LayoutHelper.dp(1))
            addView(tv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        val vh = VH(row, tv)
        row.setOnClickListener {
            val pos = vh.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRow(filtered[pos])
        }
        return vh
    }

    override fun getItemCount(): Int = filtered.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = filtered[position]
        val selected = row.value == selectedValue
        holder.text.text = row.label
        holder.text.setTextColor(themeColors.getColor(ThemeColors.key_dialogTextBlack))
        holder.text.typeface = Typeface.DEFAULT
        val accent = themeColors.getColor(ThemeColors.key_dialogButton)
        holder.wrap.background = if (selected) {
            GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(ColorUtils.setAlphaComponent(accent, 0x33))
            }
        } else {
            null
        }
    }

    class VH(val wrap: FrameLayout, val text: TextView) : RecyclerView.ViewHolder(wrap)
}

private class AuditLogAdapter(
    private val context: Context,
    private val themeColors: ThemeColors,
    private val member: (Long) -> ClanMember?,
    private val channelLabel: (Long, String) -> String,
    private val formatTime: (Int) -> String
) : RecyclerView.Adapter<AuditLogAdapter.Holder>() {

    private val items = ArrayList<AuditLog>()

    fun submit(newItems: List<AuditLog>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = items[oldItemPosition]
                val newItem = newItems[newItemPosition]
                val oid = oldItem.id
                val nid = newItem.id
                if (oid != 0L && nid != 0L) return oid == nid
                return oldItem.userId == newItem.userId &&
                    oldItem.timeLogSeconds == newItem.timeLogSeconds &&
                    oldItem.actionLog == newItem.actionLog &&
                    oldItem.entityId == newItem.entityId &&
                    oldItem.channelId == newItem.channelId
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                items[oldItemPosition] == newItems[newItemPosition]
        })
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val cell = AuditLogRowCell(parent.context, themeColors, member, channelLabel, formatTime)
        return Holder(cell)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.cell.bind(items[position])
    }

    class Holder(val cell: AuditLogRowCell) : RecyclerView.ViewHolder(cell)
}

private class AuditLogRowCell(
    context: Context,
    private val themeColors: ThemeColors,
    private val member: (Long) -> ClanMember?,
    private val channelLabel: (Long, String) -> String,
    private val formatTime: (Int) -> String
) : FrameLayout(context) {

    private val avatar = AvatarView(context).apply {
        setSizeDp(36)
        setRoundRadius(18f)
    }
    private val mainText = TextView(context).apply {
        textSize = 14f
        setTextColor(CreateClanRnUiTokens.menuText(themeColors))
    }
    private val timeText = TextView(context).apply {
        textSize = 12f
        setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
    }

    init {
        layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.TOP
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(8), LayoutHelper.dp(4), LayoutHelper.dp(8))
        }
        row.addView(avatar, LayoutHelper.createLinear(36, 36, 0f, Gravity.TOP, 0f, 0f, 10f, 0f))
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(mainText)
        textCol.addView(
            timeText,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(4f)
            }
        )
        row.addView(textCol, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    fun bind(log: AuditLog) {
        val actor = member(log.userId)
        val actorName = actor?.let { m ->
            m.clanNick.ifBlank { m.displayName.ifBlank { m.username } }
        } ?: "…"
        val avatarUrl = when {
            actor == null -> ""
            actor.clanAvatar.isNotEmpty() -> actor.clanAvatar
            else -> actor.avatarUrl
        }
        avatar.setInfo(log.userId, actor?.username.orEmpty())
        avatar.setImageUrl(avatarUrl.ifEmpty { null })

        val action = log.actionLog
        val chId = log.channelId
        val chLab = channelLabel(chId, log.channelLabel)
        val memberRoleVerb = auditLogMemberRoleChannelVerb(action)
        val isChannelStyle = memberRoleVerb != AuditLogMemberRoleChannelVerb.NONE

        val targetMember = member(log.entityId)
        val targetName = targetMember?.let { m ->
            m.displayName.ifBlank { m.clanNick.ifBlank { m.username } }
        }.orEmpty()

        val summaryText = if (isChannelStyle && chId != 0L) {
            val verb = context.getString(
                if (memberRoleVerb == AuditLogMemberRoleChannelVerb.ADD) {
                    R.string.audit_log_item_add
                } else {
                    R.string.audit_log_item_remove
                }
            )
            val toCh = context.getString(R.string.audit_log_item_to_channel)
            buildString {
                append(actorName)
                append(' ')
                append(verb)
                append(' ')
                append(targetName)
                if (log.entityId != 0L) {
                    append(" (")
                    append(log.entityId.toString())
                    append(')')
                }
                append(' ')
                append(toCh)
                append(" #")
                append(chLab)
                append(" (")
                append(chId.toString())
                append(')')
            }
        } else {
            val entity = log.entityName.ifEmpty { log.entityId.toString() }
            val actionReadable = auditLogActionDisplayLabel(context, action)
            buildString {
                append(actorName)
                append(' ')
                append(actionReadable)
                append(" #")
                append(entity)
                if (log.entityName.isNotEmpty() && log.entityId != 0L) {
                    append(" (")
                    append(log.entityId.toString())
                    append(')')
                }
            }
        }
        mainText.text = summaryText
        timeText.text = formatTime(log.timeLogSeconds)
    }
}
