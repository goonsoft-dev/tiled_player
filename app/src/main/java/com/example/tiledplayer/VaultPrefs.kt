package com.example.tiledplayer

import android.content.Context

private const val PREFS_NAME = "vault_prefs"
private const val KEY_SECURE_SCREEN = "secure_screen"
private const val KEY_LAST_TAB = "last_tab"

/**
 * Which list the library shows: the app's own copies, bookmarked streams, or
 * the device's gallery.
 */
enum class LibraryTab { PLAYER, ONLINE, DEVICE }

/**
 * Settings specific to running as a hidden app.
 *
 * [loadSecureScreen] defaults to **on**: FLAG_SECURE keeps the app's contents
 * out of screenshots, screen recordings, and — the reason it matters here — the
 * recent-apps preview, which otherwise shows a live thumbnail of whatever was
 * on screen to anyone who opens the task switcher.
 */
object VaultPrefs {
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSecureScreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SECURE_SCREEN, true)

    fun saveSecureScreen(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SECURE_SCREEN, enabled).apply()
    }

    fun loadLastTab(context: Context): LibraryTab =
        runCatching {
            LibraryTab.valueOf(prefs(context).getString(KEY_LAST_TAB, null) ?: "")
        }.getOrDefault(LibraryTab.PLAYER)

    fun saveLastTab(context: Context, tab: LibraryTab) {
        prefs(context).edit().putString(KEY_LAST_TAB, tab.name).apply()
    }
}
