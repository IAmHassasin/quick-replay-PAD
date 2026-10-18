package dev.quickreplay.pad

import dev.quickreplay.engine.GestureSpec
import dev.quickreplay.engine.SolveFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/** A screen capture as ARGB pixels in screen (gesture) coordinates. */
class Screenshot(val pixels: IntArray, val width: Int, val height: Int)

/** What one solve did, for the status line. */
data class SolveReport(val moves: Int, val combos: Int, val allGoalsMet: Boolean, val ms: Long) {
    val summary: String
        get() = "Solve $moves moves, $combos combo(s)" + if (allGoalsMet) "" else " (goals not met)"
}

/**
 * One `solve` step: capture the screen, read the board, solve it with the [PadSetup], drag the
 * path as one continuous gesture. Android-free (screen capture and gesture injection are passed
 * in), so it is unit-tested on saved screenshots.
 */
class PadSolveFlow(
    private val capture: suspend () -> Screenshot,
    /** Injects the drag; returns false when the system cancelled it. */
    private val drag: suspend (GestureSpec) -> Boolean,
    /** Monotonic milliseconds (a wall-clock change must not stretch or cut the waits). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Where the CPU-heavy recognition and search run. */
    private val work: CoroutineContext = Dispatchers.Default,
    /**
     * Called just before the drag; false means the board may have changed meanwhile (e.g. the
     * playback was paused), so it is read and solved again.
     */
    private val boardStillValid: suspend () -> Boolean = { true },
) {
    /** @throws SolveFailedException when the board cannot be read, the goals cannot be met or the drag is cancelled. */
    suspend fun run(setup: PadSetup): SolveReport {
        val started = clock()
        var found: Recognition.Found
        var result: SolveResult
        do {
            found = waitForStableBoard(setup)
            val board = found.board
            result = withContext(work) { Solver.solve(board, setup.goals, setup.solveOptions) }
            when {
                result.status == "impossible" -> throw SolveFailedException("Goals impossible: " + result.reasons.joinToString("; "))
                result.moves == 0 -> throw SolveFailedException("No path found")
                !result.solved && !setup.dragWhenUnmet -> throw SolveFailedException(
                    "Goals not met: " + result.goals.filterNot { it.met }.joinToString { it.label },
                )
            }
        } while (!boardStillValid())
        if (!drag(dragGesture(found.grid, result.path, setup.msPerMove))) throw SolveFailedException("The drag was interrupted")
        return SolveReport(result.moves, result.combos.size, result.solved, clock() - started)
    }

    /**
     * Reads the board until two reads [SETTLE_INTERVAL_MS] apart agree, so a board that is still
     * resolving combos, falling or being animated in is not solved. Fails after [SETTLE_TIMEOUT_MS]
     * with the last reason.
     */
    private suspend fun waitForStableBoard(setup: PadSetup): Recognition.Found {
        val deadline = clock() + SETTLE_TIMEOUT_MS
        var previous: Recognition.Found? = null
        while (true) {
            val shot = capture()
            val recognition = withContext(work) {
                BoardRecognizer.recognize(shot.pixels, shot.width, shot.height, setup.rows, setup.cols)
            }
            if (recognition is Recognition.Found && previous != null && sameBoard(previous, recognition)) return recognition
            previous = recognition as? Recognition.Found
            if (clock() >= deadline) {
                throw SolveFailedException(
                    (recognition as? Recognition.Failed)?.reason ?: "The board kept changing for ${SETTLE_TIMEOUT_MS / 1000} s",
                )
            }
            delay(SETTLE_INTERVAL_MS)
        }
    }

    private fun sameBoard(a: Recognition.Found, b: Recognition.Found): Boolean =
        a.board.cells.contentEquals(b.board.cells) &&
            abs(a.grid.left - b.grid.left) < a.grid.cell * 0.1f &&
            abs(a.grid.top - b.grid.top) < a.grid.cell * 0.1f

    companion object {
        const val SETTLE_INTERVAL_MS = 400L
        const val SETTLE_TIMEOUT_MS = 10_000L

        /** One stroke through the centres of the path cells, [msPerMove] per move. */
        fun dragGesture(grid: BoardGrid, path: List<Int>, msPerMove: Long): GestureSpec =
            GestureSpec(path.map { grid.centerX(it) to grid.centerY(it) }, (path.size - 1) * msPerMove)
    }
}
