package com.mezon.mobile.home.clans.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.call.CallController
import com.mezon.mobile.home.call.CallFragment
import com.mezon.mobile.home.call.CallManager
import com.mezon.mobile.home.clans.RoleController
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.friends.cancelProfileFriendRequest
import com.mezon.mobile.home.friends.sendProfileFriendRequest
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.SearchCell
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.home.chat.UserProfileBottomSheet
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ClanMembersFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val REQUEST_CALL_AUDIO = 9051

        fun newInstance(clanId: Long): ClanMembersFragment =
            ClanMembersFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                }
            }
    }

    private var clanId = 0L
    private lateinit var userClanController: UserClanController
    private lateinit var roleController: RoleController
    private lateinit var friendController: FriendController
    private lateinit var userController: UserController
    private lateinit var dialogsController: DialogsController
    private lateinit var callController: CallController
    private lateinit var callManager: CallManager

    private lateinit var searchInput: SearchCell
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ClanMembersAdapter
    private var filter = ""
    private var pendingCallPermissionCallback: (() -> Unit)? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userClanController = entryPoint.userClanController()
        roleController = entryPoint.roleController()
        friendController = entryPoint.friendController()
        userController = entryPoint.userController()
        dialogsController = entryPoint.dialogsController()
        callController = entryPoint.callController()
        callManager = entryPoint.callManager()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId != 0L) {
            userClanController.loadClanMembers(clanId, noCache = true)
            roleController.loadRolesForClan(clanId, force = true)
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId && ::adapter.isInitialized) {
                adapter.refresh()
            }
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val id = args.firstOrNull() as? Long ?: return@observe
            if (id == clanId && ::adapter.isInitialized) {
                adapter.refresh()
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
            setTitle(getString(R.string.clan_settings_members))
            setBackButtonImage(R.drawable.ic_close_24)
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            setBackClickListener { finishFragment() }
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        searchInput = SearchCell(context, themeColors).apply {
            setPlaceholder(getString(R.string.clan_roles_members_search))
            onTextChanged = {
                filter = it.trim()
                adapter.refresh()
            }
        }
        root.addView(searchInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 14f, 8f, 14f, 0f))

        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
        }
        adapter = ClanMembersAdapter()
        recyclerView.adapter = adapter
        root.addView(recyclerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        adapter.refresh()
        fragmentView = root
        return root
    }

    private fun handleMemberClick(member: ClanMember) {
        val ctx = getContext() ?: return
        val displayName = member.clanNick.takeIf { it.isNotBlank() } ?: member.displayName.takeIf { it.isNotBlank() } ?: member.username
        val avatarUrl = member.clanAvatar.takeIf { it.isNotBlank() } ?: member.avatarUrl

        val sheet = UserProfileBottomSheet(
            context = ctx,
            userId = member.userId,
            displayName = displayName,
            username = member.username,
            avatarUrl = avatarUrl,
            isOwnProfile = member.userId == userController.userId,
            isDM = false,
            isWebhook = false,
            roles = roleController.profileRoleChipsForMember(clanId, member.roleIds).map { chip ->
                UserProfileBottomSheet.UserProfileRole(
                    id = chip.roleId,
                    title = chip.title,
                    color = chip.color,
                    iconUrl = chip.iconUrl
                )
            },
            friendController = friendController,
            listener = object : UserProfileBottomSheet.UserProfileListener {
                override fun onSendMessage(userId: Long) {
                    openProfileDm(member.userId, displayName, member.username)
                }
                override fun onVoiceCall(userId: Long) {
                    startProfileVoiceCall(member.userId, displayName, member.username, avatarUrl)
                }
                override fun onAddFriend(userId: Long) {
                    sendProfileFriendRequest(friendController, member.userId, member.username)
                }
                override fun onCancelFriendRequest(userId: Long) {
                    cancelProfileFriendRequest(friendController, member.userId, member.username)
                }
                override fun onTransferFunds(userId: Long) {
                    openProfileTransferFunds(member.userId, member.username)
                }
            }
        )
        sheet.setDrawNavigationBar(true)
        sheet.show()
    }

    private fun openProfileDm(userId: Long, displayName: String, username: String) {
        if (userId == 0L) return
        fragmentScope.launch {
            val dmId = withContext(Dispatchers.IO) { dialogsController.getOrCreateDm(userId) }
            withContext(Dispatchers.Main) {
                if (dmId == 0L) {
                    MezonToast.show(this@ClanMembersFragment, ToastOverlay.ToastType.ERROR, getString(R.string.contact_shared_error))
                    return@withContext
                }
                val activity = getParentActivity() as? MainActivity
                activity?.openChat(
                    dmId,
                    displayName.ifBlank { username },
                    0L,
                    CHANNEL_TYPE_DM
                )
            }
        }
    }

    private fun startProfileVoiceCall(
        userId: Long,
        displayName: String,
        username: String,
        avatarUrl: String
    ) {
        if (userId == 0L) return
        val myId = userController.userId
        if (userId == myId) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.cannot_call_yourself))
            return
        }
        if (friendController.isUserBlocked(userId)) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.no_permission_call_blocked))
            return
        }
        if (callController.isCallSessionActive()) return

        requestCallPermissions {
            fragmentScope.launch {
                val dmChannelId = withContext(Dispatchers.IO) { dialogsController.getOrCreateDm(userId) }
                withContext(Dispatchers.Main) {
                    if (dmChannelId == 0L) {
                        MezonToast.show(this@ClanMembersFragment, ToastOverlay.ToastType.ERROR, getString(R.string.contact_shared_error))
                        return@withContext
                    }
                    val avatar = avatarUrl.takeIf { it.isNotBlank() }
                    callController.startCall(
                        userId,
                        displayName,
                        avatar,
                        dmChannelId,
                        0L,
                        CHANNEL_TYPE_DM,
                        false,
                        isVideo = false,
                        peerUsername = username
                    )
                    presentFragment(CallFragment())
                }
            }
        }
    }

    private fun requestCallPermissions(onGranted: () -> Unit) {
        val activity = getParentActivity()
        if (activity == null) {
            onGranted()
            return
        }
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isEmpty()) {
            runOutgoingCallAfterFullScreenIntentPrompt(onGranted)
        } else {
            pendingCallPermissionCallback = { runOutgoingCallAfterFullScreenIntentPrompt(onGranted) }
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), REQUEST_CALL_AUDIO)
        }
    }

    private fun runOutgoingCallAfterFullScreenIntentPrompt(startCall: () -> Unit) {
        val act = getParentActivity()
        if (!callManager.needsFullScreenIntentSettings() || act == null) {
            startCall()
            return
        }
        AlertDialog.Builder(act)
            .setTitle(getString(R.string.call_full_screen_intent_title))
            .setMessage(getString(R.string.call_full_screen_intent_message))
            .setPositiveButton(getString(R.string.call_full_screen_intent_open_settings)) { d, _ ->
                callManager.launchFullScreenIntentSettings(act)
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.call_full_screen_intent_start_call_anyway)) { d, _ ->
                d.dismiss()
                startCall()
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CALL_AUDIO) {
            val audioGranted = permissions.indices.any { i ->
                permissions[i] == Manifest.permission.RECORD_AUDIO &&
                    grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            }
            if (audioGranted) {
                pendingCallPermissionCallback?.invoke()
            } else {
                MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.permission_no_audio))
            }
            pendingCallPermissionCallback = null
        }
    }

    private inner class ClanMembersAdapter : RecyclerView.Adapter<ClanMembersAdapter.Holder>() {

        private var rows: List<ClanMember> = emptyList()
        private val diffScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var diffJob: Job? = null

        init {
            setHasStableIds(true)
        }

        fun refresh() {
            val all = userClanController.getClanMembers(clanId)
            val q = filter.lowercase(Locale.getDefault())
            val nextRows = if (q.isEmpty()) all else all.filter { m ->
                val displayName = m.clanNick.takeIf { it.isNotBlank() } ?: m.displayName.takeIf { it.isNotBlank() } ?: m.username
                displayName.lowercase(Locale.getDefault()).contains(q) ||
                    m.username.lowercase(Locale.getDefault()).contains(q)
            }
            if (nextRows.size < 50) {
                val diff = DiffUtil.calculateDiff(MemberDiffCallback(rows, nextRows))
                rows = nextRows
                diff.dispatchUpdatesTo(this)
            } else {
                val oldRows = rows
                diffJob?.cancel()
                diffJob = diffScope.launch {
                    val diff = withContext(Dispatchers.Default) {
                        DiffUtil.calculateDiff(MemberDiffCallback(oldRows, nextRows))
                    }
                    rows = nextRows
                    diff.dispatchUpdatesTo(this@ClanMembersAdapter)
                }
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemId(position: Int): Long = rows[position].userId

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cell = MemberRowCell(parent.context, themeColors, roleController, clanId) { member ->
                handleMemberClick(member)
            }
            return Holder(cell)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.cell.bind(rows[position], position < rows.lastIndex)
        }

        inner class Holder(val cell: MemberRowCell) : RecyclerView.ViewHolder(cell)
    }

    private class MemberDiffCallback(
        private val old: List<ClanMember>,
        private val new: List<ClanMember>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            old[oldPos].userId == new[newPos].userId

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val o = old[oldPos]
            val n = new[newPos]
            return o.userId == n.userId &&
                o.username == n.username &&
                o.displayName == n.displayName &&
                o.avatarUrl == n.avatarUrl &&
                o.clanNick == n.clanNick &&
                o.clanAvatar == n.clanAvatar &&
                o.roleIds == n.roleIds
        }
    }

    private class RoleWrapLayout(context: Context) : ViewGroup(context) {
        var horizontalSpacing: Int = 0
        var verticalSpacing: Int = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val maxWidth = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else widthSize - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var lineHeight = 0
            var usedWidth = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                measureChild(child, widthMeasureSpec, heightMeasureSpec)
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                if (x > 0 && x + childWidth > maxWidth) {
                    x = 0
                    y += lineHeight + verticalSpacing
                    lineHeight = 0
                }
                if (x > 0) x += horizontalSpacing
                x += childWidth
                lineHeight = maxOf(lineHeight, childHeight)
                usedWidth = maxOf(usedWidth, x)
            }
            val measuredWidth = when (widthMode) {
                MeasureSpec.EXACTLY -> widthSize
                MeasureSpec.AT_MOST -> (paddingLeft + usedWidth + paddingRight).coerceAtMost(widthSize)
                else -> paddingLeft + usedWidth + paddingRight
            }
            val measuredHeight = y + lineHeight + paddingTop + paddingBottom
            setMeasuredDimension(
                measuredWidth,
                resolveSize(measuredHeight, heightMeasureSpec)
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxWidth = width - paddingLeft - paddingRight
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                if (x > paddingLeft && (x - paddingLeft) + horizontalSpacing + childWidth > maxWidth) {
                    x = paddingLeft
                    y += lineHeight + verticalSpacing
                    lineHeight = 0
                }
                if (x > paddingLeft) x += horizontalSpacing
                child.layout(x, y, x + childWidth, y + childHeight)
                x += childWidth
                lineHeight = maxOf(lineHeight, childHeight)
            }
        }
    }

    private class MemberRowCell(
        context: Context,
        private val themeColors: ThemeColors,
        private val roleController: RoleController,
        private val clanId: Long,
        private val onClick: (ClanMember) -> Unit
    ) : FrameLayout(context) {

        private val avatarView: AvatarView
        private val displayNameText: TextView
        private val usernameText: TextView
        private val rolesLayout: RoleWrapLayout
        private val dividerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var drawDivider = false
        private var currentMember: ClanMember? = null

        init {
            setWillNotDraw(false)
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = ContextCompat.getDrawable(context, outValue.resourceId)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16), LayoutHelper.dp(10), LayoutHelper.dp(16), LayoutHelper.dp(10))
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            avatarView = AvatarView(context).apply {
                setSizeDp(40)
                setRoundRadius(20f)
            }
            row.addView(avatarView, LayoutHelper.createLinear(40, 40, 0f, Gravity.NO_GRAVITY, 0f, 0f, 12f, 0f))

            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(textContainer, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

            displayNameText = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(themeColors.textStrong)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textContainer.addView(displayNameText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

            usernameText = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(themeColors.colorText)
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textContainer.addView(usernameText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 2f))

            rolesLayout = RoleWrapLayout(context).apply {
                horizontalSpacing = LayoutHelper.dp(6)
                verticalSpacing = LayoutHelper.dp(4)
            }
            textContainer.addView(rolesLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 4f))

            setOnClickListener {
                currentMember?.let { onClick(it) }
            }
        }

        fun bind(member: ClanMember, divider: Boolean) {
            currentMember = member
            drawDivider = divider
            val displayName = member.clanNick.takeIf { it.isNotBlank() } ?: member.displayName.takeIf { it.isNotBlank() } ?: member.username
            avatarView.setInfo(member.userId, displayName)
            avatarView.setImageUrl(member.clanAvatar.ifBlank { member.avatarUrl })
            displayNameText.text = displayName
            usernameText.text = member.username

            rolesLayout.removeAllViews()
            val chips = roleController.profileRoleChipsForMember(clanId, member.roleIds)
            if (chips.isEmpty()) {
                rolesLayout.visibility = View.GONE
            } else {
                rolesLayout.visibility = View.VISIBLE
                for (chip in chips) {
                    val chipView = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(LayoutHelper.dp(8), LayoutHelper.dp(4), LayoutHelper.dp(8), LayoutHelper.dp(4))
                        background = GradientDrawable().apply {
                            setColor(themeColors.tertiary)
                            cornerRadius = LayoutHelper.dp(8).toFloat()
                        }
                    }
                    val dotColor = if (chip.color != 0) chip.color else Color.parseColor("#99aab5")
                    chipView.addView(View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(dotColor)
                        }
                    }, LinearLayout.LayoutParams(LayoutHelper.dp(8), LayoutHelper.dp(8)).apply {
                        marginEnd = LayoutHelper.dp(6)
                    })
                    chipView.addView(TextView(context).apply {
                        text = chip.title.uppercase(Locale.getDefault())
                        setTextColor(themeColors.colorText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    rolesLayout.addView(chipView)
                }
            }
            invalidate()
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            if (!drawDivider) return
            dividerPaint.color = themeColors.border
            val leftPad = LayoutHelper.dp(16f).toFloat()
            val y = (height - 1).toFloat()
            canvas.drawRect(leftPad, y, width.toFloat(), y + 1f, dividerPaint)
        }
    }
}
