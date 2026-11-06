package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.core.theme.MobileThemeColors
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.ChannelController
import com.mezon.mobile.home.clans.CreateClanRnUiTokens
import com.mezon.mobile.util.Webhook as WebhookConfig
import com.mezon.mobile.ui.cells.ActionBarView
import com.mezon.mobile.ui.cells.MezonIcon

class IntegrationSettingFragment : BaseFragment() {

    companion object {
        private const val ARG_CLAN_ID = "clanId"

        fun newInstance(clanId: Long): IntegrationSettingFragment =
            IntegrationSettingFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
    }

    private var clanId = 0L

    private lateinit var channelController: ChannelController

    override fun onInject(entryPoint: FragmentEntryPoint) {
        channelController = entryPoint.channelController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        if (clanId != 0L) {
            channelController.loadChannelsForClan(clanId, force = false)
        }
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (clanId != 0L) channelController.loadChannelsForClan(clanId, force = false)
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.surface)
        }

        actionBar = ActionBarView(context, themeColors).apply {
            setTitle(getString(R.string.menu_clan_integrations))
            setBackButtonImage(R.drawable.ic_close_24)
            setBackButtonContentDescription(getString(R.string.common_close))
            setCenterTitle(true)
            setMenuOnItemClick(object : ActionBarView.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) finishFragment()
                }
            })
        }
        actionBar!!.backButton.apply {
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = (layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                width = LayoutHelper.dp(48f)
                height = LayoutHelper.dp(48f)
            }
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(16f), LayoutHelper.dp(24f))
            adapter = IntegrationSettingAdapter(
                themeColors,
                buildIntegrationDescriptionSpans(context),
                getString(R.string.integration_automated_message),
                getString(R.string.integration_channel_webhooks),
                getString(R.string.integration_clan_webhooks),
                Runnable { presentFragment(WebhooksListFragment.newInstance(clanId, isClanScope = false)) },
                Runnable { presentFragment(WebhooksListFragment.newInstance(clanId, isClanScope = true)) },
            )
        }

        root.addView(recyclerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        fragmentView = root
        return root
    }

    private fun buildIntegrationDescriptionSpans(context: Context): CharSequence {
        val base = getString(R.string.integration_description_prefix)
        val linkChannel = getString(R.string.integration_learn_more_channel)
        val fullText = "$base $linkChannel"
        val ss = SpannableString(fullText)

        val start = fullText.indexOf(linkChannel)
        if (start >= 0) {
            val end = start + linkChannel.length
            ss.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    safeOpenDocs(context, WebhookConfig.CHANNEL_WEBHOOK_DOCS_URL)
                }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = true
                    ds.typeface = Typeface.DEFAULT_BOLD
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return ss
    }

    private fun safeOpenDocs(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private class IntegrationSettingAdapter(
        private val themeColors: ThemeColors,
        private val introText: CharSequence,
        private val webhookSubtitle: String,
        private val channelWebhooksTitle: String,
        private val clanWebhooksTitle: String,
        private val onChannelWebhooks: Runnable,
        private val onClanWebhooks: Runnable,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getItemCount(): Int = 2

        override fun getItemViewType(position: Int): Int =
            if (position == 0) VIEW_TYPE_INTRO else VIEW_TYPE_SECTION

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            return when (viewType) {
                VIEW_TYPE_INTRO -> IntroViewHolder(createIntroTextView(context))
                else -> SectionViewHolder(
                    ClanSettingsUiHelpers.buildMezonSection(
                        context,
                        themeColors,
                        null,
                        listOf(
                            ClanSettingsUiHelpers.buildMezonChevronSubtitleRow(
                                context,
                                themeColors,
                                MezonIcon.webhookIcon,
                                channelWebhooksTitle,
                                webhookSubtitle,
                                onChannelWebhooks,
                            ),
                            ClanSettingsUiHelpers.buildMezonChevronSubtitleRow(
                                context,
                                themeColors,
                                MezonIcon.webhookIcon,
                                clanWebhooksTitle,
                                webhookSubtitle,
                                onClanWebhooks,
                            ),
                        ),
                        cardColor = themeColors.background,
                        separatorColor = themeColors.border,
                    ),
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is IntroViewHolder) holder.bind(introText)
        }

        private fun createIntroTextView(context: Context): TextView {
            return TextView(context).apply {
                textSize = 12f
                setTextColor(CreateClanRnUiTokens.textDisabled(themeColors))
                setLinkTextColor(MobileThemeColors.accent)
                movementMethod = LinkMovementMethod.getInstance()
                setPadding(0, 0, 0, LayoutHelper.dp(18f))
            }
        }

        private class IntroViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
            fun bind(text: CharSequence) {
                textView.text = text
            }
        }

        private class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

        companion object {
            private const val VIEW_TYPE_INTRO = 0
            private const val VIEW_TYPE_SECTION = 1
        }
    }
}
