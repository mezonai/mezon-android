package com.mezon.mobile.home.profile

import android.graphics.Matrix
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.mezon.mobile.R
import com.mezon.mobile.ui.cells.MezonIcon

enum class UserOnlineStatus(
    val value: String,
    @DrawableRes private val iconRes: Int,
    private val glyphWidth: Float,
    private val tint: Int
) {
    ONLINE("Online", R.drawable.mz_status_online_16, 9f, 0xFF16A34A.toInt()),
    IDLE("Idle", R.drawable.mz_moon_16, 13.5f, 0xFFF0B232.toInt()),
    DO_NOT_DISTURB("Do Not Disturb", R.drawable.mz_minus_circle_16, 14f, 0xFFF23F43.toInt()),
    INVISIBLE("Invisible", R.drawable.mz_status_offline_16, 9f, 0xFFAEAEAE.toInt());

    override fun toString(): String = value

    fun isEnlarged(): Boolean = this == IDLE || this == DO_NOT_DISTURB

    internal fun bindIcon(view: ImageView, slotSize: Int, visibleSize: Int) {
        val drawable = MezonIcon.loadDrawable(view.context, iconRes, tint)

        // The Web glyphs have different padding inside the same 16-unit viewport.
        val scale = (visibleSize * 16f / glyphWidth) / drawable.intrinsicWidth
        view.scaleType = ImageView.ScaleType.MATRIX
        view.setImageDrawable(drawable)
        view.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (slotSize - drawable.intrinsicWidth * scale) / 2f,
                (slotSize - drawable.intrinsicHeight * scale) / 2f
            )
            if (this@UserOnlineStatus == IDLE) {
                postRotate(-90f, slotSize / 2f, slotSize / 2f)
            }
        }
    }

    companion object {
        fun fromString(status: String): UserOnlineStatus {
            return entries.firstOrNull {
                it.value.equals(status, ignoreCase = true)
            } ?: ONLINE
        }
    }
}
