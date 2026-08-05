package com.mezon.mobile.util

import org.json.JSONArray
import org.json.JSONObject

object PresignFinishContent {
    const val FIELD_KEY = "presign_finish"
    const val CREATE_TIME_KEY = "create_time_seconds"
    const val FOR_10_MINUTES_SEC = 10 * 60
    const val PRESIGN_PENDING_TICK_MS = 30_000L

    class PresignFilterContext private constructor(
        val hasPresignField: Boolean,
        private val finishedKeys: List<String>?,
        private val messageTimestampSeconds: Long,
        val nowSeconds: Long,
    ) {
        companion object {
            fun from(
                content: String,
                messageTimestampSeconds: Long,
                nowSeconds: Long = System.currentTimeMillis() / 1000L,
            ): PresignFilterContext {
                if (!content.contains("\"$FIELD_KEY\"")) {
                    return PresignFilterContext(false, null, messageTimestampSeconds, nowSeconds)
                }
                val keys = parseKeys(content)
                return PresignFilterContext(
                    hasPresignField = keys != null,
                    finishedKeys = keys,
                    messageTimestampSeconds = messageTimestampSeconds,
                    nowSeconds = nowSeconds,
                )
            }
        }

        fun contentFingerprint(): Int = finishedKeys?.joinToString(",")?.hashCode() ?: 0

        private val expirePending: Boolean
            get() = hasPresignField && messageTimestampSeconds > 0L &&
                nowSeconds - messageTimestampSeconds >= FOR_10_MINUTES_SEC

        fun isUnfinished(url: String): Boolean {
            if (url.isBlank()) return false
            if (url.startsWith("content://") || url.startsWith("file://")) return false
            if (!isMezonCdnUrl(url)) return false
            val keys = finishedKeys ?: return false
            val key = presignKey(url)
            return key.isNotEmpty() && !keys.contains(key)
        }

        fun isExpired(url: String): Boolean {
            if (!expirePending) return false
            return isUnfinished(url)
        }

        fun <T> filterDisplayable(attachments: List<T>, urlSelector: (T) -> String): List<T> {
            if (!expirePending) return attachments
            return attachments.filter { !isExpired(urlSelector(it)) }
        }

        /**
         * A sender that dies before its final flush leaves the last keys unsent, so
         * matching by name alone would keep those attachments hidden until they are
         * dropped. Once as many keys have arrived as there are presignable
         * attachments, everything is considered uploaded - same rule as web/desktop.
         */
        fun <T> allFinished(attachments: List<T>, urlSelector: (T) -> String): Boolean {
            val keys = finishedKeys ?: return false
            val presignable = attachments.count { isMezonCdnUrl(urlSelector(it)) }
            return presignable > 0 && keys.size >= presignable
        }

        fun <T> hasDisplayable(attachments: List<T>, urlSelector: (T) -> String): Boolean {
            if (attachments.isEmpty()) return false
            if (!expirePending) return true
            for (item in attachments) {
                if (!isExpired(urlSelector(item))) return true
            }
            return false
        }

        fun <T> hasActivePending(attachments: List<T>, urlSelector: (T) -> String): Boolean {
            if (!hasPresignField) return false
            for (item in attachments) {
                val url = urlSelector(item)
                if (isUnfinished(url) && !isExpired(url)) return true
            }
            return false
        }
    }

    fun parseKeys(content: String): List<String>? {
        if (content.isBlank()) return null
        if (!content.contains("\"$FIELD_KEY\"")) return null
        return try {
            val json = JSONObject(content)
            if (!json.has(FIELD_KEY)) return null
            val arr = json.optJSONArray(FIELD_KEY) ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Only attachments living on a mezon CDN are uploaded through a presigned URL,
     * so only those can be waiting for a `presign_finish` key. Anything else - a
     * Tenor GIF, an image someone linked from the web - is already reachable and
     * must never be hidden (nor dropped once the message passes its expiry).
     */
    fun isMezonCdnUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("https://cdn.mezon") ||
            trimmed.startsWith("https://cdn.komu") ||
            trimmed.startsWith("http://cdn.mezon") ||
            trimmed.startsWith("http://cdn.komu")
    }

    fun presignKey(cdnUrl: String): String {
        val trimmed = cdnUrl.trim()
        if (trimmed.isEmpty()) return ""
        val noQuery = trimmed.substringBefore('?')
        val last = noQuery.substringAfterLast('/')
        if (last.isEmpty()) return ""
        val dot = last.lastIndexOf('.')
        val withoutExt = if (dot > 0) last.substring(0, dot) else last
        return withoutExt.ifEmpty { last }
    }

    fun isAttachmentReady(url: String, presignFinish: List<String>?): Boolean {
        val keys = presignFinish ?: return true
        val key = presignKey(url)
        if (key.isEmpty()) return false
        return keys.contains(key)
    }

    fun injectPresignFinish(content: String, keys: List<String>): String {
        return try {
            val json = if (content.isNotBlank()) JSONObject(content) else JSONObject()
            val arr = JSONArray()
            keys.forEach { arr.put(it) }
            json.put(FIELD_KEY, arr)
            json.toString()
        } catch (_: Exception) {
            content
        }
    }

    fun withCreateTimeSeconds(content: String, createTimeSeconds: Int): String {
        if (createTimeSeconds <= 0) return content
        return try {
            val json = if (content.isNotBlank()) JSONObject(content) else JSONObject()
            json.put(CREATE_TIME_KEY, createTimeSeconds)
            json.toString()
        } catch (_: Exception) {
            content
        }
    }

    fun emptyOutgoingContent(): String = "{}"

    fun isEmptyOutgoingContent(content: String): Boolean {
        if (content.isBlank()) return true
        return try {
            val json = JSONObject(content)
            when {
                json.length() == 0 -> true
                json.length() == 1 && json.optString("t", "").trim().isEmpty() -> true
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun presignSyncOriginContent(wireContent: String): String {
        val base = contentBaseWithoutPresign(wireContent)
        return if (isEmptyOutgoingContent(base)) emptyOutgoingContent() else base
    }

    fun injectEmptyPresignFinish(content: String): String = injectPresignFinish(content, emptyList())

    fun hasPresignFinishField(content: String): Boolean {
        if (content.isBlank()) return false
        if (!content.contains("\"$FIELD_KEY\"")) return false
        return try {
            JSONObject(content).has(FIELD_KEY)
        } catch (_: Exception) {
            false
        }
    }

    fun contentBaseWithoutPresign(content: String): String {
        if (content.isBlank()) return content
        return try {
            val json = JSONObject(content)
            json.remove(FIELD_KEY)
            json.toString()
        } catch (_: Exception) {
            content
        }
    }

    fun mergePresignFinishContent(local: String, server: String): String {
        val localKeys = parseKeys(local) ?: emptyList()
        val serverKeys = parseKeys(server) ?: emptyList()
        if (localKeys.isEmpty() && serverKeys.isEmpty()) {
            return server.ifBlank { local }
        }
        val mergedKeys = if (serverKeys.size >= localKeys.size) {
            serverKeys
        } else {
            val keys = localKeys.toMutableList()
            serverKeys.forEach { key ->
                if (!keys.contains(key)) keys.add(key)
            }
            keys
        }
        val base = if (server.isNotBlank()) {
            contentBaseWithoutPresign(server)
        } else {
            contentBaseWithoutPresign(local)
        }
        return injectPresignFinish(base, mergedKeys)
    }

    fun isPresignFinishOnlyChange(newContent: String, oldContent: String): Boolean {
        if (newContent.isBlank() || oldContent.isBlank()) return false
        if (!hasPresignFinishField(newContent)) return false
        return try {
            val newJson = JSONObject(newContent)
            val oldJson = JSONObject(oldContent)
            newJson.remove(FIELD_KEY)
            oldJson.remove(FIELD_KEY)
            newJson.toString() == oldJson.toString()
        } catch (_: Exception) {
            false
        }
    }
}
