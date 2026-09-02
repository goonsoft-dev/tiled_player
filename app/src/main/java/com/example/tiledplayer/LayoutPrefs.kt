package com.example.tiledplayer

import android.content.Context

private const val PREFS_NAME = "layout_prefs"
private const val KEY_GRID_ROWS = "grid_rows"
private const val KEY_GRID_COLS = "grid_cols"
private const val KEY_PRESET_NAME = "preset_name"
private const val KEY_PRESET_ORDER = "preset_order"
private const val KEY_AUDIO_PREFIX = "audio_"

// None of the built-in preset names contain a pipe, so a plain split is safe.
private const val ORDER_SEPARATOR = "|"
private const val VOLUME_SEPARATOR = ","

/**
 * Remembers the last layout the player screen was showing (grid size, or a
 * named preset) plus the user's custom ordering of the named presets, across
 * app launches. Backed by [android.content.SharedPreferences]; every read and
 * write is cheap enough to call straight from composition.
 */
object LayoutPrefs {
    data class SavedSelection(val gridRows: Int, val gridCols: Int, val presetName: String?)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** [presetName] is null when the active selection is the MxN grid, not a named preset. */
    fun saveSelection(context: Context, gridRows: Int, gridCols: Int, presetName: String?) {
        prefs(context).edit()
            .putInt(KEY_GRID_ROWS, gridRows)
            .putInt(KEY_GRID_COLS, gridCols)
            .putString(KEY_PRESET_NAME, presetName)
            .apply()
    }

    /** [SavedSelection.gridRows]/[SavedSelection.gridCols] are -1 when nothing has been saved yet. */
    fun loadSelection(context: Context): SavedSelection {
        val p = prefs(context)
        return SavedSelection(
            gridRows = p.getInt(KEY_GRID_ROWS, -1),
            gridCols = p.getInt(KEY_GRID_COLS, -1),
            presetName = p.getString(KEY_PRESET_NAME, null),
        )
    }

    fun savePresetOrder(context: Context, orderedNames: List<String>) {
        prefs(context).edit()
            .putString(KEY_PRESET_ORDER, orderedNames.joinToString(ORDER_SEPARATOR))
            .apply()
    }

    fun loadPresetOrder(context: Context): List<String> =
        prefs(context).getString(KEY_PRESET_ORDER, null)
            ?.split(ORDER_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /**
     * Identifies a layout for the purpose of remembering its audio setup: a
     * named preset by name, the stepper grid by its dimensions. Two different
     * layouts that happen to have the same pane count still get their own
     * entry, which is the whole point — "which tile is unmuted in the 2x2 grid"
     * is a different answer from "which tile is unmuted in Spotlight".
     */
    fun layoutKey(gridRows: Int, gridCols: Int, presetName: String?): String =
        presetName?.let { "preset:$it" } ?: "grid:${gridRows}x$gridCols"

    /**
     * Per-pane volumes for one layout, indexed by *player* index (not pane
     * position), so a saved selection follows the video across pane swaps.
     */
    fun saveAudio(context: Context, layoutKey: String, volumes: List<Float>) {
        prefs(context).edit()
            .putString(
                KEY_AUDIO_PREFIX + layoutKey,
                volumes.joinToString(VOLUME_SEPARATOR) { "%.2f".format(java.util.Locale.US, it) },
            )
            .apply()
    }

    /**
     * Returns null when this layout has no saved audio yet, or when the saved
     * entry doesn't match [paneCount] (the videos or tile count changed since),
     * so the caller falls back to its default rather than restoring a stale
     * selection onto the wrong panes.
     */
    fun loadAudio(context: Context, layoutKey: String, paneCount: Int): List<Float>? {
        val saved = prefs(context).getString(KEY_AUDIO_PREFIX + layoutKey, null) ?: return null
        val values = saved.split(VOLUME_SEPARATOR).mapNotNull { it.toFloatOrNull() }
        return if (values.size == paneCount) values.map { it.coerceIn(0f, 1f) } else null
    }
}

/**
 * Applies a saved custom order (by preset name) to the built-in preset list.
 * Unknown saved names (e.g. from a preset that no longer exists) are dropped;
 * presets not mentioned in the saved order (e.g. newly added ones) are
 * appended at the end in their built-in order.
 */
fun applyPresetOrder(presets: List<LayoutPreset>, savedOrder: List<String>): List<LayoutPreset> {
    if (savedOrder.isEmpty()) return presets
    val byName = presets.associateBy { it.name }
    val ordered = savedOrder.mapNotNull { byName[it] }
    val remaining = presets.filter { it.name !in savedOrder }
    return ordered + remaining
}
