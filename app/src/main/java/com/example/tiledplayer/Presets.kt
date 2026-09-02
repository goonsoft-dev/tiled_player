package com.example.tiledplayer

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * A pane layout is a tree. A [Leaf] is a single pane (it plays one video
 * segment). A [Split] divides its area among child nodes, either side by side
 * ([horizontal] = true, a row) or stacked ([horizontal] = false, a column),
 * in proportion to [weights]. Nesting splits lets us express arbitrary layouts
 * such as "tall panes on the sides with a 2x2 grid in the middle".
 */
sealed class LayoutNode

class Leaf : LayoutNode() {
    /** Segment/player index, assigned by [assignIndices] in traversal order. */
    var index: Int = 0
}

class Split(
    val horizontal: Boolean,
    val children: List<LayoutNode>,
    val weights: SnapshotStateList<Float>,
) : LayoutNode()

private fun split(horizontal: Boolean, children: List<LayoutNode>, weights: List<Float>): Split {
    val w = mutableStateListOf<Float>().apply { addAll(weights) }
    return Split(horizontal, children, w)
}

private fun row(children: List<LayoutNode>, weights: List<Float> = List(children.size) { 1f }) =
    split(horizontal = true, children = children, weights = weights)

private fun col(children: List<LayoutNode>, weights: List<Float> = List(children.size) { 1f }) =
    split(horizontal = false, children = children, weights = weights)

private fun leaves(n: Int) = List(n) { Leaf() }

/** Number of panes (leaves) in the tree. */
fun countLeaves(node: LayoutNode): Int = when (node) {
    is Leaf -> 1
    is Split -> node.children.sumOf { countLeaves(it) }
}

/** Assigns each leaf a 0-based index in depth-first (top-left first) order. */
fun assignIndices(node: LayoutNode) {
    var next = 0
    fun walk(n: LayoutNode) {
        when (n) {
            is Leaf -> n.index = next++
            is Split -> n.children.forEach { walk(it) }
        }
    }
    walk(node)
}

/**
 * A [rows] by [cols] grid: [rows] equal-height strips stacked vertically, each
 * split into [cols] equal-width cells. Unlike a square-ish auto layout, every
 * cell in a row/column lines up exactly, so lopsided shapes like 1x4 or 3x1
 * are expressible, not just near-square counts.
 */
fun rectGridTree(rows: Int, cols: Int): LayoutNode {
    val r = rows.coerceAtLeast(1)
    val c = cols.coerceAtLeast(1)
    if (r == 1 && c == 1) return Leaf()
    val rowNodes = List(r) { row(leaves(c)) }
    return if (r == 1) rowNodes[0] else col(rowNodes)
}

/** A named layout the user can pick from the player screen. */
class LayoutPreset(val name: String, val build: () -> LayoutNode) {
    val paneCount: Int by lazy { countLeaves(build()) }
}

/**
 * Tall panes on the left and right, with a 2x2 grid in the middle (6 panes).
 */
private fun sidebarsWithGrid(): LayoutNode = row(
    children = listOf(
        Leaf(),
        col(
            listOf(
                row(leaves(2)),
                row(leaves(2)),
            )
        ),
        Leaf(),
    ),
    weights = listOf(1f, 2.6f, 1f),
)

/** One large pane with a column of smaller panes down the right side (4 panes). */
private fun spotlight(): LayoutNode = row(
    children = listOf(
        Leaf(),
        col(leaves(3)),
    ),
    weights = listOf(3f, 1f),
)

/** A single tall pane on the left, a 2x2 grid on the right (5 panes). */
private fun sidebarLeftWithGrid(): LayoutNode = row(
    children = listOf(
        Leaf(),
        col(listOf(row(leaves(2)), row(leaves(2)))),
    ),
    weights = listOf(1f, 2.4f),
)

/**
 * Named presets offered on the player screen, in addition to the MxN grid
 * stepper (see [rectGridTree]) which the player screen renders separately as
 * its own selectable control.
 */
fun buildPresetList(): List<LayoutPreset> = listOf(
    LayoutPreset("2 wide") { row(leaves(2)) },
    LayoutPreset("2 tall") { col(leaves(2)) },
    LayoutPreset("3 cols") { row(leaves(3)) },
    LayoutPreset("3 rows") { col(leaves(3)) },
    LayoutPreset("Sidebars + 2x2") { sidebarsWithGrid() },
    LayoutPreset("Sidebar + 2x2") { sidebarLeftWithGrid() },
    LayoutPreset("Spotlight") { spotlight() },
)
