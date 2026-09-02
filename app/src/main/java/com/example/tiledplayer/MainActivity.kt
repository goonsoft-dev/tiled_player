package com.example.tiledplayer

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Applied before the first frame so the recent-apps preview is never
        // captured unprotected, not even for the moment before Compose runs.
        applySecureScreen(VaultPrefs.loadSecureScreen(this))
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val handoff = HandoffIntent.from(this, intent)
        val initialSession = debugSessionFromIntent() ?: (handoff as? HandoffIntent.Play)?.session

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black,
                    contentColor = Color.White
                ) {
                    AppRoot(
                        initialSession = initialSession,
                        initialHandoff = handoff,
                        onImmersiveChange = ::applyImmersiveMode,
                        onSecureScreenChange = ::applySecureScreen,
                    )
                }
            }
        }
    }

    /**
     * Test-only hook: launch with `-e debug_video_name <file>` (a single file in
     * the app's internal files dir) or `-e debug_video_names a.mp4,b.mp4` (a
     * comma-separated list, for multi-video testing), plus an optional
     * `-e tiles <n>`, to jump straight into playback, bypassing the picker.
     * Ignored in normal use.
     */
    private fun debugSessionFromIntent(): PlaybackSession? {
        val names = intent?.getStringExtra("debug_video_names")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: intent?.getStringExtra("debug_video_name")?.let { listOf(it) }
            ?: return null
        val clips = names
            .map { File(filesDir, it) }
            .filter { it.exists() }
            .map { PlaybackClip(Uri.fromFile(it)) }
        if (clips.isEmpty()) return null
        val tiles = intent.getStringExtra("tiles")?.toIntOrNull()
            ?: intent.getIntExtra("tiles", 4)
        return PlaybackSession(clips, tiles.coerceIn(1, 16))
    }

    /**
     * FLAG_SECURE: keeps the app out of screenshots, screen recordings and the
     * recent-apps preview. On by default — a hidden app whose contents show up
     * in the task switcher isn't hidden.
     */
    private fun applySecureScreen(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun applyImmersiveMode(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * One video to play, optionally narrowed to a slice of itself.
 *
 * Trimming a vault video sets [startMs]/[endMs] rather than rewriting the
 * file: it's instant, reversible, and — since the gallery original may already
 * be deleted — can't destroy the only copy. Everything downstream treats
 * `startMs..endMs` as if it were the whole video, so the segment planner
 * divides the trimmed range across panes, not the original one.
 *
 * [endMs] is null for "to the end", since the real duration isn't known until
 * the player probes it.
 */
data class PlaybackClip(
    val uri: Uri,
    val startMs: Long = 0L,
    val endMs: Long? = null,
    /**
     * Request headers for http(s) sources. Many sites only serve their media
     * to requests carrying the page's Referer/Cookie/User-Agent, so a URL
     * sniffed from a page is useless without the headers it was seen with.
     */
    val headers: Map<String, String> = emptyMap(),
) {
    val isRemote: Boolean
        get() = uri.scheme == "http" || uri.scheme == "https"
}

/** The chosen videos plus the number of tiles to divide them across. */
data class PlaybackSession(val clips: List<PlaybackClip>, val tileCount: Int)

@Composable
fun AppRoot(
    initialSession: PlaybackSession? = null,
    initialHandoff: HandoffIntent? = null,
    onImmersiveChange: (Boolean) -> Unit,
    onSecureScreenChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf(initialSession) }
    // Non-null while the in-app browser is open; the string is where to start.
    var browserUrl by remember {
        mutableStateOf((initialHandoff as? HandoffIntent.Browse)?.url)
    }

    // Videos shared in from another app go straight into the import pipeline.
    LaunchedEffect(initialHandoff) {
        (initialHandoff as? HandoffIntent.Import)?.let { VaultImport.start(context, it.uris) }
    }

    val current = session

    // Hoisted here (AppRoot never leaves composition) so the library's scroll
    // position survives navigating to the player and back, instead of
    // resetting every time LibraryScreen itself is torn down and recreated.
    val libraryGridState = rememberLazyGridState()
    val libraryLargeListState = rememberLazyListState()
    val libraryCompactListState = rememberLazyListState()

    when {
        current != null -> {
            LaunchedEffect(Unit) { onImmersiveChange(true) }
            PlayerScreen(session = current, onExit = { session = null })
        }
        browserUrl != null -> {
            LaunchedEffect(Unit) { onImmersiveChange(false) }
            BrowserScreen(
                startUrl = browserUrl,
                onPlay = { clips, n -> session = PlaybackSession(clips, n) },
                onExit = { browserUrl = null },
            )
        }
        else -> {
            LaunchedEffect(Unit) { onImmersiveChange(false) }
            LibraryScreen(
                gridState = libraryGridState,
                largeListState = libraryLargeListState,
                compactListState = libraryCompactListState,
                onPlay = { clips, n -> session = PlaybackSession(clips, n) },
                onSecureScreenChange = onSecureScreenChange,
                // An empty string opens the browser on its blank start page.
                onOpenBrowser = { browserUrl = it ?: "" },
            )
        }
    }
}
