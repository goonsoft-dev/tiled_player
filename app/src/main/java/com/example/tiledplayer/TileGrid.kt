package com.example.tiledplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

private const val MIN_WEIGHT = 0.12f
private const val MAX_ZOOM = 6f
private val HANDLE_TOUCH = 22.dp

/** A pane's user-applied zoom (1f = fit) and pan offset in view pixels. */
data class PaneTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero)

/**
 * Renders a pane [tree] full-screen, edge to edge. Pane at position p shows the
 * player given by [assignment]`[p]` (so panes can be swapped without moving the
 * players). Thin dividers on each split boundary resize neighbouring panes.
 *
 * Gestures per pane: one-finger drag pans, pinch zooms (independently per pane),
 * and dragging a pane's corner "⇄" grab handle onto another pane swaps the two
 * via [onSwap]. Press-and-hold fires [onHoldStart]/[onHoldEnd] (slow-mo). When
 * [controlsVisible] is true each pane shows a volume slider (top-left) and the
 * grab handle (top-right); [onUserInteraction] is invoked on divider/swap drags
 * so the caller can keep the controls from auto-hiding mid-use.
 */
@Composable
fun PaneLayout(
    tree: LayoutNode,
    players: List<ExoPlayer?>,
    assignment: List<Int>,
    failedPlayerIndices: List<Int> = emptyList(),
    volumes: List<Float>,
    onVolumeChange: (playerIndex: Int, volume: Float) -> Unit,
    onSwap: (posA: Int, posB: Int) -> Unit,
    controlsVisible: Boolean,
    controlTopInset: Dp = 0.dp,
    onTap: () -> Unit,
    onUserInteraction: () -> Unit = {},
    onHoldStart: () -> Unit = {},
    onHoldEnd: () -> Unit = {},
) {
    val paneBounds = remember { mutableStateMapOf<Int, Rect>() }
    val handleBounds = remember { mutableStateMapOf<Int, Rect>() }
    // Keyed by player index so a pane's zoom/pan follows its video across swaps.
    val transforms = remember { mutableStateMapOf<Int, PaneTransform>() }
    var dragSource by remember { mutableStateOf(-1) }
    var dragHover by remember { mutableStateOf(-1) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }

    fun hitTest(point: Offset): Int =
        paneBounds.entries.firstOrNull { it.value.contains(point) }?.key ?: -1

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    // Press-and-hold slow-mo: onLongPress fires while the finger
                    // is still down; onPress waits for the lift to end it. A
                    // short tap never reaches onLongPress, so onHoldEnd is a
                    // harmless no-op for plain taps.
                    onLongPress = { onHoldStart() },
                    onPress = {
                        tryAwaitRelease()
                        onHoldEnd()
                    }
                )
            }
    ) {
        RenderNode(
            node = tree,
            modifier = Modifier.fillMaxSize(),
            onInteract = onUserInteraction,
        ) { position, paneModifier ->
            key(position) {
            val playerIndex = assignment.getOrElse(position) { position }
            Tile(
                player = players.getOrNull(playerIndex),
                failed = playerIndex in failedPlayerIndices,
                volume = volumes.getOrElse(playerIndex) { 0f },
                onVolumeChange = { v -> onVolumeChange(playerIndex, v) },
                controlsVisible = controlsVisible,
                isDragSource = dragSource == position,
                isDropTarget = dragHover == position && dragSource != position,
                controlTopInset = controlTopInset,
                transform = transforms[playerIndex] ?: PaneTransform(),
                onTransformChange = { transforms[playerIndex] = it },
                // The grab handle drives the swap; it records its own root bounds
                // so the drag can be tracked across panes for hit-testing.
                swapHandleModifier = Modifier
                    .onGloballyPositioned { handleBounds[position] = it.boundsInRoot() }
                    .pointerInput(position, assignment.size) {
                        detectDragGestures(
                            onDragStart = { local ->
                                val base = handleBounds[position]?.topLeft
                                    ?: paneBounds[position]?.center ?: Offset.Zero
                                dragPointer = base + local
                                dragSource = position
                                dragHover = position
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                onUserInteraction()
                                dragPointer += amount
                                dragHover = hitTest(dragPointer)
                            },
                            onDragEnd = {
                                if (dragHover in assignment.indices && dragHover != dragSource) {
                                    onSwap(dragSource, dragHover)
                                }
                                dragSource = -1
                                dragHover = -1
                            },
                            onDragCancel = {
                                dragSource = -1
                                dragHover = -1
                            }
                        )
                    },
                modifier = paneModifier
                    .onGloballyPositioned { paneBounds[position] = it.boundsInRoot() },
            )
            }
        }
    }
}

@Composable
private fun RenderNode(
    node: LayoutNode,
    modifier: Modifier,
    onInteract: () -> Unit,
    leafContent: @Composable (position: Int, modifier: Modifier) -> Unit,
) {
    when (node) {
        is Leaf -> leafContent(node.index, modifier)
        is Split -> BoxWithConstraints(modifier) {
            val density = LocalDensity.current
            val totalWeight = node.weights.sum().coerceAtLeast(0.0001f)

            if (node.horizontal) {
                val totalPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                Row(Modifier.fillMaxSize()) {
                    node.children.forEachIndexed { i, child ->
                        RenderNode(child, Modifier.fillMaxHeight().weight(node.weights[i]), onInteract, leafContent)
                    }
                }
                var cumulative = 0f
                for (i in 0 until node.children.size - 1) {
                    cumulative += node.weights[i]
                    val boundary = with(density) { (cumulative / totalWeight * totalPx).toDp() }
                    DragHandle(
                        vertical = true,
                        modifier = Modifier
                            .offset(x = boundary - HANDLE_TOUCH / 2)
                            .fillMaxHeight()
                            .width(HANDLE_TOUCH),
                        onDrag = { d ->
                            onInteract()
                            shiftWeight(node.weights, i, i + 1, d / totalPx * totalWeight)
                        }
                    )
                }
            } else {
                val totalPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                Column(Modifier.fillMaxSize()) {
                    node.children.forEachIndexed { i, child ->
                        RenderNode(child, Modifier.fillMaxWidth().weight(node.weights[i]), onInteract, leafContent)
                    }
                }
                var cumulative = 0f
                for (i in 0 until node.children.size - 1) {
                    cumulative += node.weights[i]
                    val boundary = with(density) { (cumulative / totalWeight * totalPx).toDp() }
                    DragHandle(
                        vertical = false,
                        modifier = Modifier
                            .offset(y = boundary - HANDLE_TOUCH / 2)
                            .fillMaxWidth()
                            .height(HANDLE_TOUCH),
                        onDrag = { d ->
                            onInteract()
                            shiftWeight(node.weights, i, i + 1, d / totalPx * totalWeight)
                        }
                    )
                }
            }
        }
    }
}

/** Moves [delta] of weight from index [b] to index [a], respecting a minimum. */
private fun shiftWeight(weights: SnapshotStateList<Float>, a: Int, b: Int, delta: Float) {
    val newA = weights[a] + delta
    val newB = weights[b] - delta
    if (newA >= MIN_WEIGHT && newB >= MIN_WEIGHT) {
        weights[a] = newA
        weights[b] = newB
    }
}

@Composable
private fun DragHandle(vertical: Boolean, modifier: Modifier, onDrag: (Float) -> Unit) {
    Box(
        modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                onDrag(if (vertical) dragAmount.x else dragAmount.y)
            }
        },
        contentAlignment = Alignment.Center
    ) {
        if (vertical) {
            Box(Modifier.fillMaxHeight().width(1.5.dp).background(Color(0x55FFFFFF)))
        } else {
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(Color(0x55FFFFFF)))
        }
    }
}

@Composable
private fun Tile(
    player: ExoPlayer?,
    failed: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    controlsVisible: Boolean,
    isDragSource: Boolean,
    isDropTarget: Boolean,
    controlTopInset: Dp,
    transform: PaneTransform,
    onTransformChange: (PaneTransform) -> Unit,
    swapHandleModifier: Modifier,
    modifier: Modifier,
) {
    // Latest transform, readable from the gesture loop and the layout listener.
    val latest = remember { mutableStateOf(transform) }
    latest.value = transform
    var textureView by remember { mutableStateOf<android.view.TextureView?>(null) }

    // player.videoSize starts at VideoSize.UNKNOWN (0,0) until the decoder
    // confirms the real dimensions, so the very first frame(s) render with
    // baseCropScales' no-op (1,1) fallback — an uncropped stretch to the
    // pane's aspect ratio rather than the correct center-crop. Nothing else
    // was listening for the size becoming known, so the stretch only cleared
    // once some unrelated recomposition happened to re-read videoSize (in
    // practice, whatever triggered the next controls-visibility or divider
    // change) — occasionally seconds later. Reapplying the transform as soon
    // as the real size arrives fixes it immediately instead of by accident.
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                textureView?.let { tv -> applyTransform(tv, videoSize, latest.value.scale, latest.value.offset) }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // clipToBounds is essential: applyTransform scales the TextureView's content
    // well beyond the view, and without clipping that zoomed content spills over
    // neighbouring panes.
    Box(modifier.clipToBounds().background(Color.Black)) {
        if (failed) {
            Column(
                modifier = Modifier.matchParentSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⚠", color = Color(0xFFFFB4A9), style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Couldn't play this pane",
                    color = Color(0xFFCCCCCC),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else if (player != null) {
            // Inner box handles pinch-zoom and one-finger pan. detectTransformGestures
            // is touch-slop gated, so it only starts consuming once a real drag or
            // pinch is detected — a stationary tap passes through untouched to the
            // outer Box's tap-to-toggle-controls detector.
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(player) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val tv = textureView ?: return@detectTransformGestures
                            val cur = latest.value
                            val newScale = (cur.scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val offset = clampOffset(tv, player.videoSize, newScale, cur.offset + pan)
                            val next = PaneTransform(newScale, offset)
                            latest.value = next
                            onTransformChange(next)
                            applyTransform(tv, player.videoSize, next.scale, next.offset)
                        }
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        val tv = android.view.TextureView(ctx)
                        textureView = tv
                        // Belt-and-suspenders: also clip the transformed surface at
                        // the view level, since a scaled TextureView can otherwise
                        // draw outside its own bounds.
                        tv.clipToOutline = true
                        tv.outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                                outline.setRect(0, 0, view.width, view.height)
                            }
                        }
                        tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            tv.invalidateOutline()
                            (tv.tag as? ExoPlayer)?.let {
                                applyTransform(tv, it.videoSize, latest.value.scale, latest.value.offset)
                            }
                        }
                        tv
                    },
                    update = { tv ->
                        // Only (re)bind the surface when the player actually
                        // changes. Re-binding on every recomposition (e.g. while
                        // dragging a divider) resets the video output and makes
                        // playback stutter and then catch up.
                        if (tv.tag !== player) {
                            // A pane swap hands this TextureView to a different
                            // player. The outgoing player must release the surface
                            // first - otherwise both players briefly hold a Surface
                            // on the same SurfaceTexture and the second connect()
                            // throws (ERROR_CODE_FAILED_RUNTIME_CHECK).
                            (tv.tag as? ExoPlayer)?.clearVideoTextureView(tv)
                            tv.tag = player
                            player.setVideoTextureView(tv)
                        }
                        applyTransform(tv, player.videoSize, transform.scale, transform.offset)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (isDragSource) {
            Box(Modifier.matchParentSize().background(Color(0x55000000)))
        }
        if (isDragSource || isDropTarget) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(3.dp, if (isDropTarget) Color(0xFF69F0AE) else Color(0xFFB388FF))
            )
        }

        if (controlsVisible) {
            // Volume control, top-left corner (inset below the preset ribbon):
            // speaker icon toggles mute, slider sets the pane's volume.
            // Remembered per player so the restore level follows the video
            // across pane swaps.
            var lastAudible by remember(player) { mutableStateOf(1f) }
            if (volume > 0f) lastAudible = volume
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 6.dp, top = controlTopInset + 6.dp, end = 6.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    when {
                        volume <= 0f -> "🔇"
                        volume < 0.5f -> "🔉"
                        else -> "🔊"
                    },
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onVolumeChange(if (volume > 0f) 0f else lastAudible) }
                        .semantics { contentDescription = if (volume > 0f) "Mute pane" else "Unmute pane" }
                        .padding(4.dp)
                )
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.width(96.dp).height(28.dp)
                )
            }

            // Grab handle, top-right corner: drag onto another pane to swap.
            Box(
                modifier = swapHandleModifier
                    .align(Alignment.TopEnd)
                    .padding(start = 6.dp, top = controlTopInset + 6.dp, end = 6.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC000000))
                    .semantics { contentDescription = "Drag to swap this pane with another" }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("⇄", color = Color(0xFFB388FF))
            }
        }
    }
}

/**
 * The base zoom-to-fill scale on each axis: the video is scaled to cover the
 * view while preserving aspect ratio, so one axis is 1f and the other > 1f.
 */
private fun baseCropScales(tv: android.view.TextureView, videoSize: VideoSize): Pair<Float, Float> {
    val viewW = tv.width
    val viewH = tv.height
    val videoW = videoSize.width * videoSize.pixelWidthHeightRatio
    val videoH = videoSize.height.toFloat()
    if (viewW <= 0 || viewH <= 0 || videoW <= 0f || videoH <= 0f) return 1f to 1f
    val videoAspect = videoW / videoH
    val viewAspect = viewW.toFloat() / viewH.toFloat()
    return if (videoAspect > viewAspect) (videoAspect / viewAspect) to 1f
    else 1f to (viewAspect / videoAspect)
}

/** Clamps a pan [raw] offset so the zoomed content can't be dragged off the view. */
private fun clampOffset(
    tv: android.view.TextureView,
    videoSize: VideoSize,
    scale: Float,
    raw: Offset,
): Offset {
    val (bsx, bsy) = baseCropScales(tv, videoSize)
    val maxX = (tv.width * (bsx * scale - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (tv.height * (bsy * scale - 1f) / 2f).coerceAtLeast(0f)
    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
}

/**
 * Scales the TextureView's content to fill the view (zoom-to-fill, preserving
 * aspect ratio and cropping the overflow), then applies the user's [scale] zoom
 * and [offset] pan on top.
 */
private fun applyTransform(
    tv: android.view.TextureView,
    videoSize: VideoSize,
    scale: Float,
    offset: Offset,
) {
    val viewW = tv.width
    val viewH = tv.height
    if (viewW <= 0 || viewH <= 0) return
    val (bsx, bsy) = baseCropScales(tv, videoSize)
    val matrix = android.graphics.Matrix()
    matrix.setScale(bsx * scale, bsy * scale, viewW / 2f, viewH / 2f)
    matrix.postTranslate(offset.x, offset.y)
    tv.setTransform(matrix)
    tv.invalidate()
}
