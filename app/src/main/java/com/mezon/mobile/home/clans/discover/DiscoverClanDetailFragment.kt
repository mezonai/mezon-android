package com.mezon.mobile.home.clans.discover

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.home.clans.ClansController
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.util.avatarImgproxyUrl
import com.mezon.mobile.util.createImgproxyUrl
import com.mezon.mobile.network.MezonApi
import com.mezon.mobile.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class DiscoverClanDetailFragment : BaseFragment() {

    companion object {
        private const val HERO_BANNER_SCREEN_FRACTION = 0.25f
        private const val ARG_INVITE_ID = "inviteId"
        private const val ARG_CLAN_NAME = "clanName"
        private const val ARG_CLAN_LOGO = "clanLogo"
        private const val ARG_DESCRIPTION = "description"
        private const val ARG_BANNER = "banner"
        private const val ARG_ABOUT = "about"
        private const val ARG_TOTAL_MEMBERS = "totalMembers"
        private const val ARG_CREATE_TIME = "createTimeSeconds"

        fun newInstance(item: DiscoverClanItem): DiscoverClanDetailFragment {
            return DiscoverClanDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_INVITE_ID, item.inviteId)
                    putString(ARG_CLAN_NAME, item.clanName)
                    putString(ARG_CLAN_LOGO, item.clanLogo)
                    putString(ARG_DESCRIPTION, item.description)
                    putString(ARG_BANNER, item.banner)
                    putString(ARG_ABOUT, item.about)
                    putInt(ARG_TOTAL_MEMBERS, item.totalMembers)
                    putInt(ARG_CREATE_TIME, item.createTimeSeconds)
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
    private var pendingJoinClanId = 0L
    private var pendingJoinTimeout: Runnable? = null
    private var clansLoadedObserver: NotificationCenter.NotificationCenterDelegate? = null
    private var heroBannerLoad: MezonImageLoader.Cancellable? = null
    private var logoLoad: MezonImageLoader.Cancellable? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        api = entryPoint.mezonApi()
        sessionManager = entryPoint.sessionManager()
        clansController = entryPoint.clansController()
    }

    override fun createView(context: Context): View {
        val args = arguments ?: Bundle()
        inviteId = args.getLong(ARG_INVITE_ID)
        val clanName = args.getString(ARG_CLAN_NAME).orEmpty()
        val clanLogo = args.getString(ARG_CLAN_LOGO).orEmpty()
        val description = args.getString(ARG_DESCRIPTION).orEmpty()
        val banner = args.getString(ARG_BANNER).orEmpty()
        val about = args.getString(ARG_ABOUT).orEmpty()
        val totalMembers = args.getInt(ARG_TOTAL_MEMBERS)
        val createTimeSeconds = args.getInt(ARG_CREATE_TIME)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.chatBackground)
        }

        actionBar = createActionBar(context).apply {
            setTitle(clanName)
        }
        root.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
        }

        val content = FrameLayout(context).apply {
            clipChildren = false
        }

        val bannerH = heroBannerHeightPx(context)
        val sheetOverlap = LayoutHelper.dp(40)
        val logoSize = LayoutHelper.dp(80)
        val logoHalf = logoSize / 2

        val heroShell = FrameLayout(context).apply {
            clipChildren = false
        }
        val bannerIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        heroShell.addView(
            bannerIv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        content.addView(
            heroShell,
            FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, bannerH)
        )

        if (banner.isNotEmpty()) {
            val wPx = context.resources.displayMetrics.widthPixels
            val reqBannerW = max(wPx * 4, LayoutHelper.dp(1280))
            val reqBannerH = max(bannerH * 4, LayoutHelper.dp(512))
            val bannerUrl = createImgproxyUrl(banner, reqBannerW, reqBannerH, "fit")
            heroBannerLoad = MezonImageLoader.getInstance(context).load(
                bannerUrl, reqBannerW, reqBannerH,
                onSuccess = { bmp -> bannerIv.setImageBitmap(bmp) }
            )
        } else {
            bannerIv.setBackgroundColor(themeColors.surfaceVariant)
        }

        val padH = LayoutHelper.dp(16)
        val sheetTopRadius = LayoutHelper.dp(20f).toFloat()
        val avatarCenterY = bannerH - sheetOverlap
        val innerTopPad = logoHalf + LayoutHelper.dp(16)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, innerTopPad, padH, LayoutHelper.dp(24))
            background = sheetBackground(themeColors.chatBackground, sheetTopRadius)
        }
        content.addView(
            inner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = bannerH - sheetOverlap
            }
        )

        val logoFramePad = LayoutHelper.dp(4)
        val logoFrameCorner = LayoutHelper.dp(16f).toFloat()
        val logoInnerCorner = (LayoutHelper.dp(16f) - logoFramePad).toFloat().coerceAtLeast(0f)
        val logoFrame = FrameLayout(context).apply {
            clipChildren = true
            setPadding(logoFramePad, logoFramePad, logoFramePad, logoFramePad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = logoFrameCorner
                setColor(themeColors.chatBackground)
                setStroke(LayoutHelper.dp(4), themeColors.chatBackground)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, logoFrameCorner)
                }
            }
            elevation = LayoutHelper.dpf(4f)
        }
        val logoIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, logoInnerCorner)
                }
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        if (clanLogo.isNotEmpty()) {
            val logoPx = max(LayoutHelper.dp(80) * 2, 160)
            val logoUrl = avatarImgproxyUrl(clanLogo, logoPx)
            logoLoad = MezonImageLoader.getInstance(context).load(
                logoUrl, logoPx, logoPx,
                onSuccess = { bmp -> logoIv.setImageBitmap(bmp) }
            )
        } else {
            logoIv.setBackgroundColor(themeColors.surfaceVariant)
        }
        logoFrame.addView(logoIv)
        content.addView(
            logoFrame,
            FrameLayout.LayoutParams(logoSize, logoSize).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = avatarCenterY - logoHalf
            }
        )

        inner.addView(label(context, clanName, 24f, true, themeColors.onSurface))

        inner.addView(label(context, description, 14f, false, themeColors.onSurfaceVariant).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, LayoutHelper.dp(16))
        })

        val membersRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.connectedColor)
            }
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(8), LayoutHelper.dp(8)).apply {
                rightMargin = LayoutHelper.dp(6)
                topMargin = LayoutHelper.dp(4)
            }
        }
        membersRow.addView(dot)
        membersRow.addView(label(context, "$totalMembers ${getString(R.string.discover_members)}", 14f, true, themeColors.onSurface))
        inner.addView(membersRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(24)
        })

        val joinWrap = FrameLayout(context)
        joinButton = TextView(context).apply {
            text = getString(R.string.discover_join_clan)
            setTextColor(themeColors.onPrimary)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = joinButtonBackground()
            isClickable = true
            isFocusable = true
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(12), LayoutHelper.dp(16), LayoutHelper.dp(12))
            setOnClickListener { onJoinClicked() }
        }
        joinWrap.addView(joinButton, FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        joinProgress = ProgressBar(context).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(themeColors.onPrimary)
        }
        joinWrap.addView(joinProgress, FrameLayout.LayoutParams(LayoutHelper.dp(24), LayoutHelper.dp(24)).apply {
            gravity = Gravity.CENTER
        })
        inner.addView(joinWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
            bottomMargin = LayoutHelper.dp(24)
        })

        inner.addView(infoBlock(context, MezonIcon.messagePlusIcon, getString(R.string.discover_how_chatty), getString(R.string.discover_des_how_chatty)))
        inner.addView(infoBlock(context, MezonIcon.calendarIcon, getString(R.string.discover_clan_created), formatCreateTime(createTimeSeconds)))
        inner.addView(infoBlock(context, MezonIcon.starIcon, getString(R.string.discover_feature), getString(R.string.discover_des_feature)))
        inner.addView(infoBlock(context, MezonIcon.userGroupIcon, getString(R.string.discover_community), getString(R.string.discover_des_community)))

        if (about.isNotEmpty()) {
            inner.addView(label(context, getString(R.string.discover_about), 16f, true, themeColors.onSurface).apply {
                setPadding(0, 0, 0, LayoutHelper.dp(12))
            })
            inner.addView(label(context, about, 14f, false, themeColors.onSurfaceVariant).apply {
                setPadding(0, 0, 0, LayoutHelper.dp(16))
            })
        }

        val tagsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(getString(R.string.discover_tag_science), getString(R.string.discover_tag_entertainment)).forEach { tagText ->
            val tag = TextView(context).apply {
                text = tagText
                textSize = 12f
                setTextColor(themeColors.onSurface)
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(6), LayoutHelper.dp(12), LayoutHelper.dp(6))
                background = GradientDrawable().apply {
                    setColor(themeColors.secondaryLight)
                    cornerRadius = LayoutHelper.dp(16f).toFloat()
                    setStroke(LayoutHelper.dp(1), themeColors.borderDim)
                }
            }
            tagsRow.addView(tag, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                rightMargin = LayoutHelper.dp(8)
            })
        }
        inner.addView(tagsRow)

        scroll.addView(content)
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        fragmentView = root
        return root
    }

    private fun heroBannerHeightPx(context: Context): Int {
        val screenH = context.resources.displayMetrics.heightPixels
        return (screenH * HERO_BANNER_SCREEN_FRACTION).toInt()
    }

    private fun sheetBackground(fillColor: Int, topRadiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadii = floatArrayOf(
                topRadiusPx, topRadiusPx,
                topRadiusPx, topRadiusPx,
                0f, 0f,
                0f, 0f
            )
        }
    }

    private fun label(
        context: Context,
        text: String,
        sizeSp: Float,
        bold: Boolean,
        color: Int
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun infoBlock(context: Context, mezonIcon: MezonIcon, title: String, body: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, LayoutHelper.dp(16))
        }
        val iconHolder = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40)).apply {
                rightMargin = LayoutHelper.dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.secondaryLight)
            }
        }
        val icon = ImageView(context).apply {
            setImageDrawable(mezonIcon.getDrawable(context, themeColors.onSurface))
            layoutParams = FrameLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20)).apply {
                gravity = Gravity.CENTER
            }
        }
        iconHolder.addView(icon)
        row.addView(iconHolder)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        }
        col.addView(label(context, title, 14f, true, themeColors.onSurface).apply { gravity = Gravity.START })
        col.addView(label(context, body, 12f, false, themeColors.onSurfaceVariant).apply {
            gravity = Gravity.START
            setPadding(0, LayoutHelper.dp(4), 0, 0)
        })
        row.addView(col)
        return row
    }

    private fun formatCreateTime(seconds: Int): String {
        if (seconds <= 0) return "—"
        return try {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            sdf.format(Date(seconds * 1000L))
        } catch (_: Exception) {
            "—"
        }
    }

    private fun joinButtonBackground(): RippleDrawable {
        val fill = GradientDrawable().apply {
            setColor(themeColors.blurple)
            cornerRadius = LayoutHelper.dp(12f).toFloat()
        }
        val mask = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = LayoutHelper.dp(12f).toFloat()
        }
        val rippleColor = (themeColors.onPrimary and 0x00FFFFFF) or 0x40000000
        return RippleDrawable(ColorStateList.valueOf(rippleColor), fill, mask)
    }

    private fun setJoinLoading(loading: Boolean) {
        val button = joinButton ?: return
        val progress = joinProgress
        if (loading) {
            if (joinOriginalText.isEmpty()) joinOriginalText = button.text
            button.text = ""
            button.isClickable = false
            button.isEnabled = false
            progress?.visibility = View.VISIBLE
        } else {
            if (joinOriginalText.isNotEmpty()) button.text = joinOriginalText
            button.isClickable = true
            button.isEnabled = true
            progress?.visibility = View.GONE
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

        val handler = Handler(Looper.getMainLooper())
        val observer = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                finalizeJoin(clanId)
            }
        }
        clansLoadedObserver = observer
        notificationCenter.addObserver(observer, NotificationCenter.clansDidLoad)

        pendingJoinTimeout = Runnable { finalizeJoin(clanId) }
        handler.postDelayed(pendingJoinTimeout!!, 2500L)

        clansController.loadClans(force = true)
    }

    private fun finalizeJoin(clanId: Long) {
        if (pendingJoinClanId != clanId) return
        pendingJoinClanId = 0L

        clansLoadedObserver?.let {
            notificationCenter.removeObserver(it, NotificationCenter.clansDidLoad)
        }
        clansLoadedObserver = null
        pendingJoinTimeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        pendingJoinTimeout = null

        clansController.selectClan(clanId, force = true)
        notificationCenter.postNotificationOnMainThread(NotificationCenter.navigateToClansTab)
        popToClansFragment()
    }

    private fun popToClansFragment() {
        val layout = parentLayout
        if (layout == null) {
            finishFragment()
            return
        }
        val stack = ArrayList(layout.getFragmentStack())
        for (i in stack.size - 2 downTo 0) {
            val f = stack[i]
            if (f is com.mezon.mobile.home.clans.ClansFragment) {
                for (j in stack.size - 2 downTo i + 1) {
                    layout.removeFragmentFromStack(stack[j])
                }
                finishFragment()
                return
            }
        }
        finishFragment()
    }

    override fun onFragmentDestroy() {
        heroBannerLoad?.cancel()
        heroBannerLoad = null
        logoLoad?.cancel()
        logoLoad = null
        clansLoadedObserver?.let {
            notificationCenter.removeObserver(it, NotificationCenter.clansDidLoad)
        }
        clansLoadedObserver = null
        pendingJoinTimeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        pendingJoinTimeout = null
        super.onFragmentDestroy()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        return true
    }
}
