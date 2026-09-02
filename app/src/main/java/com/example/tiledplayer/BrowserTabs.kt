package com.example.tiledplayer

import android.content.Context
import android.content.MutableContextWrapper
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Enough for real browsing without turning into a tab graveyard on a phone. */
const val MAX_BROWSER_TABS = 5

private const val TABS_FILE = "browser_tabs.json"

/** One tab: a live WebView plus the state the UI reads. */
class BrowserTab(val id: Long) {
    /** Created and configured by the browser screen; kept alive across screens. */
    var webView: WebView? = null

    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var loading by mutableStateOf(false)
    var progress by mutableStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var desktopSite by mutableStateOf(false)

    /** Media seen on the current page (see [MediaHit]). */
    val hits = mutableStateListOf<MediaHit>()

    /**
     * Set when a tab is restored from disk or opened with an address before
     * its WebView exists; the screen loads it as soon as one is attached.
     */
    var pendingUrl: String? = null

    val label: String
        get() = title.ifBlank { if (url.isBlank()) "New tab" else titleFromUrl(url) }
}

/**
 * The browser's tabs, owned outside of composition.
 *
 * Two reasons this isn't just screen state:
 *
 *  - Leaving the browser to play something and coming back must not reload
 *    every page and lose your place. So the WebViews outlive the composable,
 *    and are only paused while the browser isn't on screen.
 *  - The tab list is written to disk, so relaunching the app reopens the same
 *    pages rather than an empty browser.
 *
 * A retained WebView normally means a leaked Activity, since a WebView needs a
 * UI context to render. [contextWrapper] is the standard way out: the WebViews
 * are built against a wrapper whose base context is swapped to whichever
 * Activity is currently showing them, and dropped back to the application
 * context when none is.
 */
object BrowserTabs {

    val tabs = mutableStateListOf<BrowserTab>()

    var activeIndex by mutableStateOf(0)
        private set

    private var nextId = 1L
    private var contextWrapper: MutableContextWrapper? = null
    private var appContext: Context? = null
    private var restored = false

    val active: BrowserTab?
        get() = tabs.getOrNull(activeIndex)

    val atCapacity: Boolean
        get() = tabs.size >= MAX_BROWSER_TABS

    /**
     * The context to build WebViews with. Points at the current Activity while
     * the browser is on screen (see [attach]).
     */
    fun webViewContext(context: Context): Context {
        val app = context.applicationContext
        appContext = app
        return contextWrapper ?: MutableContextWrapper(app).also { contextWrapper = it }
    }

    /** Point retained WebViews at the Activity that's about to display them. */
    fun attach(context: Context) {
        webViewContext(context)
        contextWrapper?.baseContext = context
    }

    /** Release the Activity reference when the browser leaves the screen. */
    fun detach() {
        appContext?.let { contextWrapper?.baseContext = it }
    }

    fun select(index: Int) {
        if (index in tabs.indices) activeIndex = index
    }

    /** Returns null when already at [MAX_BROWSER_TABS]. */
    fun open(url: String?, select: Boolean = true): BrowserTab? {
        if (atCapacity) return null
        val tab = BrowserTab(nextId++).apply { pendingUrl = url?.takeIf { it.isNotBlank() } }
        tabs.add(tab)
        if (select) activeIndex = tabs.lastIndex
        return tab
    }

    fun close(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        // Destroying is what actually frees the renderer process; without it a
        // closed tab keeps its memory (and any playing media) for the life of
        // the app.
        tab.webView?.let { view ->
            runCatching {
                view.stopLoading()
                view.destroy()
            }
        }
        tab.webView = null
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            open(null)
        } else if (activeIndex >= tabs.size) {
            activeIndex = tabs.lastIndex
        }
    }

    /**
     * Stops timers and media in every tab. Called when the browser leaves the
     * screen — otherwise a page's video keeps playing its audio underneath the
     * tiled player, which sounds exactly like a bug.
     */
    fun pauseAll() {
        tabs.forEach { tab ->
            tab.webView?.let { view ->
                runCatching {
                    view.onPause()
                    view.pauseTimers()
                }
            }
        }
    }

    fun resumeAll() {
        tabs.forEach { tab ->
            tab.webView?.let { view ->
                runCatching {
                    view.resumeTimers()
                    view.onResume()
                }
            }
        }
    }

    // --- persistence ------------------------------------------------------

    private fun file(context: Context): File =
        File(VaultStore.vaultDir(context).apply { mkdirs() }, TABS_FILE)

    /**
     * Rebuilds the tab list from disk, once per process. Restores addresses
     * rather than full WebView state: a serialized back/forward history isn't
     * portable across WebView versions, and reopening the page the user was on
     * is the part that matters.
     */
    fun restoreOnce(context: Context) {
        if (restored) return
        restored = true
        runCatching {
            val f = file(context)
            if (!f.isFile) return@runCatching
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("tabs") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isBlank()) continue
                if (tabs.size >= MAX_BROWSER_TABS) break
                tabs.add(
                    BrowserTab(nextId++).apply {
                        pendingUrl = url
                        this.url = url
                        title = o.optString("title")
                    }
                )
            }
            activeIndex = root.optInt("active", 0).coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        }
        if (tabs.isEmpty()) open(null)
    }

    fun persist(context: Context) {
        runCatching {
            val arr = JSONArray()
            tabs.forEach { tab ->
                val url = tab.url.takeIf { it.startsWith("http") } ?: tab.pendingUrl.orEmpty()
                if (url.startsWith("http")) {
                    arr.put(JSONObject().put("url", url).put("title", tab.title))
                }
            }
            val root = JSONObject().put("tabs", arr).put("active", activeIndex)
            val tmp = File(VaultStore.vaultDir(context), "$TABS_FILE.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file(context))) {
                file(context).writeText(root.toString())
                tmp.delete()
            }
        }
    }

    /** Forgets every tab and its data — the browser half of "clear my tracks". */
    fun closeAll(context: Context) {
        tabs.toList().forEach { tab ->
            tab.webView?.let { view -> runCatching { view.stopLoading(); view.destroy() } }
            tab.webView = null
        }
        tabs.clear()
        activeIndex = 0
        open(null)
        persist(context)
    }
}
