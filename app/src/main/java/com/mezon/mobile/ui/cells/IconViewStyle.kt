package com.mezon.mobile.ui.cells

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.mezon.mobile.core.ThemeColors

internal fun Drawable.setIconColor(@ColorInt color: Int) {
    colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
}

internal fun Drawable.drawCenteredIcon(canvas: Canvas, cx: Int, cy: Int) {
    val left = cx - intrinsicWidth / 2
    val top = cy - intrinsicHeight / 2
    MezonIcon.drawIcon(canvas, this, left, top, left + intrinsicWidth, top + intrinsicHeight)
}

internal fun Context.isPackagedIcon(@DrawableRes resId: Int): Boolean =
    resId != 0 && resources.getResourceEntryName(resId).startsWith("mz_")

internal fun Drawable.withDefaultIconTint(
    context: Context,
    @DrawableRes resId: Int,
    @ColorInt color: Int,
): Drawable = apply {
    if (context.isPackagedIcon(resId)) setIconColor(color)
}

internal fun ImageView.setMezonIconResource(@DrawableRes resId: Int, theme: ThemeColors? = null) {
    if (context.isPackagedIcon(resId)) {
        scaleType = ImageView.ScaleType.CENTER
        val tint = theme?.iconTint ?: ThemeColors.defaultIconTint
        setImageDrawable(MezonIcon.loadDrawable(context, resId, tint))
    } else {
        setImageResource(resId)
    }
}
