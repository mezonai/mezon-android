package com.mezon.mobile.home.chat.channelinfo.permissions

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.clans.ClanRole
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.InputCell
import com.mezon.mobile.ui.cells.MezonIcon

class AddMemberOrRoleBottomSheet(
    context: Context,
    private val theme: ThemeColors,
    private val roles: List<ClanRole>,
    private val members: List<ClanMember>,
    private val onSubmit: (memberIds: List<Long>, roleIds: List<Long>) -> Unit,
) : BottomSheet(context, needFocusable = true) {

    private val selectedMemberIds = LinkedHashSet<Long>()
    private val selectedRoleIds = LinkedHashSet<Long>()
    private lateinit var listAdapter: PickerAdapter
    private var query = ""

    init {
        setCustomView(buildContent(context))
    }

    private fun buildContent(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LayoutHelper.dp(14f), LayoutHelper.dp(12f), LayoutHelper.dp(14f), LayoutHelper.dp(14f))
            background = GradientDrawable().apply {
                setColor(theme.channelPanelBg)
                cornerRadii = floatArrayOf(
                    LayoutHelper.dpf(18f), LayoutHelper.dpf(18f),
                    LayoutHelper.dpf(18f), LayoutHelper.dpf(18f),
                    0f, 0f,
                    0f, 0f,
                )
            }
        }

        root.addView(
            TextView(context).apply {
                text = context.getString(R.string.channel_permissions_add_members_or_roles)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.textStrong)
                gravity = Gravity.CENTER_VERTICAL
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 0f, 0f, 12f)
        )

        val input = InputCell(context, theme).apply {
            setHint(context.getString(R.string.channel_permissions_role_member_placeholder))
            onTextChanged = {
                query = it.trim().lowercase()
                rebuildRows(context)
            }
        }
        root.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        listAdapter = PickerAdapter(context)
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f))
        }
        root.addView(recyclerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 360))

        val addButton = TextView(context).apply {
            text = context.getString(R.string.channel_permissions_add)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, LayoutHelper.dp(12f), 0, LayoutHelper.dp(12f))
            background = GradientDrawable().apply {
                setColor(theme.blurple)
                cornerRadius = LayoutHelper.dpf(10f)
            }
            setOnClickListener {
                onSubmit(selectedMemberIds.toList(), selectedRoleIds.toList())
                dismiss()
            }
        }
        root.addView(addButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        rebuildRows(context)
        return root
    }

    private fun rebuildRows(context: Context) {
        val filteredRoles = roles.filter {
            query.isEmpty() || it.title.lowercase().contains(query) || it.slug.lowercase().contains(query)
        }
        val filteredMembers = members.filter {
            val display = it.displayName.ifBlank { it.username }
            query.isEmpty() || display.lowercase().contains(query) || it.username.lowercase().contains(query)
        }
        val rows = ArrayList<PickerRow>(filteredRoles.size + filteredMembers.size + 2)
        if (filteredRoles.isNotEmpty()) {
            rows.add(PickerRow.Header(context.getString(R.string.channel_permissions_roles)))
            filteredRoles.forEach { rows.add(PickerRow.RoleItem(it)) }
        }
        if (filteredMembers.isNotEmpty()) {
            rows.add(PickerRow.Header(context.getString(R.string.channel_permissions_members)))
            filteredMembers.forEach { rows.add(PickerRow.MemberItem(it)) }
        }
        if (filteredRoles.isEmpty() && filteredMembers.isEmpty()) {
            rows.add(PickerRow.Empty(context.getString(R.string.channel_permissions_no_matches)))
        }
        listAdapter.setRows(rows)
    }

    private fun baseRow(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f), LayoutHelper.dp(10f))
        background = GradientDrawable().apply {
            setColor(theme.tertiary)
            cornerRadius = LayoutHelper.dpf(10f)
        }
        isClickable = true
    }

    private fun iconCircle(context: Context, icon: MezonIcon, color: Int): View {
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        circle.addView(
            ImageView(context).apply {
                setImageDrawable(icon.getDrawable(context).apply {
                    colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                })
            },
            LayoutHelper.createFrame(18, 18, Gravity.CENTER)
        )
        return circle
    }

    private fun buildCheckbox(context: Context, checked: Boolean): View {
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = LayoutHelper.dpf(5f)
                setColor(if (checked) theme.blurple else 0x00000000)
                setStroke(LayoutHelper.dp(2f), if (checked) theme.blurple else theme.outline)
            }
        }
        if (checked) {
            frame.addView(
                ImageView(context).apply {
                    setImageDrawable(MezonIcon.checkmarkSmallIcon.getDrawable(context).apply {
                        colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                    })
                },
                LayoutHelper.createFrame(14, 14, Gravity.CENTER)
            )
        }
        return frame
    }

    private sealed class PickerRow {
        data class Header(val title: String) : PickerRow()
        data class RoleItem(val role: ClanRole) : PickerRow()
        data class MemberItem(val member: ClanMember) : PickerRow()
        data class Empty(val text: String) : PickerRow()
    }

    private inner class PickerAdapter(private val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = ArrayList<PickerRow>()

        fun setRows(nextRows: List<PickerRow>) {
            rows.clear()
            rows.addAll(nextRows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (rows[position]) {
                is PickerRow.Header -> VIEW_TYPE_HEADER
                is PickerRow.RoleItem -> VIEW_TYPE_ROLE
                is PickerRow.MemberItem -> VIEW_TYPE_MEMBER
                is PickerRow.Empty -> VIEW_TYPE_EMPTY
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_TYPE_HEADER -> HeaderHolder(createHeaderView(context))
                VIEW_TYPE_ROLE -> RoleHolder(createRoleRow(context))
                VIEW_TYPE_MEMBER -> MemberHolder(createMemberRow(context))
                else -> EmptyHolder(createEmptyView(context))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is PickerRow.Header -> (holder as HeaderHolder).bind(row.title)
                is PickerRow.RoleItem -> (holder as RoleHolder).bind(row.role)
                is PickerRow.MemberItem -> (holder as MemberHolder).bind(row.member)
                is PickerRow.Empty -> (holder as EmptyHolder).bind(row.text)
            }
        }

        override fun getItemCount(): Int = rows.size

        private fun createHeaderView(context: Context): TextView =
            TextView(context).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(theme.textStrong)
                setPadding(0, LayoutHelper.dp(10f), 0, LayoutHelper.dp(6f))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }

        private fun createRoleRow(context: Context): LinearLayout {
            val row = baseRow(context).apply {
                layoutParams = rowParams()
            }
            row.addView(iconCircle(context, MezonIcon.bravePermission, theme.blurple).apply { tag = "iconCircle" })
            row.addView(
                TextView(context).apply {
                    textSize = 15f
                    setTextColor(theme.textStrong)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    tag = "title"
                },
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12f, 0f, 8f, 0f)
            )
            row.addView(buildCheckbox(context, checked = false).apply { tag = "checkbox" }, LayoutHelper.createLinear(22, 22, 0f, Gravity.CENTER_VERTICAL))
            return row
        }

        private fun createMemberRow(context: Context): LinearLayout {
            val row = baseRow(context).apply {
                layoutParams = rowParams()
            }
            row.addView(
                AvatarView(context).apply {
                    setSizeDp(34)
                    tag = "avatar"
                },
                LayoutHelper.createLinear(34, 34, 0f, Gravity.CENTER_VERTICAL)
            )
            row.addView(
                TextView(context).apply {
                    textSize = 15f
                    setTextColor(theme.textStrong)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    tag = "title"
                },
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12f, 0f, 8f, 0f)
            )
            row.addView(buildCheckbox(context, checked = false).apply { tag = "checkbox" }, LayoutHelper.createLinear(22, 22, 0f, Gravity.CENTER_VERTICAL))
            return row
        }

        private fun createEmptyView(context: Context): TextView =
            TextView(context).apply {
                textSize = 14f
                setTextColor(theme.colorText)
                gravity = Gravity.CENTER
                setPadding(0, LayoutHelper.dp(30f), 0, LayoutHelper.dp(30f))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }

        private fun rowParams(): RecyclerView.LayoutParams =
            RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = LayoutHelper.dp(8f)
            }

        private inner class HeaderHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
            fun bind(title: String) {
                textView.text = title
            }
        }

        private inner class RoleHolder(private val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            private val title = row.findViewWithTag<TextView>("title")
            private val checkbox = row.findViewWithTag<FrameLayout>("checkbox")
            private val iconCircle = row.findViewWithTag<View>("iconCircle")

            fun bind(role: ClanRole) {
                title.text = role.title
                (iconCircle.background as? GradientDrawable)?.setColor(role.color.takeIf { it != 0 } ?: theme.blurple)
                bindCheckbox(checkbox, role.roleId in selectedRoleIds)
                row.setOnClickListener {
                    if (role.roleId in selectedRoleIds) selectedRoleIds.remove(role.roleId) else selectedRoleIds.add(role.roleId)
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
                }
            }
        }

        private inner class MemberHolder(private val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            private val avatar = row.findViewWithTag<AvatarView>("avatar")
            private val title = row.findViewWithTag<TextView>("title")
            private val checkbox = row.findViewWithTag<FrameLayout>("checkbox")

            fun bind(member: ClanMember) {
                val display = member.displayName.ifBlank { member.username }
                title.text = display
                avatar.setInfo(member.userId, member.username)
                avatar.setImageUrl(member.clanAvatar.ifBlank { member.avatarUrl })
                bindCheckbox(checkbox, member.userId in selectedMemberIds)
                row.setOnClickListener {
                    if (member.userId in selectedMemberIds) selectedMemberIds.remove(member.userId) else selectedMemberIds.add(member.userId)
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
                }
            }
        }

        private inner class EmptyHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
            fun bind(text: String) {
                textView.text = text
            }
        }
    }

    private fun bindCheckbox(frame: FrameLayout, checked: Boolean) {
        frame.removeAllViews()
        frame.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = LayoutHelper.dpf(5f)
            setColor(if (checked) theme.blurple else 0x00000000)
            setStroke(LayoutHelper.dp(2f), if (checked) theme.blurple else theme.outline)
        }
        if (checked) {
            frame.addView(
                ImageView(frame.context).apply {
                    setImageDrawable(MezonIcon.checkmarkSmallIcon.getDrawable(frame.context).apply {
                        colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                    })
                },
                LayoutHelper.createFrame(14, 14, Gravity.CENTER)
            )
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ROLE = 1
        private const val VIEW_TYPE_MEMBER = 2
        private const val VIEW_TYPE_EMPTY = 3
    }
}
