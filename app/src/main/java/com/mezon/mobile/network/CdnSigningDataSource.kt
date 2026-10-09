package com.mezon.mobile.network

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource

@androidx.annotation.OptIn(UnstableApi::class)
class CdnSigningDataSource(private val upstream: DataSource) : DataSource by upstream {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = CdnSigningDataSource(upstream.createDataSource())
    }

    override fun open(dataSpec: DataSpec): Long {
        val original = dataSpec.uri.toString()
        val request = CdnSigner.requestUrlBlocking(original)
        if (!request.isSigned) return upstream.open(dataSpec)
        return try {
            upstream.open(dataSpec.withUri(Uri.parse(request.url)))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (!CdnSigner.shouldRetry(request, e.responseCode)) throw e
            val fresh = CdnSigner.freshRequestUrlBlocking(request, original) ?: throw e
            upstream.close()
            upstream.open(dataSpec.withUri(Uri.parse(fresh.url)))
        }
    }
}
