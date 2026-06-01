package com.mezon.mobile.home.clans.channelapp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.BaseFragment
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.NotificationCenter
import com.mezon.mobile.di.FragmentEntryPoint
import com.mezon.mobile.home.chat.MezonImageLoader
import com.mezon.mobile.ui.cells.MezonIcon

class ChannelAppShowAllFragment : BaseFragment() {

    private lateinit var channelAppController: ChannelAppController

    private var clanId: Long = 0L
    private var adapter: AppsAdapter? = null
    private var emptyView: View? = null
    private var list: RecyclerView? = null

    override fun onInject(entryPoint: FragmentEntryPoint) {
        channelAppController = entryPoint.channelAppController()
    }

    override fun onFragmentCreate(): Boolean {
        super.onFragmentCreate()
        clanId = arguments?.getLong(ARG_CLAN_ID) ?: 0L
        observe(NotificationCenter.channelAppsDidLoad) { _, _, args ->
            val updatedClanId = args.firstOrNull() as? Long ?: return@observe
            if (updatedClanId == clanId) refresh()
        }
        return true
    }

    override fun createView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
        }

        root.addView(
            buildHeader(context),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LayoutHelper.dp(52))
        )

        val content = FrameLayout(context)

        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(false)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            adapter = this@ChannelAppShowAllFragment.adapter ?: AppsAdapter { onAppClicked(it) }
                .also { this@ChannelAppShowAllFragment.adapter = it }
            setPadding(
                LayoutHelper.dp(12),
                LayoutHelper.dp(8),
                LayoutHelper.dp(12),
                LayoutHelper.dp(16)
            )
            clipToPadding = false
        }
        content.addView(
            list,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        emptyView = buildEmptyView(context).also {
            it.visibility = View.GONE
            content.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        refresh()
        channelAppController.loadAppsForClan(clanId, force = true)
        return root
    }

    private fun buildHeader(context: Context): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(themeColors.channelPanelBg)
            setPadding(LayoutHelper.dp(8), 0, LayoutHelper.dp(16), 0)
        }
        val backBtn = ImageView(context).apply {
            setImageDrawable(MezonIcon.arrowLargeLeftIcon.getDrawable(context, themeColors.colorText))
            val sz = LayoutHelper.dp(40)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            val pad = LayoutHelper.dp(10)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            foreground = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFFFFF.toInt())
                }
            )
            setOnClickListener { finishFragment() }
        }
        header.addView(backBtn)

        val title = TextView(context).apply {
            textSize = 16f
            setTextColor(themeColors.colorText)
            val mezon = context.getString(R.string.channel_apps_title_mezon)
            val suffix = context.getString(R.string.channel_apps_title_subtitle)
            val builder = SpannableStringBuilder()
            builder.append(mezon)
            builder.setSpan(StyleSpan(Typeface.BOLD), 0, mezon.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append(suffix)
            text = builder
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = LayoutHelper.dp(4)
            layoutParams = lp
        }
        header.addView(title)
        return header
    }

    private fun buildEmptyView(context: Context): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                LayoutHelper.dp(24),
                LayoutHelper.dp(24),
                LayoutHelper.dp(24),
                LayoutHelper.dp(24)
            )
        }
        val icon = ImageView(context).apply {
            setImageDrawable(MezonIcon.channelApp.getDrawable(context, themeColors.textDisabled))
            val sz = LayoutHelper.dp(48)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        val tv = TextView(context).apply {
            text = context.getString(R.string.channel_apps_empty)
            textSize = 14f
            setTextColor(themeColors.textDisabled)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = LayoutHelper.dp(12)
            layoutParams = lp
        }
        col.addView(icon)
        col.addView(tv)
        return col
    }

    private fun refresh() {
        val apps = channelAppController.getApps(clanId)
        adapter?.submit(apps)
        emptyView?.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        list?.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun onAppClicked(app: ChannelAppUiModel) {
        presentFragment(
            ChannelAppFragment.newInstance(
                channelId = app.channelId,
                clanId = if (app.clanId != 0L) app.clanId else clanId,
                appId = app.appId,
                appUrl = app.appUrl,
                appName = app.appName
            )
        )
    }

    private inner class AppsAdapter(
        private val onClick: (ChannelAppUiModel) -> Unit
    ) : RecyclerView.Adapter<AppHolder>() {
        private val items = ArrayList<ChannelAppUiModel>()

        fun submit(newItems: List<ChannelAppUiModel>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder {
            val holder = AppHolder(AppRowView(parent.context, themeColors))
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
            return holder
        }

        override fun onBindViewHolder(holder: AppHolder, position: Int) {
            val item = items[position]
            (holder.itemView as AppRowView).bind(item)
        }

        override fun getItemCount(): Int = items.size
    }

    private class AppHolder(view: View) : RecyclerView.ViewHolder(view)

    private class AppRowView(
        context: Context,
        private val themeColors: com.mezon.mobile.core.ThemeColors
    ) : LinearLayout(context) {
        private val logoBox: FrameLayout
        private val logoImage: ImageView
        private val placeholder: ImageView
        private val nameText: TextView
        private val chevron: ImageView
        private var currentLogo: String = ""

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            lp.topMargin = LayoutHelper.dp(4)
            lp.bottomMargin = LayoutHelper.dp(4)
            layoutParams = lp
            background = GradientDrawable().apply {
                cornerRadius = LayoutHelper.dp(10).toFloat()
                setColor(themeColors.tertiary)
            }
            val pad = LayoutHelper.dp(12)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            foreground = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                null,
                GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(10).toFloat()
                    setColor(0xFFFFFFFF.toInt())
                }
            )

            val logoSize = LayoutHelper.dp(40)
            logoBox = FrameLayout(context).apply {
                layoutParams = LayoutParams(logoSize, logoSize)
                background = GradientDrawable().apply {
                    cornerRadius = LayoutHelper.dp(10).toFloat()
                    setColor(themeColors.surfaceVariant)
                }
                clipChildren = true
            }
            placeholder = ImageView(context).apply {
                setImageDrawable(MezonIcon.channelApp.getDrawable(context, themeColors.colorText))
                val sz = (logoSize * 0.5f).toInt()
                layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.CENTER)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            logoBox.addView(placeholder)

            logoImage = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, logoSize / 2f)
                    }
                }
                visibility = GONE
            }
            logoBox.addView(logoImage)
            addView(logoBox)

            nameText = TextView(context).apply {
                textSize = 14f
                setTextColor(themeColors.colorText)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                val lp2 = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                lp2.marginStart = LayoutHelper.dp(12)
                layoutParams = lp2
            }
            addView(nameText)

            chevron = ImageView(context).apply {
                setImageDrawable(
                    MezonIcon.chevronSmallRightIcon.getDrawable(context, themeColors.textDisabled)
                )
                val sz = LayoutHelper.dp(20)
                layoutParams = LayoutParams(sz, sz)
            }
            addView(chevron)
        }

        fun bind(item: ChannelAppUiModel) {
            nameText.text = item.appName
            if (item.appLogo == currentLogo) return
            currentLogo = item.appLogo

            placeholder.visibility = VISIBLE
            logoImage.visibility = GONE
            logoImage.setImageBitmap(null)

            if (item.appLogo.isNotBlank()) {
                val size = LayoutHelper.dp(40)
                MezonImageLoader.getInstance(context).load(
                    item.appLogo,
                    size,
                    size,
                    onSuccess = { bmp: Bitmap ->
                        if (currentLogo == item.appLogo) {
                            logoImage.setImageBitmap(bmp)
                            logoImage.visibility = VISIBLE
                            placeholder.visibility = GONE
                        }
                    }
                )
            }
        }
    }

    companion object {
        private const val ARG_CLAN_ID = "clanId"

        fun newInstance(clanId: Long): ChannelAppShowAllFragment {
            return ChannelAppShowAllFragment().apply {
                arguments = Bundle().apply { putLong(ARG_CLAN_ID, clanId) }
            }
        }
    }
}
