package com.mezon.mobile.network

const val LOCAL_PROVISIONAL_MESSAGE_ID_FLOOR: Long = 4_611_686_018_427_387_904L

fun isLocalProvisionalMessageId(id: Long): Boolean = id >= LOCAL_PROVISIONAL_MESSAGE_ID_FLOOR

fun sanitizeServerMessageId(id: Long): Long =
    if (isLocalProvisionalMessageId(id)) 0L else id
