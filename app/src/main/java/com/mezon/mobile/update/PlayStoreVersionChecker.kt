package com.mezon.mobile.update

import com.mezon.mobile.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayStoreVersionChecker @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    private val storeHttpClient: OkHttpClient
        get() = okHttpClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

    fun check(playStoreUrl: String): CheckVersionResult? {
        val idPart = appIdQueryFromUrl(playStoreUrl) ?: return null
        val fetchUrl = "https://play.google.com/store/apps/$idPart"
        val body = try {
            val request = Request.Builder()
                .url(fetchUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                .build()
            val response = storeHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        } catch (_: Exception) {
            return null
        }
        val rawRemote = matchRemoteVersion(body) ?: return null
        val remote = rawRemote.replace(CLEAN_BUILD_SUFFIX, "").trim()
        val local = BuildConfig.VERSION_NAME
        return when (compareVersionKind(local, remote)) {
            VersionOrder.LOCAL_BELOW -> CheckVersionResult(
                local = local,
                remote = remote,
                needsForceUpdate = true
            )
            VersionOrder.LOCAL_ABOVE, VersionOrder.EQUAL -> CheckVersionResult(
                local = local,
                remote = remote,
                needsForceUpdate = false
            )
        }
    }

    private fun appIdQueryFromUrl(storeUrl: String): String? =
        Regex("""details\?id=[0-9a-zA-Z.]+""").find(storeUrl)?.value

    private fun matchRemoteVersion(html: String): String? {
        val m = REMOTE_VERSION_REGEX.find(html) ?: return null
        return m.groupValues.getOrNull(1)
    }

    companion object {
        private val REMOTE_VERSION_REGEX = Regex("""\[\[\[['"]((\d+\.)+\d+)['"]\]\],""")

        private val CLEAN_BUILD_SUFFIX = Regex("""\s*\([^)]*\)$""")

        internal fun compareVersionKind(local: String, remote: String): VersionOrder {
            when (cmpSemverLike(local, remote)) {
                -1 -> return VersionOrder.LOCAL_BELOW
                1 -> return VersionOrder.LOCAL_ABOVE
                else -> return VersionOrder.EQUAL
            }
        }

        private fun cmpSemverLike(a: String, b: String): Int {
            val sa = a.split('.').map { segment -> segment.filter { it.isDigit() }.toIntOrNull() ?: 0 }
            val sb = b.split('.').map { segment -> segment.filter { it.isDigit() }.toIntOrNull() ?: 0 }
            val n = maxOf(sa.size, sb.size)
            for (i in 0 until n) {
                val ca = sa.getOrElse(i) { 0 }
                val cb = sb.getOrElse(i) { 0 }
                if (ca < cb) return -1
                if (ca > cb) return 1
            }
            return 0
        }
    }
}

enum class VersionOrder {
    LOCAL_BELOW,
    LOCAL_ABOVE,
    EQUAL
}

data class CheckVersionResult(
    val local: String,
    val remote: String,
    val needsForceUpdate: Boolean
)
