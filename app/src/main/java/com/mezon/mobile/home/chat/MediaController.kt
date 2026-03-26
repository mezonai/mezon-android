package com.mezon.mobile.home.chat

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.mezon.mobile.di.ApplicationScope
import com.mezon.mobile.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MediaController"
private const val PAGE_SIZE = 100

data class AlbumEntry(
    val bucketId: Int,
    val bucketName: String,
    val coverPhoto: AttachmentPickerItem?,
    val photos: ArrayList<AttachmentPickerItem>
)

@Singleton
class MediaController @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    @Volatile
    var allPhotosAlbum: AlbumEntry? = null
        private set

    @Volatile
    var albums: ArrayList<AlbumEntry> = ArrayList()
        private set

    private val allPhotos = ArrayList<AttachmentPickerItem>()
    private var imagesOffset = 0
    private var videosOffset = 0
    private var imagesExhausted = false
    private var videosExhausted = false
    private var isLoading = false

    val hasMore: Boolean get() = !imagesExhausted || !videosExhausted

    interface GalleryLoadListener {
        fun onGalleryLoaded(photos: List<AttachmentPickerItem>, totalLoaded: Int, hasMore: Boolean)
    }

    private var listener: GalleryLoadListener? = null

    fun setGalleryLoadListener(l: GalleryLoadListener?) {
        listener = l
    }

    fun loadGalleryPhotos() {
        synchronized(this) {
            allPhotos.clear()
            imagesOffset = 0
            videosOffset = 0
            imagesExhausted = false
            videosExhausted = false
        }
        loadNextPage()
    }

    fun loadMorePhotos() {
        if (!hasMore || isLoading) return
        loadNextPage()
    }

    private fun loadNextPage() {
        if (isLoading) return
        isLoading = true

        appScope.launch {
            val page = withContext(ioDispatcher) { queryNextPage() }
            isLoading = false

            synchronized(this@MediaController) {
                allPhotos.addAll(page)
                allPhotosAlbum = AlbumEntry(
                    bucketId = 0,
                    bucketName = "All Photos",
                    coverPhoto = allPhotos.firstOrNull(),
                    photos = allPhotos
                )
            }

            listener?.onGalleryLoaded(
                ArrayList(allPhotos),
                allPhotos.size,
                hasMore
            )
        }
    }

    private fun queryNextPage(): List<AttachmentPickerItem> {
        val resolver = context.contentResolver
        val page = ArrayList<AttachmentPickerItem>()

        if (!imagesExhausted) {
            val images = queryImages(resolver, PAGE_SIZE, imagesOffset)
            page.addAll(images)
            imagesOffset += images.size
            if (images.size < PAGE_SIZE) imagesExhausted = true
        }

        if (!videosExhausted) {
            val videos = queryVideos(resolver, PAGE_SIZE, videosOffset)
            page.addAll(videos)
            videosOffset += videos.size
            if (videos.size < PAGE_SIZE) videosExhausted = true
        }

        page.sortByDescending { it.id }
        return page
    }

    private fun queryImages(
        resolver: ContentResolver,
        limit: Int,
        offset: Int
    ): List<AttachmentPickerItem> {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        val result = ArrayList<AttachmentPickerItem>()

        resolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            if (offset > 0 && !cursor.moveToPosition(offset - 1)) return@use

            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(dataCol) ?: ""
                val name = cursor.getString(nameCol) ?: "image"
                val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                val width = cursor.getInt(widthCol)
                val height = cursor.getInt(heightCol)
                val size = cursor.getLong(sizeCol)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )

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

    private fun queryVideos(
        resolver: ContentResolver,
        limit: Int,
        offset: Int
    ): List<AttachmentPickerItem> {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        val result = ArrayList<AttachmentPickerItem>()

        resolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            if (offset > 0 && !cursor.moveToPosition(offset - 1)) return@use

            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(dataCol) ?: ""
                val name = cursor.getString(nameCol) ?: "video"
                val mime = cursor.getString(mimeCol) ?: "video/mp4"
                val width = cursor.getInt(widthCol)
                val height = cursor.getInt(heightCol)
                val size = cursor.getLong(sizeCol)
                val duration = (cursor.getLong(durationCol) / 1000).toInt()

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                )

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
