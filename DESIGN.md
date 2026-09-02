# Tiled Player — System-Agnostic Design Document

This document describes what Tiled Player *is* — its concepts, data model,
algorithms, and interaction rules — independent of any particular platform,
language, UI toolkit, or media framework. It exists so the app can be
rebuilt faithfully on a system that has none of the current implementation's
code: a different OS, a different UI framework, even a different media
pipeline (native decoders, WebCodecs, GStreamer, etc.).

The reference implementation is the Android app (Kotlin/Compose/Media3, in
`app/`). It is not the spec — this document is; where the two disagree, the
document wins.

**Scope note.** §1–§7 describe the tiled player itself, and a single-file web
implementation of that core once existed alongside the Android one. §8–§9 —
the local vault, trimming, export, streaming, bookmarks, and the in-app
browser — were added later for the Android app only, and are written to be
portable but are unimplemented anywhere else. A second implementation of
§1–§7 is still a faithful implementation of the player; it just won't have
the library around it.

## 1. Core concept

The app takes one or more source videos and displays them simultaneously as
a grid of independent, looping **tiles**. Each tile is not a full copy of a
video — it's a distinct **time segment** carved out of one source video, so
watching all tiles together is like watching every slice of a video (or
several videos) play out in parallel, forever, side by side.

Concretely:

1. The user picks N tiles worth of videos.
2. The panes (tile slots) are divided among the chosen videos.
3. Each video is cut into as many equal-duration segments as it received
   panes.
4. Every pane loops its own segment, starting all panes in sync.
5. The user can resize, rearrange, swap, zoom/pan, and mute/unmute panes
   live, and can switch between layout shapes without restarting playback
   (as long as the pane count doesn't change).

There is no "primary" video and no timeline that spans multiple videos —
each pane's segment is self-contained and loops independently once started
in sync with the others.

## 2. Data model

### 2.1 Layout tree

A layout is a binary-ish tree of two node kinds:

- **Leaf** — a single pane. Holds a 0-based **pane index**, assigned by a
  depth-first, top-to-bottom / left-to-right walk of the tree once it's
  built. This index is what everything else (players, volumes, transforms)
  keys off of.
- **Split** — divides its rectangle among its children, either as a row
  (children side by side) or a column (children stacked). Each child has a
  **weight** (a positive float); a child's share of the split's space is
  `weight / sum(all weights)`. Weights are mutable at runtime (dragging a
  divider changes them) and every split's weights must stay independently
  resizable — don't hard-code a shared weight array across splits.

This is a general tree, not just a grid: nesting splits expresses shapes
like "one big pane with a column of small panes beside it" or "sidebars
with a 2×2 grid between them." A plain even grid is just the special case
built by the row-distribution algorithm below, wrapped in one outer column
split.

**Minimum weight rule:** when resizing a split's children via a divider
drag, never let a child's weight fall below a small floor (reference value:
absolute weight `0.12`, evaluated against the split's own weight sum — so
what fraction of the split's space that floor represents depends on how
many siblings it has, e.g. two 1f-weighted children bottom out around 6% of
the split each). This keeps a pane from being dragged to zero size and
becoming unreachable.

### 2.2 Even-N grid algorithm

Given a pane count `n`, produce a layout where every row is completely
filled — never a ragged last row with empty cells:

```
rows = round(sqrt(n)), minimum 1
if n <= 3: rows = 1   # never split 1-3 panes into multiple rows
base = n div rows
extra = n mod rows
row_sizes = [base + 1 if row_index < extra else base   for row_index in 0..rows-1]
```

Earlier rows take the extra pane when `n` doesn't divide evenly. Examples:
`4 → [2,2]`, `5 → [3,2]`, `7 → [3,2,2]`, `8 → [3,3,2]`, `9 → [3,3,3]`.

Build the tree as a column split of row-splits, each row a horizontal split
of `row_sizes[i]` leaves, all weights defaulting to `1`. If there's only one
row, skip the outer column and use the row split directly; if `n == 1`,
it's a bare leaf (no split at all).

This is the layout behind the "N×N-ish" stepper control (see §5): stepping
`gridN` from 1 to 5 requests `gridN²` panes through this same algorithm — so
the stepper's "N×N" label is really "the even distribution of N² panes,"
which only looks like a literal square grid when it divides evenly.

### 2.3 Named presets

Beyond the grid stepper, offer a fixed catalog of hand-built layout shapes,
each a small tree built from the same Leaf/Split primitives, e.g.:

- 2 wide / 2 tall / 3 cols / 3 rows — trivial single-axis splits.
- Spotlight — one large pane (weight 3) beside a column of 3 small panes
  (weight 1 each): `row([Leaf, col(3 leaves)], weights=[3,1])`.
- Sidebar(s) + 2×2 — one or two tall panes flanking a nested 2×2 grid, e.g.
  `row([Leaf, col([row(2), row(2)]), Leaf], weights=[1, 2.6, 1])`.

Each preset has a fixed pane count (derived by counting its leaves) and a
display name. Selecting a preset with the *same* pane count as the current
layout must be a pure re-layout — it must not tear down and recreate the
underlying players (see §4); only a pane-count change should do that.

### 2.4 Pane assignment (position ↔ player)

Keep two separate indices per pane:

- **Position** — the leaf's place in the current layout tree (from §2.1's
  depth-first walk). This changes whenever the layout shape changes.
- **Player index** — which decoded segment is showing there. This is a
  simple array, `assignment[position] = playerIndex`, initialized to the
  identity mapping (`assignment[i] = i`).

Swapping two panes on screen means swapping two entries in `assignment` —
the underlying players/segments never move or get recreated. Per-pane state
that should *follow the video* across a swap (volume, zoom/pan transform)
must be keyed by **player index**, not position; state that's about the
*screen slot* (drag-hover highlight, this pane's divider) is keyed by
position.

## 3. Session planning: dividing videos across panes

Given `M` chosen source videos and `N` total panes:

```
distribute_panes(M, N):
    if N <= M:
        # Fewer panes than videos: show the first N videos, one pane each,
        # unsplit. (The picker UI should prevent this by forcing
        # tile_count = max(chosen_tile_count, video_count) so every picked
        # video gets at least one pane — see §5.1.)
        return [1]*N + [0]*(M-N)
    base = N div M
    extra = N mod M
    return [base + 1 if i < extra else base   for i in 0..M-1]
```

Earlier videos take the extra pane, same tie-breaking rule as §2.2.

Then, video by video, cut each into as many equal-length time segments as
it received panes, and emit them in that order (video 0's segments, then
video 1's, etc.) — this order is what pane index / leaf index refers to:

```
for each video v with duration D and pane_count k (from distribute_panes):
    if k == 0: skip
    seg = D / k
    for j in 0..k-1:
        start = j * seg
        end   = D if j == k-1 else (j+1) * seg
        emit segment(video=v, start=start, end=end)
```

The last segment of each video absorbs any rounding remainder (`end = D`)
rather than leaving a sliver untouched.

**Consequence:** segment lengths differ across panes whenever durations or
per-video pane counts differ. Any shared "global position" concept (the
scrubber, see §5.3) must therefore work in **fractional** terms (0.0–1.0 of
each pane's own segment), never absolute time, or panes will drift out of
alignment when seeking.

## 4. Playback engine requirements

- **One independent decoding/playback unit per pane.** Panes are not views
  onto a shared player — each has its own decode pipeline, its own current
  position, its own volume, and its own loop. If the platform's media APIs
  default to sharing decoder state across instances, that must be
  explicitly defeated (the Android implementation learned this the hard
  way: sharing one decoder-factory object across players made tiles bleed
  each other's frames — every player needs a *fresh* decoder resource).
- **Clip to segment, loop the clip.** Each pane's player is configured with
  a hard start/end clip at its segment boundaries and loops that clip
  indefinitely (repeat-one semantics), not the whole source video.
- **Synchronized start.** Prepare every pane's player up front; do not mark
  any of them playing until *all* have buffered/are ready. Then start them
  together in the same tick. This is what makes "all tiles loop in sync"
  true at t=0; without a barrier, faster-starting panes visibly lead.
- **Pane-count changes rebuild the engine; layout-shape changes don't.**
  Changing how many panes exist requires tearing down and rebuilding every
  player (segment boundaries change). Switching to a different layout
  shape with the *same* pane count must reuse the existing players
  untouched — only the on-screen geometry changes.
- **Global speed control** (for press-and-hold slow-motion, see §5.4)
  applies a playback-rate multiplier to every pane at once, and should ramp
  smoothly rather than snap (reference: ~6 steps over ~120ms) so the effect
  reads as a slow-down rather than a stutter; release should snap back to
  1× immediately.
- **Decoder budget is a real, platform-specific ceiling.** Devices/browsers
  can only run so many simultaneous hardware video decoders. Design for
  graceful degradation (software-decoder fallback if available) and warn
  the user in the picker UI that very high tile counts may leave some
  panes black — don't treat that as a bug to "fix" by silently capping tile
  count; let the user choose and learn the limit on their own hardware.

### 4.1 Audio model

- Exactly **one pane starts audible** (player index 0, i.e. the first
  segment of the first chosen video); every other pane starts muted. This
  is a deliberate default — N simultaneous audio tracks is unlistenable —
  not an oversight.
- Volume is per **player index** (0f–1f), independent of on-screen
  position, and must survive pane swaps (see §2.4).
- Audio focus / interruption handling (phone calls, other apps' media,
  system ducking) should be requested **once at the session level**, not
  once per pane — N independent players each requesting focus will fight
  each other for it. On a transient interruption, pause every pane and
  resume every pane together when focus returns; on a "duck" style
  interruption, scale every pane's *effective* volume down by a shared
  ducking factor (reference: 0.2×) without touching the user's per-pane
  slider values, and restore to full when the interruption ends.

### 4.2 Remembering audio per layout

Which tile is unmuted is a property of the *arrangement*, not of the
session: in a 2×2 grid the interesting tile may be bottom-right, while in
a spotlight layout it's the big one. Carrying one layout's choice over to
the next means re-picking it on every switch.

- Persist the per-pane volumes **keyed by layout identity** — a named
  preset by name, the stepper grid by its dimensions. Two layouts with the
  same pane count still get separate entries, which is the whole point.
- Restore on entering a layout (including at session start); fall back to
  the §4.1 default (player index 0 audible) when that layout has no saved
  entry.
- Store volumes by **player index**, matching §4.1, so a restored
  selection still follows the video across pane swaps.
- Discard a saved entry whose length doesn't match the current pane count
  rather than restoring it onto the wrong panes — the videos or tile count
  changed since it was written.

## 5. Screens and interaction

### 5.1 Home screen: the library

The app opens on a library, not a picker. (An earlier design opened the
platform's video picker immediately; that made sense when the app could only
play what you handed it, and stopped making sense once it kept a library of
its own — see §8.)

Three lists, switched by a tab control, because they differ in *durability*
and that difference is the thing the user needs to see:

- **Player** — the app's own copies (§8). Permanent, permission-free, and the
  only ones that survive the original being hidden or deleted.
- **Online** — bookmarked stream URLs (§9.2). Nothing stored; breaks when the
  site changes.
- **Device** — the platform's media library. Needs a media permission, and
  only to browse; playing and importing do not.

Shared rules across all three:

- Tapping a video plays it immediately in one tile. Long-press starts a
  multi-select, which is what feeds tiled playback.
- The multi-select bar carries a **tile-count** control (reference range:
  1–16) and the tab-appropriate secondary action (import, download, export,
  remove).
- Always give every chosen video **at least one pane**:
  `effective_tile_count = max(chosen_tile_count, number_of_videos_picked)`.
  Silently dropping a picked video because there weren't enough tiles for it
  would be surprising.
- Sorting, star ratings and a minimum-rating filter apply to every list;
  ratings are keyed by an id space shared across all three sources (§8.2).
- Filter any file-picker to formats the playback engine can actually decode
  (an explicit allow-list, not a catch-all "any video" filter), so the user
  never picks something that then fails to play.
- Only prompt for the media permission when the user actually opens the
  device list. An app whose main library needs no permission shouldn't ask
  for one on launch.

### 5.2 Player screen — layout controls

- A top control ribbon offers two ways to pick a layout, both switchable
  live without stopping playback (as long as pane count is unchanged, see
  §4):
  - A **grid stepper**: −/+ buttons around a label showing the current
    `N` (reference range 1–5, i.e. up to 25 panes via §2.2), plus a chip
    that both displays the current choice and re-selects "grid mode" if a
    named preset was active.
  - A horizontally-scrollable row of **named preset** chips (§2.3), one
    tap each.
  - Only one of {grid, a specific preset} is "active" at a time; switching
    either control deactivates the other.
- **Reordering the presets is a first-class action**, not a hidden gesture:
  expose an explicit, always-reachable control (not itself inside the
  horizontally-scrolling chip row — if it scrolls with the chips, it
  becomes unreachable once there are enough presets to fill the ribbon) that
  opens a reorder view. A simple, reliable pattern: list the presets by
  name with an up/down control per row; save on confirm. (Free-form drag
  reordering is a reasonable upgrade but is not required for correctness —
  don't let it block shipping the feature.)
- **Persist across launches:**
  - The last active layout selection — which control was active (grid vs.
    a specific named preset) and its value (the grid's `N`, or the
    preset's identity) — so relaunching the app resumes on the same
    layout instead of resetting to a hardcoded default.
  - The user's custom preset order, applied every time the preset list is
    built. Store presets by a stable identity (name is sufficient if names
    are unique and stable); when applying a saved order, presets present in
    the saved order but no longer offered are dropped, and presets newly
    offered but absent from the saved order are appended at the end in
    their built-in order — don't crash or reset to default on a partial
    match.
  - On first launch (no saved layout), default the grid stepper near the
    picker's chosen tile count rather than always starting at 1×1: e.g.
    `N = round(sqrt(chosen_tile_count))`, clamped to the stepper's range.

### 5.3 Player screen — transport

- A scrubber expressed as a **0.0–1.0 fraction**, not absolute time (per
  §3's segment-length note). Seeking maps the fraction back to each pane's
  own segment length independently, so panes with different segment
  durations stay proportionally aligned rather than drifting.
- Play/pause is global (applies to every pane at once).
- Poll actual playback position at a modest interval (reference: 4×/sec) to
  drive the scrubber while playing, and suspend that sync while the user is
  actively dragging the scrubber.
- On app/window backgrounding, pause every pane; on foreground, resume —
  don't leave panes silently playing off-screen, and don't require the
  user to manually re-press play after switching back.

### 5.4 Per-pane gestures

All of the following operate per pane, independent of the others:

- **Tap** (not on a control) toggles the whole control chrome
  (ribbon + transport + per-pane overlays) visible/hidden. Controls should
  also **auto-hide** a few seconds after last shown while actively playing
  (reference: 3.5s), restarting that timer on every control interaction —
  but never auto-hide while the user is mid-interaction (e.g. dragging the
  scrubber).
- **Pinch / wheel zoom**, independently per pane, within a fixed range
  (reference: 1×–6×), anchored at the gesture's focal point.
- **One-finger drag pans** the zoomed content, clamped so the video can
  never be dragged to reveal empty space beyond its edges — compute the
  pan clamp from the pane's actual crop-to-fill scale factors, not a fixed
  margin.
- **At 1× (not zoomed), a one-finger drag must not pan** — let it fall
  through to whatever gesture the platform naturally resolves it to
  instead (e.g. a divider drag if it started on one, or nothing). Treating
  every drag as a pan even at rest makes plain taps feel unreliable if
  the gesture recognizer isn't slop-gated (see the implementation note
  below).
- **A dedicated grab handle per pane, dragged onto another pane, swaps
  the two** (updates `assignment`, §2.4) — this is deliberately a separate
  affordance from the pan gesture, not "drag the video itself to swap,"
  so pan and swap never conflict.
- **Press-and-hold anywhere activates slow-motion for every pane at once**
  (reference: 0.25× speed, ramped per §4) for the duration of the hold,
  snapping back to 1× on release.
- **Divider drag** (on a split boundary) resizes the two adjacent
  children's weights live (§2.1), respecting the minimum-weight floor.
- A visible **volume control** per pane (mute toggle + level), remembering
  the last nonzero level so un-muting restores it rather than jumping to
  full.
- Clip each pane's rendered content strictly to its own rectangle. A
  zoomed pane's content must never visually bleed into a neighboring pane
  — clip at both the layout-container level and, if the rendering surface
  itself can draw outside its bounds under transform, at the surface level
  too.

**Implementation note (gesture disambiguation):** if you hand-roll gesture
detection instead of using a platform gesture API with built-in slop
handling, gate any "consume this pointer sequence" decision on the
movement actually exceeding a touch-slop threshold — not on any nonzero
movement. Consuming eagerly on sub-pixel jitter during what's meant to be a
stationary tap will silently eat taps before they reach the tap-to-toggle
handler, and the symptom ("taps don't work in the middle of a pane, but
work fine near the edges/dividers") is easy to misdiagnose as something
else.

## 6. Cross-cutting constants worth keeping consistent

These aren't load-bearing in a deep sense, but two independent
implementations should agree on them so behavior doesn't diverge:

| Constant | Reference value | Meaning |
|---|---|---|
| Max tiles (picker) | 16 | Cap on the picker screen's tile-count control |
| Grid stepper range | 1–5 (→ 1–25 panes) | Live `N` range for the in-player grid stepper |
| Min split weight | 0.12 (absolute, vs. that split's weight sum) | Divider-drag floor per §2.1 |
| Max zoom | 6× | Pinch/wheel zoom ceiling per §5.4 |
| Controls auto-hide delay | 3.5s | Per §5.3/§5.4 |
| Slow-mo speed | 0.25× | Per §4/§5.4 |
| Slow-mo ramp | ~6 steps / ~120ms total | Per §4 |
| Audio duck factor | 0.2× | Per §4.1 |

## 7. Suggested architecture (implementation-agnostic)

Regardless of platform, keep these as separately testable layers:

1. **Pure layout math** — §2.1–2.2 tree building, leaf indexing, row
   distribution. No dependency on rendering, players, or UI state. Easy to
   unit-test exhaustively (every `n` from 1 to the max tile count).
2. **Pure session planning** — §3's pane distribution and segment
   splitting. Also pure functions of (durations, pane count) → segment
   list; also easy to exhaustively test.
3. **Playback engine** — owns the per-pane player instances, the
   synchronized-start barrier, audio focus, and global speed. Exposes a
   small imperative surface (play/pause all, seek-to-fraction, set pane
   volume, set global speed) and a couple of callbacks (ready, error). Has
   no opinion about layout shape at all — it only knows pane count.
4. **Layout/session UI state** — current selection (grid vs. preset),
   assignment array, per-pane volumes and zoom/pan transforms, persisted
   preferences (§5.2). This is what glues layers 1–3 to the screen.
5. **Rendering/gesture layer** — turns the layout tree into an actual
   on-screen split view with dividers, per-pane gesture handling, and
   swap/zoom/pan visuals. This is the layer most tied to the platform's UI
   toolkit, and the only one worth rewriting wholesale per platform; layers
   1–4 should be portable almost as-is.

This separation is also why pane-count-driven engine rebuilds (§4) and
layout-shape-only re-layouts must be kept distinct in whatever "what
changed, what needs to rebuild" logic layer 4 uses — conflating them either
tears down playback unnecessarily on every layout tweak, or fails to
rebuild when it actually must.

## 8. Local video vault (imported copies)

Motivation: on some platforms the app cannot rely on a video staying
reachable at the address it was picked from. Two independent cases matter:

- The user **hides** or moves a video into a system "private/secure
  folder", after which the media index no longer returns it and any
  previously granted per-file read grant stops resolving.
- The app runs from a **restricted launcher context** (e.g. ColorOS's
  hidden-app drawer) that is deliberately denied access to the platform's
  secure-storage area, so files placed there are unreachable *by design*.

In both cases a player that merely holds a reference to the original file
breaks. The fix is for the app to own a copy.

### 8.1 Requirements

- **Copy, don't link.** On import, stream the source's bytes into
  app-private storage. After a successful import, playback must never
  touch the original again — deleting or hiding it changes nothing.
- **Private and unindexed.** Copies live where the platform's media
  scanner does not look, so they never reappear in the gallery. Store them
  under **opaque names derived from an internal id**, never the original
  filename, so a directory listing reveals nothing.
- **No permissions required.** Listing and playing the vault must work
  with zero media permissions granted. Adding to it should also be
  possible permission-free wherever the platform offers a system picker
  that returns a grant per chosen file. Media-library permission is then
  needed only for the optional "browse everything on the device" view.
- **Excluded from backup and device-to-device transfer.** The copies exist
  precisely so they stay on one device.
- **Crash-safe imports.** Write to a scratch name and commit atomically
  (rename + index write). A copy interrupted by process death must leave
  nothing referenced by the index; sweep orphaned scratch files at
  startup. Never half-commit an entry.
- **Space-aware.** Check free space before starting, and re-check
  periodically for sources that don't declare their size. Fail that one
  file with a message, not the batch.
- **Per-file failure isolation**, mirroring §4's rule for panes: one
  unreadable source marks itself failed and the rest of the batch
  continues.

### 8.2 Index and identity

Keep a small persisted index (id, stored filename, display label,
duration, size, import time, dimensions, and the source address it came
from). On load, drop entries whose file is missing rather than showing
dead rows.

Vault ids must coexist with media-library ids in the same rating,
selection, and thumbnail-cache maps. Reference approach: allocate vault
ids from a **negative** counter, since platform media ids are positive —
this makes collisions structurally impossible without a wrapper type.

The remembered source address is used **only** to mark "already imported"
in the device browser and to skip re-imports; nothing about playback may
depend on it, since it is expected to go stale.

Note that a system photo picker may return an anonymized, id-based
filename rather than the real one. Detect that (a name whose stem is all
digits), substitute a readable generated label, and offer a **rename**
action so the user is never stuck with it.

### 8.3 Thumbnails

Media-library thumbnails come from the platform's thumbnail service; vault
files have no such service. Extract a poster frame at import time (a
little way in, so it isn't a black fade-in) and cache it to disk beside
the copy, regenerating lazily if it's missing.

### 8.4 Deleting the originals

Offer this only as an explicit action after a successful import, and route
it through the platform's own delete-confirmation flow — the app must
never delete a user's media silently. It applies only to media-library
originals; files that arrived through a document picker are the user's to
remove themselves. Say so rather than failing silently.

### 8.5 Screen privacy

An app used this way should default to excluding its window from
screenshots, screen recording, and the task-switcher preview — a hidden
app whose contents show up in the recents list isn't hidden. Make it a
visible, user-toggleable setting, applied before the first frame is drawn.

### 8.6 Background behavior

Copying large files can take a while. Prefer running the copy in the
foreground with the screen kept awake and a modal progress UI, over a
background service: on platforms where a background service demands a
persistent notification, that notification defeats the purpose of a hidden
app. Tell the user to stay on the screen, make the operation cancellable,
and make interruption cheap (costs time, never data — see §8.1).

### 8.7 Non-destructive trim

Trimming an imported video should set **in/out points on the entry**, not
rewrite the file. The copy may be the only one left, so an edit that can
destroy footage is the wrong default; recording a range is instant,
reversible, and costs nothing.

- The trimmed range becomes the video's timeline everywhere downstream:
  the segment planner (§3) divides *the trimmed range* across that video's
  panes, and the library shows the trimmed length as its duration.
- Mark trimmed entries in the list, so a shortened duration doesn't read
  as a bug.
- Keep the handles at least a second apart so a trim can never produce an
  empty clip, and offer a reset back to the full length.
- Say plainly in the UI that the file isn't re-encoded and no space is
  freed — "trim" normally implies the file shrinks, and here it doesn't.

A destructive "rewrite and reclaim the space" variant is a reasonable
addition on top, but it should be an explicit second action, never the
behavior of the trim control itself.

### 8.8 Choosing the poster frame

The frame picked at import time is a guess and is sometimes useless (a
black fade-in, a title card). Offer a per-video action that re-picks it
from a random position within the trimmed range.

The important detail: seek **exactly**, not to the nearest keyframe. A
typical encode has a keyframe every several seconds, so random positions
snapped to the nearest one keep landing on the same handful of frames and
the action appears to do nothing. Exact seeking decodes forward from the
preceding keyframe — slower, but it actually changes the picture.

Note also that any thumbnail cache must be keyed by something that
changes when the frame is replaced (the file's mtime works), or the new
frame will sit on disk unseen behind a stale cache hit.

### 8.9 Exporting back out

The vault's one structural weakness is that app-private storage dies with the
app: uninstalling, or the OS clearing app data, takes every copy with it, and
by then the originals may be long gone. So the vault must have an exit.

- Export selected videos, or the whole library, into a folder the user picks
  through the platform's own folder-picker — not a hard-coded path.
- Never overwrite silently: if a name is taken, disambiguate it.
- Rebuild a file extension from the stored file rather than trusting the
  display name, which may have been renamed or generated without one.
- Delete a partially-written export on failure or cancellation. A truncated
  file looks like a successful export until someone tries to play it.
- Say in the completion message that **trimmed videos export whole** — the
  trim is a playback range (§8.7), not part of the file.

## 9. Remote sources: streaming and bookmarks

Not every video needs to be stored. A URL can be played directly, which means
watching (and tiling) something without it ever landing on the device — the
right default when storage is finite or a local copy is unwanted.

### 9.1 Playing a URL

- Feed the player http(s) URLs the same way as local files; the only real
  difference is that **headers matter**. Carry the `Referer`, `Cookie`, and
  `User-Agent` a URL was discovered with, and send them on every request:
  most CDNs answer a bare request with 403, so a URL without its headers is
  not a usable source.
- Allow cross-protocol redirects. CDNs bounce between http and https on the
  way to the real asset.
- **Adaptive streams (HLS/DASH) have no duration to probe up front**, so
  there's nothing for the segment planner (§3) to divide. Give such a source
  one pane playing the whole stream and mark its other allocated panes with a
  clear reason, rather than failing the session. Splitting it would also mean
  fetching the same stream once per pane.
- Note the bandwidth consequence of §3 for remote sources generally: N panes
  of one remote video means N concurrent ranged requests for the same file.
  That's acceptable for a deliberate choice, but it's a reason to prefer
  downloading a video that will be tiled repeatedly.

### 9.2 Bookmarks

A bookmark is a URL plus the headers needed to fetch it — a reference, with
the opposite durability guarantee to a vault copy: it breaks the moment the
site rotates its URLs. Keep the two lists visibly separate so that difference
is legible, and offer "download this bookmark into the vault" as the one-way
door between them.

Re-bookmarking a URL already held should refresh its headers rather than
duplicate it: the URL usually still works when the cookie has expired.

### 9.3 Finding video on a page

Only a small share of web video is a plain link another app will hand over.
The rest is fetched by a player script, so the only reliable way to find the
real media URL is to **watch the requests a page makes** while it loads and
plays, and record each media-looking request together with its headers.

Classify by URL extension first, then by the request's `Accept` header for
extensionless CDN URLs. Treat a new document as invalidating the previous
page's findings.

Limits worth stating in the UI rather than hiding:

- `blob:` sources (MSE players assembling segments in JS) expose no fetchable
  URL and cannot be captured.
- DRM-protected video is not playable outside its own player.
- An adaptive stream can be watched and bookmarked but not saved as one file:
  that would mean downloading thousands of segments and re-muxing them.

### 9.4 Being an external player

Register as a handler for other apps' "open this video" intents: a media URL
or local video to play, and shared text containing a URL. A shared link that
*isn't* a direct media URL is a page, so the right response is to open it in
the built-in browser and look for video on it (§9.3), not to fail.

**Keep this switchable, and treat it as a disclosure.** Registering for these
intents puts the app's name and icon in the system share sheet and in other
apps' "Open with" lists, where anyone sharing anything will see it — which
directly undercuts §8's premise of an app that isn't meant to be found. Put
the filters on a component that can be disabled at runtime, and expose that
as a setting.

### 9.5 What a usable in-app browser actually needs

An embedded web view gives you a rendering engine, not a browser. Ship only
the engine and pages don't merely feel limited — they feel *broken*, because
the failures are silent: a tap does nothing at all. Each of these is required
before the browser is usable, and each fails invisibly when missing.

- **Multiple windows.** `window.open` and `target="_blank"` are dropped unless
  the view is told to support multiple windows *and* given a handler to place
  the new window. This is the single most common cause of "the play button
  does nothing".
  A new window's view must be handed over **un-navigated** — loading anything
  into it first (even a start page) is rejected by the engine.
- **Fullscreen.** A page's fullscreen request hands back a view to host; with
  no handler, the fullscreen button silently fails. Host it over the whole
  screen, and route Back to exiting fullscreen before anything else.
- **Downloads.** A download link produces no navigation, so without a download
  handler the tap is ignored entirely.
- **JavaScript dialogs.** `alert`/`confirm`/`prompt` are swallowed by default,
  hanging any page that gates playback behind one.
- **Non-http schemes.** App links (`intent:`, `market:`, `tel:`) can't load.
  Most `intent:` URLs carry a fallback web URL meant for exactly this case;
  follow it, and say something rather than dead-ending when there isn't one.
- **Ordinary chrome**: forward as well as back, reload/stop, load progress,
  and a desktop-user-agent toggle for sites that serve a crippled mobile page.

### 9.6 Tabs and session

- A handful of tabs (reference: 5). Pop-ups open as tabs, which is also the
  natural place for the tab cap to act as a pop-up limit.
- Tabs must **outlive the browser screen**: leaving to play a video and coming
  back must not reload every page. That means the web views are owned outside
  the screen's lifecycle.
- Because a web view needs a UI context to render, retaining one across
  screens leaks the host window unless the view is built against a swappable
  context wrapper whose base is re-pointed at whichever window is showing it,
  and released when none is.
- **Pause every tab's media and timers when the browser isn't on screen.**
  Otherwise a page's video keeps playing its audio underneath the tiled
  player, which is indistinguishable from a bug.
- Persist the open tabs (addresses, titles, active index) and restore them on
  launch. Persist *addresses*, not serialized engine state: the latter isn't
  portable across engine versions, and reopening the page is the part that
  matters.
- Closing a tab must destroy its web view, not just drop the reference, or its
  memory and any playing media survive the close.
