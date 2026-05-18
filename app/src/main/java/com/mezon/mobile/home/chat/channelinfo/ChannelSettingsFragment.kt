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
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ChannelPermissionController
import com.mezon.mobile.home.clans.PermissionPolicy
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers
import com.mezon.mobile.home.chat.channelinfo.permissions.ChannelPermissionsFragment
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.ui.cells.MezonIcon

class ChannelSettingsFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CHANNEL_TYPE = "channelType"
        private const val ARG_CHANNEL_PRIVATE = "channelPrivate"

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
    private var nameField: EditText? = null
    private var topicField: EditText? = null
    private var saveText: TextView? = null
    private var originalName = ""
    private var originalTopic = ""

    override fun onInject(entryPoint: FragmentEntryPoint) {
        channelController = entryPoint.channelController()
        permissionController = entryPoint.channelPermissionController()
        permissionPolicy = entryPoint.permissionPolicy()
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
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(20f), LayoutHelper.dp(8f), LayoutHelper.dp(20f), LayoutHelper.dp(28f))
        }
        scroll.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        bindInitialValues()
        content.addView(
            buildInput(context, originalName, multiline = false),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 28f)
        )

        content.addView(sectionLabel(context, getString(R.string.channel_settings_channel_topic)))
        content.addView(
            buildInput(context, originalTopic, multiline = true),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 150, 0f, Gravity.NO_GRAVITY, 0f, 10f, 0f, 40f)
        )

        content.addView(
            ClanSettingsUiHelpers.buildMezonSection(context, themeColors, null, buildTopRows(context)),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

        content.addView(
            TextView(context).apply {
                text = getString(R.string.channel_settings_permission_description)
                textSize = 14f
                setTextColor(themeColors.textDisabled)
                setLineSpacing(LayoutHelper.dpf(2f), 1f)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 18f, 0f, 28f)
        )

        content.addView(
            ClanSettingsUiHelpers.buildMezonSection(context, themeColors, null, buildBottomRows(context)),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )

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

    private fun buildHeader(context: Context): FrameLayout {
        return FrameLayout(context).apply {
            setBackgroundColor(themeColors.serverRailBg)

            addView(
                iconButton(context, MezonIcon.closeIcon) { finishFragment() },
                FrameLayout.LayoutParams(LayoutHelper.dp(56f), LayoutHelper.dp(56f), Gravity.START or Gravity.BOTTOM)
            )

            addView(
                TextView(context).apply {
                    text = getString(R.string.channel_settings_title)
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
                setOnClickListener {
                    if (isEnabled) {
                        Toast.makeText(context, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
                    }
                }
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

    private fun buildInput(context: Context, value: String, multiline: Boolean): EditText {
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
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateSaveState()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        if (multiline) {
            topicField = field
        } else {
            nameField = field
        }
        return field
    }

    private fun buildTopRows(context: Context): List<View> {
        return listOf(
            ClanSettingsUiHelpers.buildMezonChevronRow(
                context,
                themeColors,
                MezonIcon.clipboardIcon,
                getString(R.string.channel_settings_change_category),
                null,
                Runnable { showComingSoon(context) }
            ),
            ClanSettingsUiHelpers.buildMezonChevronRow(
                context,
                themeColors,
                MezonIcon.bravePermission,
                getString(R.string.channel_settings_permissions),
                null,
                Runnable { openChannelPermissions(context) }
            ),
            ClanSettingsUiHelpers.buildMezonChevronRow(
                context,
                themeColors,
                MezonIcon.quickAction,
                getString(R.string.channel_settings_quick_action),
                null,
                Runnable { showComingSoon(context) }
            ),
            ClanSettingsUiHelpers.buildMezonChevronRow(
                context,
                themeColors,
                MezonIcon.hammerIcon,
                getString(R.string.channel_settings_ban_list),
                null,
                Runnable { showComingSoon(context) }
            )
        )
    }

    private fun buildBottomRows(context: Context): List<View> {
        return listOf(
            ClanSettingsUiHelpers.buildMezonChevronRow(
                context,
                themeColors,
                MezonIcon.webhookIcon,
                getString(R.string.channel_settings_webhooks),
                null,
                Runnable { showComingSoon(context) }
            ),
            ClanSettingsUiHelpers.buildMezonChevronRow(
                context,
                themeColors,
                MezonIcon.trashIcon,
                getString(R.string.channel_settings_delete_channel),
                themeColors.redStrong,
                Runnable { showComingSoon(context) }
            )
        )
    }

    private fun openChannelPermissions(context: Context) {
        if (clanId == 0L || channelId == 0L) {
            showComingSoon(context)
            return
        }
        val parentId = currentChannel()?.parentId ?: 0L
        if (!permissionPolicy.canOpenChannelSettings(channelId, clanId, channelType, parentId)) {
            permissionController.loadChannelPermissionData(clanId, channelId, channelType, force = true)
            Toast.makeText(context, R.string.channel_permissions_no_access, Toast.LENGTH_SHORT).show()
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

    private fun showComingSoon(context: Context) {
        Toast.makeText(context, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
    }

    private fun bindInitialValues() {
        val channel = currentChannel()
        originalName = channel?.channelLabel?.takeIf { it.isNotBlank() } ?: channelName
        originalTopic = channel?.topic.orEmpty()
    }

    private fun syncFieldsFromChannel() {
        val channel = currentChannel() ?: return
        originalName = channel.channelLabel
        originalTopic = channel.topic
        nameField?.setText(originalName)
        topicField?.setText(originalTopic)
        updateSaveState()
    }

    private fun currentChannel(): ClanChannelEntity? =
        channelController.findChannelById(channelId, clanId)

    private fun updateSaveState() {
        val changed = nameField?.text?.toString().orEmpty() != originalName ||
            topicField?.text?.toString().orEmpty() != originalTopic
        saveText?.isEnabled = changed
        saveText?.setTextColor(if (changed) themeColors.blurple else themeColors.textDisabled)
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = LayoutHelper.dpf(radiusDp)
            setColor(color)
        }
}
