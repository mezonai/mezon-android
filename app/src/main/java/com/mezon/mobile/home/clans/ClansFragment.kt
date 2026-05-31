package com.mezon.mobile.home.clans

import android.content.Context
import android.util.Log
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import androidx.annotation.ColorInt
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.core.RecyclerListView
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.DialogsController
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.channelapp.ChannelAppController
import com.mezon.mobile.home.clans.channelapp.ChannelAppFragment
import com.mezon.mobile.home.clans.channelapp.ChannelAppShowAllFragment
import com.mezon.mobile.home.clans.channelapp.ChannelAppUiModel
import com.mezon.mobile.home.messages.DirectMessage
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.friends.FriendController
import com.mezon.mobile.home.profile.AccountController
import com.mezon.mobile.MainActivity
import com.mezon.mobile.home.voice.JoinVoiceBottomSheet
import com.mezon.mobile.home.voice.VoiceController
import com.mezon.mobile.home.voice.VoiceRoomFragment
import com.mezon.mobile.home.clans.discover.DiscoverClansListSection
import com.mezon.mobile.home.clans.discover.buildDiscoverCommunitySearchToolbar
import com.mezon.mobile.home.clans.discover.DiscoverRailCell
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.settings.AuditLogSettingFragment
import com.mezon.mobile.home.clans.settings.ClanSettingFragment
import com.mezon.mobile.home.clans.settings.InvitePeopleBottomSheet
import com.mezon.mobile.home.clans.settings.InvitePeopleController
import com.mezon.mobile.search.GlobalSearchFragment
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.home.qr.QrScanFragment
import com.mezon.mobile.home.profile.UserController

private const val TAG_CHANNEL_OPEN = "ClansFragment"

class ClansFragment : BaseFragment() {

    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController
    private lateinit var channelAppController: ChannelAppController
    private lateinit var dialogsController: DialogsController
    private lateinit var accountController: AccountController
    private lateinit var friendController: FriendController
    private lateinit var userClanController: UserClanController
    private lateinit var voiceController: VoiceController
    private lateinit var channelCategoryExpandStore: ChannelCategoryExpandStore
    private lateinit var userController: UserController
    private lateinit var roleController: RoleController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var invitePeopleController: InvitePeopleController
    private var clanMenuSheet: ClanMenuBottomSheet? = null

    var onOpenChat: ((channelId: Long, channelName: String, clanId: Long, channelType: Int) -> Unit)? = null
    var onSwitchToMessages: (() -> Unit)? = null

    private lateinit var serverRail: RecyclerListView
    private lateinit var channelListView: ChannelListView
    private lateinit var serverAdapter: ServerRailAdapter
    private lateinit var channelPanel: LinearLayout
    private var listFrozen = false
    private var lastVoiceFetchClanId = 0L

    private var bannerImage: ImageView? = null
    private var bannerCancellable: MezonImageLoader.Cancellable? = null
    private lateinit var clanNameText: TextView
    private var verifiedIcon: ImageView? = null
    private lateinit var memberCountText: TextView
    private var viewJustCreated = false

    private var headerSwitch: FrameLayout? = null
    private var normalClanHeaderRoot: View? = null
    private var emptyClanHeaderRoot: LinearLayout? = null
    private lateinit var discoverListSection: DiscoverClansListSection
    private var discoveringFromRailWhileHasClans = false
    private val searchDebounceHandler = Handler(Looper.getMainLooper())
    private var searchDebounceRunnable: Runnable? = null

    private var renderedClanId = 0L
    private var renderedClanName: String? = null
    private var renderedIsCommunity: Boolean? = null
    private var renderedBannerUrl: String? = null
    private var renderedSubtitleKey: String? = null
    private var clanHeaderBannerLoadSeq = 0

    override fun onInject(entryPoint: FragmentEntryPoint) {
        clansController = entryPoint.clansController()
        channelController = entryPoint.channelController()
        channelAppController = entryPoint.channelAppController()
        dialogsController = entryPoint.dialogsController()
        accountController = entryPoint.accountController()
        friendController = entryPoint.friendController()
        userClanController = entryPoint.userClanController()
        voiceController = entryPoint.voiceController()
        channelCategoryExpandStore = entryPoint.channelCategoryExpandStore()
        userController = entryPoint.userController()
        roleController = entryPoint.roleController()
        permissionPolicy = entryPoint.permissionPolicy()
        invitePeopleController = entryPoint.invitePeopleController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.clansDidLoad) { _, _, _ ->
            if (fragmentView == null || isPaused || listFrozen) return@observe
            updateServerRail()
        }
        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            if (fragmentView == null || listFrozen) return@observe
            val clanId = args.firstOrNull() as? Long ?: return@observe
            if (clanId == clansController.selectedClanId.value) {
                updateChannelList()
            }
        }
        observe(NotificationCenter.channelAppsDidLoad) { _, _, args ->
            if (fragmentView == null || listFrozen) return@observe
            val clanId = args.firstOrNull() as? Long ?: return@observe
            if (clanId == clansController.selectedClanId.value) {
                updateChannelAppsStrip(clanId)
            }
        }
        observe(NotificationCenter.clanInfoUpdated) { _, _, _ ->
            if (fragmentView == null || isPaused || listFrozen) return@observe
            updateServerRail()
        }
        observe(NotificationCenter.clanMembersDidLoad) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            val clanId = args.firstOrNull() as? Long ?: return@observe
            if (clanId == clansController.selectedClanId.value) {
                updateMemberCount()
                syncVoiceMembersUi()
            }
        }
        observe(NotificationCenter.dialogsNeedReload) { _, _, _ ->
            if (fragmentView == null || isPaused || listFrozen) return@observe
            updateServerRail()
        }
        observe(NotificationCenter.friendsLoaded) { _, _, _ ->
            if (fragmentView == null || isPaused || listFrozen) return@observe
            updateServerRail()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null || isPaused || listFrozen) return@observe
            val mask = args.firstOrNull() as? Int ?: 0
            updateVisibleRows(mask)
        }
        observe(NotificationCenter.voiceChannelMembersChanged) { _, _, args ->
            if (fragmentView == null || listFrozen) return@observe
            val clanId = args.firstOrNull() as? Long ?: return@observe
            if (clanId == clansController.selectedClanId.value) {
                syncVoiceMembersUi()
            }
        }
        observe(NotificationCenter.appDidReconnect) { _, _, _ ->
            if (fragmentView == null || listFrozen) return@observe
            val clanId = clansController.selectedClanId.value
            if (clanId == 0L) return@observe
            voiceController.fetchVoiceChannelMembers(clanId, noCache = true)
            syncVoiceMembersUi()
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            fragmentView?.setBackgroundColor(themeColors.serverRailBg)
            serverRail.setBackgroundColor(themeColors.serverRailBg)
            serverAdapter.notifyDataSetChanged()
            channelListView.invalidateTheme()
            updateClanPanelHeaderMode()
        }

        clansController.loadClans()
        observe(NotificationCenter.favoriteChannelsChanged) { _, _, args ->
            if (fragmentView == null || isPaused || listFrozen) return@observe
            val clanId = args.firstOrNull() as? Long ?: return@observe
            if (clanId == clansController.selectedClanId.value) {
                updateChannelList()
            }
        }
        observe(NotificationCenter.selectedClanChanged) { _, _, args ->
            if (fragmentView == null || !clansController.clansLoaded) return@observe
            val clanId = args.firstOrNull() as? Long ?: 0L
            updateServerRail()
            if (clanId != 0L) {
                userClanController.loadClanMembers(clanId)
                updateChannelList()
            }
        }
        return true
    }

    override fun onFragmentDestroy() {
        clanMenuSheet?.dismiss()
        clanMenuSheet = null
        super.onFragmentDestroy()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(themeColors.serverRailBg)
        }

        serverRail = RecyclerListView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(themeColors.serverRailBg)
            isVerticalScrollBarEnabled = false
            itemAnimator = null
            setSelectorType(RecyclerListView.SELECTOR_CIRCLE_TO_BOUND)
        }
        serverAdapter = ServerRailAdapter()
        serverRail.adapter = serverAdapter
        serverRail.setOnItemClickListener(RecyclerListView.OnItemClickListener { view, _ ->
            when (view) {
                is DmLogoCell -> {
                    onSwitchToMessages?.invoke()
                }
                is ClanCell -> {
                    val clan = view.currentClan ?: return@OnItemClickListener
                    onClanSelected(clan)
                }
                is UnreadDmCell -> {
                    val dm = view.directMessage ?: return@OnItemClickListener
                    onOpenChat?.invoke(dm.channelId, dm.displayName.ifEmpty { dm.label }, 0L, dm.type)
                }
                is DiscoverRailCell -> {
                    if (clansController.clans.value.isNotEmpty()) {
                        discoveringFromRailWhileHasClans = true
                        updateServerRail()
                    }
                }
                is AddClanCell -> {
                    presentFragment(CreateClanTemplateFragment())
                }
            }
        })

        channelPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            applyChannelPanelBg()
            val topRadius = LayoutHelper.dp(20).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, (view.height + topRadius).toInt(), topRadius)
                }
            }
            clipToOutline = true
        }
        discoverListSection = DiscoverClansListSection(this, themeColors, embeddedInClanPanel = true).also {
            it.inject(entryPoint())
            it.buildViews(context)
        }

        val clanHeader = buildClanHeader(context).also { normalClanHeaderRoot = it }
        val emptyHeader = buildEmptyClanHomeHeader(context, discoverListSection).also { emptyClanHeaderRoot = it }
        headerSwitch = FrameLayout(context).apply {
            addView(clanHeader, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(emptyHeader, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            emptyHeader.visibility = View.GONE
        }

        channelListView = ChannelListView(context, themeColors, channelCategoryExpandStore).apply {
            onChannelClick = { channel -> onChannelSelected(channel) }
            onSectionLongClick = sectionLongClick@ { catId, _, _ ->
                val selClan = clansController.selectedClanId.value
                if (selClan == 0L || catId == 0L) return@sectionLongClick
                val clan = clansController.clans.value.firstOrNull { it.clanId == selClan }
                if (clan != null) {
                    CategoryMenuBottomSheet(
                        context,
                        clan.clanId,
                        clan.clanName,
                        clan.logo,
                        catId,
                        canManageChannel = permissionPolicy.canManageChannelForClan(clan.clanId),
                        onCreateChannel = {
                            presentFragment(CreateChannelFragment.newInstance(catId))
                        }
                    ).show()
                }
            }
        }

        channelPanel.addView(headerSwitch, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        channelPanel.addView(channelListView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        channelPanel.addView(discoverListSection.rootView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        discoverListSection.rootView.visibility = View.GONE

        root.addView(serverRail, LayoutHelper.createLinear(56, LayoutHelper.MATCH_PARENT))
        root.addView(channelPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            topMargin = LayoutHelper.dp(8)
        })

        if (clansController.clansLoaded) {
            updateServerRail()
            val selectedId = clansController.selectedClanId.value
            if (selectedId != 0L) {
                updateChannelList()
                userClanController.loadClanMembers(selectedId)
            }
        }
        viewJustCreated = true

        return root
    }

    private fun LinearLayout.applyChannelPanelBg() {
        val r = LayoutHelper.dp(20).toFloat()
        background = GradientDrawable().apply {
            setColor(themeColors.channelPanelBg)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }

    private fun buildClanHeader(context: Context): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.channelPanelBg)
        }

        bannerImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        header.addView(bannerImage, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 140))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(14), LayoutHelper.dp(12), LayoutHelper.dp(14))
        }

        val clanHeaderTapZone = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
            }
            val zoneBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.channelPanelBg)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(themeColors.onSurface and 0x1AFFFFFF),
                zoneBg,
                rippleMask
            )
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.clan_menu_header_content_description)
            setOnClickListener { presentClanMenuBottomSheetIfPossible() }
        }

        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        clanNameText = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 17f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 500, false)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        nameRow.addView(clanNameText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        verifiedIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.verifyIcon.getDrawable(context).apply {
                colorFilter = PorterDuffColorFilter(themeColors.blurple, PorterDuff.Mode.SRC_IN)
            })
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        nameRow.addView(verifiedIcon, LinearLayout.LayoutParams(
            LayoutHelper.dp(18), LayoutHelper.dp(18)
        ).apply { leftMargin = LayoutHelper.dp(8) })
        clanHeaderTapZone.addView(nameRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        memberCountText = TextView(context).apply {
            setTextColor(themeColors.textDisabled)
            textSize = 13f
            text = ""
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        clanHeaderTapZone.addView(memberCountText, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT
        ))
        content.addView(clanHeaderTapZone, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val navBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, LayoutHelper.dp(10), 0, 0)
        }

        val searchPill = FrameLayout(context).apply {
            val pillBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(themeColors.tertiary)
                cornerRadius = LayoutHelper.dp(32f).toFloat()
            }
            val rippleMask = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = LayoutHelper.dp(32f).toFloat()
            }
            val rippleColor = android.content.res.ColorStateList.valueOf(themeColors.onSurface and 0x1AFFFFFF)
            background = android.graphics.drawable.RippleDrawable(rippleColor, pillBg, rippleMask)
            isClickable = true
            isFocusable = true
            setOnClickListener { openSearch() }
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
        val searchIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.searchIcon.getDrawable(context))
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = false
            isFocusable = false
        }
        searchPill.addView(searchIcon, FrameLayout.LayoutParams(
            LayoutHelper.dp(18), LayoutHelper.dp(18),
            Gravity.START or Gravity.CENTER_VERTICAL
        ).apply { leftMargin = LayoutHelper.dp(12) })
        val searchText = TextView(context).apply {
            text = "Search"
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
        }
        searchPill.addView(searchText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        navBar.addView(searchPill, LinearLayout.LayoutParams(
            0, LayoutHelper.dp(32), 1f
        ))

        val qrButton = ImageView(context).apply {
            val circleBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.tertiary)
            }
            val rippleMask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            background = RippleDrawable(
                ColorStateList.valueOf(themeColors.onSurface and 0x1AFFFFFF),
                circleBg,
                rippleMask
            )
            setImageDrawable(MezonIcon.scanQR.getDrawable(context, themeColors))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = LayoutHelper.dp(8)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                presentFragment(QrScanFragment())
            }
        }
        navBar.addView(qrButton, LinearLayout.LayoutParams(
            LayoutHelper.dp(32), LayoutHelper.dp(32)
        ).apply { leftMargin = LayoutHelper.dp(8) })

        val eventButton = ImageView(context).apply {
            val circleBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.tertiary)
            }
            val rippleMask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            background = RippleDrawable(
                ColorStateList.valueOf(themeColors.onSurface and 0x1AFFFFFF),
                circleBg,
                rippleMask
            )
            setImageDrawable(MezonIcon.calendarIcon.getDrawable(context))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = LayoutHelper.dp(7)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Toast.makeText(context, getString(R.string.feature_coming_soon), Toast.LENGTH_SHORT).show()
            }
        }
        navBar.addView(eventButton, LinearLayout.LayoutParams(
            LayoutHelper.dp(32), LayoutHelper.dp(32)
        ).apply { leftMargin = LayoutHelper.dp(8) })

        content.addView(navBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        header.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val divider = View(context).apply {
            setBackgroundColor(themeColors.outlineVariant)
        }
        header.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1))

        return header
    }

    private fun buildEmptyClanHomeHeader(context: Context, inlineDiscoverList: DiscoverClansListSection): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(14), LayoutHelper.dp(12), LayoutHelper.dp(14))
        }
        val title = TextView(context).apply {
            text = getString(R.string.discover_community_on_mezon)
            setTextColor(themeColors.onSurface)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, LayoutHelper.dp(6))
        }
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val (toolbarWrap, debounceRunnable) = buildDiscoverCommunitySearchToolbar(
            context,
            themeColors,
            searchDebounceHandler,
            onSearchCommitted = { inlineDiscoverList.onSearchFilterChangedFromParent() },
            onQrClick = { presentFragment(QrScanFragment()) },
            onAddFriendClick = { showAddFriendBottomSheet() }
        )
        searchDebounceRunnable = debounceRunnable
        root.addView(toolbarWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        root.addView(
            View(context).apply { setBackgroundColor(themeColors.outlineVariant) },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1).apply {
                topMargin = LayoutHelper.dp(14)
            }
        )
        return root
    }

    private fun updateClanPanelHeaderMode() {
        if (fragmentView == null) return
        val emptyServerList = clansController.clansLoaded && clansController.clans.value.isEmpty()
        if (emptyServerList) {
            discoveringFromRailWhileHasClans = false
        }
        val showEmbeddedDiscover = emptyServerList || discoveringFromRailWhileHasClans
        normalClanHeaderRoot?.visibility = if (showEmbeddedDiscover) View.GONE else View.VISIBLE
        emptyClanHeaderRoot?.visibility = if (showEmbeddedDiscover) View.VISIBLE else View.GONE
        discoverListSection.rootView.visibility = if (showEmbeddedDiscover) View.VISIBLE else View.GONE
        channelListView.visibility = if (showEmbeddedDiscover) View.GONE else View.VISIBLE
        val r = LayoutHelper.dp(20).toFloat()
        if (showEmbeddedDiscover) {
            channelPanel.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                CreateClanRnUiTokens.screenGradientColors(themeColors)
            ).apply {
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            if (emptyServerList) {
                channelListView.clear()
            }
            discoverListSection.ensureLoadsIfEmbedded()
        } else {
            channelPanel.applyChannelPanelBg()
        }
    }

    override fun onResume() {
        super.onResume()
        ensureVoiceMembersLoaded()
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (::discoverListSection.isInitialized &&
            discoverListSection.rootView.visibility == View.VISIBLE) {
            discoverListSection.onEmbeddedVisibilityChanged(true)
        }
        ensureVoiceMembersLoaded()
        if (viewJustCreated) {
            viewJustCreated = false
            return
        }
        if (clansController.clansLoaded) {
            updateVisibleRows(0)
        }
    }

    override fun clearViews() {
        bannerCancellable?.cancel()
        bannerCancellable = null
        renderedClanId = 0L
        renderedClanName = null
        renderedIsCommunity = null
        renderedBannerUrl = null
        renderedSubtitleKey = null
        super.clearViews()
    }

    fun setListFrozen(frozen: Boolean) {
        if (listFrozen == frozen) return
        listFrozen = frozen
        if (!frozen && fragmentView != null) {
            updateServerRail()
            updateChannelList()
        }
    }

    private fun updateVisibleRows(mask: Int) {
        if (isPaused && mask != 0) {
            if ((mask and NotificationCenter.UPDATE_MASK_BADGE) != 0) {
                updateServerRail()
            }
            return
        }
        if (mask == 0) {
            updateServerRail()
            updateChannelList()
            return
        }

        if ((mask and NotificationCenter.UPDATE_MASK_BADGE) != 0) {
            updateServerRail()
        }

        val clans = clansController.clans.value
        val selectedId = clansController.selectedClanId.value

        if ((mask and NotificationCenter.UPDATE_MASK_CLAN_BANNER) != 0) {
            clans.find { it.clanId == selectedId }?.let { updateClanHeader(it) }
        }

        val clanMap = HashMap<Long, ClanEntity>(clans.size)
        for (c in clans) clanMap[c.clanId] = c

        val count = serverRail.childCount
        for (i in 0 until count) {
            val child = serverRail.getChildAt(i)
            if (child is ClanCell) {
                val entity = child.currentClan ?: continue
                val updated = clanMap[entity.clanId]
                child.update(mask, updated, entity.clanId == selectedId)
            }
        }

        val channels = channelController.getChannels(selectedId)
        val channelMap = channels.associateBy { it.channelId }
        channelListView.updateVisibleRows(mask, channelMap)
    }

    private fun updateServerRail() {
        val unreadDms = dialogsController.getDialogs()
            .filter { it.unreadCount > 0 && !it.isMute }
        val clans = clansController.clans.value
        val selectedId = clansController.selectedClanId.value
        val logoUrl = accountController.accountInfo.value.logo
        val highlightDiscoverRail = discoveringFromRailWhileHasClans && clans.isNotEmpty()
        serverAdapter.submitData(
            unreadDms,
            clans,
            selectedId,
            newLogoUrl = logoUrl,
            newPendingFriendCount = friendController.pendingReceivedCount.value,
            embeddedDiscoverRail = highlightDiscoverRail
        )

        updateClanPanelHeaderMode()

        val selected = clans.find { it.clanId == selectedId }
        if (selected != null) updateClanHeader(selected)
    }

    private fun updateClanHeader(clan: ClanEntity) {
        val clanChanged = renderedClanId != clan.clanId
        renderedClanId = clan.clanId

        clanNameText.text = clan.clanName
        renderedClanName = clan.clanName

        verifiedIcon?.visibility = if (clan.isCommunity) View.VISIBLE else View.GONE
        if (renderedIsCommunity != clan.isCommunity) {
            renderedIsCommunity = clan.isCommunity
            renderedSubtitleKey = null
        }

        if (clanChanged || renderedBannerUrl != clan.banner) {
            renderedBannerUrl = clan.banner
            bannerCancellable?.cancel()
            bannerCancellable = null
            if (clan.banner.isNotEmpty()) {
                bannerImage?.visibility = View.VISIBLE
                val context = bannerImage?.context
                if (context != null) {
                    val loadSeq = ++clanHeaderBannerLoadSeq
                    val url = clan.banner
                    val loader = MezonImageLoader.getInstance(context)
                    bannerCancellable = loader.load(url, 800, LayoutHelper.dp(140), onSuccess = { bitmap ->
                        if (loadSeq != clanHeaderBannerLoadSeq) return@load
                        if (renderedBannerUrl != url) return@load
                        bannerImage?.setImageBitmap(bitmap)
                    })
                }
            } else {
                clanHeaderBannerLoadSeq++
                bannerImage?.setImageBitmap(null)
                bannerImage?.visibility = View.GONE
            }
        }

        if (clanChanged) {
            renderedSubtitleKey = null
            memberCountText.animate().cancel()
            if (userClanController.hasClanMembersCache(clan.clanId)) {
                memberCountText.alpha = 1f
                memberCountText.visibility = View.VISIBLE
            } else {
                memberCountText.alpha = 0f
                memberCountText.visibility = View.INVISIBLE
            }
        }
        updateMemberCount()
    }

    private fun updateMemberCount() {
        val clanId = clansController.selectedClanId.value
        if (!userClanController.hasClanMembersCache(clanId)) {
            memberCountText.visibility = View.INVISIBLE
            return
        }
        val count = userClanController.getClanMemberCount(clanId)
        val isCommunity = renderedIsCommunity == true
        val key = "$count|$isCommunity"
        val alreadyShown = memberCountText.visibility == View.VISIBLE && memberCountText.alpha >= 1f
        if (renderedSubtitleKey == key && alreadyShown) return
        val wasHidden = !alreadyShown
        renderedSubtitleKey = key
        memberCountText.text = buildSubtitleText(count, isCommunity)
        memberCountText.visibility = View.VISIBLE
        if (wasHidden) {
            memberCountText.animate().cancel()
            memberCountText.alpha = 0f
            memberCountText.animate()
                .alpha(1f)
                .setDuration(180)
                .start()
        }
    }

    private fun buildSubtitleText(count: Int, isCommunity: Boolean): CharSequence {
        val ctx = fragmentView?.context ?: return ""
        val memberWord = if (count == 1) {
            ctx.getString(R.string.common_member)
        } else {
            ctx.getString(R.string.common_members)
        }
        val base = "$count $memberWord"
        if (!isCommunity) return base
        val builder = SpannableStringBuilder(base)
        val dotStart = builder.length
        builder.append(" ")
        builder.setSpan(
            CenteredDotSpan(
                themeColors.blurple,
                LayoutHelper.dp(4),
                LayoutHelper.dp(8)
            ),
            dotStart,
            builder.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val labelStart = builder.length
        builder.append(ctx.getString(R.string.discover_community))
        builder.setSpan(
            ForegroundColorSpan(themeColors.onSurface),
            labelStart,
            builder.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return builder
    }

    private fun openSearch() {
        val fragment = GlobalSearchFragment().apply {
            this.onOpenChat = this@ClansFragment.onOpenChat
        }
        presentFragment(fragment)
    }

    private fun openInvitePeopleSheet(clanId: Long, clanName: String, clanLogo: String) {
        val ctx = fragmentView?.context ?: return
        InvitePeopleBottomSheet(
            ctx,
            invitePeopleController,
            clanId,
            clanName,
            clanLogo,
        ).apply {
            setDrawNavigationBar(true)
            show()
        }
    }

    private fun dismissClanMenuSheet() {
        clanMenuSheet?.dismiss()
        clanMenuSheet = null
    }

    private fun dismissClanMenuThen(action: Runnable) {
        val sheet = clanMenuSheet
        clanMenuSheet = null
        if (sheet != null) {
            sheet.setOnDismissListener { action.run() }
            sheet.dismiss()
        } else {
            action.run()
        }
    }

    private fun presentClanMenuBottomSheetIfPossible() {
        val ctx = fragmentView?.context ?: return
        val clanId = clansController.selectedClanId.value
        if (clanId == 0L) return
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId } ?: return
        userClanController.loadClanMembers(clanId)
        roleController.loadPermissionCatalogIfNeeded()
        roleController.loadUserMaxPermissionForClan(clanId)
        roleController.loadRolesForClanThen(clanId, force = true, Runnable {
            val members = userClanController.getClanMembers(clanId)
            val permissionState = permissionPolicy.clanSettingsPermissionState(clanId)
            val expandState = channelCategoryExpandStore.load(clanId)
            dismissClanMenuSheet()
            val sheet = ClanMenuBottomSheet(
                ctx,
                themeColors,
                clanId,
                clan.clanName,
                clan.logo.ifBlank { null },
                clan.isCommunity,
                members.size,
                false,
                permissionState,
                expandState.allExpanded,
                Runnable {
                    dismissClanMenuThen(Runnable {
                        presentFragment(ClanSettingFragment.newInstance(clanId))
                    })
                },
                Runnable {
                    dismissClanMenuThen(Runnable {
                        presentFragment(AuditLogSettingFragment.newInstance(clanId))
                    })
                },
                Runnable {
                    dismissClanMenuThen(Runnable {
                        openInvitePeopleSheet(clanId, clan.clanName, clan.logo)
                    })
                },
            )
            clanMenuSheet = sheet
            sheet.show()
        })
    }

    private fun updateChannelList() {
        val clanId = clansController.selectedClanId.value
        val sections = channelController.getChannelSections(clanId)
        channelListView.bind(clanId, sections)
        if (clanId != lastVoiceFetchClanId) {
            lastVoiceFetchClanId = clanId
            fragmentView?.post { voiceController.fetchVoiceChannelMembers(clanId) }
        }
        updateVoiceMembers(clanId)
        updateChannelAppsStrip(clanId)
    }

    private fun updateChannelAppsStrip(clanId: Long) {
        if (clanId == 0L) {
            channelListView.setChannelApps(emptyList(), ::onChannelAppClicked, ::onChannelAppViewAllClicked)
            return
        }
        val apps = channelAppController.getApps(clanId)
        channelListView.setChannelApps(apps, ::onChannelAppClicked, ::onChannelAppViewAllClicked)
    }

    private fun onChannelAppClicked(app: ChannelAppUiModel) {
        presentFragment(
            ChannelAppFragment.newInstance(
                channelId = app.channelId,
                clanId = if (app.clanId != 0L) app.clanId else clansController.selectedClanId.value,
                appId = app.appId,
                appUrl = app.appUrl,
                appName = app.appName
            )
        )
    }

    private fun onChannelAppViewAllClicked() {
        val clanId = clansController.selectedClanId.value
        if (clanId == 0L) return
        presentFragment(ChannelAppShowAllFragment.newInstance(clanId))
    }

    private fun ensureVoiceMembersLoaded() {
        if (fragmentView == null || listFrozen || !::channelListView.isInitialized) return
        val clanId = clansController.selectedClanId.value
        if (clanId == 0L) return
        voiceController.fetchVoiceChannelMembers(clanId)
        updateVoiceMembers(clanId)
    }

    private fun syncVoiceMembersUi() {
        if (fragmentView == null || listFrozen || !::channelListView.isInitialized) return
        val clanId = clansController.selectedClanId.value
        if (clanId == 0L) return
        updateVoiceMembers(clanId)
    }

    private fun updateVoiceMembers(clanId: Long) {
        val channels = channelController.getChannels(clanId)
        val voiceChannels = channels.filter {
            it.type == CHANNEL_TYPE_VOICE || it.type == CHANNEL_TYPE_STREAMING || it.type == CHANNEL_TYPE_APP
        }
        if (voiceChannels.isEmpty()) return

        val clanMembers = userClanController.getClanMembers(clanId)
        val memberMap = HashMap<Long, ClanMember>(clanMembers.size)
        for (m in clanMembers) memberMap[m.userId] = m

        val result = HashMap<Long, List<VoiceMemberDisplay>>()
        for (vc in voiceChannels) {
            val userIds = voiceController.getVoiceMembersForChannel(vc.channelId, clanId)
            if (userIds.isEmpty()) continue
            val displays = userIds.map { uid ->
                val member = memberMap[uid]
                val name = member?.clanNick?.ifEmpty { null }
                    ?: member?.displayName?.ifEmpty { null }
                    ?: member?.username
                    ?: "User"
                val username = member?.username.orEmpty()
                val avatar = member?.clanAvatar?.ifEmpty { null } ?: member?.avatarUrl
                VoiceMemberDisplay(uid, name, username, avatar)
            }
            if (displays.isNotEmpty()) result[vc.channelId] = displays
        }
        channelListView.setVoiceMembers(result)
    }

    private fun onClanSelected(clan: ClanEntity) {
        val prevId = clansController.selectedClanId.value
        val hadDiscoverRail = discoveringFromRailWhileHasClans
        discoveringFromRailWhileHasClans = false
        val sameSelection = clan.clanId == prevId
        if (sameSelection && hadDiscoverRail) {
            updateClanPanelHeaderMode()
            updateChannelList()
            updateServerRail()
            return
        }
        if (sameSelection) return
        clansController.selectClan(clan.clanId)
        updateClanHeader(clan)

        val count = serverRail.childCount
        for (i in 0 until count) {
            val child = serverRail.getChildAt(i)
            if (child is ClanCell) {
                val c = child.currentClan ?: continue
                if (c.clanId == clan.clanId || c.clanId == prevId) {
                    child.update(0, newSelected = c.clanId == clan.clanId)
                }
            }
        }

        updateChannelList()
    }

    private fun onChannelSelected(channel: ClanChannelEntity) {
        val clanIdForJoin = if (channel.clanId != 0L) channel.clanId else clansController.selectedClanId.value

        if (clanIdForJoin != 0L && channel.isPrivate) {
            val selectedClan = clansController.selectedClanId.value
            Log.d(
                TAG_CHANNEL_OPEN,
                "onChannelSelected private channelId=${channel.channelId} clanIdForJoin=$clanIdForJoin selectedClan=$selectedClan label=${channel.channelLabel}",
            )
            if (selectedClan == clanIdForJoin) {
                permissionPolicy.ensurePrivateChannelAccessPrefetch(clanIdForJoin, channel.channelId, channel.type)
            }
        }

        continueChannelOpenAfterPrivateGate(channel, clanIdForJoin)
    }

    private fun continueChannelOpenAfterPrivateGate(channel: ClanChannelEntity, clanIdForJoin: Long) {
        if (channel.type == CHANNEL_TYPE_VOICE) {
            val inRoom = voiceController.isJoined || voiceController.isConnecting
            if (inRoom && voiceController.currentVoiceInfo?.channelId == channel.channelId) {
                (getParentActivity() as? MainActivity)?.showVoiceRoom(
                    channel.channelId, clanIdForJoin, channel.channelLabel
                )
                return
            }
            val currentVoice = voiceController.currentVoiceInfo
            if (inRoom && currentVoice != null && currentVoice.channelId != channel.channelId) {
                val activity = getParentActivity() ?: return
                val currentName = currentVoice.channelLabel.ifEmpty { "current voice room" }
                AlertDialog.Builder(activity)
                    .setTitle("Switch Voice Room")
                    .setMessage("You are in \"$currentName\". Leave and join \"${channel.channelLabel}\"?")
                    .setPositiveButton("Switch") { _, _ ->
                        showJoinVoiceBottomSheet(channel, clanIdForJoin)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
            showJoinVoiceBottomSheet(channel, clanIdForJoin)
            return
        }

        onOpenChat?.invoke(channel.channelId, channel.channelLabel, clanIdForJoin, channel.type)
        Log.d(
            TAG_CHANNEL_OPEN,
            "onChannelSelected openChat channelId=${channel.channelId} clanId=$clanIdForJoin type=${channel.type}",
        )
    }

    private fun showJoinVoiceBottomSheet(channel: ClanChannelEntity, clanId: Long) {
        val activity = getParentActivity() ?: return
        val members = voiceController.getVoiceMembersForChannel(channel.channelId, clanId)
        val clanMembers = userClanController.getClanMembers(clanId)
        val memberMap = HashMap<Long, ClanMember>(clanMembers.size)
        for (m in clanMembers) memberMap[m.userId] = m

        val displays = members.map { uid ->
            val m = memberMap[uid]
            val name = m?.clanNick?.ifEmpty { null } ?: m?.displayName?.ifEmpty { null } ?: m?.username ?: "User"
            val username = m?.username.orEmpty()
            val avatar = m?.clanAvatar?.ifEmpty { null } ?: m?.avatarUrl
            VoiceMemberDisplay(uid, name, username, avatar)
        }

        val sheet = JoinVoiceBottomSheet(
            activity, themeColors, channel.channelLabel, channel.channelId, clanId, displays, channel.unreadCount
        )
        sheet.onJoinVoice = {
            (getParentActivity() as? MainActivity)?.showVoiceRoom(
                channel.channelId, clanId, channel.channelLabel
            )
        }
        sheet.onOpenChat = {
            onOpenChat?.invoke(channel.channelId, channel.channelLabel, clanId, channel.type)
        }
        sheet.show()
    }

    inner class ServerRailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        init { setHasStableIds(true) }

        private val VIEW_TYPE_DM_HEADER = 0
        private val VIEW_TYPE_UNREAD_DM = 1
        private val VIEW_TYPE_SEPARATOR = 2
        private val VIEW_TYPE_CLAN = 3
        private val VIEW_TYPE_DISCOVER = 5
        private val VIEW_TYPE_ADD_CLAN = 4

        private val unreadDms = mutableListOf<DirectMessage>()
        private val clans = mutableListOf<ClanEntity>()
        private var selectedClanId = 0L
        private var pendingFriendCount = 0
        private var logoUrl = ""

        private val dmHeaderCount = 1
        private val discoverRailCellEnabled = true
        private val hasSeparator: Boolean
            get() = clans.isNotEmpty()

        private var embeddedRailDiscoverHighlight = false

        fun submitData(
            newUnreadDms: List<DirectMessage>,
            newClans: List<ClanEntity>,
            newSelectedId: Long,
            newPendingFriendCount: Int = 0,
            newLogoUrl: String = "",
            embeddedDiscoverRail: Boolean = false
        ) {
            val oldUnreadDms = ArrayList(unreadDms)
            val oldUnreadIds = oldUnreadDms.map { it.channelId }
            val oldClans = ArrayList(clans)
            val oldClanIds = oldClans.map { it.clanId }
            val oldSelectedId = selectedClanId
            val oldPendingFriendCount = pendingFriendCount
            val oldLogoUrl = logoUrl
            val oldEmbeddedDiscoverRail = embeddedRailDiscoverHighlight
            val oldSize = itemCount

            unreadDms.clear()
            unreadDms.addAll(newUnreadDms)
            clans.clear()
            clans.addAll(newClans)
            selectedClanId = newSelectedId
            pendingFriendCount = newPendingFriendCount
            logoUrl = newLogoUrl
            embeddedRailDiscoverHighlight = embeddedDiscoverRail

            val newUnreadIds = newUnreadDms.map { it.channelId }
            val newClanIds = newClans.map { it.clanId }
            val newSize = itemCount

            val structureChanged = oldSize != newSize ||
                oldUnreadIds != newUnreadIds ||
                oldClanIds != newClanIds
            if (structureChanged) {
                notifyDataSetChanged()
                return
            }

            if (oldLogoUrl != newLogoUrl || oldPendingFriendCount != newPendingFriendCount) {
                notifyItemChanged(0)
            }

            for (i in newUnreadDms.indices) {
                val old = oldUnreadDms.getOrNull(i)
                val new = newUnreadDms[i]
                if (old == null || old.unreadCount != new.unreadCount ||
                    old.lastMessageContent != new.lastMessageContent) {
                    notifyItemChanged(dmHeaderCount + i)
                }
            }

            val sep = if (hasSeparator) 1 else 0
            val clanStart = dmHeaderCount + unreadDms.size + sep
            val oldClanMap = HashMap<Long, ClanEntity>(oldClans.size)
            for (c in oldClans) oldClanMap[c.clanId] = c

            for (i in newClans.indices) {
                val new = newClans[i]
                val old = oldClanMap[new.clanId]
                val selected = new.clanId == newSelectedId
                val wasSelected = new.clanId == oldSelectedId
                if (old == null ||
                    old.badgeCount != new.badgeCount ||
                    old.hasUnread != new.hasUnread ||
                    old.logo != new.logo ||
                    old.clanName != new.clanName ||
                    selected != wasSelected) {
                    notifyItemChanged(clanStart + i)
                }
            }

            if (discoverRailCellEnabled &&
                oldEmbeddedDiscoverRail != embeddedRailDiscoverHighlight) {
                notifyItemChanged(itemCount - 2)
            }
        }

        fun updatePendingFriendCount(count: Int) {
            if (pendingFriendCount == count) return
            pendingFriendCount = count
            notifyItemChanged(0)
        }

        override fun getItemCount(): Int {
            val sep = if (hasSeparator) 1 else 0
            val discoverSlots = if (discoverRailCellEnabled) 1 else 0
            return dmHeaderCount + unreadDms.size + sep + clans.size + discoverSlots + 1
        }

        override fun getItemId(position: Int): Long {
            if (position == 0) return Long.MIN_VALUE
            val afterHeader = position - dmHeaderCount
            if (afterHeader < unreadDms.size) return unreadDms[afterHeader].channelId
            if (hasSeparator && afterHeader == unreadDms.size) return Long.MIN_VALUE + 1
            if (position == itemCount - 1) return Long.MIN_VALUE + 4
            if (discoverRailCellEnabled && position == itemCount - 2) return Long.MIN_VALUE + 3
            val idx = clanIndex(position)
            return if (idx in clans.indices) clans[idx].clanId else Long.MIN_VALUE + 2
        }

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return VIEW_TYPE_DM_HEADER
            val afterHeader = position - dmHeaderCount
            if (afterHeader < unreadDms.size) return VIEW_TYPE_UNREAD_DM
            if (hasSeparator && afterHeader == unreadDms.size) return VIEW_TYPE_SEPARATOR
            if (position == itemCount - 1) return VIEW_TYPE_ADD_CLAN
            if (discoverRailCellEnabled && position == itemCount - 2) return VIEW_TYPE_DISCOVER
            return VIEW_TYPE_CLAN
        }

        private fun clanIndex(position: Int): Int {
            val sep = if (hasSeparator) 1 else 0
            return position - dmHeaderCount - unreadDms.size - sep
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view: View = when (viewType) {
                VIEW_TYPE_DM_HEADER -> DmLogoCell(parent.context, themeColors)
                VIEW_TYPE_UNREAD_DM -> UnreadDmCell(parent.context, themeColors)
                VIEW_TYPE_SEPARATOR -> SeparatorView(parent.context, themeColors)
                VIEW_TYPE_DISCOVER -> DiscoverRailCell(parent.context, themeColors)
                VIEW_TYPE_ADD_CLAN -> AddClanCell(parent.context, themeColors)
                else -> ClanCell(parent.context, themeColors)
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val view = holder.itemView) {
                is DmLogoCell -> {
                    view.setLogoUrl(logoUrl)
                    view.setPendingFriendCount(pendingFriendCount)
                }
                is UnreadDmCell -> {
                    val idx = position - dmHeaderCount
                    if (idx in unreadDms.indices) view.setData(unreadDms[idx])
                }
                is ClanCell -> {
                    val idx = clanIndex(position)
                    if (idx in clans.indices) {
                        val clan = clans[idx]
                        view.update(0, clan, clan.clanId == selectedClanId)
                    }
                }
                is DiscoverRailCell -> {
                    view.setEmbeddedDiscoverHighlight(embeddedRailDiscoverHighlight)
                }
            }
        }
    }

    private class SeparatorView(context: Context, private val theme: ThemeColors) : View(context) {
        private val paint = Paint().apply { color = theme.outlineVariant }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), LayoutHelper.dp(9))
        }
        override fun onDraw(canvas: Canvas) {
            val y = height / 2f
            val margin = LayoutHelper.dp(12f).toFloat()
            paint.color = theme.outlineVariant
            canvas.drawLine(margin, y, width - margin, y, paint)
        }
    }

    private class CenteredDotSpan(
        @ColorInt private val color: Int,
        private val diameterPx: Int,
        private val marginPx: Int
    ) : ReplacementSpan() {
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int = diameterPx + marginPx * 2

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            dotPaint.color = color
            val cx = x + marginPx + diameterPx / 2f
            val cy = (top + bottom) / 2f
            canvas.drawCircle(cx, cy, diameterPx / 2f, dotPaint)
        }
    }
}
