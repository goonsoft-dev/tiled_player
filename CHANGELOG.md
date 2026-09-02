# Changelog

Version names come from `app/build.gradle.kts`. This app is sideloaded, so
"released" just means an APK was built and installed.

## 0.5-vault (versionCode 8)

The in-app browser rebuilt as an actual browser. The engine was never the
problem — Android's WebView *is* Chromium — but a bare WebView omits
everything a browser wraps around it, and every omission fails **silently**,
which is why pages felt soft-locked rather than limited.

- **Pop-ups and `target="_blank"` now work.** Previously dropped entirely
  (a WebView ignores `window.open` unless told to support multiple windows and
  given a handler), which is what made play buttons look dead. They open as
  tabs.
- **Page fullscreen now works** — previously the fullscreen button did
  nothing. Back exits fullscreen before it exits the page.
- **Download links now work** — a download triggers no navigation, so without
  a listener the tap was ignored. Tapping one saves into the vault.
- **`alert`/`confirm`/`prompt` are shown** instead of swallowed, which used to
  hang any page gating playback behind one.
- **`intent:` links follow their `browser_fallback_url`** instead of dead-ending.
- Added forward, reload/stop, load progress, desktop-site toggle, clear
  browsing data, "Open in Chrome".
- **Tabs** (max 5) that survive leaving the browser, and **session restore**
  across app relaunch.
- Tab media/timers pause when the browser leaves the screen, so a page's video
  no longer keeps playing audio under the tiled player.
- Fixed a crash: a pop-up's WebView must be handed over un-navigated
  (`IllegalArgumentException: New WebView for popup window must not have been
  previously navigated`).

## 0.4.1-vault (versionCode 7)

- **Fixed the browser opening to a black screen.** Opened from the menu it was
  handed an empty URL and never loaded anything; an empty WebView fills ~90%
  of the screen and paints black. It now opens on a built-in offline start
  page. Page-load errors surface instead of leaving a blank screen.

## 0.4-vault (versionCode 6)

- **Export** selected videos or the whole library to a folder you pick, with
  collision-safe naming and partial-file cleanup on failure.
- **Streaming**: play http(s) URLs directly, with the headers they were found
  with. HLS/DASH supported; without a probeable duration they get one pane
  playing the whole stream rather than failing the session.
- **Online tab**: bookmarked stream URLs, downloadable into the vault later.
- **In-app browser** that sniffs media URLs from the requests a page makes.
- **External player**: registers for other apps' video/link intents, behind a
  toggleable component alias (being in the share sheet is a disclosure).
- Added `INTERNET`, cleartext HTTP, media3 HLS/DASH, `documentfile`.

## 0.3-vault (versionCode 5)

- **Per-layout audio memory** — which tile is unmuted is remembered per layout.
- **Real filenames on import** — recovers the true name behind the photo
  picker's id-based one; falls back to a readable generated label.
- **Shuffle thumbnail** per video (exact-seek, so it actually changes).
- **Non-destructive trim** with a scrubbing frame preview; the segment planner
  divides the trimmed range.
- Fixed a stale-thumbnail bug: `produceState` keeps its value across key
  changes, so the old bitmap survived and the reload was skipped.

## 0.2-vault (versionCode 4)

The vault. Built for ColorOS's hidden-app drawer, which can't reach the
Private Safe.

- Import videos into app-private storage; they keep playing after the original
  is hidden or deleted, and need no permission to list or play.
- Photo-picker and SAF import paths, atomic `.part` commits, orphan sweep on
  launch, free-space checks, per-file failure isolation.
- Optional post-import "delete originals" via the system's own confirmation.
- `FLAG_SECURE` on by default; backup and device-transfer disabled.

## 0.1-alpha (versionCode 3)

Tiled playback: segment planning, layout tree with presets and a grid stepper,
per-pane zoom/pan/swap/audio, synchronized start, slow-mo, per-pane failure
isolation, persisted layout and reorderable presets.
