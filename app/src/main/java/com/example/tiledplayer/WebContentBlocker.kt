package com.example.tiledplayer

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * A small, self-contained blocklist for the in-app browser. It isn't a full
 * ad blocker — there's no room to ship or fetch EasyList — but it kills the
 * handful of pop-under / redirect / "click anywhere" ad networks that make
 * free video sites nearly unusable, plus the common trackers. Matching is by
 * domain suffix, so `x.ads.doubleclick.net` is caught by `doubleclick.net`.
 *
 * The toggle is global and defaults on; a site that genuinely breaks can be
 * un-blocked from the browser's overflow menu.
 */
object WebContentBlocker {
    private const val PREFS = "browser_prefs"
    private const val KEY_ENABLED = "block_ads"

    @Volatile
    private var enabledCache: Boolean? = null

    fun isEnabled(context: Context): Boolean =
        enabledCache ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true).also { enabledCache = it }

    fun setEnabled(context: Context, on: Boolean) {
        enabledCache = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** An empty 200, so the page sees "loaded, nothing there" rather than an error. */
    fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun shouldBlock(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Ad / pop-under / tracker hosts, hand-picked at the "ad network" level:
     * these are the ones behind the pop-ups, forced redirects and full-page
     * interstitials on streaming and download sites.
     */
    private val BLOCKED_HOSTS: Set<String> = setOf(
        // Google ads / analytics
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagservices.com", "googletagmanager.com",
        // Exchanges / SSPs
        "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net",
        "criteo.com", "criteo.net", "casalemedia.com", "adform.net",
        "smartadserver.com", "3lift.com", "districtm.io", "gumgum.com",
        "sharethrough.com", "spotxchange.com", "spotx.tv", "teads.tv",
        "yieldmo.com", "sonobi.com", "indexww.com", "bidswitch.net",
        // Pop-under / redirect ad networks — the worst offenders on video sites
        "popads.net", "popcash.net", "popmyads.com", "poptm.com",
        "propellerads.com", "propellerclick.com", "propu.sh",
        "exoclick.com", "exosrv.com", "exdynsrv.com", "realsrv.com",
        "juicyads.com", "trafficjunky.com", "trafficjunky.net",
        "adsterra.com", "adsterranet.com", "adskeeper.com",
        "hilltopads.net", "hilltopads.com", "clickadu.com", "adcash.com",
        "ad-maven.com", "admaven.com", "admavenpop.com", "onclickalgo.com",
        "onclickperformance.com", "onclickmax.com", "clickaine.com",
        "mgid.com", "revcontent.com", "outbrain.com", "taboola.com",
        "zergnet.com", "content.ad", "bidvertiser.com", "chitika.com",
        "infolinks.com", "vidoomy.com", "adnium.com", "adtng.com",
        "tsyndicate.com", "pemsrv.com", "waframedia5.com",
        "highperformanceformat.com", "effectivegatecpm.com",
        "effectivecpmgate.com", "displaycontentnetwork.com",
        // Trackers / session replay / fingerprinting
        "scorecardresearch.com", "quantserve.com", "quantcount.com",
        "hotjar.com", "hotjar.io", "mouseflow.com", "fullstory.com",
        "mixpanel.com", "segment.com", "segment.io", "amplitude.com",
        "branch.io", "adjust.com", "appsflyer.com", "kochava.com",
        "newrelic.com", "nr-data.net", "bugsnag.com",
        "connect.facebook.net", "analytics.tiktok.com",
        "bat.bing.com", "clarity.ms", "mc.yandex.ru",
        "moatads.com", "adsafeprotected.com", "doubleverify.com",
    )
}
