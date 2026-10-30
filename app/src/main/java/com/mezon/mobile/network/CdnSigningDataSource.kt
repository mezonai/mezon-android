package com.mezon.mobile.network

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.mezon.mobile.BuildConfig

@androidx.annotation.OptIn(UnstableApi::class)
class CdnSigningDataSource(private val upstream: DataSource) : DataSource by upstream {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = CdnSigningDataSource(upstream.createDataSource())
    }

    override fun open(dataSpec: DataSpec): Long {
        val original = dataSpec.uri.toString()
        val request = CdnSigner.requestUrlBlocking(original)
        if (!request.isSigned) return openAndLog(dataSpec)
        return try {
            openAndLog(dataSpec.withUri(Uri.parse(request.url)))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (!CdnSigner.shouldRetry(request, e.responseCode)) throw e
            val fresh = CdnSigner.freshRequestUrlBlocking(request, original) ?: throw e
            upstream.close()
            openAndLog(dataSpec.withUri(Uri.parse(fresh.url)))
        }
    }

    private fun openAndLog(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        if (BuildConfig.DEBUG) {
            val headers = upstream.responseHeaders
            fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.joinToString(",") ?: "-"
            Log.d(
                "VideoPlayerDebug",
                "source position=${dataSpec.position} requestedLength=${dataSpec.length} openedLength=$length " +
                    "contentLength=${header("Content-Length")} contentRange=${header("Content-Range")} " +
                    "acceptRanges=${header("Accept-Ranges")} encoding=${header("Content-Encoding")}"
            )
        }
        return length
    }
}
