package com.example.tiledplayer

import android.content.Context

private const val PREFS_NAME = "library_prefs"
private const val KEY_RATINGS = "ratings"
private const val KEY_VIEW_MODE = "view_mode"
private const val KEY_SORT_MODE = "sort_mode"
private const val KEY_MIN_RATING = "min_rating"

private const val ENTRY_SEPARATOR = ","
private const val KV_SEPARATOR = ":"

/** CARDS = multi-column grid, LARGE = one big card per row, LIST = compact rows. */
enum class LibraryViewMode { CARDS, LARGE, LIST }

enum class LibrarySortMode {
    DATE_ADDED_DESC,
    DATE_ADDED_ASC,
    NAME_ASC,
    NAME_DESC,
    DURATION_DESC,
    DURATION_ASC,
    SIZE_DESC,
    RATING_DESC,
}

/**
 * Remembers per-video star ratings (keyed by [VideoItem.id], the MediaStore
 * row id) plus the library screen's view/sort/filter prefs, across launches.
 * Backed by [android.content.SharedPreferences], same lightweight pattern as
 * [LayoutPrefs].
 */
object LibraryPrefs {
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadRatings(context: Context): Map<Long, Int> =
        prefs(context).getString(KEY_RATINGS, null)
            ?.split(ENTRY_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?.mapNotNull { entry ->
                val parts = entry.split(KV_SEPARATOR)
                val id = parts.getOrNull(0)?.toLongOrNull()
                val rating = parts.getOrNull(1)?.toIntOrNull()
                if (id != null && rating != null) id to rating else null
            }
            ?.toMap()
            ?: emptyMap()

    fun saveRatings(context: Context, ratings: Map<Long, Int>) {
        val encoded = ratings.entries.joinToString(ENTRY_SEPARATOR) { (id, rating) ->
            "$id$KV_SEPARATOR$rating"
        }
        prefs(context).edit().putString(KEY_RATINGS, encoded).apply()
    }

    fun loadViewMode(context: Context): LibraryViewMode =
        runCatching {
            LibraryViewMode.valueOf(prefs(context).getString(KEY_VIEW_MODE, null) ?: "")
        }.getOrDefault(LibraryViewMode.CARDS)

    fun saveViewMode(context: Context, mode: LibraryViewMode) {
        prefs(context).edit().putString(KEY_VIEW_MODE, mode.name).apply()
    }

    fun loadSortMode(context: Context): LibrarySortMode =
        runCatching {
            LibrarySortMode.valueOf(prefs(context).getString(KEY_SORT_MODE, null) ?: "")
        }.getOrDefault(LibrarySortMode.DATE_ADDED_DESC)

    fun saveSortMode(context: Context, mode: LibrarySortMode) {
        prefs(context).edit().putString(KEY_SORT_MODE, mode.name).apply()
    }

    fun loadMinRating(context: Context): Int =
        prefs(context).getInt(KEY_MIN_RATING, 0)

    fun saveMinRating(context: Context, minRating: Int) {
        prefs(context).edit().putInt(KEY_MIN_RATING, minRating).apply()
    }
}
