package com.mezon.mobile.home.clans

import android.content.Context
import android.graphics.Canvas
import com.mezon.mobile.ui.cells.MezonIcon

internal class ChannelEventIcon(context: Context) {
    private val drawable = MezonIcon.channelEventCalendarIcon.getDrawable(context)
    private var status = ClanEventStatus.CREATED

    fun setStatus(status: Int?): Boolean {
        val normalized = when (status) {
            ClanEventStatus.UPCOMING, ClanEventStatus.ONGOING -> status
            else -> ClanEventStatus.CREATED
        }
        if (this.status == normalized) return false
        this.status = normalized
        return true
    }

    fun drawIfVisible(
        canvas: Canvas,
        left: Int,
        top: Int,
        size: Int,
        muted: Boolean,
    ): Boolean {
        if (status != ClanEventStatus.UPCOMING && status != ClanEventStatus.ONGOING) {
            return false
        }
        drawable.state = if (status == ClanEventStatus.ONGOING) {
            ONGOING_STATE
        } else {
            UPCOMING_STATE
        }
        drawable.alpha = if (muted) MUTED_ALPHA else 255
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
        return true
    }

    private companion object {
        private val UPCOMING_STATE = intArrayOf(android.R.attr.state_selected)
        private val ONGOING_STATE = intArrayOf(android.R.attr.state_activated)
        private const val MUTED_ALPHA = 153
    }
}
