package dev.quickreplay.pad

/** One combo: [cells] sorted ascending; [pass] 0 = matched by the drag itself, 1+ = cascades. */
class Combo(val color: Int, val cells: IntArray, val pass: Int)

/** Result of [Matcher.simulate]. [cells] is a scratch buffer, valid until the next call. */
class Simulation(val combos: List<Combo>, val cells: IntArray)

/**
 * Match, combo grouping, gravity and cascades (no skyfall). Port of `matcher.js`.
 *
 * Rules (same as the dawnGlare simulator): 3+ orbs of one colour in a straight horizontal or
 * vertical line are matched; matched orbs of the same colour that touch orthogonally form one
 * combo. Matched orbs are removed, the rest fall, and the board is matched again until nothing
 * matches. Keeps scratch buffers, so one instance must not be used from two threads.
 */
class Matcher(private val rows: Int, private val cols: Int) {
    private val work = IntArray(rows * cols)
    private val matched = BooleanArray(rows * cols)
    private val seen = BooleanArray(rows * cols)
    private val stack = IntArray(rows * cols)

    fun simulate(cells: IntArray): Simulation {
        cells.copyInto(work)
        val combos = ArrayList<Combo>()
        var pass = 0
        while (findCombos(pass, combos) > 0) {
            removeMatched()
            applyGravity()
            pass++
        }
        return Simulation(combos, work)
    }

    private fun findCombos(pass: Int, out: MutableList<Combo>): Int {
        val board = work
        matched.fill(false)
        seen.fill(false)
        var any = false
        for (r in 0 until rows) {
            val base = r * cols
            var c = 0
            while (c < cols) {
                val color = board[base + c]
                var e = c + 1
                while (e < cols && board[base + e] == color) e++
                if (color != Orbs.EMPTY && e - c >= 3) {
                    for (k in c until e) matched[base + k] = true
                    any = true
                }
                c = e
            }
        }
        for (c in 0 until cols) {
            var r = 0
            while (r < rows) {
                val color = board[r * cols + c]
                var e = r + 1
                while (e < rows && board[e * cols + c] == color) e++
                if (color != Orbs.EMPTY && e - r >= 3) {
                    for (k in r until e) matched[k * cols + c] = true
                    any = true
                }
                r = e
            }
        }
        if (!any) return 0

        var count = 0
        for (start in 0 until rows * cols) {
            if (!matched[start] || seen[start]) continue
            val color = board[start]
            val comboCells = ArrayList<Int>()
            var top = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val i = stack[--top]
                comboCells += i
                val r = i / cols
                val c = i - r * cols
                if (r > 0) top = visit(i - cols, color, top)
                if (r < rows - 1) top = visit(i + cols, color, top)
                if (c > 0) top = visit(i - 1, color, top)
                if (c < cols - 1) top = visit(i + 1, color, top)
            }
            comboCells.sort()
            out += Combo(color, comboCells.toIntArray(), pass)
            count++
        }
        return count
    }

    private fun visit(j: Int, color: Int, top: Int): Int {
        if (matched[j] && !seen[j] && work[j] == color) {
            seen[j] = true
            stack[top] = j
            return top + 1
        }
        return top
    }

    private fun removeMatched() {
        for (i in work.indices) if (matched[i]) work[i] = Orbs.EMPTY
    }

    private fun applyGravity() {
        for (c in 0 until cols) {
            var write = rows - 1
            for (r in rows - 1 downTo 0) {
                val v = work[r * cols + c]
                if (v != Orbs.EMPTY) {
                    work[write * cols + c] = v
                    write--
                }
            }
            while (write >= 0) {
                work[write * cols + c] = Orbs.EMPTY
                write--
            }
        }
    }
}
