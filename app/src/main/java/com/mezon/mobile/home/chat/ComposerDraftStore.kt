package com.mezon.mobile.home.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import com.mezon.mobile.util.HashtagData
import com.mezon.mobile.util.MentionData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

data class ComposerDraft(
    val rawText: String,
    val mentions: List<MentionData>,
    val hashtags: List<HashtagData>,
    val emojis: Map<String, String>
)

class ComposerDraftStore private constructor(private val directory: File) {

    private class StoredDraft(val draft: ComposerDraft, val updatedAt: Long)

    private val drafts = LinkedHashMap<String, StoredDraft>()
    private var owner = 0L
    private var loaded = false
    private var dirty = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "composer-drafts").apply { priority = Thread.MIN_PRIORITY }
    }
    private val writeRunnable = Runnable { writeNow() }

    fun get(ownerId: Long, key: String): ComposerDraft? {
        if (!prepare(ownerId)) return null
        return drafts[key]?.draft
    }

    fun put(ownerId: Long, key: String, draft: ComposerDraft?) {
        if (!prepare(ownerId)) return
        if (draft == null) {
            if (drafts.remove(key) == null) return
        } else {
            if (drafts[key]?.draft == draft) return
            drafts.remove(key)
            drafts[key] = StoredDraft(draft, System.currentTimeMillis())
            while (drafts.size > MAX_DRAFTS) {
                drafts.remove(drafts.keys.first())
            }
        }
        dirty = true
        mainHandler.removeCallbacks(writeRunnable)
        mainHandler.postDelayed(writeRunnable, WRITE_DELAY_MS)
    }

    fun flush() {
        mainHandler.removeCallbacks(writeRunnable)
        writeNow()
    }

    private fun prepare(ownerId: Long): Boolean {
        if (ownerId == 0L) return false
        if (loaded && ownerId == owner) return true
        if (dirty) flush()
        owner = ownerId
        drafts.clear()
        dirty = false
        val file = fileFor(ownerId)
        val stored = try {
            parse(String(AtomicFile(file).readFully(), Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Discarding unreadable composer drafts", e)
            emptyList()
        }
        stored.sortedBy { it.second.updatedAt }.forEach { (key, value) -> drafts[key] = value }
        loaded = true
        return true
    }

    private fun writeNow() {
        if (!dirty || !loaded) return
        dirty = false
        val file = fileFor(owner)
        val snapshot = drafts.entries.map { it.key to it.value }
        io.execute {
            val atomicFile = AtomicFile(file)
            var stream: java.io.FileOutputStream? = null
            try {
                file.parentFile?.mkdirs()
                stream = atomicFile.startWrite()
                stream.write(serialize(snapshot).toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (e: Exception) {
                stream?.let { atomicFile.failWrite(it) }
                Log.w(TAG, "Failed to write composer drafts", e)
            }
        }
    }

    private fun fileFor(ownerId: Long): File = File(directory, "$ownerId.json")

    private fun serialize(entries: List<Pair<String, StoredDraft>>): String {
        val array = JSONArray()
        for ((key, stored) in entries) {
            val draft = stored.draft
            val mentions = JSONArray()
            for (m in draft.mentions) {
                mentions.put(
                    JSONObject()
                        .put("u", m.userId)
                        .put("r", m.roleId)
                        .put("d", m.display)
                        .put("s", m.startOffset)
                        .put("e", m.endOffset)
                )
            }
            val hashtags = JSONArray()
            for (h in draft.hashtags) {
                hashtags.put(
                    JSONObject()
                        .put("c", h.channelId)
                        .put("k", h.clanId)
                        .put("s", h.startOffset)
                        .put("e", h.endOffset)
                )
            }
            val emojis = JSONObject()
            for ((shortname, id) in draft.emojis) emojis.put(shortname, id)
            array.put(
                JSONObject()
                    .put("key", key)
                    .put("t", draft.rawText)
                    .put("m", mentions)
                    .put("h", hashtags)
                    .put("ej", emojis)
                    .put("ts", stored.updatedAt)
            )
        }
        return JSONObject().put("v", FORMAT_VERSION).put("drafts", array).toString()
    }

    private fun parse(json: String): List<Pair<String, StoredDraft>> {
        val array = JSONObject(json).optJSONArray("drafts") ?: return emptyList()
        val result = ArrayList<Pair<String, StoredDraft>>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val key = obj.optString("key")
            val rawText = obj.optString("t")
            if (key.isEmpty() || rawText.isBlank()) continue
            val mentions = ArrayList<MentionData>()
            obj.optJSONArray("m")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val m = arr.optJSONObject(j) ?: continue
                    mentions += MentionData(
                        userId = m.optString("u"),
                        roleId = m.optString("r"),
                        display = m.optString("d"),
                        startOffset = m.optInt("s", -1),
                        endOffset = m.optInt("e", -1)
                    )
                }
            }
            val hashtags = ArrayList<HashtagData>()
            obj.optJSONArray("h")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val h = arr.optJSONObject(j) ?: continue
                    hashtags += HashtagData(
                        channelId = h.optString("c"),
                        startOffset = h.optInt("s", -1),
                        endOffset = h.optInt("e", -1),
                        clanId = h.optString("k")
                    )
                }
            }
            val emojis = HashMap<String, String>()
            obj.optJSONObject("ej")?.let { map ->
                for (shortname in map.keys()) emojis[shortname] = map.optString(shortname)
            }
            result += key to StoredDraft(
                ComposerDraft(rawText, mentions, hashtags, emojis),
                obj.optLong("ts", 0L)
            )
        }
        return result
    }

    companion object {
        private const val TAG = "ComposerDraftStore"
        private const val MAX_DRAFTS = 200
        private const val WRITE_DELAY_MS = 400L
        private const val FORMAT_VERSION = 1

        @Volatile
        private var instance: ComposerDraftStore? = null

        fun getInstance(context: Context): ComposerDraftStore =
            instance ?: synchronized(this) {
                instance ?: ComposerDraftStore(
                    File(context.applicationContext.filesDir, "composer_drafts")
                ).also { instance = it }
            }
    }
}
