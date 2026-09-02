package com.example.tiledplayer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val VAULT_DIR = "vault"
private const val VIDEO_SUBDIR = "videos"
private const val THUMB_SUBDIR = "thumbs"
private const val INDEX_FILE = "index.json"
private const val INDEX_TMP_FILE = "index.json.tmp"

/** Suffix for an in-progress copy; anything left with it is a dead import. */
const val PART_SUFFIX = ".part"

/** Refuse to import if it would leave the device with less than this free. */
private const val FREE_SPACE_HEADROOM_BYTES = 256L * 1024 * 1024

/**
 * Where a [VideoItem] came from: the device's media library, our own vault, or
 * a bookmarked URL that streams straight off the network.
 */
enum class VideoSource { DEVICE, VAULT, ONLINE }

/**
 * One video that has been copied into the app's private storage.
 *
 * [id] is always negative so it can share the ratings/selection maps with
 * MediaStore ids (which are always positive) without ever colliding.
 * [sourceKey] remembers where the copy came from, purely so the device list can
 * mark it as "already in the player" — the copy itself never depends on it.
 */
data class VaultVideo(
    val id: Long,
    val fileName: String,
    val displayName: String,
    /** The file's full length, independent of any trim. */
    val durationMs: Long,
    val sizeBytes: Long,
    val importedAtSec: Long,
    val width: Int,
    val height: Int,
    val sourceKey: String,
    /** In-point. Playback starts here and the segment planner divides from here. */
    val trimStartMs: Long = 0L,
    /** Out-point; 0 or >= [durationMs] means "to the end". */
    val trimEndMs: Long = 0L,
) {
    val hasTrim: Boolean
        get() = trimStartMs > 0L || (trimEndMs in 1 until durationMs)

    /** End of the trim range, resolved against the real duration. */
    val effectiveEndMs: Long
        get() = if (trimEndMs in 1..durationMs) trimEndMs else durationMs

    /** How long this plays for once the trim is applied. */
    val playingDurationMs: Long
        get() = (effectiveEndMs - trimStartMs).coerceAtLeast(0L)
}

/**
 * The app's private copy of every imported video, kept in internal storage
 * (`filesDir/vault/`).
 *
 * This exists because some OEM launchers' hidden-app drawers can't reach
 * files in the device's secure/private folder, and because a video that has
 * merely been *hidden* in the gallery is no longer readable through
 * MediaStore or through a previously granted SAF permission. Holding our own
 * copy means playback keeps working after the original is hidden, moved into
 * that folder, or deleted outright.
 *
 * Properties that matter for that use:
 *  - internal storage is private to this app and is not media-scanned, so the
 *    copies never reappear in the gallery;
 *  - files are named `<id>.<ext>`, so the on-disk names give nothing away;
 *  - the index is rewritten atomically, and entries whose file has vanished are
 *    dropped on load, so a half-finished import can't leave a broken row.
 */
object VaultStore {

    private val lock = Any()

    @Volatile
    private var cache: List<VaultVideo>? = null

    @Volatile
    private var nextId: Long = 1L

    fun vaultDir(context: Context): File = File(context.filesDir, VAULT_DIR)

    fun videosDir(context: Context): File =
        File(vaultDir(context), VIDEO_SUBDIR).apply { mkdirs() }

    private fun thumbsDir(context: Context): File =
        File(vaultDir(context), THUMB_SUBDIR).apply { mkdirs() }

    private fun indexFile(context: Context): File = File(vaultDir(context), INDEX_FILE)

    fun videoFile(context: Context, entry: VaultVideo): File =
        File(videosDir(context), entry.fileName)

    fun thumbFile(context: Context, id: Long): File =
        File(thumbsDir(context), "${-id}.jpg")

    /** Every imported video, newest first. Cheap after the first call. */
    fun load(context: Context): List<VaultVideo> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val loaded = readIndex(context)
            // Drop entries whose file is gone (manual wipe, storage cleaner,
            // restore-from-backup weirdness) rather than showing dead rows.
            val alive = loaded.filter { videoFile(context, it).isFile }
            if (alive.size != loaded.size) writeIndex(context, alive)
            cache = alive
            return alive
        }
    }

    /** The vault as library items, ready for the same UI the device list uses. */
    fun items(context: Context): List<VideoItem> = load(context).map { toVideoItem(context, it) }

    fun totalBytes(context: Context): Long = load(context).sumOf { it.sizeBytes }

    /** Source keys of everything already imported, for the "in player" badge. */
    fun importedSourceKeys(context: Context): Set<String> =
        load(context).flatMap { listOf(it.sourceKey, identityKey(it.displayName, it.sizeBytes)) }
            .toSet()

    fun toVideoItem(context: Context, entry: VaultVideo): VideoItem {
        val thumb = thumbFile(context, entry.id)
        return VideoItem(
            id = entry.id,
            uri = Uri.fromFile(videoFile(context, entry)),
            displayName = entry.displayName,
            // The list shows how long it *plays* for, which is the trimmed
            // length when in/out points are set.
            durationMs = entry.playingDurationMs,
            fullDurationMs = entry.durationMs,
            sizeBytes = entry.sizeBytes,
            dateAddedSec = entry.importedAtSec,
            width = entry.width,
            height = entry.height,
            source = VideoSource.VAULT,
            thumbPath = thumb.absolutePath,
            // Part of the thumbnail cache key, so shuffling the poster frame
            // shows up immediately instead of hitting a stale cached bitmap.
            thumbStamp = thumb.lastModified(),
            trimStartMs = entry.trimStartMs,
            trimEndMs = entry.trimEndMs,
        )
    }

    /**
     * Registers a finished copy. The caller has already written the bytes to
     * [file] (a `.part` scratch file); this probes it, renames it to its final
     * id-based name, writes the thumbnail, and commits the index entry.
     */
    fun commit(
        context: Context,
        file: File,
        displayName: String,
        extension: String,
        sourceKey: String,
    ): VaultVideo = synchronized(lock) {
        val existing = load(context)          // also restores nextId from the index
        val meta = probe(file)
        val id = -(nextId++)
        val ext = extension.ifEmpty { "mp4" }
        val finalFile = File(videosDir(context), "${-id}.$ext")
        if (file != finalFile) {
            if (!file.renameTo(finalFile)) {
                file.copyTo(finalFile, overwrite = true)
                file.delete()
            }
        }
        val entry = VaultVideo(
            id = id,
            fileName = finalFile.name,
            displayName = displayName,
            durationMs = meta.durationMs,
            sizeBytes = finalFile.length(),
            importedAtSec = System.currentTimeMillis() / 1000,
            width = meta.width,
            height = meta.height,
            sourceKey = sourceKey,
        )
        runCatching {
            extractThumbnail(finalFile, meta.durationMs, meta.width, meta.height)?.let { bmp ->
                FileOutputStream(thumbFile(context, id)).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bmp.recycle()
            }
        }
        val updated = listOf(entry) + existing
        writeIndex(context, updated)
        cache = updated
        entry
    }

    /** Renames one entry. Only the label changes; the file on disk keeps its id name. */
    fun rename(context: Context, id: Long, newName: String) = synchronized(lock) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val updated = load(context).map { if (it.id == id) it.copy(displayName = trimmed) else it }
        writeIndex(context, updated)
        cache = updated
    }

    /**
     * Sets (or clears, with `0..0`) an entry's in/out points. Non-destructive:
     * the file is untouched, so trimming costs nothing and can be undone.
     */
    fun setTrim(context: Context, id: Long, startMs: Long, endMs: Long) = synchronized(lock) {
        val updated = load(context).map { entry ->
            if (entry.id != id) return@map entry
            val start = startMs.coerceIn(0L, entry.durationMs)
            val end = if (endMs <= 0L) 0L else endMs.coerceIn(start, entry.durationMs)
            entry.copy(trimStartMs = start, trimEndMs = if (end >= entry.durationMs) 0L else end)
        }
        writeIndex(context, updated)
        cache = updated
    }

    /**
     * Replaces an entry's poster frame with one from a different, randomly
     * chosen moment — for when the frame picked at import time happened to be
     * a black frame or otherwise unhelpful. Stays inside the trim range, since
     * that's the part the user actually plays. Returns false if no frame could
     * be decoded, leaving the existing thumbnail alone.
     */
    fun reshuffleThumbnail(context: Context, id: Long): Boolean {
        val entry = load(context).firstOrNull { it.id == id } ?: return false
        val file = videoFile(context, entry)
        if (!file.isFile) return false

        val from = entry.trimStartMs
        val to = entry.effectiveEndMs.coerceAtLeast(from + 1)
        // Avoid the very edges: the first and last frames are the most likely
        // to be a fade or a black frame.
        val span = (to - from).coerceAtLeast(1L)
        val at = from + (span * 0.05 + Math.random() * span * 0.9).toLong()

        val bitmap = extractThumbnail(
            file, entry.durationMs, entry.width, entry.height, atMs = at, exact = true,
        ) ?: return false
        return runCatching {
            FileOutputStream(thumbFile(context, id)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            true
        }.getOrDefault(false)
    }

    /** Deletes the copies (and their thumbnails) for [ids]. */
    fun remove(context: Context, ids: Set<Long>) = synchronized(lock) {
        val current = load(context)
        val (removed, kept) = current.partition { it.id in ids }
        removed.forEach { entry ->
            runCatching { videoFile(context, entry).delete() }
            runCatching { thumbFile(context, entry.id).delete() }
        }
        writeIndex(context, kept)
        cache = kept
    }

    /**
     * Deletes leftovers from imports that were killed mid-copy (process death,
     * an OEM's background cleanup). Safe to call on every launch: a `.part`
     * file is never referenced by the index, so nothing playable can be lost.
     */
    fun sweepPartials(context: Context) {
        runCatching {
            videosDir(context).listFiles()?.forEach { f ->
                if (f.name.endsWith(PART_SUFFIX)) f.delete()
            }
        }
    }

    /** True if [bytes] can be copied in without running the device dry. */
    fun hasRoomFor(context: Context, bytes: Long): Boolean = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBytes > bytes + FREE_SPACE_HEADROOM_BYTES
    }.getOrDefault(true)

    fun freeBytes(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /**
     * A weak identity for a file, used only to notice that a gallery video has
     * already been imported even though its content uri changed (the gallery
     * reassigns ids when a file is moved, re-indexed, or restored).
     */
    fun identityKey(displayName: String, sizeBytes: Long): String = "$displayName|$sizeBytes"

    // --- index io ---------------------------------------------------------

    private fun readIndex(context: Context): List<VaultVideo> {
        val file = indexFile(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            nextId = root.optLong("nextId", 1L).coerceAtLeast(1L)
            val arr = root.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                VaultVideo(
                    id = o.optLong("id"),
                    fileName = o.optString("file"),
                    displayName = o.optString("name", "Untitled"),
                    durationMs = o.optLong("duration"),
                    sizeBytes = o.optLong("size"),
                    importedAtSec = o.optLong("added"),
                    width = o.optInt("w"),
                    height = o.optInt("h"),
                    sourceKey = o.optString("src"),
                    trimStartMs = o.optLong("trimStart"),
                    trimEndMs = o.optLong("trimEnd"),
                )
            }.filter { it.id < 0 && it.fileName.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, items: List<VaultVideo>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("file", e.fileName)
                    .put("name", e.displayName)
                    .put("duration", e.durationMs)
                    .put("size", e.sizeBytes)
                    .put("added", e.importedAtSec)
                    .put("w", e.width)
                    .put("h", e.height)
                    .put("src", e.sourceKey)
                    .put("trimStart", e.trimStartMs)
                    .put("trimEnd", e.trimEndMs)
            )
        }
        // Keep nextId monotonic even after deletions, so a stale thumbnail file
        // can never be picked up by a later import that reused the id.
        val highest = items.minOfOrNull { it.id }?.let { -it } ?: 0L
        nextId = maxOf(nextId, highest + 1)
        val root = JSONObject().put("nextId", nextId).put("items", arr)
        runCatching {
            val tmp = File(vaultDir(context).apply { mkdirs() }, INDEX_TMP_FILE)
            tmp.writeText(root.toString())
            if (!tmp.renameTo(indexFile(context))) {
                indexFile(context).writeText(root.toString())
                tmp.delete()
            }
        }
    }

    // --- media probing ----------------------------------------------------

    private data class Meta(val durationMs: Long, val width: Int, val height: Int)

    /** Broad catch: a malformed file must degrade to "unknown", never throw. */
    private fun probe(file: File): Meta {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            Meta(
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0,
                height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0,
            )
        } catch (t: Throwable) {
            Meta(0L, 0, 0)
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * A poster frame, taken a little way in so it isn't a black fade-in.
     * Written to disk at import time because vault files aren't in MediaStore,
     * so there is no system thumbnail to fall back on while scrolling.
     */
    fun extractThumbnail(
        file: File,
        durationMs: Long,
        width: Int,
        height: Int,
        atMs: Long? = null,
        exact: Boolean = false,
    ): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val defaultMs = if (durationMs > 0) (durationMs / 10).coerceAtMost(3_000L) else 1_000L
            val atUs = (atMs ?: defaultMs) * 1000L
            val dstW = 640
            val dstH = if (width > 0 && height > 0) (dstW.toLong() * height / width).toInt().coerceAtLeast(1) else 360
            // CLOSEST_SYNC only ever lands on a keyframe, and a typical encode
            // has one every several seconds — fine for the import-time poster
            // frame, useless for shuffling (random positions keep snapping to
            // the same handful of frames). [exact] decodes forward to the real
            // frame instead: slower, but it actually changes the picture.
            val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST
            else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(atUs, option, dstW, dstH)
                    ?: r.getFrameAtTime(atUs, option)
            } else {
                r.getFrameAtTime(atUs, option)
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

/** Reads a content uri's display name and size (both may be missing). */
fun queryNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
    var name: String? = null
    var size = -1L
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameCol >= 0 && !c.isNull(nameCol)) name = c.getString(nameCol)
                if (sizeCol >= 0 && !c.isNull(sizeCol)) size = c.getLong(sizeCol)
            }
        }
    }
    val resolved = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Video"
    return resolved to size
}

/**
 * The best filename we can recover for [uri].
 *
 * The system photo picker deliberately hands back an id-based name ("86.mp4")
 * rather than the real filename. Two fallbacks, in order:
 *
 * 1. A picker uri's last path segment *is* the MediaStore row id, so if the
 *    app happens to hold media permission we can look the real name up
 *    directly. This is the common case once the user has visited the device
 *    tab, and it keeps "clip1.mp4" as "clip1.mp4".
 * 2. Otherwise build something readable from the capture date, so the user
 *    gets "Video 13 Aug 2026, 17:33" rather than "86.mp4".
 *
 * Videos picked through SAF or the device tab always report their real name
 * and skip all of this. Either way the result can be renamed afterwards.
 */
fun humanizeName(context: Context, uri: Uri, rawName: String): String {
    val stem = rawName.substringBeforeLast('.', rawName)
    if (stem.isNotEmpty() && !stem.all { it.isDigit() }) return rawName

    resolveMediaStoreName(context, uri)?.let { return it }

    val takenMs = queryLong(context, uri, MediaStore.MediaColumns.DATE_TAKEN)
    val stamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        .format(Date(takenMs ?: System.currentTimeMillis()))
    return "Video $stamp"
}

/**
 * Looks the real display name up in the media library, treating [uri]'s
 * trailing path segment as a MediaStore row id. Returns null when that isn't a
 * row id, when the app has no media permission (the expected case for a
 * permission-free import), or when the name is no better than what we had.
 */
private fun resolveMediaStoreName(context: Context, uri: Uri): String? {
    val rowId = uri.lastPathSegment?.toLongOrNull() ?: return null
    return runCatching {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
            "${MediaStore.Video.Media._ID} = ?",
            arrayOf(rowId.toString()),
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            // Guard against the library reporting an id-based name too.
            val nameStem = name.substringBeforeLast('.', name)
            if (nameStem.isNotEmpty() && nameStem.all { it.isDigit() }) null else name
        }
    }.getOrNull()
}

private fun queryLong(context: Context, uri: Uri, column: String): Long? = runCatching {
    context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
        val col = c.getColumnIndex(column)
        if (c.moveToFirst() && col >= 0 && !c.isNull(col)) c.getLong(col) else null
    }
}.getOrNull()

/** File extension for the copy: from the name if it has one, else the MIME type. */
fun extensionFor(context: Context, uri: Uri, displayName: String): String {
    val fromName = displayName.substringAfterLast('.', "")
    if (fromName.isNotEmpty() && fromName.length <= 5) return fromName.lowercase()
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    return mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "mp4"
}
