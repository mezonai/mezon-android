package com.mezon.mobile.home.clans.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ChannelPickerSheet
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.FileUtils
import com.mezon.mobile.util.Webhook as WebhookUtil
import com.mezon.mezon.api.ClanWebhook
import com.mezon.mezon.api.Webhook
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class WebhookEditFragment : BaseFragment() {

    companion object {
        private const val MODE_CHANNEL = 0
        private const val MODE_CLAN = 1

        private const val ARG_MODE = "mode"
        private const val ARG_CLAN_ID = "clanId"

        private const val ARG_WH_ID = "wh_id"
        private const val ARG_CH_ID = "ch_id"
        private const val ARG_NAME = "name"
        private const val ARG_AVATAR = "avatar"
        private const val ARG_URL = "url"

        fun newInstanceChannel(webhook: Webhook, clanId: Long): WebhookEditFragment =
            WebhookEditFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_MODE, MODE_CHANNEL)
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_WH_ID, webhook.id)
                    putLong(ARG_CH_ID, webhook.channelId)
                    putString(ARG_NAME, webhook.webhookName)
                    putString(ARG_AVATAR, webhook.avatar)
                    putString(ARG_URL, webhook.url)
                }
            }

        fun newInstanceClan(webhook: ClanWebhook, clanId: Long): WebhookEditFragment =
            WebhookEditFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_MODE, MODE_CLAN)
                    putLong(ARG_CLAN_ID, clanId)
                    putLong(ARG_WH_ID, webhook.id)
                    putString(ARG_NAME, webhook.webhookName)
                    putString(ARG_AVATAR, webhook.avatar)
                    putString(ARG_URL, webhook.url)
                }
            }
    }

    private var mode = MODE_CHANNEL
    private var clanId = 0L
    private var webhookId = 0L

    private var apiAnchorChannelId = 0L

    private lateinit var clansController: ClansController
    private lateinit var ioDispatcher: CoroutineDispatcher
    private lateinit var mainDispatcher: CoroutineDispatcher
    private lateinit var channelController: ChannelController

    private lateinit var avatarView: ImageView
    private lateinit var nameField: EditText
    private lateinit var urlView: TextView
    private lateinit var copyLabel: TextView
    private lateinit var channelLabel: TextView
    private var channelRow: LinearLayout? = null
    private var saveTv: TextView? = null
    private var busy: ProgressBar? = null

    private var sourceName = ""
    private var draftName = ""
    private var sourceAvatar = ""
    private var draftAvatar = ""
    private var sourceChannelId = 0L
    private var draftChannelId = 0L
    private var displayUrl = ""

    private var avatarLoadDisposable: MezonImageLoader.Cancellable? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        clansController = entryPoint.clansController()
        ioDispatcher = entryPoint.ioDispatcher()
        mainDispatcher = entryPoint.mainDispatcher()
        channelController = entryPoint.channelController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        val args = arguments ?: return false
        mode = args.getInt(ARG_MODE, MODE_CHANNEL)
        clanId = args.getLong(ARG_CLAN_ID, 0L)
        webhookId = args.getLong(ARG_WH_ID, 0L)
        sourceName = args.getString(ARG_NAME) ?: ""
        draftName = sourceName
        sourceAvatar = args.getString(ARG_AVATAR) ?: ""
        draftAvatar = sourceAvatar
        displayUrl = args.getString(ARG_URL) ?: ""

        if (mode == MODE_CHANNEL) {
            apiAnchorChannelId = args.getLong(ARG_CH_ID, 0L)
            sourceChannelId = apiAnchorChannelId
            draftChannelId = apiAnchorChannelId
        }

        if (clanId != 0L) {
            channelController.loadChannelsForClan(clanId, force = true)
        }

        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.webhooks_edit_title))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.common_close))
            setCenterTitle(true)

            val saveItem = createMenu().addItem(1, context.getString(R.string.webhooks_edit_save))
            val st = TextView(context).apply {
                text = context.getString(R.string.webhooks_edit_save)
                setTextColor(themeColors.primary)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
                alpha = 0.45f
            }
            saveTv = st
            saveItem.addView(
                st,
                LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT,
                    Gravity.CENTER_VERTICAL, 0f, 3f, 0f, 0f,
                ),
            )
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        1 -> onSaveClicked()
                    }
                }
            })
            st.setOnClickListener { onSaveClicked() }
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

        val scrollContent = ClanSettingsUiHelpers.newMezonScrollRoot(context)
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val avatarOuter = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = LayoutHelper.dp(12f)
            }
        }

        avatarView = ImageView(context).apply {
            val s = LayoutHelper.dp(100f)
            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                gravity = Gravity.CENTER
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            val rPx = LayoutHelper.dpf(12f)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, rPx)
                }
            }
            isClickable = true
            setOnClickListener { pickAvatarImage() }
        }
        avatarOuter.addView(avatarView)

        avatarOuter.addView(
            ImageView(context).apply {
                val d = MezonIcon.uploadPlusIcon.getDrawable(context).mutate()
                d.colorFilter = PorterDuffColorFilter(themeColors.onPrimary, PorterDuff.Mode.SRC_IN)
                setImageDrawable(d)
                elevation = LayoutHelper.dp(4f).toFloat()
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            },
            FrameLayout.LayoutParams(LayoutHelper.dp(28f), LayoutHelper.dp(28f)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            },
        )

        avatarOuter.contentDescription = getString(R.string.webhooks_edit_avatar_cd)
        inner.addView(avatarOuter)

        inner.addView(
            TextView(context).apply {
                text = context.getString(R.string.webhooks_edit_recommend_image)
                textSize = 11f
                setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, LayoutHelper.dp(12f))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )

        inner.addView(
            TextView(context).apply {
                text = context.getString(R.string.webhooks_edit_name_label)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(CreateClanRnUiTokens.menuText(themeColors))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )

        nameField = EditText(context).apply {
            setText(draftName)
            textSize = 16f
            setTextColor(CreateClanRnUiTokens.textStrong(themeColors))
            filters = arrayOf(InputFilter.LengthFilter(WebhookUtil.WEBHOOK_NAME_MAX))
            background = GradientDrawable().apply {
                cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
                setColor(themeColors.surfaceVariant)
            }
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    refreshSaveUi()
                }
            })
        }
        inner.addView(
            nameField,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 8f, 0f, 12f),
        )

        if (mode == MODE_CHANNEL) {
            channelLabel = TextView(context).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(CreateClanRnUiTokens.menuText(themeColors))
            }
            val channelRowInner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
                    setColor(themeColors.channelPanelBg)
                }
                setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(12f), LayoutHelper.dp(14f), LayoutHelper.dp(12f))
                isClickable = true
                setOnClickListener { pickChannelDialog() }
                addView(
                    TextView(context).apply {
                        text = context.getString(R.string.webhooks_edit_channel_heading)
                        textSize = 12f
                        setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
                    },
                )
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, LayoutHelper.dp(6f), 0, 0)
                        addView(
                            ImageView(context).apply {
                                val d = MezonIcon.channelText.getDrawable(context)
                                d.mutate()
                                d.colorFilter = PorterDuffColorFilter(CreateClanRnUiTokens.menuText(themeColors), PorterDuff.Mode.SRC_IN)
                                setImageDrawable(d)
                                scaleType = ImageView.ScaleType.CENTER_INSIDE
                            },
                            LayoutHelper.createLinear(14, 14, 0f, Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f),
                        )
                        addView(channelLabel, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
                        addView(
                            ImageView(context).apply {
                                val d = MezonIcon.chevronSmallRightIcon.getDrawable(context)
                                d.mutate()
                                d.colorFilter = PorterDuffColorFilter(CreateClanRnUiTokens.textDisabled(themeColors), PorterDuff.Mode.SRC_IN)
                                setImageDrawable(d)
                            },
                            LayoutHelper.createLinear(16, 16, 0f, Gravity.CENTER_VERTICAL),
                        )
                    },
                )
            }
            channelRow = channelRowInner
            inner.addView(channelRowInner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 14f))
        }

        inner.addView(
            TextView(context).apply {
                text = context.getString(R.string.webhooks_edit_webhook_url)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(CreateClanRnUiTokens.menuText(themeColors))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )

        urlView = TextView(context).apply {
            textSize = 12f
            setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
            maxLines = 4
            setPadding(LayoutHelper.dp(12f), LayoutHelper.dp(10f), LayoutHelper.dp(12f), LayoutHelper.dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
                setColor(themeColors.surfaceVariant)
            }
        }

        inner.addView(
            urlView,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 4f, 0f, 0f),
        )

        copyLabel = TextView(context).apply {
            text = context.getString(R.string.webhooks_edit_copy)
            textSize = 15f
            setTextColor(themeColors.onPrimary)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumHeight = LayoutHelper.dp(48f)
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
                setColor(themeColors.primary)
            }
            isClickable = true
            setOnClickListener { copyWebhookUrl() }
        }
        inner.addView(
            copyLabel,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 10f, 0f, 16f),
        )

        if (mode == MODE_CLAN) {
            inner.addView(
                TextView(context).apply {
                    text = context.getString(R.string.webhooks_edit_reset_token)
                    textSize = 15f
                    setTextColor(themeColors.primary)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, LayoutHelper.dp(8f), 0, LayoutHelper.dp(16f))
                    setOnClickListener { confirmResetToken() }
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
            )
        }

        inner.addView(
            TextView(context).apply {
                text = context.getString(R.string.webhooks_edit_delete)
                textSize = 15f
                setTextColor(themeColors.onPrimary)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minimumHeight = LayoutHelper.dp(48f)
                setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(12f), LayoutHelper.dp(16f), LayoutHelper.dp(12f))
                background = GradientDrawable().apply {
                    cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
                    setColor(themeColors.error)
                }
                isClickable = true
                setOnClickListener { confirmDelete() }
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 20f, 0f, 28f),
        )

        busy = ProgressBar(context).apply { visibility = View.GONE }
        inner.addView(busy, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL))

        scrollContent.addView(inner)
        root.addView(scrollContent, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        fragmentView = root

        bindAvatarImage()
        urlView.text = displayUrl.ifBlank { "—" }
        refreshChannelUi()
        refreshSaveUi()
        return root
    }

    private fun refreshSaveUi() {
        draftName = nameField.text?.toString()?.trim() ?: ""
        val dirtyNames = draftName != sourceName.trim() || draftAvatar != sourceAvatar.trim()
        val dirtyChan = mode == MODE_CHANNEL && draftChannelId != sourceChannelId
        val dirty = (dirtyNames || dirtyChan) && draftName.isNotEmpty()
        saveTv?.alpha = if (dirty) 1f else 0.45f
        saveTv?.isClickable = dirty
    }

    private fun pickAvatarImage() {
        val pick = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val chooser = Intent.createChooser(getContent, getString(R.string.webhooks_edit_pick_avatar)).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(pick))
        }
        startActivityForResult(chooser, WebhookUtil.REQ_PICK_WEBHOOK_AVATAR)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != WebhookUtil.REQ_PICK_WEBHOOK_AVATAR || resultCode != Activity.RESULT_OK) return
        val uri = data?.clipData?.getItemAt(0)?.uri ?: data?.data ?: return
        uploadAvatar(uri)
    }

    private fun uploadAvatar(uri: Uri) {
        val cr = getContext()?.contentResolver ?: return
        busy?.visibility = View.VISIBLE
        fragmentScope.launch(mainDispatcher) {
            runCatching {
                val fileSize = withContext(ioDispatcher) { FileUtils.getPickedFileSize(cr, uri) }
                if (fileSize > WebhookUtil.MAX_WEBHOOK_AVATAR_BYTES) {
                    MezonToast.show(this@WebhookEditFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_image_too_large, WebhookUtil.MAX_WEBHOOK_AVATAR_BYTES / (1024 * 1024)))
                    return@launch
                }
                val bytes = withContext(ioDispatcher) {
                    cr.openInputStream(uri)?.use { it.readBytes() } ?: throw RuntimeException("read")
                }
                if (bytes.size > WebhookUtil.MAX_WEBHOOK_AVATAR_BYTES) {
                    MezonToast.show(this@WebhookEditFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_image_too_large, WebhookUtil.MAX_WEBHOOK_AVATAR_BYTES / (1024 * 1024)))
                    return@launch
                }
                val mime = cr.getType(uri) ?: "image/jpeg"
                val url = clansController.uploadWebhookAvatar(bytes, mime)
                draftAvatar = url
                bindAvatarImage()
                refreshSaveUi()
            }.onFailure {
                MezonToast.show(this@WebhookEditFragment, ToastOverlay.ToastType.ERROR, getString(R.string.clan_overview_save_error))
            }
            busy?.visibility = View.GONE
        }
    }

    private fun bindAvatarImage() {
        avatarLoadDisposable?.cancel()
        val ctx = avatarView.context
        val s = LayoutHelper.dp(100f)
        if (draftAvatar.isNotBlank()) {
            avatarLoadDisposable = MezonImageLoader.getInstance(ctx).loadDrawable(
                draftAvatar,
                s,
                s,
                cacheAnimated = true,
                onSuccess = { drawable ->
                    avatarView.setImageDrawable(drawable)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && drawable is android.graphics.drawable.AnimatedImageDrawable) {
                        drawable.start()
                    }
                },
                onError = { avatarView.setImageDrawable(null) },
            )
        } else {
            avatarView.setImageDrawable(null)
        }
    }

    private fun parentTextChannelsOrdered(): List<ClanChannelEntity> {
        return channelController.getChannels(clanId)
            .filter { !it.isThread && it.parentId == 0L && it.type == CHANNEL_TYPE_CHANNEL }
            .sortedBy { it.channelLabel.lowercase(Locale.getDefault()) }
    }

    private fun refreshChannelUi() {
        if (mode != MODE_CHANNEL || !::channelLabel.isInitialized) return
        val ch = channelController.getChannels(clanId).firstOrNull { it.channelId == draftChannelId }
        channelLabel.text = ch?.channelLabel ?: getString(R.string.webhooks_edit_select_channel_placeholder)
    }

    private fun pickChannelDialog() {
        val ctx = getContext() ?: return
        val list = parentTextChannelsOrdered()
        if (list.isEmpty()) {
            MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.clan_invite_need_channel))
            return
        }
        ChannelPickerSheet(
            ctx,
            themeColors,
            list,
            getString(R.string.webhook_pick_channel_title),
        ) { ch ->
            draftChannelId = ch.channelId
            refreshChannelUi()
            refreshSaveUi()
        }.show()
    }

    private fun copyWebhookUrl() {
        val u = displayUrl.trim()
        if (u.isBlank()) {
            MezonToast.show(this, ToastOverlay.ToastType.ERROR, getString(R.string.webhooks_toast_copy_error))
            return
        }
        val cm = getContext()?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("webhook", u))
        copyLabel.text = getString(R.string.webhooks_edit_copied)
        fragmentScope.launch(mainDispatcher) {
            delay(2200)
            copyLabel.text = getString(R.string.webhooks_edit_copy)
        }
    }

    private fun onSaveClicked() {
        draftName = nameField.text?.toString()?.trim() ?: ""
        if (draftName.isBlank()) return
        val dirtyNames = draftName != sourceName.trim() || draftAvatar != sourceAvatar.trim()
        val dirtyChan = mode == MODE_CHANNEL && draftChannelId != sourceChannelId
        if (!(dirtyNames || dirtyChan)) return

        busy?.visibility = View.VISIBLE
        fragmentScope.launch(mainDispatcher) {
            try {
                runCatching {
                    when (mode) {
                        MODE_CLAN ->
                            clansController.updateClanWebhookById(
                                webhookId,
                                clanId,
                                draftName,
                                draftAvatar,
                                resetToken = false,
                            )
                        MODE_CHANNEL ->
                            clansController.updateChannelWebhookById(
                                webhookId,
                                draftName,
                                draftAvatar,
                                channelIdExisting = apiAnchorChannelId,
                                newChannelId = draftChannelId,
                                clanId = clanId,
                            )
                    }
                }.onSuccess {
                    MezonToast.show(this@WebhookEditFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.webhooks_toast_save_success))
                    sourceName = draftName
                    sourceAvatar = draftAvatar
                    sourceChannelId = draftChannelId
                    refreshSaveUi()
                    finishFragment()
                }.onFailure {
                    MezonToast.show(this@WebhookEditFragment, ToastOverlay.ToastType.ERROR, getString(R.string.webhooks_toast_save_error))
                }
            } finally {
                busy?.visibility = View.GONE
            }
        }
    }

    private fun confirmResetToken() {
        val ctx = getContext() ?: return
        AlertsCreator.createConfirmDialog(
            context = ctx,
            title = getString(R.string.webhooks_edit_reset_token),
            message = getString(R.string.webhooks_edit_reset_token_confirm),
            confirmText = getString(R.string.common_ok),
            cancelText = getString(R.string.webhooks_edit_cancel_action),
            onConfirm = { runResetToken() },
        ).show()
    }

    private fun runResetToken() {
        busy?.visibility = View.VISIBLE
        fragmentScope.launch(mainDispatcher) {
            try {
                runCatching {
                    clansController.updateClanWebhookById(
                        webhookId,
                        clanId,
                        draftName.ifBlank { sourceName }.trim(),
                        draftAvatar.ifBlank { sourceAvatar },
                        resetToken = true,
                    )
                }.onSuccess {
                    MezonToast.show(this@WebhookEditFragment, ToastOverlay.ToastType.SUCCESS, getString(R.string.webhooks_toast_reset_token_success))
                    fragmentScope.launch(mainDispatcher) {
                        runCatching {
                            val urlFresh = clansController.clanWebhookPublicUrl(webhookId, clanId)
                            urlFresh?.let {
                                displayUrl = it
                                urlView.text = it
                            }
                        }
                    }
                }.onFailure {
                    MezonToast.show(this@WebhookEditFragment, ToastOverlay.ToastType.ERROR, getString(R.string.webhooks_toast_reset_token_error))
                }
            } finally {
                busy?.visibility = View.GONE
            }
        }
    }

    private fun confirmDelete() {
        val ctx = getContext() ?: return
        val nm = draftName.ifBlank { sourceName }
        AlertsCreator.createConfirmDialog(
            context = ctx,
            title = getString(R.string.webhooks_edit_delete_confirm_title_format, nm),
            message = getString(R.string.webhooks_edit_delete_confirm_message_format, nm),
            confirmText = getString(R.string.webhooks_edit_yes),
            cancelText = getString(R.string.webhooks_edit_cancel_action),
            destructive = true,
            onConfirm = { runDelete() },
        ).show()
    }

    private fun runDelete() {
        busy?.visibility = View.VISIBLE
        fragmentScope.launch(mainDispatcher) {
            try {
                runCatching {
                    when (mode) {
                        MODE_CHANNEL ->
                            clansController.deleteChannelWebhook(
                                webhookId,
                                clanId,
                                hookChannelId = apiAnchorChannelId,
                            )
                        MODE_CLAN ->
                            clansController.deleteClanWebhook(webhookId, clanId)
                    }
                }.onSuccess {
                    finishFragment()
                }.onFailure {
                    MezonToast.show(this@WebhookEditFragment, ToastOverlay.ToastType.ERROR, getString(R.string.webhooks_toast_delete_error))
                }
            } finally {
                busy?.visibility = View.GONE
            }
        }
    }
}
