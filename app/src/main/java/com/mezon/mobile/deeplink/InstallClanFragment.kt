package com.mezon.mobile.deeplink

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.CHANNEL_TYPE_APP
import com.mezon.mobile.home.clans.ClanCategoryItem
import com.mezon.mobile.home.clans.ClanEntity
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.profile.UserController
import com.mezon.mezon.api.App
import com.mezon.mobile.network.HttpRpcStatusException
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import com.mezon.mobile.util.NetworkErrorMessages
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale

class InstallClanFragment : BaseFragment() {

    companion object {
        private const val ARG_APP_ID = "appId"
        private const val ARG_INSTALL_KIND = "installKind"
        private const val ARG_SOURCE_URL = "sourceUrl"
        private val CHANNEL_LABEL_REGEX = Regex("[^\\p{L}\\p{M}\\p{N} \\-_]")
        private const val DUPLICATE_CHECK_TYPE_CHANNEL = 2

        fun newInstance(appId: Long, installKind: InstallKind, sourceUrl: String? = null): InstallClanFragment {
            return InstallClanFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_APP_ID, appId)
                    putInt(ARG_INSTALL_KIND, installKind.ordinal)
                    sourceUrl?.let { putString(ARG_SOURCE_URL, it) }
                }
            }
        }
    }

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var clansController: ClansController
    private lateinit var channelController: ChannelController
    private lateinit var userController: UserController

    private var appId = 0L
    private var installKind = InstallKind.BOT
    private var appName = ""
    private var appCreateTimeSeconds = 0
    private var selectedClan: ClanEntity? = null
    private var selectedCategory: ClanCategoryItem? = null
    private var categories = emptyList<ClanCategoryItem>()
    private var createdChannelId = 0L
    private var createdChannelLabel = ""

    private var appNameView: TextView? = null
    private var accessRequestView: TextView? = null
    private var signedInView: TextView? = null
    private var clanPickerValueView: TextView? = null
    private var clanErrorView: TextView? = null
    private var categorySection: LinearLayout? = null
    private var channelNameSection: LinearLayout? = null
    private var categoryPickerValueView: TextView? = null
    private var categoryErrorView: TextView? = null
    private var channelNameInput: EditText? = null
    private var privacyFooterView: TextView? = null
    private var activeSinceView: TextView? = null
    private var authorizeButton: TextView? = null
    private var loadingView: ProgressBar? = null
    private var formScroll: ScrollView? = null
    private var successOpenChannelButton: TextView? = null
    private var successContainer: LinearLayout? = null
    private var logoView: DeeplinkLogoView? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        clansController = entryPoint.clansController()
        channelController = entryPoint.channelController()
        userController = entryPoint.userController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        appId = arguments?.getLong(ARG_APP_ID) ?: 0L
        installKind = readInstallKindFromArguments()
        clansController.loadClans()
        return true
    }

    private fun readInstallKindFromArguments(): InstallKind {
        val sourceUrl = arguments?.getString(ARG_SOURCE_URL)
        val fromUrl = DeepLinkParser.installKindFromUrl(sourceUrl)
        val fromArg = when (arguments?.getInt(ARG_INSTALL_KIND, InstallKind.BOT.ordinal)) {
            InstallKind.APP.ordinal -> InstallKind.APP
            else -> InstallKind.BOT
        }
        return when {
            fromUrl == InstallKind.APP || fromArg == InstallKind.APP -> InstallKind.APP
            fromUrl == InstallKind.BOT -> InstallKind.BOT
            else -> fromArg
        }
    }

    override fun createView(context: Context): View {
        actionBar = createActionBar(context).apply {
            setTitle(getString(R.string.deeplink_install_authorize))
            setBackClickListener { finishToHome() }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.chatBackground)
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val loading = ProgressBar(context).apply {
            visibility = View.VISIBLE
        }
        loadingView = loading
        root.addView(loading, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER))

        val formScroll = ScrollView(context).apply {
            visibility = View.GONE
        }
        this.formScroll = formScroll

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(24), LayoutHelper.dp(24), LayoutHelper.dp(24))
        }

        val logo = DeeplinkLogoView(context, themeColors, sizeDp = 100, cornerRadiusDp = 16f)
        logoView = logo
        card.addView(logo, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        val appName = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        appNameView = appName
        card.addView(
            appName,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(16)
            }
        )

        val accessRequest = TextView(context).apply {
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        accessRequestView = accessRequest
        card.addView(
            accessRequest,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 12f, 0f, 0f)
        )

        val signedInRow = buildSignedInRow(context)
        card.addView(
            signedInRow,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 16f, 0f, 0f)
        )

        card.addView(formLabel(context, getString(R.string.deeplink_install_add_to_clan)), fieldLabelMargin())

        val clanPickerRow = buildPickerRow(context) { showClanPicker() }
        val clanPickerValue = TextView(context).apply {
            text = getString(R.string.deeplink_install_clan_placeholder)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        }
        clanPickerValueView = clanPickerValue
        clanPickerRow.addView(clanPickerValue)
        clanPickerRow.addView(chevronView(context))
        card.addView(clanPickerRow, fieldRowMargin())

        val clanError = inlineErrorView(context)
        clanErrorView = clanError
        card.addView(clanError, fieldErrorMargin())

        val categoryBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        categorySection = categoryBlock
        categoryBlock.addView(formLabel(context, getString(R.string.deeplink_install_add_to_category)), fieldLabelMargin())
        val categoryPickerRow = buildPickerRow(context) { showCategoryPicker() }
        val categoryPickerValue = TextView(context).apply {
            text = getString(R.string.deeplink_install_category_placeholder)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        }
        categoryPickerValueView = categoryPickerValue
        categoryPickerRow.addView(categoryPickerValue)
        categoryPickerRow.addView(chevronView(context))
        categoryBlock.addView(categoryPickerRow, fieldRowMargin())
        val categoryError = inlineErrorView(context)
        categoryErrorView = categoryError
        categoryBlock.addView(categoryError, fieldErrorMargin())
        card.addView(categoryBlock, fieldBlockMargin())

        val channelNameBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        channelNameSection = channelNameBlock
        channelNameBlock.addView(formLabel(context, getString(R.string.deeplink_install_channel_name)), fieldLabelMargin())
        val channelName = EditText(context).apply {
            setTextColor(themeColors.onSurface)
            setHintTextColor(themeColors.onSurfaceVariant)
            textSize = 15f
            background = fieldBackground()
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
            minimumHeight = LayoutHelper.dp(44)
        }
        channelNameInput = channelName
        channelNameBlock.addView(channelName, fieldRowMargin())
        card.addView(channelNameBlock, fieldBlockMargin())

        applyInstallKindUi()

        card.addView(buildMetaTextRow(context, MezonIcon.lockIcon) { privacyFooterView = it }, metaRowMargin())
        card.addView(buildMetaTextRow(context, MezonIcon.clockIcon) { activeSinceView = it }, fieldRowMargin())

        card.addView(buildActionRow(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(24)
        })

        formScroll.addView(card, FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val success = buildSuccessView(context).apply {
            visibility = View.GONE
        }
        successContainer = success

        root.addView(formScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        root.addView(success, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        fragmentView = root
        loadAppDetail()
        return root
    }

    override fun onFragmentDestroy() {
        logoView?.cancelLoad()
        super.onFragmentDestroy()
    }

    private fun loadAppDetail() {
        if (appId == 0L) {
            finishToHome()
            return
        }
        fragmentScope.launch {
            val app = runCatching {
                sessionManager.withAutoRefresh { session ->
                    withContext(Dispatchers.IO) {
                        api.getApp(session.apiUrl, session.token, appId)
                    }
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                loadingView?.visibility = View.GONE
                if (fragmentView == null || isPaused) return@withContext
                if (app == null) {
                    MezonToast.show(this@InstallClanFragment, ToastOverlay.ToastType.ERROR, getString(R.string.deeplink_install_error))
                    finishToHome()
                    return@withContext
                }
                formScroll?.visibility = View.VISIBLE
                appName = app.appname
                appCreateTimeSeconds = app.createTimeSeconds
                installKind = resolveInstallKindFromApp(app)
                appNameView?.text = app.appname.ifBlank { getString(R.string.deeplink_install_unknown_app) }
                updateAccessRequestText()
                updateSignedInText()
                updateFooterTexts()
                channelNameInput?.setText(app.appname)
                val displayName = app.appname.ifBlank { getString(R.string.deeplink_install_unknown_app) }
                logoView?.bind(
                    fallbackKey = appId,
                    displayName = displayName,
                    logoUrl = app.applogo.takeIf { it.isNotBlank() },
                    fallbackStyle = DeeplinkLogoView.FallbackStyle.AVATAR_DEFAULT,
                )
                applyInstallKindUi()
            }
        }
    }

    private fun resolveInstallKindFromApp(app: App): InstallKind {
        if (installKind == InstallKind.APP) return InstallKind.APP
        return if (app.appUrl.isNotBlank()) InstallKind.APP else InstallKind.BOT
    }

    private fun applyInstallKindUi() {
        val isAppInstall = installKind == InstallKind.APP
        categorySection?.visibility = if (isAppInstall) View.VISIBLE else View.GONE
        channelNameSection?.visibility = if (isAppInstall) View.VISIBLE else View.GONE
        updateAuthorizeState()
    }

    private fun updateAccessRequestText() {
        val name = appName.ifBlank { getString(R.string.deeplink_install_unknown_app) }
        accessRequestView?.text = getString(R.string.deeplink_install_access_request, name)
    }

    private fun updateSignedInText() {
        val username = userController.username.ifBlank { userController.displayName }
        val signedIn = getString(R.string.deeplink_install_signed_in_as, username)
        val spannable = SpannableString(signedIn)
        if (username.isNotBlank()) {
            val usernameStart = signedIn.indexOf(username).coerceAtLeast(0)
            val usernameEnd = (usernameStart + username.length).coerceAtMost(signedIn.length)
            if (usernameStart < usernameEnd) {
                spannable.setSpan(StyleSpan(Typeface.BOLD), usernameStart, usernameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(ForegroundColorSpan(themeColors.onSurface), usernameStart, usernameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        signedInView?.text = spannable
    }

    private fun updateFooterTexts() {
        val displayName = appName.ifBlank { getString(R.string.deeplink_install_unknown_app) }
        privacyFooterView?.text = getString(R.string.deeplink_install_privacy_footer, displayName)
        val dateText = if (appCreateTimeSeconds > 0) {
            DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault())
                .format(Date(appCreateTimeSeconds.toLong() * 1000L))
        } else {
            ""
        }
        activeSinceView?.text = if (dateText.isBlank()) {
            ""
        } else {
            getString(R.string.deeplink_install_active_since, dateText)
        }
        activeSinceView?.visibility = if (dateText.isBlank()) View.GONE else View.VISIBLE
        (activeSinceView?.parent as? View)?.visibility = if (dateText.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showClanPicker() {
        clearClanError()
        val clans = clansController.clans.value
        if (clans.isEmpty()) {
            MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.deeplink_install_no_clans))
            return
        }
        val activity = getParentActivity() ?: return
        InstallPickerSheet(
            context = activity,
            themeColors = themeColors,
            title = getString(R.string.deeplink_install_add_to_clan),
            items = clans.map { InstallPickerSheet.InstallPickerItem(it.clanId.toString(), it.clanName) },
            onPicked = { item ->
                val clan = clans.firstOrNull { it.clanId.toString() == item.id } ?: return@InstallPickerSheet
                onClanSelected(clan)
            }
        ).show()
    }

    private fun onClanSelected(clan: ClanEntity) {
        selectedClan = clan
        selectedCategory = null
        categories = emptyList()
        clanPickerValueView?.text = clan.clanName
        clanPickerValueView?.setTextColor(themeColors.onSurface)
        categoryPickerValueView?.text = getString(R.string.deeplink_install_category_placeholder)
        categoryPickerValueView?.setTextColor(themeColors.onSurfaceVariant)
        clearCategoryError()
        updateAuthorizeState()
        if (installKind == InstallKind.APP) {
            loadCategories(clan.clanId)
            scrollToAppInstallFields()
        }
    }

    private fun scrollToAppInstallFields() {
        if (installKind != InstallKind.APP) return
        val scroll = formScroll ?: return
        val target = categorySection ?: return
        scroll.post { scroll.smoothScrollTo(0, target.top) }
    }

    private fun loadCategories(clanId: Long) {
        fragmentScope.launch {
            val list = runCatching {
                withContext(Dispatchers.IO) {
                    runCatching { channelController.loadChannelsForClanSuspend(clanId, force = true) }
                    channelController.loadCategoriesForClan(clanId, force = true)
                }
            }.getOrElse { emptyList() }
            withContext(Dispatchers.Main) {
                if (fragmentView == null || isPaused) return@withContext
                if (selectedClan?.clanId != clanId) return@withContext
                categories = list
                if (list.isEmpty()) {
                    MezonToast.show(this@InstallClanFragment, ToastOverlay.ToastType.INFO, getString(R.string.deeplink_install_no_categories))
                }
            }
        }
    }

    private fun showCategoryPicker() {
        clearCategoryError()
        if (selectedClan == null) {
            showClanError(getString(R.string.deeplink_install_select_clan_error))
            return
        }
        if (categories.isEmpty()) {
            MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.deeplink_install_no_categories))
            return
        }
        val activity = getParentActivity() ?: return
        InstallPickerSheet(
            context = activity,
            themeColors = themeColors,
            title = getString(R.string.deeplink_install_add_to_category),
            items = categories.map { InstallPickerSheet.InstallPickerItem(it.categoryId.toString(), it.categoryName) },
            onPicked = { item ->
                val category = categories.firstOrNull { it.categoryId.toString() == item.id } ?: return@InstallPickerSheet
                selectedCategory = category
                categoryPickerValueView?.text = category.categoryName
                categoryPickerValueView?.setTextColor(themeColors.onSurface)
                clearCategoryError()
                updateAuthorizeState()
            }
        ).show()
    }

    private fun updateAuthorizeState() {
        val enabled = when (installKind) {
            InstallKind.BOT -> selectedClan != null
            InstallKind.APP -> selectedClan != null && selectedCategory != null
        }
        authorizeButton?.isEnabled = enabled
        authorizeButton?.alpha = if (enabled) 1f else 0.5f
    }

    private fun onAuthorizeClicked() {
        clearClanError()
        clearCategoryError()
        val clan = selectedClan
        var hasError = false
        if (clan == null) {
            showClanError(getString(R.string.deeplink_install_select_clan_error))
            hasError = true
        }
        if (installKind == InstallKind.APP) {
            val category = selectedCategory
            if (category == null) {
                showCategoryError(getString(R.string.deeplink_install_select_category_error))
                hasError = true
            }
            if (hasError) return
            authorizeAppInstall(clan!!, category!!)
            return
        }
        if (hasError) return
        authorizeBotInstall(clan!!)
    }

    private fun authorizeBotInstall(clan: ClanEntity) {
        authorizeButton?.isEnabled = false
        fragmentScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    channelController.addAppToClan(clan.clanId, appId)
                }
            }
            withContext(Dispatchers.Main) {
                if (fragmentView == null || isPaused) return@withContext
                result.onSuccess {
                    showSuccessState(clan)
                }.onFailure {
                    updateAuthorizeState()
                    MezonToast.show(this@InstallClanFragment, ToastOverlay.ToastType.ERROR, getString(R.string.deeplink_install_error))
                }
            }
        }
    }

    private fun authorizeAppInstall(clan: ClanEntity, category: ClanCategoryItem) {
        val channelLabel = sanitizeChannelLabel(channelNameInput?.text?.toString().orEmpty(), appName)
        authorizeButton?.isEnabled = false
        fragmentScope.launch {
            val duplicate = withContext(Dispatchers.IO) {
                channelController.checkDuplicateChannelName(
                    channelLabel,
                    DUPLICATE_CHECK_TYPE_CHANNEL,
                    category.categoryId,
                ).getOrDefault(false)
            }
            if (duplicate) {
                withContext(Dispatchers.Main) {
                    if (fragmentView == null || isPaused) return@withContext
                    updateAuthorizeState()
                    MezonToast.show(
                        this@InstallClanFragment,
                        ToastOverlay.ToastType.ERROR,
                        getString(R.string.channel_creator_channel_name_duplicate_error),
                    )
                }
                return@launch
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    channelController.createAppChannel(
                        clanId = clan.clanId,
                        categoryId = category.categoryId,
                        channelLabel = channelLabel,
                        appId = appId
                    )
                }
            }
            withContext(Dispatchers.Main) {
                if (fragmentView == null || isPaused) return@withContext
                result.onSuccess { desc ->
                    createdChannelId = desc.channelId
                    createdChannelLabel = desc.channelLabel.ifBlank { channelLabel }
                    showSuccessState(clan)
                }.onFailure { error ->
                    updateAuthorizeState()
                    MezonToast.show(
                        this@InstallClanFragment,
                        ToastOverlay.ToastType.ERROR,
                        installErrorMessage(error),
                    )
                }
            }
        }
    }

    private fun installErrorMessage(error: Throwable): String {
        val ctx = getContext() ?: return getString(R.string.deeplink_install_error)
        val fallback = getString(R.string.deeplink_install_error)
        if (error is HttpRpcStatusException && error.code == 400) {
            val body = error.message?.substringAfter(": ")?.trim().orEmpty()
            if (body.isEmpty() || body == "(empty)") {
                return getString(R.string.channel_creator_channel_name_duplicate_error)
            }
            val parsed = body.removePrefix("RPC CreateChannelDesc failed (400): ").trim()
            if (parsed.isNotEmpty() && parsed != body) {
                return NetworkErrorMessages.userMessage(ctx, error, parsed)
            }
        }
        return NetworkErrorMessages.userMessage(ctx, error, fallback)
    }

    private fun showSuccessState(clan: ClanEntity) {
        formScroll?.visibility = View.GONE
        successContainer?.visibility = View.VISIBLE
        val title = successContainer?.findViewWithTag<TextView>("success_title")
        val message = successContainer?.findViewWithTag<TextView>("success_message")
        title?.text = getString(R.string.deeplink_install_success_title)
        message?.text = getString(
            R.string.deeplink_install_success_message,
            appName.ifBlank { getString(R.string.deeplink_install_unknown_app) },
            clan.clanName
        )
        successOpenChannelButton?.visibility = if (installKind == InstallKind.APP) View.VISIBLE else View.GONE
        MezonToast.show(this, ToastOverlay.ToastType.SUCCESS, getString(R.string.deeplink_install_success))
    }

    private fun openCreatedChannel() {
        val clan = selectedClan ?: return
        if (createdChannelId == 0L) {
            finishToHome()
            return
        }
        val activity = getParentActivity() as? MainActivity ?: return
        clansController.selectClan(clan.clanId, force = true)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
        activity.openChat(
            channelId = createdChannelId,
            channelName = createdChannelLabel.ifBlank { appName },
            clanId = clan.clanId,
            channelType = CHANNEL_TYPE_APP,
            replaceLastFragment = true,
        )
    }

    private fun finishToHome() {
        val activity = getParentActivity() as? MainActivity
        if (activity != null) {
            activity.popToMainTabsIfPresent()
        } else {
            finishFragment()
        }
    }

    override fun onBackPressed(): Boolean {
        finishToHome()
        return false
    }

    private fun onNotYouClicked() {
        (getParentActivity() as? MainActivity)?.logoutToChooseDifferentPhone()
    }

    private fun sanitizeChannelLabel(label: String, fallback: String): String {
        val source = label.ifBlank { fallback }
        return Normalizer.normalize(source, Normalizer.Form.NFC)
            .replace(CHANNEL_LABEL_REGEX, "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(32)
    }

    private fun buildSignedInRow(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val signedIn = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(themeColors.onSurfaceVariant)
            }
            signedInView = signedIn
            addView(signedIn, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
            val notYou = TextView(context).apply {
                text = getString(R.string.deeplink_install_not_you)
                textSize = 14f
                setTextColor(themeColors.primary)
                setPadding(LayoutHelper.dp(4), 0, 0, 0)
                setOnClickListener { onNotYouClicked() }
            }
            addView(notYou, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        }
    }

    private fun buildActionRow(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            val back = TextView(context).apply {
                text = getString(R.string.deeplink_install_back)
                setTextColor(themeColors.onSurface)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(LayoutHelper.dp(8), LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(12))
                setOnClickListener { finishToHome() }
            }
            addView(back, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

            addView(
                TextView(context).apply {
                    text = getString(R.string.deeplink_install_authorize_hint)
                    setTextColor(themeColors.onSurfaceVariant)
                    textSize = 11f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
            )

            val authorize = TextView(context).apply {
                text = getString(R.string.deeplink_install_authorize)
                gravity = Gravity.CENTER
                setTextColor(themeColors.onPrimary)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                val radius = LayoutHelper.dp(8f).toFloat()
                background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33000000),
                    GradientDrawable().apply {
                        cornerRadius = radius
                        setColor(themeColors.primary)
                    },
                    null
                )
                setPadding(LayoutHelper.dp(16), LayoutHelper.dp(10), LayoutHelper.dp(16), LayoutHelper.dp(10))
                isEnabled = false
                alpha = 0.5f
                setOnClickListener { onAuthorizeClicked() }
            }
            authorizeButton = authorize
            addView(authorize, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        }
    }

    private fun buildSuccessView(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(48), LayoutHelper.dp(24), LayoutHelper.dp(24))

            addView(TextView(context).apply {
                tag = "success_title"
                setTextColor(themeColors.onSurface)
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })

            addView(
                TextView(context).apply {
                    tag = "success_message"
                    setTextColor(themeColors.onSurfaceVariant)
                    textSize = 15f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(12)
                }
            )

            addView(
                buildPrimaryButton(context, getString(R.string.deeplink_install_open_channel)) {
                    openCreatedChannel()
                }.also { successOpenChannelButton = it },
                LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(24)
                }
            )

            addView(
                buildSecondaryButton(context, getString(R.string.deeplink_install_done)) {
                    finishToHome()
                },
                LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                    topMargin = LayoutHelper.dp(12)
                }
            )
        }
    }

    private fun buildMetaTextRow(context: Context, icon: MezonIcon, assignTextView: (TextView) -> Unit): LinearLayout {
        val textView = TextView(context).apply {
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 12f
            gravity = Gravity.START
        }
        assignTextView(textView)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val iconSize = LayoutHelper.dp(14)
            addView(ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context, themeColors.onSurfaceVariant))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
            addView(
                textView,
                LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
                    leftMargin = LayoutHelper.dp(8)
                }
            )
        }
    }

    private fun buildPickerRow(context: Context, onClick: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fieldBackground()
            minimumHeight = LayoutHelper.dp(44)
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(10), LayoutHelper.dp(12), LayoutHelper.dp(10))
            setOnClickListener { onClick() }
        }
    }

    private fun chevronView(context: Context): ImageView {
        val size = LayoutHelper.dp(18)
        return ImageView(context).apply {
            setImageDrawable(MezonIcon.chevronDownSmallIcon.getDrawable(context, themeColors.onSurfaceVariant))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = LayoutHelper.dp(8)
            }
        }
    }

    private fun inlineErrorView(context: Context): TextView {
        return TextView(context).apply {
            visibility = View.GONE
            setTextColor(themeColors.error)
            textSize = 12f
        }
    }

    private fun showClanError(message: String) {
        clanErrorView?.text = message
        clanErrorView?.visibility = View.VISIBLE
    }

    private fun showCategoryError(message: String) {
        categoryErrorView?.text = message
        categoryErrorView?.visibility = View.VISIBLE
    }

    private fun clearClanError() {
        clanErrorView?.visibility = View.GONE
    }

    private fun clearCategoryError() {
        categoryErrorView?.visibility = View.GONE
    }

    private fun fieldBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(8f).toFloat()
            setColor(themeColors.surface)
            setStroke(LayoutHelper.dp(1), themeColors.outline)
        }
    }

    private fun formLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(themeColors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
            gravity = Gravity.START
        }
    }

    private fun fieldLabelMargin(): LinearLayout.LayoutParams {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(12)
        }
    }

    private fun fieldRowMargin(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(8)
        }
    }

    private fun fieldErrorMargin(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(4)
        }
    }

    private fun fieldBlockMargin(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
    }

    private fun metaRowMargin(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            topMargin = LayoutHelper.dp(16)
        }
    }

    private fun buildPrimaryButton(context: Context, label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(themeColors.onPrimary)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            val radius = LayoutHelper.dp(8f).toFloat()
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33000000),
                GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(themeColors.primary)
                },
                null
            )
            setPadding(0, LayoutHelper.dp(14), 0, LayoutHelper.dp(14))
            setOnClickListener { onClick() }
        }
    }

    private fun buildSecondaryButton(context: Context, label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(themeColors.onSurface)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            val radius = LayoutHelper.dp(8f).toFloat()
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000),
                GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(themeColors.surfaceVariant)
                },
                null
            )
            setPadding(0, LayoutHelper.dp(14), 0, LayoutHelper.dp(14))
            setOnClickListener { onClick() }
        }
    }
}
