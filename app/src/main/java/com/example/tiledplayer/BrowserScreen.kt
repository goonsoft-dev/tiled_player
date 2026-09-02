package com.example.tiledplayer

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Extensions that are a video file we can play or download directly. */
private val DIRECT_VIDEO_EXTENSIONS =
    setOf("mp4", "webm", "mkv", "m4v", "mov", "3gp", "ts", "ogv")

/** Adaptive-stream manifests: playable, but not downloadable as one file. */
private val STREAM_EXTENSIONS = setOf("m3u8", "mpd")

/** Content types worth treating as media even when the URL has no extension. */
private val MEDIA_CONTENT_TYPE_HINTS =
    listOf("video/", "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/dash+xml")

private const val MAX_HITS = 40

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

/** One media URL observed while loading a page. */
data class MediaHit(
    val url: String,
    val headers: Map<String, String>,
    val pageUrl: String,
    val kind: Kind,
) {
    enum class Kind { FILE, STREAM }

    val label: String get() = titleFromUrl(url)
}

/**
 * The browser's start page. Served from a string so the browser always has
 * something in it, works offline, and can explain itself where the user is
 * looking.
 */
private const val HOME_PAGE_HTML = """
<!doctype html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root { color-scheme: dark; }
  body {
    margin: 0; padding: 28px 22px;
    background: #101014; color: #e6e1e5;
    font-family: -apple-system, Roboto, sans-serif; line-height: 1.5;
  }
  h1 { font-size: 21px; margin: 0 0 6px; }
  p.sub { margin: 0 0 22px; color: #a9a4ad; font-size: 14px; }
  form { display: flex; gap: 8px; margin-bottom: 28px; }
  input {
    flex: 1; min-width: 0; padding: 13px 15px; font-size: 16px;
    border-radius: 12px; border: 1px solid #48454e;
    background: #1c1b20; color: #e6e1e5;
  }
  button {
    padding: 13px 20px; font-size: 16px; font-weight: 600;
    border: 0; border-radius: 12px; background: #d0bcff; color: #381e72;
  }
  ol { padding-left: 20px; margin: 0 0 24px; }
  li { margin-bottom: 9px; }
  .note {
    font-size: 13px; color: #a9a4ad;
    border-top: 1px solid #2d2a32; padding-top: 16px;
  }
</style>
</head><body>
  <h1>Find video on the web</h1>
  <p class="sub">Search or paste a link in the bar above.</p>
  <form onsubmit="go(event)">
    <input id="q" type="search" placeholder="Search the web" autocomplete="off">
    <button type="submit">Go</button>
  </form>
  <ol>
    <li>Open a page and <b>start the video playing</b>.</li>
    <li>Anything it loads appears as &ldquo;videos detected&rdquo; above.</li>
    <li>Tap <b>Show</b>, then Play, Bookmark, or Save.</li>
  </ol>
  <p class="note">
    Some video can&rsquo;t be picked up: players that build the stream in
    JavaScript (blob: sources) and anything DRM-protected stay inside their own
    player. Live and adaptive streams can be watched and bookmarked, but not
    saved as a single file.
  </p>
<script>
  function go(e) {
    e.preventDefault();
    var q = document.getElementById('q').value.trim();
    if (q) location.href = 'https://duckduckgo.com/?q=' + encodeURIComponent(q);
  }
</script>
</body></html>
"""

/** A JavaScript dialog waiting on the user. */
private data class JsDialog(
    val message: String,
    val isPrompt: Boolean,
    val defaultValue: String,
    val onConfirm: (String?) -> Unit,
    val onCancel: () -> Unit,
)

/**
 * A tabbed browser for getting at video that lives on a web page.
 *
 * The engine is Android's WebView, which *is* Chromium — the same renderer
 * Chrome uses. What a bare WebView lacks is everything a browser wraps around
 * that engine, and the absence of those pieces is what makes pages feel broken
 * rather than merely limited:
 *
 *  - **Pop-ups and `target="_blank"`** do nothing at all unless the WebView is
 *    told to support multiple windows *and* given a [WebChromeClient] to place
 *    them. A tap on a play button that opens a window otherwise looks dead.
 *  - **Fullscreen video** needs [WebChromeClient.onShowCustomView]; without it
 *    the page's fullscreen button silently fails.
 *  - **Downloads** need a `DownloadListener`; without one, a direct media link
 *    is simply ignored.
 *  - **`alert`/`confirm`** are swallowed, so pages that gate playback behind
 *    one hang forever.
 *  - **Non-http schemes** (`intent:`, `market:`, `tel:`) throw or dead-end.
 *
 * Tabs live in [BrowserTabs] rather than here, so they survive leaving this
 * screen and are restored on the next launch.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    startUrl: String?,
    onPlay: (List<PlaybackClip>, Int) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current

    var notice by remember { mutableStateOf<String?>(null) }
    var showHits by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var jsDialog by remember { mutableStateOf<JsDialog?>(null) }
    // The page's own fullscreen view (its video player), shown over everything.
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    var addressText by remember { mutableStateOf("") }
    val addressFocus = remember { FocusRequester() }

    // Restore the previous session before anything reads the tab list.
    LaunchedEffect(Unit) {
        BrowserTabs.restoreOnce(context)
        if (!startUrl.isNullOrBlank()) {
            val existing = BrowserTabs.active
            if (existing != null && existing.url.isBlank() && existing.pendingUrl == null) {
                existing.pendingUrl = startUrl
            } else {
                BrowserTabs.open(startUrl) ?: run {
                    // At capacity: reuse the active tab rather than refusing.
                    BrowserTabs.active?.pendingUrl = startUrl
                }
            }
        }
    }

    DisposableEffect(Unit) {
        BrowserTabs.attach(context)
        BrowserTabs.resumeAll()
        onDispose {
            // Stop page media before leaving, so a page's video doesn't keep
            // playing underneath the tiled player.
            BrowserTabs.pauseAll()
            BrowserTabs.persist(context)
            BrowserTabs.detach()
        }
    }

    val tab = BrowserTabs.active
    LaunchedEffect(tab?.id, tab?.url) {
        addressText = tab?.url.orEmpty()
    }

    /**
     * Builds and wires a WebView for [target].
     *
     * [loadInitial] must be false for a pop-up: the WebView handed back
     * through [WebChromeClient.onCreateWindow]'s transport has to be
     * untouched, and loading anything into it first — even the start page —
     * makes WebView reject it ("must not have been previously navigated").
     */
    fun configure(target: BrowserTab, loadInitial: Boolean = true): WebView {
        target.webView?.let { return it }
        val view = WebView(BrowserTabs.webViewContext(context)).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // Both are required for window.open / target=_blank to reach
            // onCreateWindow instead of being dropped on the floor.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setBackgroundColor(0xFF101014.toInt())
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                v: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                // Background thread, once per subresource: stay cheap, don't
                // touch the WebView. Returning null lets it proceed untouched.
                classify(request)?.let { kind ->
                    val url = request.url.toString()
                    val pageUrl = target.url
                    v.post {
                        if (target.hits.none { it.url == url } && target.hits.size < MAX_HITS) {
                            target.hits.add(
                                MediaHit(url, collectHeaders(request, pageUrl), pageUrl, kind)
                            )
                        }
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                v: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") return false
                // An app link (intent:, market:, tel:, ...) can't load here.
                // Many carry a browser_fallback_url meant for exactly this.
                val fallback = fallbackUrlFrom(uri)
                if (fallback != null) {
                    v.loadUrl(fallback)
                } else {
                    notice = "That link opens another app, which this browser doesn't do."
                }
                return true
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                target.loading = true
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    if (url != target.url) target.hits.clear()
                    target.url = url
                }
            }

            override fun onPageFinished(v: WebView, url: String?) {
                target.loading = false
                target.title = v.title.orEmpty()
                target.canGoBack = v.canGoBack()
                target.canGoForward = v.canGoForward()
                if (url != null && url.startsWith("http")) target.url = url
                BrowserTabs.persist(context)
            }

            override fun onReceivedError(
                v: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                // Only the page itself; a subresource 404 is normal.
                if (request.isForMainFrame) {
                    target.loading = false
                    notice = "Couldn't load that page: ${error.description}"
                }
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                target.progress = newProgress
                target.canGoBack = v.canGoBack()
                target.canGoForward = v.canGoForward()
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                target.title = title.orEmpty()
            }

            override fun onCreateWindow(
                v: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // This is the fix for "the play button does nothing": the page
                // asked for a new window, and without handling it here the
                // request is dropped and the click appears to be swallowed.
                val opened = BrowserTabs.open(null, select = true)
                if (opened == null) {
                    notice = "Pop-up blocked — all $MAX_BROWSER_TABS tabs are in use."
                    return false
                }
                val child = configure(opened, loadInitial = false)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = child
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                BrowserTabs.tabs.firstOrNull { it.webView === window }?.let { BrowserTabs.close(it) }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                // The page's fullscreen video surface. Hosting it is the only
                // way a page's fullscreen button can work.
                customViewCallback?.onCustomViewHidden()
                customView = view
                customViewCallback = callback
            }

            override fun onHideCustomView() {
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }

            override fun onJsAlert(
                v: WebView, url: String?, message: String?, result: JsResult,
            ): Boolean {
                jsDialog = JsDialog(
                    message = message.orEmpty(), isPrompt = false, defaultValue = "",
                    onConfirm = { result.confirm(); jsDialog = null },
                    onCancel = { result.cancel(); jsDialog = null },
                )
                return true
            }

            override fun onJsConfirm(
                v: WebView, url: String?, message: String?, result: JsResult,
            ): Boolean {
                jsDialog = JsDialog(
                    message = message.orEmpty(), isPrompt = false, defaultValue = "",
                    onConfirm = { result.confirm(); jsDialog = null },
                    onCancel = { result.cancel(); jsDialog = null },
                )
                return true
            }

            override fun onJsPrompt(
                v: WebView, url: String?, message: String?, defaultValue: String?,
                result: JsPromptResult,
            ): Boolean {
                jsDialog = JsDialog(
                    message = message.orEmpty(), isPrompt = true,
                    defaultValue = defaultValue.orEmpty(),
                    onConfirm = { result.confirm(it ?: ""); jsDialog = null },
                    onCancel = { result.cancel(); jsDialog = null },
                )
                return true
            }
        }

        // A tapped download link produces no navigation, so without this the
        // tap simply does nothing.
        view.setDownloadListener { url, userAgent, _, mimeType, _ ->
            val looksVideo = mimeType.orEmpty().startsWith("video/") ||
                Uri.parse(url).path?.substringAfterLast('.', "")?.lowercase() in DIRECT_VIDEO_EXTENSIONS
            if (looksVideo) {
                VaultImport.download(
                    context = context,
                    url = url,
                    title = titleFromUrl(url),
                    headers = buildMap {
                        put("User-Agent", userAgent ?: DESKTOP_USER_AGENT)
                        if (target.url.isNotBlank()) put("Referer", target.url)
                        CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
                    },
                )
                notice = "Saving into the player."
            } else {
                notice = "That download isn't a video, so there's nothing to play here."
            }
        }

        target.webView = view
        if (loadInitial) {
            target.pendingUrl?.let { pending ->
                target.pendingUrl = null
                view.loadUrl(pending)
            } ?: run {
                if (target.url.isBlank()) {
                    view.loadDataWithBaseURL(null, HOME_PAGE_HTML, "text/html", "utf-8", null)
                }
            }
        }
        return view
    }

    val go = {
        val target = BrowserTabs.active
        val url = normalizeUrl(addressText)
        if (target != null && url != null) {
            target.hits.clear()
            target.webView?.loadUrl(url) ?: run { target.pendingUrl = url }
        } else if (url == null) {
            notice = "That doesn't look like an address."
        }
    }

    BackHandler {
        val view = BrowserTabs.active?.webView
        when {
            customView != null -> {
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
            }
            view != null && view.canGoBack() -> view.goBack()
            else -> onExit()
        }
    }

    // A page in fullscreen owns the whole screen; the browser chrome would
    // otherwise sit on top of its player controls.
    if (customView != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> android.widget.FrameLayout(ctx) },
                update = { frame ->
                    frame.removeAllViews()
                    customView?.let { view ->
                        (view.parent as? ViewGroup)?.removeView(view)
                        frame.addView(view)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) { Text("✕", style = MaterialTheme.typography.titleLarge) }
            IconButton(
                onClick = { BrowserTabs.active?.webView?.goBack() },
                enabled = tab?.canGoBack == true,
            ) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
            IconButton(
                onClick = { BrowserTabs.active?.webView?.goForward() },
                enabled = tab?.canGoForward == true,
            ) { Text("›", style = MaterialTheme.typography.headlineSmall) }
            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                singleLine = true,
                placeholder = { Text("Search or paste a link") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.weight(1f).focusRequester(addressFocus),
            )
            IconButton(onClick = {
                val view = BrowserTabs.active?.webView
                if (tab?.loading == true) view?.stopLoading() else view?.reload()
            }) {
                Text(if (tab?.loading == true) "✕" else "⟳", style = MaterialTheme.typography.titleMedium)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("New tab") },
                        onClick = {
                            menuOpen = false
                            if (BrowserTabs.open(null) == null) {
                                notice = "That's the $MAX_BROWSER_TABS-tab limit."
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text((if (tab?.desktopSite == true) "✓ " else "    ") + "Desktop site")
                        },
                        onClick = {
                            menuOpen = false
                            val target = BrowserTabs.active ?: return@DropdownMenuItem
                            target.desktopSite = !target.desktopSite
                            target.webView?.let { view ->
                                view.settings.userAgentString =
                                    if (target.desktopSite) DESKTOP_USER_AGENT else null
                                view.settings.useWideViewPort = true
                                view.reload()
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear browsing data") },
                        onClick = {
                            menuOpen = false
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                            BrowserTabs.closeAll(context)
                            notice = "Cookies, site data and tabs cleared."
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Open in Chrome") },
                        enabled = tab?.url?.startsWith("http") == true,
                        onClick = {
                            menuOpen = false
                            val url = BrowserTabs.active?.url ?: return@DropdownMenuItem
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }.onFailure { notice = "No browser could open that." }
                        },
                    )
                }
            }
        }

        if (tab?.loading == true) {
            LinearProgressIndicator(
                progress = { (tab.progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }

        TabStrip(
            tabs = BrowserTabs.tabs,
            activeIndex = BrowserTabs.activeIndex,
            onSelect = { BrowserTabs.select(it) },
            onClose = { BrowserTabs.close(it) },
            onNew = {
                if (BrowserTabs.open(null) == null) notice = "That's the $MAX_BROWSER_TABS-tab limit."
            },
        )

        val hitCount = tab?.hits?.size ?: 0
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (hitCount == 0) "No video detected yet — start it playing on the page"
                else "$hitCount video${if (hitCount == 1) "" else "s"} detected",
                style = MaterialTheme.typography.labelMedium,
                color = if (hitCount == 0) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hitCount > 0) {
                Button(onClick = { showHits = true }) { Text("Show") }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Keyed on the tab so switching swaps which WebView is attached.
            key(tab?.id) {
                if (tab != null) {
                    AndroidView(
                        factory = { configure(tab) },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { view -> (view.parent as? ViewGroup)?.removeView(view) },
                    )
                }
            }
        }
    }

    if (showHits && tab != null) {
        MediaHitsDialog(
            hits = tab.hits.toList(),
            onDismiss = { showHits = false },
            onPlay = { hit ->
                showHits = false
                onPlay(listOf(PlaybackClip(Uri.parse(hit.url), headers = hit.headers)), 1)
            },
            onBookmark = { hit ->
                val added = RemoteLibrary.add(
                    context = context,
                    url = hit.url,
                    title = bestTitle(hit, tab.title),
                    headers = hit.headers,
                    pageUrl = hit.pageUrl,
                )
                notice = if (added == null) "Already bookmarked — refreshed its headers."
                else "Bookmarked. It's in the Online tab."
            },
            onDownload = { hit ->
                if (hit.kind == MediaHit.Kind.STREAM) {
                    notice = "This is an adaptive stream (playlist of segments), " +
                        "so it can't be saved as one file. Bookmark it to watch instead."
                } else {
                    showHits = false
                    VaultImport.download(
                        context = context,
                        url = hit.url,
                        title = bestTitle(hit, tab.title),
                        headers = hit.headers,
                    )
                    notice = "Downloading into the player."
                }
            },
        )
    }

    jsDialog?.let { dialog ->
        JsDialogHost(dialog)
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
        )
    }
}

/** Horizontal tab chips plus a "+" — visible only once there's more than one. */
@Composable
private fun TabStrip(
    tabs: List<BrowserTab>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (BrowserTab) -> Unit,
    onNew: () -> Unit,
) {
    if (tabs.size <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == activeIndex
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .widthIn(max = 180.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable { onSelect(index) }
                        .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp)
                        .widthIn(max = 130.dp),
                )
                Text(
                    "✕",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { onClose(tab) }
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                )
            }
        }
        Text(
            "+",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onNew() }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun JsDialogHost(dialog: JsDialog) {
    var input by remember(dialog) { mutableStateOf(dialog.defaultValue) }
    AlertDialog(
        onDismissRequest = dialog.onCancel,
        text = {
            Column {
                Text(dialog.message)
                if (dialog.isPrompt) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { dialog.onConfirm(if (dialog.isPrompt) input else null) }) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = dialog.onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun MediaHitsDialog(
    hits: List<MediaHit>,
    onDismiss: () -> Unit,
    onPlay: (MediaHit) -> Unit,
    onBookmark: (MediaHit) -> Unit,
    onDownload: (MediaHit) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video on this page") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                hits.forEach { hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(10.dp),
                    ) {
                        Text(
                            hit.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (hit.kind == MediaHit.Kind.STREAM) "adaptive stream" else "video file",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Button(
                                onClick = { onPlay(hit) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            ) { Text("Play", maxLines = 1, softWrap = false) }
                            OutlinedButton(
                                onClick = { onBookmark(hit) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            ) { Text("Bookmark", maxLines = 1, softWrap = false) }
                            OutlinedButton(
                                onClick = { onDownload(hit) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            ) { Text("Save", maxLines = 1, softWrap = false) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Whether this request looks like media, and if so what kind. */
private fun classify(request: WebResourceRequest): MediaHit.Kind? {
    if (!request.method.equals("GET", ignoreCase = true)) return null
    val uri = request.url
    val scheme = uri.scheme
    if (scheme != "http" && scheme != "https") return null

    val path = uri.path?.lowercase().orEmpty()
    val extension = path.substringAfterLast('.', "")
    if (extension in DIRECT_VIDEO_EXTENSIONS) return MediaHit.Kind.FILE
    if (extension in STREAM_EXTENSIONS) return MediaHit.Kind.STREAM

    // Some CDNs serve extensionless URLs and declare the type in the Accept
    // header of the request the player made.
    val accept = request.requestHeaders["Accept"]?.lowercase().orEmpty()
    if (MEDIA_CONTENT_TYPE_HINTS.any { accept.contains(it) }) {
        return if (accept.contains("mpegurl") || accept.contains("dash")) MediaHit.Kind.STREAM
        else MediaHit.Kind.FILE
    }
    return null
}

/**
 * The headers to replay when we fetch this URL ourselves. Cookies come from the
 * WebView's jar rather than the request (the WebView doesn't expose the Cookie
 * header to interceptors), and Referer falls back to the page URL, which is what
 * most hotlink checks actually look at.
 */
private fun collectHeaders(request: WebResourceRequest, pageUrl: String): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    request.requestHeaders["User-Agent"]?.let { headers["User-Agent"] = it }
    val referer = request.requestHeaders["Referer"] ?: pageUrl.takeIf { it.isNotBlank() }
    if (referer != null) headers["Referer"] = referer
    runCatching {
        CookieManager.getInstance().getCookie(request.url.toString())
    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
    return headers
}

/**
 * An `intent:` URL usually carries `S.browser_fallback_url`, the page the site
 * wants shown when its app isn't there. Following it turns a dead link into a
 * working one.
 */
private fun fallbackUrlFrom(uri: Uri): String? {
    if (uri.scheme != "intent") return null
    val raw = uri.toString()
    val marker = "S.browser_fallback_url="
    val start = raw.indexOf(marker).takeIf { it >= 0 } ?: return null
    val value = raw.substring(start + marker.length).substringBefore(";")
    return runCatching { Uri.decode(value) }.getOrNull()?.takeIf { it.startsWith("http") }
}

private fun bestTitle(hit: MediaHit, pageTitle: String): String {
    val fromUrl = titleFromUrl(hit.url)
    // A filename beats a page title, but an opaque CDN id doesn't.
    val looksOpaque = fromUrl.length > 40 || fromUrl.none { it == '.' }
    return if (looksOpaque && pageTitle.isNotBlank()) pageTitle else fromUrl
}

/** Turns typed input into a loadable URL, or a web search if it isn't one. */
fun normalizeUrl(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("http://") || text.startsWith("https://")) return text
    // A single token with a dot and no spaces is a hostname; anything else is
    // a search phrase.
    val looksLikeHost = !text.contains(' ') && text.contains('.') && !text.startsWith(".")
    if (looksLikeHost) return "https://$text"
    return "https://duckduckgo.com/?q=" + Uri.encode(text)
}
