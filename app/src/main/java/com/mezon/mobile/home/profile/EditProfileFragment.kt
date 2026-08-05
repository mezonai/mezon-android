package com.mezon.mobile.home.profile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mezon.mobile.R
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.ActionButton
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.cells.MezonBottomSheetDialog
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.ClanEntity
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.util.ColorUtilities
import android.view.ViewGroup

class EditProfileFragment : BaseFragment() {

    companion object {
        private const val REQUEST_CODE_PICK_AVATAR = 2001
        private const val REQUEST_CODE_PICK_DM_LOGO = 2002
        private const val MAX_AVATAR_SIZE_BYTES = 10L * 1024 * 1024
        private const val MAX_DM_LOGO_SIZE_BYTES = 1L * 1024 * 1024
        private const val TAB_PERSONAL = 0
        private const val TAB_CLAN = 1
        private const val ARG_OPEN_CLAN_TAB = "openClanTab"

        fun newOpenClanProfileTab(): EditProfileFragment {
            return EditProfileFragment().apply {
                arguments = Bundle().apply { putBoolean(ARG_OPEN_CLAN_TAB, true) }
            }
        }

        private const val BANNER_H = 150
        private const val AVATAR_S = 110
        private const val AVATAR_O = 40
        private const val CONTAINER_H = BANNER_H + AVATAR_O
        private const val REMAINING_S = 60 - AVATAR_O
    }

    private lateinit var accountController: AccountController
    private lateinit var userController: UserController
    private lateinit var clansController: ClansController

    var onSaved: (() -> Unit)? = null

    private lateinit var displayNameCell: InputCell
    private lateinit var aboutMeCell: InputCell
    private lateinit var loadingView: View
    private lateinit var bannerView: View
    private lateinit var avatarView: AvatarView
    private lateinit var nameView: TextView
    private lateinit var usernameSubView: TextView

    private var currentAvatarUrl: String = ""

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
    }

    private fun selectClanData(clan: ClanEntity) {
        selectedClan = clan
        clanNickname = ""
        clanAvatarUrl = ""
    }

    private fun selectClan(clan: ClanEntity) {
        selectClanData(clan)
        clanProfileLoaded = true
        updateClanUI()
        loadClanProfileData(clan.clanId)
    }

    private var lastShownClanId: Long = 0L
    private var lastShownClanLogo: String? = null
    private var lastBannerUrl: String? = null

    private fun updateClanUI() {
        val clan = selectedClan ?: return
        
        if (::clanSelectorNameView.isInitialized) {
            clanSelectorNameView.text = clan.clanName
        }
        if (::clanSelectorAvatarView.isInitialized) {
            if (lastShownClanId != clan.clanId || lastShownClanLogo != clan.logo) {
                lastShownClanId = clan.clanId
                lastShownClanLogo = clan.logo
                clanSelectorAvatarView.setInfo(clan.clanId, clan.clanName)
                clanSelectorAvatarView.setImageUrl(clan.logo.ifEmpty { null })
            }
        }
        
        if (::clanNicknameCell.isInitialized) {
            clanNicknameCell.setText(clanNickname)
            clanNicknameCell.setHint(currentMainDisplayName)
        }
    }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        accountController = entryPoint.accountController()
        userController = entryPoint.userController()
        clansController = entryPoint.clansController()
    }

    private var currentDmLogoUrl: String = ""
    private lateinit var dmLogoView: AvatarView
    private lateinit var removeDmLogoIcon: ImageView
    private lateinit var saveButtonView: View
    private var isUploadingAvatar = false
    private var isUploadingDmLogo = false

    private var activeTab = TAB_PERSONAL
    private lateinit var personalContent: View
    private lateinit var clanContent: View
    private lateinit var tabPersonalView: TextView
    private lateinit var tabClanView: TextView

    private var selectedClan: ClanEntity? = null
    private lateinit var clanSelectorNameView: TextView
    private lateinit var clanSelectorAvatarView: AvatarView
    private lateinit var clanBannerView: View
    private lateinit var clanAvatarView: AvatarView
    private lateinit var clanNameView: TextView
    private lateinit var clanUsernameView: TextView
    private lateinit var clanNicknameCell: InputCell
    private var clanNickname: String = ""
    private var clanAvatarUrl: String = ""
    private var currentMainDisplayName: String = ""

    override fun createView(context: Context): View {
        val info = accountController.accountInfo.value
        val userId = info.userId.takeIf { it != 0L } ?: userController.userId
        val displayName = info.displayName.ifEmpty { userController.displayName }
        val username = info.username.ifEmpty { userController.username }
        currentMainDisplayName = displayName.ifEmpty { username }
        currentAvatarUrl = info.avatarUrl.ifEmpty { userController.avatarUrl }
        currentDmLogoUrl = info.logo

        val clans = clansController.clans.value
        val initialClan = clans.find { it.clanId == clansController.selectedClanId.value } ?: clans.firstOrNull()
        initialClan?.let { selectClanData(it) }

        observe(NotificationCenter.clansDidLoad) { _, _, args -> onClanNotificationReceived(args) }
        observe(NotificationCenter.selectedClanChanged) { _, _, args -> onClanNotificationReceived(args) }

        actionBar = createActionBar(context).apply {
            setBackButtonImage(R.drawable.ic_close_icon)
            setTitle(getString(R.string.edit_profile_title))
            setCenterTitle(true)
            val saveItem = createMenu().addItem(1, getString(R.string.edit_profile_save))
            saveButtonView = TextView(context).apply {
                text = getString(R.string.edit_profile_save)
                setTextColor(themeColors.primary)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16), 0, LayoutHelper.dp(16), 0)
            }
            saveItem.addView(saveButtonView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL, 0f, 3f, 0f, 0f))
            getBackButtonView()?.apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val p = LayoutHelper.dp(8)
                setPadding(p, p, p, p)
            }
            setMenuOnItemClick(object : com.mezon.mobile.ui.cells.ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                    if (id == 1) handleSave()
                }
            })
        }
        val rootLinear = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }
        rootLinear.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val tabBg = GradientDrawable().apply {
            setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            cornerRadius = LayoutHelper.dpf(20f)
        }
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = tabBg
            val pad = LayoutHelper.dp(4)
            setPadding(pad, pad, pad, pad)
        }
        val tabPersonal = TextView(context).apply {
            text = getString(R.string.edit_profile_tab_personal)
            setTextColor(themeColors.onPrimary)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val activeBg = GradientDrawable().apply {
                setColor(themeColors.primary)
                cornerRadius = LayoutHelper.dpf(16f)
            }
            background = activeBg
            setOnClickListener { switchTab(TAB_PERSONAL) }
        }
        tabPersonalView = tabPersonal
        val tabClan = TextView(context).apply {
            text = getString(R.string.edit_profile_tab_clan)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setOnClickListener { switchTab(TAB_CLAN) }
        }
        tabClanView = tabClan
        tabContainer.addView(tabPersonal, LayoutHelper.createLinear(0, 32, 1f))
        tabContainer.addView(tabClan, LayoutHelper.createLinear(0, 32, 1f))
        
        val tabWrapper = FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(8), LayoutHelper.dp(16), LayoutHelper.dp(16))
        }
        tabWrapper.addView(tabContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40))
        rootLinear.addView(tabWrapper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val bannerContainer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        val bannerColor = AvatarDrawable.getColorForId(userId)
        bannerView = View(context).apply {
            if (currentAvatarUrl.isEmpty()) {
                setBackgroundColor(bannerColor)
            } else {
                setBackgroundColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            }
        }
        bannerContainer.addView(bannerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, BANNER_H))
        if (currentAvatarUrl.isNotEmpty()) {
            loadBannerFromAvatar(currentAvatarUrl)
        }

        avatarView = AvatarView(context).apply {
            setSizeDp(100)
            setInfo(userId, username)
            if (currentAvatarUrl.isNotEmpty()) setImageUrl(currentAvatarUrl)
        }
        val avatarBorderColor = if (themeColors.resolvedMode == com.mezon.mobile.ui.theme.ThemeMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFF0D0D18.toInt()
        val avatarBorderBg = GradientDrawable().apply {
            setColor(0x00000000)
            cornerRadius = LayoutHelper.dpf(999f)
            setStroke(LayoutHelper.dp(5), avatarBorderColor)
        }
        val avatarWrapper = FrameLayout(context).apply {
            background = avatarBorderBg
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setOnClickListener { showAvatarBottomSheet() }
        }
        avatarWrapper.addView(avatarView, LayoutHelper.createFrame(100, 100, Gravity.CENTER))

        bannerContainer.addView(avatarWrapper, LayoutHelper.createFrame(
            AVATAR_S, AVATAR_S, Gravity.START or Gravity.BOTTOM,
            16f, 0f, 0f, 0f
        ))
        content.addView(bannerContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CONTAINER_H))
        content.addView(View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, REMAINING_S))

        val infoCardBg = GradientDrawable().apply {
            setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            cornerRadius = LayoutHelper.dpf(12f)
        }
        val inputGroupCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = infoCardBg
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        nameView = TextView(context).apply {
            text = displayName.ifEmpty { username }
            setTextColor(themeColors.onSurface)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        inputGroupCard.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        usernameSubView = TextView(context).apply {
            text = username
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 15f
        }
        inputGroupCard.addView(usernameSubView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 2f, 0f, 16f
        ))

        displayNameCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.edit_profile_display_name_label)) 
            setHint(getString(R.string.edit_profile_display_name_hint))
            setText(displayName)
            setMaxCharacter(32)
            setCellBackgroundColor(themeColors.surface)
            setCellStrokeColor(0x00000000)
            onTextChanged = { text ->
                nameView.text = text.ifEmpty { username }
                avatarView.setInfo(userId, username)
                if (::clanAvatarView.isInitialized) clanAvatarView.setInfo(userId, username)
            }
        }
        inputGroupCard.addView(displayNameCell, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 0f, 0f, 16f
        ))

        aboutMeCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.edit_profile_about_me))
            setHint(getString(R.string.edit_profile_about_me_hint))
            setTextarea(true, 128)
            setText(info.aboutMe)
            setCellBackgroundColor(themeColors.surface)
            setCellStrokeColor(0x00000000)
            editText.gravity = Gravity.TOP or Gravity.START
        }
        inputGroupCard.addView(aboutMeCell, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        content.addView(inputGroupCard, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 16f, 0f, 16f, 16f
        ))
        val logoCardBg = GradientDrawable().apply {
            setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            cornerRadius = LayoutHelper.dpf(12f)
        }
        val dmGroupCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = logoCardBg
            gravity = Gravity.CENTER_VERTICAL
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }
        val dmTitle = TextView(context).apply {
            text = getString(R.string.edit_profile_dm_icon_label)
            setTextColor(themeColors.onSurface)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        dmGroupCard.addView(dmTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        dmLogoView = AvatarView(context).apply {
            setSizeDp(50)
            setOnClickListener { openDmLogoPicker() }
        }
        val dmWrapper = FrameLayout(context)
        dmWrapper.addView(dmLogoView, LayoutHelper.createFrame(50, 50))
        
        val removeIconBg = GradientDrawable().apply {
            setColor(themeColors.error)
            cornerRadius = LayoutHelper.dpf(999f)
            setStroke(LayoutHelper.dp(1.5f), 0xFFFFFFFF.toInt())
        }
        removeDmLogoIcon = ImageView(context).apply {
            background = removeIconBg
            setImageResource(R.drawable.ic_close_icon) 
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4))
            setOnClickListener { removeDmLogo() }
        }
        dmWrapper.addView(removeDmLogoIcon, LayoutHelper.createFrame(22, 22, Gravity.TOP or Gravity.END))
        
        bindDmLogoPreview()
        dmGroupCard.addView(dmWrapper, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        content.addView(dmGroupCard, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 16f, 0f, 16f, 24f
        ))
        personalContent = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
        }
        (personalContent as ScrollView).addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        clanContent = buildClanTabContent(context)
        clanContent.visibility = View.GONE

        val contentContainer = FrameLayout(context)
        contentContainer.addView(personalContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        contentContainer.addView(clanContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))
        rootLinear.addView(contentContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        updateClanUI() 

        val rootFrame = FrameLayout(context)
        rootFrame.addView(rootLinear, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        loadingView = View(context).apply {
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        rootFrame.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT))

        if (accountController.accountInfo.value.username.isEmpty()) accountController.loadAccount()

        if (arguments?.getBoolean(ARG_OPEN_CLAN_TAB) == true) {
            switchTab(TAB_CLAN)
        }

        fragmentView = rootFrame
        return rootFrame
    }

    private fun buildClanTabContent(context: Context): View {
        val clans = clansController.clans.value
        if (clans.isEmpty()) {
            return TextView(context).apply {
                text = getString(R.string.edit_profile_no_clans)
                setTextColor(themeColors.onSurfaceVariant)
                textSize = 16f
                gravity = Gravity.CENTER
                val pad = LayoutHelper.dp(32)
                setPadding(pad, pad, pad, pad)
            }
        }

        if (selectedClan == null) {
            selectedClan = clans.find { it.clanId == clansController.selectedClanId.value } ?: clans.firstOrNull()
        }
        val info = accountController.accountInfo.value
        val displayName = info.displayName.ifEmpty { userController.displayName }
        val username = info.username.ifEmpty { userController.username }
        val userId = info.userId.takeIf { it != 0L } ?: userController.userId

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
        }
        val clanRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val selectorBg = GradientDrawable().apply {
            setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            cornerRadius = LayoutHelper.dpf(12f)
        }
        val selectorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = selectorBg
            val padH = LayoutHelper.dp(12)
            val padV = LayoutHelper.dp(10)
            setPadding(padH, padV, padH, padV)
            setOnClickListener { showClanPickerBottomSheet() }
        }
        clanSelectorAvatarView = AvatarView(context).apply {
            setSizeDp(36)
        }
        selectorRow.addView(clanSelectorAvatarView, LayoutHelper.createLinear(36, 36, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 10f, 0f))

        clanSelectorNameView = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        selectorRow.addView(clanSelectorNameView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        val chevron = TextView(context).apply {
            text = "›"
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        selectorRow.addView(chevron, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL))

        clanRoot.addView(selectorRow, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 16f, 0f, 16f, 0f
        ))

        val clanBannerContainer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        clanBannerView = View(context).apply {
            setBackgroundColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
        }
        clanBannerContainer.addView(clanBannerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, BANNER_H))

        clanAvatarView = AvatarView(context).apply {
            setSizeDp(100)
            setInfo(userId, username)
            if (currentAvatarUrl.isNotEmpty()) setImageUrl(currentAvatarUrl)
        }
        val clanAvatarBorderColor = if (themeColors.resolvedMode == com.mezon.mobile.ui.theme.ThemeMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFF0D0D18.toInt()
        val clanAvatarBorderBg = GradientDrawable().apply {
            setColor(0x00000000)
            cornerRadius = LayoutHelper.dpf(999f)
            setStroke(LayoutHelper.dp(5), clanAvatarBorderColor)
        }
        val clanAvatarWrapper = FrameLayout(context).apply {
            background = clanAvatarBorderBg
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setOnClickListener { showAvatarBottomSheet() }
        }
        clanAvatarWrapper.addView(clanAvatarView, LayoutHelper.createFrame(100, 100, Gravity.CENTER))

        clanBannerContainer.addView(clanAvatarWrapper, LayoutHelper.createFrame(
            AVATAR_S, AVATAR_S, Gravity.START or Gravity.BOTTOM,
            16f, 0f, 0f, 0f
        ))
        clanRoot.addView(clanBannerContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CONTAINER_H, 0f, 0, 0f, 16f, 0f, 0f))
        clanRoot.addView(View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, REMAINING_S))

        val clanInfoCardBg = GradientDrawable().apply {
            setColor(themeColors.getColor(ThemeColors.key_sheetItemBackground))
            cornerRadius = LayoutHelper.dpf(12f)
        }
        val clanInfoCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = clanInfoCardBg
            val pad = LayoutHelper.dp(16)
            setPadding(pad, pad, pad, pad)
        }

        clanNameView = TextView(context).apply {
            text = displayName
            setTextColor(themeColors.onSurface)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        clanInfoCard.addView(clanNameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        clanUsernameView = TextView(context).apply {
            text = username
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 15f
        }
        clanInfoCard.addView(clanUsernameView, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0f, 2f, 0f, 16f
        ))

        clanNicknameCell = InputCell(context, themeColors).apply {
            setLabel(getString(R.string.edit_profile_clan_nickname))
            setHint(currentMainDisplayName)
            setMaxCharacter(32)
            setCellBackgroundColor(themeColors.surface)
            setCellStrokeColor(0x00000000)
            onTextChanged = { text ->
                clanNickname = text
                clanNameView.text = text.ifEmpty { currentMainDisplayName }
            }
        }
        clanInfoCard.addView(clanNicknameCell, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
        ))

        clanRoot.addView(clanInfoCard, LayoutHelper.createLinear(
            LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 16f, 0f, 16f, 16f
        ))

        scrollView.addView(clanRoot, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        selectedClan?.let { loadClanProfileData(it.clanId) }

        return scrollView
    }

    private var clanProfileLoaded = false

    private fun switchTab(tab: Int) {
        if (tab == activeTab) return
        activeTab = tab
        com.mezon.mobile.core.AndroidUtilities.hideKeyboard(fragmentView ?: return)

        val activeBg = GradientDrawable().apply {
            setColor(themeColors.primary)
            cornerRadius = LayoutHelper.dpf(16f)
        }

        if (tab == TAB_PERSONAL) {
            personalContent.visibility = View.VISIBLE
            clanContent.visibility = View.GONE
            
            tabPersonalView.background = activeBg
            tabPersonalView.setTextColor(themeColors.onPrimary)
            
            tabClanView.background = null
            tabClanView.setTextColor(themeColors.onSurfaceVariant)
        } else {
            val clans = clansController.clans.value
            if (selectedClan == null) {
                val curId = clansController.selectedClanId.value
                val clan = clans.find { it.clanId == curId } ?: clans.firstOrNull()
                if (clan != null) {
                    selectClan(clan)
                    clanProfileLoaded = true
                }
            } else {
                updateClanUI()
                if (!clanProfileLoaded) {
                    selectedClan?.let { loadClanProfileData(it.clanId) }
                    clanProfileLoaded = true
                }
            }
            if (clanAvatarUrl.isNotEmpty()) {
                loadClanBannerFromAvatar(clanAvatarUrl)
            }
            personalContent.visibility = View.GONE
            clanContent.visibility = View.VISIBLE
            
            tabClanView.background = activeBg
            tabClanView.setTextColor(themeColors.onPrimary)
            
            tabPersonalView.background = null
            tabPersonalView.setTextColor(themeColors.onSurfaceVariant)
        }
    }

    private fun showClanPickerBottomSheet() {
        val context = getContext() ?: return
        val clans = clansController.clans.value
        if (clans.isEmpty()) return

        var dialog: Dialog? = null
        dialog = MezonBottomSheetDialog.create(
            context, themeColors,
            title = getString(R.string.edit_profile_select_clan),
            scrollable = true
        ) { container ->
            for (clan in clans) {
                val itemRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val padH = LayoutHelper.dp(12)
                    val padV = LayoutHelper.dp(14)
                    setPadding(padH, padV, padH, padV)
                    setOnClickListener {
                        selectClan(clan)
                        dialog?.dismiss()
                    }
                }
                val itemAvatar = AvatarView(context).apply {
                    setSizeDp(40)
                    setInfo(clan.clanId, clan.clanName)
                    if (clan.logo.isNotEmpty()) setImageUrl(clan.logo)
                }
                itemRow.addView(itemAvatar, LayoutHelper.createLinear(40, 40, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 12f, 0f))

                val itemName = TextView(context).apply {
                    text = clan.clanName
                    setTextColor(themeColors.onSurface)
                    textSize = 16f
                }
                itemRow.addView(itemName, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

                if (clan.clanId == selectedClan?.clanId) {
                    val check = ImageView(context).apply {
                        setImageResource(MezonIcon.checkmarkSmallIcon.resId)
                        imageTintList = android.content.res.ColorStateList.valueOf(themeColors.onlineGreen)
                    }
                    itemRow.addView(check, LayoutHelper.createLinear(24, 24, 0f, Gravity.CENTER_VERTICAL))
                }

                container.addView(itemRow, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
                ))

                val divider = View(context).apply {
                    setBackgroundColor(themeColors.outlineVariant)
                }
                container.addView(divider, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, 1, 0f, 0, 12f, 0f, 12f, 0f
                ))
            }
        }
        dialog.show()
    }

    private fun loadClanProfileData(clanId: Long) {
        accountController.loadClanProfile(clanId) { nickName, avatar ->
            clanNickname = nickName
            clanAvatarUrl = avatar
            clanNicknameCell.setText(nickName)
            clanNicknameCell.setHint(currentMainDisplayName)
            clanNameView.text = nickName.ifEmpty { currentMainDisplayName }

            val info = accountController.accountInfo.value
            val fallbackAvatar = info.avatarUrl.ifEmpty { userController.avatarUrl }
            val avatarToShow = avatar.ifEmpty { fallbackAvatar }
            if (avatarToShow.isNotEmpty()) {
                clanAvatarView.setImageUrl(avatarToShow)
                loadClanBannerFromAvatar(avatarToShow)
            } else {
                val rUserId = info.userId.takeIf { it != 0L } ?: userController.userId
                clanBannerView.setBackgroundColor(AvatarDrawable.getColorForId(rUserId))
            }
        }
    }

    private var lastClanBannerUrl: String? = null

    private fun loadClanBannerFromAvatar(url: String?) {
        if (url.isNullOrEmpty() || url == lastClanBannerUrl) return
        lastClanBannerUrl = url
        MezonImageLoader.getInstance(requireContext()).load(url, 100, 100,
            onSuccess = { bmp ->
                updateBannerColor(clanBannerView, bmp, url, true)
            }
        )
    }

    private fun onClanNotificationReceived(args: Array<out Any?>) {
        val clans = clansController.clans.value
        val targetId: Long = when (val first = args.firstOrNull()) {
            is Long -> first
            is Number -> first.toLong()
            is String -> first.toLongOrNull() ?: 0L
            else -> clansController.selectedClanId.value
        }
        
        val clanToSelect = clans.find { it.clanId == targetId } ?: clans.firstOrNull()
        
        if (clanToSelect != null && (selectedClan == null || selectedClan?.clanId != clanToSelect.clanId)) {
            if (fragmentView != null && !isPaused && ::clanSelectorNameView.isInitialized) {
                selectClan(clanToSelect)
            } else {
                selectClanData(clanToSelect)
            }
        }
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.accountInfoLoaded) { _, _, _ ->
            if (fragmentView == null) return@observe
            val updatedInfo = accountController.accountInfo.value
            if (displayNameCell.getText().isEmpty()) displayNameCell.setText(updatedInfo.displayName)
            if (aboutMeCell.getText().isEmpty()) aboutMeCell.setText(updatedInfo.aboutMe)
            if (currentAvatarUrl.isEmpty() && updatedInfo.avatarUrl.isNotEmpty()) {
                currentAvatarUrl = updatedInfo.avatarUrl
                avatarView.setImageUrl(currentAvatarUrl)
            }
            if (updatedInfo.displayName.isNotEmpty() || updatedInfo.username.isNotEmpty()) {
                 currentMainDisplayName = updatedInfo.displayName.ifEmpty { updatedInfo.username.ifEmpty { userController.username } }
                 if (::clanNicknameCell.isInitialized) {
                     clanNicknameCell.setHint(currentMainDisplayName)
                 }
            }
            if (currentDmLogoUrl.isEmpty() && updatedInfo.logo.isNotEmpty()) {
                currentDmLogoUrl = updatedInfo.logo
            }
            bindDmLogoPreview()
            nameView.text = updatedInfo.displayName.ifEmpty { updatedInfo.username }
            usernameSubView.text = updatedInfo.username
            val uid = updatedInfo.userId.takeIf { it != 0L } ?: userController.userId
            val refreshedUsername = updatedInfo.username.ifEmpty { userController.username }
            avatarView.setInfo(uid, refreshedUsername)
            if (::clanAvatarView.isInitialized) clanAvatarView.setInfo(uid, refreshedUsername)
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            fragmentView?.setBackgroundColor(themeColors.background)
        }

        return true
    }

    private fun showAvatarBottomSheet() {
        val context = getContext() ?: return
        var dialog: Dialog? = null
        dialog = MezonBottomSheetDialog.create(
            context, themeColors, title = null, scrollable = false
        ) { container ->
            val changeButton = TextView(context).apply {
                text = getString(R.string.edit_profile_change_avatar)
                setTextColor(themeColors.onSurface)
                textSize = 16f
                val pad = LayoutHelper.dp(14)
                setPadding(0, pad, 0, pad)
                setOnClickListener {
                    dialog?.dismiss()
                    openImagePicker()
                }
            }
            container.addView(changeButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
            ))

            val removeButton = TextView(context).apply {
                text = getString(R.string.edit_profile_remove_avatar)
                setTextColor(themeColors.error)
                textSize = 16f
                val pad = LayoutHelper.dp(14)
                setPadding(0, pad, 0, pad)
                setOnClickListener {
                    dialog?.dismiss()
                    removeAvatar()
                }
            }
            container.addView(removeButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT
            ))
        }
        dialog.show()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        getParentActivity()?.startActivityForResult(
            Intent.createChooser(intent, getString(R.string.edit_profile_change_avatar)),
            REQUEST_CODE_PICK_AVATAR
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            if (requestCode == REQUEST_CODE_PICK_AVATAR) {
                handleAvatarPicked(uri)
            } else if (requestCode == REQUEST_CODE_PICK_DM_LOGO) {
                handleDmLogoPicked(uri)
            }
        }
    }

    private fun openDmLogoPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        getParentActivity()?.startActivityForResult(
            Intent.createChooser(intent, getString(R.string.edit_profile_dm_icon_label)),
            REQUEST_CODE_PICK_DM_LOGO
        )
    }

    private fun handleDmLogoPicked(uri: Uri) {
        val context = getContext() ?: return
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1 && it.getLong(sizeIndex) > MAX_DM_LOGO_SIZE_BYTES) {
                        val overlay = ToastOverlay(context, themeColors)
                        getParentActivity()?.findViewById<ViewGroup>(android.R.id.content)?.let { root ->
                            overlay.show(root, ToastOverlay.ToastType.ERROR, getString(R.string.edit_profile_dm_logo_too_large))
                        }
                        return
                    }
                }
            }
        } catch (_: Exception) {}

        loadingView.visibility = View.VISIBLE
        isUploadingDmLogo = true
        updateSaveButtonState()
        com.mezon.mobile.core.AndroidUtilities.hideKeyboard(loadingView)
        accountController.uploadAvatar(uri, context.contentResolver) { success, cdnUrl ->
            isUploadingDmLogo = false
            updateSaveButtonState()
            loadingView.visibility = View.GONE
            if (success) {
                currentDmLogoUrl = cdnUrl
                bindDmLogoPreview()
            } else {
                val overlay = ToastOverlay(context, themeColors)
                getParentActivity()?.findViewById<ViewGroup>(android.R.id.content)?.let { root ->
                    overlay.show(root, ToastOverlay.ToastType.ERROR, getString(R.string.edit_profile_save_error))
                }
            }
        }
    }

    private fun bindDmLogoPreview() {
        if (!::dmLogoView.isInitialized) return
        val url = currentDmLogoUrl.ifEmpty { com.mezon.mobile.BuildConfig.MEZON_LOGO_URL }
        dmLogoView.setImageUrl(url)
        if (::removeDmLogoIcon.isInitialized) {
            removeDmLogoIcon.visibility = if (url == com.mezon.mobile.BuildConfig.MEZON_LOGO_URL) View.GONE else View.VISIBLE
        }
    }

    private fun removeDmLogo() {
        currentDmLogoUrl = com.mezon.mobile.BuildConfig.MEZON_LOGO_URL
        bindDmLogoPreview()
    }

    private fun updateSaveButtonState() {
        if (!::saveButtonView.isInitialized) return
        saveButtonView.alpha = if (isUploadingAvatar || isUploadingDmLogo) 0.6f else 1.0f
    }

    private fun handleAvatarPicked(uri: Uri) {
        val context = getContext() ?: return
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        val size = it.getLong(sizeIndex)
                        if (size > MAX_AVATAR_SIZE_BYTES) {
                            val overlay = ToastOverlay(context, themeColors)
                            val rootView = getParentActivity()?.findViewById<ViewGroup>(android.R.id.content)
                            if (rootView != null) {
                                overlay.show(rootView, ToastOverlay.ToastType.ERROR, getString(R.string.edit_profile_avatar_too_large))
                            }
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        isUploadingAvatar = true
        updateSaveButtonState()
        loadingView.visibility = View.VISIBLE
        com.mezon.mobile.core.AndroidUtilities.hideKeyboard(loadingView)
        accountController.uploadAvatar(uri, context.contentResolver) { success, cdnUrl ->
            isUploadingAvatar = false
            updateSaveButtonState()
            loadingView.visibility = View.GONE
            if (success) {
                if (activeTab == TAB_PERSONAL) {
                    currentAvatarUrl = cdnUrl
                    avatarView.setImageUrl(cdnUrl)
                    loadBannerFromAvatar(cdnUrl)
                } else {
                    clanAvatarUrl = cdnUrl
                    clanAvatarView.setImageUrl(cdnUrl)
                    loadClanBannerFromAvatar(cdnUrl)
                }
            } else {
                val overlay = ToastOverlay(context, themeColors)
                val rootView = getParentActivity()?.findViewById<ViewGroup>(android.R.id.content)
                if (rootView != null) {
                    overlay.show(rootView, ToastOverlay.ToastType.ERROR, getString(R.string.edit_profile_save_error))
                }
            }
        }
    }

    private fun removeAvatar() {
        if (activeTab == TAB_PERSONAL) {
            currentAvatarUrl = com.mezon.mobile.BuildConfig.MEZON_LOGO_URL
            avatarView.setImageUrl(currentAvatarUrl)
            loadBannerFromAvatar(currentAvatarUrl)
        } else {
            val info = accountController.accountInfo.value
            clanAvatarUrl = info.avatarUrl.ifEmpty { userController.avatarUrl }
            clanAvatarView.setImageUrl(clanAvatarUrl)
            loadClanBannerFromAvatar(clanAvatarUrl)
        }
    }

    private fun handleSave() {
        if (activeTab == TAB_PERSONAL) {
            handleSavePersonal()
        } else {
            handleSaveClan()
        }
    }

    private fun handleSavePersonal() {
        if (isUploadingAvatar || isUploadingDmLogo) return
        val displayName = displayNameCell.getText().trim()
        val aboutMe = aboutMeCell.getText().trim()

        loadingView.visibility = View.VISIBLE

        accountController.updateProfile(
            displayName,
            currentAvatarUrl,
            aboutMe,
            currentDmLogoUrl
        ) { success, errorMsg ->
            loadingView.visibility = View.GONE
            if (success) {
                userController.updateFromAccount(accountController.accountInfo.value)
                finishFragment()
                onSaved?.invoke()
            } else {
                showProfileSaveErrorToast()
            }
        }
    }

    private fun showProfileSaveErrorToast() {
        val ctx = getContext() ?: return
        getParentActivity()?.findViewById<ViewGroup>(android.R.id.content)?.let { root ->
            ToastOverlay(ctx, themeColors).show(root, ToastOverlay.ToastType.ERROR, getString(R.string.profile_update_failed))
        } ?: Toast.makeText(ctx, getString(R.string.profile_update_failed), Toast.LENGTH_LONG).show()
    }

    private fun handleSaveClan() {
        val clan = selectedClan ?: return
        val nickname = clanNicknameCell.getText().trim()
        val baseAccountInfo = accountController.accountInfo.value

        val nickToSave = nickname.ifEmpty { baseAccountInfo.displayName.ifEmpty { baseAccountInfo.username } }
        val avatarToSave = if (clanAvatarUrl.isNullOrEmpty()) baseAccountInfo.avatarUrl else clanAvatarUrl

        loadingView.visibility = View.VISIBLE

        accountController.updateClanProfile(
            clan.clanId,
            nickToSave,
            avatarToSave ?: ""
        ) { success, errorMsg ->
            loadingView.visibility = View.GONE
            if (success) {
                finishFragment()
                onSaved?.invoke()
            } else {
                AlertsCreator.showSimpleAlert(
                    requireContext(),
                    getString(R.string.common_error),
                    errorMsg.ifEmpty { getString(R.string.edit_profile_save_error) }
                )
            }
        }
    }

    private fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (reqW <= 0 || reqH <= 0) return 1
        while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
        return sample
    }

    private fun loadBannerFromAvatar(url: String?) {
        if (url.isNullOrEmpty() || url == lastBannerUrl) return
        lastBannerUrl = url
        MezonImageLoader.getInstance(requireContext()).load(url, 100, 100,
            onSuccess = { bmp ->
                updateBannerColor(bannerView, bmp, url, false)
            }
        )
    }

    private fun updateBannerColor(view: View, bitmap: android.graphics.Bitmap, url: String, isClan: Boolean) {
        fragmentScope.launch(Dispatchers.Default) {
            val dominant = ColorUtilities.getDominantColor(bitmap)
            withContext(Dispatchers.Main) {
                if (view.parent != null) {
                    if (isClan && lastClanBannerUrl != url) return@withContext
                    if (!isClan && lastBannerUrl != url) return@withContext
                    view.setBackgroundColor(dominant)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }
}
