package com.mezon.mobile.home.chat.channelinfo

import android.app.Activity
import android.app.Dialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.recyclerview.widget.LinearLayoutManager
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ViewPagerFixed
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.ChannelFilesController
import com.mezon.mobile.home.ChannelGalleryController
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.DmGroupAvatarUploadResult
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ChannelPermissionController
import com.mezon.mobile.home.clans.PermissionPolicy

import com.mezon.mobile.home.clans.CHANNEL_TYPE_APP
import com.mezon.mobile.home.clans.CHANNEL_TYPE_STREAMING
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mezon.mobile.home.chat.thread.ThreadListFragment
import com.mezon.mobile.home.messages.NewGroupFragment
import com.mezon.mobile.home.PinMessageController
import com.mezon.mobile.MainActivity
import com.mezon.mobile.search.GlobalSearchFragment
import com.mezon.mobile.ui.cells.ActionBarView

import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ScreenStateView
import com.mezon.mobile.util.CreateChannelNameValidator

class ChannelInfoFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_CHANNEL_PRIVATE = "channelPrivate"
        private const val ARG_PARENT_ID = "parentId"
        private const val REQUEST_CODE_PICK_GROUP_AVATAR = 9124
        private const val MAX_GROUP_AVATAR_BYTES = 8 * 1024 * 1024
        private const val GROUP_AVATAR_PREVIEW_MAX_SIDE = 512

        fun newInstance(
            channelId: Long,
            channelName: String,
            clanId: Long,
            channelType: Int,
            isChannelPrivate: Boolean = false,
            parentId: Long = 0L
        ): ChannelInfoFragment = ChannelInfoFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putString(ARG_CHANNEL_NAME, channelName)
                putLong(ARG_CLAN_ID, clanId)
                putInt(ARG_CHANNEL_TYPE, channelType)
                if (isChannelPrivate) putBoolean(ARG_CHANNEL_PRIVATE, true)
                if (parentId != 0L) putLong(ARG_PARENT_ID, parentId)
            }
        }
    }

    private var channelId = 0L
    private var channelName = ""
    private var clanId = 0L
    private var channelType = 0
    private var routeChannelPrivate = false
    private var routeParentId = 0L

    private lateinit var memberResolver: MemberResolver
    private lateinit var userClanController: UserClanController
    private lateinit var dialogsController: DialogsController
    private lateinit var userController: UserController
    private lateinit var pinMessageController: PinMessageController
    private lateinit var channelController: ChannelController
    private lateinit var channelPermissionController: ChannelPermissionController
    private lateinit var permissionPolicy: PermissionPolicy
    private var settingsActionGap: View? = null
    private var settingsActionView: View? = null
    private var dmHeaderAvatarView: DmHeaderAvatarView? = null
    private var editGroupSheet: DmGroupEditBottomSheet? = null

    private lateinit var channelFilesController: ChannelFilesController
    private lateinit var channelGalleryController: ChannelGalleryController

    private var memberListAdapter: MemberListAdapter? = null
    private var membersRecyclerView: RecyclerListView? = null
    private var pinsTab: PinsTabHelper? = null
    private var filesTab: FilesTabHelper? = null
    private var mediaTab: MediaTabHelper? = null

    private val isDm get() = channelType == CHANNEL_TYPE_DM || channelType == CHANNEL_TYPE_GROUP
    private val isSelfDm: Boolean
        get() = channelType == CHANNEL_TYPE_DM && run {
            val participants = dialogsController.getParticipants(channelId)
            participants.size <= 1 || participants.all { it.userId == userController.userId }
        }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME) ?: ""
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: 0
        routeChannelPrivate = arguments?.getBoolean(ARG_CHANNEL_PRIVATE) ?: false
        routeParentId = arguments?.getLong(ARG_PARENT_ID) ?: 0L
        val initialEntity = channelController.findChannelById(channelId, clanId)
            ?: channelController.findChannelById(channelId)
        if (!isDm && !initialEntity?.channelLabel.isNullOrBlank()) {
            channelName = initialEntity!!.channelLabel
        }

        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            reloadMembers()
        }

        observe(NotificationCenter.channelMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            reloadMembers()
        }

        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            val changedClanId = args.firstOrNull() as? Long ?: return@observe
            if (changedClanId != clanId || isDm) return@observe
            refreshClanHeader()
        }

        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            if (isPaused) return@observe
            refreshDmHeader()
        }

        observe(NotificationCenter.pinMessagesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val ch = args.firstOrNull() as? Long ?: return@observe
            if (ch == channelId) pinsTab?.reload()
        }
        observe(NotificationCenter.pinMessageAdded) { _, _, args ->
            if (isPaused) return@observe
            val ch = args.firstOrNull() as? Long ?: return@observe
            if (ch == channelId) pinsTab?.reload()
        }
        observe(NotificationCenter.pinMessageRemoved) { _, _, args ->
            if (isPaused) return@observe
            val ch = args.firstOrNull() as? Long ?: return@observe
            if (ch == channelId) pinsTab?.reload()
        }

        observe(NotificationCenter.channelFilesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val ch = args.firstOrNull() as? Long ?: return@observe
            if (ch == channelId) filesTab?.onRemoteChannelFiles(ch)
        }
        observe(NotificationCenter.channelFilesLoadError) { _, _, args ->
            if (isPaused) return@observe
            val ch = args.firstOrNull() as? Long ?: return@observe
            if (ch == channelId) filesTab?.onRemoteChannelFiles(ch)
        }
        observe(NotificationCenter.channelPermissionsDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val ch = args.firstOrNull() as? Long ?: return@observe
            if (ch == channelId) updateSettingsActionVisibility()
        }
        observe(NotificationCenter.channelPermissionOverridesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val ch = args.firstOrNull() as? Long ?: return@observe
            if (ch == channelId) updateSettingsActionVisibility()
        }
        observe(NotificationCenter.clanRolesDidLoad) { _, _, args ->
            if (isPaused) return@observe
            val changedClanId = args.firstOrNull() as? Long ?: return@observe
            if (changedClanId == clanId) updateSettingsActionVisibility()
        }

        observeGlobal(NotificationCenter.channelGalleryDidLoad) { _, _, args ->
            if (isPaused) return@observeGlobal
            val ch = args.firstOrNull() as? Long ?: return@observeGlobal
            if (ch != channelId) return@observeGlobal
            fragmentView?.context?.let { ctx -> mediaTab?.syncFromApi(ctx) }
        }
        observeGlobal(NotificationCenter.channelGalleryLoadError) { _, _, args ->
            if (isPaused) return@observeGlobal
            val ch = args.firstOrNull() as? Long ?: return@observeGlobal
            if (ch != channelId) return@observeGlobal
            fragmentView?.context?.let { ctx -> mediaTab?.onGalleryLoadFailure(ctx) }
        }

        triggerMemberLoad()
        if (clanId != 0L && channelId != 0L && !isDm) {
            channelPermissionController.loadChannelPermissionData(clanId, channelId, channelType)
        }
        return true
    }

    override fun dismissDialogOnPause(dialog: Dialog): Boolean {
        if (dialog === editGroupSheet) {
            return false
        }
        return super.dismissDialogOnPause(dialog)
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        memberResolver = entryPoint.memberResolver()
        userClanController = entryPoint.userClanController()
        dialogsController = entryPoint.dialogsController()
        userController = entryPoint.userController()
        pinMessageController = entryPoint.pinMessageController()
        channelController = entryPoint.channelController()
        channelPermissionController = entryPoint.channelPermissionController()
        permissionPolicy = entryPoint.permissionPolicy()
        channelFilesController = entryPoint.channelFilesController()
        channelGalleryController = entryPoint.channelGalleryController()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.surface)
        }

        val chatActionBar = ActionBarView(context, themeColors).apply {
            setBackClickListener { finishFragment() }
            setBackgroundColor(themeColors.surface)
            setCenterTitle(true)
            if (isDm) {
                setTitle(channelName)
                setTitleStartIcon(null, 0, 0)
            } else {
                val entity = channelController.findChannelById(channelId)
                val iconEnum = resolveChannelTypeIcon(entity)
                val iconPx = LayoutHelper.dp(20)
                val iconDrawable = iconEnum.getDrawable(context, themeColors)
                if (!iconEnum.shouldKeepOriginalFill()) {
                    iconDrawable.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
                }
                setTitle(channelName)
                setTitleStartIcon(iconDrawable, iconPx, LayoutHelper.dp(6))
            }
        }
        actionBar = chatActionBar
        root.addView(chatActionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))

        if (isDm) {
            root.addView(buildDmHeader(context), LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
            ))
        }

        root.addView(buildActionRow(context), LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        val tabTitles = buildTabTitles()
        if (tabTitles.size > 1) {
            val viewPager = ViewPagerFixed(context)
            val tabsView = ViewPagerFixed.TabsView(context, viewPager)

            viewPager.setAdapter(object : ViewPagerFixed.Adapter() {
                override fun getItemCount() = tabTitles.size
                override fun getItemTitle(position: Int): CharSequence = tabTitles[position]

                override fun createView(viewType: Int): View {
                    return FrameLayout(context)
                }

                override fun bindView(view: View, position: Int, viewType: Int) {
                    val container = view as FrameLayout
                    container.removeAllViews()
                    val actualIndex = resolveTabIndex(position)
                    container.addView(
                        buildTabContent(context, actualIndex),
                        LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT)
                    )
                }
            })

            viewPager.fillTabs(tabsView)

            viewPager.onPageChangeListener = object : ViewPagerFixed.OnPageChangeListener {
                override fun onPageSelected(position: Int, forward: Boolean) {
                    tabsView.selectTabByPosition(position)
                }
            }

            root.addView(tabsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44))
            root.addView(viewPager, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        } else if (tabTitles.size == 1) {
            val actualIndex = resolveTabIndex(0)
            root.addView(
                buildTabContent(context, actualIndex),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f)
            )
        }

        fragmentView = root
        return root
    }

    private fun buildDmHeader(context: Context): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padTop = LayoutHelper.dp(10)
            setPadding(0, padTop, 0, 0)
        }

        val avatarSize = 50
        val avatarContainer = FrameLayout(context)

        val dm = dialogsController.getDialog(channelId)
        val avatarUrl = dm?.avatarUrl ?: ""
        val displayName = dm?.displayName?.ifBlank { dm.label } ?: channelName

        val placeholderKey = dm?.avatarPlaceholderKey() ?: displayName
        val av = DmHeaderAvatarView(context).apply {
            setSizeDp(avatarSize)
            bind(channelType, channelId, avatarUrl, placeholderKey)
        }
        dmHeaderAvatarView = av
        avatarContainer.addView(av, LayoutHelper.createFrame(avatarSize, avatarSize, Gravity.CENTER))
        if (channelType == CHANNEL_TYPE_GROUP) {
            avatarContainer.setOnClickListener { showGroupEditSheet(context) }
        }

        container.addView(avatarContainer, LayoutHelper.createLinear(
            avatarSize, avatarSize, 0f, Gravity.CENTER_HORIZONTAL
        ))

        return container
    }

    private fun resolveChannelTypeIcon(entity: com.mezon.mobile.home.clans.ClanChannelEntity?): MezonIcon {
        if (entity == null) {
            val isThreadLike = channelType != CHANNEL_TYPE_CHANNEL || routeParentId != 0L
            if (isThreadLike) {
                return if (routeChannelPrivate) MezonIcon.threadLockIcon else MezonIcon.threadIcon
            }
            return if (routeChannelPrivate) MezonIcon.channelTextLock else MezonIcon.channelText
        }

        val isChannel = !entity.isThread

        if (entity.type == CHANNEL_TYPE_STREAMING) return MezonIcon.channelStream

        if (entity.type == CHANNEL_TYPE_APP) return MezonIcon.channelApp

        if (isChannel) {
            return when {
                entity.isAgeRestricted -> MezonIcon.channelTextWarning
                entity.isPrivate -> MezonIcon.channelTextLock
                else -> MezonIcon.channelText
            }
        }

        return if (entity.isPrivate) MezonIcon.threadLockIcon else MezonIcon.threadIcon
    }

    private fun buildActionRow(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val padTop = LayoutHelper.dp(16)
            val padBottom = LayoutHelper.dp(20)
            setPadding(0, padTop, 0, padBottom)
        }

        val isDmContext = clanId == 0L

        row.addView(createActionButton(context, MezonIcon.searchIcon, "Search", applyTint = false) {
            openSearch()
        })

        if (channelType == CHANNEL_TYPE_GROUP) {
            addActionGap(row)
            row.addView(createActionButton(context, MezonIcon.pencilIcon, getString(R.string.dm_group_customize)) {
                showGroupEditSheet(context)
            })
        }

        if (!isDmContext && channelType == CHANNEL_TYPE_CHANNEL) {
            addActionGap(row)
            row.addView(createActionButton(context, MezonIcon.threadIcon, "Threads", applyTint = false) {
                presentFragment(ThreadListFragment.newInstance(channelId, channelName, clanId))
            })
        }

        if (!isSelfDm) {
            addActionGap(row)
            row.addView(createActionButton(context, MezonIcon.bellIcon, "Mute") {
                Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
            })
        }

        if (!isDmContext) {
            settingsActionGap = addActionGap(row)
            val settingsAction = createActionButton(context, MezonIcon.settingIcon, "Settings") {
                openChannelSettings(context)
            }
            settingsActionView = settingsAction
            row.addView(settingsAction)
            updateSettingsActionVisibility()
        }

        return row
    }

    private fun addActionGap(row: LinearLayout): View {
        val gap = View(row.context)
        row.addView(gap, LinearLayout.LayoutParams(LayoutHelper.dp(30), 0))
        return gap
    }

    private fun updateSettingsActionVisibility() {
        val visible = !isDm && permissionPolicy.canOpenChannelSettings(channelId, clanId, channelType, routeParentId)
        val visibility = if (visible) View.VISIBLE else View.GONE
        settingsActionGap?.visibility = visibility
        settingsActionView?.visibility = visibility
    }

    private fun createActionButton(
        context: Context,
        icon: MezonIcon,
        label: String,
        applyTint: Boolean = true,
        onClick: () -> Unit
    ): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { onClick() }
        }

        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
        }
        val iconView = ImageView(context).apply {
            val d = icon.getDrawable(context)
            if (applyTint) {
                d.colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            }
            setImageDrawable(d)
        }
        circle.addView(iconView, LayoutHelper.createFrame(22, 22, Gravity.CENTER))
        column.addView(circle, LayoutHelper.createLinear(52, 52, 0f, Gravity.CENTER_HORIZONTAL))

        val tv = TextView(context).apply {
            text = label
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        column.addView(tv, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f,
            Gravity.CENTER_HORIZONTAL, 0f, 8f, 0f, 0f
        ))

        return column
    }

    private fun buildTabTitles(): List<String> {
        return when {
            isSelfDm -> listOf("Media", "Files", "Pins")
            isDm -> listOf("Members", "Media", "Files", "Pins")
            else -> listOf("Members", "Media", "Files", "Pins", "Canvas")
        }
    }

    private fun resolveTabIndex(tabPosition: Int): Int {
        return when {
            isSelfDm -> tabPosition + 1
            else -> tabPosition
        }
    }

    private fun buildTabContent(context: Context, actualIndex: Int): View {
        return when (actualIndex) {
            0 -> buildMembersTab(context)
            1 -> buildMediaTab(context)
            2 -> buildFilesTab(context)
            3 -> buildPinsTab(context)
            else -> buildComingSoonTab(context)
        }
    }

    private fun buildMediaTab(context: Context): View {
        val h =
            MediaTabHelper(
                channelId = channelId,
                clanId = clanId,
                channelType = channelType,
                isDm = isDm,
                themeColors = themeColors,
                galleryController = channelGalleryController,
                memberResolver = memberResolver,
                getString = { resId -> getString(resId) },
                hostContext = { fragmentView?.context },
                hostIsPaused = { isPaused }
            )
        mediaTab = h
        return h.buildView(context)
    }

    private fun buildMembersTab(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (channelType != CHANNEL_TYPE_DM) {
            container.addView(buildInviteRow(context), LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
            ))
        }

        memberListAdapter = MemberListAdapter(themeColors, isDm, 0L)

        membersRecyclerView = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = memberListAdapter
            setOnItemClickListener(RecyclerListView.OnItemClickListener { _, position ->
                val member = memberListAdapter?.getMember(position) ?: return@OnItemClickListener
                if (member.userId == userController.userId) return@OnItemClickListener
                navigateToDm(member)
            })
        }
        container.addView(membersRecyclerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        reloadMembers()
        return container
    }

    private fun buildInviteRow(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = LayoutHelper.dp(12)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(themeColors.surfaceVariant)
                cornerRadius = LayoutHelper.dpf(12f)
            }
        }

        val iconCircle = FrameLayout(context).apply {
            val size = LayoutHelper.dp(32)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.blurple)
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        val addIcon = ImageView(context).apply {
            val d = MezonIcon.userPlusIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        iconCircle.addView(addIcon, LayoutHelper.createFrame(16, 16, Gravity.CENTER))
        row.addView(iconCircle)

        val label = TextView(context).apply {
            text = if (isDm) "Add Members" else "Invite Members"
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        }
        row.addView(label, LayoutHelper.createLinear(
            0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12f, 0f, 0f, 0f
        ))

        val chevron = ImageView(context).apply {
            val d = MezonIcon.chevronSmallRightIcon.getDrawable(context)
            d.colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            setImageDrawable(d)
        }
        row.addView(chevron, LayoutHelper.createLinear(12, 12, 0f, Gravity.CENTER_VERTICAL))

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val marginH = LayoutHelper.dp(12)
            val marginV = LayoutHelper.dp(6)
            setPadding(marginH, marginV, marginH, marginV)
        }
        wrapper.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        wrapper.setOnClickListener {
            if (clanId != 0L) {
                val sheet = InviteMembersBottomSheet(context, clanId, channelId, channelName)
                showDialog(sheet)
            } else if (channelType == CHANNEL_TYPE_GROUP) {
                presentFragment(NewGroupFragment.newAddMembers(channelId, channelName))
            } else {
                Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
            }
        }

        return wrapper
    }

    private fun buildFilesTab(context: Context): View {
        val act = getParentActivity() ?: return buildComingSoonTab(context)
        val helper = FilesTabHelper(
            channelId = channelId,
            clanId = clanId,
            channelType = channelType,
            themeColors = themeColors,
            channelFilesController = channelFilesController,
            memberResolver = memberResolver,
            hostActivity = act,
            isVietnameseLocale = userController.languageTag.startsWith("vi"),
            getString = { resId -> getString(resId) },
            getStringArg = { resId, arg -> getString(resId, arg) }
        )
        filesTab = helper
        return helper.buildView(context)
    }

    private fun buildPinsTab(context: Context): View {
        val act = getParentActivity() ?: return buildComingSoonTab(context)
        val helper = PinsTabHelper(
            channelId = channelId,
            clanId = clanId,
            channelType = channelType,
            themeColors = themeColors,
            pinMessageController = pinMessageController,
            memberResolver = memberResolver,
            notificationCenter = notificationCenter,
            hostActivity = act,
            onJumpToMessage = { finishFragment() },
            getString = { resId -> getString(resId) }
        )
        pinsTab = helper
        return helper.buildView(context)
    }

    private fun buildComingSoonTab(context: Context): View {
        return ScreenStateView(context, themeColors).apply {
            showEmpty("Coming soon")
        }
    }

    private fun showGroupEditSheet(context: Context) {
        if (channelType != CHANNEL_TYPE_GROUP) return
        val dm = dialogsController.getDialog(channelId)
        val name = dm?.displayName?.ifBlank { dm.label }?.ifBlank { channelName } ?: channelName
        val avatar = dm?.avatarUrl.orEmpty()
        val sheet = DmGroupEditBottomSheet(
            context = context,
            themeColors = themeColors,
            initialName = name,
            initialAvatarUrl = avatar,
            onPickAvatar = { openGroupAvatarPicker() },
            onSaveRequested = { trimmedName, changedName, changedAvatar ->
                saveGroupEdits(trimmedName, changedName, changedAvatar)
            }
        )
        editGroupSheet = sheet
        sheet.setOnHideListener { _ -> editGroupSheet = null }
        showDialog(sheet)
    }

    private fun openGroupAvatarPicker() {
        val pick = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(getContent, getString(R.string.dm_group_upload_image)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pick))
        }
        startActivityForResult(chooser, REQUEST_CODE_PICK_GROUP_AVATAR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_PICK_GROUP_AVATAR || resultCode != Activity.RESULT_OK) return
        val uri = data?.clipData?.getItemAt(0)?.uri ?: data?.data ?: return
        handleGroupAvatarPicked(uri)
    }

    private fun handleGroupAvatarPicked(uri: Uri) {
        val context = getContext() ?: return
        val resolver = context.contentResolver
        val sheet = editGroupSheet ?: return
        fragmentScope.launch(Dispatchers.Main.immediate) {
            val preview = withContext(Dispatchers.IO) {
                decodeGroupAvatarPreview(resolver, uri, GROUP_AVATAR_PREVIEW_MAX_SIDE)
            }
            if (preview != null) {
                sheet.setPreviewBitmap(preview)
            }
            sheet.setUploading(true)
            val result = dialogsController.uploadDmGroupAvatar(uri, resolver, MAX_GROUP_AVATAR_BYTES)
            sheet.setUploading(false)
            when (result) {
                is DmGroupAvatarUploadResult.Success -> sheet.setDraftAvatar(result.url)
                DmGroupAvatarUploadResult.TooLarge -> {
                    sheet.setPreviewBitmap(null)
                    Toast.makeText(
                        context,
                        getString(R.string.clan_image_too_large, MAX_GROUP_AVATAR_BYTES / (1024 * 1024)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                DmGroupAvatarUploadResult.Failed -> {
                    sheet.setPreviewBitmap(null)
                    Toast.makeText(
                        context,
                        getString(R.string.dm_group_update_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun decodeGroupAvatarPreview(
        resolver: ContentResolver,
        uri: Uri,
        maxSide: Int
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxSide || bounds.outHeight / sampleSize > maxSide) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveGroupEdits(
        trimmedName: String,
        changedName: String?,
        changedAvatar: String?
    ) {
        val context = getContext() ?: return
        if (!CreateChannelNameValidator.isValid(trimmedName)) {
            Toast.makeText(context, getString(R.string.dm_group_invalid_name), Toast.LENGTH_SHORT).show()
            return
        }
        val sheet = editGroupSheet ?: return
        sheet.setSaving(true)
        fragmentScope.launch(Dispatchers.Main.immediate) {
            val success = dialogsController.updateDmGroup(channelId, changedName, changedAvatar)
            sheet.setSaving(false)
            if (success) {
                sheet.dismiss()
                editGroupSheet = null
                refreshDmHeader()
            } else {
                Toast.makeText(context, getString(R.string.dm_group_update_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshDmHeader() {
        if (!isDm) return
        val dm = dialogsController.getDialog(channelId)
        val displayName = dm?.displayName?.ifBlank { dm.label }?.ifBlank { channelName } ?: channelName
        if (displayName.isNotBlank()) {
            channelName = displayName
            actionBar?.setTitle(displayName)
        }
        val avatarUrl = dm?.avatarUrl.orEmpty()
        val placeholderKey = dm?.avatarPlaceholderKey() ?: displayName
        dmHeaderAvatarView?.bind(channelType, channelId, avatarUrl, placeholderKey)
    }

    private fun refreshClanHeader() {
        if (isDm) return
        val bar = actionBar as? ActionBarView ?: return
        val entity = channelController.findChannelById(channelId, clanId)
            ?: channelController.findChannelById(channelId)
            ?: return
        val nextName = entity.channelLabel.ifBlank { channelName }
        if (nextName.isNotBlank()) {
            channelName = nextName
        }
        val iconEnum = resolveChannelTypeIcon(entity)
        val iconPx = LayoutHelper.dp(20)
        val iconDrawable = iconEnum.getDrawable(bar.context, themeColors)
        if (!iconEnum.shouldKeepOriginalFill()) {
            iconDrawable.colorFilter = PorterDuffColorFilter(themeColors.textStrong, PorterDuff.Mode.SRC_IN)
        }
        bar.setTitle(channelName)
        bar.setTitleStartIcon(iconDrawable, iconPx, LayoutHelper.dp(6))
        bar.requestLayout()
    }

    override fun onResume() {
        super.onResume()
        if (isDm) {
            refreshDmHeader()
            reloadMembers()
            if (channelType == CHANNEL_TYPE_GROUP) {
                dialogsController.loadDmParticipants(channelId, force = true)
            }
        } else {
            refreshClanHeader()
            reloadMembers()
        }
    }

    override fun onFragmentDestroy() {
        editGroupSheet?.let { sheet ->
            sheet.setOnHideListener { _ -> }
            try { sheet.dismiss() } catch (_: Throwable) {}
        }
        editGroupSheet = null
        super.onFragmentDestroy()
    }

    private fun openSearch() {
        val fragment = GlobalSearchFragment.newInstance(channelId, channelName, clanId, channelType)
        fragment.onOpenChat = { chId, chName, clId, chType ->
            (getParentActivity() as? MainActivity)?.openChat(chId, chName, clId, chType)
        }
        presentFragment(fragment)
    }

    private fun openChannelSettings(context: Context) {
        if (clanId == 0L || channelId == 0L) {
            Toast.makeText(context, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
            return
        }
        if (!permissionPolicy.canOpenChannelSettings(channelId, clanId, channelType, routeParentId)) {
            channelPermissionController.loadChannelPermissionData(clanId, channelId, channelType, force = true)
            Toast.makeText(context, getString(R.string.channel_permissions_no_access), Toast.LENGTH_SHORT).show()
            return
        }
        val channel = channelController.findChannelById(channelId, clanId)
        presentFragment(
            ChannelSettingsFragment.newInstance(
                channelId = channelId,
                channelName = channelName,
                clanId = clanId,
                channelType = channelType,
                isChannelPrivate = channel?.isPrivate ?: routeChannelPrivate,
            )
        )
    }

    private fun navigateToDm(member: ClanMember) {
        fragmentScope.launch {
            val dmChannelId = dialogsController.getOrCreateDm(member.userId)
            if (dmChannelId != 0L) {
                launch(Dispatchers.Main.immediate) {
                    (getParentActivity() as? MainActivity)?.openChat(
                        dmChannelId, member.displayName.ifBlank { member.username }, 0L, CHANNEL_TYPE_DM
                    )
                }
            }
        }
    }

    private fun reloadMembers() {
        val members = memberResolver.resolveChannelMembers(clanId, channelId, channelType)
        memberListAdapter?.setData(members)
    }

    private fun triggerMemberLoad() {
        if (clanId != 0L) {
            userClanController.loadClanMembers(clanId)
        } else if (channelType == CHANNEL_TYPE_GROUP) {
            dialogsController.loadDmParticipants(channelId)
        }
    }
}
