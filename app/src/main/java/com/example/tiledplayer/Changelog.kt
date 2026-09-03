package com.example.tiledplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The "What's new" history, newest first. Shown from the library screen's
 * overflow menu and version footer. Kept here as plain data so adding an entry
 * is a one-line change next to bumping `versionName` in build.gradle.kts.
 */
data class ReleaseNotes(val version: String, val changes: List<String>)

object Changelog {
    val releases: List<ReleaseNotes> = listOf(
        ReleaseNotes(
            "0.6-vault",
            listOf(
                "Player top bar is now a single dropdown — grid size, presets and " +
                    "play mode moved off the crowded ribbon.",
                "Fixed an out-of-memory crash at high tile counts: per-pane video " +
                    "buffering now scales with the number of tiles, the heap ceiling " +
                    "is raised, and library thumbnails are decoded at display size.",
                "App version is shown on the library screen, with this history.",
            ),
        ),
        ReleaseNotes(
            "0.5-vault",
            listOf(
                "The in-app browser is a real tabbed browser now: multiple tabs, " +
                    "pop-ups and new-window links, downloads, and full-screen video.",
                "Fixed the browser opening to a black screen from the + menu.",
                "Streams with no fixed length (HLS/DASH) play and scrub correctly.",
            ),
        ),
        ReleaseNotes(
            "0.4-vault",
            listOf(
                "Export vault videos back out to a folder you pick.",
                "Online tab: bookmark and tile web videos without ever storing them.",
                "Other apps can hand videos and links to this one (toggle in ⋮).",
            ),
        ),
        ReleaseNotes(
            "0.3-vault",
            listOf(
                "Each layout remembers which tiles were unmuted in it.",
                "Non-destructive trim: set in/out points without re-encoding.",
                "Shuffle a vault video's thumbnail to a different frame.",
            ),
        ),
        ReleaseNotes(
            "0.2-vault",
            listOf(
                "In-player vault: keep private copies that survive deleting the " +
                    "originals and need no storage permission.",
                "Block screenshots and the recents preview (on by default).",
            ),
        ),
    )
}

@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's new") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Changelog.releases.forEachIndexed { index, release ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    Text(
                        "Version ${release.version}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    release.changes.forEach { line ->
                        Text(
                            "•  $line",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
