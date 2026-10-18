package dev.quickreplay.pad

/**
 * Puzzle & Dragons board model. Kotlin port of `browser/extension/lib/pad/board.js`; both must
 * give the same results (checked by the shared vectors in `spec/pad-solver-vectors.json`).
 */
object Orbs {
    /** Orb letters, indexed by orb code. R Fire, B Water, G Wood, L Light, D Dark, H Heart, J Jammer, P Poison, M Mortal poison. */
    val LETTERS = charArrayOf('R', 'B', 'G', 'L', 'D', 'H', 'J', 'P', 'M')
    val NAMES = listOf("Fire", "Water", "Wood", "Light", "Dark", "Heart", "Jammer", "Poison", "Mortal poison")
    const val COUNT = 9

    /** Code of a cell emptied by a match (only used while simulating). */
    const val EMPTY = 255

    /** Number of attribute colours (R B G L D H) that can be goal colours. */
    const val ATTRIBUTES = 6

    /** Orb code of a letter (case-insensitive), or -1. */
    fun code(letter: Char): Int = LETTERS.indexOf(letter.uppercaseChar())
}

/** A board: [cells] holds orb codes in row-major order (index = row * cols + col, row 0 at the top). */
class Board(val rows: Int, val cols: Int, val cells: IntArray) {
    init {
        checkSize(rows, cols)
        require(cells.size == rows * cols) { "cells must have rows * cols entries" }
    }

    /** Letter rows, top to bottom; empty cells are ".". */
    fun format(values: IntArray = cells): String = (0 until rows).joinToString("\n") { r ->
        (0 until cols).joinToString("") { c ->
            val code = values[r * cols + c]
            if (code == Orbs.EMPTY) "." else Orbs.LETTERS[code].toString()
        }
    }

    companion object {
        const val MIN_SIZE = 3
        const val MAX_SIZE = 9

        fun checkSize(rows: Int, cols: Int) {
            require(rows in MIN_SIZE..MAX_SIZE) { "Board rows must be an integer from $MIN_SIZE to $MAX_SIZE" }
            require(cols in MIN_SIZE..MAX_SIZE) { "Board cols must be an integer from $MIN_SIZE to $MAX_SIZE" }
        }

        /** Parses letter rows (one non-empty line per row; whitespace inside a line is ignored). */
        fun parse(text: String): Board {
            val lines = text.lines().map { line -> line.filterNot(Char::isWhitespace) }.filter(String::isNotEmpty)
            require(lines.size > 1) { "Board size is required for single-line input" }
            val cols = lines.first().length
            require(lines.all { it.length == cols }) { "All board rows must have the same length" }
            return parse(lines.joinToString(""), lines.size, cols)
        }

        /** Parses [letters] (whitespace ignored) as a board of [rows] x [cols]. */
        fun parse(letters: String, rows: Int, cols: Int): Board {
            checkSize(rows, cols)
            val compact = letters.filterNot(Char::isWhitespace)
            require(compact.length == rows * cols) {
                "Expected ${rows * cols} orbs for a ${cols}x$rows board, got ${compact.length}"
            }
            val cells = IntArray(compact.length) { i ->
                Orbs.code(compact[i]).also { require(it >= 0) { "Unknown orb letter \"${compact[i]}\" at position ${i + 1}" } }
            }
            return Board(rows, cols, cells)
        }
    }
}

/** Number of orbs of each code. */
fun countOrbs(cells: IntArray): IntArray {
    val counts = IntArray(Orbs.COUNT)
    for (code in cells) if (code != Orbs.EMPTY) counts[code]++
    return counts
}

/** "r1c1" style label (1-based) for a cell index. */
fun cellLabel(index: Int, cols: Int): String = "r${index / cols + 1}c${index % cols + 1}"

/**
 * Applies a drag path: the orb at `path[0]` is held and swapped with every following cell.
 * Returns the board after the drag, before any match; [cells] is not modified.
 */
fun applyPath(cells: IntArray, path: List<Int>): IntArray {
    val out = cells.copyOf()
    for (i in 1 until path.size) {
        val a = path[i - 1]
        val b = path[i]
        val held = out[a]
        out[a] = out[b]
        out[b] = held
    }
    return out
}
