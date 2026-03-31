package com.mezon.mobile.home

import com.mezon.mezon.api.ChannelDescription

private const val TS_MASK = 0xFFFF_FFFFL

fun ChannelDescription.extractLastSentMessageId(): Long =
    if (hasLastSentMessage() && lastSentMessage.id != 0L) lastSentMessage.id else 0L

fun ChannelDescription.extractLastSeenMessageId(): Long =
    if (hasLastSeenMessage() && lastSeenMessage.id != 0L) lastSeenMessage.id else 0L

fun ChannelDescription.extractLastSentMessageTs(fallbackToCreateTime: Boolean = true): Long {
    val fromSent = if (hasLastSentMessage()) {
        lastSentMessage.timestampSeconds.toLong() and TS_MASK
    } else {
        0L
    }
    if (fromSent > 0L) return fromSent
    return if (fallbackToCreateTime) createTimeSeconds.toLong() and TS_MASK else 0L
}

fun ChannelDescription.extractLastSeenMessageTs(): Long =
    if (hasLastSeenMessage()) lastSeenMessage.timestampSeconds.toLong() and TS_MASK else 0L
