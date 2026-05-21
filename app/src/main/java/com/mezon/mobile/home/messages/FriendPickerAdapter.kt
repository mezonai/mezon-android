package com.mezon.mobile.home.messages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mezon.api.Friend
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView
import java.util.Locale

class FriendPickerAdapter(
    private val context: Context,
    private val themeColors: ThemeColors,
    private val selectMode: Boolean,
    private val maxMembers: Int = GROUP_CHAT_MAX_MEMBERS,
    private val onFriendClick: (Friend) -> Unit,
    private val onSelectionChanged: (List<Long>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = ArrayList<Row>()
    private val selectedIds = LinkedHashSet<Long>()
    private var defaultSelectedIds = emptySet<Long>()
    private var searchListMode = false

    init {
        setHasStableIds(true)
    }

    fun setDefaultSelected(ids: Set<Long>) {
        defaultSelectedIds = ids
        selectedIds.clear()
        selectedIds.addAll(ids)
        notifyDataSetChanged()
        onSelectionChanged(ArrayList(selectedIds))
    }

    fun getSelectedIds(): List<Long> = ArrayList(selectedIds)

    fun submitFriends(friends: List<Friend>, searching: Boolean) {
        val nextRows = buildRows(friends, searching)
        if (searching != searchListMode) {
            searchListMode = searching
            rows.clear()
            rows.addAll(nextRows)
            notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(RowDiffCallback(rows, nextRows))
        rows.clear()
        rows.addAll(nextRows)
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long {
        val row = rows.getOrNull(position) ?: return RecyclerView.NO_ID
        return when (row) {
            is Row.Header -> -1_000_000L - row.title.hashCode().toLong()
            is Row.FriendRow -> row.friend.user.id
        }
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> VIEW_TYPE_HEADER
        is Row.FriendRow -> VIEW_TYPE_FRIEND
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderHolder(HeaderRowView(parent.context, themeColors))
        } else {
            FriendHolder(this, FriendPickerCell(parent.context, themeColors))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION) && holder is FriendHolder) {
            bindFriendRow(holder, position)
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).view.bind(row.title, row.uppercase, row.separated)
            is Row.FriendRow -> bindFriendRow(holder as FriendHolder, position)
        }
    }

    internal fun onFriendRowClicked(position: Int) {
        val row = rows.getOrNull(position) as? Row.FriendRow ?: return
        handleFriendPressed(row.friend)
    }

    private fun bindFriendRow(holder: FriendHolder, position: Int) {
        val row = rows[position] as Row.FriendRow
        val userId = row.friend.user.id
        val selected = selectedIds.contains(userId)
        val disabled = isFriendDisabled(userId, selected)
        holder.cell.bind(
            friend = row.friend,
            checked = selected,
            disabled = disabled,
            selectMode = selectMode,
            divider = row.divider
        )
    }

    private fun isFriendDisabled(userId: Long, selected: Boolean): Boolean =
        defaultSelectedIds.contains(userId) || (!selected && selectedIds.size >= maxMembers)

    private fun handleFriendPressed(friend: Friend) {
        if (!selectMode) {
            onFriendClick(friend)
            return
        }
        val userId = friend.user.id
        if (isFriendDisabled(userId, selectedIds.contains(userId))) return
        if (selectedIds.contains(userId)) {
            selectedIds.remove(userId)
        } else {
            selectedIds.add(userId)
        }
        refreshSelectionUi()
        onSelectionChanged(ArrayList(selectedIds))
    }

    private fun refreshSelectionUi() {
        for (i in rows.indices) {
            if (rows[i] is Row.FriendRow) {
                notifyItemChanged(i, PAYLOAD_SELECTION)
            }
        }
    }

    private fun buildRows(friends: List<Friend>, searching: Boolean): List<Row> {
        if (friends.isEmpty()) return emptyList()
        val result = ArrayList<Row>(friends.size + 12)
        if (searching) {
            result.add(Row.Header(context.getString(com.mezon.mobile.R.string.dm_friends), uppercase = false))
            friends.forEachIndexed { index, friend ->
                result.add(Row.FriendRow(friend, divider = index < friends.lastIndex))
            }
            return result
        }
        val groups = friends.groupBy { friend ->
            val name = friend.user.displayName.ifBlank { friend.user.username }.trim()
            name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        }.toSortedMap()
        groups.entries.forEachIndexed { groupIndex, entry ->
            val letter = entry.key
            val group = entry.value
            result.add(Row.Header(letter, uppercase = true, separated = groupIndex > 0))
            group.forEachIndexed { index, friend ->
                result.add(Row.FriendRow(friend, divider = index < group.lastIndex))
            }
        }
        return result
    }

    private sealed class Row {
        data class Header(val title: String, val uppercase: Boolean, val separated: Boolean = false) : Row()
        data class FriendRow(val friend: Friend, val divider: Boolean) : Row()
    }

    private class HeaderHolder(val view: HeaderRowView) : RecyclerView.ViewHolder(view)

    private class FriendHolder(
        private val adapter: FriendPickerAdapter,
        val cell: FriendPickerCell
    ) : RecyclerView.ViewHolder(cell) {
        init {
            cell.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.onFriendRowClicked(pos)
            }
        }
    }

    private class RowDiffCallback(
        private val oldRows: List<Row>,
        private val newRows: List<Row>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldRows.size
        override fun getNewListSize() = newRows.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldRows[oldItemPosition]
            val new = newRows[newItemPosition]
            return when {
                old is Row.Header && new is Row.Header -> old.title == new.title
                old is Row.FriendRow && new is Row.FriendRow -> old.friend.user.id == new.friend.user.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val old = oldRows[oldItemPosition]
            val new = newRows[newItemPosition]
            if (old is Row.Header && new is Row.Header) return old == new
            if (old is Row.FriendRow && new is Row.FriendRow) {
                val o = old.friend
                val n = new.friend
                return old.divider == new.divider &&
                    o.state == n.state &&
                    o.user.id == n.user.id &&
                    o.user.username == n.user.username &&
                    o.user.displayName == n.user.displayName &&
                    o.user.avatarUrl == n.user.avatarUrl &&
                    o.user.online == n.user.online
            }
            return false
        }
    }

    companion object {
        const val GROUP_CHAT_MAX_MEMBERS = 20
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_FRIEND = 1
        private const val PAYLOAD_SELECTION = "selection"
    }
}

private class HeaderRowView(context: Context, private val themeColors: ThemeColors) : TextView(context) {
    init {
        setTextColor(themeColors.colorText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        includeFontPadding = false
    }

    fun bind(title: String, uppercase: Boolean, separated: Boolean) {
        text = if (uppercase) title.uppercase(Locale.getDefault()) else title
        setTextColor(themeColors.colorText)
        val topPadding = when {
            !uppercase -> PAD_TOP_SEARCH
            separated -> PAD_TOP_SEPARATED
            else -> PAD_TOP_LETTER
        }
        val bottomPadding = if (uppercase) PAD_BOTTOM_LETTER else PAD_BOTTOM_SEARCH
        setPadding(0, topPadding, 0, bottomPadding)
    }

    companion object {
        private val PAD_TOP_SEARCH = LayoutHelper.dp(18)
        private val PAD_TOP_SEPARATED = LayoutHelper.dp(14)
        private val PAD_TOP_LETTER = LayoutHelper.dp(6)
        private val PAD_BOTTOM_LETTER = LayoutHelper.dp(6)
        private val PAD_BOTTOM_SEARCH = LayoutHelper.dp(18)
    }
}

private class FriendPickerCell(
    context: Context,
    private val themeColors: ThemeColors
) : FrameLayout(context) {

    private val avatarView: AvatarView
    private val fallbackAvatar: TextView
    private val titleView: TextView
    private val subtitleView: TextView
    private val checkView: SquareCheckView
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawDivider = false

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        if (outValue.resourceId != 0) {
            foreground = ContextCompat.getDrawable(context, outValue.resourceId)
        }
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(8), LayoutHelper.dp(10), LayoutHelper.dp(8))
            background = GradientDrawable().apply { setColor(themeColors.secondaryInputBackground) }
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutHelper.dp(56)))

        val avatarWrap = FrameLayout(context)
        row.addView(avatarWrap, LayoutHelper.createLinear(40, 40, rightMargin = 8f))

        fallbackAvatar = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColors.colorAvatarDefault)
            }
            visibility = View.GONE
        }
        avatarWrap.addView(fallbackAvatar, LayoutHelper.createFrame(40, 40))

        avatarView = AvatarView(context).apply {
            setSizeDp(40)
            setRoundRadius(20f)
        }
        avatarWrap.addView(avatarView, LayoutHelper.createFrame(40, 40))

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10), 0, LayoutHelper.dp(8), 0)
        }
        row.addView(textContainer, LayoutHelper.createLinear(0, 40, 1f))

        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.textStrong)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textContainer.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        subtitleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.textStrong)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textContainer.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, topMargin = 3f))

        checkView = SquareCheckView(context, themeColors).apply {
            visibility = View.GONE
        }
        row.addView(checkView, LayoutHelper.createLinear(24, 24))
    }

    fun bind(
        friend: Friend,
        checked: Boolean,
        disabled: Boolean,
        selectMode: Boolean,
        divider: Boolean
    ) {
        val user = friend.user
        val displayName = user.displayName.ifBlank { user.username }
        if (user.avatarUrl.isBlank()) {
            avatarView.visibility = View.GONE
            avatarView.setImageUrl(null)
            fallbackAvatar.visibility = View.VISIBLE
            fallbackAvatar.text = user.username.firstOrNull()?.uppercaseChar()?.toString()
                ?: displayName.firstOrNull()?.uppercaseChar()?.toString()
                ?: ""
        } else {
            fallbackAvatar.visibility = View.GONE
            avatarView.visibility = View.VISIBLE
            avatarView.setInfo(user.id, displayName)
            avatarView.setImageUrl(user.avatarUrl)
        }
        titleView.text = displayName
        subtitleView.text = user.username.takeIf { it.isNotBlank() && it != displayName } ?: ""
        subtitleView.visibility = if (subtitleView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        checkView.visibility = if (selectMode) View.VISIBLE else View.GONE
        checkView.setState(checked, disabled)
        alpha = if (disabled && !checked) 0.45f else 1f
        drawDivider = divider
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!drawDivider) return
        dividerPaint.color = themeColors.tertiary
        val y = height - LayoutHelper.dpf(1.5f)
        canvas.drawRect(0f, y, width.toFloat(), height.toFloat(), dividerPaint)
    }
}

private class SquareCheckView(
    context: Context,
    private val themeColors: ThemeColors
) : View(context) {

    private val rect = RectF()
    private val checkPath = Path()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(1.5f)
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LayoutHelper.dpf(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private var checked = false
    private var disabled = false

    fun setState(checked: Boolean, disabled: Boolean) {
        this.checked = checked
        this.disabled = disabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = LayoutHelper.dpf(2f)
        rect.set(pad, pad, width - pad, height - pad)
        val radius = LayoutHelper.dpf(5f)
        if (checked) {
            bgPaint.color = themeColors.blurple
            bgPaint.alpha = if (disabled) 150 else 255
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
        }
        borderPaint.color = if (checked) themeColors.blurple else themeColors.outline
        borderPaint.alpha = if (disabled) 120 else 255
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
        if (checked) {
            checkPath.reset()
            checkPath.moveTo(width * 0.28f, height * 0.52f)
            checkPath.lineTo(width * 0.43f, height * 0.67f)
            checkPath.lineTo(width * 0.73f, height * 0.34f)
            canvas.drawPath(checkPath, checkPaint)
        }
        bgPaint.alpha = 255
        borderPaint.alpha = 255
    }
}
