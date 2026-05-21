package com.mezon.mobile.util

import java.util.concurrent.atomic.AtomicInteger

object MezonSnowflake {
    private const val EPOCH_MS = 0L
    private const val SHARD_ID = 1
    private val sequence = AtomicInteger(1)

    fun generate(timestampMs: Long = System.currentTimeMillis()): Long {
        val ts = timestampMs - EPOCH_MS
        val shard = SHARD_ID % 1024
        val seq = sequence.getAndIncrement() and 0xFFF
        return (ts shl 22) or (shard.toLong() shl 12) or seq.toLong()
    }
}
