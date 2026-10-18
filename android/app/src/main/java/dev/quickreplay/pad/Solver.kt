package dev.quickreplay.pad

/**
 * A goal: a combo shape of one colour ([Shape]) or a minimum number of combos ([Combos]).
 * Same model and JSON shape as the browser solver (`{type, color}` / `{type: "combos", count}`).
 */
sealed interface Goal {
    val type: String
    val label: String

    data class Shape(override val type: String, val color: Int) : Goal {
        init {
            require(type in Shapes.TYPES) { "Unknown goal type \"$type\"" }
            require(color in 0 until Orbs.COUNT) { "Unknown colour code $color" }
        }

        override val label: String get() = "${Shapes.LABELS.getValue(type)} of ${Orbs.NAMES[color]}"
    }

    data class Combos(val count: Int) : Goal {
        init {
            require(count >= 1) { "Combo count must be >= 1" }
        }

        override val type: String get() = "combos"
        override val label: String get() = "$count+ combos"
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

data class GoalResult(val label: String, val met: Boolean)

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
    private const val TIME_CHECK_INTERVAL = 256
    private const val SEEN_LIMIT = 1_000_000

    /** Checks orb counts and board size; returns the reasons the goals are impossible. */
    fun checkFeasibility(board: Board, goals: List<Goal>): List<String> {
        val counts = countOrbs(board.cells)
        val need = IntArray(Orbs.COUNT)
        val rowGoals = IntArray(Orbs.COUNT)
        val columnGoals = IntArray(Orbs.COUNT)
        val reasons = ArrayList<String>()
        for (goal in goals) {
            when (goal) {
                is Goal.Combos -> {
                    val max = counts.sumOf { it / 3 }
                    if (goal.count > max) reasons += "${goal.label}: at most $max combos are possible on this board"
                }
                is Goal.Shape -> {
                    if (!Shapes.fits(goal.type, board.rows, board.cols)) {
                        reasons += "${goal.label}: does not fit on a ${board.cols}x${board.rows} board"
                        continue
                    }
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

    fun solve(
        board: Board,
        goals: List<Goal>,
        options: SolveOptions = SolveOptions(),
        // Monotonic: a wall-clock change must not stretch or cut the time limit.
        now: () -> Long = { System.nanoTime() / 1_000_000 },
    ): SolveResult {
        val started = now()
        val labels = goals.map { it.label }
        val reasons = checkFeasibility(board, goals)
        if (reasons.isNotEmpty()) {
            return SolveResult(
                "impossible", reasons, emptyList(), labels.map { GoalResult(it, false) }, emptyList(), null,
                SolveStats(0, 0, 0, 0, now() - started, false),
            )
        }
        val search = Search(
            // Private copy: the search swaps orbs in place while scoring.
            cells = board.cells.copyOf(),
            goalCount = goals.size,
            evaluator = Evaluator(board.rows, board.cols, goals, options.diagonals),
            neighbours = buildNeighbours(board.rows, board.cols, options.diagonals),
            zobrist = Zobrist(board.rows * board.cols),
            timeUp = { now() - started > options.timeLimitMs },
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
        val met = search.evaluator.goalsMet(sim.combos)
        return SolveResult(
            status = if (met.all { it }) "solved" else "partial",
            reasons = emptyList(),
            path = path,
            goals = labels.mapIndexed { i, label -> GoalResult(label, met[i]) },
            combos = sim.combos.map { combo ->
                ComboInfo(
                    Orbs.LETTERS[combo.color], combo.cells.size, combo.pass,
                    Shapes.TYPES.filter { Shapes.has(it, combo.cells, board.rows, board.cols) },
                )
            },
            afterDrag = afterDrag,
            stats = SolveStats(depth, evaluated, rounds, width, now() - started, timedOut),
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

    private fun buildNeighbours(rows: Int, cols: Int, diagonals: Boolean): Array<IntArray> {
        val dirs = mutableListOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        if (diagonals) dirs += listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        return Array(rows * cols) { i ->
            val r = i / cols
            val c = i % cols
            dirs.mapNotNull { (dr, dc) ->
                val nr = r + dr
                val nc = c + dc
                if (nr in 0 until rows && nc in 0 until cols) nr * cols + nc else null
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

    /** Scores boards against a goal list (allocation-free in the hot path). Port of `Evaluator`. */
    private class Evaluator(private val rows: Int, private val cols: Int, private val goals: List<Goal>, diagonals: Boolean) {
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
            val indices: MutableList<Int>,
            val placements: List<Shapes.Placement>,
            val borders: List<IntArray>,
            val insides: List<BooleanArray>,
        ) {
            val values = DoubleArray(placements.size)
            val haves = IntArray(placements.size)
        }

        private val groups = LinkedHashMap<String, Group>()

        init {
            for (i in 0 until n) {
                for (j in 0 until n) {
                    val dr = Math.abs(i / cols - j / cols)
                    val dc = Math.abs(i % cols - j % cols)
                    dist[i * n + j] = if (diagonals) maxOf(dr, dc) else dr + dc
                }
            }
            goals.forEachIndexed { index, goal ->
                val k = if (goal is Goal.Shape) "${goal.type}:${goal.color}" else "combos"
                val group = groups.getOrPut(k) {
                    val placements = if (goal is Goal.Shape) Shapes.placements(goal.type, rows, cols) else emptyList()
                    Group(
                        goal.type,
                        (goal as? Goal.Shape)?.color ?: -1,
                        ArrayList(),
                        placements,
                        placements.map { p -> if (p.exact) borderOf(p.cells) else IntArray(0) },
                        placements.map { p -> BooleanArray(n).also { inside -> for (i in p.cells) inside[i] = true } },
                    )
                }
                group.indices += index
            }
        }

        /** Per-goal met flags for a list of combos. */
        fun goalsMet(combos: List<Combo>): List<Boolean> {
            val met = MutableList(goals.size) { false }
            for (group in groups.values) {
                val available = available(group, combos)
                group.indices.forEachIndexed { k, index ->
                    met[index] = if (group.type == "combos") combos.size >= (goals[index] as Goal.Combos).count else k < available
                }
            }
            return met
        }

        private fun available(group: Group, combos: List<Combo>): Int {
            if (group.type == "combos") return combos.size
            return combos.count { it.color == group.color && Shapes.has(group.type, it.cells, rows, cols) }
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
                val available = minOf(available(group, combos), group.indices.size)
                met += available
                score += available * MET_SCORE
                val missing = group.indices.size - available
                if (missing > 0) score += progress(group, cells, available, missing)
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

        /** Number of horizontally or vertically touching pairs of same-colour orbs. */
        private fun pairs(cells: IntArray): Int {
            var count = 0
            for (r in 0 until rows) {
                for (c in 0 until cols - 1) {
                    val i = r * cols + c
                    if (cells[i] == cells[i + 1]) count++
                }
            }
            for (i in 0 until n - cols) if (cells[i] == cells[i + cols]) count++
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
