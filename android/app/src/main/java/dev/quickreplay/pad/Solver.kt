package dev.quickreplay.pad

/**
 * A goal: a combo shape of one colour ([Shape]), a minimum number of combos ([Combos]), one
 * combo of a colour with at least `min` connected orbs ([Blob]) or combos of at least `count`
 * distinct colours ([Colors]). Same model and JSON shape as the browser solver (`{type, color}` /
 * `{type: "combos", count}` / `{type: "blob", color, min, max?}` / `{type: "colors", count, of?}`).
 */
sealed interface Goal {
    val type: String
    val label: String

    /** A shape of one colour; [color] may be [Orbs.ANY]. */
    data class Shape(override val type: String, val color: Int) : Goal {
        init {
            require(type in Shapes.TYPES) { "Unknown goal type \"$type\"" }
            require(color == Orbs.ANY || color in 0 until Orbs.GOAL_COLORS) { "Unknown colour code $color" }
        }

        override val label: String get() = "${Shapes.LABELS.getValue(type)} of ${Orbs.goalName(color)}"
    }

    data class Combos(val count: Int) : Goal {
        init {
            require(count >= 1) { "Combo count must be >= 1" }
        }

        override val type: String get() = "combos"
        override val label: String get() = "$count+ combos"
    }

    /**
     * One combo of [color] with at least [min] and at most [max] connected orbs ([max] null: no
     * upper bound; [max] == [min]: exactly that many); a repeated goal needs another combo. [min]
     * and [max] are at most the largest board here; [PadSetup] and [Solver.solve] check the real board.
     * [color] may be [Orbs.ANY].
     */
    data class Blob(val color: Int, val min: Int, val max: Int? = null) : Goal {
        init {
            require(color == Orbs.ANY || color in 0 until Orbs.GOAL_COLORS) { "Unknown colour code $color" }
            require(min >= MIN) { "Blob size must be >= $MIN" }
            require(min <= Board.MAX_SIZE * Board.MAX_SIZE) { "Blob size must be at most ${Board.MAX_SIZE * Board.MAX_SIZE}" }
            if (max != null) {
                require(max >= min) { "Blob max must be >= min ($min)" }
                require(max <= Board.MAX_SIZE * Board.MAX_SIZE) { "Blob max must be at most ${Board.MAX_SIZE * Board.MAX_SIZE}" }
            }
        }

        override val type: String get() = "blob"
        override val label: String
            get() = when (max) {
                null -> "$min+ ${Orbs.goalName(color)} connected"
                min -> "exactly $min ${Orbs.goalName(color)}"
                else -> "$min-$max ${Orbs.goalName(color)} connected"
            }

        /** True when a combo of [size] orbs (of the colour) meets this goal. */
        fun accepts(size: Int): Boolean = size >= min && (max == null || size <= max)

        /** Throws [IllegalArgumentException] when the blob cannot fit on a [rows] x [cols] board. */
        fun checkSize(rows: Int, cols: Int) {
            require(min <= rows * cols) { "Blob size must be at most ${rows * cols} on a ${cols}x$rows board" }
            if (max != null) require(max <= rows * cols) { "Blob max must be at most ${rows * cols} on a ${cols}x$rows board" }
        }

        companion object {
            /** A combo has at least 3 orbs. */
            const val MIN = 3
        }
    }

    /**
     * Combos of at least [count] distinct colours of [set] (orb codes of R, B, G, L, D, H; default
     * the 5 attributes). Only membership matters: the order of [set] is kept for the JSON.
     */
    data class Colors(val count: Int, val set: List<Int> = DEFAULT_SET) : Goal {
        init {
            require(count in 1..Orbs.ATTRIBUTES) { "Colour count must be from 1 to ${Orbs.ATTRIBUTES}" }
            require(set.isNotEmpty() && set.toSet().size == set.size && set.all { it in 0 until Orbs.ATTRIBUTES }) {
                "Colour set must be a non-empty list of distinct colours R, B, G, L, D, H"
            }
            require(count <= set.size) { "Colour count must be at most ${set.size}, the colours in the set" }
        }

        override val type: String get() = "colors"

        /** Bit mask of [set]. */
        val mask: Int get() = set.fold(0) { m, color -> m or (1 shl color) }

        /** "3+ colours", or "2+ colours of Water, Heart" for a set other than the 5 attributes. */
        override val label: String
            get() {
                val mask = mask
                if (mask == DEFAULT_MASK) return "$count+ colours"
                val names = (0 until Orbs.ATTRIBUTES).filter { mask and (1 shl it) != 0 }.map { Orbs.NAMES[it] }
                return "$count+ colours of ${names.joinToString(", ")}"
            }

        companion object {
            /** R, B, G, L, D. */
            val DEFAULT_SET = listOf(0, 1, 2, 3, 4)
            private const val DEFAULT_MASK = 0b11111
        }
    }
}

data class SolveOptions(
    val diagonals: Boolean = true,
    val maxMoves: Int = 40,
    /** Width of the first search round; later rounds are 4x wider up to [maxBeamWidth]. */
    val beamWidth: Int = 1000,
    val maxBeamWidth: Int = 64000,
    val timeLimitMs: Long = 3000,
) {
    init {
        require(maxMoves >= 1 && beamWidth >= 1 && maxBeamWidth >= 1 && timeLimitMs >= 1) { "Solve options must be >= 1" }
    }
}

/**
 * [anyColor] is the letter an Any goal became (its [label] then names that colour and ends with
 * "(any colour)"); null for other goals and for impossible results.
 */
data class GoalResult(val label: String, val met: Boolean, val anyColor: Char? = null)

data class ComboInfo(val color: Char, val size: Int, val pass: Int, val shapes: List<String>)

data class SolveStats(
    /** Deepest depth explored by any round. */
    val depth: Int,
    val evaluated: Long,
    val rounds: Int,
    /** Width of the last round started (not necessarily the one that found the path). */
    val beamWidth: Int,
    val ms: Long,
    val timedOut: Boolean,
    /** Number of Any colour assignments searched (1 without Any goals, 0 when impossible). */
    val assignments: Int = 1,
)

data class SolveResult(
    /** "solved", "partial" or "impossible". */
    val status: String,
    val reasons: List<String>,
    /** Cell indices of the drag; the orb at `path[0]` is held. */
    val path: List<Int>,
    val goals: List<GoalResult>,
    val combos: List<ComboInfo>,
    /** Board after the drag, before matching (null when impossible). */
    val afterDrag: IntArray?,
    val stats: SolveStats,
) {
    val moves: Int get() = (path.size - 1).coerceAtLeast(0)
    val solved: Boolean get() = status == "solved"
}

/**
 * PAD drag-path solver, a port of `browser/extension/lib/pad/solver.js` that must return the same
 * paths (checked against `spec/pad-solver-vectors.json`).
 *
 * Beam search over drag paths: a state is (board, cursor cell), the held orb is always at the
 * cursor, so one move swaps the cursor cell with a neighbour. Every start cell is tried; each depth
 * keeps the best states by a heuristic score; repeated states are dropped with a Zobrist hash.
 * Anytime widening: rounds of width 1000, 4000, 16000, ... until the time limit; once a path is
 * found, later rounds only accept a shorter one (or as long with more combos).
 */
object Solver {
    private const val MET_SCORE = 1000.0
    private const val HAVE_WEIGHT = 10.0
    private const val LEAK_WEIGHT = 4.0
    private const val DISTANCE_WEIGHT = 2.0
    private const val PAIR_WEIGHT = 2.0
    private const val COMBO_WEIGHT = 2.0
    private const val COMBO_GOAL_WEIGHT = 40.0

    /** Same as solver.js: a board matching Poison or Mortal poison stays below every other board. */
    private const val POISON_PENALTY = 100_000.0
    private const val TIME_CHECK_INTERVAL = 256
    private const val SEEN_LIMIT = 1_000_000

    /** False for orbs the result must never match (Poison, Mortal poison) or cannot (unknown colour). */
    private fun isMatchable(code: Int): Boolean = code != Orbs.POISON && code != Orbs.MORTAL_POISON && code != Orbs.UNKNOWN

    private fun isPoison(combo: Combo): Boolean = combo.color == Orbs.POISON || combo.color == Orbs.MORTAL_POISON

    /** Label of the extra goal of a board with 3+ bombs (same as solver.js). */
    private fun bombsLabel(count: Int): String = "All $count bombs matched"

    /**
     * Checks orb counts and board size; returns the reasons the goals are impossible. Hazard orbs,
     * like solver.js: 1 or 2 bombs make every goal list impossible; Poison and Mortal poison goals
     * are impossible; Poison, Mortal poison and unknown orbs never count towards the combos possible.
     */
    fun checkFeasibility(board: Board, goals: List<Goal>): List<String> {
        val counts = countOrbs(board.cells)
        val need = IntArray(Orbs.COUNT)
        val rowGoals = IntArray(Orbs.COUNT)
        val columnGoals = IntArray(Orbs.COUNT)
        val reasons = ArrayList<String>()
        val bombs = counts[Orbs.BOMB]
        if (bombs in 1..2) {
            reasons += "Bomb: $bombs ${if (bombs == 1) "bomb" else "bombs"} on the board, all bombs must be matched and a match needs 3"
        }
        for (goal in goals) {
            val color = colorOf(goal)
            if (color == Orbs.POISON || color == Orbs.MORTAL_POISON) {
                reasons += "${goal.label}: Poison and Mortal poison orbs must never be matched"
                continue
            }
            when (goal) {
                is Goal.Combos -> {
                    val max = counts.indices.sumOf { if (isMatchable(it)) counts[it] / 3 else 0 }
                    if (goal.count > max) reasons += "${goal.label}: at most $max combos are possible on this board"
                }
                // A plain sum, like solver.js: a blob shares orbs with another goal only as its combo.
                // Any goals are only checked for size here: their colour is chosen in solve().
                is Goal.Blob -> if (goal.color != Orbs.ANY) need[goal.color] += goal.min
                // Each counted colour needs one combo of 3+ orbs; checked on its own, like Combos.
                is Goal.Colors -> {
                    val mask = goal.mask
                    val letters = (0 until Orbs.ATTRIBUTES).filter { mask and (1 shl it) != 0 && counts[it] >= 3 }.map { Orbs.LETTERS[it] }
                    val k = letters.size
                    if (k < goal.count) {
                        reasons += "${goal.label}: only $k ${if (k == 1) "colour has" else "colours have"} 3 or more orbs" +
                            (if (k > 0) " (${letters.joinToString(", ")})" else "") + ", the goal needs ${goal.count}"
                    }
                }
                is Goal.Shape -> {
                    if (!Shapes.fits(goal.type, board.rows, board.cols)) {
                        reasons += "${goal.label}: does not fit on a ${board.cols}x${board.rows} board"
                        continue
                    }
                    if (goal.color == Orbs.ANY) continue
                    need[goal.color] += Shapes.size(goal.type, board.rows, board.cols)
                    if (goal.type == "row") rowGoals[goal.color]++
                    if (goal.type == "column") columnGoals[goal.color]++
                }
            }
        }
        for (color in 0 until Orbs.COUNT) {
            // A row and a column of one colour may share their crossing orb (one combo).
            need[color] -= rowGoals[color] * columnGoals[color]
            if (need[color] > counts[color]) {
                reasons += "${Orbs.NAMES[color]}: the goals need ${need[color]} orbs, the board has ${counts[color]}"
            }
        }
        return reasons
    }

    /**
     * Solves a board. Any goals: like solver.js, the feasible colour assignments are ranked by the
     * orbs of their colours and the first [ANY_ASSIGNMENT_LIMIT] are searched, each with an equal
     * share of the time left; the best result wins (solved, more goals met, fewer moves; a tie keeps
     * the better stocked assignment).
     */
    fun solve(
        board: Board,
        goals: List<Goal>,
        options: SolveOptions = SolveOptions(),
        // Monotonic: a wall-clock change must not stretch or cut the time limit.
        now: () -> Long = { System.nanoTime() / 1_000_000 },
    ): SolveResult {
        val started = now()
        // Like normalizeGoals(goals, size) in JS: a blob larger than the board is an invalid goal.
        for (goal in goals) if (goal is Goal.Blob) goal.checkSize(board.rows, board.cols)
        val labels = goals.map { it.label }
        val anyIndices = goals.indices.filter { colorOf(goals[it]) == Orbs.ANY }
        var reasons = checkFeasibility(board, goals)
        var assignments = listOf(emptyList<Int>())
        if (reasons.isEmpty() && anyIndices.isNotEmpty()) {
            assignments = anyAssignments(board, goals, anyIndices)
            if (assignments.isEmpty()) {
                val names = anyIndices.joinToString(", ") { "\"${labels[it]}\"" }
                reasons = listOf("Any colour: no assignment of distinct colours has enough orbs for $names")
            }
        }
        if (reasons.isNotEmpty()) {
            return SolveResult(
                "impossible", reasons, emptyList(), labels.map { GoalResult(it, false) }, emptyList(), null,
                SolveStats(0, 0, 0, 0, now() - started, false, 0),
            )
        }

        // 3+ bombs: an extra goal, met when every bomb is in a combo (1 or 2 failed the feasibility check).
        val bombs = countOrbs(board.cells)[Orbs.BOMB]
        val deadline = started + options.timeLimitMs
        var best: Found? = null
        var bestGoals: List<Goal> = goals
        var evaluated = 0L
        var rounds = 0
        var depth = 0
        var timedOut = false
        assignments.forEachIndexed { k, assignment ->
            val resolved = resolveAny(goals, anyIndices, assignment)
            // The last assignment (the only one without Any goals) ends exactly at the time limit.
            val sliceStart = now()
            val left = assignments.size - k
            val sliceEnd = if (left == 1) deadline else sliceStart + (deadline - sliceStart) / left
            val found = searchGoals(board, resolved, bombs, options) { now() > sliceEnd }
            evaluated += found.evaluated
            rounds += found.rounds
            depth = maxOf(depth, found.depth)
            timedOut = timedOut || found.timedOut
            if (best.let { it == null || betterResult(found, it) }) {
                best = found
                bestGoals = resolved
            }
        }

        val result = best!!
        val extraLabels = if (bombs > 0) listOf(bombsLabel(bombs)) else emptyList()
        val stats = SolveStats(depth, evaluated, rounds, result.beamWidth, now() - started, timedOut, assignments.size)
        if (result.poisoned) {
            return SolveResult(
                "impossible", listOf("Poison: every path found matches Poison or Mortal poison orbs"), emptyList(),
                (bestGoals.map { it.label } + extraLabels).map { GoalResult(it, false) }, emptyList(), null, stats,
            )
        }
        return SolveResult(
            status = if (result.met.all { it }) "solved" else "partial",
            reasons = emptyList(),
            path = result.path,
            goals = bestGoals.mapIndexed { i, goal ->
                if (i in anyIndices) {
                    GoalResult("${goal.label} (any colour)", result.met[i], Orbs.LETTERS[colorOf(goal)!!])
                } else {
                    GoalResult(labels[i], result.met[i])
                }
            } + extraLabels.mapIndexed { k, label -> GoalResult(label, result.met[goals.size + k]) },
            combos = result.combos.map { combo ->
                ComboInfo(
                    Orbs.LETTERS[combo.color], combo.cells.size, combo.pass,
                    Shapes.TYPES.filter { Shapes.has(it, combo.cells, board.rows, board.cols) },
                )
            },
            afterDrag = result.afterDrag,
            stats = stats,
        )
    }

    /** At most this many colour assignments of the Any goals are searched (same as solver.js). */
    const val ANY_ASSIGNMENT_LIMIT = 6

    private fun colorOf(goal: Goal): Int? = when (goal) {
        is Goal.Shape -> goal.color
        is Goal.Blob -> goal.color
        is Goal.Combos, is Goal.Colors -> null
    }

    /** [goal] with [color] in place of its colour (Shape and Blob goals only). */
    private fun withColor(goal: Goal, color: Int): Goal = when (goal) {
        is Goal.Shape -> goal.copy(color = color)
        is Goal.Blob -> goal.copy(color = color)
        is Goal.Combos, is Goal.Colors -> goal
    }

    private fun resolveAny(goals: List<Goal>, anyIndices: List<Int>, assignment: List<Int>): List<Goal> {
        if (assignment.isEmpty()) return goals
        val resolved = goals.toMutableList()
        anyIndices.forEachIndexed { k, index -> resolved[index] = withColor(goals[index], assignment[k]) }
        return resolved
    }

    /**
     * Feasible colour assignments of the Any goals (pairwise different colours of [Orbs.ANY_COLORS]),
     * best stocked first, at most [ANY_ASSIGNMENT_LIMIT]; identical Any goals get their colours in
     * colour order. Same order as `anyAssignments()` in solver.js: generated in colour order, then
     * stably sorted.
     */
    private fun anyAssignments(board: Board, goals: List<Goal>, anyIndices: List<Int>): List<List<Int>> {
        val counts = countOrbs(board.cells)
        val anyGoals = anyIndices.map { goals[it] }
        val found = ArrayList<List<Int>>()
        val current = ArrayList<Int>()
        fun pick() {
            val k = current.size
            if (k == anyIndices.size) {
                if (checkFeasibility(board, resolveAny(goals, anyIndices, current)).isEmpty()) found += current.toList()
                return
            }
            // Two identical Any goals swapped are the same problem: keep their colours in colour order.
            val floor = current.indices.filter { anyGoals[it] == anyGoals[k] }.maxOfOrNull { current[it] } ?: -1
            for (color in Orbs.ANY_COLORS) {
                if (color in current || color < floor) continue
                current += color
                pick()
                current.removeAt(current.size - 1)
            }
        }
        pick()
        val sorted = found.sortedWith { a, b ->
            var order = 0
            for (k in a.indices) {
                if (counts[a[k]] != counts[b[k]]) {
                    order = counts[b[k]] - counts[a[k]]
                    break
                }
            }
            order
        }
        return sorted.take(ANY_ASSIGNMENT_LIMIT)
    }

    /** Result of one search (goals without Any colours). */
    private class Found(
        val path: List<Int>,
        val afterDrag: IntArray,
        val combos: List<Combo>,
        val met: List<Boolean>,
        /** The path matches Poison or Mortal poison (only when every path found does). */
        val poisoned: Boolean,
        val evaluated: Long,
        val rounds: Int,
        val depth: Int,
        val beamWidth: Int,
        val timedOut: Boolean,
    )

    /** Same ranking as `betterResult()` in solver.js: no poison, solved, then more goals met, then fewer moves. */
    private fun betterResult(a: Found, b: Found): Boolean {
        if (a.poisoned != b.poisoned) return b.poisoned
        val solvedA = a.met.all { it }
        val solvedB = b.met.all { it }
        if (solvedA != solvedB) return solvedA
        val metA = a.met.count { it }
        val metB = b.met.count { it }
        if (metA != metB) return metA > metB
        return a.path.size < b.path.size
    }

    /** [bombs] > 0: an extra goal after [goals], met when every bomb is matched. */
    private fun searchGoals(board: Board, goals: List<Goal>, bombs: Int, options: SolveOptions, timeUp: () -> Boolean): Found {
        val search = Search(
            // Private copy: the search swaps orbs in place while scoring.
            cells = board.cells.copyOf(),
            goalCount = goals.size + (if (bombs > 0) 1 else 0),
            evaluator = Evaluator(board.rows, board.cols, goals, options.diagonals, board.cells, bombs),
            neighbours = buildNeighbours(board.rows, board.cols, options.diagonals, board.cells),
            zobrist = Zobrist(board.rows * board.cols),
            timeUp = timeUp,
        )

        var best: Node? = null
        var width = options.beamWidth
        var maxDepth = options.maxMoves
        var rounds = 0
        var evaluated = 0L
        var depth = 0
        var timedOut = false
        while (true) {
            rounds++
            val round = beamRound(search, width, maxDepth)
            evaluated += round.evaluated
            depth = maxOf(depth, round.depth)
            val improved = round.best != null && better(round.best, best, search.goalCount)
            if (improved) best = round.best
            if (round.timedOut) {
                timedOut = true
                break
            }
            val solved = best != null && search.goalCount > 0 && best.met == search.goalCount
            // One move is the shortest possible path.
            if (width >= options.maxBeamWidth || (solved && best!!.depth == 1)) break
            if (search.timeUp()) {
                timedOut = true
                break
            }
            if (solved) maxDepth = best!!.depth
            width = minOf(width * 4, options.maxBeamWidth)
        }

        val path = best?.let(::pathOf).orEmpty()
        val afterDrag = applyPath(board.cells, path)
        val sim = search.evaluator.matcher.simulate(afterDrag)
        return Found(
            path, afterDrag, sim.combos, search.evaluator.goalsMet(sim.combos), sim.combos.any(::isPoison),
            evaluated, rounds, depth, width, timedOut,
        )
    }

    private class Node(
        var cells: IntArray?,
        val cursor: Int,
        val prev: Int,
        val parent: Node?,
        val h1: Int,
        val h2: Int,
        val score: Double,
        val met: Int,
        val combos: Int,
        val depth: Int,
    )

    private class Search(
        val cells: IntArray,
        val goalCount: Int,
        val evaluator: Evaluator,
        val neighbours: Array<IntArray>,
        val zobrist: Zobrist,
        val timeUp: () -> Boolean,
    )

    private class Round(val best: Node?, val evaluated: Long, val depth: Int, val timedOut: Boolean)

    /** Same ranking as `better()` in solver.js. */
    private fun better(a: Node, b: Node?, goalCount: Int): Boolean {
        if (b == null) return true
        if (a.met != b.met) return a.met > b.met
        if (goalCount == 0) return if (a.combos != b.combos) a.combos > b.combos else a.depth < b.depth
        if (a.met == goalCount) return if (a.depth != b.depth) a.depth < b.depth else a.combos > b.combos
        return if (a.score != b.score) a.score > b.score else a.depth < b.depth
    }

    private fun beamRound(search: Search, width: Int, maxDepth: Int): Round {
        val cells = search.cells
        val evaluator = search.evaluator
        val zobrist = search.zobrist
        val seen = LongSet()
        var beam = ArrayList<Node>()
        for (start in cells.indices) {
            // An unknown orb is never held or swapped.
            if (cells[start] == Orbs.UNKNOWN) continue
            val (h1, h2) = zobrist.hashOf(cells, start)
            beam += Node(cells, start, -1, null, h1, h2, 0.0, 0, 0, 0)
            seen.add(key(h1, h2))
        }
        var best: Node? = null
        var evaluated = 0L
        var depth = 0
        while (depth < maxDepth) {
            depth++
            val candidates = ArrayList<Node>()
            for (state in beam) {
                val board = state.cells!!
                val a = state.cursor
                for (next in search.neighbours[a]) {
                    if (next == state.prev) continue
                    val ca = board[a]
                    val cb = board[next]
                    val h1 = state.h1 xor zobrist.swap1(a, next, ca, cb)
                    val h2 = state.h2 xor zobrist.swap2(a, next, ca, cb)
                    val k = key(h1, h2)
                    if (k in seen) continue
                    if (seen.size >= SEEN_LIMIT) seen.clear()
                    seen.add(k)
                    board[a] = cb
                    board[next] = ca
                    val score = evaluator.score(board)
                    board[a] = ca
                    board[next] = cb
                    val node = Node(null, next, a, state, h1, h2, score, evaluator.met, evaluator.combos, depth)
                    candidates += node
                    if (better(node, best, search.goalCount)) best = node
                    if (++evaluated % TIME_CHECK_INTERVAL == 0L && search.timeUp()) return Round(best, evaluated, depth, true)
                }
            }
            if (candidates.isEmpty()) break
            if (search.goalCount > 0 && best!!.met == search.goalCount) break
            candidates.sortByDescending { it.score } // stable, like Array.prototype.sort
            val kept = if (candidates.size > width) candidates.subList(0, width) else candidates
            for (node in kept) {
                val parentCells = node.parent!!.cells!!
                val copy = parentCells.copyOf()
                copy[node.prev] = parentCells[node.cursor]
                copy[node.cursor] = parentCells[node.prev]
                node.cells = copy
            }
            // Boards of the previous depth are no longer needed (paths only use the cursors).
            for (state in beam) state.cells = null
            beam = ArrayList(kept)
            if (search.timeUp()) return Round(best, evaluated, depth, true)
        }
        return Round(best, evaluated, depth, false)
    }

    /** Same key as solver.js: 32 bits of h1 and the top 21 bits of h2. */
    private fun key(h1: Int, h2: Int): Long = ((h1.toLong() and 0xffffffffL) shl 21) + (h2 ushr 11).toLong()

    private fun pathOf(node: Node): List<Int> {
        val path = ArrayList<Int>()
        var n: Node? = node
        while (n != null) {
            path += n.cursor
            n = n.parent
        }
        return path.reversed()
    }

    /** Cells a move may go to from each cell; cells holding an unknown orb are left out. */
    private fun buildNeighbours(rows: Int, cols: Int, diagonals: Boolean, cells: IntArray): Array<IntArray> {
        val dirs = mutableListOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        if (diagonals) dirs += listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        return Array(rows * cols) { i ->
            val r = i / cols
            val c = i % cols
            dirs.mapNotNull { (dr, dc) ->
                val nr = r + dr
                val nc = c + dc
                if (nr in 0 until rows && nc in 0 until cols && cells[nr * cols + nc] != Orbs.UNKNOWN) nr * cols + nc else null
            }.toIntArray()
        }
    }

    /** Zobrist hashing of (board, cursor) with two 32-bit keys; same random sequence as solver.js. */
    private class Zobrist(n: Int) {
        private val orb1: IntArray
        private val orb2: IntArray
        private val cur1: IntArray
        private val cur2: IntArray

        init {
            val random = mulberry32(0x5eed)
            orb1 = IntArray(n * Orbs.COUNT) { random() }
            orb2 = IntArray(n * Orbs.COUNT) { random() }
            cur1 = IntArray(n) { random() }
            cur2 = IntArray(n) { random() }
        }

        fun hashOf(cells: IntArray, cursor: Int): Pair<Int, Int> {
            var h1 = cur1[cursor]
            var h2 = cur2[cursor]
            for (i in cells.indices) {
                h1 = h1 xor orb1[i * Orbs.COUNT + cells[i]]
                h2 = h2 xor orb2[i * Orbs.COUNT + cells[i]]
            }
            return h1 to h2
        }

        fun swap1(a: Int, b: Int, ca: Int, cb: Int): Int =
            orb1[a * Orbs.COUNT + ca] xor orb1[b * Orbs.COUNT + cb] xor orb1[a * Orbs.COUNT + cb] xor orb1[b * Orbs.COUNT + ca] xor
                cur1[a] xor cur1[b]

        fun swap2(a: Int, b: Int, ca: Int, cb: Int): Int =
            orb2[a * Orbs.COUNT + ca] xor orb2[b * Orbs.COUNT + cb] xor orb2[a * Orbs.COUNT + cb] xor orb2[b * Orbs.COUNT + ca] xor
                cur2[a] xor cur2[b]

        /** mulberry32 on 32-bit ints (Int overflow wraps like Math.imul / `>>> 0` in JS). */
        private fun mulberry32(seed: Int): () -> Int {
            var s = seed
            return {
                s += 0x6d2b79f5
                var t = s
                t = (t xor (t ushr 15)) * (t or 1)
                t = t xor (t + (t xor (t ushr 7)) * (t or 61))
                t xor (t ushr 14)
            }
        }
    }

    /**
     * Scores boards against a goal list (allocation-free in the hot path). Port of `Evaluator`.
     * [start] is the board before the drag: shape and blob placements on or next to an unknown orb
     * are left out, unless that leaves none. [bombs] > 0 adds a last goal: every bomb matched.
     */
    private class Evaluator(
        private val rows: Int,
        private val cols: Int,
        private val goals: List<Goal>,
        diagonals: Boolean,
        start: IntArray,
        bombs: Int,
    ) {
        val matcher = Matcher(rows, cols)
        private val n = rows * cols
        private val dist = IntArray(n * n)
        private val maxDist = rows + cols
        private val histogram = IntArray(maxDist + 1)
        private val empty = IntArray(n)

        /** Goals met and combo count of the last [score] call. */
        var met = 0
            private set
        var combos = 0
            private set

        private class Group(
            val type: String,
            val color: Int,
            /** Blob goal of the group; null for other goals. */
            val blob: Goal.Blob?,
            val indices: MutableList<Int>,
            val placements: List<Shapes.Placement>,
            val borders: List<IntArray>,
            val insides: List<BooleanArray>,
            /** Number of bombs of the "bombs" group (0 for other groups). */
            val count: Int = 0,
        ) {
            val values = DoubleArray(placements.size)
            val haves = IntArray(placements.size)
        }

        private val groups = LinkedHashMap<String, Group>()

        /** Colour bit mask of each Colors goal (0 for other goals). */
        private val masks = IntArray(goals.size) { (goals[it] as? Goal.Colors)?.mask ?: 0 }

        /** Number of distinct colours of [mask] among the combos. */
        private fun colorCount(combos: List<Combo>, mask: Int): Int {
            var seen = 0
            for (combo in combos) seen = seen or ((1 shl combo.color) and mask)
            return Integer.bitCount(seen)
        }

        init {
            for (i in 0 until n) {
                for (j in 0 until n) {
                    val dr = Math.abs(i / cols - j / cols)
                    val dc = Math.abs(i % cols - j % cols)
                    dist[i * n + j] = if (diagonals) maxOf(dr, dc) else dr + dc
                }
            }
            // Goals that share type and colour (and blob size range) need distinct combos; group them.
            // Colors goals are about the whole turn, like Combos: one group, each goal checked alone.
            goals.forEachIndexed { index, goal ->
                val k = when (goal) {
                    is Goal.Shape -> "${goal.type}:${goal.color}"
                    is Goal.Blob -> "blob:${goal.color}:${goal.min}:${goal.max ?: ""}"
                    is Goal.Combos -> "combos"
                    is Goal.Colors -> "colors"
                }
                val group = groups.getOrPut(k) {
                    var placements = when (goal) {
                        is Goal.Shape -> Shapes.placements(goal.type, rows, cols)
                        is Goal.Blob -> Shapes.blobPlacements(goal.min, rows, cols, goal.max ?: Int.MAX_VALUE)
                        is Goal.Combos, is Goal.Colors -> emptyList()
                    }
                    val clear = placements.filter { !touchesUnknown(it.cells, start) }
                    if (clear.isNotEmpty()) placements = clear
                    Group(
                        goal.type,
                        when (goal) {
                            is Goal.Shape -> goal.color
                            is Goal.Blob -> goal.color
                            is Goal.Combos, is Goal.Colors -> -1
                        },
                        goal as? Goal.Blob,
                        ArrayList(),
                        placements,
                        placements.map { p -> if (p.exact) borderOf(p.cells) else IntArray(0) },
                        placements.map { p -> BooleanArray(n).also { inside -> for (i in p.cells) inside[i] = true } },
                    )
                }
                group.indices += index
            }
            if (bombs > 0) {
                // Every bomb in one line of exactly that many cells (or the smallest rectangle that holds them).
                val placements = Shapes.blobPlacements(bombs, rows, cols, bombs)
                groups["bombs"] = Group(
                    "bombs",
                    Orbs.BOMB,
                    null,
                    mutableListOf(goals.size),
                    placements,
                    placements.map { p -> if (p.exact) borderOf(p.cells) else IntArray(0) },
                    placements.map { p -> BooleanArray(n).also { inside -> for (i in p.cells) inside[i] = true } },
                    bombs,
                )
            }
        }

        private val goalCount = goals.size + (if (bombs > 0) 1 else 0)

        /** True when [placement] holds an unknown orb of [cells] or touches one orthogonally. */
        private fun touchesUnknown(placement: IntArray, cells: IntArray): Boolean {
            for (i in placement) {
                if (cells[i] == Orbs.UNKNOWN) return true
                val r = i / cols
                val c = i % cols
                if ((r > 0 && cells[i - cols] == Orbs.UNKNOWN) || (r < rows - 1 && cells[i + cols] == Orbs.UNKNOWN) ||
                    (c > 0 && cells[i - 1] == Orbs.UNKNOWN) || (c < cols - 1 && cells[i + 1] == Orbs.UNKNOWN)
                ) {
                    return true
                }
            }
            return false
        }

        /** Per-goal met flags for a list of combos (the bombs goal last). */
        fun goalsMet(combos: List<Combo>): List<Boolean> {
            val met = MutableList(goalCount) { false }
            for (group in groups.values) {
                val available = available(group, combos)
                group.indices.forEachIndexed { k, index ->
                    met[index] = when (group.type) {
                        "combos" -> combos.size >= (goals[index] as Goal.Combos).count
                        "colors" -> colorCount(combos, masks[index]) >= (goals[index] as Goal.Colors).count
                        else -> k < available
                    }
                }
            }
            return met
        }

        /**
         * Combos of a group's colour that meet its goals ("bombs": 1 when every bomb is matched, else 0).
         * A shape or blob combo next to an unknown orb does not count.
         */
        private fun available(group: Group, combos: List<Combo>): Int {
            if (group.type == "combos" || group.type == "colors") return combos.size
            if (group.type == "bombs") {
                var matched = 0
                for (combo in combos) if (combo.color == Orbs.BOMB) matched += combo.cells.size
                return if (matched >= group.count) 1 else 0
            }
            return combos.count {
                it.color == group.color && !it.touchesUnknown &&
                    (group.blob?.accepts(it.cells.size) ?: Shapes.has(group.type, it.cells, rows, cols))
            }
        }

        fun score(cells: IntArray): Double {
            val combos = matcher.simulate(cells).combos
            var score = combos.size * COMBO_WEIGHT
            var met = 0
            var pairsAdded = false
            for (group in groups.values) {
                if (group.type == "combos") {
                    for (index in group.indices) {
                        val want = (goals[index] as Goal.Combos).count
                        if (combos.size >= want) {
                            score += MET_SCORE
                            met++
                        } else {
                            score += COMBO_GOAL_WEIGHT * combos.size / want
                            if (!pairsAdded) {
                                score += PAIR_WEIGHT * pairs(cells)
                                pairsAdded = true
                            }
                        }
                    }
                    continue
                }
                if (group.type == "colors") {
                    // Like Combos: the share of the colours reached, plus touching pairs (once per board).
                    for (index in group.indices) {
                        val want = (goals[index] as Goal.Colors).count
                        val have = colorCount(combos, masks[index])
                        if (have >= want) {
                            score += MET_SCORE
                            met++
                        } else {
                            score += COMBO_GOAL_WEIGHT * have / want
                            if (!pairsAdded) {
                                score += PAIR_WEIGHT * pairs(cells)
                                pairsAdded = true
                            }
                        }
                    }
                    continue
                }
                val available = minOf(available(group, combos), group.indices.size)
                met += available
                score += available * MET_SCORE
                val missing = group.indices.size - available
                if (missing > 0) score += progress(group, cells, available, missing)
            }
            // A board matching Poison or Mortal poison is never an answer: below every other board.
            if (combos.any(::isPoison)) {
                score -= POISON_PENALTY
                met = -1
            }
            this.met = met
            this.combos = combos.size
            return score
        }

        private fun progress(group: Group, cells: IntArray, skip: Int, count: Int): Double {
            val values = group.values
            val haves = group.haves
            var bestIndex = 0
            for (p in group.placements.indices) {
                val target = group.placements[p].cells
                val border = group.borders[p]
                var have = 0
                for (i in target) if (cells[i] == group.color) have++
                var leak = 0
                for (i in border) if (cells[i] == group.color) leak++
                haves[p] = have
                values[p] = HAVE_WEIGHT * have - LEAK_WEIGHT * leak
                if (values[p] > values[bestIndex]) bestIndex = p
            }
            if (skip == 0 && count == 1) {
                return values[bestIndex] - DISTANCE_WEIGHT * distance(group, bestIndex, haves[bestIndex], cells)
            }
            // Several unmet goals of one type and colour (rare): take the next best placements.
            val order = values.indices.sortedByDescending { values[it] }
            var total = 0.0
            for (k in 0 until count) {
                val p = order[minOf(skip + k, order.size - 1)]
                total += values[p]
                if (k == 0) total -= DISTANCE_WEIGHT * distance(group, p, haves[p], cells)
            }
            return total
        }

        /** Sum of the move distances of the `missing` nearest loose orbs to the placement's gaps. */
        private fun distance(group: Group, p: Int, have: Int, cells: IntArray): Int {
            val target = group.placements[p].cells
            val missing = target.size - have
            if (missing <= 0) return 0
            val inside = group.insides[p]
            var gaps = 0
            for (j in target) if (cells[j] != group.color) empty[gaps++] = j
            histogram.fill(0)
            for (i in 0 until n) {
                if (cells[i] != group.color || inside[i]) continue
                var d = maxDist
                val row = i * n
                for (k in 0 until gaps) {
                    val v = dist[row + empty[k]]
                    if (v < d) d = v
                }
                histogram[d]++
            }
            var sum = 0
            var left = missing
            var d = 0
            while (d <= maxDist && left > 0) {
                val take = minOf(left, histogram[d])
                sum += take * d
                left -= take
                d++
            }
            return sum + left * maxDist
        }

        /** Number of horizontally or vertically touching pairs of same-colour orbs (not Poison, Mortal poison or unknown). */
        private fun pairs(cells: IntArray): Int {
            var count = 0
            for (r in 0 until rows) {
                for (c in 0 until cols - 1) {
                    val i = r * cols + c
                    if (cells[i] == cells[i + 1] && isMatchable(cells[i])) count++
                }
            }
            for (i in 0 until n - cols) if (cells[i] == cells[i + cols] && isMatchable(cells[i])) count++
            return count
        }

        private fun borderOf(cells: IntArray): IntArray {
            val inside = cells.toSet()
            val out = LinkedHashSet<Int>()
            for (i in cells) {
                val r = i / cols
                val c = i % cols
                for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nr = r + dr
                    val nc = c + dc
                    val j = nr * cols + nc
                    if (nr in 0 until rows && nc in 0 until cols && j !in inside) out += j
                }
            }
            return out.toIntArray()
        }
    }
}
