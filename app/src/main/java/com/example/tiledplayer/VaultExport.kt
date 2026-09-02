package com.example.tiledplayer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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

private const val COPY_BUFFER_BYTES = 512 * 1024
private const val PROGRESS_UPDATE_INTERVAL_MS = 120L

/** What happened to one file in an export batch. */
sealed class ExportOutcome {
    abstract val name: String

    data class Exported(override val name: String) : ExportOutcome()
    data class Failed(override val name: String, val reason: String) : ExportOutcome()
}

data class ExportState(
    val running: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val currentName: String = "",
    val currentCopiedBytes: Long = 0L,
    val currentTotalBytes: Long = 0L,
    val outcomes: List<ExportOutcome> = emptyList(),
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    /** Where it went, for the completion message. */
    val destinationName: String = "",
) {
    val currentFraction: Float
        get() = if (currentTotalBytes > 0)
            (currentCopiedBytes.toFloat() / currentTotalBytes).coerceIn(0f, 1f) else 0f

    val exported: Int get() = outcomes.count { it is ExportOutcome.Exported }
    val idle: Boolean get() = !running && !finished
}

/**
 * Copies vault videos back out to a folder the user picks.
 *
 * This is the escape hatch for the vault's one real weakness: the copies live
 * in app-private storage, so uninstalling the app — or Android clearing its
 * data — takes them with it, and by then the gallery originals may be long
 * gone. Exporting writes them somewhere durable and shareable.
 *
 * Mirrors [VaultImport]'s shape deliberately (same app-scoped single-job queue,
 * same per-file isolation, same foreground-only trade-off).
 *
 * Note that a trimmed video exports **whole**: the trim is stored as in/out
 * points rather than baked into the file, so there is no trimmed file to copy.
 */
object VaultExport {

    private val state = MutableStateFlow(ExportState())
    val progress: StateFlow<ExportState> = state.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            state.value = state.value.copy(
                running = false,
                finished = true,
                outcomes = state.value.outcomes +
                    ExportOutcome.Failed("Export", t.message ?: "Unexpected error"),
            )
        }
    )

    private var job: Job? = null

    /** [treeUri] is a folder from ACTION_OPEN_DOCUMENT_TREE. */
    fun start(context: Context, treeUri: Uri, ids: List<Long>) {
        if (state.value.running || ids.isEmpty()) return
        val appContext = context.applicationContext
        val folder = DocumentFile.fromTreeUri(appContext, treeUri)
        if (folder == null || !folder.canWrite()) {
            state.value = ExportState(
                finished = true,
                total = ids.size,
                outcomes = listOf(
                    ExportOutcome.Failed("Export", "That folder can't be written to.")
                ),
            )
            return
        }

        state.value = ExportState(
            running = true,
            total = ids.size,
            destinationName = folder.name ?: "the chosen folder",
        )

        job = scope.launch {
            val outcomes = mutableListOf<ExportOutcome>()
            val entries = VaultStore.load(appContext).associateBy { it.id }
            for ((index, id) in ids.withIndex()) {
                currentCoroutineContext().ensureActive()
                val entry = entries[id]
                val outcome = if (entry == null) {
                    ExportOutcome.Failed("Video", "no longer in the player")
                } else {
                    exportOne(appContext, folder, entry)
                }
                outcomes += outcome
                state.value = state.value.copy(
                    completed = index + 1,
                    outcomes = outcomes.toList(),
                    currentCopiedBytes = 0L,
                    currentTotalBytes = 0L,
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

    fun acknowledge() {
        if (!state.value.running) state.value = ExportState()
    }

    private suspend fun exportOne(
        context: Context,
        folder: DocumentFile,
        entry: VaultVideo,
    ): ExportOutcome {
        val source = VaultStore.videoFile(context, entry)
        if (!source.isFile) return ExportOutcome.Failed(entry.displayName, "the copy is missing")

        state.value = state.value.copy(
            currentName = entry.displayName,
            currentCopiedBytes = 0L,
            currentTotalBytes = source.length(),
        )

        // The vault's display name may have no extension (renamed, or generated
        // for a photo-picker import), so rebuild one from the stored file so the
        // exported video is recognised by whatever opens it next.
        val extension = source.extension.ifEmpty { "mp4" }
        val baseName = entry.displayName.substringBeforeLast('.', entry.displayName)
            .ifBlank { "video" }
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val fileName = uniqueName(folder, baseName, extension)

        val target = folder.createFile("video/*", fileName)
            ?: return ExportOutcome.Failed(entry.displayName, "couldn't create the file")

        return try {
            val out = context.contentResolver.openOutputStream(target.uri)
                ?: throw IllegalStateException("couldn't open the destination")
            var copied = 0L
            var lastUpdate = 0L
            out.use { sink ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                            lastUpdate = now
                            state.value = state.value.copy(currentCopiedBytes = copied)
                        }
                    }
                }
            }
            ExportOutcome.Exported(fileName)
        } catch (c: kotlinx.coroutines.CancellationException) {
            runCatching { target.delete() }
            throw c
        } catch (t: Throwable) {
            // A partial file is worse than none: it looks like a successful
            // export until someone tries to play it.
            runCatching { target.delete() }
            ExportOutcome.Failed(entry.displayName, t.message ?: "copy failed")
        }
    }

    /** Avoids silently overwriting an earlier export of the same name. */
    private fun uniqueName(folder: DocumentFile, base: String, extension: String): String {
        var candidate = "$base.$extension"
        var n = 2
        while (folder.findFile(candidate) != null && n < 1000) {
            candidate = "$base ($n).$extension"
            n++
        }
        return candidate
    }
}
