package dev.quickreplay.pad

/**
 * Combo shapes (port of `shapes.js`). Each shape is one combo of a single colour:
 * `row`/`column` contain a full line (extras allowed); `square` exactly 9 orbs in a 3x3 block;
 * `cross` exactly 5 in a plus; `lshape` exactly 5, 3 + 3 sharing a corner; `tpa` exactly 4 in a line.
 */
object Shapes {
    val TYPES = listOf("row", "column", "square", "cross", "lshape", "tpa")

    val LABELS = mapOf(
        "row" to "Row",
        "column" to "Column",
        "square" to "3x3 square",
        "cross" to "Cross",
        "lshape" to "L shape",
        "tpa" to "4 in a line",
    )

    /** Minimum number of orbs of the colour needed on the board for one shape. */
    fun size(type: String, rows: Int, cols: Int): Int = when (type) {
        "row" -> cols
        "column" -> rows
        "square" -> 9
        "cross", "lshape" -> 5
        "tpa" -> 4
        else -> throw IllegalArgumentException("Unknown shape: $type")
    }

    /** True when the shape can exist on a board of this size at all. */
    fun fits(type: String, rows: Int, cols: Int): Boolean =
        if (type == "tpa") rows >= 4 || cols >= 4 else rows >= 3 && cols >= 3

    /** Tests whether a combo ([cells] sorted) has a shape. */
    fun has(type: String, cells: IntArray, rows: Int, cols: Int): Boolean {
        val n = cells.size
        return when (type) {
            "row" -> n >= cols && hasFullLine(cells, rows, cols, horizontal = true)
            "column" -> n >= rows && hasFullLine(cells, rows, cols, horizontal = false)
            "square" -> n == 9 && boxSize(cells, cols) == "3x3"
            "cross" -> n == 5 && boxSize(cells, cols) == "3x3" && isCross(cells, cols)
            "lshape" -> n == 5 && boxSize(cells, cols) == "3x3" && isL(cells, cols)
            "tpa" -> n == 4 && boxSize(cells, cols).let { it == "1x4" || it == "4x1" }
            else -> false
        }
    }

    private fun hasFullLine(cells: IntArray, rows: Int, cols: Int, horizontal: Boolean): Boolean {
        val counts = IntArray(if (horizontal) rows else cols)
        for (i in cells) counts[if (horizontal) i / cols else i % cols]++
        val per = if (horizontal) cols else rows
        return counts.any { it == per }
    }

    private fun boxSize(cells: IntArray, cols: Int): String {
        val rs = cells.map { it / cols }
        val cs = cells.map { it % cols }
        return "${rs.max() - rs.min() + 1}x${cs.max() - cs.min() + 1}"
    }

    private fun isCross(cells: IntArray, cols: Int): Boolean {
        // In a 3x3 box a plus is the middle row and column; the sorted middle cell is the centre.
        val set = cells.toSet()
        val centre = cells[2]
        return listOf(centre - cols, centre + cols, centre - 1, centre + 1).all { it in set }
    }

    private fun isL(cells: IntArray, cols: Int): Boolean {
        val set = cells.toSet()
        val top = cells.minOf { it / cols }
        val left = cells.minOf { it % cols }
        for ((cr, cc) in listOf(top to left, top to left + 2, top + 2 to left, top + 2 to left + 2)) {
            val dr = if (cr == top) 1 else -1
            val dc = if (cc == left) 1 else -1
            val want = listOf(0 to 0, dr to 0, 2 * dr to 0, 0 to dc, 0 to 2 * dc)
            if (want.all { (r, c) -> (cr + r) * cols + cc + c in set }) return true
        }
        return false
    }

    /** A target area for a shape; `exact` ones must not touch other orbs of the same colour. */
    class Placement(val cells: IntArray, val exact: Boolean)

    /** All placements of a shape on the board, in the same order as `shapePlacements` in JS. */
    fun placements(type: String, rows: Int, cols: Int): List<Placement> {
        val out = ArrayList<Placement>()
        fun at(r: Int, c: Int) = r * cols + c
        fun add(cells: List<Int>, exact: Boolean) = out.add(Placement(cells.sorted().toIntArray(), exact))
        when (type) {
            "row" -> for (r in 0 until rows) add(List(cols) { c -> at(r, c) }, false)
            "column" -> for (c in 0 until cols) add(List(rows) { r -> at(r, c) }, false)
            "square" -> for (r in 0..rows - 3) for (c in 0..cols - 3) {
                add((0 until 3).flatMap { dr -> (0 until 3).map { dc -> at(r + dr, c + dc) } }, true)
            }
            "cross" -> for (r in 1 until rows - 1) for (c in 1 until cols - 1) {
                add(listOf(at(r, c), at(r - 1, c), at(r + 1, c), at(r, c - 1), at(r, c + 1)), true)
            }
            "lshape" -> for (r in 0..rows - 3) for (c in 0..cols - 3) {
                for ((cr, cc) in listOf(r to c, r to c + 2, r + 2 to c, r + 2 to c + 2)) {
                    val dr = if (cr == r) 1 else -1
                    val dc = if (cc == c) 1 else -1
                    add(listOf(at(cr, cc), at(cr + dr, cc), at(cr + 2 * dr, cc), at(cr, cc + dc), at(cr, cc + 2 * dc)), true)
                }
            }
            "tpa" -> {
                for (r in 0 until rows) for (c in 0..cols - 4) add(List(4) { k -> at(r, c + k) }, true)
                for (c in 0 until cols) for (r in 0..rows - 4) add(List(4) { k -> at(r + k, c) }, true)
            }
            else -> throw IllegalArgumentException("Unknown shape: $type")
        }
        return out
    }
}
