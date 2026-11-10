package com.mezon.mobile.home.chat

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.mezon.mobile.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MediaController"
private const val PAGE_SIZE = 100
private const val GALLERY_SORT_ORDER = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC, ${MediaStore.MediaColumns._ID} DESC"

data class AlbumEntry(
    val bucketId: Long,
    val bucketName: String
)

@Singleton
class MediaController @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    suspend fun loadAlbums(): List<AlbumEntry> = withContext(ioDispatcher) {
        val albums = mutableMapOf<Long, AlbumEntry>()
        val projection = arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            queryMedia(uri, projection)?.use { cursor ->
                while (cursor.moveToNext()) {
                    ensureActive()
                    if (cursor.isNull(0)) continue
                    val bucketId = cursor.getLong(0)
                    if (bucketId in albums) continue
                    val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    albums[bucketId] = AlbumEntry(bucketId, name)
                }
            }
        }
        albums.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.bucketName })
    }

    inner class GalleryPager(private val bucketId: Long?) {
        private var imagesOffset = 0
        private var videosOffset = 0
        private var imagesExhausted = false
        private var videosExhausted = false

        val hasMore: Boolean get() = !imagesExhausted || !videosExhausted

        suspend fun loadNextPage(): List<AttachmentPickerItem> {
            val (images, videos) = withContext(ioDispatcher) {
                val images = if (imagesExhausted) emptyList() else queryImages(bucketId, imagesOffset)
                val videos = if (videosExhausted) emptyList() else queryVideos(bucketId, videosOffset)
                images to videos
            }
            imagesOffset += images.size
            videosOffset += videos.size
            imagesExhausted = images.size < PAGE_SIZE
            videosExhausted = videos.size < PAGE_SIZE
            return (images + videos).sortedByDescending { it.id }
        }
    }

    private fun queryMedia(
        uri: Uri,
        projection: Array<String>,
        bucketId: Long? = null,
        sortOrder: String? = null
    ): Cursor? {
        val selection = bucketId?.let { "${MediaStore.MediaColumns.BUCKET_ID} = ?" }
        val args = bucketId?.let { arrayOf(it.toString()) }
        return try {
            context.contentResolver.query(uri, projection, selection, args, sortOrder)
        } catch (e: SecurityException) {
            Log.w(TAG, "Media access unavailable", e)
            null
        }
    }

    private fun queryImages(bucketId: Long?, offset: Int): List<AttachmentPickerItem> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE
        )

        val result = ArrayList<AttachmentPickerItem>()

        queryMedia(uri, projection, bucketId, GALLERY_SORT_ORDER)?.use { cursor ->
            if (offset > 0 && !cursor.moveToPosition(offset - 1)) return@use

            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            var count = 0
            while (count < PAGE_SIZE && cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(dataCol) ?: ""
                val name = cursor.getString(nameCol) ?: "image"
                val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                val width = cursor.getInt(widthCol)
                val height = cursor.getInt(heightCol)
                val size = cursor.getLong(sizeCol)

                val contentUri = ContentUris.withAppendedId(uri, id)

                result.add(
                    AttachmentPickerItem(
                        id = id,
                        uri = contentUri,
                        path = path,
                        filename = name,
                        mimeType = mime,
                        width = width,
                        height = height,
                        size = size,
                        duration = 0,
                        isVideo = false
                    )
                )
                count++
            }
        }
        return result
    }

    private fun queryVideos(bucketId: Long?, offset: Int): List<AttachmentPickerItem> {
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION
        ).let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it + MediaStore.Video.Media.ORIENTATION else it }

        val result = ArrayList<AttachmentPickerItem>()

        queryMedia(uri, projection, bucketId, GALLERY_SORT_ORDER)?.use { cursor ->
            if (offset > 0 && !cursor.moveToPosition(offset - 1)) return@use

            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val orientationCol = cursor.getColumnIndex(MediaStore.Video.Media.ORIENTATION)

            var count = 0
            while (count < PAGE_SIZE && cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(dataCol) ?: ""
                val name = cursor.getString(nameCol) ?: "video"
                val mime = cursor.getString(mimeCol) ?: "video/mp4"
                val turned = orientationCol >= 0 && cursor.getInt(orientationCol) % 180 != 0
                val width = cursor.getInt(if (turned) heightCol else widthCol)
                val height = cursor.getInt(if (turned) widthCol else heightCol)
                val size = cursor.getLong(sizeCol)
                val duration = (cursor.getLong(durationCol) / 1000).toInt()

                val contentUri = ContentUris.withAppendedId(uri, id)

                result.add(
                    AttachmentPickerItem(
                        id = id,
                        uri = contentUri,
                        path = path,
                        filename = name,
                        mimeType = mime,
                        width = width,
                        height = height,
                        size = size,
                        duration = duration,
                        isVideo = true
                    )
                )
                count++
            }
        }
        return result
    }
}
