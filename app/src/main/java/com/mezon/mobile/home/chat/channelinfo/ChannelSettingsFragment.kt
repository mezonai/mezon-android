package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertDialog
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.CHANNEL_TYPE_APP
import com.mezon.mobile.home.clans.CHANNEL_TYPE_STREAMING
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ChannelPermissionController
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers
import com.mezon.mobile.home.clans.settings.WebhooksListFragment
import com.mezon.mobile.home.chat.channelinfo.permissions.ChannelPermissionsFragment
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.network.CHANNEL_TYPE_THREAD
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.TextCheckCell
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.ChannelSettingsNameValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelSettingsFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_CHANNEL_PRIVATE = "channelPrivate"
        private const val DUPLICATE_DEBOUNCE_MS = 300L

        fun newInstance(
            channelId: Long,
            channelName: String,
            clanId: Long,
            channelType: Int,
            isChannelPrivate: Boolean,
        ): ChannelSettingsFragment =
            ChannelSettingsFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putString(ARG_CHANNEL_NAME, channelName)
                    putLong(ARG_CLAN_ID, clanId)
                    putInt(ARG_CHANNEL_TYPE, channelType)
                    putBoolean(ARG_CHANNEL_PRIVATE, isChannelPrivate)
                }
            }
    }

    private var channelId = 0L
    private var channelName = ""
    private var clanId = 0L
    private var channelType = 0
    private var routePrivate = false

    private lateinit var channelController: ChannelController
    private lateinit var permissionController: ChannelPermissionController
    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var clansController: ClansController

    private var nameField: EditText? = null
    private var topicField: EditText? = null
    private var nameErrorView: TextView? = null
    private var saveText: TextView? = null
    private var contentLayout: LinearLayout? = null
    private var permissionDescView: View? = null

    private var originalName = ""
    private var originalTopic = ""
    private var originalAgeRestricted = 0
    private var ageRestrictedCell: TextCheckCell? = null
    private var nameValidationError: String? = null
    private var isDuplicateName = false
    private var duplicateCheckJob: Job? = null
    private var saving = false

    private val isThread: Boolean
        get() {
            val ch = currentChannel()
            return channelType == CHANNEL_TYPE_THREAD || (ch?.parentId ?: 0L) != 0L
        }

    override fun onInject(entryPoint: FragmentEntryPoint) {
        channelController = entryPoint.channelController()
        permissionController = entryPoint.channelPermissionController()
        permissionPolicy = entryPoint.permissionPolicy()
        clansController = entryPoint.clansController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME).orEmpty()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelType = arguments?.getInt(ARG_CHANNEL_TYPE) ?: CHANNEL_TYPE_CHANNEL
        routePrivate = arguments?.getBoolean(ARG_CHANNEL_PRIVATE) == true

        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            val loadedClanId = args.firstOrNull() as? Long ?: return@observe
            if (loadedClanId == clanId && !isPaused) syncFieldsFromChannel()
        }

        if (clanId != 0L) {
            channelController.loadChannelsForClan(clanId)
            permissionController.loadChannelPermissionData(clanId, channelId, channelType)
            if (!isThread) {
                fragmentScope.launch { channelController.loadCategoriesForClan(clanId) }
            }
        }
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.serverRailBg)
        }

        root.addView(
            buildHeader(context),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.statusBarHeight + LayoutHelper.dp(56f))
        )

        val scroll = NestedScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(20f), LayoutHelper.dp(8f), LayoutHelper.dp(20f), LayoutHelper.dp(28f))
        }
        scroll.addView(contentLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        bindInitialValues()
        rebuildContent(context)

        fragmentView = root
        updateSaveState()
        return root
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (clanId != 0L) {
            permissionController.loadChannelPermissionData(clanId, channelId, channelType)
        }
        syncFieldsFromChannel()
    }

    private fun rebuildContent(context: Context) {
        val content = contentLayout ?: return
        content.removeAllViews()

        if (isThread) {
            content.addView(sectionLabel(context, getString(R.string.channel_settings_thread_name_title)))
        }
        val nameWrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        nameWrap.addView(
            buildInput(context, originalName, multiline = false, isName = true),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56)
        )
        nameErrorView = TextView(context).apply {
            textSize = 13f
            setTextColor(themeColors.redStrong)
            visibility = View.GONE
            setPadding(LayoutHelper.dp(4f), LayoutHelper.dp(6f), 0, 0)
        }
        nameWrap.addView(nameErrorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        content.addView(nameWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, if (isThread) 28f else 28f))

        if (!isThread) {
            content.addView(sectionLabel(context, getString(R.string.channel_settings_channel_topic)))
            content.addView(
                buildInput(context, originalTopic, multiline = true, isName = false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 150, 0f, Gravity.NO_GRAVITY, 0f, 10f, 0f, 40f)
            )
        } else {
            content.addView(sectionLabel(context, getString(R.string.channel_settings_topic_title)))
            content.addView(
                buildInput(context, originalTopic, multiline = true, isName = false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 120, 0f, Gravity.NO_GRAVITY, 0f, 10f, 0f, 40f)
            )
        }

        if (showAgeRestrictedToggle()) {
            content.addView(
                ClanSettingsUiHelpers.buildMezonSection(
                    context,
                    themeColors,
                    null,
                    listOf(buildAgeRestrictedRow(context)),
                ),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 28f),
            )
        }

        val topRows = buildTopRows(context)
        if (topRows.isNotEmpty()) {
            content.addView(
                ClanSettingsUiHelpers.buildMezonSection(context, themeColors, null, topRows),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 20f)
            )
        }

        if (showPermissionDescription()) {
            permissionDescView = TextView(context).apply {
                text = getString(R.string.channel_settings_permission_description)
                textSize = 14f
                setTextColor(themeColors.textDisabled)
                setLineSpacing(LayoutHelper.dpf(2f), 1f)
            }
            content.addView(
                permissionDescView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 18f, 0f, 28f)
            )
        }

        val bottomRows = buildBottomRows(context)
        if (bottomRows.isNotEmpty()) {
            content.addView(
                ClanSettingsUiHelpers.buildMezonSection(context, themeColors, null, bottomRows),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
            )
        }
    }

    private fun buildHeader(context: Context): FrameLayout {
        val titleRes = if (isThread) R.string.channel_settings_title_thread else R.string.channel_settings_title
        return FrameLayout(context).apply {
            setBackgroundColor(themeColors.serverRailBg)

            addView(
                iconButton(context, MezonIcon.closeIcon) { finishFragment() },
                FrameLayout.LayoutParams(LayoutHelper.dp(56f), LayoutHelper.dp(56f), Gravity.START or Gravity.BOTTOM)
            )

            addView(
                TextView(context).apply {
                    text = getString(titleRes)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(themeColors.textStrong)
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(56f), Gravity.BOTTOM).apply {
                    leftMargin = LayoutHelper.dp(72f)
                    rightMargin = LayoutHelper.dp(72f)
                }
            )

            saveText = TextView(context).apply {
                text = getString(R.string.channel_settings_save)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f), 0)
                setOnClickListener { if (isEnabled) onSavePressed() }
            }
            addView(
                saveText,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, LayoutHelper.dp(56f), Gravity.END or Gravity.BOTTOM)
            )
        }
    }

    private fun iconButton(context: Context, icon: MezonIcon, onPress: () -> Unit): FrameLayout {
        return FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(themeColors.colorText and 0x1AFFFFFF),
                ColorDrawable(Color.TRANSPARENT),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            )
            setOnClickListener { onPress() }
            addView(
                ImageView(context).apply {
                    val d = icon.getDrawable(context)
                    d.colorFilter = PorterDuffColorFilter(themeColors.colorText, PorterDuff.Mode.SRC_IN)
                    setImageDrawable(d)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                FrameLayout.LayoutParams(LayoutHelper.dp(30f), LayoutHelper.dp(30f), Gravity.CENTER)
            )
        }
    }

    private fun sectionLabel(context: Context, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColors.textStrong)
        }

    private fun buildInput(context: Context, value: String, multiline: Boolean, isName: Boolean): EditText {
        val field = EditText(context).apply {
            setText(value)
            setTextColor(themeColors.textStrong)
            setHintTextColor(themeColors.textDisabled)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            background = rounded(themeColors.channelPanelBg, 16f)
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(12f), LayoutHelper.dp(14f), LayoutHelper.dp(12f))
            isSingleLine = !multiline
            gravity = if (multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.START
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            imeOptions = if (multiline) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_DONE
            if (multiline) {
                filters = arrayOf(InputFilter.LengthFilter(ChannelSettingsNameValidator.TOPIC_MAX_LENGTH))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (isName) scheduleDuplicateCheck(s?.toString().orEmpty())
                    updateSaveState()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        if (multiline) topicField = field else nameField = field
        return field
    }

    private fun buildTopRows(context: Context): List<View> {
        val rows = ArrayList<View>()
        if (!isThread && showChangeCategory()) {
            rows.add(
                ClanSettingsUiHelpers.buildMezonChevronRow(
                    context, themeColors, MezonIcon.clipboardIcon,
                    getString(R.string.channel_settings_change_category), null,
                    Runnable { openChangeCategory(context) }
                )
            )
        }
        if (showPermissions()) {
            rows.add(
                ClanSettingsUiHelpers.buildMezonChevronRow(
                    context, themeColors, MezonIcon.bravePermission,
                    getString(R.string.channel_settings_permissions), null,
                    Runnable { openChannelPermissions(context) }
                )
            )
        }
        if (showQuickAction()) {
            rows.add(
                ClanSettingsUiHelpers.buildMezonChevronRow(
                    context, themeColors, MezonIcon.quickAction,
                    getString(R.string.channel_settings_quick_action), null,
                    Runnable { openQuickAction() }
                )
            )
        }
        if (!isThread && showBanList()) {
            rows.add(
                ClanSettingsUiHelpers.buildMezonChevronRow(
                    context, themeColors, MezonIcon.hammerIcon,
                    getString(R.string.channel_settings_ban_list), null,
                    Runnable { openBanList() }
                )
            )
        }
        return rows
    }

    private fun buildBottomRows(context: Context): List<View> {
        val rows = ArrayList<View>()
        if (!isThread && showWebhooks()) {
            rows.add(
                ClanSettingsUiHelpers.buildMezonChevronRow(
                    context, themeColors, MezonIcon.webhookIcon,
                    getString(R.string.channel_settings_webhooks), null,
                    Runnable { openWebhooks() }
                )
            )
        }
        if (isThread) {
            rows.add(
                ClanSettingsUiHelpers.buildMezonChevronRow(
                    context, themeColors, MezonIcon.doorExitIcon,
                    getString(R.string.channel_settings_menu_leave_thread), null,
                    Runnable { confirmLeaveThread() }
                )
            )
        }
        if (showDelete()) {
            val deleteLabel = if (isThread) R.string.channel_settings_menu_delete_thread
            else R.string.channel_settings_delete_channel
            rows.add(
                ClanSettingsUiHelpers.buildMezonChevronRow(
                    context, themeColors, MezonIcon.trashIcon,
                    getString(deleteLabel), themeColors.redStrong,
                    Runnable { confirmDelete() }
                )
            )
        }
        return rows
    }

    private fun showAgeRestrictedToggle(): Boolean =
        !isThread &&
            channelType != CHANNEL_TYPE_VOICE &&
            channelType != CHANNEL_TYPE_STREAMING

    private fun buildAgeRestrictedRow(context: Context): View {
        val cell = TextCheckCell(context, themeColors).apply {
            setTextAndCheck(
                getString(R.string.channel_settings_age_restricted_title),
                getString(R.string.channel_settings_age_restricted_description),
                originalAgeRestricted == 1,
                divider = false,
            )
            onCheckedChange = { updateSaveState() }
        }
        ageRestrictedCell = cell
        return cell
    }

    private fun showChangeCategory(): Boolean = !isRestrictedChannelType()
    private fun showPermissions(): Boolean = !isThread && !isRestrictedChannelType()
    private fun showQuickAction(): Boolean = !isThread && channelType != CHANNEL_TYPE_VOICE && channelType != CHANNEL_TYPE_STREAMING && channelType != CHANNEL_TYPE_APP
    private fun showBanList(): Boolean = !isRestrictedChannelType()
    private fun showWebhooks(): Boolean = channelType != CHANNEL_TYPE_VOICE && channelType != CHANNEL_TYPE_STREAMING
    private fun showPermissionDescription(): Boolean = showPermissions()
    private fun isRestrictedChannelType(): Boolean =
        channelType == CHANNEL_TYPE_VOICE || channelType == CHANNEL_TYPE_STREAMING || channelType == CHANNEL_TYPE_APP

    private fun showDelete(): Boolean {
        if (isWelcomeChannel()) return false
        return true
    }

    private fun isWelcomeChannel(): Boolean {
        val clan = clansController.clans.value.firstOrNull { it.clanId == clanId }
            ?: return true
        return clan.welcomeChannelId != 0L && clan.welcomeChannelId == channelId
    }

    private fun scheduleDuplicateCheck(raw: String) {
        duplicateCheckJob?.cancel()
        val trimmed = raw.trim()
        nameValidationError = validateNameLocal(trimmed)
        updateNameErrorUi()
        updateSaveState()
        if (nameValidationError != null || trimmed == originalName.trim()) {
            isDuplicateName = false
            return
        }
        val ch = currentChannel() ?: return
        val checkType = if (isThread) ChannelSettingsNameValidator.TYPE_THREAD else ChannelSettingsNameValidator.TYPE_CHANNEL
        val conditionId = if (isThread) ch.parentId else ch.categoryId
        duplicateCheckJob = fragmentScope.launch {
            delay(DUPLICATE_DEBOUNCE_MS)
            val duplicate = channelController.checkDuplicateChannelName(trimmed, checkType, conditionId)
                .getOrDefault(false)
            if (isFinished) return@launch
            val currentText = nameField?.text?.toString()?.trim().orEmpty()
            if (currentText != trimmed) return@launch
            isDuplicateName = duplicate
            if (duplicate) {
                nameValidationError = getString(
                    if (isThread) R.string.channel_settings_name_duplicate_thread
                    else R.string.channel_settings_name_duplicate_channel
                )
            } else {
                nameValidationError = validateNameLocal(trimmed)
            }
            updateNameErrorUi()
            updateSaveState()
        }
    }

    private fun validateNameLocal(trimmed: String): String? {
        if (trimmed.isEmpty()) {
            return getString(if (isThread) R.string.channel_settings_name_invalid_thread else R.string.channel_settings_name_invalid_channel)
        }
        if (!ChannelSettingsNameValidator.isValidName(trimmed)) {
            return getString(if (isThread) R.string.channel_settings_name_invalid_thread else R.string.channel_settings_name_invalid_channel)
        }
        return null
    }

    private fun updateNameErrorUi() {
        val err = nameValidationError
        nameErrorView?.let { v ->
            if (err.isNullOrBlank()) {
                v.visibility = View.GONE
            } else {
                v.text = err
                v.visibility = View.VISIBLE
            }
        }
    }

    private fun onSavePressed() {
        if (saving) return
        val label = nameField?.text?.toString()?.trim().orEmpty()
        val topic = topicField?.text?.toString().orEmpty()
        nameValidationError = validateNameLocal(label)
        if (nameValidationError != null || isDuplicateName) {
            updateNameErrorUi()
            updateSaveState()
            return
        }
        val ch = currentChannel() ?: return
        val ageRestricted = if (ageRestrictedCell != null) {
            if (ageRestrictedCell?.isChecked() == true) 1 else 0
        } else {
            ch.ageRestricted
        }
        saving = true
        saveText?.isEnabled = false
        fragmentScope.launch {
            val result = channelController.updateChannelDescSettings(
                clanId = clanId,
                channelId = channelId,
                channelLabel = label,
                categoryId = ch.categoryId,
                topic = topic,
                appId = 0L,
                ageRestricted = ageRestricted,
            )
            withContext(Dispatchers.Main.immediate) {
                saving = false
                if (result.isSuccess) {
                    originalName = label
                    originalTopic = topic
                    originalAgeRestricted = ageRestricted
                    ageRestrictedCell?.setChecked(ageRestricted == 1)
                    isDuplicateName = false
                    nameValidationError = null
                    updateNameErrorUi()
                    updateSaveState()
                    MezonToast.show(this@ChannelSettingsFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.channel_settings_updated))
                } else {
                    MezonToast.show(this@ChannelSettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.common_something_went_wrong))
                    updateSaveState()
                }
            }
        }
    }

    private fun openChangeCategory(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (clanId == 0L || channelId == 0L) return
        val ch = currentChannel()
        val label = ch?.channelLabel?.ifBlank { channelName } ?: channelName
        val categoryId = ch?.categoryId ?: 0L
        val categoryName = ch?.let { channelController.resolveCategoryDisplayName(clanId, it) }.orEmpty()
        presentFragment(
            ChannelChangeCategoryFragment.newInstance(
                channelId,
                label,
                clanId,
                categoryId,
                categoryName,
            ),
        )
    }

    private fun openChannelPermissions(context: Context) {
        if (clanId == 0L || channelId == 0L) return
        val parentId = currentChannel()?.parentId ?: 0L
        if (!permissionPolicy.canOpenChannelSettings(channelId, clanId, channelType, parentId)) {
            permissionController.loadChannelPermissionData(clanId, channelId, channelType, force = true)
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.channel_permissions_no_access))
            return
        }
        presentFragment(
            ChannelPermissionsFragment.newInstance(
                channelId = channelId,
                channelName = currentChannel()?.channelLabel ?: channelName,
                clanId = clanId,
                channelType = channelType,
                isChannelPrivate = currentChannel()?.isPrivate ?: routePrivate,
            )
        )
    }

    private fun openQuickAction() {
        presentFragment(
            ChannelQuickActionFragment.newInstance(
                channelId = channelId,
                channelName = currentChannel()?.channelLabel ?: channelName,
                clanId = clanId,
            )
        )
    }

    private fun openBanList() {
        presentFragment(
            ChannelBanListFragment.newInstance(
                channelId = channelId,
                channelName = currentChannel()?.channelLabel ?: channelName,
                clanId = clanId,
            )
        )
    }

    private fun openWebhooks() {
        presentFragment(WebhooksListFragment.newInstanceForChannel(clanId, channelId))
    }

    private fun confirmDelete() {
        val act = getParentActivity() ?: return
        val label = currentChannel()?.channelLabel ?: channelName
        if (isWelcomeChannel()) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.channel_settings_delete_system_channel))
            return
        }
        val titleRes = if (isThread) R.string.channel_settings_delete_confirm_thread_title
        else R.string.channel_settings_delete_confirm_channel_title
        AlertDialog.Builder(act)
            .setTitle(getString(titleRes))
            .setMessage(getString(R.string.channel_settings_delete_confirm_message, label))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_delete)) { _, _ -> performDelete() }
            .show()
    }

    private fun performDelete() {
        val parentId = currentChannel()?.parentId ?: 0L
        val parentLabel = channelController.findChannelById(parentId, clanId)?.channelLabel.orEmpty()
        fragmentScope.launch {
            val result = channelController.deleteChannelDesc(clanId, channelId, channelType)
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    finishFragment()
                    if (isThread && parentId != 0L) {
                        (getParentActivity() as? MainActivity)?.openChat(
                            parentId,
                            parentLabel,
                            clanId,
                            CHANNEL_TYPE_CHANNEL,
                        )
                    }
                } else {
                    val msg = result.exceptionOrNull()?.message?.takeIf { it.length < 200 }
                        ?: getString(R.string.common_something_went_wrong)
                    MezonToast.show(this@ChannelSettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.channel_settings_delete_failed, msg))
                }
            }
        }
    }

    private fun confirmLeaveThread() {
        val act = getParentActivity() ?: return
        val label = currentChannel()?.channelLabel ?: channelName
        AlertDialog.Builder(act)
            .setTitle(getString(R.string.channel_settings_leave_confirm_title))
            .setMessage(getString(R.string.channel_settings_leave_confirm_message, label))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.channel_settings_menu_leave_thread)) { _, _ -> performLeaveThread() }
            .show()
    }

    private fun performLeaveThread() {
        val parentId = currentChannel()?.parentId ?: 0L
        val parentLabel = channelController.findChannelById(parentId, clanId)?.channelLabel.orEmpty()
        fragmentScope.launch {
            val result = channelController.leaveThread(clanId, channelId, parentId)
            withContext(Dispatchers.Main.immediate) {
                if (result.isSuccess) {
                    finishFragment()
                    if (parentId != 0L) {
                        (getParentActivity() as? MainActivity)?.openChat(
                            parentId,
                            parentLabel,
                            clanId,
                            CHANNEL_TYPE_CHANNEL,
                        )
                    }
                } else {
                    val msg = result.exceptionOrNull()?.message?.takeIf { it.length < 200 }
                        ?: getString(R.string.common_something_went_wrong)
                    MezonToast.show(this@ChannelSettingsFragment, ToastOverlay.ToastType.ERROR, getString(R.string.channel_settings_leave_failed, msg))
                }
            }
        }
    }

    private fun bindInitialValues() {
        val channel = currentChannel()
        originalName = channel?.channelLabel?.takeIf { it.isNotBlank() } ?: channelName
        originalTopic = channel?.topic.orEmpty()
        originalAgeRestricted = channel?.ageRestricted ?: 0
    }

    private fun hasUnsavedEdits(): Boolean {
        val ageRestrictedDraft = if (ageRestrictedCell != null) {
            if (ageRestrictedCell?.isChecked() == true) 1 else 0
        } else {
            originalAgeRestricted
        }
        return nameField?.text?.toString()?.trim().orEmpty() != originalName.trim() ||
            topicField?.text?.toString().orEmpty() != originalTopic ||
            ageRestrictedDraft != originalAgeRestricted
    }

    private fun syncFieldsFromChannel() {
        if (hasUnsavedEdits()) return
        val channel = currentChannel() ?: return
        originalName = channel.channelLabel
        originalTopic = channel.topic
        originalAgeRestricted = channel.ageRestricted
        nameField?.setText(originalName)
        topicField?.setText(originalTopic)
        ageRestrictedCell?.setChecked(channel.ageRestricted == 1)
        updateSaveState()
    }

    private fun currentChannel(): ClanChannelEntity? =
        channelController.findChannelById(channelId, clanId)

    private fun updateSaveState() {
        val ageRestrictedDraft = if (ageRestrictedCell != null) {
            if (ageRestrictedCell?.isChecked() == true) 1 else 0
        } else {
            originalAgeRestricted
        }
        val changed = nameField?.text?.toString()?.trim().orEmpty() != originalName.trim() ||
            topicField?.text?.toString().orEmpty() != originalTopic ||
            ageRestrictedDraft != originalAgeRestricted
        val canSave = changed && nameValidationError == null && !isDuplicateName && !saving
        saveText?.isEnabled = canSave
        saveText?.setTextColor(if (canSave) themeColors.blurple else themeColors.textDisabled)
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = LayoutHelper.dpf(radiusDp)
            setColor(color)
        }
}
