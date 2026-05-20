package com.mezon.mobile.home.clans

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mezon.mobile.R
import com.mezon.mobile.core.AndroidUtilities
import com.mezon.mobile.core.BottomSheet
import com.mezon.mobile.core.LayoutHelper
import com.mezon.mobile.core.ThemeColors
import com.mezon.mobile.ui.cells.AvatarView
import com.mezon.mobile.ui.cells.MezonIcon

class CategoryMenuBottomSheet(
    context: android.content.Context,
    private val clanId: Long,
    private val clanName: String,
    private val clanLogoUrl: String,
    private val categoryId: Long,
    private val canManageChannel: Boolean,
    private val onCreateChannel: () -> Unit
) : BottomSheet(context) {

    private val themeColors = ThemeColors.instance

    init {
        containerHeight = (AndroidUtilities.displaySize.y * 0.45f).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, LayoutHelper.dp(30))
        }

        val avatarWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutHelper.dp(60), LayoutHelper.dp(60))
        }
        val avatarView = AvatarView(context).apply {
            setSizeDp(60)
            setRoundRadius(10f)
            setInfo(clanId, clanName)
            setImageUrl(clanLogoUrl.ifBlank { "" })
        }
        avatarWrap.addView(avatarView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val title = TextView(context).apply {
            text = clanName.ifBlank { "…" }
            setTextColor(themeColors.textStrong)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        header.addView(avatarWrap, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
            marginEnd = LayoutHelper.dp(15)
        })
        header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        val interactiveCreate = canManageChannel && categoryId != 0L
        val rowLabelColor =
            if (interactiveCreate) themeColors.textStrong else themeColors.onSurfaceVariant

        val createRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14), LayoutHelper.dp(14))
            background = if (interactiveCreate) {
                android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(themeColors.onSurface and 0x1AFFFFFF),
                    android.graphics.drawable.ColorDrawable(themeColors.surfaceVariant),
                    android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt())
                )
            } else {
                android.graphics.drawable.ColorDrawable(themeColors.surfaceVariant)
            }
            isClickable = interactiveCreate
            isFocusable = interactiveCreate
            if (interactiveCreate) {
                setOnClickListener {
                    dismiss()
                    onCreateChannel()
                }
            }
        }

        val plusIcon = ImageView(context).apply {
            setImageDrawable(MezonIcon.plusLargeIcon.getDrawable(context, rowLabelColor))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        createRow.addView(plusIcon, LayoutHelper.createLinear(20, 20).apply { rightMargin = LayoutHelper.dp(12) })

        val createLabel = TextView(context).apply {
            text = context.getString(R.string.category_menu_create_channel)
            setTextColor(rowLabelColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }
        createRow.addView(createLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        createRow.contentDescription = if (interactiveCreate) {
            context.getString(R.string.category_menu_create_channel)
        } else {
            "${context.getString(R.string.category_menu_create_channel)}. " +
                context.getString(R.string.category_menu_create_channel_unavailable)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColors.background)
            setPadding(LayoutHelper.dp(20), LayoutHelper.dp(4), LayoutHelper.dp(20), LayoutHelper.dp(20))
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(createRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT).apply {
                topMargin = LayoutHelper.dp(8)
            })
        }

        if (!canManageChannel) {
            createRow.visibility = View.GONE
        }

        setCustomView(root)
        super.onCreate(savedInstanceState)
    }
}
