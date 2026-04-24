package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.recyclerview.widget.LinearLayoutManager
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.core.ViewPagerFixed
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.MemberResolver
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.CHANNEL_TYPE_APP
import com.mezon.mobile.home.clans.CHANNEL_TYPE_STREAMING
import com.mezon.mobile.home.profile.UserController
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_DM
import com.mezon.mobile.network.CHANNEL_TYPE_GROUP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.mezon.mobile.home.chat.thread.ThreadListFragment
import com.mezon.mobile.home.PinMessageController
import com.mezon.mobile.MainActivity
import com.mezon.mobile.search.GlobalSearchFragment
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.ColoredImageSpan
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ScreenStateView

class ChannelInfoFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_CHANNEL_PRIVATE = "channelPrivate"
        private const val ARG_PARENT_ID = "parentId"

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

    private var memberListAdapter: MemberListAdapter? = null
    private var membersRecyclerView: RecyclerListView? = null
    private var pinsTab: PinsTabHelper? = null

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

        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            reloadMembers()
        }

        observe(NotificationCenter.channelMembersDidLoad) { _, _, args ->
            if (isPaused) return@observe
            reloadMembers()
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

        triggerMemberLoad()
        return true
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        memberResolver = entryPoint.memberResolver()
        userClanController = entryPoint.userClanController()
        dialogsController = entryPoint.dialogsController()
        userController = entryPoint.userController()
        pinMessageController = entryPoint.pinMessageController()
        channelController = entryPoint.channelController()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.surface)
        }

        val chatActionBar = ActionBarView(context, themeColors).apply {
            setBackClickListener { finishFragment() }
            setBackgroundColor(themeColors.surface)
            setTitle(buildActionBarTitle(context))
            setCenterTitle(true)
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

    private fun buildActionBarTitle(context: Context): CharSequence {
        if (isDm) return channelName
        val entity = channelController.findChannelById(channelId)
        val iconEnum = resolveChannelTypeIcon(entity)
        val iconSize = LayoutHelper.dp(20)
        val d = iconEnum.getDrawable(context, themeColors)
        val span = ColoredImageSpan(d, ColoredImageSpan.ALIGN_CENTER)
        span.setSize(iconSize)
        if (iconEnum == MezonIcon.threadIcon || iconEnum == MezonIcon.threadLockIcon) {
            span.usePaintColor = false
        } else {
            span.overrideColor = themeColors.onSurface
        }
        val text = SpannableString("\u200B $channelName")
        text.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
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

        if (channelType == CHANNEL_TYPE_GROUP) {
            val hasCustomAvatar = avatarUrl.isNotEmpty() && !avatarUrl.contains("avatar-group.png")
            if (hasCustomAvatar) {
                val av = AvatarView(context).apply {
                    setSizeDp(avatarSize)
                    setInfo(channelId, channelName)
                    setImageUrl(avatarUrl)
                }
                avatarContainer.addView(av, LayoutHelper.createFrame(avatarSize, avatarSize, Gravity.CENTER))
            } else {
                val groupCircle = FrameLayout(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFFF97316.toInt())
                    }
                }
                val groupIconView = ImageView(context).apply {
                    val d = MezonIcon.groupIcon.getDrawable(context)
                    d.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                    setImageDrawable(d)
                }
                groupCircle.addView(groupIconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER))
                avatarContainer.addView(groupCircle, LayoutHelper.createFrame(avatarSize, avatarSize, Gravity.CENTER))
            }
        } else {
            val av = AvatarView(context).apply {
                setSizeDp(avatarSize)
                setInfo(channelId, displayName)
                setImageUrl(avatarUrl)
            }
            avatarContainer.addView(av, LayoutHelper.createFrame(avatarSize, avatarSize, Gravity.CENTER))
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
                return if (routeChannelPrivate || routeParentId != 0L) MezonIcon.threadLockIcon else MezonIcon.threadIcon
            }
            return if (routeChannelPrivate) MezonIcon.channelTextLock else MezonIcon.channelText
        }

        val isChannel = !entity.isThread
        val isPrivate = entity.isPrivate

        if (entity.type == CHANNEL_TYPE_STREAMING) return MezonIcon.channelStream

        if (entity.type == CHANNEL_TYPE_APP) return MezonIcon.channelApp

        if (isPrivate) {
            return if (isChannel) MezonIcon.channelTextLock else MezonIcon.threadLockIcon
        }

        return if (isChannel) MezonIcon.channelText else MezonIcon.threadIcon
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
            addActionGap(row)
            row.addView(createActionButton(context, MezonIcon.settingIcon, "Settings") {
                Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
            })
        }

        return row
    }

    private fun addActionGap(row: LinearLayout) {
        val gap = View(row.context)
        row.addView(gap, LinearLayout.LayoutParams(LayoutHelper.dp(30), 0))
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
            3 -> buildPinsTab(context)
            else -> buildComingSoonTab(context)
        }
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
            } else {
                Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
            }
        }

        return wrapper
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

    private fun openSearch() {
        val fragment = GlobalSearchFragment.newInstance(channelId, channelName, clanId, channelType)
        fragment.onOpenChat = { chId, chName, clId, chType ->
            (getParentActivity() as? MainActivity)?.openChat(chId, chName, clId, chType)
        }
        presentFragment(fragment)
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
