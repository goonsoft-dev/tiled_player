package com.example.tiledplayer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val BOOKMARKS_FILE = "bookmarks.json"
private const val BOOKMARKS_TMP_FILE = "bookmarks.json.tmp"

/**
 * Ids for bookmarks live below this, far under any plausible vault id, so the
 * three sources (device = positive, vault = small negative, online = very
 * negative) never collide in the ratings/selection maps.
 */
private const val BOOKMARK_ID_BASE = -1_000_000_000L

/** A video that plays straight off the network — nothing is stored locally. */
data class Bookmark(
    val id: Long,
    val url: String,
    val title: String,
    val addedAtSec: Long,
    /** The headers this URL was seen with; without them many CDNs 403. */
    val headers: Map<String, String>,
    /** The page it came from, kept so the browser can be reopened there. */
    val pageUrl: String?,
)

/**
 * Bookmarked stream URLs, stored in the same private directory as the vault.
 *
 * The point of these is to not have to download first: a bookmark is a
 * reference plus the headers needed to fetch it, so a video can be watched (and
 * tiled) without ever landing on the device. They're deliberately kept apart
 * from [VaultStore] because they have the opposite durability guarantee — a
 * bookmark breaks the moment the site rotates its URLs, which is exactly why
 * "Download" exists next to it.
 */
object RemoteLibrary {

    private val lock = Any()

    @Volatile
    private var cache: List<Bookmark>? = null

    @Volatile
    private var nextIndex: Long = 1L

    private fun file(context: Context): File =
        File(VaultStore.vaultDir(context).apply { mkdirs() }, BOOKMARKS_FILE)

    fun load(context: Context): List<Bookmark> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val loaded = read(context)
            cache = loaded
            return loaded
        }
    }

    fun items(context: Context): List<VideoItem> = load(context).map { it.toVideoItem() }

    fun add(
        context: Context,
        url: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
        pageUrl: String? = null,
    ): Bookmark? = synchronized(lock) {
        val existing = load(context)
        // Re-bookmarking the same URL just refreshes its headers, which is the
        // useful behavior: the URL still works, the old cookie may not.
        existing.firstOrNull { it.url == url }?.let { old ->
            val updated = existing.map {
                if (it.id == old.id) it.copy(headers = headers, pageUrl = pageUrl ?: it.pageUrl) else it
            }
            write(context, updated)
            cache = updated
            return null
        }
        val entry = Bookmark(
            id = BOOKMARK_ID_BASE - nextIndex++,
            url = url,
            title = title.ifBlank { titleFromUrl(url) },
            addedAtSec = System.currentTimeMillis() / 1000,
            headers = headers,
            pageUrl = pageUrl,
        )
        val updated = listOf(entry) + existing
        write(context, updated)
        cache = updated
        entry
    }

    fun remove(context: Context, ids: Set<Long>) = synchronized(lock) {
        val kept = load(context).filter { it.id !in ids }
        write(context, kept)
        cache = kept
    }

    fun rename(context: Context, id: Long, newTitle: String) = synchronized(lock) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        val updated = load(context).map { if (it.id == id) it.copy(title = trimmed) else it }
        write(context, updated)
        cache = updated
    }

    fun byId(context: Context, id: Long): Bookmark? = load(context).firstOrNull { it.id == id }

    fun isBookmarked(context: Context, url: String): Boolean =
        load(context).any { it.url == url }

    /** True for ids in the bookmark range — cheap enough to call from the UI. */
    fun isBookmarkId(id: Long): Boolean = id <= BOOKMARK_ID_BASE

    // --- io ---------------------------------------------------------------

    private fun read(context: Context): List<Bookmark> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(f.readText())
            nextIndex = root.optLong("nextIndex", 1L).coerceAtLeast(1L)
            val arr = root.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isEmpty()) return@mapNotNull null
                Bookmark(
                    id = o.optLong("id"),
                    url = url,
                    title = o.optString("title").ifEmpty { titleFromUrl(url) },
                    addedAtSec = o.optLong("added"),
                    headers = o.optJSONObject("headers")?.let { h ->
                        h.keys().asSequence().associateWith { k -> h.optString(k) }
                    } ?: emptyMap(),
                    pageUrl = o.optString("page").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, items: List<Bookmark>) {
        val arr = JSONArray()
        items.forEach { b ->
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("url", b.url)
                    .put("title", b.title)
                    .put("added", b.addedAtSec)
                    .put("headers", JSONObject(b.headers))
                    .put("page", b.pageUrl ?: "")
            )
        }
        val lowest = items.minOfOrNull { it.id }
        if (lowest != null) nextIndex = maxOf(nextIndex, BOOKMARK_ID_BASE - lowest + 1)
        val root = JSONObject().put("nextIndex", nextIndex).put("items", arr)
        runCatching {
            val tmp = File(VaultStore.vaultDir(context), BOOKMARKS_TMP_FILE)
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file(context))) {
                file(context).writeText(root.toString())
                tmp.delete()
            }
        }
    }
}

fun Bookmark.toVideoItem(): VideoItem = VideoItem(
    id = id,
    uri = Uri.parse(url),
    displayName = title,
    // Duration and size are unknown until something fetches it, and probing
    // every bookmark on every library load would mean a network round trip per
    // row. The cards show "stream" instead of a duration.
    durationMs = 0L,
    sizeBytes = 0L,
    dateAddedSec = addedAtSec,
    width = 0,
    height = 0,
    source = VideoSource.ONLINE,
    headers = headers,
)

/** A readable label from a URL: its filename, else its host. */
fun titleFromUrl(url: String): String {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return "Stream"
    val last = uri.lastPathSegment?.substringBefore('?')?.takeIf { it.isNotBlank() }
    if (last != null && last.length in 1..80) return last
    return uri.host ?: "Stream"
}
