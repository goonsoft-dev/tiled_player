package com.example.tiledplayer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay

private const val MAX_GRID_DIM = 5

private const val SLOW_MO_SPEED = 0.25f
private const val SLOW_MO_RAMP_STEPS = 6
private const val SLOW_MO_RAMP_STEP_MS = 20L

private sealed interface PlayerStatus {
    data object Loading : PlayerStatus
    data object Ready : PlayerStatus
    data class Error(val message: String) : PlayerStatus
}

/** Which ribbon entry is driving the current layout: the MxN grid stepper, or a named preset. */
private sealed interface PresetSelection {
    data object Grid : PresetSelection
    data class Named(val preset: LayoutPreset) : PresetSelection
}

@Composable
fun PlayerScreen(session: PlaybackSession, onExit: () -> Unit) {
    val context = LocalContext.current

    // Keep the screen awake for the whole playback session.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Ordered presets: built-in list re-ordered by whatever the user saved last
    // time via the reorder dialog. A SnapshotStateList so drag/move edits and
    // the ribbon that reads preset identity both see the same mutable list.
    val presets = remember(session) {
        mutableStateListOf<LayoutPreset>().apply {
            addAll(applyPresetOrder(buildPresetList(), LayoutPrefs.loadPresetOrder(context)))
        }
    }
    val savedSelection = remember(session) { LayoutPrefs.loadSelection(context) }
    // A saved layout only makes sense to restore if its pane count actually
    // matches what this session asked for — otherwise a single-pane layout
    // left over from an unrelated earlier session would silently win over an
    // explicit new tile-count request, dropping the extra selected videos
    // from playback instead of giving them their own panes. Same guard
    // [LayoutPrefs.loadAudio] already applies to per-pane audio, for the same
    // reason.
    val savedPaneCount = remember(session, savedSelection, presets) {
        savedSelection.presetName
            ?.let { name -> presets.firstOrNull { it.name == name } }
            ?.paneCount
            ?: (savedSelection.gridRows * savedSelection.gridCols).takeIf {
                savedSelection.gridRows in 1..MAX_GRID_DIM && savedSelection.gridCols in 1..MAX_GRID_DIM
            }
    }
    val useSavedSelection = savedPaneCount == session.tileCount
    // Default rows/cols close to the pane count chosen on the picker screen
    // (near-square), unless a previous session already picked a matching
    // grid, which wins.
    var gridRows by remember(session) {
        val fallback = sqrt(session.tileCount.toDouble()).roundToInt().coerceIn(1, MAX_GRID_DIM)
        mutableStateOf(if (useSavedSelection) savedSelection.gridRows else fallback)
    }
    var gridCols by remember(session) {
        val fallback = ceil(session.tileCount.toDouble() / gridRows).roundToInt().coerceIn(1, MAX_GRID_DIM)
        mutableStateOf(if (useSavedSelection) savedSelection.gridCols else fallback)
    }
    var selection by remember(session) {
        val restored = if (useSavedSelection) {
            savedSelection.presetName
                ?.let { name -> presets.firstOrNull { it.name == name } }
                ?.let { PresetSelection.Named(it) }
        } else null
        mutableStateOf<PresetSelection>(restored ?: PresetSelection.Grid)
    }
    // Persist whenever the active layout changes, so the next launch reopens
    // on the same one.
    LaunchedEffect(selection, gridRows, gridCols) {
        val presetName = (selection as? PresetSelection.Named)?.preset?.name
        LayoutPrefs.saveSelection(context, gridRows, gridCols, presetName)
    }
    val tree = remember(selection, gridRows, gridCols) {
        when (val s = selection) {
            is PresetSelection.Grid -> rectGridTree(gridRows, gridCols)
            is PresetSelection.Named -> s.preset.build()
        }.also { assignIndices(it) }
    }
    val paneCount = remember(tree) { countLeaves(tree) }

    // Alternative play mode: instead of each pane looping one fixed slice of
    // its video forever, it plays an ever-changing run of random-length
    // slices. Persisted like the layout selection, so relaunching resumes
    // whichever mode (and range) was last active.
    var randomModeOn by remember(session) { mutableStateOf(LayoutPrefs.loadRandomModeOn(context)) }
    var randomRangeSec by remember(session) { mutableStateOf(LayoutPrefs.loadRandomRangeSec(context)) }
    LaunchedEffect(randomModeOn, randomRangeSec) {
        LayoutPrefs.saveRandomModeOn(context, randomModeOn)
        LayoutPrefs.saveRandomRangeSec(context, randomRangeSec.first, randomRangeSec.second)
    }

    // Rebuild players when the number of panes changes, or when toggling
    // between loop and random mode (the clip plan itself differs); adjusting
    // the random range while already in random mode applies live instead
    // (below) rather than tearing every pane down.
    val manager = remember(session, paneCount, randomModeOn) {
        SegmentPlayerManager(
            context, session.clips, paneCount,
            mode = if (randomModeOn) {
                PlaybackMode.Random(randomRangeSec.first * 1000L, randomRangeSec.second * 1000L)
            } else {
                PlaybackMode.Loop
            },
        )
    }
    LaunchedEffect(manager, randomRangeSec) {
        manager.setRandomRange(randomRangeSec.first * 1000L, randomRangeSec.second * 1000L)
    }
    var status by remember(manager) { mutableStateOf<PlayerStatus>(PlayerStatus.Loading) }

    // Identifies the active layout for the purpose of remembering its audio.
    val layoutKey = remember(selection, gridRows, gridCols) {
        LayoutPrefs.layoutKey(
            gridRows, gridCols, (selection as? PresetSelection.Named)?.preset?.name,
        )
    }

    // Per-player volume, 0f..1f. Only the first pane starts audible.
    val volumes = remember(paneCount) {
        mutableStateListOf<Float>().apply { repeat(paneCount) { add(if (it == 0) 1f else 0f) } }
    }

    // Each layout remembers which tiles were unmuted in it. Switching layouts
    // restores that layout's own selection rather than carrying the previous
    // one over — "which tile plays audio" is a property of the arrangement,
    // and re-picking it on every switch was tedious.
    LaunchedEffect(layoutKey, paneCount, manager) {
        val restored = LayoutPrefs.loadAudio(context, layoutKey, paneCount)
            ?: List(paneCount) { if (it == 0) 1f else 0f }
        restored.forEachIndexed { i, v ->
            if (i in volumes.indices) volumes[i] = v
            manager.setPaneVolume(i, v)
        }
    }

    // Maps each pane position to the player index it shows. Swapping panes just
    // swaps two entries here; the players themselves never move.
    val assignment = remember(paneCount) {
        mutableStateListOf<Int>().apply { repeat(paneCount) { add(it) } }
    }

    // Panes that failed to load or errored out mid-playback (bad file,
    // unsupported codec, out of decoders, ...). Tracked separately from
    // [status] so a single bad pane shows its own error tile instead of
    // discarding every other pane that's playing fine.
    val failedPanes = remember(manager) { mutableStateListOf<Int>() }

    DisposableEffect(manager) {
        manager.initialize(
            onReady = { status = PlayerStatus.Ready },
            onError = { msg -> status = PlayerStatus.Error(msg) },
            onPaneFailed = { i -> if (i !in failedPanes) failedPanes.add(i) },
        )
        onDispose { manager.release() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, manager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> manager.pauseAll()
                Lifecycle.Event.ON_START -> manager.playAll()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler { onExit() }

    Box(Modifier.fillMaxSize()) {
        when (val s = status) {
            is PlayerStatus.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            is PlayerStatus.Error ->
                Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Couldn't play this video",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(s.message, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onExit) { Text("Back") }
                    }
                }

            is PlayerStatus.Ready ->
                ReadyContent(
                    manager = manager,
                    tree = tree,
                    presets = presets,
                    selection = selection,
                    onSelectPreset = { selection = PresetSelection.Named(it) },
                    onReorderPresets = { newOrder ->
                        presets.clear()
                        presets.addAll(newOrder)
                        LayoutPrefs.savePresetOrder(context, newOrder.map { it.name })
                    },
                    gridRows = gridRows,
                    gridCols = gridCols,
                    onSelectGrid = { selection = PresetSelection.Grid },
                    onGridRowsChange = { n ->
                        gridRows = n.coerceIn(1, MAX_GRID_DIM)
                        selection = PresetSelection.Grid
                    },
                    onGridColsChange = { n ->
                        gridCols = n.coerceIn(1, MAX_GRID_DIM)
                        selection = PresetSelection.Grid
                    },
                    volumes = volumes,
                    onVolumeChange = { i, v ->
                        if (i in volumes.indices) volumes[i] = v
                        manager.setPaneVolume(i, v)
                        LayoutPrefs.saveAudio(context, layoutKey, volumes.toList())
                    },
                    assignment = assignment,
                    onSwap = { a, b ->
                        if (a in assignment.indices && b in assignment.indices) {
                            val tmp = assignment[a]
                            assignment[a] = assignment[b]
                            assignment[b] = tmp
                        }
                    },
                    failedPanes = failedPanes,
                    randomModeOn = randomModeOn,
                    onToggleRandomMode = { randomModeOn = !randomModeOn },
                    randomRangeSec = randomRangeSec,
                    onRandomRangeChange = { randomRangeSec = it },
                    onExit = onExit,
                )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyContent(
    manager: SegmentPlayerManager,
    tree: LayoutNode,
    presets: List<LayoutPreset>,
    selection: PresetSelection,
    onSelectPreset: (LayoutPreset) -> Unit,
    onReorderPresets: (List<LayoutPreset>) -> Unit,
    gridRows: Int,
    gridCols: Int,
    onSelectGrid: () -> Unit,
    onGridRowsChange: (Int) -> Unit,
    onGridColsChange: (Int) -> Unit,
    volumes: List<Float>,
    onVolumeChange: (Int, Float) -> Unit,
    assignment: List<Int>,
    onSwap: (Int, Int) -> Unit,
    failedPanes: List<Int>,
    randomModeOn: Boolean,
    onToggleRandomMode: () -> Unit,
    randomRangeSec: Pair<Int, Int>,
    onRandomRangeChange: (Pair<Int, Int>) -> Unit,
    onExit: () -> Unit,
) {
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var sliderFraction by remember { mutableStateOf(0f) }
    var userSeeking by remember { mutableStateOf(false) }
    // Bumped on any control interaction to restart the auto-hide countdown,
    // so the controls don't vanish mid-adjustment.
    var interactionTick by remember { mutableStateOf(0) }
    // Press-and-hold slow-mo (all panes at once).
    var slowMo by remember { mutableStateOf(false) }
    // Height of the top preset ribbon, so per-pane corner controls can sit below
    // it instead of being hidden/blocked by it.
    var topBarHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val controlTopInset = with(density) { topBarHeightPx.toDp() }
    var showReorderDialog by remember { mutableStateOf(false) }
    var showRandomRangeDialog by remember { mutableStateOf(false) }

    // A plain var re-read every tick below, not captured once: an unclipped
    // pane (adaptive stream with no probe-able duration) starts at 0 and only
    // gets its real length once the player itself parses it, so freezing this
    // at composition time left the scrubber pinned to a useless ~1ms total.
    var segmentMs by remember { mutableStateOf(manager.segmentDurationMs.coerceAtLeast(1L)) }

    // Make sure the players' volumes match the sliders after a rebuild.
    LaunchedEffect(manager) {
        volumes.forEachIndexed { i, v -> manager.setPaneVolume(i, v) }
    }

    // Advance the scrubber while playing (unless the user is dragging it).
    LaunchedEffect(Unit) {
        while (true) {
            if (!userSeeking) {
                segmentMs = manager.segmentDurationMs.coerceAtLeast(1L)
                sliderFraction = (manager.currentOffsetMs().toFloat() / segmentMs).coerceIn(0f, 1f)
                isPlaying = manager.isPlaying()
            }
            delay(250)
        }
    }

    // Auto-hide the controls a few seconds after they appear while playing,
    // counting from the most recent interaction.
    LaunchedEffect(showControls, isPlaying, userSeeking, interactionTick) {
        if (showControls && isPlaying && !userSeeking) {
            delay(3500)
            showControls = false
        }
    }

    // Ramp all panes down to slow-mo while held, snap back on release.
    LaunchedEffect(slowMo, manager) {
        if (slowMo) {
            for (step in 1..SLOW_MO_RAMP_STEPS) {
                val t = step / SLOW_MO_RAMP_STEPS.toFloat()
                manager.setGlobalSpeed(1f + (SLOW_MO_SPEED - 1f) * t)
                delay(SLOW_MO_RAMP_STEP_MS)
            }
        } else {
            manager.setGlobalSpeed(1f)
        }
    }

    Box(Modifier.fillMaxSize()) {
        PaneLayout(
            tree = tree,
            players = manager.players,
            assignment = assignment,
            failedPlayerIndices = failedPanes,
            volumes = volumes,
            onVolumeChange = { i, v ->
                interactionTick++
                onVolumeChange(i, v)
            },
            onSwap = onSwap,
            controlsVisible = showControls,
            controlTopInset = controlTopInset,
            onTap = { showControls = !showControls },
            onUserInteraction = { interactionTick++ },
            onHoldStart = { slowMo = true },
            onHoldEnd = { slowMo = false },
        )

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { topBarHeightPx = it.size.height }
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .background(Color(0xCC000000))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onExit,
                    modifier = Modifier.semantics { contentDescription = "Exit player" },
                ) { Text("✕") }
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { interactionTick++; onGridRowsChange(gridRows - 1) },
                            enabled = gridRows > 1
                        ) {
                            Text("row −")
                        }
                        FilledTonalButton(
                            onClick = { interactionTick++; onGridRowsChange(gridRows + 1) },
                            enabled = gridRows < MAX_GRID_DIM
                        ) {
                            Text("row +")
                        }
                        FilterChip(
                            selected = selection is PresetSelection.Grid,
                            onClick = { interactionTick++; onSelectGrid() },
                            label = { Text("${gridRows}×$gridCols") }
                        )
                        FilledTonalButton(
                            onClick = { interactionTick++; onGridColsChange(gridCols - 1) },
                            enabled = gridCols > 1
                        ) {
                            Text("col −")
                        }
                        FilledTonalButton(
                            onClick = { interactionTick++; onGridColsChange(gridCols + 1) },
                            enabled = gridCols < MAX_GRID_DIM
                        ) {
                            Text("col +")
                        }
                    }
                    FilterChip(
                        selected = randomModeOn,
                        onClick = { interactionTick++; onToggleRandomMode() },
                        label = { Text("🎲 Random") }
                    )
                    if (randomModeOn) {
                        FilledTonalButton(
                            onClick = { interactionTick++; showRandomRangeDialog = true },
                            modifier = Modifier.semantics { contentDescription = "Random segment length" },
                        ) {
                            Text("${randomRangeSec.first}–${randomRangeSec.second}s")
                        }
                    }
                    presets.forEach { preset ->
                        FilterChip(
                            selected = selection is PresetSelection.Named && selection.preset === preset,
                            onClick = { interactionTick++; onSelectPreset(preset) },
                            label = { Text(preset.name) }
                        )
                    }
                }
                // Fixed at the far end of the ribbon (unlike the chips, which
                // scroll) so it stays reachable regardless of how many presets
                // there are — but away from the exit button, which used to sit
                // right next to it and was an easy accidental tap while
                // reaching for exit.
                FilledTonalButton(
                    onClick = { interactionTick++; showReorderDialog = true },
                    modifier = Modifier.semantics { contentDescription = "Reorder layouts" },
                ) {
                    Text("↕")
                }
            }
        }

        if (showReorderDialog) {
            ReorderPresetsDialog(
                presets = presets,
                onReorder = onReorderPresets,
                onDismiss = { showReorderDialog = false },
            )
        }

        if (showRandomRangeDialog) {
            RandomRangeDialog(
                rangeSec = randomRangeSec,
                onRangeChange = onRandomRangeChange,
                onDismiss = { showRandomRangeDialog = false },
            )
        }

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Drag ⇄ onto another pane to swap · pinch to zoom · drag to pan · hold for slow-mo",
                    color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                TimeControls(
                    isPlaying = isPlaying,
                    fraction = sliderFraction,
                    currentMs = (sliderFraction * segmentMs).toLong(),
                    totalMs = segmentMs,
                    onPlayPause = {
                        interactionTick++
                        if (isPlaying) manager.pauseAll() else manager.playAll()
                        isPlaying = !isPlaying
                    },
                    onSeekChange = { f ->
                        userSeeking = true
                        sliderFraction = f
                    },
                    onSeekFinished = {
                        interactionTick++
                        manager.seekToFraction(sliderFraction)
                        userSeeking = false
                    }
                )
            }
        }

        // Last child so the badge draws above the ribbon and scrubber.
        AnimatedVisibility(
            visible = slowMo,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Text(
                "${SLOW_MO_SPEED}×",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * Lets the user re-rank the named presets shown in the ribbon, one move at a
 * time (▲/▼ per row rather than free-form drag, since the panel is small and
 * this stays reliable with just touch taps). [onReorder] is only called once,
 * on Save, with the full new order.
 */
@Composable
private fun ReorderPresetsDialog(
    presets: List<LayoutPreset>,
    onReorder: (List<LayoutPreset>) -> Unit,
    onDismiss: () -> Unit,
) {
    val order = remember { mutableStateListOf<LayoutPreset>().apply { addAll(presets) } }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in order.indices) return
        val item = order.removeAt(index)
        order.add(target, item)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reorder layouts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                order.forEachIndexed { index, preset ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(preset.name, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(
                                onClick = { move(index, -1) },
                                enabled = index > 0,
                            ) { Text("▲") }
                            FilledTonalButton(
                                onClick = { move(index, 1) },
                                enabled = index < order.lastIndex,
                            ) { Text("▼") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onReorder(order.toList()); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Lets the user adjust the min/max length of a random-mode slice (see
 * [PlaybackMode.Random]). Applies live as the range slider moves — there's
 * nothing destructive to confirm or cancel, unlike [ReorderPresetsDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RandomRangeDialog(
    rangeSec: Pair<Int, Int>,
    onRangeChange: (Pair<Int, Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var range by remember { mutableStateOf(rangeSec.first.toFloat()..rangeSec.second.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Random segment length") },
        text = {
            Column {
                Text("${range.start.roundToInt()}s – ${range.endInclusive.roundToInt()}s")
                RangeSlider(
                    value = range,
                    onValueChange = { newRange ->
                        range = newRange
                        onRangeChange(newRange.start.roundToInt() to newRange.endInclusive.roundToInt())
                    },
                    valueRange = 1f..60f,
                )
                Text(
                    "Each pane plays a random clip in this length range from its video, " +
                        "then jumps to a new random clip when it ends.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun TimeControls(
    isPlaying: Boolean,
    fraction: Float,
    currentMs: Long,
    totalMs: Long,
    onPlayPause: () -> Unit,
    onSeekChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .displayCutoutPadding()
            .background(Color(0xCC000000))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilledTonalButton(onClick = onPlayPause) {
            Text(if (isPlaying) "❚❚" else "▶")
        }
        Text(formatTime(currentMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = fraction,
            onValueChange = onSeekChange,
            onValueChangeFinished = onSeekFinished,
            modifier = Modifier.weight(1f)
        )
        Text(formatTime(totalMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
