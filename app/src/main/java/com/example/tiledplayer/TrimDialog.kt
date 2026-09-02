package com.example.tiledplayer

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** How long to sit still before decoding a preview frame while dragging. */
private const val PREVIEW_DEBOUNCE_MS = 140L

/**
 * Sets a vault video's in/out points, with a live frame preview so the user can
 * see where they're cutting.
 *
 * The trim is non-destructive — it records where playback should start and end,
 * and the file is never rewritten. That makes it instant, reversible, and safe
 * when the app's copy is the only one left. The dialog says so, because the
 * usual expectation of "trim" is that the file shrinks, and it doesn't.
 */
@Composable
fun TrimDialog(
    item: VideoItem,
    onDismiss: () -> Unit,
    onConfirm: (startMs: Long, endMs: Long) -> Unit,
) {
    val fullMs = item.fullDurationMs.coerceAtLeast(1L)

    var startMs by remember(item.id) { mutableStateOf(item.trimStartMs.coerceIn(0L, fullMs)) }
    var endMs by remember(item.id) {
        mutableStateOf(if (item.trimEndMs in 1..fullMs) item.trimEndMs else fullMs)
    }
    // Which handle to show a frame for — whichever the user touched last.
    var previewMs by remember(item.id) { mutableStateOf(startMs) }

    val retriever = remember(item.id) { MediaMetadataRetriever() }
    var retrieverReady by remember(item.id) { mutableStateOf(false) }
    DisposableEffect(item.id) {
        runCatching {
            retriever.setDataSource(File(item.uri.path ?: "").absolutePath)
            retrieverReady = true
        }
        onDispose { runCatching { retriever.release() } }
    }

    var frame by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(previewMs, retrieverReady) {
        if (!retrieverReady) return@LaunchedEffect
        // Debounced: decoding a frame per slider pixel would make the drag
        // stutter, and only the position the user rests on matters.
        delay(PREVIEW_DEBOUNCE_MS)
        frame = withContext(Dispatchers.IO) { decodeFrame(retriever, previewMs, item) }
    }

    val length = (endMs - startMs).coerceAtLeast(0L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trim") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = frame
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${formatClock(startMs)} → ${formatClock(endMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "plays ${formatClock(length)} of ${formatClock(fullMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text("Start", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = startMs.toFloat(),
                    onValueChange = {
                        // Keep at least a second of video between the handles,
                        // so a trim can never produce an empty clip.
                        startMs = it.toLong().coerceIn(0L, (endMs - 1000L).coerceAtLeast(0L))
                        previewMs = startMs
                    },
                    valueRange = 0f..fullMs.toFloat(),
                )

                Text("End", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = endMs.toFloat(),
                    onValueChange = {
                        endMs = it.toLong().coerceIn((startMs + 1000L).coerceAtMost(fullMs), fullMs)
                        previewMs = endMs
                    },
                    valueRange = 0f..fullMs.toFloat(),
                )

                Text(
                    "Sets where playback starts and ends. The file isn't re-encoded, " +
                        "so this is instant and can be undone — but it doesn't free up space.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(startMs, if (endMs >= fullMs) 0L else endMs) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (item.isTrimmed) {
                    TextButton(onClick = { onConfirm(0L, 0L) }) { Text("Reset") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * OPTION_CLOSEST is worth the extra decode work here: with CLOSEST_SYNC the
 * preview can jump to a keyframe seconds away from the handle, which makes the
 * trim look wrong even though it isn't.
 */
private fun decodeFrame(
    retriever: MediaMetadataRetriever,
    atMs: Long,
    item: VideoItem,
): Bitmap? = runCatching {
    val atUs = atMs * 1000L
    val dstW = 480
    val dstH = if (item.width > 0 && item.height > 0) {
        (dstW.toLong() * item.height / item.width).toInt().coerceAtLeast(1)
    } else {
        270
    }
    if (Build.VERSION.SDK_INT >= 27) {
        retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST, dstW, dstH)
            ?: retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)
    } else {
        retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)
    }
}.getOrNull()

/** h:mm:ss / m:ss, matching the duration badges elsewhere in the library. */
private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
