package com.mezon.mobile.home.profile

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.createImgproxyUrl
import kotlinx.coroutines.flow.first

class FriendRequestsFragment : BaseFragment() {

    private lateinit var viewModel: FriendRequestsViewModel

    // Palette màu cố định cho avatar initials (giống Discord/Mezon)
    private val avatarColors = listOf(
        0xFF5865F2.toInt(), // blurple
        0xFF57F287.toInt(), // green
        0xFFFEE75C.toInt(), // yellow
        0xFFEB459E.toInt(), // fuchsia
        0xFFED4245.toInt(), // red
        0xFF3498DB.toInt(), // blue
        0xFF9B59B6.toInt(), // purple
        0xFF1ABC9C.toInt(), // teal
        0xFFF39C12.toInt(), // orange
        0xFF2ECC71.toInt(), // emerald
    )

    private fun avatarBgColor(name: String): Int {
        val idx = (name.firstOrNull()?.code ?: 0) % avatarColors.size
        return avatarColors[idx]
    }

    override fun createView(context: Context): View {
        inject(context)

        val entryPoint = entryPoint()
        val api = entryPoint.mezonApi()

        val session = kotlinx.coroutines.runBlocking {
            entryPoint.sessionManager().sessionFlow.first { it != null }!!
        }

        val repo = FriendRepository(api, session.apiUrl, session.token)
        viewModel = FriendRequestsViewModel(repo)

        kotlinx.coroutines.runBlocking {
            viewModel.load()
            if (viewModel.receivedList.isEmpty()) {
                viewModel.setSelectedTab(1)
            } else {
                viewModel.setSelectedTab(0)
            }
        }

        // Root – nền dark/light từ themeColors
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        // ── Tab container ──────────────────────────────────────────────────────
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                LayoutHelper.dp(16), LayoutHelper.dp(16),
                LayoutHelper.dp(16), LayoutHelper.dp(12)
            )
        }

        // Pill background cho toàn bộ tab row
        val tabPillBg = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dp(100).toFloat()
            setColor(themeColors.tertiary)        // sáng/tối theo theme
        }
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = tabPillBg
            setPadding(LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4), LayoutHelper.dp(4))
        }

        // Các tab sẽ được rebuild khi click; giữ reference trong array
        val tabViews = Array<TextView?>(2) { null }

        fun buildTab(index: Int, label: String): TextView {
            val isSelected = viewModel.selectedTab.value == index
            return TextView(context).apply {
                text = label
                textSize = 14f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(
                    LayoutHelper.dp(28), LayoutHelper.dp(10),
                    LayoutHelper.dp(28), LayoutHelper.dp(10)
                )
                gravity = Gravity.CENTER
                setTextColor(
                    if (isSelected) Color.WHITE
                    else themeColors.onSurfaceVariant
                )
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(100).toFloat()
                    setColor(if (isSelected) 0xFF5865F2.toInt() else Color.TRANSPARENT)
                }
            }
        }

        // List container (scrollable)
        val scrollView = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(24))
        }
        scrollView.addView(listContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        fun selectTab(index: Int) {
            viewModel.setSelectedTab(index)
            // Refresh style on both tabs
            for (i in 0..1) {
                val tv = tabViews[i] ?: continue
                val sel = (i == index)
                tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tv.setTextColor(if (sel) Color.WHITE else themeColors.onSurfaceVariant)
                (tv.background as? GradientDrawable)?.setColor(
                    if (sel) 0xFF5865F2.toInt() else Color.TRANSPARENT
                )
            }
            // Rebuild list
            listContainer.removeAllViews()
            val list = if (index == 0) viewModel.receivedList else viewModel.sentList
            if (list.isEmpty()) {
                showEmptyState(context, listContainer, index)
            } else {
                list.forEach { friend ->
                    listContainer.addView(createFriendCard(context, friend, ::selectTab))
                }
            }
        }

        // Build tabs
        for (i in 0..1) {
            val label = if (i == 0)
                context.getString(R.string.received)
            else
                context.getString(R.string.sent)

            val tv = buildTab(i, label)
            tabViews[i] = tv
            tv.setOnClickListener { selectTab(i) }
            tabRow.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        tabContainer.addView(tabRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(tabContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        // Initial render
        val initTab = viewModel.selectedTab.value
        val initList = if (initTab == 0) viewModel.receivedList else viewModel.sentList
        if (initList.isEmpty()) {
            showEmptyState(context, listContainer, initTab)
        } else {
            initList.forEach { friend ->
                listContainer.addView(createFriendCard(context, friend, ::selectTab))
            }
        }

        return wrapWithActionBar(
            context.getString(R.string.friends_request_title),
            root
        )
    }

    // =========================
    // Empty State
    // =========================
    private fun showEmptyState(context: Context, container: LinearLayout, tabIndex: Int) {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = LayoutHelper.dp(80) }
        }

        val image = ImageView(context).apply {
            setImageResource(R.drawable.ic_friend_requests_empty)
            layoutParams = LinearLayout.LayoutParams(
                LayoutHelper.dp(200), LayoutHelper.dp(200)
            ).apply { bottomMargin = LayoutHelper.dp(24) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val title = TextView(context).apply {
            text = if (tabIndex == 0)
                context.getString(R.string.no_incoming_friend_requests)
            else
                context.getString(R.string.no_outgoing_friend_requests)
            setTextColor(themeColors.onSurface)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(24), 0, LayoutHelper.dp(24), LayoutHelper.dp(8))
        }

        val desc = TextView(context).apply {
            text = if (tabIndex == 0)
                context.getString(R.string.incoming_friend_requests_desc)
            else
                context.getString(R.string.outgoing_friend_requests_desc)
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(32), 0, LayoutHelper.dp(32), 0)
        }

        wrapper.addView(image)
        wrapper.addView(title)
        wrapper.addView(desc)
        container.addView(wrapper)
    }

    // =========================
    // Friend Card Item
    // =========================
    private fun createFriendCard(
        context: Context,
        friend: FriendEntity,
        onTabChanged: (Int) -> Unit
    ): View {
        val isReceived = viewModel.selectedTab.value == 0

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                LayoutHelper.dp(14), LayoutHelper.dp(12),
                LayoutHelper.dp(14), LayoutHelper.dp(12)
            )
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(16).toFloat()
                setColor(themeColors.tertiary)
            }
            elevation = LayoutHelper.dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = LayoutHelper.dp(10)
                marginStart = LayoutHelper.dp(16)
                marginEnd = LayoutHelper.dp(16)
            }
        }

        // ── Avatar ──────────────────────────────────────────────────────────────
        val avatarSize = LayoutHelper.dp(46)
        val avatarContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                marginEnd = LayoutHelper.dp(12)
            }
        }

        val displayName = friend.displayName.ifBlank { friend.username }
        val initial = displayName.firstOrNull()?.uppercase() ?: "?"
        val bgColor = avatarBgColor(displayName)

        // Initials circle (shown by default, hidden if real avatar loaded)
        val initialsBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
        }
        val initialsView = TextView(context).apply {
            text = initial
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = initialsBg
            layoutParams = FrameLayout.LayoutParams(avatarSize, avatarSize)
        }
        avatarContainer.addView(initialsView)

        // Real avatar ImageView (rounded via clipToOutline or BitmapShader)
        val avatarImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(avatarSize, avatarSize)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, avatarSize, avatarSize)
                }
            }
            clipToOutline = true
        }
        avatarContainer.addView(avatarImage)

        // Load real avatar if available
        val avatarUrl = friend.avatar.orEmpty()
        var cancellable: MezonImageLoader.Cancellable? = null
        if (avatarUrl.isNotEmpty()) {
            val proxyUrl = createImgproxyUrl(avatarUrl, avatarSize * 2, avatarSize * 2, "fill")
            val loader = MezonImageLoader.getInstance(context)
            val cached = loader.getBitmapFromMemory(proxyUrl, avatarSize, avatarSize)
            if (cached != null) {
                avatarImage.setImageDrawable(BitmapDrawable(context.resources, cached))
                avatarImage.visibility = View.VISIBLE
                initialsView.visibility = View.GONE
            } else {
                cancellable = loader.load(proxyUrl, avatarSize, avatarSize, onSuccess = { bmp ->
                    Handler(Looper.getMainLooper()).post {
                        avatarImage.setImageDrawable(BitmapDrawable(context.resources, bmp))
                        avatarImage.visibility = View.VISIBLE
                        initialsView.visibility = View.GONE
                    }
                })
            }
        }

        // Cancel load when card detached
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                cancellable?.cancel()
            }
        })

        card.addView(avatarContainer)

        // ── Info ─────────────────────────────────────────────────────────────────
        val infoLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(context).apply {
            text = friend.displayName.ifBlank { friend.username }
            setTextColor(themeColors.onSurface)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (friend.displayName.isNotBlank() && friend.username.isNotBlank()) {
            val usernameView = TextView(context).apply {
                text = friend.username
                setTextColor(themeColors.onSurfaceVariant)
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            infoLayout.addView(nameView)
            infoLayout.addView(usernameView)
        } else {
            infoLayout.addView(nameView)
        }
        card.addView(infoLayout)

        // ── Action buttons ────────────────────────────────────────────────────────
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Decline / Cancel button (X)
        val btnSize = LayoutHelper.dp(36)
        val declineBtn = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(themeColors.onSurfaceVariant)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.surfaceVariant)
            }
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginStart = LayoutHelper.dp(6)
            }
            setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
            setOnClickListener {
                viewModel.delete(friend)
                // Rebuild UI ngay với data hiện tại (friend bị remove khỏi local list
                // sau khi coroutine trong viewModel.delete() hoàn thành)
                Handler(Looper.getMainLooper()).postDelayed({
                    onTabChanged(viewModel.selectedTab.value)
                }, 300)
            }
        }
        actionsRow.addView(declineBtn)

        // Accept button (✓) – only for received tab
        if (isReceived) {
            val acceptBtn = ImageView(context).apply {
                setImageResource(android.R.drawable.checkbox_on_background)
                setColorFilter(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF43B581.toInt())   // online green
                }
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginStart = LayoutHelper.dp(8)
                }
                setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
                setOnClickListener {
                    viewModel.approve(friend)
                    Handler(Looper.getMainLooper()).postDelayed({
                        onTabChanged(viewModel.selectedTab.value)
                    }, 300)
                }
            }
            actionsRow.addView(acceptBtn)
        }

        card.addView(actionsRow)
        return card
    }
}
