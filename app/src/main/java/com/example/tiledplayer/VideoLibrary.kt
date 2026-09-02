package com.example.tiledplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.Immutable

/**
 * One video shown in the library — either a row from the device's media store
 * ([VideoSource.DEVICE], positive [id]) or one of the app's own imported copies
 * ([VideoSource.VAULT], negative [id]). The two share this type so the same
 * cards, ratings, sorting and playback flow work for both.
 */
@Immutable
data class VideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    /** How long it plays for — the trimmed length when in/out points are set. */
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
    val source: VideoSource = VideoSource.DEVICE,
    /** VAULT only: on-disk poster frame written at import time. */
    val thumbPath: String? = null,
    /** VAULT only: poster frame's mtime, so a reshuffled thumbnail invalidates caches. */
    val thumbStamp: Long = 0L,
    /** VAULT only: the file's untrimmed length. Equals [durationMs] when untrimmed. */
    val fullDurationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    /** ONLINE only: request headers the stream URL needs (see [PlaybackClip]). */
    val headers: Map<String, String> = emptyMap(),
) {
    val isTrimmed: Boolean
        get() = trimStartMs > 0L || (trimEndMs in 1 until fullDurationMs)

    /** What to hand the player: the whole file, or just the trimmed range. */
    fun toPlaybackClip(): PlaybackClip = PlaybackClip(
        uri = uri,
        startMs = trimStartMs,
        endMs = trimEndMs.takeIf { it > 0L },
        headers = headers,
    )
}

/**
 * Reads the device's video library via [MediaStore]. Requires READ_MEDIA_VIDEO
 * (API 33+) or READ_EXTERNAL_STORAGE (below); callers must check/request that
 * permission first — this just queries, it doesn't touch permissions.
 */
object VideoLibraryRepository {

    fun queryVideos(context: Context): List<VideoItem> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
        val items = mutableListOf<VideoItem>()
        context.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items += VideoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = cursor.getString(nameCol) ?: "Untitled",
                    durationMs = cursor.getLong(durationCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    dateAddedSec = cursor.getLong(dateCol),
                    width = cursor.getInt(widthCol),
                    height = cursor.getInt(heightCol),
                )
            }
        }
        return items
    }

    /** The runtime permission this SDK level needs to read the video library. */
    val requiredPermission: String =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 KB"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb >= 1024) return "%.1f GB".format(mb / 1024.0)
    // Sub-gigabyte sizes get a decimal below 100MB so the vault's storage line
    // doesn't read "0 MB" for a handful of short clips.
    return if (mb < 100) "%.1f MB".format(mb) else "%.0f MB".format(mb)
}
