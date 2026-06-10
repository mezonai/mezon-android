package com.mezon.mobile.deeplink

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.MainActivity
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InviteClanFragment : BaseFragment() {

    companion object {
        private const val ARG_INVITE_ID = "inviteId"

        fun newInstance(inviteId: Long): InviteClanFragment {
            return InviteClanFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_INVITE_ID, inviteId)
                }
            }
        }
    }

    private lateinit var api: MezonApi
    private lateinit var sessionManager: SessionManager
    private lateinit var clansController: ClansController

    private var inviteId = 0L
    private var joinButton: TextView? = null
    private var joinProgress: ProgressBar? = null
    private var joinOriginalText: CharSequence = ""
    private var clanNameView: TextView? = null
    private var channelLabelView: TextView? = null
    private var logoView: DeeplinkLogoView? = null
    private var pendingJoinClanId = 0L
    private var pendingJoinTimeout: Runnable? = null
    private var clansLoadedObserver: NotificationCenter.NotificationCenterDelegate? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        clansController = entryPoint.clansController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        inviteId = arguments?.getLong(ARG_INVITE_ID) ?: 0L
        return true
    }

    override fun createView(context: Context): View {
        val scroll = ScrollView(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(LayoutHelper.dp(24), LayoutHelper.dp(32), LayoutHelper.dp(24), LayoutHelper.dp(32))
        }

        val logo = DeeplinkLogoView(context, themeColors, sizeDp = 96, cornerRadiusDp = 14f)
        logoView = logo
        card.addView(
            logo,
            LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(24)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )

        val clanName = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        clanNameView = clanName
        card.addView(
            clanName,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(16)
            }
        )

        val channelLabel = TextView(context).apply {
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        channelLabelView = channelLabel
        card.addView(
            channelLabel,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(8)
            }
        )

        val joinRow = FrameLayout(context)
        val join = buildPrimaryButton(context, getString(R.string.clan_link_invite_join))
        join.setOnClickListener { onJoinClicked() }
        joinButton = join
        joinOriginalText = join.text
        joinRow.addView(join, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        val progress = ProgressBar(context).apply {
            visibility = View.GONE
        }
        joinProgress = progress
        joinRow.addView(
            progress,
            LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER)
        )
        card.addView(
            joinRow,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(32)
            }
        )

        val dismiss = buildSecondaryButton(context, getString(R.string.clan_link_invite_no_thanks))
        dismiss.setOnClickListener { dismissInvite() }
        card.addView(
            dismiss,
            LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(12)
            }
        )

        scroll.addView(card, FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val root = wrapWithActionBar(getString(R.string.clan_link_invite_title), scroll)
        actionBar?.setBackClickListener { dismissInvite() }
        fragmentView = root
        loadPreview()
        return root
    }

    private fun dismissInvite() {
        val activity = getParentActivity() as? MainActivity
        if (activity != null) {
            activity.popToMainTabsIfPresent()
        } else {
            finishFragment()
        }
    }

    override fun onBackPressed(): Boolean {
        dismissInvite()
        return false
    }

    override fun onFragmentDestroy() {
        logoView?.cancelLoad()
        clansLoadedObserver?.let {
            notificationCenter.removeObserver(it, NotificationCenter.clansDidLoad)
        }
        clansLoadedObserver = null
        pendingJoinTimeout?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        pendingJoinTimeout = null
        super.onFragmentDestroy()
    }

    private fun loadPreview() {
        if (inviteId == 0L) return
        fragmentScope.launch {
            val preview = withContext(Dispatchers.IO) {
                api.getLinkInvitePreview(inviteId)
            }
            withContext(Dispatchers.Main) {
                if (fragmentView == null || isPaused) return@withContext
                val clanName = if (preview == null) {
                    getString(R.string.clan_link_invite_unknown_clan)
                } else {
                    preview.clanName.ifBlank { getString(R.string.clan_link_invite_unknown_clan) }
                }
                clanNameView?.text = clanName
                if (preview != null && preview.channelLabel.isNotBlank()) {
                    channelLabelView?.visibility = View.VISIBLE
                    channelLabelView?.text = getString(R.string.clan_link_invite_channel_prefix, preview.channelLabel)
                } else {
                    channelLabelView?.visibility = View.GONE
                }
                logoView?.bind(
                    fallbackKey = inviteId,
                    displayName = clanName,
                    logoUrl = preview?.logoUrl?.takeIf { it.isNotBlank() },
                    fallbackStyle = DeeplinkLogoView.FallbackStyle.CLAN_LIST,
                )
            }
        }
    }

    private fun onJoinClicked() {
        val ctx = getContext() ?: return
        if (inviteId == 0L) {
            Toast.makeText(ctx, R.string.discover_join_failed, Toast.LENGTH_SHORT).show()
            return
        }
        setJoinLoading(true)
        fragmentScope.launch {
            val res = runCatching {
                sessionManager.withAutoRefresh { session ->
                    withContext(Dispatchers.IO) {
                        api.inviteUserByInviteId(session.apiUrl, session.token, inviteId)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                res.onSuccess { r ->
                    val cid = r.clanId
                    if (cid != 0L) {
                        navigateToJoinedClan(cid)
                    } else {
                        setJoinLoading(false)
                        Toast.makeText(ctx, R.string.discover_join_failed, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    setJoinLoading(false)
                    Toast.makeText(ctx, R.string.discover_join_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateToJoinedClan(clanId: Long) {
        if (pendingJoinClanId != 0L) return
        pendingJoinClanId = clanId
        val observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                finalizeJoin(clanId)
            }
        }
        clansLoadedObserver = observer
        notificationCenter.addObserver(observer, NotificationCenter.clansDidLoad)
        val timeout = Runnable { finalizeJoin(clanId) }
        pendingJoinTimeout = timeout
        AndroidUtilities.runOnUIThread(timeout, 2500L)
        clansController.loadClans(force = true)
    }

    private fun finalizeJoin(clanId: Long) {
        if (pendingJoinClanId != clanId) return
        pendingJoinClanId = 0L
        clansLoadedObserver?.let {
            notificationCenter.removeObserver(it, NotificationCenter.clansDidLoad)
        }
        clansLoadedObserver = null
        pendingJoinTimeout?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        pendingJoinTimeout = null
        setJoinLoading(false)
        clansController.selectClan(clanId, force = true)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
        finishFragment()
    }

    private fun setJoinLoading(loading: Boolean) {
        joinButton?.isEnabled = !loading
        joinButton?.alpha = if (loading) 0.6f else 1f
        joinProgress?.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            joinButton?.text = getString(R.string.clan_link_invite_joining)
        } else {
            joinButton?.text = joinOriginalText
        }
    }

    private fun buildPrimaryButton(context: Context, label: String): TextView {
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
        }
    }

    private fun buildSecondaryButton(context: Context, label: String): TextView {
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
        }
    }
}
