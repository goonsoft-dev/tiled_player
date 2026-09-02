package com.example.tiledplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Large (roughly 640x360) video thumbnails, cached in memory so scrolling the
 * library back and forth doesn't re-decode. Keyed by [VideoItem.id].
 */
private object ThumbnailCache {
    // Cache limit chosen for ~640x360 ARGB_8888 bitmaps (~0.9MB each): 40 slots
    // is a generous scroll buffer without risking OOM on low-end devices.
    // Keyed by id *and* poster-frame mtime so reshuffling a vault thumbnail
    // can't be masked by a stale cache hit.
    val cache = object : LruCache<String, Bitmap>(40) {}

    fun key(item: VideoItem): String = "${item.id}:${item.thumbStamp}"
}

private val THUMBNAIL_SIZE = Size(640, 360)

private fun loadThumbnailBlocking(context: Context, item: VideoItem): Bitmap? = runCatching {
    when (item.source) {
        VideoSource.VAULT -> loadVaultThumbnail(item)
        // Deliberately no thumbnail for a bookmark: generating one means
        // fetching part of the stream, and doing that for every row on every
        // library load would be a burst of network traffic just to draw a grid.
        VideoSource.ONLINE -> null
        VideoSource.DEVICE ->
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(item.uri, THUMBNAIL_SIZE, null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            }
    }
}.getOrNull()

/**
 * Vault files aren't in MediaStore, so there is no system thumbnail service to
 * ask. Import writes a poster frame next to the copy; this reads that, and
 * regenerates (and re-caches) it if it's missing — e.g. an entry imported
 * before thumbnails existed, or one whose jpg was cleared.
 */
private fun loadVaultThumbnail(item: VideoItem): Bitmap? {
    val thumb = item.thumbPath?.let { File(it) }
    if (thumb != null && thumb.isFile) {
        BitmapFactory.decodeFile(thumb.absolutePath)?.let { return it }
    }
    val video = File(item.uri.path ?: return null)
    if (!video.isFile) return null
    val generated = VaultStore.extractThumbnail(video, item.durationMs, item.width, item.height)
    if (generated != null && thumb != null) {
        runCatching {
            FileOutputStream(thumb).use { out ->
                generated.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        }
    }
    return generated
}

@Composable
fun rememberVideoThumbnail(item: VideoItem): Bitmap? {
    val context = LocalContext.current
    val cacheKey = ThumbnailCache.key(item)
    val state = produceState<Bitmap?>(
        initialValue = ThumbnailCache.cache.get(cacheKey),
        key1 = cacheKey,
    ) {
        // produceState keeps its value holder across key changes, so `value`
        // still holds the *previous* key's bitmap here. Guarding on
        // `value == null` would therefore skip the reload and show a stale
        // frame forever after a reshuffle — always consult the cache for the
        // current key instead, and only fall back to decoding.
        val cached = ThumbnailCache.cache.get(cacheKey)
        if (cached != null) {
            value = cached
        } else {
            // The old frame stays on screen while the new one decodes, which
            // avoids a spinner flash on an image that's about to be replaced.
            val loaded = withContext(Dispatchers.IO) {
                loadThumbnailBlocking(context, item)?.also { ThumbnailCache.cache.put(cacheKey, it) }
            }
            if (loaded != null) value = loaded
        }
    }
    return state.value
}

/**
 * Drops cached bitmaps for videos removed from the vault. Matches on the id
 * prefix because the cache key also carries the poster frame's timestamp.
 */
fun evictThumbnails(ids: Set<Long>) {
    val prefixes = ids.map { "$it:" }
    ThumbnailCache.cache.snapshot().keys
        .filter { key -> prefixes.any { key.startsWith(it) } }
        .forEach { ThumbnailCache.cache.remove(it) }
}
