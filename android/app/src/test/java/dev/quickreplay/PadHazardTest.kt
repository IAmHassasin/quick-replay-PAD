package dev.quickreplay

import dev.quickreplay.pad.Board
import dev.quickreplay.pad.Goal
import dev.quickreplay.pad.GoalResult
import dev.quickreplay.pad.Matcher
import dev.quickreplay.pad.Orbs
import dev.quickreplay.pad.SolveOptions
import dev.quickreplay.pad.Solver
import dev.quickreplay.pad.applyPath
import dev.quickreplay.pad.countOrbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REHA-18: hazard orbs. Poison (P) and Mortal poison (M) are never matched, not even by a cascade;
 * every Bomb (X) must be matched (1 or 2 bombs: impossible); an orb of unknown colour (?) never
 * matches, is never dragged, and a shape or blob combo touching it does not count. Same cases and
 * boards as `browser/test/unit/pad-hazards.test.js`.
 */
class PadHazardTest {
    private val options = SolveOptions(timeLimitMs = 60_000, maxBeamWidth = 1000)
    private val rowL = listOf(Goal.Shape("row", Orbs.code('L')))

    private val poisonDirect = "LLLPLL\nRBPLPG\nGDBHRB\nDHGRDH\nBRHGBD"
    private val poisonCascade = "LBBBGP\nRPBGGL\nHLPHBH\nPDGLBP\nLPLRLP"
    private val mortalCascade = "MBDBML\nRHMBBM\nMHLLML\nLDLBDM\nMGBDLL"
    private val mortalDirect = "LLLMLL\nRBMLMG\nGDBHRB\nDHGRDH\nBRHGBD"
    private val bomb1 = "LLLLLX\nRBGDHL\nGDHRBG\nDHRBGD\nHRBGDH"
    private val bomb2 = "LLLLLX\nRBGDHX\nGDHRBG\nDHRBGD\nHRBGDL"
    private val bomb3 = "LLLLLB\nXRGXDH\nGDXRBG\nDHRBGL\nHRBGDH"
    private val bombFar = "LLLRLL\nXBGLDH\nGDHRBG\nDHRBGD\nXRBGDX"
    private val unknownRow = "LLL?LL\nLRBGDH\nRBGDHR\nBGDHRB\nGDHRBG"
    private val sample = "HRXHGL\nGJBJRG\nXRXGMD\nGPGRBL\nBLPLDB"

    private fun combosAfter(board: Board, path: List<Int>) = Matcher(board.rows, board.cols).simulate(applyPath(board.cells, path)).combos

    private fun assertNoPoison(text: String, path: List<Int>) {
        val poison = combosAfter(Board.parse(text), path).filter { it.color == Orbs.POISON || it.color == Orbs.MORTAL_POISON }
        assertTrue("poison matched by $path", poison.isEmpty())
    }

    private fun matchedBombs(text: String, path: List<Int>) =
        combosAfter(Board.parse(text), path).filter { it.color == Orbs.BOMB }.sumOf { it.cells.size }

    @Test
    fun newOrbLetters() {
        assertEquals(Orbs.BOMB, Orbs.code('X'))
        assertEquals(Orbs.UNKNOWN, Orbs.code('?'))
        assertEquals(Orbs.POISON, Orbs.code('P'))
        assertEquals(Orbs.MORTAL_POISON, Orbs.code('M'))
        assertEquals(unknownRow, Board.parse(unknownRow).format())
        assertEquals(3, countOrbs(Board.parse(sample).cells)[Orbs.BOMB])
    }

    @Test
    fun bombsMatchLikeAColourUnknownOrbsNever() {
        val m = Matcher(3, 3)
        assertEquals(listOf(Orbs.BOMB), m.simulate(Board.parse("XXX\nRBG\nBGR").cells).combos.map { it.color })
        assertEquals(0, m.simulate(Board.parse("???\nRBG\nBGR").cells).combos.size)
        assertEquals(0, m.simulate(Board.parse("?RB\n?BG\n?GR").cells).combos.size)
        assertEquals(listOf(Orbs.POISON), m.simulate(Board.parse("PPP\nRBG\nBGR").cells).combos.map { it.color })
    }

    @Test
    fun combosTouchingAnUnknownOrbAreFlagged() {
        val m = Matcher(3, 4)
        assertTrue(m.simulate(Board.parse("RRRB\n?GBG\nBGBG").cells).combos.single().touchesUnknown)
        assertFalse("diagonal contact does not count", m.simulate(Board.parse("RRRB\nGBG?\nBGBG").cells).combos.single().touchesUnknown)
    }

    @Test
    fun neverMatchesPoisonOrMortalPoison() {
        for (text in listOf(poisonDirect, poisonCascade, mortalDirect, mortalCascade)) {
            val result = Solver.solve(Board.parse(text), rowL, options)
            assertEquals(text, "solved", result.status)
            assertTrue(result.combos.none { it.color == 'P' || it.color == 'M' })
            assertNoPoison(text, result.path)
        }
    }

    @Test
    fun poisonIsNotCountedAndPoisonGoalsAreImpossible() {
        val board = Board.parse("PPPRRB\nBGDHLB\nGDHLBG\nDHLBGD\nHLBGDH")
        assertEquals(listOf("7+ combos: at most 6 combos are possible on this board"), Solver.checkFeasibility(board, listOf(Goal.Combos(7))))
        val result = Solver.solve(board, listOf(Goal.Shape("row", Orbs.POISON)), options)
        assertEquals("impossible", result.status)
        assertEquals(listOf("Row of Poison: Poison and Mortal poison orbs must never be matched"), result.reasons)
    }

    @Test
    fun oneOrTwoBombsAreImpossible() {
        for ((text, reason) in listOf(
            bomb1 to "Bomb: 1 bomb on the board, all bombs must be matched and a match needs 3",
            bomb2 to "Bomb: 2 bombs on the board, all bombs must be matched and a match needs 3",
        )) {
            val result = Solver.solve(Board.parse(text), rowL, options)
            assertEquals("impossible", result.status)
            assertEquals(listOf(reason), result.reasons)
            assertEquals(0L, result.stats.evaluated)
            assertEquals("impossible", Solver.solve(Board.parse(text), listOf(Goal.Combos(1)), options).status)
        }
    }

    @Test
    fun threeBombsAreSolvedOnlyWhenAllAreMatched() {
        val result = Solver.solve(Board.parse(bomb3), rowL, options)
        assertEquals("solved", result.status)
        assertEquals(listOf(GoalResult("Row of Light", true), GoalResult("All 3 bombs matched", true)), result.goals)
        assertEquals(3, matchedBombs(bomb3, result.path))
    }

    @Test
    fun aRowWithBombsLeftOverIsNotSolved() {
        val result = Solver.solve(Board.parse(bombFar), rowL, options.copy(maxMoves = 1))
        assertEquals("partial", result.status)
        assertEquals(listOf(true, false), result.goals.map { it.met })
        assertEquals("All 3 bombs matched", result.goals[1].label)
    }

    @Test
    fun pathAvoidsUnknownOrbsAndTheRowDoesNotTouchThem() {
        val board = Board.parse(unknownRow)
        val result = Solver.solve(board, rowL, options)
        assertEquals("solved", result.status)
        assertFalse("path crosses the unknown orb: ${result.path}", 3 in result.path)
        assertEquals(Orbs.UNKNOWN, applyPath(board.cells, result.path)[3])
        val row = combosAfter(board, result.path).firstOrNull { it.color == Orbs.code('L') && it.cells.size >= 6 }
        assertNotNull(row)
        assertFalse(row!!.touchesUnknown)
        assertTrue("row ${row.cells.toList()}", row.cells[0] >= 12)
    }

    @Test
    fun unknownOrbsAreNotCounted() {
        val board = Board.parse("LLL?LL\n?RBGDH\nRBGDHR\nBGDHRB\nGDHRBG")
        assertEquals(listOf("Light: the goals need 6 orbs, the board has 5"), Solver.checkFeasibility(board, rowL))
        val triple = Board.parse("???RRB\nBGDHLB\nGDHLBG\nDHLBGD\nHLBGDH")
        assertEquals(listOf("7+ combos: at most 6 combos are possible on this board"), Solver.checkFeasibility(triple, listOf(Goal.Combos(7))))
    }

    @Test
    fun bombAndUnknownAreNotGoalColours() {
        assertThrows(IllegalArgumentException::class.java) { Goal.Shape("row", Orbs.BOMB) }
        assertThrows(IllegalArgumentException::class.java) { Goal.Blob(Orbs.UNKNOWN, 3) }
    }

    @Test
    fun dawnGlareSampleBoard() {
        val result = Solver.solve(Board.parse(sample), listOf(Goal.Combos(3)), options)
        assertEquals("solved", result.status)
        assertNoPoison(sample, result.path)
        assertEquals(3, matchedBombs(sample, result.path))
    }
}
