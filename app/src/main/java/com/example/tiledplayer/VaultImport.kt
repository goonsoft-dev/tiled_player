package com.example.tiledplayer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val COPY_BUFFER_BYTES = 512 * 1024
private const val PROGRESS_UPDATE_INTERVAL_MS = 120L

/** What happened to one file in an import batch. */
sealed class ImportOutcome {
    abstract val name: String

    data class Imported(override val name: String, val vaultId: Long, val sourceUri: Uri) : ImportOutcome()
    data class Skipped(override val name: String, val reason: String) : ImportOutcome()
    data class Failed(override val name: String, val reason: String) : ImportOutcome()
}

/** Snapshot of the running (or just-finished) import, for the progress sheet. */
data class ImportState(
    val running: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val currentName: String = "",
    val currentCopiedBytes: Long = 0L,
    /** -1 when the source didn't report a size, so the bar shows as indeterminate. */
    val currentTotalBytes: Long = -1L,
    val outcomes: List<ImportOutcome> = emptyList(),
    val finished: Boolean = false,
    val cancelled: Boolean = false,
) {
    val currentFraction: Float
        get() = if (currentTotalBytes > 0)
            (currentCopiedBytes.toFloat() / currentTotalBytes).coerceIn(0f, 1f) else 0f

    val imported: List<ImportOutcome.Imported>
        get() = outcomes.filterIsInstance<ImportOutcome.Imported>()

    val idle: Boolean get() = !running && !finished
}

/**
 * Copies chosen videos into [VaultStore], one at a time, on an
 * application-scoped coroutine.
 *
 * Deliberately *not* a foreground service: a service would need a visible
 * notification, which defeats the point of an app that lives in the hidden
 * drawer. The trade-off is that a copy only runs while the app is in the
 * foreground — some OEMs' battery managers may freeze the process once it's
 * backgrounded — so an interrupted copy leaves a `.part` file, which
 * [VaultStore.sweepPartials] clears on the next launch. Nothing half-copied
 * is ever committed to the index, so an interruption costs time, never data.
 */
object VaultImport {

    private val state = MutableStateFlow(ImportState())
    val progress: StateFlow<ImportState> = state.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            state.value = state.value.copy(
                running = false,
                finished = true,
                outcomes = state.value.outcomes +
                    ImportOutcome.Failed("Import", t.message ?: "Unexpected error"),
            )
        }
    )

    private var job: Job? = null

    /** Starts copying [uris]. Ignored if an import is already running. */
    fun start(context: Context, uris: List<Uri>) =
        startAll(context, uris.map { ImportRequest(it) })

    /**
     * Downloads a stream URL into the vault, turning a bookmark that could
     * break at any time into a copy that can't.
     */
    fun download(
        context: Context,
        url: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
    ) = startAll(
        context,
        listOf(ImportRequest(Uri.parse(url), preferredName = title, headers = headers)),
    )

    /** One thing to import: a local uri, or a remote URL plus its headers. */
    data class ImportRequest(
        val uri: Uri,
        val preferredName: String? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    fun startAll(context: Context, requests: List<ImportRequest>) {
        if (state.value.running || requests.isEmpty()) return
        val appContext = context.applicationContext
        state.value = ImportState(running = true, total = requests.size)
        job = scope.launch {
            val outcomes = mutableListOf<ImportOutcome>()
            for ((index, request) in requests.withIndex()) {
                currentCoroutineContext().ensureActive()
                val outcome = importOne(appContext, request) { copied, total ->
                    state.value = state.value.copy(
                        currentCopiedBytes = copied,
                        currentTotalBytes = total,
                    )
                }
                outcomes += outcome
                state.value = state.value.copy(
                    completed = index + 1,
                    outcomes = outcomes.toList(),
                    currentCopiedBytes = 0L,
                    currentTotalBytes = -1L,
                )
            }
            state.value = state.value.copy(running = false, finished = true)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        state.value = state.value.copy(running = false, finished = true, cancelled = true)
    }

    /** Clears the finished-batch summary so the sheet closes. */
    fun acknowledge() {
        if (!state.value.running) state.value = ImportState()
    }

    private suspend fun importOne(
        context: Context,
        request: ImportRequest,
        onProgress: (copied: Long, total: Long) -> Unit,
    ): ImportOutcome {
        val uri = request.uri
        val remote = uri.scheme == "http" || uri.scheme == "https"
        val (rawName, reportedSize) = if (remote) {
            // A content-provider query is meaningless for a URL; the size comes
            // from the response headers once the connection is open.
            (request.preferredName ?: titleFromUrl(uri.toString())) to -1L
        } else {
            queryNameAndSize(context, uri)
        }
        val displayName =
            if (remote) rawName else humanizeName(context, uri, rawName)
        state.value = state.value.copy(
            currentName = displayName,
            currentCopiedBytes = 0L,
            currentTotalBytes = reportedSize,
        )

        val alreadyThere = VaultStore.importedSourceKeys(context)
        if (uri.toString() in alreadyThere ||
            (reportedSize > 0 && VaultStore.identityKey(displayName, reportedSize) in alreadyThere)
        ) {
            return ImportOutcome.Skipped(displayName, "already in the player")
        }

        if (reportedSize > 0 && !VaultStore.hasRoomFor(context, reportedSize)) {
            return ImportOutcome.Failed(displayName, "not enough free space")
        }

        val ext = if (remote) remoteExtension(uri, rawName) else extensionFor(context, uri, rawName)
        val part = File(
            VaultStore.videosDir(context),
            "import_${System.nanoTime()}$PART_SUFFIX",
        )

        return try {
            val copied = if (remote) {
                downloadToPart(context, uri.toString(), request.headers, part, onProgress)
            } else {
                copyToPart(context, uri, part, reportedSize, onProgress)
            }
            if (copied <= 0L) {
                part.delete()
                ImportOutcome.Failed(displayName, "the file was empty or unreadable")
            } else {
                val entry = VaultStore.commit(
                    context = context,
                    file = part,
                    displayName = displayName,
                    extension = ext,
                    sourceKey = uri.toString(),
                )
                ImportOutcome.Imported(displayName, entry.id, uri)
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            part.delete()
            throw c
        } catch (t: Throwable) {
            part.delete()
            ImportOutcome.Failed(displayName, t.message ?: "copy failed")
        }
    }

    /**
     * Downloads a remote video into [part], sending the headers the URL was
     * seen with — without them many sites answer 403 to a bare request.
     *
     * Deliberately only handles a single file. An HLS/DASH manifest is a
     * playlist of thousands of segments that would need muxing back together,
     * which is out of scope here: such a stream can be *watched* via a
     * bookmark, just not downloaded.
     */
    private suspend fun downloadToPart(
        context: Context,
        url: String,
        headers: Map<String, String>,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 20_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("the server answered $code")
            }
            val contentType = connection.contentType.orEmpty()
            if (contentType.contains("mpegurl", true) || contentType.contains("dash", true)) {
                throw IllegalStateException(
                    "this is an adaptive stream playlist, not a video file — bookmark it instead"
                )
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            if (total > 0 && !VaultStore.hasRoomFor(context, total)) {
                throw IllegalStateException("not enough free space")
            }
            onProgress(0L, total)

            var copied = 0L
            var lastUpdate = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                            lastUpdate = now
                            onProgress(copied, total)
                        }
                    }
                    out.fd.sync()
                }
            }
            onProgress(copied, total)
            return copied
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** Extension for a downloaded file: from the URL path, else assume mp4. */
    private fun remoteExtension(uri: Uri, name: String): String {
        val fromName = name.substringAfterLast('.', "")
        if (fromName.length in 1..5 && fromName.all { it.isLetterOrDigit() }) {
            return fromName.lowercase()
        }
        val fromPath = uri.lastPathSegment?.substringBefore('?')?.substringAfterLast('.', "")
        return fromPath?.takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }
            ?.lowercase() ?: "mp4"
    }

    private suspend fun copyToPart(
        context: Context,
        uri: Uri,
        part: File,
        reportedSize: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("could not open the source file")
        var copied = 0L
        var lastUpdate = 0L
        input.use { source ->
            FileOutputStream(part).use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    // Cancellation is checked per chunk so "Cancel" is
                    // responsive even on a multi-gigabyte file.
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                        lastUpdate = now
                        onProgress(copied, reportedSize)
                    }
                    // Re-check space every ~256MB for sources that lied about
                    // (or never reported) their size.
                    if (reportedSize <= 0 && copied % (256L * 1024 * 1024) < COPY_BUFFER_BYTES &&
                        !VaultStore.hasRoomFor(context, COPY_BUFFER_BYTES.toLong())
                    ) {
                        throw IllegalStateException("ran out of free space")
                    }
                }
                out.fd.sync()
            }
        }
        onProgress(copied, reportedSize)
        return copied
    }
}
