package com.example.tiledplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

private const val OPEN_WITH_ALIAS = ".OpenWithActivity"

/** Extensions that mean "this URL is a video file", for untyped VIEW intents. */
private val VIDEO_URL_EXTENSIONS =
    setOf("mp4", "webm", "mkv", "m4v", "mov", "3gp", "ts", "ogv", "m3u8", "mpd")

/**
 * What another app asked us to do.
 *
 * The app registers as an external video player (see the `OpenWithActivity`
 * alias in the manifest), so it can be launched three ways beyond its own
 * icon: with a video URL to play, with local video files to import, or with a
 * page URL that needs opening in the in-app browser to find the video on it.
 */
sealed interface HandoffIntent {
    /** Play this immediately — a direct video URL or a local video file. */
    data class Play(val session: PlaybackSession, val url: String?) : HandoffIntent

    /** A page, not a video: open the browser there and sniff for media. */
    data class Browse(val url: String) : HandoffIntent

    /** Video files shared in from another app; copy them into the vault. */
    data class Import(val uris: List<Uri>) : HandoffIntent

    companion object {
        fun from(context: Context, intent: Intent?): HandoffIntent? {
            if (intent == null) return null
            return when (intent.action) {
                Intent.ACTION_VIEW -> fromView(intent)
                Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> fromSend(context, intent)
                else -> null
            }
        }

        private fun fromView(intent: Intent): HandoffIntent? {
            val uri = intent.data ?: return null
            return when (uri.scheme) {
                "http", "https" ->
                    // A direct media URL plays; anything else is a page, and
                    // the browser is the only thing that can find video on it.
                    if (looksLikeVideoUrl(uri) || intent.type?.startsWith("video/") == true) {
                        HandoffIntent.Play(playSession(uri), uri.toString())
                    } else {
                        HandoffIntent.Browse(uri.toString())
                    }
                "content", "file" -> HandoffIntent.Play(playSession(uri), null)
                else -> null
            }
        }

        private fun fromSend(context: Context, intent: Intent): HandoffIntent? {
            // Shared video files: import them, same as picking them.
            val streams = when (intent.action) {
                Intent.ACTION_SEND_MULTIPLE ->
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList()
                else ->
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) }
            }
            if (!streams.isNullOrEmpty()) {
                streams.forEach { uri ->
                    // A shared uri is only readable while this grant lasts, so
                    // take a persistable hold where the sender offered one.
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                return HandoffIntent.Import(streams)
            }

            // Shared text: pull the first URL out of it. Browsers often share
            // "Page title https://..." rather than a bare link.
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val url = firstUrlIn(text) ?: return null
            val uri = Uri.parse(url)
            return if (looksLikeVideoUrl(uri)) {
                HandoffIntent.Play(playSession(uri), url)
            } else {
                HandoffIntent.Browse(url)
            }
        }

        private fun playSession(uri: Uri): PlaybackSession =
            PlaybackSession(listOf(PlaybackClip(uri)), tileCount = 1)

        private fun looksLikeVideoUrl(uri: Uri): Boolean {
            val extension = uri.path?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return extension in VIDEO_URL_EXTENSIONS
        }

        private fun firstUrlIn(text: String): String? =
            Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', ']')

        /**
         * Whether the app currently offers itself to other apps. Reflects the
         * alias's component state, so it survives restarts without a pref.
         */
        fun isOpenWithEnabled(context: Context): Boolean {
            val component = ComponentName(context.packageName, context.packageName + OPEN_WITH_ALIAS)
            return when (context.packageManager.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                // DEFAULT means "as declared in the manifest", which is enabled.
                else -> true
            }
        }

        fun setOpenWithEnabled(context: Context, enabled: Boolean) {
            val component = ComponentName(context.packageName, context.packageName + OPEN_WITH_ALIAS)
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    component,
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
