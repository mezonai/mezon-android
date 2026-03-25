package com.mezon.mobile.home.chat

import javax.inject.Inject
import javax.inject.Singleton

/** Placeholder media controller for DI wiring; extend with real logic as needed. */
@Singleton
class MediaController @Inject constructor()

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
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

    interface GalleryLoadListener {
        fun onGalleryLoaded(allPhotos: AlbumEntry, albums: List<AlbumEntry>)
    }

    private var listener: GalleryLoadListener? = null

    fun setGalleryLoadListener(l: GalleryLoadListener?) {
        listener = l
    }

    fun loadGalleryPhotos() {
        appScope.launch {
            val result = withContext(ioDispatcher) { queryMediaStore() }
            if (result != null) {
                allPhotosAlbum = result.first
                albums = result.second
                listener?.onGalleryLoaded(result.first, result.second)
            }
        }
    }

    private fun queryMediaStore(): Pair<AlbumEntry, ArrayList<AlbumEntry>>? {
        val resolver = context.contentResolver
        val allPhotos = ArrayList<AttachmentPickerItem>()
        val albumMap = LinkedHashMap<Int, AlbumEntry>()

        try {
            queryImages(resolver, allPhotos, albumMap)
            queryVideos(resolver, allPhotos, albumMap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MediaStore", e)
            return null
        }

        allPhotos.sortByDescending { it.id }

        val allPhotosAlbum = AlbumEntry(
            bucketId = 0,
            bucketName = "All Photos",
            coverPhoto = allPhotos.firstOrNull(),
            photos = allPhotos
        )

        val albumList = ArrayList(albumMap.values)
        Log.d(TAG, "Loaded ${allPhotos.size} media items, ${albumList.size} albums")
        return Pair(allPhotosAlbum, albumList)
    }

    private fun queryImages(
        resolver: ContentResolver,
        allPhotos: ArrayList<AttachmentPickerItem>,
        albumMap: LinkedHashMap<Int, AlbumEntry>
    ) {
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

        resolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(dataCol) ?: ""
                val name = cursor.getString(nameCol) ?: "image"
                val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                val width = cursor.getInt(widthCol)
                val height = cursor.getInt(heightCol)
                val size = cursor.getLong(sizeCol)
                val bucketId = cursor.getInt(bucketIdCol)
                val bucketName = cursor.getString(bucketNameCol) ?: "Unknown"

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )

                val item = AttachmentPickerItem(
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

                allPhotos.add(item)
                addToAlbum(albumMap, bucketId, bucketName, item)
            }
        }
    }

    private fun queryVideos(
        resolver: ContentResolver,
        allPhotos: ArrayList<AttachmentPickerItem>,
        albumMap: LinkedHashMap<Int, AlbumEntry>
    ) {
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

        resolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val path = cursor.getString(dataCol) ?: ""
                val name = cursor.getString(nameCol) ?: "video"
                val mime = cursor.getString(mimeCol) ?: "video/mp4"
                val width = cursor.getInt(widthCol)
                val height = cursor.getInt(heightCol)
                val size = cursor.getLong(sizeCol)
                val duration = (cursor.getLong(durationCol) / 1000).toInt()
                val bucketId = cursor.getInt(bucketIdCol)
                val bucketName = cursor.getString(bucketNameCol) ?: "Unknown"

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                )

                val item = AttachmentPickerItem(
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

                allPhotos.add(item)
                addToAlbum(albumMap, bucketId, bucketName, item)
            }
        }
    }

    private fun addToAlbum(
        albumMap: LinkedHashMap<Int, AlbumEntry>,
        bucketId: Int,
        bucketName: String,
        item: AttachmentPickerItem
    ) {
        val existing = albumMap[bucketId]
        if (existing != null) {
            existing.photos.add(item)
        } else {
            val photos = ArrayList<AttachmentPickerItem>()
            photos.add(item)
            albumMap[bucketId] = AlbumEntry(
                bucketId = bucketId,
                bucketName = bucketName,
                coverPhoto = item,
                photos = photos
            )
        }
    }
}
