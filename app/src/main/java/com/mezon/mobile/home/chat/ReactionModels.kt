package com.mezon.mobile.home.chat

// ────────────────────────────────────────────────
// 1. Reaction data models
// ────────────────────────────────────────────────

/** Thông tin 1 người đã react vào emoji */
data class SenderInfoOptionals(
    val senderId: String?,
    val count: Int?        // số lần user này react (thường là 1)
)

/** Reaction của 1 emoji trên 1 tin nhắn */
data class EmojiDataOptionals(
    val id: String?,
    val emojiId: String?,  // ID emoji (dùng để build URL ảnh)
    val emoji: String?,    // shortname, ví dụ: "thumbsup"
    val senders: List<SenderInfoOptionals>,
    val channelId: String? = null,
    val messageId: String? = null,
    val action: Boolean? = null   // true = đang remove
)

// ────────────────────────────────────────────────
// 2. Emoji model (từ server)
// ────────────────────────────────────────────────

/** Thông tin emoji từ API */
data class IEmoji(
    val id: String,
    val shortname: String? = null,
    val src: String?       = null,
    val category: String?  = null,
    val clanId: String?    = null,
    val clanName: String?  = null,
    val isForSale: Boolean? = null
)

/**
 * Build URL ảnh emoji.
 * Hỗ trợ 3 dạng ID:
 *   1. "hq_1f44d"   → cdn.mezon.vn/emojis/hq_1f44d.webp
 *   2. Long BE ID   → cdn.mezon.vn/emojis/{longId}.webp
 *   3. ":thumbsup:" → cdn.mezon.vn/emojis/hq_thumbsup.webp
 */
fun getSrcEmoji(id: String): String {
    if (id.isBlank()) return ""
    // Dạng shortname ":thumbsup:" → strip dấu : và prefix hq_
    if (id.startsWith(":") && id.endsWith(":")) {
        val name = id.trim(':')
        return "https://cdn.mezon.vn/emojis/hq_$name.webp"
    }
    return "https://cdn.mezon.vn/emojis/$id.webp"
}

// ────────────────────────────────────────────────
// 3. Payload gửi lên server khi react
// ────────────────────────────────────────────────

data class WriteMessageReactionArgs(
    val id: String,                  // reaction record ID (rỗng nếu react mới)
    val clanId: String,              // "0" nếu là DM
    val channelId: String,
    val mode: Int,                   // ChannelStreamMode: 2=channel, 4=DM, 5=group
    val messageId: String,
    val emojiId: String,             // ID emoji, "0" nếu không có
    val emoji: String,               // shortname emoji
    val count: Int,                  // số lần react cần xóa (khi actionDelete=true)
    val messageSenderId: String,     // ID người gửi tin nhắn gốc
    val actionDelete: Boolean,       // true = bỏ reaction, false = thêm reaction
    val isPublic: Boolean,
    val userId: String,              // ID người đang react
    val topicId: String = "0",
    val emojiRecentId: String = "0",
    val senderName: String? = null
)

// ────────────────────────────────────────────────
// 4. In-memory reaction aggregation model
// ────────────────────────────────────────────────

/**
 * Một nhóm emoji reaction trên tin nhắn (sau khi đã gom nhóm theo emojiId).
 * - [emojiId]   : id emoji (dùng build URL ảnh)
 * - [shortname] : shortname như "thumbsup"
 * - [count]     : tổng số react
 * - [isMine]    : user hiện tại đã react emoji này chưa
 */
/**
 * Thông tin 1 người react (dùng để hiển thị trong bottom sheet reaction detail).
 */
data class SenderReact(
    val senderId: String,
    val displayName: String,   // tên hiển thị (từ WS event hoặc fallback = senderId)
    val count: Int = 1
)

/**
 * Một nhóm emoji reaction trên tin nhắn (sau khi đã gom nhóm theo emojiId).
 * - [emojiId]   : id emoji (dùng build URL ảnh)
 * - [shortname] : shortname như "thumbsup"
 * - [count]     : tổng số react
 * - [isMine]    : user hiện tại đã react emoji này chưa
 */
data class ReactionChip(
    val emojiId: String,
    val shortname: String,
    val count: Int,
    val isMine: Boolean,
    val emojiSrc: String = ""   // URL ảnh trực tiếp nếu có, fallback sang getSrcEmoji(emojiId)
)

/**
 * Entry cho mỗi emojiId trong store.
 * senders: senderId → SenderReact
 */
data class EmojiEntry(
    val shortname: String,
    val totalCount: Int,
    val senders: Map<String, SenderReact>,
    val emojiSrc: String = ""   // URL ảnh trực tiếp (ưu tiên hơn reconstruct từ emojiId)
)

/**
 * Tổng hợp tất cả reactions của 1 tin nhắn.
 * Key = emojiId, Value = EmojiEntry
 */
data class MessageReactions(
    val byEmoji: MutableMap<String, EmojiEntry> = LinkedHashMap()  // LinkedHashMap giữ insertion order
) {
    /** Convert thành danh sách chip để render. Giữ nguyên thứ tự react đầu tiên (insertion order). */
    fun toChips(myUserId: String): List<ReactionChip> =
        byEmoji.entries
            .filter { (_, v) -> v.totalCount > 0 }
            .map { (emojiId, v) ->
                ReactionChip(
                    emojiId   = emojiId,
                    shortname = v.shortname,
                    count     = v.totalCount,
                    isMine    = v.senders.containsKey(myUserId),
                    emojiSrc  = v.emojiSrc
                )
            }

    /** Lấy danh sách người đã react emoji này (cho bottom sheet). */
    fun getSenders(emojiId: String): List<SenderReact> =
        byEmoji[emojiId]?.senders?.values?.toList() ?: emptyList()

    /** Apply một WS reaction event vào state.
     * @param isIdempotent true = WS echo (chỉ reconcile, không cộng nếu đã có)
     *                     false = optimistic từ user action (luôn thêm mới)
     */
    fun applyEvent(
        emojiId: String,
        shortname: String,
        senderId: String,
        senderName: String,
        count: Int,
        actionDelete: Boolean,
        emojiSrc: String = "",
        isIdempotent: Boolean = false  // true = WS echo, false = optimistic
    ) {
        if (actionDelete) {
            val entry = byEmoji[emojiId] ?: return
            val newSenders = entry.senders.toMutableMap().also { it.remove(senderId) }
            val newCount = (entry.totalCount - count).coerceAtLeast(0)
            if (newCount <= 0 && newSenders.isEmpty()) {
                byEmoji.remove(emojiId)
            } else {
                byEmoji[emojiId] = entry.copy(totalCount = newCount, senders = newSenders)
            }
        } else {
            val entry = byEmoji[emojiId]
            val existingSender = entry?.senders?.get(senderId)

            val newSenders = (entry?.senders?.toMutableMap() ?: mutableMapOf()).also { map ->
                map[senderId] = SenderReact(
                    senderId    = senderId,
                    displayName = senderName.ifBlank { existingSender?.displayName ?: senderId },
                    count       = if (isIdempotent && existingSender != null)
                        existingSender.count  // WS echo: giữ nguyên
                    else
                        (existingSender?.count ?: 0) + count  // Optimistic: cộng thêm
                )
            }

            // WS echo + sender đã có → giữ nguyên totalCount (không đếm 2 lần)
            val newTotal = if (isIdempotent && existingSender != null) {
                entry!!.totalCount
            } else {
                (entry?.totalCount ?: 0) + count
            }

            val resolvedSrc = emojiSrc.ifBlank { entry?.emojiSrc.orEmpty().ifBlank { getSrcEmoji(emojiId) } }
            byEmoji[emojiId] = EmojiEntry(
                shortname  = shortname,
                totalCount = newTotal,
                senders    = newSenders,
                emojiSrc   = resolvedSrc
            )
        }
    }

    fun isEmpty() = byEmoji.isEmpty()

    fun copy(): MessageReactions {
        val m = MessageReactions(byEmoji = LinkedHashMap())
        byEmoji.forEach { (k, v) ->
            m.byEmoji[k] = v.copy(senders = v.senders.toMap())
        }
        return m
    }
}

// ────────────────────────────────────────────────
// 5. Recent emoji model
// ────────────────────────────────────────────────

/** Emoji đã dùng gần đây */
data class RecentEmoji(val id: String, val shortname: String)
