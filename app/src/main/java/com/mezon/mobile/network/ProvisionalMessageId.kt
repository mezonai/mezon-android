package com.mezon.mobile.network

fun sanitizeServerMessageId(id: Long): Long = if (id > 0L) id else 0L
