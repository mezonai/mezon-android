package com.mezon.mobile.home.friends

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mezon.mezon.api.Friend
import com.mezon.mobile.R
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView

const val FRIEND_STATE_FRIEND = 0
const val FRIEND_STATE_INVITE_SENT = 1
const val FRIEND_STATE_INVITE_RECEIVED = 2
const val FRIEND_STATE_BLOCKED = 3

fun incomingFriendRequestsForUi(receivedFromApi: List<Friend>, acceptedFriends: List<Friend>): List<Friend> {
    val friendIds = acceptedFriends.map { it.user.id }.toSet()
    return receivedFromApi.filter { it.state == FRIEND_STATE_INVITE_RECEIVED && it.user.id !in friendIds }
}

fun sentFriendInvitesForUi(sentFromApi: List<Friend>): List<Friend> {
    return sentFromApi.filter { it.state == FRIEND_STATE_INVITE_SENT }
}

enum class FriendRowAction {
    CALL,
    MESSAGE,
    DELETE,
    APPROVE,
    OPEN_PROFILE
}

class FriendRelationAdapter(
    private val context: Context,
    private val themeColors: ThemeColors,
    private val onAction: (friend: Friend, action: FriendRowAction) -> Unit
) : RecyclerView.Adapter<FriendRelationAdapter.FriendHolder>() {

    private val items = ArrayList<Friend>()

    fun submitItems(newItems: List<Friend>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendHolder {
        val cell = FriendRowCell(context, themeColors, onAction)
        return FriendHolder(cell)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: FriendHolder, position: Int) {
        holder.cell.bind(items[position], position < items.lastIndex)
    }

    class FriendHolder(val cell: FriendRowCell) : RecyclerView.ViewHolder(cell)
}

class FriendRowCell(
    context: Context,
    private val themeColors: ThemeColors,
    private val onAction: (friend: Friend, action: FriendRowAction) -> Unit
) : FrameLayout(context) {
    private val avatarView: AvatarView
    private val statusDot: View
    private val displayNameText: TextView
    private val usernameText: TextView
    private val actionContainer: LinearLayout
    private val callButton: ImageView
    private val messageButton: ImageView
    private val deleteButton: ImageView
    private val approveButton: ImageView
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawDivider = false
    private var currentFriend: Friend? = null

    init {
        setWillNotDraw(false)
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        foreground = androidx.core.content.ContextCompat.getDrawable(context, outValue.resourceId)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10), LayoutHelper.dp(8), LayoutHelper.dp(10), LayoutHelper.dp(8))
            setBackgroundColor(themeColors.surfaceVariant)
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val avatarWrap = FrameLayout(context)
        row.addView(avatarWrap, LayoutHelper.createLinear(40, 40, rightMargin = 8f))

        avatarView = AvatarView(context).apply {
            setSizeDp(40)
            setRoundRadius(20f)
        }
        avatarWrap.addView(avatarView, LayoutHelper.createFrame(40, 40))

        statusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF16A34A.toInt())
            }
            visibility = View.GONE
        }
        avatarWrap.addView(statusDot, LayoutHelper.createFrame(14, 14, Gravity.END or Gravity.BOTTOM, 0f, 0f, -2f, 0f))

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(10), 0, LayoutHelper.dp(10), 0)
        }
        row.addView(textContainer, LayoutHelper.createLinear(0, 40, 1f))

        displayNameText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        textContainer.addView(displayNameText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        usernameText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textContainer.addView(usernameText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        actionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = LayoutHelper.dp(40)
        }
        row.addView(actionContainer, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40))

        callButton = createActionButton(R.drawable.ic_phone_call_icon)
        messageButton = createActionButton(R.drawable.ic_chat_icon)
        deleteButton = createPendingDeleteButton()
        approveButton = createPendingApproveButton()

        actionContainer.addView(
            callButton,
            LinearLayout.LayoutParams(LayoutHelper.dp(30), LayoutHelper.dp(30)).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = LayoutHelper.dp(10)
            }
        )
        actionContainer.addView(
            messageButton,
            LinearLayout.LayoutParams(LayoutHelper.dp(30), LayoutHelper.dp(30)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        actionContainer.addView(
            deleteButton,
            LinearLayout.LayoutParams(LayoutHelper.dp(18), LayoutHelper.dp(18)).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = LayoutHelper.dp(20)
            }
        )
        actionContainer.addView(
            approveButton,
            LinearLayout.LayoutParams(LayoutHelper.dp(28), LayoutHelper.dp(28)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        setOnClickListener { currentFriend?.let { onAction(it, FriendRowAction.OPEN_PROFILE) } }
        setOnLongClickListener {
            currentFriend?.let { onAction(it, FriendRowAction.OPEN_PROFILE) }
            true
        }

        callButton.setOnClickListener { currentFriend?.let { onAction(it, FriendRowAction.CALL) } }
        messageButton.setOnClickListener { currentFriend?.let { onAction(it, FriendRowAction.MESSAGE) } }
        deleteButton.setOnClickListener { currentFriend?.let { onAction(it, FriendRowAction.DELETE) } }
        approveButton.setOnClickListener { currentFriend?.let { onAction(it, FriendRowAction.APPROVE) } }
    }

    fun bind(friend: Friend, divider: Boolean) {
        currentFriend = friend
        drawDivider = divider
        val user = friend.user
        val state = friend.state
        val displayName = user.displayName.ifBlank { user.username }
        avatarView.setInfo(user.id, displayName)
        avatarView.setImageUrl(user.avatarUrl)

        val showPendingName = state == FRIEND_STATE_INVITE_SENT || state == FRIEND_STATE_INVITE_RECEIVED
        displayNameText.visibility = if (showPendingName && user.displayName.isNotBlank()) View.VISIBLE else View.GONE
        displayNameText.text = user.displayName
        usernameText.text = if (showPendingName && user.displayName.isNotBlank()) user.username else displayName

        val isOnline = user.online
        statusDot.visibility = if (state == FRIEND_STATE_FRIEND && isOnline) View.VISIBLE else View.GONE

        val isFriend = state == FRIEND_STATE_FRIEND
        val isPending = state == FRIEND_STATE_INVITE_SENT || state == FRIEND_STATE_INVITE_RECEIVED
        val isSent = state == FRIEND_STATE_INVITE_SENT

        callButton.visibility = if (isFriend) View.VISIBLE else View.GONE
        messageButton.visibility = if (isFriend) View.VISIBLE else View.GONE
        deleteButton.visibility = if (isPending) View.VISIBLE else View.GONE
        approveButton.visibility = if (isPending && !isSent) View.VISIBLE else View.GONE

        invalidate()
    }

    private fun createActionButton(iconRes: Int, withPrimaryBackground: Boolean = false): ImageView {
        return ImageView(context).apply {
            val iconColor = if (withPrimaryBackground) themeColors.onPrimary else themeColors.onSurface
            setImageResource(iconRes)
            colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val touchBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (withPrimaryBackground) themeColors.primary else themeColors.tertiary)
            }
            background = touchBg
            val pad = LayoutHelper.dp(6)
            setPadding(pad, pad, pad, pad)
        }
    }

    private fun createPendingDeleteButton(): ImageView {
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_close_icon)
            colorFilter = PorterDuffColorFilter(0xFFC7C7C7.toInt(), PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = null
            setPadding(0, 0, 0, 0)
        }
    }

    private fun createPendingApproveButton(): ImageView {
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_checkmark_small_icon)
            colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF16A34A.toInt())
            }
            val pad = LayoutHelper.dp(5)
            setPadding(pad, pad, pad, pad)
        }
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (!drawDivider) return
        dividerPaint.color = themeColors.border
        val leftPad = 0f
        val y = (height - 1).toFloat()
        canvas.drawRect(leftPad, y, width.toFloat(), y + 1f, dividerPaint)
    }
}
