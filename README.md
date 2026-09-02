# Tiled Player

An Android video player that splits videos into equal time segments and plays
them **all at once**, side by side, each pane looping its own slice. Layouts
are live-editable: resize with dividers, swap panes, pinch-zoom individual
tiles, unmute whichever pane you want to hear.

Around that player sits a private video library, built for one specific
constraint: **ColorOS's hidden-app drawer can't reach the Private Safe**, and a
video that's merely been *hidden* in the gallery stops resolving through
MediaStore. So the app keeps its own copies instead of referencing yours.

[`DESIGN.md`](DESIGN.md) is the authoritative spec — a platform-agnostic
description of every rule here, written so the app can be rebuilt elsewhere.
Where this code and that document disagree, the document wins.

## What it does

**Player** — the vault. Videos copied into the app's private storage.

- Keep playing after the gallery original is hidden, moved to the Private
  Safe, or deleted outright.
- Need **no storage permission at all** to list or play, and adding via the
  system photo picker needs none either.
- Never re-appear in the gallery (internal storage isn't media-scanned), and
  are stored under opaque id-based filenames.
- Per-video: rename, non-destructive trim (in/out points, no re-encode),
  shuffle the poster frame, export back out.

**Online** — bookmarked URLs that stream straight off the web, with the
`Referer`/`Cookie`/`User-Agent` they were found with (without those, most CDNs
return 403). Nothing is stored until you choose to download.

**Device** — the ordinary MediaStore library, for picking what to import.
Ratings, sorting and filtering work across all three tabs.

**Browser** — a tabbed in-app browser (up to 5 tabs) that watches the requests
a page makes and offers every media URL it sees to play, bookmark, or save.
Tabs survive leaving the screen and are restored on relaunch.

**External player** — registers for other apps' "open this video" intents, so
Chrome can hand over a video link or a page. Toggleable, because being in the
share sheet is a disclosure (see below).

### Privacy behaviour

| Behaviour | Default | Where |
|---|---|---|
| `FLAG_SECURE` — blocks screenshots, screen recording, recents preview | **on** | ⋮ → Block screenshots |
| Offered to other apps (share sheet / "Open with") | on | ⋮ → Offer this app to other apps |
| Cloud backup and device-to-device transfer | **disabled** | manifest + `data_extraction_rules.xml` |

### Known limits

- `blob:` video (players assembling streams in JavaScript) and DRM-protected
  video can't be captured or played outside their own player. This is the main
  reason some sites won't work, and no browser choice changes it.
- Adaptive streams (HLS/DASH) can be watched and bookmarked but **not saved**
  as one file, and can't be split across panes — no duration to divide, so
  they get one pane playing the whole stream.
- Imports, downloads and exports run **only in the foreground**. A background
  service would need a persistent notification, which defeats a hidden app.
  An interrupted copy costs time, never data.
- Trimming sets playback in/out points; it does not shrink the file.
- **The vault dies with the app's data.** Uninstalling or clearing app data
  deletes every copy. Export is the only backup — use it.

## Building

No JDK or SDK on `PATH`; set them explicitly:

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat assembleDebug
```

Release builds need signing credentials, supplied out-of-tree. Copy
`keystore.properties.example` to `keystore.properties` (git-ignored) and fill
in your keystore path and passwords, or set the `TILEDPLAYER_STORE_FILE` /
`TILEDPLAYER_STORE_PASSWORD` / `TILEDPLAYER_KEY_ALIAS` /
`TILEDPLAYER_KEY_PASSWORD` environment variables. With neither present,
`assembleRelease` falls back to the debug key.

```bash
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" ./gradlew.bat assembleRelease
```

Output lands in `app/build/outputs/apk/{debug,release}/`. This is a private
sideload build, not a Play Store artifact.

- AGP 8.5.2 · Gradle 8.7 · Kotlin 1.9.24 · Compose compiler 1.5.14 · Media3 1.4.1
- `compileSdk`/`targetSdk` 34, `minSdk` 26

## Testing notes

- The debug build has an intent hook that bypasses the library entirely:
  ```bash
  adb shell am start -n com.example.tiledplayer/.MainActivity -e debug_video_names a.mp4,b.mp4 -e tiles 4
  ```
  Files are read from the app's internal `filesDir` (push them with `run-as`).
- **`FLAG_SECURE` is on by default, so `adb shell screencap` returns a black
  app area.** That is the flag working, not a rendering bug. Turn it off via
  ⋮ before taking screenshots. `uiautomator dump` still works under it — but
  it does not expose WebView page content, so it can't tell you whether a web
  page rendered.
- Inspect app state directly rather than fighting pixel taps:
  ```bash
  adb shell "run-as com.example.tiledplayer cat files/vault/index.json"
  adb shell "run-as com.example.tiledplayer cat files/vault/bookmarks.json"
  adb shell "run-as com.example.tiledplayer cat files/vault/browser_tabs.json"
  adb shell "run-as com.example.tiledplayer cat shared_prefs/layout_prefs.xml"
  ```
- For browser/streaming work, serve a page from the host and reach it at
  `http://10.0.2.2:<port>` from the emulator.

## Source map

Playback core (§1–§7 of the spec):

| File | Role |
|---|---|
| `GridLayout.kt`, `Presets.kt` | Layout tree, even-N grid, named presets |
| `SegmentPlayerManager.kt` | One ExoPlayer per pane, segment planning, sync start, audio focus, per-pane failure isolation |
| `TileGrid.kt` | Split rendering, dividers, per-pane zoom/pan/swap gestures |
| `PlayerScreen.kt` | Layout ribbon, transport, per-layout audio memory |
| `LayoutPrefs.kt` | Persisted layout selection, preset order, per-layout audio |

Library and vault (§8):

| File | Role |
|---|---|
| `VaultStore.kt` | Private copies, index, trim points, thumbnails |
| `VaultImport.kt` | Copy/download queue, atomic commit via `.part` files |
| `VaultExport.kt` | Export out to a user-chosen SAF folder |
| `LibraryScreen.kt` | Three-tab library, selection actions, dialogs |
| `TrimDialog.kt` | In/out points with scrubbing frame preview |
| `VideoLibrary.kt`, `ThumbnailLoader.kt`, `LibraryPrefs.kt`, `VaultPrefs.kt` | Item model, thumbnails, ratings/view prefs, privacy prefs |

Web sources (§9):

| File | Role |
|---|---|
| `RemoteLibrary.kt` | Bookmarked stream URLs and their headers |
| `BrowserScreen.kt` | Browser UI, media sniffing, popups/fullscreen/downloads |
| `BrowserTabs.kt` | Retained tabs, context-wrapper lifecycle, session persistence |
| `HandoffIntent.kt` | External-player intents and the toggleable alias |

`MainActivity.kt` routes between library, browser and player;
`CrashReporter.kt` persists uncaught exceptions to `files/last_crash.txt` and
shows them on next launch.

## License

GPL-3.0-or-later. See [`LICENSE`](LICENSE).
