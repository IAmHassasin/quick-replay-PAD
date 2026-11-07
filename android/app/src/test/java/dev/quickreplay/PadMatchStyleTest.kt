package dev.quickreplay

import dev.quickreplay.pad.Board
import dev.quickreplay.pad.Combo
import dev.quickreplay.pad.Goal
import dev.quickreplay.pad.GoalResult
import dev.quickreplay.pad.Matcher
import dev.quickreplay.pad.Orbs
import dev.quickreplay.pad.Shapes
import dev.quickreplay.pad.SolveOptions
import dev.quickreplay.pad.SolveResult
import dev.quickreplay.pad.Solver
import dev.quickreplay.pad.applyPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Match-style tests: for every goal type the solver must solve a board that has the orbs, reject
 * a board that does not (from orb counts, without searching) and report an unmet goal when the
 * move budget is too small. Shape definitions, goal combinations, other board sizes and
 * orthogonal-only drags are covered too. Same cases as `browser/test/unit/pad-match-styles.test.js`.
 */
class PadMatchStyleTest {
    // Fixture boards, rows top to bottom. DOC, SHOT, RICH and R3 are the boards of the shared
    // vectors (orb counts in the comments); WIDE and BIG exercise other board sizes.
    private val doc = "LGHLBB\nLRDDGL\nBRRLRR\nHBRGBL\nLBBRRD" // R8 B7 G3 L7 D3 H2
    private val shot = "LHDGHL\nHDLBHR\nGLBRGG\nGGBHBG\nHGLHBH" // R2 B5 G8 L5 D2 H8
    private val rich = "DRDBDG\nLDHDRD\nBDGLDB\nRHDBGL\nGLRHBD" // D10
    private val r3 = "HRBGDL\nRRLBGD\nLBDRHB\nGDRLBR\nBLHGRD" // R7 B6
    private val wide = "LRBLG\nGLHRB\nRBLGL\nHGRBD" // 5 columns x 4 rows, L5
    private val big = "RGBLDHR\nBRGLHDB\nGBRLDHG\nLGBRHDL\nDLGBRHD\nHDLGBRH" // 7 columns x 6 rows, R7 L6

    // One 1000-wide round and no wall-clock limit: the same result on every machine.
    private val options = SolveOptions(timeLimitMs = 60_000, maxBeamWidth = 1000)

    private fun shape(type: String, color: Char) = Goal.Shape(type, Orbs.code(color))

    private class Solved(val board: Board, val result: SolveResult, val combos: List<Combo>)

    private class ShapeSolved(val board: Board, val result: SolveResult, val combo: Combo)

    /** Every step is a move to a neighbouring cell (orthogonal only when [diagonals] is false). */
    private fun assertContinuous(path: List<Int>, cols: Int, diagonals: Boolean) {
        assertTrue("a path holds at least two cells", path.size >= 2)
        for (i in 1 until path.size) {
            val dr = Math.abs(path[i] / cols - path[i - 1] / cols)
            val dc = Math.abs(path[i] % cols - path[i - 1] % cols)
            val ok = if (diagonals) dr <= 1 && dc <= 1 && dr + dc > 0 else dr + dc == 1
            assertTrue("step $i from cell ${path[i - 1]} to ${path[i]} is not a ${if (diagonals) "" else "orthogonal "}move", ok)
        }
    }

    /** Replays the path and returns the combos of match -> gravity -> cascades (no skyfall). */
    private fun replay(board: Board, result: SolveResult): List<Combo> {
        val afterDrag = applyPath(board.cells, result.path)
        assertArrayEquals("afterDrag must be the board after replaying the path", afterDrag, result.afterDrag)
        return Matcher(board.rows, board.cols).simulate(afterDrag).combos
    }

    /** Combos of one colour that have the shape, re-checked from the raw simulated cells. */
    private fun shaped(combos: List<Combo>, type: String, color: Char, board: Board): List<Combo> =
        combos.filter { it.color == Orbs.code(color) && Shapes.has(type, it.cells, board.rows, board.cols) }

    /** Solves and checks the contract of a solved result; returns the re-simulated combos. */
    private fun assertSolved(text: String, goals: List<Goal>, options: SolveOptions = this.options): Solved {
        val board = Board.parse(text)
        val result = Solver.solve(board, goals, options)
        assertEquals("$goals: ${result.goals}", "solved", result.status)
        assertEquals(emptyList<String>(), result.reasons)
        assertEquals(goals.map { true }, result.goals.map { it.met })
        assertEquals(result.path.size - 1, result.moves)
        assertContinuous(result.path, board.cols, options.diagonals)
        val combos = replay(board, result)
        assertEquals("reported combos match the simulation", combos.size, result.combos.size)
        return Solved(board, result, combos)
    }

    /** A single shape goal is solved with exactly one combo of that colour and shape. */
    private fun assertShapeGoal(text: String, type: String, color: Char, options: SolveOptions = this.options): ShapeSolved {
        val solved = assertSolved(text, listOf(shape(type, color)), options)
        val found = shaped(solved.combos, type, color, solved.board)
        assertEquals("expected exactly one $type of $color", 1, found.size)
        assertTrue(solved.result.goals[0].met)
        assertTrue(
            "the reported combo lists the shape",
            solved.result.combos.any { it.color == color && type in it.shapes },
        )
        return ShapeSolved(solved.board, solved.result, found[0])
    }

    /** Impossible from orb counts or board size: no search, no path, every goal unmet. */
    private fun assertImpossible(text: String, goals: List<Goal>, reasons: List<String>) {
        val result = Solver.solve(Board.parse(text), goals, options)
        assertEquals(goals.toString(), "impossible", result.status)
        assertEquals(reasons, result.reasons)
        assertEquals(goals.map { false }, result.goals.map { it.met })
        assertEquals(emptyList<Int>(), result.path)
        assertEquals(0, result.moves)
        assertNull(result.afterDrag)
        assertEquals("impossible goals are rejected without searching", 0L, result.stats.evaluated)
        assertEquals(0, result.stats.rounds)
    }

    /** Enough orbs, but a single move cannot make the goal: unmet, with the best one-move path. */
    private fun assertUnmet(text: String, goals: List<Goal>) {
        val board = Board.parse(text)
        val result = Solver.solve(board, goals, options.copy(maxMoves = 1))
        assertEquals(goals.toString(), "partial", result.status)
        assertEquals(emptyList<String>(), result.reasons)
        assertEquals(goals.map { false }, result.goals.map { it.met })
        assertEquals(1, result.moves)
        assertTrue("the board was searched", result.stats.evaluated > 0)
        assertContinuous(result.path, board.cols, diagonals = true)
        replay(board, result)
    }

    /** The cells of one full line of the board, if the combo contains one. */
    private fun fullLine(combo: Combo, board: Board, horizontal: Boolean): List<Int>? {
        val lines = if (horizontal) board.rows else board.cols
        val per = if (horizontal) board.cols else board.rows
        for (line in 0 until lines) {
            val cells = combo.cells.filter { (if (horizontal) it / board.cols else it % board.cols) == line }
            if (cells.size == per) return cells
        }
        return null
    }

    // --- Solved: one case per goal type, checking the shape from the simulated cells ---------------

    @Test
    fun rowOfLightOnTheDocBoard() {
        val s = assertShapeGoal(doc, "row", 'L')
        assertNotNull("the combo contains one full row", fullLine(s.combo, s.board, horizontal = true))
        assertTrue(s.combo.cells.size >= s.board.cols)
    }

    @Test
    fun columnOfFireOnTheR3Board() {
        val s = assertShapeGoal(r3, "column", 'R')
        assertNotNull("the combo contains one full column", fullLine(s.combo, s.board, horizontal = false))
        assertTrue(s.combo.cells.size >= s.board.rows)
    }

    @Test
    fun squareOfExactlyNineDarkOnTheRichBoard() {
        val s = assertShapeGoal(rich, "square", 'D')
        assertEquals(9, s.combo.cells.size)
        val top = s.combo.cells[0]
        val block = (0 until 3).flatMap { dr -> (0 until 3).map { dc -> top + dr * s.board.cols + dc } }
        assertEquals(block, s.combo.cells.toList())
    }

    @Test
    fun crossOfExactlyFiveHeartOnTheScreenshotBoard() {
        val s = assertShapeGoal(shot, "cross", 'H')
        assertEquals(5, s.combo.cells.size)
        val centre = s.combo.cells[2]
        val cols = s.board.cols
        assertEquals(listOf(centre - cols, centre - 1, centre, centre + 1, centre + cols), s.combo.cells.toList())
    }

    @Test
    fun lshapeOfExactlyFiveWaterOnTheScreenshotBoard() {
        val s = assertShapeGoal(shot, "lshape", 'B')
        assertEquals(5, s.combo.cells.size)
        val cols = s.board.cols
        val armRow = s.combo.cells.groupBy { it / cols }.entries.firstOrNull { it.value.size == 3 }?.key
        val armCol = s.combo.cells.groupBy { it % cols }.entries.firstOrNull { it.value.size == 3 }?.key
        assertNotNull("three orbs in one row", armRow)
        assertNotNull("three orbs in one column", armCol)
        assertTrue("the arms share their corner orb", armRow!! * cols + armCol!! in s.combo.cells)
    }

    @Test
    fun tpaOfExactlyFourWaterOnTheR3Board() {
        val s = assertShapeGoal(r3, "tpa", 'B')
        assertEquals(4, s.combo.cells.size)
        val first = s.combo.cells[0]
        val cols = s.board.cols
        val horizontal = (0 until 4).map { first + it }
        val vertical = (0 until 4).map { first + it * cols }
        val cells = s.combo.cells.toList()
        val inLine = (cells == horizontal && horizontal.all { it / cols == first / cols }) || cells == vertical
        assertTrue("cells $cells are not 4 in a line", inLine)
    }

    @Test
    fun atLeastSevenCombosOnTheDocBoard() {
        val s = assertSolved(doc, listOf(Goal.Combos(7)))
        assertTrue("${s.result.combos.size} combos", s.result.combos.size >= 7)
        assertEquals("7+ combos", s.result.goals[0].label)
        for (combo in s.combos) assertTrue("every combo has at least 3 orbs", combo.cells.size >= 3)
    }

    // --- Impossible and unmet: every goal type ------------------------------------------------------

    @Test
    fun everyGoalTypeIsRejectedFromOrbCountsWithoutSearching() {
        assertImpossible(doc, listOf(shape("row", 'D')), listOf("Dark: the goals need 6 orbs, the board has 3"))
        assertImpossible(doc, listOf(shape("column", 'H')), listOf("Heart: the goals need 5 orbs, the board has 2"))
        assertImpossible(doc, listOf(shape("square", 'D')), listOf("Dark: the goals need 9 orbs, the board has 3"))
        assertImpossible(doc, listOf(shape("cross", 'H')), listOf("Heart: the goals need 5 orbs, the board has 2"))
        assertImpossible(doc, listOf(shape("lshape", 'G')), listOf("Wood: the goals need 5 orbs, the board has 3"))
        assertImpossible(doc, listOf(shape("tpa", 'H')), listOf("Heart: the goals need 4 orbs, the board has 2"))
        // R8 B7 G3 L7 D3 H2: floor(n / 3) per colour = 2 + 2 + 1 + 2 + 1 + 0.
        assertImpossible(doc, listOf(Goal.Combos(9)), listOf("9+ combos: at most 8 combos are possible on this board"))
    }

    @Test
    fun everyGoalTypeEndsUnmetWithItsBestPathWhenOneMoveIsNotEnough() {
        assertUnmet(doc, listOf(shape("row", 'L')))
        assertUnmet(r3, listOf(shape("column", 'R')))
        assertUnmet(rich, listOf(shape("square", 'D')))
        assertUnmet(shot, listOf(shape("cross", 'H')))
        assertUnmet(shot, listOf(shape("lshape", 'B')))
        assertUnmet(r3, listOf(shape("tpa", 'B')))
        assertUnmet(doc, listOf(Goal.Combos(7)))
    }

    // --- Shape definitions ----------------------------------------------------------------------------

    private val rows = 5
    private val cols = 6

    private fun at(r: Int, c: Int) = r * cols + c

    private fun has(type: String, cells: List<Int>, rows: Int = this.rows, cols: Int = this.cols) =
        Shapes.has(type, cells.sorted().toIntArray(), rows, cols)

    @Test
    fun squareIsExactlyNineOrbsInAThreeByThreeBlock() {
        val square = (1..3).flatMap { r -> (2..4).map { c -> at(r, c) } }
        assertTrue(has("square", square))
        assertFalse("10 orbs containing a 3x3 block", has("square", square + at(4, 2)))
        assertFalse("10 orbs, extra below the middle", has("square", square + at(4, 3)))
        assertFalse("8 orbs", has("square", square.drop(1)))
        assertFalse("9 orbs in a 3x4 box", has("square", square - at(2, 3) + at(2, 5)))
        for (type in listOf("cross", "lshape", "tpa")) assertFalse("a square is not a $type", has(type, square))
    }

    @Test
    fun crossIsExactlyFiveOrbsInAPlus() {
        val cross = listOf(at(1, 2), at(2, 1), at(2, 2), at(2, 3), at(3, 2))
        assertTrue(has("cross", cross))
        assertFalse("a cross with one extra orb", has("cross", cross + at(1, 1)))
        assertFalse("a cross with a longer arm", has("cross", cross + at(2, 4)))
        assertFalse("a T is not a cross", has("cross", listOf(at(1, 1), at(1, 2), at(1, 3), at(2, 2), at(3, 2))))
        assertFalse("an L is not a cross", has("cross", listOf(at(1, 1), at(2, 1), at(3, 1), at(3, 2), at(3, 3))))
        for (type in listOf("square", "lshape", "tpa")) assertFalse("a cross is not a $type", has(type, cross))
    }

    @Test
    fun lshapeIsExactlyFiveOrbsThreePlusThreeSharingACornerInAnyOrientation() {
        val corners = listOf(
            listOf(at(0, 0), at(1, 0), at(2, 0), at(2, 1), at(2, 2)), // corner bottom-left
            listOf(at(0, 0), at(0, 1), at(0, 2), at(1, 0), at(2, 0)), // corner top-left
            listOf(at(0, 0), at(0, 1), at(0, 2), at(1, 2), at(2, 2)), // corner top-right
            listOf(at(0, 2), at(1, 2), at(2, 0), at(2, 1), at(2, 2)), // corner bottom-right
        )
        for (l in corners) {
            assertTrue("L $l", has("lshape", l))
            assertFalse(has("cross", l))
            assertFalse(has("tpa", l))
        }
        assertFalse("a T is not an L", has("lshape", listOf(at(0, 0), at(0, 1), at(0, 2), at(1, 1), at(2, 1))))
        assertFalse("an L with one extra orb", has("lshape", corners[0] + at(1, 1)))
        assertFalse("4 + 2 is not 3 + 3", has("lshape", listOf(at(0, 0), at(0, 1), at(0, 2), at(0, 3), at(1, 0))))
        assertFalse("3 + 4 is not an L", has("lshape", listOf(at(0, 0), at(1, 0), at(2, 0), at(2, 1), at(2, 2), at(2, 3))))
    }

    @Test
    fun tpaIsExactlyFourOrbsInAStraightLine() {
        assertTrue("horizontal", has("tpa", listOf(at(4, 1), at(4, 2), at(4, 3), at(4, 4))))
        assertTrue("vertical", has("tpa", listOf(at(0, 5), at(1, 5), at(2, 5), at(3, 5))))
        assertFalse("5 in a line", has("tpa", listOf(at(4, 0), at(4, 1), at(4, 2), at(4, 3), at(4, 4))))
        assertFalse("3 in a line", has("tpa", listOf(at(4, 1), at(4, 2), at(4, 3))))
        assertFalse("a 2x2 block", has("tpa", listOf(at(0, 0), at(0, 1), at(1, 0), at(1, 1))))
        assertFalse("3 + 1 bent", has("tpa", listOf(at(0, 0), at(0, 1), at(0, 2), at(1, 2))))
        assertFalse("two rows of 4", has("tpa", (0..3).map { at(0, it) } + (0..3).map { at(1, it) }))
    }

    @Test
    fun rowAndColumnContainAFullLineAndAcceptExtraOrbs() {
        val fullRow = (0..5).toList()
        assertTrue(has("row", fullRow))
        assertTrue("a row plus a column stub", has("row", fullRow + at(1, 0) + at(2, 0)))
        assertFalse("5 of 6 is not a row", has("row", (0..4).toList()))
        assertFalse("6 orbs on two rows", has("row", listOf(at(0, 0), at(0, 1), at(0, 2), at(1, 2), at(1, 3), at(1, 4))))
        assertFalse(has("column", fullRow))
        val fullColumn = (0..4).map { at(it, 2) }
        assertTrue("a column needs 5 orbs on a 6x5 board", has("column", fullColumn))
        assertTrue("a column plus a row stub", has("column", fullColumn + at(2, 3) + at(2, 4)))
        assertFalse("4 of 5 is not a column", has("column", fullColumn.drop(1)))
        assertFalse(has("row", fullColumn))
        // Full lines are not "exact" shapes of their size.
        assertFalse(has("tpa", fullColumn))
        assertFalse("9 orbs in a 2x6 box", has("square", fullRow + listOf(6, 7, 8)))
    }

    @Test
    fun shapeSizesFitsAndPlacementsFollowTheBoardSize() {
        assertEquals(listOf(5, 4), listOf(Shapes.size("row", 4, 5), Shapes.size("column", 4, 5)))
        assertEquals(listOf(7, 6), listOf(Shapes.size("row", 6, 7), Shapes.size("column", 6, 7)))
        assertEquals(9, Shapes.size("square", 4, 5))
        assertEquals(4, Shapes.size("tpa", 6, 7))
        for (type in Shapes.TYPES) assertTrue("$type fits 5x4 and 7x6", Shapes.fits(type, 4, 5) && Shapes.fits(type, 6, 7))
        assertFalse("4 in a line needs a side of 4", Shapes.fits("tpa", 3, 3))
        assertTrue(Shapes.fits("tpa", 3, 4) && Shapes.fits("tpa", 4, 3))
        assertTrue(Shapes.fits("square", 3, 3) && Shapes.fits("cross", 3, 3) && Shapes.fits("lshape", 3, 3))
        // Placements per board: rows, columns, 3x3 windows, 4 Ls per window, 4-lines both ways.
        assertEquals(4, Shapes.placements("row", 4, 5).size)
        assertEquals(5, Shapes.placements("column", 4, 5).size)
        assertEquals(2 * 3, Shapes.placements("square", 4, 5).size)
        assertEquals(2 * 3 * 4, Shapes.placements("lshape", 4, 5).size)
        assertEquals(4 * 2 + 5 * 1, Shapes.placements("tpa", 4, 5).size)
        assertEquals(6, Shapes.placements("row", 6, 7).size)
        assertEquals(7, Shapes.placements("column", 6, 7).size)
        assertEquals(4 * 5, Shapes.placements("cross", 6, 7).size)
        for ((r, c) in listOf(4 to 5, 6 to 7)) {
            for (type in Shapes.TYPES) {
                for (p in Shapes.placements(type, r, c)) {
                    assertTrue("$type ${p.cells.toList()} on ${c}x$r", Shapes.has(type, p.cells, r, c))
                }
            }
        }
        // On a 4-row board a full column is also 4 in a line.
        assertTrue(has("column", listOf(0, 5, 10, 15), rows = 4, cols = 5))
        assertTrue(has("tpa", listOf(0, 5, 10, 15), rows = 4, cols = 5))
    }

    // --- Goal combinations ----------------------------------------------------------------------------

    @Test
    fun twoGoalsOfDifferentKindsAreBothMet() {
        val s = assertSolved(doc, listOf(shape("row", 'L'), Goal.Combos(5)))
        assertEquals(listOf("Row of Light", "5+ combos"), s.result.goals.map { it.label })
        assertEquals(1, shaped(s.combos, "row", 'L', s.board).size)
        assertTrue(s.combos.size >= 5)
    }

    @Test
    fun twoShapeGoalsOfDifferentColoursAreBothMet() {
        val s = assertSolved(shot, listOf(shape("lshape", 'B'), shape("row", 'G')))
        val l = shaped(s.combos, "lshape", 'B', s.board)
        assertEquals(1, l.size)
        assertEquals(5, l[0].cells.size)
        assertEquals(1, shaped(s.combos, "row", 'G', s.board).size)
    }

    @Test
    fun twoGoalsOfOneColourAddUpTheirOrbs() {
        // 6 + 5 Light needed, 7 on the board.
        assertImpossible(doc, listOf(shape("row", 'L'), shape("cross", 'L')), listOf("Light: the goals need 11 orbs, the board has 7"))
        // Two rows of Fire: 12 needed, 7 on the board.
        assertImpossible(r3, listOf(shape("row", 'R'), shape("row", 'R')), listOf("Fire: the goals need 12 orbs, the board has 7"))
        // Every goal is reported, including the one that could be met on its own.
        assertImpossible(doc, listOf(Goal.Combos(3), shape("square", 'D')), listOf("Dark: the goals need 9 orbs, the board has 3"))
    }

    // --- Other board sizes ----------------------------------------------------------------------------

    @Test
    fun fiveByFourBoardRowNeedsFiveOrbsAndColumnFour() {
        val s = assertShapeGoal(wide, "row", 'L')
        assertEquals(4, s.board.rows)
        assertEquals(5, s.board.cols)
        assertEquals(5, fullLine(s.combo, s.board, horizontal = true)!!.size)
        val column = assertShapeGoal(wide, "column", 'L')
        assertEquals(4, fullLine(column.combo, column.board, horizontal = false)!!.size)
    }

    @Test
    fun sevenBySixBoardColumnNeedsSixOrbsAndRowSeven() {
        val s = assertShapeGoal(big, "column", 'L')
        assertEquals(6, s.board.rows)
        assertEquals(7, s.board.cols)
        assertEquals(6, fullLine(s.combo, s.board, horizontal = false)!!.size)
        val r = assertShapeGoal(big, "row", 'R')
        assertEquals(7, fullLine(r.combo, r.board, horizontal = true)!!.size)
    }

    @Test
    fun threeByThreeBoardFourInALineDoesNotFit() {
        assertImpossible("RBG\nLDH\nRRR", listOf(shape("tpa", 'R')), listOf("4 in a line of Fire: does not fit on a 3x3 board"))
    }

    // --- Diagonals off --------------------------------------------------------------------------------

    @Test
    fun withoutDiagonalsAnExactShapeIsMadeWithOrthogonalMovesOnly() {
        val s = assertShapeGoal(shot, "cross", 'H', options.copy(diagonals = false))
        assertEquals(5, s.combo.cells.size)
        assertContinuous(s.result.path, 6, diagonals = false)
        // Diagonal moves are allowed by default and give a shorter path here.
        val withDiagonals = Solver.solve(Board.parse(shot), listOf(shape("cross", 'H')), options)
        assertTrue(withDiagonals.moves <= s.result.moves)
    }

    @Test
    fun withoutDiagonalsAFullRowIsMadeWithOrthogonalMovesOnly() {
        val s = assertShapeGoal(doc, "row", 'L', options.copy(diagonals = false))
        assertContinuous(s.result.path, 6, diagonals = false)
    }

    // --- blob: one combo of a colour with at least N connected orbs (REHA-11) ------------------------

    // Boards of tools/pad-research/proposed-test-cases.json (REHA-4): 30 orbs, no initial match.
    private val blobBoard = "RRBRRG\nGLRDHB\nBGLDHL\nHDBGLR\nLBHGDB" // R6 B6 G5 L6 D4 H4
    private val three = "RLRRBB\nGDHBHD\nHGGDGL\nLHBLDR\nDBLHRG" // R5 B5 G5 L5 D5 H5
    private val rbg = "RBGRBG\nBGRBGR\nGRBGRB\nRBGRBG\nLDHLDH" // R8 B8 G8 L2 D2 H2
    private val twice = "RRBRGL\nGRLHBD\nRHRBLG\nBLGDHB\nDGHLRH" // R7 B5 G5 L5 D3 H5

    private fun blob(color: Char, min: Int) = Goal.Blob(Orbs.code(color), min)

    /** Combos of one colour with at least [min] orbs, re-checked from the raw simulated cells. */
    private fun blobs(combos: List<Combo>, color: Char, min: Int) = combos.filter { it.color == Orbs.code(color) && it.cells.size >= min }

    @Test
    fun blobOfFivePlusFireOnTheBlobBoardInOneMove() {
        val s = assertSolved(blobBoard, listOf(blob('R', 5)))
        assertEquals(listOf(GoalResult("5+ Fire connected", true)), s.result.goals)
        assertEquals("the R at r1c2 moved up completes R x5 in row 0", 1, s.result.moves)
        assertEquals(1, blobs(s.combos, 'R', 5).size)
    }

    @Test
    fun blobFixedColourSetIsOneBlobGoalPerColour() {
        val s = assertSolved(three, listOf(blob('R', 3), blob('B', 3)))
        assertEquals(listOf("3+ Fire connected", "3+ Water connected"), s.result.goals.map { it.label })
        assertTrue(blobs(s.combos, 'R', 3).isNotEmpty())
        assertTrue(blobs(s.combos, 'B', 3).isNotEmpty())
    }

    @Test
    fun blobRepeatedGoalNeedsSeparateCombos() {
        val s = assertSolved(twice, listOf(blob('R', 3), blob('R', 3)))
        assertTrue("two separate Fire combos", blobs(s.combos, 'R', 3).size >= 2)
    }

    @Test
    fun blobImpossibleFromOrbCountsWithoutSearching() {
        assertImpossible(blobBoard, listOf(blob('H', 5)), listOf("Heart: the goals need 5 orbs, the board has 4"))
        assertImpossible(
            rbg, listOf(blob('L', 3), blob('D', 3)),
            listOf("Light: the goals need 3 orbs, the board has 2", "Dark: the goals need 3 orbs, the board has 2"),
        )
        assertImpossible(twice, listOf(blob('D', 3), blob('D', 3)), listOf("Dark: the goals need 6 orbs, the board has 3"))
        // Blob orbs add up with other goals of the colour.
        assertImpossible(blobBoard, listOf(shape("row", 'R'), blob('R', 3)), listOf("Fire: the goals need 9 orbs, the board has 6"))
    }

    @Test
    fun blobUnmetWithOneMoveWhenTwoSeparateCombosAreNeeded() {
        val board = Board.parse(twice)
        val result = Solver.solve(board, listOf(blob('R', 3), blob('R', 3)), options.copy(maxMoves = 1))
        // One move makes one Fire combo; that combo cannot count for the second goal too.
        assertEquals("partial", result.status)
        assertEquals(listOf(true, false), result.goals.map { it.met })
        assertEquals(1, result.moves)
        assertContinuous(result.path, board.cols, diagonals = true)
        assertEquals(1, blobs(replay(board, result), 'R', 3).size)
        // A single goal of a size one move cannot reach stays unmet.
        assertUnmet(twice, listOf(blob('R', 6)))
    }

    @Test
    fun blobMinMustBeFromThreeToTheBoardSize() {
        for (min in listOf(2, 0, -3)) assertThrows("min $min", IllegalArgumentException::class.java) { blob('R', min) }
        // Without a board the limit is the largest board (9x9).
        assertThrows(IllegalArgumentException::class.java) { blob('R', 82) }
        assertEquals(81, blob('R', 81).min)
        assertThrows(IllegalArgumentException::class.java) { Goal.Blob(Orbs.COUNT, 5) }
        // With a board: at most rows x cols orbs.
        val error = assertThrows(IllegalArgumentException::class.java) { Solver.solve(Board.parse(blobBoard), listOf(blob('R', 31)), options) }
        assertEquals("Blob size must be at most 30 on a 6x5 board", error.message)
        assertEquals("7+ Wood connected", blob('G', 7).label)
    }

    @Test
    fun blobPlacementsAreRectanglesOfMinToMinPlusTwoWithASideOfThree() {
        val placements = Shapes.blobPlacements(5, 5, 6)
        val sizes = ArrayList<String>()
        for (p in placements) {
            assertFalse(p.exact)
            val h = p.cells.maxOf { it / 6 } - p.cells.minOf { it / 6 } + 1
            val w = p.cells.maxOf { it % 6 } - p.cells.minOf { it % 6 } + 1
            assertEquals("a filled rectangle", h * w, p.cells.size)
            assertTrue("${h}x$w", h * w in 5..7 && maxOf(h, w) >= 3)
            assertArrayEquals(p.cells.sortedArray(), p.cells)
            if (sizes.lastOrNull() != "${h}x$w") sizes += "${h}x$w"
        }
        // 1x5, 1x6, 2x3, 3x2, 5x1 (no 1x7, 7x1 or 6x1 on 6x5).
        assertEquals(listOf("1x5", "1x6", "2x3", "3x2", "5x1"), sizes)
        assertEquals(5 * 2 + 5 * 1 + 4 * 4 + 3 * 5 + 1 * 6, placements.size)
        // Row-major order inside one size.
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), placements[0].cells)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5), placements[1].cells)
        assertArrayEquals(intArrayOf(6, 7, 8, 9, 10), placements[2].cells)
        assertArrayEquals(intArrayOf(0, 1, 2, 6, 7, 8), Shapes.rectanglePlacements(2, 3, 5, 6)[0])
        assertEquals(4 * 4, Shapes.rectanglePlacements(2, 3, 5, 6).size)
        assertEquals(0, Shapes.rectanglePlacements(6, 1, 5, 6).size)
        // The whole board is the only placement for min = rows x cols.
        assertEquals(listOf(30), Shapes.blobPlacements(30, 5, 6).map { it.cells.size })
    }

    @Test
    fun blobPlacementsExistForEveryMinOnEveryBoardSize() {
        for (rows in 3..9) for (cols in 3..9) for (min in 3..rows * cols) {
            val placements = Shapes.blobPlacements(min, rows, cols)
            assertTrue("min $min on ${cols}x$rows", placements.isNotEmpty())
            for (p in placements) assertTrue(p.cells.size >= min)
        }
        // 73 on 9x9: no rectangle of 73 to 75 orbs, so the smallest area >= 73 (the whole board).
        assertEquals(listOf(81), Shapes.blobPlacements(73, 9, 9).map { it.cells.size })
    }

    // --- colors: at least K distinct colours among the combos (REHA-12) ------------------------------

    // One swap of r0c2 and r0c3 makes R R R B B B in row 0 (two colours); the three hearts are apart,
    // so no single move makes a Heart combo. No initial match.
    private val duo = "RRBRBB\nGLDGLD\nDGLDGH\nLDHLDG\nGHLGLD" // R3 B3 G6 L7 D7 H3

    private fun colors(count: Int, of: String? = null) =
        if (of == null) Goal.Colors(count) else Goal.Colors(count, of.map { Orbs.code(it) })

    /** Distinct colours of the combos that are in [of] (default: the 5 attributes). */
    private fun distinctColors(combos: List<Combo>, of: String = "RBGLD") =
        combos.map { it.color }.filter { it in of.map { c -> Orbs.code(c) } }.toSet().size

    @Test
    fun colorsThreePlusColoursOnTheThreeBoard() {
        val s = assertSolved(three, listOf(colors(3)))
        assertEquals(listOf(GoalResult("3+ colours", true)), s.result.goals)
        assertTrue("${distinctColors(s.combos)} colours", distinctColors(s.combos) >= 3)
    }

    @Test
    fun colorsImpossibleWhenFewerColoursOfTheSetHaveThreeOrbs() {
        assertImpossible(rbg, listOf(colors(4)), listOf("4+ colours: only 3 colours have 3 or more orbs (R, B, G), the goal needs 4"))
        // Hearts are outside the default set; with them in the set the RBG board still has 3 (H2).
        assertImpossible(
            rbg, listOf(colors(4, "RBGH")),
            listOf("4+ colours of Fire, Water, Wood, Heart: only 3 colours have 3 or more orbs (R, B, G), the goal needs 4"),
        )
        // Colours outside the set do not count: only Fire of R, L, D has 3+ orbs on the RBG board.
        assertImpossible(
            rbg, listOf(colors(2, "RLD")),
            listOf("2+ colours of Fire, Light, Dark: only 1 colour has 3 or more orbs (R), the goal needs 2"),
        )
        assertImpossible(rbg, listOf(colors(1, "LD")), listOf("1+ colours of Light, Dark: only 0 colours have 3 or more orbs, the goal needs 1"))
    }

    @Test
    fun colorsSetWithHeartCountsAHeartCombo() {
        val s = assertSolved(three, listOf(colors(2, "BH")))
        assertEquals("2+ colours of Water, Heart", s.result.goals[0].label)
        val found = s.combos.map { it.color }.toSet()
        assertTrue("a Water and a Heart combo", Orbs.code('B') in found && Orbs.code('H') in found)
    }

    @Test
    fun colorsOutsideTheSetDoNotCount() {
        // One move makes Fire and Water combos: enough for the default set, not for Fire + Heart.
        val s = assertSolved(duo, listOf(colors(2)), options.copy(maxMoves = 1))
        assertEquals(1, s.result.moves)
        val board = Board.parse(duo)
        val unmet = Solver.solve(board, listOf(colors(2, "RH")), options.copy(maxMoves = 1))
        assertEquals("partial", unmet.status)
        assertEquals(listOf(false), unmet.goals.map { it.met })
        // The order of the set does not change the result.
        val reordered = Solver.solve(board, listOf(colors(2, "HR")), options.copy(maxMoves = 1))
        assertEquals(unmet.path, reordered.path)
    }

    @Test
    fun colorsUnmetWithOneMoveOnTheThreeBoard() {
        assertUnmet(three, listOf(colors(3)))
    }

    @Test
    fun colorsCountFromOneToSixAndANonEmptySetOfDistinctAttributes() {
        for (count in listOf(0, 7, -1)) {
            val e = assertThrows("count $count", IllegalArgumentException::class.java) { colors(count, "RBGLDH") }
            assertEquals("Colour count must be from 1 to 6", e.message)
        }
        for (of in listOf(emptyList(), listOf(Orbs.code('J')), listOf(0, 0), listOf(-1), listOf(Orbs.COUNT))) {
            val e = assertThrows("of $of", IllegalArgumentException::class.java) { Goal.Colors(1, of) }
            assertEquals("Colour set must be a non-empty list of distinct colours R, B, G, L, D, H", e.message)
        }
        assertEquals(
            "Colour count must be at most 2, the colours in the set",
            assertThrows(IllegalArgumentException::class.java) { colors(3, "RB") }.message,
        )
        assertEquals(
            "Colour count must be at most 5, the colours in the set",
            assertThrows(IllegalArgumentException::class.java) { colors(6) }.message,
        )
        assertEquals(listOf(0, 1, 2, 3, 4), colors(3).set)
        assertEquals(listOf(5, 0, 1, 2, 3, 4), colors(6, "HRBGLD").set)
        assertEquals("3+ colours", colors(3).label)
        // The default set in another order is still the default label.
        assertEquals("5+ colours", colors(5, "DLGBR").label)
        assertEquals("colors", colors(1).type)
    }

    // --- T shape (REHA-13) ----------------------------------------------------------------------------

    // TEE of tools/pad-research/proposed-test-cases.json: moving the D at r3c1 up makes a T of Dark.
    private val tee = "BDGLRH\nGDBHLR\nDHDGBL\nLDRBGH\nRBLHDG" // R4 B5 G5 L5 D6 H5

    // TEE with the D at r3c1 swapped one row down: the T needs two moves.
    private val teeFar = "BDGLRH\nGDBHLR\nDHDGBL\nLBRBGH\nRDLHDG" // R4 B5 G5 L5 D6 H5

    @Test
    fun tshapeIsExactlyFiveOrbsThreeInALinePlusTwoFromItsMiddleInAnyOrientation() {
        val ts = listOf(
            listOf(at(0, 0), at(0, 1), at(0, 2), at(1, 1), at(2, 1)), // stem down
            listOf(at(0, 1), at(1, 1), at(2, 0), at(2, 1), at(2, 2)), // stem up
            listOf(at(0, 0), at(1, 0), at(2, 0), at(1, 1), at(1, 2)), // stem right
            listOf(at(0, 2), at(1, 2), at(2, 2), at(1, 1), at(1, 0)), // stem left
        )
        for (t in ts) {
            assertTrue("T $t", has("tshape", t))
            for (type in listOf("square", "cross", "lshape", "tpa")) assertFalse("a T is not a $type", has(type, t))
        }
        assertFalse("an L is not a T", has("tshape", listOf(at(0, 0), at(1, 0), at(2, 0), at(2, 1), at(2, 2))))
        assertFalse("a cross is not a T", has("tshape", listOf(at(0, 1), at(1, 0), at(1, 1), at(1, 2), at(2, 1))))
        assertFalse("6 orbs: a longer stem", has("tshape", listOf(at(0, 0), at(0, 1), at(0, 2), at(1, 1), at(2, 1), at(3, 1))))
        assertFalse("4 in a line plus 1", has("tshape", listOf(at(0, 0), at(0, 1), at(0, 2), at(0, 3), at(1, 1))))
        // Exact shape: a T with one more orb of the colour attached is a 6-orb combo, not a T.
        assertFalse("a T with one extra orb", has("tshape", ts[0] + at(1, 0)))
        assertFalse("a T with a longer bar", has("tshape", ts[1] + at(2, 3)))
        // Same shape away from the corner of the board.
        assertTrue("T at r2c3", has("tshape", listOf(at(2, 3), at(2, 4), at(2, 5), at(3, 4), at(4, 4))))
    }

    @Test
    fun tshapeSizeFiveNeedsAThreeByThreeBoardFourPlacementsPerWindow() {
        assertTrue("tshape" in Shapes.TYPES)
        assertEquals(5, Shapes.size("tshape", 5, 6))
        assertTrue(Shapes.fits("tshape", 3, 3))
        for ((r, c) in listOf(2 to 6, 6 to 2, 2 to 2, 1 to 9)) assertFalse("${c}x$r", Shapes.fits("tshape", r, c))
        assertEquals(48, Shapes.placements("tshape", 5, 6).size)
        assertEquals(2 * 3 * 4, Shapes.placements("tshape", 4, 5).size)
        // Orientation order per window (decides ties; same as shapes.js): stem down, up, right, left.
        val window = Shapes.placements("tshape", 3, 3)
        assertEquals(
            listOf(listOf(0, 1, 2, 4, 7), listOf(1, 4, 6, 7, 8), listOf(0, 3, 4, 5, 6), listOf(2, 3, 4, 5, 8)),
            window.map { it.cells.toList() },
        )
        assertTrue(window.all { it.exact })
        assertEquals("T shape of Dark", shape("tshape", 'D').label)
    }

    @Test
    fun tshapeOfDarkOnTheTeeBoardInOneMove() {
        val s = assertShapeGoal(tee, "tshape", 'D')
        // One swap of r2c1 and r3c1. The research case wrote [19, 13] (D up); the solver holds the
        // H and drags it down ([13, 19]), which leaves the same board.
        assertEquals(1, s.result.moves)
        assertEquals(listOf(13, 19), s.result.path.sorted())
        assertEquals("column 1 rows 0-2 + row 2 columns 0-2: stem up", listOf(1, 7, 12, 13, 14), s.combo.cells.toList())
        val reported = s.result.combos.first { it.color == 'D' && "tshape" in it.shapes }
        assertEquals("not a cross, L, square or 4 in a line", listOf("tshape"), reported.shapes)
    }

    @Test
    fun tshapeImpossibleWithFourFireUnmetWithOneMoveWhenTheTNeedsTwo() {
        assertImpossible(tee, listOf(shape("tshape", 'R')), listOf("Fire: the goals need 5 orbs, the board has 4"))
        assertImpossible("RRB\nGLD\nHBG", listOf(shape("tshape", 'R')), listOf("Fire: the goals need 5 orbs, the board has 2"))
        assertUnmet(teeFar, listOf(shape("tshape", 'D')))
        assertShapeGoal(teeFar, "tshape", 'D')
    }
}
