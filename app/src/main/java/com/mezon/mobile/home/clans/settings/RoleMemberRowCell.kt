package com.mezon.mobile.home.clans.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.home.ClanMember
import com.mezon.mobile.ui.cells.AvatarView

class RoleCircleGlyphView(context: Context) : View(context) {

    enum class Glyph { PLUS, MINUS, CHECKED, UNCHECKED }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var glyph = Glyph.UNCHECKED
    private var circleColor = 0
    private var glyphColor = 0xFFFFFFFF.toInt()

    fun setGlyph(glyph: Glyph, circleColor: Int, glyphColor: Int = 0xFFFFFFFF.toInt()) {
        if (this.glyph == glyph && this.circleColor == circleColor && this.glyphColor == glyphColor) return
        this.glyph = glyph
        this.circleColor = circleColor
        this.glyphColor = glyphColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val lineWidth = size * 0.1f
        if (glyph == Glyph.UNCHECKED) {
            strokePaint.color = circleColor
            strokePaint.strokeWidth = size * 0.08f
            canvas.drawCircle(cx, cy, size / 2f - strokePaint.strokeWidth / 2f, strokePaint)
            return
        }
        fillPaint.color = circleColor
        canvas.drawCircle(cx, cy, size / 2f, fillPaint)
        strokePaint.color = glyphColor
        strokePaint.strokeWidth = lineWidth
        val arm = size * 0.24f
        when (glyph) {
            Glyph.PLUS -> {
                canvas.drawLine(cx - arm, cy, cx + arm, cy, strokePaint)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, strokePaint)
            }
            Glyph.MINUS -> canvas.drawLine(cx - arm, cy, cx + arm, cy, strokePaint)
            Glyph.CHECKED -> {
                canvas.drawLine(cx - size * 0.22f, cy + size * 0.02f, cx - size * 0.06f, cy + size * 0.18f, strokePaint)
                canvas.drawLine(cx - size * 0.06f, cy + size * 0.18f, cx + size * 0.24f, cy - size * 0.16f, strokePaint)
            }
            Glyph.UNCHECKED -> Unit
        }
    }
}

class RoleMemberRowCell(context: Context, private val theme: ThemeColors) : LinearLayout(context) {

    sealed class Accessory {
        object None : Accessory()
        object Remove : Accessory()
        object Loading : Accessory()
        data class Checkbox(val checked: Boolean) : Accessory()
    }

    private val avatarView = AvatarView(context).apply { setSizeDp(AVATAR_SIZE_DP) }
    private val nameView = TextView(context).apply {
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(theme.textStrong)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val usernameView = TextView(context).apply {
        textSize = 12f
        setTextColor(theme.textDisabled)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val accessoryGlyph = RoleCircleGlyphView(context)
    private val accessoryProgress = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(theme.textDisabled)
        visibility = View.GONE
    }
    private val accessoryContainer = FrameLayout(context)

    var onAccessoryClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = LayoutHelper.dp(56f)
        setPadding(LayoutHelper.dp(16f), LayoutHelper.dp(8f), LayoutHelper.dp(6f), LayoutHelper.dp(8f))

        addView(avatarView, LayoutHelper.createLinear(AVATAR_SIZE_DP, AVATAR_SIZE_DP, 0f, Gravity.CENTER_VERTICAL))

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textColumn.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        textColumn.addView(
            usernameView,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.NO_GRAVITY, 0f, 2f, 0f, 0f)
        )
        addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12f, 0f, 8f, 0f))

        accessoryContainer.addView(accessoryGlyph, LayoutHelper.createFrame(22, 22, Gravity.CENTER))
        accessoryContainer.addView(accessoryProgress, LayoutHelper.createFrame(20, 20, Gravity.CENTER))
        addView(accessoryContainer, LayoutHelper.createLinear(42, 42, 0f, Gravity.CENTER_VERTICAL))
    }

    fun bind(member: ClanMember, accessory: Accessory) {
        val name = member.clanNick.ifBlank { member.displayName.ifBlank { member.username } }
        nameView.text = name
        usernameView.text = member.username
        usernameView.visibility = if (member.username.isBlank()) View.GONE else View.VISIBLE
        avatarView.setUserId(member.userId)
        avatarView.setImageUrl(member.clanAvatar.ifBlank { member.avatarUrl })
        setAccessory(accessory)
    }

    fun setAccessory(accessory: Accessory) {
        val loading = accessory == Accessory.Loading
        accessoryGlyph.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        accessoryProgress.visibility = if (loading) View.VISIBLE else View.GONE
        when (accessory) {
            Accessory.None -> {
                accessoryContainer.visibility = View.INVISIBLE
                accessoryContainer.setOnClickListener(null)
                accessoryContainer.isClickable = false
            }
            Accessory.Loading -> {
                accessoryContainer.visibility = View.VISIBLE
                accessoryContainer.setOnClickListener(null)
                accessoryContainer.isClickable = false
            }
            Accessory.Remove -> {
                accessoryContainer.visibility = View.VISIBLE
                accessoryGlyph.setGlyph(RoleCircleGlyphView.Glyph.MINUS, REMOVE_RED)
                accessoryContainer.setOnClickListener { onAccessoryClick?.invoke() }
            }
            is Accessory.Checkbox -> {
                accessoryContainer.visibility = View.VISIBLE
                if (accessory.checked) {
                    accessoryGlyph.setGlyph(RoleCircleGlyphView.Glyph.CHECKED, theme.blurple)
                } else {
                    accessoryGlyph.setGlyph(RoleCircleGlyphView.Glyph.UNCHECKED, theme.textDisabled)
                }
                accessoryContainer.setOnClickListener(null)
                accessoryContainer.isClickable = false
            }
        }
    }

    companion object {
        private const val AVATAR_SIZE_DP = 36
        private const val REMOVE_RED = 0xFFFF3B30.toInt()
    }
}
