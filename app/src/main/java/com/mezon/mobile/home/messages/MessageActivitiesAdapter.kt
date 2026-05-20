package com.mezon.mobile.home.messages

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView

class MessageActivitiesAdapter(
    private val themeColors: ThemeColors
) : RecyclerView.Adapter<MessageActivitiesAdapter.Holder>() {

    private val items = ArrayList<MessageActivityRow>()

    fun setData(newItems: List<MessageActivityRow>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val cell = MessageActivityStripCell(parent.context, themeColors)
        val gap = LayoutHelper.dp(10)
        cell.layoutParams = RecyclerView.LayoutParams(
            LayoutHelper.dp(220),
            LayoutHelper.WRAP_CONTENT
        ).apply {
            marginEnd = gap
        }
        return Holder(cell)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.cell.bind(items[position])
    }

    class Holder(val cell: MessageActivityStripCell) : RecyclerView.ViewHolder(cell)
}

class MessageActivityStripCell(
    context: android.content.Context,
    private val themeColors: ThemeColors
) : LinearLayout(context) {

    var row: MessageActivityRow? = null
        private set

    private val avatarView: AvatarView
    private val titleView: TextView
    private val subtitleView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(LayoutHelper.dp(8), LayoutHelper.dp(6), LayoutHelper.dp(10), LayoutHelper.dp(6))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(themeColors.tertiary)
            cornerRadius = LayoutHelper.dp(12).toFloat()
        }
        avatarView = AvatarView(context).apply {
            setSizeDp(40)
            setRoundRadius(10f)
        }
        addView(avatarView, LayoutHelper.createLinear(40, 40, gravity = Gravity.CENTER_VERTICAL, rightMargin = 6f))
        val textCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f)
        }
        titleView = TextView(context).apply {
            setTextColor(themeColors.onSurface)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = TextView(context).apply {
            setTextColor(themeColors.onSurfaceVariant)
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        textCol.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        addView(textCol)
    }

    fun bind(r: MessageActivityRow) {
        row = r
        avatarView.setInfo(r.userId, r.username)
        avatarView.setImageUrl(r.avatarUrl.takeIf { it.isNotBlank() })
        titleView.text = r.displayName
        subtitleView.text = r.activityText
    }
}
