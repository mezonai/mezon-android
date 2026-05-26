package com.mezon.mobile.home.chat.poll

import android.app.Dialog
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.text.SpannableStringBuilder
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
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
    private val memberResolver: (Long) -> ClanMember?
) : Dialog(context) {

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var optionsList: LinearLayout
    private lateinit var votersList: RecyclerView
    private var loaded: ParsedPoll = seedParsed
    private var selectedAnswerIndex: Int = seedParsed.answers.firstOrNull()?.index ?: 0

    private val voterAdapter = VoterAdapter(themeColors)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val maxDialogW =
            (AndroidUtilities.displaySize.x * 0.92f).toInt().coerceAtMost(LayoutHelper.dp(560))
        val maxDialogH =
            (AndroidUtilities.displaySize.y * 0.72f).toInt().coerceAtMost(LayoutHelper.dp(560))

        val modalBg = GradientDrawable().apply {
            cornerRadius = LayoutHelper.dpf(20f)
            setColor(themeColors.surface)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = modalBg
            setPadding(LayoutHelper.dp(16), LayoutHelper.dp(14), LayoutHelper.dp(16), LayoutHelper.dp(14))
        }

        val header = FrameLayout(context)
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            setTextColor(themeColors.onSurface)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 3
        }
        val closeSize = LayoutHelper.dp(22)
        header.addView(
            titleView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply { rightMargin = closeSize + LayoutHelper.dp(10) }
        )

        val close = ImageView(context).apply {
            setImageResource(R.drawable.ic_close_icon)
            colorFilter =
                PorterDuffColorFilter(themeColors.onSurfaceVariant, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(LayoutHelper.dp(2), LayoutHelper.dp(2), LayoutHelper.dp(2), LayoutHelper.dp(2))
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
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(themeColors.onSurfaceVariant)
        }
        root.addView(
            subtitleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = LayoutHelper.dp(6) }
        )

        val divider = android.view.View(context).apply {
            setBackgroundColor(themeColors.outlineVariant)
        }
        root.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LayoutHelper.dp(1)
            ).apply { topMargin = LayoutHelper.dp(12) }
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
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )

        val vDiv = android.view.View(context).apply {
            setBackgroundColor(themeColors.outlineVariant)
        }
        body.addView(
            vDiv,
            LinearLayout.LayoutParams(LayoutHelper.dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
        )

        votersList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = voterAdapter
            clipToPadding = false
            setPadding(LayoutHelper.dp(4), 0, LayoutHelper.dp(4), 0)
        }
        body.addView(
            votersList,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )

        val bodyH = (maxDialogH * 0.58f).toInt()
            .coerceIn(LayoutHelper.dp(200), LayoutHelper.dp(420))
        root.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                bodyH.coerceAtMost(maxDialogH - LayoutHelper.dp(140))
            ).apply {
                topMargin = LayoutHelper.dp(12)
            }
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
            setColor(if (selected) themeColors.surfaceVariant else Color.TRANSPARENT)
        }
    }

    private fun rebuildOptions() {
        optionsList.removeAllViews()
        for (ans in loaded.answers) {
            val row = TextView(context).apply {
                setPadding(LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12), LayoutHelper.dp(12))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(themeColors.onSurface)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                val cnt = loaded.countFor(ans.index)
                val label = buildPollAnswerSpannable(ans.label, this)
                text = SpannableStringBuilder().apply {
                    append(label)
                    append("  ·  $cnt")
                }
                background = optionRowBackground(false)
                setOnClickListener {
                    selectOption(ans.index)
                }
            }
            optionsList.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = LayoutHelper.dp(6) }
            )
        }
    }

    private fun selectOption(answerIndex: Int) {
        selectedAnswerIndex = answerIndex
        for (i in 0 until optionsList.childCount) {
            val v = optionsList.getChildAt(i) as? TextView ?: continue
            val ans = loaded.answers.getOrNull(i) ?: continue
            val sel = ans.index == answerIndex
            v.background = optionRowBackground(sel)
        }
        val voters =
            loaded.voterDetails.find { it.answerIndex == answerIndex }?.userIds.orEmpty()
        if (voters.isEmpty()) {
            voterAdapter.submit(emptyList())
            return
        }
        val models = voters.map { uid ->
            val m = try {
                memberResolver(uid)
            } catch (e: Exception) {
                Log.w(TAG, "memberResolver", e)
                null
            }
            VoterRow(
                uid,
                m?.displayName?.ifBlank { m.username }.orEmpty().ifEmpty { uid.toString() },
                m?.username.orEmpty().ifEmpty { uid.toString() },
                m?.avatarUrl.orEmpty()
            )
        }
        voterAdapter.submit(models)
    }

    private class VoterRow(
        val userId: Long,
        val display: String,
        val username: String,
        val avatarUrl: String
    )

    private class VoterAdapter(
        private val theme: ThemeColors
    ) : RecyclerView.Adapter<VoterHolder>() {
        private var items: List<VoterRow> = emptyList()

        fun submit(list: List<VoterRow>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoterHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8), LayoutHelper.dp(8))
            }
            val avatar = android.widget.ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(40), LayoutHelper.dp(40))
            }
            val texts = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = LayoutHelper.dp(10)
                }
            }
            val name = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(theme.onSurface)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val sub = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                setTextColor(theme.onSurfaceVariant)
            }
            texts.addView(name)
            texts.addView(sub)
            row.addView(avatar)
            row.addView(texts)
            return VoterHolder(row, avatar, name, sub)
        }

        override fun onBindViewHolder(holder: VoterHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class VoterHolder(
        private val row: LinearLayout,
        private val avatar: android.widget.ImageView,
        private val name: TextView,
        private val sub: TextView
    ) : RecyclerView.ViewHolder(row) {
        private var cancel: MezonImageLoader.Cancellable? = null

        fun bind(item: VoterRow) {
            name.text = item.display
            sub.text = item.username
            cancel?.cancel()
            cancel = null
            val ad = AvatarDrawable()
            ad.setInfo(item.userId, item.username)
            avatar.setImageDrawable(ad)
            if (item.avatarUrl.isNotEmpty()) {
                val url = avatarImgproxyUrl(item.avatarUrl, LayoutHelper.dp(40))
                cancel = MezonImageLoader.getInstance(row.context).load(
                    url, LayoutHelper.dp(40), LayoutHelper.dp(40),
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
