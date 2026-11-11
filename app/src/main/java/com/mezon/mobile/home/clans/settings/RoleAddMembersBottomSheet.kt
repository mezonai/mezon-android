package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.ui.cells.SearchCell

class RoleAddMembersBottomSheet(
    context: Context,
    private val theme: ThemeColors,
    private val candidates: List<ClanMember>,
    private val onConfirm: (ids: List<Long>, onFinished: (Boolean) -> Unit) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    override val sheetBackgroundColor: Int
        get() = theme.background

    override val limitHeightToVisibleArea: Boolean
        get() = true

    private val selectedIds = LinkedHashSet<Long>()
    private var filtered: List<ClanMember> = candidates
    private lateinit var saveButton: TextView
    private lateinit var saveProgress: ProgressBar
    private var saving = false
    private val listAdapter = CandidatesAdapter()

    init {
        setCustomView(buildContent(context))
    }

    private fun buildContent(context: Context): View {
        val preferredHeight = (AndroidUtilities.displaySize.y * 0.72f).toInt()
        val root = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val height = resolveSize(preferredHeight, heightMeasureSpec)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = FrameLayout(context)
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.common_cancel)
                textSize = 14f
                setTextColor(theme.textDisabled)
                gravity = Gravity.CENTER
                setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
                setOnClickListener { if (!saving) dismiss() }
            },
            LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL)
        )
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.clan_roles_members_add)
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(theme.textStrong)
                gravity = Gravity.CENTER
            },
            LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER)
        )
        saveButton = TextView(context).apply {
            text = context.getString(R.string.clan_roles_detail_save)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.blurple)
            gravity = Gravity.CENTER
            setPadding(LayoutHelper.dp(16f), 0, LayoutHelper.dp(16f), 0)
            setOnClickListener {
                if (saving || selectedIds.isEmpty()) return@setOnClickListener
                setSaving(true)
                onConfirm(selectedIds.toList()) { success ->
                    if (success) {
                        dismiss()
                    } else {
                        setSaving(false)
                    }
                }
            }
        }
        header.addView(
            saveButton,
            LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL)
        )
        saveProgress = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(theme.blurple)
            visibility = View.GONE
        }
        header.addView(
            saveProgress,
            LayoutHelper.createFrame(20, 20, Gravity.END or Gravity.CENTER_VERTICAL, 0f, 0f, 20f, 0f)
        )
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44))

        val searchInput = SearchCell(context, theme, 10f).apply {
            setPlaceholder(context.getString(R.string.clan_roles_members_search))
            onTextChanged = { applyFilter(it) }
        }
        root.addView(
            searchInput,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 16f, 6f, 16f, 0f)
        )

        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
            itemAnimator = null
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, LayoutHelper.dp(8f), 0, LayoutHelper.dp(24f))
        }
        root.addView(recyclerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))

        refreshSave()
        return root
    }

    private fun applyFilter(raw: String) {
        val q = raw.trim().lowercase()
        filtered = if (q.isEmpty()) candidates else candidates.filter { m ->
            m.displayName.lowercase().contains(q) ||
                m.username.lowercase().contains(q) ||
                m.clanNick.lowercase().contains(q)
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun setSaving(value: Boolean) {
        saving = value
        saveButton.visibility = if (value) View.INVISIBLE else View.VISIBLE
        saveProgress.visibility = if (value) View.VISIBLE else View.GONE
        setCanDismissWithSwipe(!value)
        setCanDismissWithTouchOutside(!value)
        setCancelable(!value)
    }

    private fun refreshSave() {
        val enabled = selectedIds.isNotEmpty()
        saveButton.isEnabled = enabled
        saveButton.alpha = if (enabled) 1f else 0.4f
    }

    private inner class CandidatesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = if (filtered.isEmpty()) 1 else filtered.size

        override fun getItemViewType(position: Int): Int = if (filtered.isEmpty()) VIEW_TYPE_EMPTY else VIEW_TYPE_MEMBER

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == VIEW_TYPE_EMPTY) {
                val empty = TextView(parent.context).apply {
                    text = parent.context.getString(R.string.clan_roles_members_none)
                    textSize = 13f
                    setTextColor(theme.textDisabled)
                    gravity = Gravity.CENTER
                    setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(30f), LayoutHelper.dp(16f), LayoutHelper.dp(30f))
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                return object : RecyclerView.ViewHolder(empty) {}
            }
            val cell = RoleMemberRowCell(parent.context, theme).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            val holder = object : RecyclerView.ViewHolder(cell) {}
            cell.setOnClickListener {
                if (saving) return@setOnClickListener
                val pos = holder.bindingAdapterPosition
                val member = filtered.getOrNull(pos) ?: return@setOnClickListener
                val checked = if (selectedIds.remove(member.userId)) {
                    false
                } else {
                    selectedIds.add(member.userId)
                    true
                }
                cell.setAccessory(RoleMemberRowCell.Accessory.Checkbox(checked))
                refreshSave()
            }
            return holder
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val cell = holder.itemView as? RoleMemberRowCell ?: return
            val member = filtered[position]
            cell.bind(member, RoleMemberRowCell.Accessory.Checkbox(member.userId in selectedIds))
        }
    }

    companion object {
        private const val VIEW_TYPE_MEMBER = 0
        private const val VIEW_TYPE_EMPTY = 1
    }
}
