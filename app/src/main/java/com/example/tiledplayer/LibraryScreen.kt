package com.example.tiledplayer

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_TILES = 16

/** Same decode-capable MIME list as the old picker screen. */
private val SUPPORTED_VIDEO_MIME_TYPES = arrayOf(
    "video/mp4",
    "video/x-matroska",
    "video/webm",
    "video/3gpp",
    "video/3gpp2",
    "video/mp2t",
    "video/quicktime",
    "video/avc",
    "video/hevc",
    "video/ogg",
)

/**
 * The app's welcome screen, in three tabs.
 *
 * **Player** is the vault: videos copied into the app's own private storage by
 * [VaultImport]. They keep playing after the original has been hidden, moved
 * into a vendor-specific secure/private folder, or deleted, and they need no
 * storage permission at all — which is what makes the app usable from a
 * hidden-app drawer, where that secure folder is unreachable. They can also
 * be **exported** back out to a folder, which is the only defence against the
 * copies dying with the app's data.
 *
 * **Online** is [RemoteLibrary]: bookmarked URLs that stream straight off the
 * web, so a video can be watched (and tiled) without ever being stored. Any of
 * them can be downloaded into the vault later.
 *
 * **Device** is the MediaStore-backed library (ratings, sorting, filtering,
 * card/list views), used to pick what to import — though device videos can
 * still be played in place.
 *
 * In every tab, tapping a video plays it immediately (1 tile) and long-press
 * starts a multi-select that feeds the tiled-playback flow.
 */
@Composable
fun LibraryScreen(
    gridState: LazyGridState,
    largeListState: LazyListState,
    compactListState: LazyListState,
    onPlay: (List<PlaybackClip>, Int) -> Unit,
    onSecureScreenChange: (Boolean) -> Unit = {},
    onOpenBrowser: (String?) -> Unit = {},
) {
    val context = LocalContext.current

    var tab by remember { mutableStateOf(VaultPrefs.loadLastTab(context)) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, VideoLibraryRepository.requiredPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    // Only ask once the user actually opens the device tab: the vault works
    // without any permission, so a hidden-app user who never browses the
    // gallery is never prompted.
    LaunchedEffect(tab) {
        if (tab == LibraryTab.DEVICE && !hasPermission && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(VideoLibraryRepository.requiredPermission)
        }
    }

    // --- vault state ------------------------------------------------------

    var vaultVersion by remember { mutableStateOf(0) }
    var vaultItems by remember { mutableStateOf<List<VideoItem>?>(null) }
    var vaultSourceKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(vaultVersion) {
        withContext(Dispatchers.IO) {
            val items = VaultStore.items(context)
            val keys = VaultStore.importedSourceKeys(context)
            withContext(Dispatchers.Main) {
                vaultItems = items
                vaultSourceKeys = keys
            }
        }
    }

    // Bookmarked streams. Cheap to load (a small json), so no async dance.
    var onlineVersion by remember { mutableStateOf(0) }
    val onlineItems = remember(onlineVersion) { RemoteLibrary.items(context) }

    val importState by VaultImport.progress.collectAsState()
    // Refresh as each file lands, so the vault tab fills in during a long batch.
    LaunchedEffect(importState.completed, importState.finished) {
        if (importState.completed > 0 || importState.finished) vaultVersion++
    }

    val exportState by VaultExport.progress.collectAsState()
    val exportTargetIds = remember { mutableStateOf<List<Long>>(emptyList()) }
    val exportFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            VaultExport.start(context, treeUri, exportTargetIds.value)
        }
        exportTargetIds.value = emptyList()
    }
    /** Asks for a destination folder, then exports [ids]. */
    val startExport: (List<Long>) -> Unit = { ids ->
        if (ids.isNotEmpty()) {
            exportTargetIds.value = ids
            exportFolderPicker.launch(null)
        }
    }

    val startImport: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            VaultImport.start(context, uris)
            tab = LibraryTab.PLAYER
            VaultPrefs.saveLastTab(context, LibraryTab.PLAYER)
        }
    }

    // Two ways in, both of which copy rather than link: the system photo picker
    // (needs no permission — the best route for a hidden app) and SAF, for
    // files the gallery doesn't index.
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> startImport(uris) }

    val filesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            startImport(uris)
        }
    }
    val launchGalleryImport = {
        galleryPicker.launch(
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.VideoOnly
            )
        )
    }
    val launchFilesImport = { filesPicker.launch(SUPPORTED_VIDEO_MIME_TYPES) }

    var deleteError by remember { mutableStateOf<String?>(null) }
    var deviceReloadKey by remember { mutableStateOf(0) }
    val deleteOriginalsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { _ ->
        // Whatever the user chose in the system's own confirmation dialog,
        // re-read the device list so it reflects the result.
        deviceReloadKey++
    }

    var secureScreen by remember { mutableStateOf(VaultPrefs.loadSecureScreen(context)) }
    LaunchedEffect(secureScreen) { onSecureScreenChange(secureScreen) }

    // --- device state -----------------------------------------------------

    var lastCrash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { lastCrash = CrashReporter.consumeLastCrash(context) }

    var showWhatsNew by remember { mutableStateOf(false) }

    var videos by remember { mutableStateOf<List<VideoItem>?>(null) }
    LaunchedEffect(hasPermission, deviceReloadKey) {
        if (hasPermission) {
            videos = withContext(Dispatchers.IO) { VideoLibraryRepository.queryVideos(context) }
        }
    }

    var ratings by remember { mutableStateOf(LibraryPrefs.loadRatings(context)) }
    val onRate: (Long, Int) -> Unit = { id, rating ->
        ratings = ratings.toMutableMap().apply {
            if (rating <= 0) remove(id) else put(id, rating)
        }
        LibraryPrefs.saveRatings(context, ratings)
    }

    var viewMode by remember { mutableStateOf(LibraryPrefs.loadViewMode(context)) }
    var sortMode by remember { mutableStateOf(LibraryPrefs.loadSortMode(context)) }
    var minRating by remember { mutableStateOf(LibraryPrefs.loadMinRating(context)) }

    // Jump every list back to the top on an actual sort/filter change, but not
    // on the first composition after returning from the player (that's a
    // remount, not a change, and should keep the restored scroll position).
    var sortFilterTouched by remember { mutableStateOf(false) }
    LaunchedEffect(sortMode, minRating, tab) {
        if (sortFilterTouched) {
            gridState.scrollToItem(0)
            largeListState.scrollToItem(0)
            compactListState.scrollToItem(0)
        } else {
            sortFilterTouched = true
        }
    }

    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var tileCount by remember { mutableStateOf(1) }
    var confirmRemove by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VideoItem?>(null) }
    var renaming by remember { mutableStateOf<VideoItem?>(null) }
    var trimming by remember { mutableStateOf<VideoItem?>(null) }
    var busy by remember { mutableStateOf(false) }

    val source = when (tab) {
        LibraryTab.PLAYER -> vaultItems
        LibraryTab.ONLINE -> onlineItems
        LibraryTab.DEVICE -> videos
    }
    val byId = remember(source) { source.orEmpty().associateBy { it.id } }

    val visible = remember(source, ratings, sortMode, minRating) {
        val base = source.orEmpty().filter { (ratings[it.id] ?: 0) >= minRating }
        sortVideos(base, ratings, sortMode)
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        LibraryTabBar(
            tab = tab,
            onTabChange = {
                if (it != tab) {
                    selected = emptySet()
                    tab = it
                    VaultPrefs.saveLastTab(context, it)
                }
            },
            vaultCount = vaultItems?.size ?: 0,
            onlineCount = onlineItems.size,
            secureScreen = secureScreen,
            onSecureScreenToggle = {
                secureScreen = !secureScreen
                VaultPrefs.saveSecureScreen(context, secureScreen)
            },
            onExportAll = { startExport(vaultItems.orEmpty().map { it.id }) },
            exportEnabled = !vaultItems.isNullOrEmpty(),
            onShowWhatsNew = { showWhatsNew = true },
        )
        LibraryControlBar(
            minRating = minRating,
            onMinRatingChange = { minRating = it; LibraryPrefs.saveMinRating(context, it) },
            summary = librarySummary(tab, visible.size, source?.size ?: 0, minRating, vaultItems),
            viewMode = viewMode,
            onViewModeChange = { viewMode = it; LibraryPrefs.saveViewMode(context, it) },
            sortMode = sortMode,
            onSortModeChange = { sortMode = it; LibraryPrefs.saveSortMode(context, it) },
            onGalleryImport = launchGalleryImport,
            onFilesImport = launchFilesImport,
            onOpenBrowser = { onOpenBrowser(null) },
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab == LibraryTab.DEVICE && !hasPermission -> PermissionGate(
                    onGrantAccess = { permissionLauncher.launch(VideoLibraryRepository.requiredPermission) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    },
                    onImport = launchGalleryImport,
                )
                source == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                source.isEmpty() -> when (tab) {
                    LibraryTab.PLAYER -> EmptyVault(
                        onGalleryImport = launchGalleryImport,
                        onFilesImport = launchFilesImport,
                    )
                    LibraryTab.ONLINE -> EmptyOnline(onOpenBrowser = { onOpenBrowser(null) })
                    LibraryTab.DEVICE -> EmptyLibrary(onImport = launchGalleryImport)
                }
                visible.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "No videos match this filter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> VideoGridOrList(
                    videos = visible,
                    viewMode = viewMode,
                    gridState = gridState,
                    largeListState = largeListState,
                    compactListState = compactListState,
                    ratings = ratings,
                    onRate = onRate,
                    selected = selected,
                    importedKeys = if (tab == LibraryTab.DEVICE) vaultSourceKeys else emptySet(),
                    onTap = { item ->
                        if (selected.isEmpty()) {
                            onPlay(listOf(item.toPlaybackClip()), 1)
                        } else {
                            selected = if (item.id in selected) selected - item.id else selected + item.id
                        }
                    },
                    onLongPress = { item -> selected = selected + item.id },
                )
            }

            if (selected.isNotEmpty()) {
                SelectionBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    tab = tab,
                    count = selected.size,
                    tileCount = tileCount,
                    onTileCountChange = { tileCount = it },
                    onCancel = { selected = emptySet() },
                    onImport = {
                        if (tab == LibraryTab.ONLINE) {
                            // Downloading a bookmark turns a reference that can
                            // break into a copy that can't. One batch, not one
                            // call per item — a second start() while the first
                            // is running would be dropped.
                            VaultImport.startAll(
                                context,
                                selected.mapNotNull { byId[it] }.map { item ->
                                    VaultImport.ImportRequest(
                                        uri = item.uri,
                                        preferredName = item.displayName,
                                        headers = item.headers,
                                    )
                                },
                            )
                            tab = LibraryTab.PLAYER
                            VaultPrefs.saveLastTab(context, LibraryTab.PLAYER)
                        } else {
                            startImport(selected.mapNotNull { byId[it]?.uri })
                        }
                        selected = emptySet()
                    },
                    onExport = { startExport(selected.toList()) },
                    onRemove = { confirmRemove = true },
                    onEdit = {
                        val item = selected.singleOrNull()?.let { byId[it] }
                        // Trim and poster frames need a local file, so a
                        // bookmark's only editable property is its name.
                        if (item != null) {
                            if (item.source == VideoSource.ONLINE) renaming = item else editing = item
                        }
                    },
                    onPlay = {
                        val clips = selected.mapNotNull { byId[it]?.toPlaybackClip() }
                        if (clips.isNotEmpty()) onPlay(clips, maxOf(tileCount, clips.size))
                        selected = emptySet()
                    },
                )
            }
        }

        AppVersionFooter(onClick = { showWhatsNew = true })
    }

    if (showWhatsNew) {
        WhatsNewDialog(onDismiss = { showWhatsNew = false })
    }

    if (!importState.idle) {
        ImportSheet(
            state = importState,
            onCancel = { VaultImport.cancel() },
            onDone = { VaultImport.acknowledge() },
            onDeleteOriginals = {
                // Only gallery-backed originals can be deleted through
                // MediaStore, and only via the system's own confirmation
                // dialog — the app never deletes anything silently.
                val uris = importState.imported
                    .map { it.sourceUri }
                    .filter { it.pathSegments.firstOrNull() == "external" }
                when {
                    Build.VERSION.SDK_INT < 30 ->
                        deleteError = "This Android version can't hand the app a delete " +
                            "confirmation — remove the originals from the gallery yourself."
                    uris.isEmpty() ->
                        deleteError = "These came in through the file picker, so the app can't " +
                            "delete them for you — remove them from the gallery yourself."
                    else -> runCatching {
                        val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                        deleteOriginalsLauncher.launch(
                            IntentSenderRequest.Builder(request.intentSender).build()
                        )
                        VaultImport.acknowledge()
                    }.onFailure {
                        deleteError = it.message ?: "The system refused the delete request."
                    }
                }
            },
        )
    }

    if (confirmRemove) {
        val count = selected.size
        val online = tab == LibraryTab.ONLINE
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = {
                Text(if (online) "Remove $count bookmark${if (count == 1) "" else "s"}?"
                else "Remove $count from the player?")
            },
            text = {
                Text(
                    if (online) {
                        "Only the link is removed — nothing was stored on the device anyway."
                    } else {
                        "This deletes the app's own copies. If the originals are already " +
                            "gone from the gallery, this cannot be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected
                    confirmRemove = false
                    selected = emptySet()
                    if (online) {
                        RemoteLibrary.remove(context, ids)
                        onlineVersion++
                    } else {
                        VaultStore.remove(context, ids)
                        evictThumbnails(ids)
                        vaultVersion++
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }

    if (!exportState.idle) {
        ExportSheet(
            state = exportState,
            onCancel = { VaultExport.cancel() },
            onDone = { VaultExport.acknowledge() },
        )
    }

    editing?.let { item ->
        val scope = rememberCoroutineScope()
        EditVideoDialog(
            item = item,
            busy = busy,
            onRename = { editing = null; renaming = item },
            onTrim = { editing = null; trimming = item },
            onShuffleThumbnail = {
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        VaultStore.reshuffleThumbnail(context, item.id)
                    }
                    busy = false
                    if (ok) {
                        evictThumbnails(setOf(item.id))
                        vaultVersion++
                        // Re-read so the dialog's own preview picks up the new
                        // frame (the item we were handed is now stale).
                        editing = VaultStore.items(context).firstOrNull { it.id == item.id }
                    }
                }
            },
            onDismiss = { editing = null; selected = emptySet() },
        )
    }

    renaming?.let { item ->
        RenameDialog(
            item = item,
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                if (item.source == VideoSource.ONLINE) {
                    RemoteLibrary.rename(context, item.id, newName)
                    onlineVersion++
                } else {
                    VaultStore.rename(context, item.id, newName)
                    vaultVersion++
                }
                renaming = null
                selected = emptySet()
            },
        )
    }

    trimming?.let { item ->
        TrimDialog(
            item = item,
            onDismiss = { trimming = null },
            onConfirm = { start, end ->
                VaultStore.setTrim(context, item.id, start, end)
                trimming = null
                selected = emptySet()
                vaultVersion++
            },
        )
    }

    deleteError?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteError = null },
            title = { Text("Couldn't delete the originals") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { deleteError = null }) { Text("OK") } },
        )
    }

    lastCrash?.let { report ->
        CrashReportDialog(report = report, onDismiss = { lastCrash = null })
    }
}

/** The line under the tabs: how many videos, and how much space the vault uses. */
private fun librarySummary(
    tab: LibraryTab,
    visibleCount: Int,
    totalCount: Int,
    minRating: Int,
    vaultItems: List<VideoItem>?,
): String {
    val counted = if (minRating > 0) "$visibleCount of $totalCount" else "$totalCount videos"
    if (tab != LibraryTab.PLAYER) return counted
    val bytes = vaultItems.orEmpty().sumOf { it.sizeBytes }
    return if (bytes > 0) "$counted · ${formatFileSize(bytes)}" else counted
}

@Composable
private fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The app closed unexpectedly last time") },
        text = {
            Text(
                report,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
    )
}

private fun sortVideos(videos: List<VideoItem>, ratings: Map<Long, Int>, mode: LibrarySortMode): List<VideoItem> =
    when (mode) {
        LibrarySortMode.DATE_ADDED_DESC -> videos.sortedByDescending { it.dateAddedSec }
        LibrarySortMode.DATE_ADDED_ASC -> videos.sortedBy { it.dateAddedSec }
        LibrarySortMode.NAME_ASC -> videos.sortedBy { it.displayName.lowercase() }
        LibrarySortMode.NAME_DESC -> videos.sortedByDescending { it.displayName.lowercase() }
        LibrarySortMode.DURATION_DESC -> videos.sortedByDescending { it.durationMs }
        LibrarySortMode.DURATION_ASC -> videos.sortedBy { it.durationMs }
        LibrarySortMode.SIZE_DESC -> videos.sortedByDescending { it.sizeBytes }
        LibrarySortMode.RATING_DESC -> videos.sortedByDescending { ratings[it.id] ?: 0 }
    }

private fun nextViewMode(mode: LibraryViewMode): LibraryViewMode = when (mode) {
    LibraryViewMode.CARDS -> LibraryViewMode.LARGE
    LibraryViewMode.LARGE -> LibraryViewMode.LIST
    LibraryViewMode.LIST -> LibraryViewMode.CARDS
}

/** Icon shows the current mode: multi-column grid, one big card per row, or a compact list. */
private fun viewModeGlyph(mode: LibraryViewMode): String = when (mode) {
    LibraryViewMode.CARDS -> "▦"
    LibraryViewMode.LARGE -> "▭"
    LibraryViewMode.LIST -> "☰"
}

private fun viewModeLabel(mode: LibraryViewMode): String = when (mode) {
    LibraryViewMode.CARDS -> "Grid"
    LibraryViewMode.LARGE -> "Large list"
    LibraryViewMode.LIST -> "Compact list"
}

private fun sortLabel(mode: LibrarySortMode): String = when (mode) {
    LibrarySortMode.DATE_ADDED_DESC -> "Newest first"
    LibrarySortMode.DATE_ADDED_ASC -> "Oldest first"
    LibrarySortMode.NAME_ASC -> "Name A-Z"
    LibrarySortMode.NAME_DESC -> "Name Z-A"
    LibrarySortMode.DURATION_DESC -> "Longest first"
    LibrarySortMode.DURATION_ASC -> "Shortest first"
    LibrarySortMode.SIZE_DESC -> "Largest first"
    LibrarySortMode.RATING_DESC -> "Highest rated"
}

/**
 * "In player" vs "On device", plus the overflow with the hidden-app settings.
 * The tabs are the app's primary navigation, so they get a full row.
 */
@Composable
private fun LibraryTabBar(
    tab: LibraryTab,
    onTabChange: (LibraryTab) -> Unit,
    vaultCount: Int,
    onlineCount: Int,
    secureScreen: Boolean,
    onSecureScreenToggle: () -> Unit,
    onExportAll: () -> Unit,
    exportEnabled: Boolean,
    onShowWhatsNew: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var openWith by remember { mutableStateOf(HandoffIntent.isOpenWithEnabled(context)) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(3.dp),
        ) {
            TabButton(
                label = if (vaultCount > 0) "Player · $vaultCount" else "Player",
                selected = tab == LibraryTab.PLAYER,
                onClick = { onTabChange(LibraryTab.PLAYER) },
                modifier = Modifier.weight(1f),
            )
            TabButton(
                label = if (onlineCount > 0) "Online · $onlineCount" else "Online",
                selected = tab == LibraryTab.ONLINE,
                onClick = { onTabChange(LibraryTab.ONLINE) },
                modifier = Modifier.weight(1f),
            )
            TabButton(
                label = "Device",
                selected = tab == LibraryTab.DEVICE,
                onClick = { onTabChange(LibraryTab.DEVICE) },
                modifier = Modifier.weight(1f),
            )
        }

        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.semantics { contentDescription = "More options" },
            ) {
                Text("⋮", style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Export everything…") },
                    enabled = exportEnabled,
                    onClick = { menuOpen = false; onExportAll() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (secureScreen) "✓ " else "    ") +
                                "Block screenshots & recents preview"
                        )
                    },
                    onClick = { onSecureScreenToggle(); menuOpen = false },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (openWith) "✓ " else "    ") +
                                "Offer this app to other apps"
                        )
                    },
                    onClick = {
                        openWith = !openWith
                        HandoffIntent.setOpenWithEnabled(context, openWith)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("What's new  ·  v${BuildConfig.VERSION_NAME}") },
                    onClick = { menuOpen = false; onShowWhatsNew() },
                )
            }
        }
    }
}

/**
 * A quiet line at the very bottom of the library: the app name and version,
 * tappable to open the "What's new" history. Deliberately low-contrast so it
 * reads as a footer, not a control.
 */
@Composable
private fun AppVersionFooter(onClick: () -> Unit) {
    Text(
        "Tiled Player  ·  v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun TabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Rating filter and count on the left; sort, view mode and "add" on the right. */
@Composable
private fun LibraryControlBar(
    minRating: Int,
    onMinRatingChange: (Int) -> Unit,
    summary: String,
    viewMode: LibraryViewMode,
    onViewModeChange: (LibraryViewMode) -> Unit,
    sortMode: LibrarySortMode,
    onSortModeChange: (LibrarySortMode) -> Unit,
    onGalleryImport: () -> Unit,
    onFilesImport: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            StarRow(rating = minRating, onRate = onMinRatingChange, starSize = 17.dp)
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box {
            IconButton(
                onClick = { sortMenuOpen = true },
                modifier = Modifier.semantics { contentDescription = "Sort" },
            ) {
                Text("⇅", style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                LibrarySortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                sortLabel(mode),
                                fontWeight = if (mode == sortMode) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = { onSortModeChange(mode); sortMenuOpen = false },
                    )
                }
            }
        }

        IconButton(
            onClick = { onViewModeChange(nextViewMode(viewMode)) },
            modifier = Modifier.semantics {
                contentDescription = "View: ${viewModeLabel(viewMode)}. Tap to switch."
            },
        ) {
            Text(viewModeGlyph(viewMode), style = MaterialTheme.typography.titleLarge)
        }

        Box {
            IconButton(
                onClick = { addMenuOpen = true },
                modifier = Modifier.semantics { contentDescription = "Add videos" },
            ) {
                Text("+", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Add from gallery") },
                    onClick = { addMenuOpen = false; onGalleryImport() },
                )
                DropdownMenuItem(
                    text = { Text("Add from files") },
                    onClick = { addMenuOpen = false; onFilesImport() },
                )
                DropdownMenuItem(
                    text = { Text("Find video on the web") },
                    onClick = { addMenuOpen = false; onOpenBrowser() },
                )
            }
        }
    }
}

@Composable
private fun StarRow(rating: Int, onRate: (Int) -> Unit, starSize: Dp = 16.dp) {
    Row {
        for (i in 1..5) {
            val filled = i <= rating
            Text(
                text = if (filled) "★" else "☆",
                color = if (filled) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = starSize.value.sp,
                modifier = Modifier
                    .clickable { onRate(if (rating == i) 0 else i) }
                    // A bit more than the glyph itself: a 1dp pad made each
                    // star a near-pixel-exact target sitting flush under the
                    // tap-to-select thumbnail above, with nothing to catch an
                    // imprecise tap before it silently changed the rating
                    // instead of selecting the card.
                    .padding(4.dp),
            )
        }
    }
}

/**
 * Centers empty-state content but biased toward the upper third rather than
 * dead center: on a tall phone, true center leaves an equally tall gap below
 * with nothing to balance it, which reads as unfinished rather than
 * intentional.
 */
@Composable
private fun EmptyStateColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.8f))
        content()
        Spacer(Modifier.weight(1.3f))
    }
}

@Composable
private fun PermissionGate(onGrantAccess: () -> Unit, onOpenSettings: () -> Unit, onImport: () -> Unit) {
    EmptyStateColumn {
        Text(
            "Browse this device",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Allow access to browse every video on the device with ratings, sorting and " +
                "filtering. The \"In player\" tab works without this — adding videos there " +
                "needs no permission at all.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrantAccess, modifier = Modifier.widthIn(min = 220.dp).height(52.dp)) {
            Text("Grant access")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onImport, modifier = Modifier.widthIn(min = 220.dp).height(52.dp)) {
            Text("Add videos without it")
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenSettings) {
            Text("Open app settings", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    EmptyStateColumn {
        Text(
            "No videos found on this device.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onImport) { Text("Add from gallery") }
    }
}

/** First-run state for the Online tab. */
@Composable
private fun EmptyOnline(onOpenBrowser: () -> Unit) {
    EmptyStateColumn {
        Text(
            "No bookmarked streams",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Videos bookmarked here play straight off the web — nothing is stored on the " +
                "device. Open a page in the built-in browser and start the video, and any " +
                "media it loads shows up ready to play, bookmark, or save.\n\n" +
                "You can also share a link to this app from Chrome.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenBrowser,
            modifier = Modifier.widthIn(min = 240.dp).height(52.dp),
        ) { Text("Open browser") }
    }
}

/** First-run state for the vault tab — this is the screen that explains the app. */
@Composable
private fun EmptyVault(onGalleryImport: () -> Unit, onFilesImport: () -> Unit) {
    EmptyStateColumn {
        Text(
            "Nothing in the player yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Add videos from the gallery and the app keeps its own copy inside itself. " +
                "Once they're added you can hide or delete the originals — these keep playing, " +
                "and they never show up in the gallery again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGalleryImport,
            modifier = Modifier.widthIn(min = 240.dp).height(52.dp),
        ) { Text("Add from gallery") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onFilesImport,
            modifier = Modifier.widthIn(min = 240.dp).height(52.dp),
        ) { Text("Add from files") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGridOrList(
    videos: List<VideoItem>,
    viewMode: LibraryViewMode,
    gridState: LazyGridState,
    largeListState: LazyListState,
    compactListState: LazyListState,
    ratings: Map<Long, Int>,
    onRate: (Long, Int) -> Unit,
    selected: Set<Long>,
    importedKeys: Set<String>,
    onTap: (VideoItem) -> Unit,
    onLongPress: (VideoItem) -> Unit,
) {
    // A device video counts as "in player" if its uri or its name+size matches
    // a copy — the name+size fallback survives the gallery reassigning ids.
    fun alreadyImported(item: VideoItem): Boolean =
        importedKeys.isNotEmpty() &&
            (item.uri.toString() in importedKeys ||
                VaultStore.identityKey(item.displayName, item.sizeBytes) in importedKeys)

    when (viewMode) {
        LibraryViewMode.CARDS -> LazyVerticalGrid(
            state = gridState,
            // 168dp only ever fit 2 columns even on a large phone (a ~452dp
            // content width just misses a 3rd 168dp column), so a big screen
            // was rendering the same 2-wide grid as a small one. 130dp lets
            // wide phones reach 3 (each card still renders ~136dp once 3
            // columns is chosen) while narrow phones stay at 2.
            columns = GridCells.Adaptive(minSize = 130.dp),
            contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(videos, key = { it.id }) { item ->
                VideoCard(
                    item = item,
                    rating = ratings[item.id] ?: 0,
                    onRate = { onRate(item.id, it) },
                    isSelected = item.id in selected,
                    selectionActive = selected.isNotEmpty(),
                    alreadyImported = alreadyImported(item),
                    onTap = { onTap(item) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
        LibraryViewMode.LARGE -> LazyColumn(
            state = largeListState,
            contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(videos, key = { it.id }) { item ->
                VideoCard(
                    item = item,
                    rating = ratings[item.id] ?: 0,
                    onRate = { onRate(item.id, it) },
                    isSelected = item.id in selected,
                    selectionActive = selected.isNotEmpty(),
                    alreadyImported = alreadyImported(item),
                    onTap = { onTap(item) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
        LibraryViewMode.LIST -> LazyColumn(
            state = compactListState,
            contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(videos, key = { it.id }) { item ->
                VideoListRow(
                    item = item,
                    rating = ratings[item.id] ?: 0,
                    onRate = { onRate(item.id, it) },
                    isSelected = item.id in selected,
                    selectionActive = selected.isNotEmpty(),
                    alreadyImported = alreadyImported(item),
                    onTap = { onTap(item) },
                    onLongPress = { onLongPress(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoCard(
    item: VideoItem,
    rating: Int,
    onRate: (Int) -> Unit,
    isSelected: Boolean,
    selectionActive: Boolean,
    alreadyImported: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val bitmap = rememberVideoThumbnail(item)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            ThumbnailImage(bitmap, item.source)
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                )
            }
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            ) {
                Text(
                    // A trimmed video shows the length it actually plays for,
                    // marked so it doesn't look like the duration is wrong.
                    when {
                        item.source == VideoSource.ONLINE -> "stream"
                        item.isTrimmed -> "✂ ${formatDuration(item.durationMs)}"
                        else -> formatDuration(item.durationMs)
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (selectionActive) {
                SelectionBadge(isSelected, Modifier.align(Alignment.TopStart).padding(6.dp))
            } else if (alreadyImported) {
                InPlayerBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .padding(top = 14.dp),
            ) {
                Text(
                    item.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            StarRow(rating = rating, onRate = onRate, starSize = 16.dp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoListRow(
    item: VideoItem,
    rating: Int,
    onRate: (Int) -> Unit,
    isSelected: Boolean,
    selectionActive: Boolean,
    alreadyImported: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            ThumbnailImage(rememberVideoThumbnail(item), item.source)
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(5.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            ) {
                Text(
                    formatDuration(item.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                if (alreadyImported) "${formatFileSize(item.sizeBytes)} · in player"
                else formatFileSize(item.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = if (alreadyImported) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            StarRow(rating = rating, onRate = onRate, starSize = 15.dp)
        }
        if (selectionActive) {
            Spacer(Modifier.width(8.dp))
            SelectionBadge(isSelected, Modifier)
        }
    }
}

@Composable
private fun SelectionBadge(isSelected: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Marks a device video that already has a copy inside the app. */
@Composable
private fun InPlayerBadge(modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Text(
            "✓ in player",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ThumbnailImage(bitmap: Bitmap?, source: VideoSource = VideoSource.DEVICE) {
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            // A bookmark has no thumbnail to wait for (fetching one would mean
            // hitting the network per row), so show a mark rather than a
            // spinner that would never resolve.
            if (source == VideoSource.ONLINE) {
                Text("☁", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * Bottom bar for a multi-selection. The tile stepper and Play are the same in
 * both tabs; the secondary action differs — copy into the app on the device
 * tab, delete the copies on the vault tab.
 */
@Composable
private fun SelectionBar(
    modifier: Modifier = Modifier,
    tab: LibraryTab,
    count: Int,
    tileCount: Int,
    onTileCountChange: (Int) -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onPlay: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel") }

                Spacer(Modifier.weight(1f))

                Text("Tiles", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = { onTileCountChange((tileCount - 1).coerceAtLeast(1)) },
                    modifier = Modifier.size(32.dp),
                ) { Text("−") }
                Text(
                    "$tileCount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = 28.dp),
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = { onTileCountChange((tileCount + 1).coerceAtMost(MAX_TILES)) },
                    modifier = Modifier.size(32.dp),
                ) { Text("+") }

                Spacer(Modifier.width(10.dp))
                Button(onClick = onPlay, colors = ButtonDefaults.buttonColors()) {
                    Text("Play ($count)")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (tab) {
                    LibraryTab.DEVICE ->
                        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                            Text("Add $count to player (keeps a copy)")
                        }
                    LibraryTab.ONLINE -> {
                        if (count == 1) {
                            OutlinedButton(onClick = onEdit) { Text("Rename") }
                        }
                        OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                            Text("Save $count")
                        }
                        OutlinedButton(onClick = onRemove) { Text("Remove") }
                    }
                    LibraryTab.PLAYER -> {
                        if (count == 1) {
                            OutlinedButton(onClick = onEdit) { Text("Edit") }
                        }
                        OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                            Text("Export $count")
                        }
                        OutlinedButton(onClick = onRemove) { Text("Remove") }
                    }
                }
            }
        }
    }
}

/**
 * Per-video actions for a vault entry, gathered in one place so the selection
 * bar stays a two-button row no matter how many of these there end up being.
 */
@Composable
private fun EditVideoDialog(
    item: VideoItem,
    busy: Boolean,
    onRename: () -> Unit,
    onTrim: () -> Unit,
    onShuffleThumbnail: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(item.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp)),
                ) {
                    ThumbnailImage(rememberVideoThumbnail(item), item.source)
                    if (busy) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(strokeWidth = 2.dp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append(formatDuration(item.durationMs))
                        if (item.isTrimmed) {
                            append(" trimmed from ")
                            append(formatDuration(item.fullDurationMs))
                        }
                        append(" · ")
                        append(formatFileSize(item.sizeBytes))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRename,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Rename") }
                OutlinedButton(
                    onClick = onTrim,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (item.isTrimmed) "Trim (trimmed)" else "Trim") }
                OutlinedButton(
                    onClick = onShuffleThumbnail,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Shuffle thumbnail") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Renames a vault entry. Worth having because the system photo picker returns
 * id-based filenames, so imports often arrive with a generated label.
 */
@Composable
private fun RenameDialog(
    item: VideoItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(item.id) { mutableStateOf(item.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Progress for an export out to a user-chosen folder. Same modal treatment as
 * the import sheet, and for the same reason: the copy runs only while the app
 * is in the foreground.
 */
@Composable
private fun ExportSheet(
    state: ExportState,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val failed = state.outcomes.filterIsInstance<ExportOutcome.Failed>()

    AlertDialog(
        onDismissRequest = { if (!state.running) onDone() },
        title = {
            Text(
                when {
                    state.running -> "Exporting…"
                    state.cancelled -> "Stopped"
                    else -> "Exported ${state.exported} of ${state.total}"
                }
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (state.running) {
                    Text(
                        state.currentName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.currentTotalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { state.currentFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "File ${state.completed + 1} of ${state.total} · " +
                            formatFileSize(state.currentCopiedBytes) +
                            if (state.currentTotalBytes > 0) " of ${formatFileSize(state.currentTotalBytes)}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Keep this screen open — exporting pauses if the app goes to the background.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        if (state.exported == 0) "Nothing was exported."
                        else "Copied to ${state.destinationName}. Trimmed videos export " +
                            "whole — the trim is only a playback range, not part of the file.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (failed.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Failed: " + failed.joinToString { "${it.name} (${it.reason})" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.running) {
                TextButton(onClick = onCancel) { Text("Stop") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
    )
}

/**
 * Blocks the screen while copying. Deliberately modal: the copy only runs
 * while the app is in the foreground, so the user needs to know to stay here.
 */
@Composable
private fun ImportSheet(
    state: ImportState,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onDeleteOriginals: () -> Unit,
) {
    val skipped = state.outcomes.filterIsInstance<ImportOutcome.Skipped>()
    val failed = state.outcomes.filterIsInstance<ImportOutcome.Failed>()

    AlertDialog(
        onDismissRequest = { if (!state.running) onDone() },
        title = {
            Text(
                when {
                    state.running -> "Adding to the player…"
                    state.cancelled -> "Stopped"
                    else -> "Added ${state.imported.size} of ${state.total}"
                }
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (state.running) {
                    Text(
                        state.currentName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.currentTotalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { state.currentFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "File ${state.completed + 1} of ${state.total} · " +
                            formatFileSize(state.currentCopiedBytes) +
                            if (state.currentTotalBytes > 0) " of ${formatFileSize(state.currentTotalBytes)}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Keep this screen open — copying pauses if the app goes to the background.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        if (state.imported.isEmpty()) {
                            "Nothing was copied."
                        } else {
                            "These now play from inside the app. You can hide or delete the " +
                                "originals in the gallery and they'll still work here."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (skipped.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Skipped: " + skipped.joinToString { "${it.name} (${it.reason})" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (failed.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Failed: " + failed.joinToString { "${it.name} (${it.reason})" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.running) {
                TextButton(onClick = onCancel) { Text("Stop") }
            } else {
                TextButton(onClick = onDone) { Text("Done") }
            }
        },
        dismissButton = {
            if (!state.running && state.imported.isNotEmpty()) {
                TextButton(onClick = onDeleteOriginals) { Text("Delete originals") }
            }
        },
    )
}
