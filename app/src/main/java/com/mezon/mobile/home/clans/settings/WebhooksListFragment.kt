package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.widget.FrameLayout
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.UserClanController
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.home.clans.ClanChannelEntity
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.network.CHANNEL_TYPE_CHANNEL
import com.mezon.mobile.ui.MezonToast
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.util.DateTimeUtil
import com.mezon.mobile.util.Webhook as WebhookConfig
import com.mezon.mezon.api.ClanWebhook
import com.mezon.mezon.api.Webhook
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.Locale

class WebhooksListFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"
        private const val ARG_CLAN_SCOPE = "isClanIntegration"
        private const val ARG_CHANNEL_ID = "channelId"

        fun newInstance(clanId: Long, isClanScope: Boolean): WebhooksListFragment =
            WebhooksListFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putBoolean(ARG_CLAN_SCOPE, isClanScope)
                    putLong(ARG_CHANNEL_ID, 0L)
                }
            }

        fun newInstanceForChannel(clanId: Long, channelId: Long): WebhooksListFragment =
            WebhooksListFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CLAN_ID, clanId)
                    putBoolean(ARG_CLAN_SCOPE, false)
                    putLong(ARG_CHANNEL_ID, channelId)
                }
            }
    }

    private var clanId = 0L
    private var channelId = 0L
    private var isClanIntegration = false

    private lateinit var clansController: ClansController
    private lateinit var mainDispatcher: CoroutineDispatcher
    private lateinit var channelController: ChannelController
    private lateinit var userClanController: UserClanController

    private lateinit var listWrap: LinearLayout
    private lateinit var emptyWrap: LinearLayout
    private lateinit var listFrame: FrameLayout
    private var loadingBar: ProgressBar? = null

    private val channelWebhookItems = ArrayList<Webhook>()
    private val clanWebhookItems = ArrayList<ClanWebhook>()

    override fun onInject(entryPoint: FragmentEntryPoint) {
        clansController = entryPoint.clansController()
        mainDispatcher = entryPoint.mainDispatcher()
        channelController = entryPoint.channelController()
        userClanController = entryPoint.userClanController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        isClanIntegration = arguments?.getBoolean(ARG_CLAN_SCOPE) ?: false
        if (clanId != 0L) {
            channelController.loadChannelsForClan(clanId, force = true)
            userClanController.loadClanMembers(clanId)
        }
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (clanId != 0L) {
            channelController.loadChannelsForClan(clanId, force = false)
            userClanController.loadClanMembers(clanId)
        }
        reloadWebhooks()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        val titleRes = if (isClanIntegration) {
            R.string.menu_clan_integration_clan_webhooks
        } else {
            R.string.menu_clan_integration_channel_webhooks
        }

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(titleRes))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.common_close))
            setCenterTitle(true)
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                }
            })
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
        root.setPadding(pad, LayoutHelper.dp(6f), pad, 0)

        root.addView(
            TextView(context).apply {
                text = webhookListDescriptionSpans(context)
                textSize = 12f
                setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
                movementMethod = LinkMovementMethod.getInstance()
                setPadding(0, 0, 0, LayoutHelper.dp(12f))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )

        listFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT, 0, 1f,
            )
        }

        val scroll = ClanSettingsUiHelpers.newMezonScrollRoot(context)
        listWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        scroll.addView(
            listWrap,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        listFrame.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        emptyWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(
                TextView(context).apply {
                    text = context.getString(R.string.webhooks_empty_title)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(CreateClanRnUiTokens.textStrong(themeColors))
                    gravity = Gravity.CENTER
                },
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT),
            )
        }
        listFrame.addView(
            emptyWrap,
            FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )

        root.addView(listFrame)

        val overlay = ProgressBar(context).apply { visibility = View.GONE }
        loadingBar = overlay
        root.addView(
            overlay,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_HORIZONTAL),
        )

        val fabOuter = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
            )
        }
        val fab = TextView(context).apply {
            text = "+"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10f).toFloat()
                setColor(themeColors.primary)
            }
            elevation = LayoutHelper.dp(6f).toFloat()
            setPadding(LayoutHelper.dp(18f), LayoutHelper.dp(10f), LayoutHelper.dp(18f), LayoutHelper.dp(10f))
            setOnClickListener { onAddWebhookPressed() }
        }
        fabOuter.addView(
            fab,
            FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END)
                .apply {
                    bottomMargin = LayoutHelper.dp(20f)
                    topMargin = LayoutHelper.dp(8f)
                    marginEnd = LayoutHelper.dp(20f)
                },
        )
        root.addView(fabOuter)

        fragmentView = root
        return root
    }

    private fun webhookListDescriptionSpans(context: Context): CharSequence {
        if (isClanIntegration) {
            val tip = context.getString(R.string.webhooks_clan_tip_link_label)
            val full = "${context.getString(R.string.webhooks_clan_description)} $tip"
            val ss = SpannableStringBuilder(full)
            val start = full.lastIndexOf(tip).coerceAtLeast(0)
            val end = start + tip.length
            val lc = themeColors.primary
            ss.setSpan(ForegroundColorSpan(lc), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    safeOpenUrl(context, WebhookConfig.CLAN_WEBHOOK_DOCS_URL)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = true
                    ds.typeface = Typeface.DEFAULT_BOLD
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return ss
        }

        val p1 = context.getString(R.string.webhooks_channel_description_part1)
        val lm = context.getString(R.string.webhooks_channel_learn_more)
        val suf = context.getString(R.string.webhooks_channel_try_suffix)
        val bo = context.getString(R.string.webhooks_channel_build_one)
        val full = "$p1 $lm $suf $bo"
        val ss = SpannableStringBuilder(full)
        fun paint(rangeStart: Int, rangeEndExclusive: Int) {
            val lc = themeColors.primary
            ss.setSpan(ForegroundColorSpan(lc), rangeStart, rangeEndExclusive, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    safeOpenUrl(context, WebhookConfig.CHANNEL_WEBHOOK_DOCS_URL)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = true
                    ds.typeface = Typeface.DEFAULT_BOLD
                }
            }, rangeStart, rangeEndExclusive, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val iLm = full.indexOf(lm)
        if (iLm >= 0) paint(iLm, iLm + lm.length)
        val iBo = full.indexOf(bo)
        if (iBo >= 0) paint(iBo, iBo + bo.length)
        return ss
    }

    private fun safeOpenUrl(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun parentTextChannelsOrdered(): List<ClanChannelEntity> {
        return channelController.getChannels(clanId)
            .filter { !it.isThread && it.parentId == 0L && it.type == CHANNEL_TYPE_CHANNEL }
            .sortedBy { it.channelLabel.lowercase(Locale.getDefault()) }
    }

    private fun reloadWebhooks() {
        if (clanId == 0L || !::listWrap.isInitialized) return
        loadingBar?.visibility = View.VISIBLE
        fragmentScope.launch(mainDispatcher) {
            try {
                runCatching {
                    if (isClanIntegration) {
                        clanWebhookItems.clear()
                        clanWebhookItems.addAll(clansController.fetchClanWebhooks(clanId).listClanWebhooksList)
                    } else {
                        channelWebhookItems.clear()
                        val response = if (channelId != 0L) {
                            clansController.fetchChannelWebhooks(channelId, clanId)
                        } else {
                            clansController.fetchChannelWebhooksForClan(clanId)
                        }
                        val filtered = if (channelId != 0L) {
                            response.webhooksList.filter { it.channelId == channelId }
                        } else {
                            response.webhooksList
                        }
                        channelWebhookItems.addAll(filtered)
                    }
                }.onSuccess {
                    listWrap.removeAllViews()
                    if (isClanIntegration) {
                        clanWebhookItems.forEach { wh ->
                            listWrap.addView(
                                webhookRowChannelOrClan(clanWebhook = wh, channelWebhook = null),
                                LayoutHelper.createLinear(
                                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                                    0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 10f,
                                ),
                            )
                        }
                    } else {
                        channelWebhookItems.forEach { wh ->
                            listWrap.addView(
                                webhookRowChannelOrClan(clanWebhook = null, channelWebhook = wh),
                                LayoutHelper.createLinear(
                                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                                    0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 10f,
                                ),
                            )
                        }
                    }
                    val empty = listWrap.childCount == 0
                    emptyWrap.visibility = if (empty) View.VISIBLE else View.GONE
                }.onFailure {
                    MezonToast.show(this@WebhooksListFragment, ToastOverlay.ToastType.ERROR, getString(R.string.integration_webhooks_load_failed))
                    emptyWrap.visibility = View.VISIBLE
                }
            } finally {
                loadingBar?.visibility = View.GONE
            }
        }
    }

    private fun webhookRowChannelOrClan(
        clanWebhook: ClanWebhook?,
        channelWebhook: Webhook?,
    ): LinearLayout {
        val ctx = listWrap.context
        if (clanWebhook == null && channelWebhook == null) return LinearLayout(ctx)

        val name = clanWebhook?.webhookName ?: channelWebhook?.webhookName ?: ""
        val avatarUrl = clanWebhook?.avatar ?: channelWebhook?.avatar ?: ""
        val creatorId = clanWebhook?.creatorId ?: channelWebhook?.creatorId ?: 0L
        val created = clanWebhook?.createTime ?: channelWebhook?.createTime ?: ""

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = CreateClanRnUiTokens.clanSettingsMenuCornerPx()
                setColor(themeColors.surfaceVariant)
            }
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(10f), LayoutHelper.dp(14f), LayoutHelper.dp(10f))
        }
        val img = ImageView(ctx)
        val w50 = LayoutHelper.dp(50f)
        img.layoutParams = LinearLayout.LayoutParams(w50, w50)
        val corner = LayoutHelper.dpf(25f)
        img.clipToOutline = true
        img.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        if (avatarUrl.isNotBlank()) {
            MezonImageLoader.getInstance(ctx).load(
                avatarUrl,
                w50,
                w50,
                onSuccess = { bmp -> img.setImageBitmap(bmp) },
                onError = {
                    img.setImageDrawable(null)
                    img.background = android.graphics.drawable.GradientDrawable().apply {
                        setCornerRadius(corner)
                        setColor(themeColors.tertiary)
                    }
                },
            )
        } else {
            img.background = android.graphics.drawable.GradientDrawable().apply {
                setCornerRadius(corner)
                setColor(themeColors.tertiary)
            }
        }
        row.addView(img)

        val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(
            TextView(ctx).apply {
                text = name
                textSize = 15f
                setTextColor(CreateClanRnUiTokens.menuText(themeColors))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )
        val owner = userClanController.getClanMembers(clanId).firstOrNull { it.userId == creatorId }?.username ?: ""
        val dateStr = when {
            created.isBlank() -> ""
            else -> DateTimeUtil.parseFlexibleToMillis(created)?.let { ms ->
                DateTimeUtil.format(ms, "dd/MM/yyyy")
            }.orEmpty()
        }
        textCol.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.webhooks_item_created_line, dateStr, owner)
                textSize = 12f
                setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT),
        )

        row.addView(
            textCol,
            LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply {
                marginStart = LayoutHelper.dp(10f)
            },
        )
        row.addView(
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val d = MezonIcon.chevronSmallRightIcon.getDrawable(ctx)
                d.mutate()
                d.colorFilter = PorterDuffColorFilter(CreateClanRnUiTokens.menuText(themeColors), PorterDuff.Mode.SRC_IN)
                setImageDrawable(d)
            },
            LayoutHelper.createLinear(18, 18, 0f, Gravity.CENTER_VERTICAL),
        )

        row.setOnClickListener {
            if (clanWebhook != null && isClanIntegration) {
                presentFragment(WebhookEditFragment.newInstanceClan(clanWebhook, clanId))
            }
            if (channelWebhook != null && !isClanIntegration) {
                presentFragment(WebhookEditFragment.newInstanceChannel(channelWebhook, clanId))
            }
        }

        row.isClickable = true
        row.contentDescription = name
        return row
    }

    private fun onAddWebhookPressed() {
        val ctx = getContext() ?: return
        if (!isClanIntegration) {
            if (channelId != 0L) {
                createChannelWebhook(channelId = channelId)
                return
            }
            val channels = parentTextChannelsOrdered()
            if (channels.isEmpty()) {
                MezonToast.show(this, ToastOverlay.ToastType.INFO, getString(R.string.clan_invite_need_channel))
                return
            }
            ChannelPickerSheet(
                ctx,
                themeColors,
                channels,
                getString(R.string.webhook_pick_channel_title),
            ) { ch ->
                createChannelWebhook(channelId = ch.channelId)
            }.show()
        } else {
            createClanScopedWebhook()
        }
    }

    private fun randomPresetName(): String = WebhookConfig.PRESET_NAMES.random()

    private fun randomPresetAvatar(): String =
        WebhookConfig.presetAvatarUrls(BuildConfig.MEZON_BASE_IMG_URL).random()

    private fun createChannelWebhook(channelId: Long) {
        fragmentScope.launch(mainDispatcher) {
            loadingBar?.visibility = View.VISIBLE
            try {
                runCatching {
                    clansController.generateChannelWebhook(
                        randomPresetName(),
                        channelId,
                        clanId,
                        randomPresetAvatar(),
                    )
                }.onSuccess {
                    reloadWebhooks()
                }.onFailure {
                    MezonToast.show(this@WebhooksListFragment, ToastOverlay.ToastType.ERROR, getString(R.string.webhooks_toast_add_error))
                }
            } finally {
                loadingBar?.visibility = View.GONE
            }
        }
    }

    private fun createClanScopedWebhook() {
        fragmentScope.launch(mainDispatcher) {
            loadingBar?.visibility = View.VISIBLE
            try {
                runCatching {
                    clansController.generateClanWebhook(
                        clanId,
                        randomPresetName(),
                        randomPresetAvatar(),
                    )
                }.onSuccess {
                    reloadWebhooks()
                }.onFailure {
                    MezonToast.show(this@WebhooksListFragment, ToastOverlay.ToastType.ERROR, getString(R.string.webhooks_toast_add_error))
                }
            } finally {
                loadingBar?.visibility = View.GONE
            }
        }
    }
}
