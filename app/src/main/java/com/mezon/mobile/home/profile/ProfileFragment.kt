package com.mezon.mobile.home.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mezon.mobile.R
import com.mezon.mobile.auth.AuthRepository
import com.mezon.mobile.core.AlertsCreator
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon
import com.mezon.mobile.ui.cells.SelectPopup
import com.mezon.mobile.ui.cells.ToastOverlay
import com.mezon.mobile.ui.theme.ThemeMode
import com.mezon.mobile.session.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfileFragment : BaseFragment() {

    companion object {
        private val PLACEHOLDER_COLORS = intArrayOf(
            Color.parseColor("#D8B4E2"), Color.parseColor("#A6A8CD"),
            Color.parseColor("#B69F91"), Color.parseColor("#030303"),
            Color.parseColor("#8E8E93")
        )
        private val MILLION = java.math.BigDecimal(1000000)
        private val VI_LOCALE = Locale("vi", "VN")
    }

    private var cachedDateFormat: SimpleDateFormat? = null
    private var cachedLocale: Locale? = null
    
    private fun getDateFormat(): SimpleDateFormat {
        val currentLocale = Locale.getDefault()
        if (cachedDateFormat == null || cachedLocale != currentLocale) {
            cachedLocale = currentLocale
            cachedDateFormat = SimpleDateFormat("dd 'tháng' M, yyyy", currentLocale)
        }
        return cachedDateFormat!!
    }

    private lateinit var userController: UserController
    private lateinit var accountController: AccountController
    private lateinit var authRepository: AuthRepository

    var onLogout: (() -> Unit)? = null

    private lateinit var avatarView: AvatarView
    private lateinit var displayNameText: TextView
    private lateinit var usernameText: TextView
    private lateinit var balanceText: TextView
    private lateinit var aboutMeContainer: LinearLayout
    private lateinit var aboutMeValueText: TextView
    private lateinit var memberSinceText: TextView
    private lateinit var statusBubble: LinearLayout
    private lateinit var plusIconView: View
    private lateinit var badgeStatusText: TextView
    private lateinit var walletSection: LinearLayout
    private lateinit var friendsAvatarsContainer: LinearLayout
    private lateinit var scrollView: ScrollView

    override fun onInject(entryPoint: FragmentEntryPoint) {
        userController = entryPoint.userController()
        accountController = entryPoint.accountController()
        authRepository = entryPoint.authRepository()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()

        observe(NotificationCenter.userDataLoaded) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            updateUI()
        }
        observe(NotificationCenter.accountInfoLoaded) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            updateUI()
        }
        observe(NotificationCenter.friendsLoaded) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            updateUI()
        }
        observe(NotificationCenter.updateInterfaces) { _, _, args ->
            if (fragmentView == null || isPaused) return@observe
            updateUI()
        }
        observe(NotificationCenter.themeChanged) { _, _, _ ->
            if (fragmentView == null) return@observe
            applyTheme()
        }
        observe(NotificationCenter.languageChanged) { _, _, _ ->
            if (fragmentView == null || isPaused) return@observe
            updateUI()
        }
        return true
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        accountController.loadAccount(noCache = false)
        accountController.loadFriends()
        updateUI()
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        val headerContainer = FrameLayout(context)
        root.addView(headerContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val topBg = View(context).apply {
            setBackgroundColor(Color.parseColor("#121212")) 
        }
        headerContainer.addView(topBg, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(160)
        ))

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(LayoutHelper.dp(18), LayoutHelper.dp(83), LayoutHelper.dp(18), 0)
        }
        headerContainer.addView(infoCol, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        val avatarStatusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        infoCol.addView(avatarStatusRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val showStatusSheet = {
            val info = accountController.accountInfo.value
            val sheet = ChangeUserStatusBottomSheet(
                requireContext(),
                info.onlineStatus.value,
                info.userStatus,
                { newStatus -> accountController.updateOnlineStatus(newStatus) },
                { presentFragment(EditStatusFragment()) },
                { accountController.updateCustomStatus(0L, "", 0, false) {} }
            )
            sheet.show()
        }

        val avatarContainer = FrameLayout(context).apply {
            clipChildren = false
        }
        avatarStatusRow.addView(avatarContainer, LinearLayout.LayoutParams(
            LayoutHelper.dp(96), LayoutHelper.dp(96)
        ))

        avatarView = AvatarView(context).apply { 
            setSizeDp(96)
            setRoundRadius(22f)
        }
        avatarContainer.addView(avatarView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        val onlineStatusIndicator = ImageView(context).apply {
            tag = "onlineStatusIndicator"
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = LayoutHelper.dp(2).toFloat()
        }
        avatarContainer.addView(onlineStatusIndicator, FrameLayout.LayoutParams(
            LayoutHelper.dp(16), LayoutHelper.dp(16)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        })

        avatarView.setOnClickListener { showStatusSheet() }

        statusBubble = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = object : Drawable() {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColors.surfaceVariant }
                private val path = Path()
                private var lastWidth = 0f
                private var lastHeight = 0f
                override fun draw(canvas: Canvas) {
                    val w = bounds.width().toFloat()
                    val h = bounds.height().toFloat()
                    if (w != lastWidth || h != lastHeight) {
                        lastWidth = w
                        lastHeight = h
                        val tailHeight = LayoutHelper.dp(6f).toFloat()
                        val rectBottom = h - tailHeight
                        val r = rectBottom / 2f
                        path.reset()
                        path.moveTo(0f, r)
                        path.arcTo(0f, 0f, 2 * r, 2 * r, 180f, 90f, false)
                        path.lineTo(w - r, 0f)
                        path.arcTo(w - 2 * r, 0f, w, rectBottom, 270f, 180f, false)
                        path.lineTo(r, rectBottom)
                        path.quadTo(0f, rectBottom, 0f, h)
                        path.lineTo(0f, r)
                        path.close()
                    }
                    canvas.drawPath(path, paint)
                }
                override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
                @Deprecated("Deprecated")
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(16), LayoutHelper.dp(14))
            setOnClickListener {
                presentFragment(EditStatusFragment())
            }
        }
        
        plusIconView = ImageView(context).apply {
            setImageResource(R.drawable.ic_plus_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        statusBubble.addView(plusIconView, LinearLayout.LayoutParams(
            LayoutHelper.dp(24), LayoutHelper.dp(24)
        ))

        badgeStatusText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
            maxLines = 2 
            ellipsize = TextUtils.TruncateAt.END
        }
        statusBubble.addView(badgeStatusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = LayoutHelper.dp(6) })

        avatarStatusRow.addView(statusBubble, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = LayoutHelper.dp(12); gravity = Gravity.CENTER_VERTICAL; bottomMargin = LayoutHelper.dp(28) })

        val nameIconsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, LayoutHelper.dp(12), 0, 0)
        }
        infoCol.addView(nameIconsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val nameChevronLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { showStatusSheet() }
        }
        nameIconsRow.addView(nameChevronLayout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        displayNameText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nameChevronLayout.addView(displayNameText)

        val chevronDown = ImageView(context).apply {
            setImageResource(MezonIcon.chevronDownSmallIcon.resId)
            colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        nameChevronLayout.addView(chevronDown, LinearLayout.LayoutParams(
            LayoutHelper.dp(20), LayoutHelper.dp(20)
        ).apply { leftMargin = LayoutHelper.dp(4) })

        val rightIconsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameIconsRow.addView(rightIconsLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        rightIconsLayout.addView(createCircleIconButton(context, MezonIcon.shopIcon) {
        })
        val gapIcon = View(context)
        rightIconsLayout.addView(gapIcon, LinearLayout.LayoutParams(LayoutHelper.dp(12), 0))
        rightIconsLayout.addView(createCircleIconButton(context, MezonIcon.settingProfileIcon) {
            openAccountSetting()
        })

        usernameText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
            maxLines = 1
        }
        infoCol.addView(usernameText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 0 })

        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, 0, LayoutHelper.dp(100))
        }
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val contentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(contentColumn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        walletSection = createCardSection(context)
        contentColumn.addView(walletSection, createCardMarginParams().apply { topMargin = LayoutHelper.dp(24) })

        val balanceRow = createIconTextRow(context, MezonIcon.balanceIcon, "", null) {
        }
        balanceText = balanceRow.getChildAt(1) as TextView
        walletSection.addView(balanceRow)

        walletSection.addView(createIconTextRow(context, MezonIcon.transferIcon, getString(R.string.profile_transfer), null) {
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0 })

        walletSection.addView(createIconTextRow(context, MezonIcon.historyTransactionIcon, getString(R.string.profile_history_transaction), null) {
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0 })

        val editProfileBtn = createEditProfileButton(context)
        walletSection.addView(editProfileBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(48)
        ).apply { topMargin = LayoutHelper.dp(10) })

        val aboutSection = createCardSection(context)
        contentColumn.addView(aboutSection, createCardMarginParams())

        aboutMeContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        aboutSection.addView(aboutMeContainer)

        val aboutMeLabel = TextView(context).apply {
            text = getString(R.string.profile_about_me)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.DEFAULT_BOLD
        }
        aboutMeContainer.addView(aboutMeLabel)

        aboutMeValueText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColors.onSurfaceVariant)
            setPadding(0, LayoutHelper.dp(4), 0, 0)
        }
        aboutMeContainer.addView(aboutMeValueText)

        val memberSinceContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        aboutSection.addView(memberSinceContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = LayoutHelper.dp(18) })

        val memberSinceLabel = TextView(context).apply {
            text = getString(R.string.profile_mezon_member_since)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.DEFAULT_BOLD
        }
        memberSinceContainer.addView(memberSinceLabel)

        memberSinceText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColors.onSurfaceVariant)
            setPadding(0, LayoutHelper.dp(4), 0, 0)
        }
        memberSinceContainer.addView(memberSinceText)

        val friendsCard = createCardSection(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        contentColumn.addView(friendsCard, createCardMarginParams())

        val friendsLabel = TextView(context).apply {
            text = getString(R.string.profile_your_friends)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.DEFAULT_BOLD
        }
        friendsCard.addView(friendsLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        friendsAvatarsContainer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        friendsCard.addView(friendsAvatarsContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = LayoutHelper.dp(8) })

        val friendsChevron = ImageView(context).apply {
            setImageResource(MezonIcon.chevronSmallRightIcon.resId)
            colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        friendsCard.addView(friendsChevron, LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)))

        val copyIdCard = createCardSection(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        contentColumn.addView(copyIdCard, createCardMarginParams())

        val copyIdLabel = TextView(context).apply {
            text = getString(R.string.profile_copy_user_id)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            typeface = Typeface.DEFAULT_BOLD
        }
        copyIdCard.addView(copyIdLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val idIcon = ImageView(context).apply {
            setImageResource(MezonIcon.idIcon.resId)
            colorFilter = PorterDuffColorFilter(themeColors.onSurface, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        copyIdCard.addView(idIcon, LinearLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20)))
        copyIdCard.setOnClickListener { copyUserId() }

        updateUI()
        return root
    }

    private fun createSettingsSection(context: Context): LinearLayout {
        val section = createCardSection(context)
        section.addView(createSettingsRow(context, getString(R.string.setting_theme_title), MezonIcon.paintPaletteIcon) { showThemeSelector(it) })
        section.addView(createSettingsRow(context, getString(R.string.setting_app_language), MezonIcon.languageIcon) { showLanguageSelector(it) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = LayoutHelper.dp(8) })
        section.addView(createSettingsRow(context, getString(R.string.profile_account_settings), MezonIcon.settingIcon) { openAccountSetting() }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = LayoutHelper.dp(8) })
        section.addView(createSettingsRow(context, "Component Preview", MezonIcon.settingIcon) { presentFragment(ComponentPreviewFragment()) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = LayoutHelper.dp(8) })
        val logoutRow = createSettingsRow(context, getString(R.string.profile_sign_out), MezonIcon.doorExitIcon) { confirmLogout() }
        (logoutRow.getChildAt(1) as? TextView)?.setTextColor(themeColors.error)
        logoutRow.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = LayoutHelper.dp(16) }
        section.addView(logoutRow)
        return section
    }

    private fun createSettingsRow(context: Context, title: String, icon: MezonIcon, onClick: (View) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = LayoutHelper.dp(6)
            setPadding(0, pad, 0, pad)
            isClickable = true; isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
            setOnClickListener { onClick(it) }
        }
        val iconView = ImageView(context).apply {
            setImageResource(icon.resId)
            colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(iconView, LinearLayout.LayoutParams(LayoutHelper.dp(24), LayoutHelper.dp(24)))
        val label = TextView(context).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(themeColors.onSurface)
            setPadding(LayoutHelper.dp(14), 0, 0, 0)
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val chevron = ImageView(context).apply {
            setImageResource(MezonIcon.chevronSmallRightIcon.resId)
            colorFilter = PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(chevron, LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)))
        return row
    }

    private fun createCircleIconButton(context: Context, icon: MezonIcon, onClick: () -> Unit): FrameLayout {
        val size = LayoutHelper.dp(36)
        val container = FrameLayout(context).apply {
            val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(themeColors.surfaceVariant) }
            background = bg
            isClickable = true; isFocusable = true; setOnClickListener { onClick() }
        }
        val iconView = ImageView(context).apply {
            setImageResource(icon.resId)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        container.addView(iconView, FrameLayout.LayoutParams(LayoutHelper.dp(22), LayoutHelper.dp(22), Gravity.CENTER))
        container.layoutParams = LinearLayout.LayoutParams(size, size)
        return container
    }

    private fun createCardSection(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = LayoutHelper.dp(20)
            setPadding(pad, LayoutHelper.dp(16), pad, LayoutHelper.dp(16))
            val bg = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(20f).toFloat(); setColor(themeColors.surfaceVariant)
            }
            background = bg
        }
    }

    private fun createCardMarginParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = LayoutHelper.dp(18); rightMargin = LayoutHelper.dp(18); topMargin = LayoutHelper.dp(12)
        }
    }

    private fun createIconTextRow(context: Context, icon: MezonIcon, text: String, tintColor: Int?, onClick: ((LinearLayout) -> Unit)?): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)
        }
        val iconView = ImageView(context).apply {
            setImageResource(icon.resId); scaleType = ImageView.ScaleType.CENTER_INSIDE
            if (tintColor != null) colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
        }
        row.addView(iconView, LinearLayout.LayoutParams(LayoutHelper.dp(24), LayoutHelper.dp(24)))
        val label = TextView(context).apply {
            this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(themeColors.onSurfaceVariant)
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(10), 0, LayoutHelper.dp(10))
        }
        row.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.setOnClickListener { onClick?.invoke(row) }
        return row
    }

    private fun createEditProfileButton(context: Context): FrameLayout {
        val container = FrameLayout(context).apply {
            val bg = GradientDrawable().apply { cornerRadius = LayoutHelper.dp(50f).toFloat(); setColor(Color.parseColor("#4F46E5")) }
            background = bg; isClickable = true; isFocusable = true; setOnClickListener { navigateToProfileSetting() }
        }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        container.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val editIcon = ImageView(context).apply {
            setImageResource(MezonIcon.editProfileIcon.resId)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        inner.addView(editIcon, LinearLayout.LayoutParams(LayoutHelper.dp(20), LayoutHelper.dp(20)))
        val btnText = TextView(context).apply {
            text = getString(R.string.profile_edit_status); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; setPadding(LayoutHelper.dp(10), 0, 0, 0)
        }
        inner.addView(btnText)
        return container
    }

    private fun updateUI() {
        if (!::avatarView.isInitialized) return
        val info = accountController.accountInfo.value
        android.util.Log.d("ProfileFragment", "updateUI: info.userStatus='${info.userStatus}', userController.userStatus='${userController.userStatus}'")

        val name = info.displayName.ifEmpty { info.username.ifEmpty { userController.displayName.ifEmpty { userController.username } } }
        val user = info.username.ifEmpty { userController.username }

        avatarView.setInfo(userController.userId, name)
        if (info.avatarUrl.isNotEmpty()) avatarView.setImageUrl(info.avatarUrl)
        else if (userController.avatarUrl.isNotEmpty()) avatarView.setImageUrl(userController.avatarUrl)

        displayNameText.text = name
        usernameText.text = user.removePrefix("@")

        val status = info.userStatus.ifEmpty { userController.userStatus }
        badgeStatusText.text = status.ifEmpty { getString(R.string.profile_add_status) }
        plusIconView.visibility = if (status.isEmpty()) View.VISIBLE else View.GONE
        (badgeStatusText.layoutParams as LinearLayout.LayoutParams).leftMargin = if (status.isEmpty()) LayoutHelper.dp(6) else 0

        val avatarContainer = avatarView.parent as? FrameLayout
        val indicator = avatarContainer?.findViewWithTag<ImageView>("onlineStatusIndicator")
        if (indicator != null) {
            val onlineStatus = info.onlineStatus
            val isEnlarged = onlineStatus.isEnlarged()
            val size = if (isEnlarged) LayoutHelper.dp(28) else LayoutHelper.dp(16)
            
            val lp = indicator.layoutParams as FrameLayout.LayoutParams
            val margin = if (isEnlarged) -LayoutHelper.dp(6) else 0
            if (lp.width != size || lp.rightMargin != margin) {
                lp.width = size
                lp.height = size
                lp.rightMargin = margin
                lp.bottomMargin = margin
                indicator.layoutParams = lp
            }

            indicator.setImageResource(onlineStatus.getIcon().resId)
        }

        val balanceVal = info.balance.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val actualBalance = balanceVal.divide(MILLION).toLong()
        val formattedBalance = String.format(VI_LOCALE, "%,d", actualBalance)
        balanceText.text = getString(R.string.profile_balance, formattedBalance)
        
        walletSection.visibility = if (info.address.isNotEmpty()) View.VISIBLE else View.GONE

        val aboutMe = info.aboutMe.ifEmpty { userController.aboutMe }
        if (aboutMe.isNotEmpty()) {
            aboutMeContainer.visibility = View.VISIBLE
            aboutMeValueText.text = aboutMe
        } else {
            aboutMeContainer.visibility = View.GONE
        }

        val createTime = userController.createTimeSeconds
        if (createTime > 0) {
            val timestamp = if (createTime.toString().length <= 10) createTime * 1000 else createTime
            memberSinceText.text = getDateFormat().format(Date(timestamp))
        } else {
            memberSinceText.text = ""
        }

        val friendsList = accountController.friends.value
        val displayFriends = friendsList.take(5)
        if (displayFriends.isNotEmpty()) {
            friendsAvatarsContainer.visibility = View.VISIBLE
            if (friendsAvatarsContainer.tag != "friends" || friendsAvatarsContainer.childCount != displayFriends.size) {
                friendsAvatarsContainer.removeAllViews()
                friendsAvatarsContainer.tag = "friends"
                for (i in displayFriends.indices) {
                    val fAvatarContainer = FrameLayout(requireContext()).apply {
                        val bg = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(themeColors.surfaceVariant)
                        }
                        background = bg
                    }
                    val fAvatar = AvatarView(requireContext()).apply {
                        setSizeDp(28)
                        setRoundRadius(14f)
                    }
                    fAvatarContainer.addView(fAvatar, FrameLayout.LayoutParams(LayoutHelper.dp(28), LayoutHelper.dp(28), Gravity.CENTER))
                    friendsAvatarsContainer.addView(fAvatarContainer, LinearLayout.LayoutParams(LayoutHelper.dp(32), LayoutHelper.dp(32)).apply { if (i > 0) leftMargin = -LayoutHelper.dp(10) })
                }
            }
            
            for (i in displayFriends.indices) {
                val friendUser = displayFriends[i].user
                val fAvatarContainer = friendsAvatarsContainer.getChildAt(i) as FrameLayout
                val fAvatar = fAvatarContainer.getChildAt(0) as AvatarView
                fAvatar.setInfo(friendUser.id, friendUser.displayName.ifEmpty { friendUser.username })
                if (friendUser.avatarUrl.isNotEmpty()) fAvatar.setImageUrl(friendUser.avatarUrl)
            }
        } else {
            friendsAvatarsContainer.visibility = View.GONE
            friendsAvatarsContainer.removeAllViews()
            friendsAvatarsContainer.tag = "empty"
        }
    }

    private fun applyTheme() {
        fragmentView?.setBackgroundColor(themeColors.background)
        updateUI()
    }

    private fun copyUserId() {
        val userId = userController.userIdStr
        if (userId.isBlank()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("User ID", userId))
        
        val parent = getLayoutContainer() ?: (fragmentView as? ViewGroup) ?: return
        ToastOverlay(requireContext(), themeColors).show(
            parent,
            ToastOverlay.ToastType.SUCCESS,
            getString(R.string.profile_copy_success)
        )
    }

    private fun openAccountSetting() {
        presentFragment(AccountSettingFragment().apply {
            onNavigateUpdateEmail = { presentFragment(UpdateEmailFragment.newInstance(it)) }
            onNavigateUpdatePhone = { presentFragment(UpdatePhoneFragment.newInstance(it)) }
            onNavigateBlockedUsers = { presentFragment(BlockedUsersFragment()) }
        })
    }

    private fun navigateToProfileSetting() = presentFragment(EditProfileFragment())
    private fun showThemeSelector(anchor: View) {
        val popup = SelectPopup(anchor.context, themeColors)
        val entries = ThemeMode.entries; val items = entries.map { getThemeDisplayName(it) }
        popup.setItems(items, entries.indexOf(userController.themeMode))
        popup.setOnItemSelectedListener { userController.applyTheme(entries[it]) }
        popup.show(anchor)
    }
    private fun showLanguageSelector(anchor: View) {
        val popup = SelectPopup(anchor.context, themeColors)
        val items = listOf(getString(R.string.setting_language_english), getString(R.string.setting_language_vietnamese))
        val tags = listOf(LocaleManager.ENGLISH, LocaleManager.VIETNAMESE)
        popup.setItems(items, tags.indexOf(userController.languageTag).let { if (it < 0) 0 else it })
        popup.setOnItemSelectedListener { userController.applyLanguage(tags[it]) }
        popup.show(anchor)
    }
    private fun confirmLogout() {
        AlertsCreator.createConfirmDialog(requireContext(), getString(R.string.setting_log_out), getString(R.string.setting_log_out_description), confirmText = getString(R.string.setting_log_out_yes), cancelText = getString(R.string.setting_log_out_no), destructive = true) {
            fragmentScope.launch(Dispatchers.Main) { authRepository.logout(); onLogout?.invoke() }
        }.show()
    }
    private fun getThemeDisplayName(mode: ThemeMode): String = when (mode) {
        ThemeMode.LIGHT -> getString(R.string.setting_theme_light)
        ThemeMode.DARK -> getString(R.string.setting_theme_dark)
        ThemeMode.ABYSS -> getString(R.string.setting_theme_abyss)
        ThemeMode.SYSTEM -> getString(R.string.setting_theme_system)
    }

}
