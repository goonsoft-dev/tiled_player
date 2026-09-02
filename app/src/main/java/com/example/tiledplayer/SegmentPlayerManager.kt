package com.example.tiledplayer

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one ExoPlayer per pane. The [paneCount] panes are divided among the
 * chosen [uris] as evenly as possible (earlier videos take the extra pane when
 * it doesn't divide evenly); each video is then split into as many equal time
 * segments as it received panes, and one segment plays per pane on a loop.
 *
 * A single video therefore behaves like before (split into [paneCount] slices);
 * with several videos, each occupies a contiguous block of panes.
 *
 * All players are prepared up front and started together once every pane has
 * either buffered or given up (failed), so the tiles begin in sync. Because
 * segment lengths can differ between videos, the scrubber works in a 0..1
 * fraction of each pane's own segment rather than absolute milliseconds.
 *
 * Failure isolation: a bad file (unreadable, corrupt, unsupported codec, or a
 * device that's simply out of decoders) fails only the panes it occupies —
 * [failedPaneIndices] tracks which, and every other pane keeps playing. The
 * whole-session [onError] callback is reserved for the rare case where
 * nothing at all is playable (no videos chosen, or every single pane failed).
 */
class SegmentPlayerManager(
    private val context: Context,
    private val clips: List<PlaybackClip>,
    private val paneCount: Int,
) {
    /** Index-aligned with pane index; null means that pane failed to load. */
    val players = mutableListOf<ExoPlayer?>()

    /** Pane indices that failed to load or errored out during playback. */
    val failedPaneIndices = mutableSetOf<Int>()

    /** Representative segment length (first playable pane's), for the time labels. */
    var segmentDurationMs: Long = 0L
        private set

    /** Each pane's own segment length, so the scrubber can map a fraction back. */
    private var segmentDurations = LongArray(0)

    // A CoroutineExceptionHandler here is the last line of defense: without
    // one, any exception this scope's coroutine doesn't itself catch (e.g. an
    // unanticipated throw during initialization) would hit the thread's
    // default uncaught-exception handler and crash the whole app instead of
    // just failing this playback session.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        onErrorCb?.invoke(throwable.message ?: "Unexpected playback error.")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)
    private var readyFlags = BooleanArray(0)
    private var started = false
    private var released = false

    private var onReadyCb: (() -> Unit)? = null
    private var onErrorCb: ((String) -> Unit)? = null
    private var onPaneFailedCb: ((Int) -> Unit)? = null

    // Audio focus is handled here at the app level rather than per player
    // (handleAudioFocus on N players would make them steal focus from each
    // other). One request covers all panes; on loss we pause, on a duckable
    // loss we scale every pane's volume down via [duckFactor].
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var duckFactor = 1f
    private var paneVolumes = FloatArray(0)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pauseAll()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = isPlaying()
                pauseAll()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckFactor = 0.2f
                applyVolumes()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                duckFactor = 1f
                applyVolumes()
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    playAll()
                }
            }
        }
    }

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()
        // Play even if the request is denied - this is a foreground player.
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }

    private fun applyVolumes() {
        if (released) return
        players.forEachIndexed { i, p ->
            p?.volume = paneVolumes.getOrElse(i) { 0f } * duckFactor
        }
    }

    /**
     * A pane's plan: a playable slice of a video, or a known-bad source.
     *
     * [endMs] is null for "play the whole thing unclipped", which is what an
     * adaptive stream of unknown length gets — see [planSegments].
     */
    private sealed class SegmentSpec {
        data class Playable(
            val uri: Uri,
            val startMs: Long,
            val endMs: Long?,
            val headers: Map<String, String> = emptyMap(),
        ) : SegmentSpec()

        data class Failed(val reason: String) : SegmentSpec()
    }

    fun initialize(onReady: () -> Unit, onError: (String) -> Unit, onPaneFailed: (Int) -> Unit = {}) {
        onReadyCb = onReady
        onErrorCb = onError
        onPaneFailedCb = onPaneFailed
        scope.launch {
            if (clips.isEmpty()) {
                onErrorCb?.invoke("No video selected.")
                return@launch
            }
            val durations = withContext(Dispatchers.IO) { clips.map { playableLengthMs(it) } }
            if (released) return@launch
            // A remote clip with no readable duration still ends up playable —
            // planSegments() gives it one pane playing the whole stream
            // unclipped (see the isRemote branch there). Only bail here when
            // nothing at all, including that fallback, would have anything to
            // show; otherwise every HLS/adaptive stream or hotlink-protected
            // file would hit this fatal error before ever reaching that path.
            val anyPlayable = clips.indices.any { i ->
                val dur = durations[i]
                (dur != null && dur > 0L) || clips[i].isRemote
            }
            if (!anyPlayable) {
                onErrorCb?.invoke("Could not read any video's duration.")
                return@launch
            }
            buildPlayers(durations)
        }
    }

    /**
     * How much of [clip] actually plays: its trimmed length if it carries in/out
     * points, otherwise the file's full duration. Null when the file is
     * unreadable, or when the trim range came out empty.
     */
    private fun playableLengthMs(clip: PlaybackClip): Long? {
        val full = fetchDurationMs(clip.uri, clip.headers) ?: return null
        val start = clip.startMs.coerceIn(0L, full)
        val end = (clip.endMs ?: full).coerceIn(start, full)
        val length = end - start
        return if (length > 0L) length else null
    }

    /** Broad catch is deliberate: a single bad file's metadata probe (even a
     * native/OOM-ish failure) must never be able to take down the whole app. */
    private fun fetchDurationMs(uri: Uri, headers: Map<String, String> = emptyMap()): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            // Remote sources need the headers the URL was seen with, and the
            // string overload is the only one that takes them. This succeeds
            // for progressive files and fails for HLS/DASH manifests, which
            // [planSegments] handles as "can't be split".
            if (uri.scheme == "http" || uri.scheme == "https") {
                retriever.setDataSource(uri.toString(), headers)
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** panes[v] = how many panes video v is split into (sums to [paneCount]). */
    private fun distributePanes(videoCount: Int): IntArray {
        val result = IntArray(videoCount)
        if (paneCount <= 0 || videoCount <= 0) return result
        if (paneCount <= videoCount) {
            // Fewer panes than videos: show the first [paneCount] videos, whole.
            for (v in 0 until paneCount) result[v] = 1
        } else {
            val base = paneCount / videoCount
            val extra = paneCount % videoCount
            for (v in 0 until videoCount) result[v] = base + if (v < extra) 1 else 0
        }
        return result
    }

    /**
     * Builds the ordered per-pane segment list (video by video). A video
     * whose duration couldn't be read still gets its allocated pane count
     * (so the chosen layout's pane count never silently shrinks) — those
     * panes are just marked [SegmentSpec.Failed] instead of computing a
     * segment range from a duration that doesn't exist.
     */
    private fun planSegments(durations: List<Long?>): List<SegmentSpec> {
        val panesPerVideo = distributePanes(clips.size)
        val specs = mutableListOf<SegmentSpec>()
        for (v in clips.indices) {
            val k = panesPerVideo[v]
            if (k <= 0) continue
            val clip = clips[v]
            val dur = durations[v]
            if (dur == null || dur <= 0L) {
                if (clip.isRemote) {
                    // An adaptive stream (HLS/DASH) has no duration to probe
                    // up front, so there's nothing to divide. Rather than fail
                    // outright, give it one pane playing the whole stream and
                    // say plainly why the rest are empty — splitting it would
                    // also mean fetching the same stream k times over.
                    specs.add(SegmentSpec.Playable(clip.uri, 0L, null, clip.headers))
                    repeat(k - 1) {
                        specs.add(SegmentSpec.Failed("A live or adaptive stream can't be split into segments."))
                    }
                } else {
                    repeat(k) { specs.add(SegmentSpec.Failed("Could not read this video's duration.")) }
                }
                continue
            }
            // [dur] is the *trimmed* length, so segments divide the trimmed
            // range; the clip's in-point shifts them onto the real timeline.
            val offset = clip.startMs
            val seg = dur / k
            for (j in 0 until k) {
                val start = j * seg
                val end = if (j == k - 1) dur else (j + 1) * seg
                specs.add(SegmentSpec.Playable(clip.uri, offset + start, offset + end, clip.headers))
            }
        }
        return specs
    }

    private fun buildPlayers(durations: List<Long?>) {
        val specs = planSegments(durations)
        if (specs.isEmpty()) {
            onErrorCb?.invoke("Nothing to play.")
            return
        }
        readyFlags = BooleanArray(specs.size)
        // An unclipped stream (null end) has no known segment length; 0 here
        // means the scrubber falls back to another pane's length, and a
        // fraction seek on it is a no-op rather than a crash.
        segmentDurations = LongArray(specs.size) {
            (specs[it] as? SegmentSpec.Playable)
                ?.let { s -> s.endMs?.minus(s.startMs) } ?: 0L
        }
        segmentDurationMs = segmentDurations.firstOrNull { it > 0L } ?: 0L
        // Keep audio from a single (playable) tile so playback isn't a wall
        // of noise; if pane 0 itself failed, the first playable pane wins.
        val audibleIndex = specs.indexOfFirst { it is SegmentSpec.Playable }
        paneVolumes = FloatArray(specs.size) { if (it == audibleIndex) 1f else 0f }
        players.clear()
        repeat(specs.size) { players.add(null) }

        specs.forEachIndexed { i, spec ->
            when (spec) {
                is SegmentSpec.Failed -> failPane(i, spec.reason)
                is SegmentSpec.Playable -> createPlayer(i, spec)
            }
        }
    }

    /**
     * A media source factory that can reach the network with the headers a
     * sniffed URL needs. Cross-protocol redirects are allowed because CDNs
     * routinely bounce between http and https on the way to the real asset.
     * HLS/DASH manifests are recognized automatically as long as those media3
     * modules are on the classpath.
     */
    private fun mediaSourceFactory(headers: Map<String, String>): DefaultMediaSourceFactory {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
        if (headers.isNotEmpty()) http.setDefaultRequestProperties(headers)
        headers["User-Agent"]?.let { http.setUserAgent(it) }
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
    }

    /** Every ExoPlayer construction/preparation step is guarded: a decoder
     * that fails to initialize (exhausted hardware decoders, an OEM quirk
     * that throws synchronously instead of surfacing via onPlayerError,
     * device out of memory, ...) must fail only this one pane, never crash
     * the process or block the other panes from starting. */
    private fun createPlayer(i: Int, spec: SegmentSpec.Playable) {
        // Held outside the try so a failure partway through setup (after the
        // ExoPlayer itself was constructed, e.g. prepare() throwing) can still
        // release it — an orphaned, unreleased player would leak exactly the
        // decoder resource this whole guard exists to protect under pressure.
        var player: ExoPlayer? = null
        try {
            // A fresh renderers factory per player. Decoder fallback lets more
            // tiles play when the device runs out of hardware video decoders.
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
            val itemBuilder = MediaItem.Builder().setUri(spec.uri)
            // Only clip when there's a real end: an unclipped item is how an
            // adaptive stream of unknown length plays.
            if (spec.endMs != null) {
                itemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(spec.startMs)
                        .setEndPositionMs(spec.endMs)
                        .build()
                )
            }
            val item = itemBuilder.build()

            player = ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory(spec.headers))
                .build()
            player.apply {
                setMediaItem(item)
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = false
                volume = paneVolumes.getOrElse(i) { 0f }
                // Media attributes for correct routing; focus is managed by
                // this manager, not per player (see requestAudioFocus).
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ false
                )
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && i !in failedPaneIndices) {
                            readyFlags[i] = true
                            maybeStart()
                            // An unclipped pane (adaptive stream whose duration
                            // couldn't be probed up front) starts with a 0
                            // segment length, which is why the scrubber can't
                            // show/seek anything for it. Once the player itself
                            // has parsed the manifest, its real duration is
                            // usually known (VOD) — pick that up so the
                            // scrubber comes alive; a genuinely live stream
                            // keeps reporting C.TIME_UNSET and the scrubber
                            // stays at its 1ms floor, which is correct there.
                            if (segmentDurations.getOrElse(i) { 0L } <= 0L) {
                                val d = player?.duration ?: C.TIME_UNSET
                                if (d != C.TIME_UNSET && d > 0L) {
                                    segmentDurations[i] = d
                                    if (segmentDurationMs <= 0L) segmentDurationMs = d
                                }
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        failPane(i, error.errorCodeName)
                    }
                })
                prepare()
            }
            players[i] = player
        } catch (t: Throwable) {
            runCatching { player?.release() }
            failPane(i, t.message ?: "Couldn't start this pane.")
        }
    }

    /** Marks pane [i] as failed and unblocks the sync-start barrier for the
     * rest — a failed pane must never make every other pane wait forever. */
    private fun failPane(i: Int, reason: String) {
        if (i !in failedPaneIndices) {
            failedPaneIndices.add(i)
            onPaneFailedCb?.invoke(i)
        }
        if (i in readyFlags.indices) readyFlags[i] = true
        runCatching { players.getOrNull(i)?.stop() }
        maybeStart()
        // Only if truly nothing on screen is playable do we surface the
        // whole-session fatal error (there'd otherwise be nothing to show).
        if (started.not() && readyFlags.isNotEmpty() && failedPaneIndices.size == readyFlags.size) {
            onErrorCb?.invoke(reason)
        }
    }

    private fun maybeStart() {
        if (started || released) return
        if (readyFlags.isNotEmpty() && readyFlags.all { it } && failedPaneIndices.size < readyFlags.size) {
            started = true
            requestAudioFocus()
            players.forEach { it?.playWhenReady = true }
            onReadyCb?.invoke()
        }
    }

    /** Set a single pane's volume (0f..1f). */
    fun setPaneVolume(index: Int, volume: Float) {
        if (released) return
        val v = volume.coerceIn(0f, 1f)
        if (index in paneVolumes.indices) paneVolumes[index] = v
        players.getOrNull(index)?.volume = v * duckFactor
    }

    /**
     * Applies a playback speed to every pane at once (press-and-hold slow-mo).
     * Audio pitch is preserved; 1f restores normal speed.
     */
    fun setGlobalSpeed(speed: Float) {
        if (released) return
        val params = PlaybackParameters(speed.coerceIn(0.1f, 2f))
        players.forEach { it?.playbackParameters = params }
    }

    fun playAll() {
        if (started && !released) {
            requestAudioFocus()
            players.forEach { it?.playWhenReady = true }
        }
    }

    fun pauseAll() {
        if (!released) players.forEach { it?.playWhenReady = false }
    }

    /** Whether playback is currently running (sampled from the first playable tile). */
    fun isPlaying(): Boolean =
        started && !released && (players.firstOrNull { it != null }?.isPlaying ?: false)

    /** Current offset within the first playable pane's segment, for the time label. */
    fun currentOffsetMs(): Long =
        if (started && !released) players.firstOrNull { it != null }?.currentPosition ?: 0L else 0L

    /**
     * Seek every pane to the same fractional position within its own segment,
     * so panes with different segment lengths stay proportionally aligned.
     */
    fun seekToFraction(fraction: Float) {
        if (!started || released) return
        val f = fraction.coerceIn(0f, 1f)
        players.forEachIndexed { i, player ->
            if (player == null) return@forEachIndexed
            val seg = segmentDurations.getOrElse(i) { 0L }
            val target = (f * seg).toLong().coerceIn(0L, (seg - 1).coerceAtLeast(0L))
            runCatching { player.seekTo(target) }
        }
    }

    fun release() {
        if (released) return
        released = true
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        scope.cancel()
        players.forEach { runCatching { it?.release() } }
        players.clear()
    }
}
