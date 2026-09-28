package com.mezon.mobile.home.chat.channelinfo

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.CHANNEL_TYPE_VOICE
import com.mezon.mobile.home.clans.ClanCategoryItem
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.settings.ClanRolesUiTheme
import com.mezon.mobile.home.clans.settings.ClanSettingsUiHelpers
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.ChannelSettingsNameValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ChannelChangeCategoryFragment : BaseFragment() {

    companion object {
        private const val ARG_CHANNEL_ID = "channelId"
        private const val ARG_CHANNEL_NAME = "channelName"
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CATEGORY_ID = "categoryId"
        private const val ARG_CATEGORY_NAME = "categoryName"

        fun newInstance(
            channelId: Long,
            channelName: String,
            clanId: Long,
            currentCategoryId: Long,
            currentCategoryName: String,
        ): ChannelChangeCategoryFragment =
            ChannelChangeCategoryFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putString(ARG_CHANNEL_NAME, channelName)
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_CATEGORY_ID, currentCategoryId)
                    putString(ARG_CATEGORY_NAME, currentCategoryName)
                }
            }
    }

    private var channelId = 0L
    private var channelName = ""
    private var clanId = 0L
    private var routeCategoryId = 0L
    private var routeCategoryName = ""

    private lateinit var channelController: ChannelController

    private lateinit var contentFrame: FrameLayout
    private lateinit var listHost: LinearLayout
    private lateinit var moveFromLabel: TextView
    private var loadingBar: ProgressBar? = null
    private var moving = false
    private var nameField: EditText? = null

    private val moveTargets = ArrayList<ClanCategoryItem>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        channelController = entryPoint.channelController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        channelName = arguments?.getString(ARG_CHANNEL_NAME).orEmpty()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        routeCategoryId = arguments?.getLong(ARG_CATEGORY_ID) ?: 0L
        routeCategoryName = arguments?.getString(ARG_CATEGORY_NAME).orEmpty()

        observe(NotificationCenter.channelsDidLoad) { _, _, args ->
            val loadedClanId = args.firstOrNull() as? Long ?: return@observe
            if (loadedClanId == clanId && !isPaused) refreshUi()
        }
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        reloadCategories()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ClanRolesUiTheme.applyPrimaryFlowRoot(root, themeColors)

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.channel_change_category_title))
            setBackButtonImage(R.drawable.ic_arrow_back)
            setCenterTitle(true)
            ClanRolesUiTheme.applyPrimaryFlowActionBar(this, themeColors)
            if (currentChannel()?.type == CHANNEL_TYPE_VOICE) {
                createMenu().addItem(1, getString(R.string.common_save)).addView(TextView(context).apply {
                    text = getString(R.string.common_save)
                    textSize = 16f
                    setTextColor(themeColors.primary)
                    gravity = Gravity.CENTER
                    setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
                }, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT))
            }
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                    if (id == 1) saveChannelName()
                }
            })
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val pad = LayoutHelper.dp(16f)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, LayoutHelper.dp(20f), pad, LayoutHelper.dp(24f))
        }

        if (currentChannel()?.type == CHANNEL_TYPE_VOICE) {
            body.addView(TextView(context).apply {
                text = getString(R.string.channel_category_management_channel_name)
                setTextColor(themeColors.textStrong)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            nameField = EditText(context).apply {
                setText(currentChannel()?.channelLabel ?: channelName)
                setSingleLine(true)
                setTextColor(themeColors.textStrong)
                textSize = 16f
                setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(12f), LayoutHelper.dp(14f), LayoutHelper.dp(12f))
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dpf(14f)
                    setColor(themeColors.channelPanelBg)
                }
            }
            body.addView(nameField, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 8f, 0f, 24f,
            ))
        }

        moveFromLabel = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            setTextColor(themeColors.textDisabled)
        }
        body.addView(
            moveFromLabel,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 16f),
        )

        contentFrame = FrameLayout(context)
        listHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        loadingBar = ProgressBar(context)
        contentFrame.addView(listHost, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        contentFrame.addView(loadingBar, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))
        body.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        root.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        fragmentView = root
        refreshUi()
        return root
    }

    private fun reloadCategories() {
        if (clanId == 0L || !::contentFrame.isInitialized) return
        loadingBar?.visibility = View.VISIBLE
        listHost.visibility = View.INVISIBLE
        fragmentScope.launch {
            runCatching { channelController.loadChannelsForClanSuspend(clanId) }
            runCatching { channelController.loadCategoriesForClan(clanId, force = true) }
            withContext(Dispatchers.Main.immediate) {
                loadingBar?.visibility = View.GONE
                listHost.visibility = View.VISIBLE
                if (!isFinished) refreshUi()
            }
        }
    }

    private fun refreshUi() {
        if (!::moveFromLabel.isInitialized) return

        val channel = currentChannel()
        val excludeCategoryId = channel?.categoryId?.takeIf { it != 0L } ?: routeCategoryId
        val currentLabel = channel?.let { channelController.resolveCategoryDisplayName(clanId, it) }
            ?.ifBlank { routeCategoryName }
            ?: routeCategoryName

        moveFromLabel.text = getString(
            R.string.channel_change_category_move_header,
            currentLabel.ifBlank { "—" }.uppercase(Locale.getDefault()),
        )

        moveTargets.clear()
        moveTargets.addAll(channelController.categoriesForMove(clanId, excludeCategoryId, channel))
        renderCategoryList()
    }

    private fun renderCategoryList() {
        if (!::listHost.isInitialized) return
        listHost.removeAllViews()
        val ctx = getContext() ?: return

        if (moveTargets.isEmpty()) {
            listHost.addView(
                TextView(ctx).apply {
                    text = getString(R.string.channel_category_picker_empty)
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setTextColor(themeColors.textDisabled)
                    setPadding(0, LayoutHelper.dp(32f), 0, LayoutHelper.dp(32f))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
            )
            return
        }

        val rows = moveTargets.map { category ->
            buildCategoryRow(ctx, category.categoryName) { onCategorySelected(category) }
        }
        listHost.addView(
            ClanSettingsUiHelpers.buildMezonSection(ctx, themeColors, null, rows),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )
    }

    private fun buildCategoryRow(context: Context, categoryName: String, onClick: () -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                TextView(context).apply {
                    text = categoryName
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColors.textStrong)
                    setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(14f), LayoutHelper.dp(16f), LayoutHelper.dp(14f))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
            )
        }
    }

    private fun onCategorySelected(target: ClanCategoryItem) {
        if (moving) return
        performMove(target)
    }

    private fun saveChannelName() {
        if (moving) return
        moving = true
        fragmentScope.launch {
            try { saveNameIfChanged() } finally { moving = false }
        }
    }

    private suspend fun saveNameIfChanged(): Boolean {
        val name = nameField?.text?.toString()?.trim() ?: return true
        val channel = currentChannel() ?: return false
        if (name == channel.channelLabel) return true
        if (!ChannelSettingsNameValidator.isValidName(name)) {
            nameField?.error = getString(R.string.channel_settings_name_invalid_channel)
            return false
        }
        val duplicate = channelController.checkDuplicateChannelName(
            name, ChannelSettingsNameValidator.TYPE_CHANNEL, channel.categoryId,
        )
        if (duplicate.isFailure) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.common_something_went_wrong))
            return false
        }
        if (duplicate.getOrDefault(false)) {
            nameField?.error = getString(R.string.channel_settings_name_duplicate_channel)
            return false
        }
        val result = channelController.updateChannelDescSettings(
            clanId, channelId, name, channel.categoryId, channel.topic, ageRestricted = channel.ageRestricted,
        )
        MezonToast.show(this, if (result.isSuccess) ToastOverlay.ToastType.SUCCESS else ToastOverlay.ToastType.ERROR,
            getString(if (result.isSuccess) R.string.channel_settings_updated else R.string.common_something_went_wrong))
        return result.isSuccess
    }

    private fun performMove(target: ClanCategoryItem) {
        if (moving) return
        moving = true
        loadingBar?.visibility = View.VISIBLE
        fragmentScope.launch {
            try {
                if (!saveNameIfChanged()) return@launch
                val result = channelController.changeChannelCategory(clanId, channelId, target)
                withContext(Dispatchers.Main.immediate) {
                    if (result.isSuccess) {
                        MezonToast.show(
                            this@ChannelChangeCategoryFragment,
                            ToastOverlay.ToastType.SUCCESS,
                            getString(R.string.channel_settings_updated),
                        )
                        finishFragment()
                    } else {
                        MezonToast.show(
                            this@ChannelChangeCategoryFragment,
                            ToastOverlay.ToastType.ERROR,
                            getString(R.string.channel_change_category_error),
                        )
                    }
                }
            } finally {
                moving = false
                loadingBar?.visibility = View.GONE
            }
        }
    }

    private fun currentChannel(): ClanChannelEntity? =
        channelController.findChannelById(channelId, clanId)
}
