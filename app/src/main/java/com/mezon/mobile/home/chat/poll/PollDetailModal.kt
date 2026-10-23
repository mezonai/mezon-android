package com.mezon.mobile.home.chat.poll

import android.app.Dialog
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.util.avatarImgproxyUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PollDetailModal(
    context: android.content.Context,
    private val themeColors: ThemeColors,
    private val scope: CoroutineScope,
    private val seedParsed: ParsedPoll,
    private val loadPoll: suspend () -> ParsedPoll,
    membersResolver: (Set<Long>) -> Map<Long, ClanMember>,
    private val notificationCenter: NotificationCenter,
    private val clanId: Long,
    private val channelId: Long,
    private val parentChannelId: Long
) : Dialog(context), NotificationCenter.NotificationCenterDelegate {

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var optionsList: LinearLayout
    private lateinit var votersList: RecyclerView
    private var loaded: ParsedPoll = seedParsed
    private var selectedAnswerIndex: Int = seedParsed.answers.firstOrNull()?.index ?: 0

    private val voterAdapter = VoterAdapter(themeColors, membersResolver)
    private val memberEvents = intArrayOf(
        NotificationCenter.clanMembersDidLoad,
        NotificationCenter.channelMembersDidLoad,
        NotificationCenter.userClansDidLoad,
        NotificationCenter.dialogsNeedReload
    )

    override fun onStart() {
        super.onStart()
        memberEvents.forEach { notificationCenter.addObserver(this, it) }
    }

    override fun onStop() {
        memberEvents.forEach { notificationCenter.removeObserver(this, it) }
        super.onStop()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        val target = args.firstOrNull() as? Long
        when (id) {
            NotificationCenter.clanMembersDidLoad -> {
                if (clanId == 0L || target != clanId) return
            }
            NotificationCenter.channelMembersDidLoad -> {
                if (target != channelId && (parentChannelId == 0L || target != parentChannelId)) return
            }
            NotificationCenter.userClansDidLoad -> Unit
            NotificationCenter.dialogsNeedReload -> {
                if (clanId != 0L) return
            }
            else -> return
        }
        voterAdapter.refreshProfiles()
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val maxDialogW =
            (AndroidUtilities.displaySize.x * 0.92f).toInt().coerceAtMost(LayoutHelper.dp(560))
        val maxDialogH =
            (AndroidUtilities.displaySize.y * 0.72f).toInt().coerceAtMost(LayoutHelper.dp(560))

        val modalBg = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(12f)
            setColor(themeColors.surface)
            setStroke(LayoutHelper.dp(1), themeColors.borderDim)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = modalBg
            clipToOutline = true
        }

        val header = FrameLayout(context).apply {
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(16), LayoutHelper.dp(8), 0)
        }
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(themeColors.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 3
        }
        val closeSize = LayoutHelper.dp(40)
        header.addView(
            titleView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = closeSize + LayoutHelper.dp(8) }
        )

        val close = ImageView(context).apply {
            setImageResource(R.drawable.ic_close_icon)
            colorFilter =
                PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10), LayoutHelper.dp(10))
            contentDescription = context.getString(R.string.common_close)
            setOnClickListener { dismiss() }
        }
        header.addView(
            close,
            FrameLayout.LayoutParams(closeSize, closeSize, Gravity.END or Gravity.TOP)
        )
        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        subtitleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurfaceVariant)
        }
        root.addView(
            subtitleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = LayoutHelper.dp(4)
                marginStart = LayoutHelper.dp(16)
                marginEnd = LayoutHelper.dp(16)
            }
        )

        val divider = android.view.View(context).apply {
            setBackgroundColor(themeColors.borderDim)
        }
        root.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(1)
            ).apply { topMargin = LayoutHelper.dp(16) }
        )

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val leftScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
        }
        optionsList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(12))
        }
        leftScroll.addView(
            optionsList,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        body.addView(
            leftScroll,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.4f)
        )

        val vDiv = android.view.View(context).apply {
            setBackgroundColor(themeColors.borderDim)
        }
        body.addView(
            vDiv,
            LinearLayout.LayoutParams(LayoutHelper.dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
        )

        votersList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = voterAdapter
            clipToPadding = false
            setPadding(LayoutHelper.dp(12), LayoutHelper.dp(4), LayoutHelper.dp(12), LayoutHelper.dp(4))
        }
        body.addView(
            votersList,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.6f)
        )

        val bodyH = (maxDialogH * 0.68f).toInt()
            .coerceIn(LayoutHelper.dp(200), LayoutHelper.dp(420))
        root.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                bodyH.coerceAtMost(maxDialogH - LayoutHelper.dp(140))
            )
        )

        setContentView(root)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                width = maxDialogW
                height = WindowManager.LayoutParams.WRAP_CONTENT
                dimAmount = 0.52f
            }
            setLayout(maxDialogW, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        setCanceledOnTouchOutside(true)

        loaded = seedParsed
        if (loaded.answers.none { it.index == selectedAnswerIndex }) {
            selectedAnswerIndex = loaded.answers.firstOrNull()?.index ?: 0
        }
        bindHeader()
        rebuildOptions()
        selectOption(selectedAnswerIndex)

        scope.launch {
            val p = withContext(Dispatchers.IO) {
                try {
                    loadPoll()
                } catch (e: Exception) {
                    Log.w(TAG, "loadPoll", e)
                    null
                }
            }
            withContext(Dispatchers.Main) {
                if (!isShowing) return@withContext
                if (p == null) {
                    val base = context.resources.getQuantityString(
                        R.plurals.poll_total_votes,
                        loaded.totalVotes.coerceAtLeast(0),
                        loaded.totalVotes.coerceAtLeast(0)
                    )
                    subtitleView.text = "$base - ${context.getString(R.string.poll_detail_refresh_failed)}"
                    return@withContext
                }
                loaded = p
                if (loaded.answers.none { it.index == selectedAnswerIndex }) {
                    selectedAnswerIndex = loaded.answers.firstOrNull()?.index ?: 0
                }
                bindHeader()
                rebuildOptions()
                selectOption(selectedAnswerIndex)
            }
        }
    }

    private fun bindHeader() {
        titleView.text = pollAnswerPlainText(loaded.question)
        val total = loaded.totalVotes.coerceAtLeast(0)
        subtitleView.text = context.resources.getQuantityString(R.plurals.poll_total_votes, total, total)
    }

    private fun optionRowBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(10f)
            setColor(
                if (selected) ColorUtils.blendARGB(themeColors.surface, themeColors.primary, 0.2f)
                else Color.TRANSPARENT
            )
        }
    }

    private fun rebuildOptions() {
        optionsList.removeAllViews()
        for (ans in loaded.answers) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = LayoutHelper.dp(48)
                setPadding(LayoutHelper.dp(8), LayoutHelper.dp(12), LayoutHelper.dp(8), LayoutHelper.dp(12))
                background = optionRowBackground(false)
                setOnClickListener { selectOption(ans.index) }
            }
            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(themeColors.onSurface)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                text = buildPollAnswerSpannable(ans.label, this)
            }
            val count = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(themeColors.tabLabelInactive)
                setSingleLine(true)
                text = loaded.countFor(ans.index).toString()
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(count, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = LayoutHelper.dp(8) })
            optionsList.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = LayoutHelper.dp(12) }
            )
        }
    }

    private fun selectOption(answerIndex: Int) {
        selectedAnswerIndex = answerIndex
        for (i in 0 until optionsList.childCount) {
            val v = optionsList.getChildAt(i)
            val ans = loaded.answers.getOrNull(i) ?: continue
            val sel = ans.index == answerIndex
            v.isSelected = sel
            v.background = optionRowBackground(sel)
        }
        val voters =
            loaded.voterDetails.find { it.answerIndex == answerIndex }?.userIds.orEmpty()
        voterAdapter.submit(voters)
    }

    private class VoterAdapter(
        private val theme: ThemeColors,
        membersResolver: (Set<Long>) -> Map<Long, ClanMember>
    ) : RecyclerView.Adapter<VoterHolder>() {
        private val profiles = PollVoterProfiles(membersResolver)

        fun submit(voterIds: List<Long>) {
            profiles.setVoters(voterIds)
            notifyDataSetChanged()
        }

        fun refreshProfiles() {
            profiles.refresh().forEach { notifyItemChanged(it, "profile") }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoterHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                minimumHeight = LayoutHelper.dp(60)
                setPadding(0, LayoutHelper.dp(10), 0, LayoutHelper.dp(10))
            }
            val avatar = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(36), LayoutHelper.dp(36))
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
            }
            val texts = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = LayoutHelper.dp(10)
                }
            }
            val name = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(theme.onSurface)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            val sub = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(theme.tabLabelInactive)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            texts.addView(name)
            texts.addView(sub)
            row.addView(avatar)
            row.addView(texts)
            return VoterHolder(row, avatar, name, sub)
        }

        override fun onBindViewHolder(holder: VoterHolder, position: Int) {
            holder.bind(profiles[position])
        }

        override fun getItemCount(): Int = profiles.size
    }

    private class VoterHolder(
        private val row: LinearLayout,
        private val avatar: android.widget.ImageView,
        private val name: TextView,
        private val sub: TextView
    ) : RecyclerView.ViewHolder(row) {
        private var cancel: MezonImageLoader.Cancellable? = null

        fun bind(item: PollVoterProfile) {
            name.text = item.display
            sub.text = item.username
            cancel?.cancel()
            cancel = null
            val ad = AvatarDrawable()
            ad.setUserId(item.userId)
            avatar.setImageDrawable(ad)
            if (item.avatarUrl.isNotEmpty()) {
                val url = avatarImgproxyUrl(item.avatarUrl, LayoutHelper.dp(36))
                cancel = MezonImageLoader.getInstance(row.context).load(
                    url, LayoutHelper.dp(36), LayoutHelper.dp(36),
                    onSuccess = { bmp ->
                        avatar.setImageBitmap(bmp)
                    },
                    onError = {}
                )
            }
        }
    }

    companion object {
        private const val TAG = "PollDetailModal"
    }
}
