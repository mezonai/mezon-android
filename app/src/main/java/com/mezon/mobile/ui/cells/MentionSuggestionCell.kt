package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.core.AvatarDrawable
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.home.chat.MezonImageLoader

class MentionSuggestionCell(
    context: Context,
    private val theme: ThemeColors
) : LinearLayout(context) {

    private val avatarView: ImageView
    private val nameView: TextView
    private val usernameView: TextView
    private val avatarDrawable = AvatarDrawable()
    private val imageLoader = MezonImageLoader.getInstance(context)
    private var needsDivider = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        avatarView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        addView(avatarView, LayoutHelper.createLinear(28, 28, leftMargin = 12f, topMargin = 4f, bottomMargin = 4f))

        nameView = TextView(context).apply {
            setTextColor(theme.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(nameView, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
            gravity = Gravity.CENTER_VERTICAL, leftMargin = 12f
        ))

        usernameView = TextView(context).apply {
            setTextColor(theme.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(usernameView, LayoutHelper.createLinear(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
            gravity = Gravity.CENTER_VERTICAL, leftMargin = 8f, rightMargin = 12f
        ))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(LayoutHelper.dp(40f), MeasureSpec.EXACTLY)
        )
    }

    fun setMember(member: ClanMember) {
        val displayName = member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
        nameView.text = displayName
        usernameView.text = "@${member.username}"
        usernameView.visibility = VISIBLE
        avatarView.visibility = VISIBLE

        avatarDrawable.setInfo(member.userId, displayName)
        val avatarUrl = member.clanAvatar.ifBlank { member.avatarUrl }
        if (avatarUrl.isNotBlank()) {
            val sz = LayoutHelper.dp(28f)
            val cached = imageLoader.getBitmapFromMemory(avatarUrl, sz, sz)
            if (cached != null) {
                avatarView.setImageBitmap(cached)
            } else {
                avatarView.setImageDrawable(avatarDrawable)
                imageLoader.load(avatarUrl, sz, sz, onSuccess = { bmp ->
                    avatarView.setImageBitmap(bmp)
                })
            }
        } else {
            avatarView.setImageDrawable(avatarDrawable)
        }
    }

    fun setHereItem() {
        nameView.text = "@here"
        usernameView.text = "Notify everyone online"
        usernameView.visibility = VISIBLE
        avatarView.visibility = VISIBLE
        avatarDrawable.setInfo(0L, "@")
        avatarView.setImageDrawable(avatarDrawable)
    }

    fun setDivider(enabled: Boolean) {
        if (enabled != needsDivider) {
            needsDivider = enabled
            setWillNotDraw(!needsDivider)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (needsDivider) {
            canvas.drawLine(
                LayoutHelper.dp(52f).toFloat(), (height - 1).toFloat(),
                (width - LayoutHelper.dp(8f)).toFloat(), (height - 1).toFloat(),
                theme.dividerPaint
            )
        }
    }
}
