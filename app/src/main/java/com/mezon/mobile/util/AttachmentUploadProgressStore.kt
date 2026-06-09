package com.mezon.mobile.util

object AttachmentUploadProgressStore {

    private val lock = Any()
    private val progressByKey = HashMap<String, Float>()
    private val uploadCompleteByKey = HashSet<String>()

    fun setProgressIfBucketChanged(key: String, value: Float): Boolean {
        if (key.isEmpty()) return false
        val clamped = value.coerceIn(0f, 1f)
        synchronized(lock) {
            val previous = progressByKey[key] ?: 0f
            progressByKey[key] = clamped
            val previousBucket = (previous * 100f).toInt()
            val newBucket = (clamped * 100f).toInt()
            return newBucket != previousBucket || clamped >= 1f
        }
    }

    fun progress(key: String): Float {
        if (key.isEmpty()) return 0f
        synchronized(lock) {
            return progressByKey[key] ?: 0f
        }
    }

    fun markUploadComplete(key: String) {
        if (key.isEmpty()) return
        synchronized(lock) {
            uploadCompleteByKey.add(key)
            progressByKey.remove(key)
        }
    }

    fun isUploadComplete(key: String): Boolean {
        if (key.isEmpty()) return false
        synchronized(lock) {
            return key in uploadCompleteByKey
        }
    }

    fun clearProgress(key: String) {
        if (key.isEmpty()) return
        synchronized(lock) {
            progressByKey.remove(key)
        }
    }

    fun clear(key: String) {
        if (key.isEmpty()) return
        synchronized(lock) {
            progressByKey.remove(key)
            uploadCompleteByKey.remove(key)
        }
    }
}
