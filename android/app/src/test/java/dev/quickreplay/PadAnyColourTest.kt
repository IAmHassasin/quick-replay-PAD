package dev.quickreplay

import dev.quickreplay.model.ScreenSize
import dev.quickreplay.model.Script
import dev.quickreplay.model.ScriptJson
import dev.quickreplay.model.Step
import dev.quickreplay.pad.Board
import dev.quickreplay.pad.Goal
import dev.quickreplay.pad.Orbs
import dev.quickreplay.pad.PadSetup
import dev.quickreplay.pad.SolveOptions
import dev.quickreplay.pad.SolveResult
import dev.quickreplay.pad.Solver
import dev.quickreplay.pad.countOrbs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REHA-16: missing orbs are reported with a readable reason, and shape / blob goals may use the
 * colour Any. Same cases and boards as `browser/test/unit/pad-any-colour.test.js`.
 */
class PadAnyColourTest {
    private val options = SolveOptions(timeLimitMs = 60_000, maxBeamWidth = 1000)

    private val light4 = "LRBGDH\nRBGDHR\nLBGDHB\nRGLDHG\nBRDLHR"
    private val light5Fire2 = "LLLLLB\nRGDHBG\nDBGHDB\nRGDHBG\nDBGHDB"
    private val light7 = "LLLLLL\nLRBGDH\nRBGDHR\nBGDHRB\nGDHRBG"
    private val teeL = "LLLRBG\nRLBGDH\nBGLDJP\nGDRJPM\nDHJMRB"
    private val teeLOnly = "LLLRBG\nHLHHHH\nHJLJPM\nRBGDJP\nDHHHMM"
    private val teeL8 = "LLLRBG\nHLHHLH\nHJLJLM\nRBGDLP\nDHHHMM"

    private fun c(letter: Char) = Orbs.code(letter)
    private fun tee(color: Int) = Goal.Shape("tshape", color)
    private fun blob(color: Int, min: Int = 3) = Goal.Blob(color, min)

    private fun impossible(board: String, goals: List<Goal>, reasons: List<String>): SolveResult {
        val result = Solver.solve(Board.parse(board), goals, options)
        assertEquals("impossible", result.status)
        assertEquals(reasons, result.reasons)
        assertTrue(result.path.isEmpty())
        assertEquals(0L, result.stats.evaluated)
        return result
    }

    private fun solved(board: String, goals: List<Goal>): SolveResult {
        val result = Solver.solve(Board.parse(board), goals, options)
        assertEquals(result.goals.toString(), "solved", result.status)
        assertEquals(goals.map { true }, result.goals.map { it.met })
        return result
    }

    @Test
    fun boardsHaveTheStatedOrbCounts() {
        fun counts(board: String, letters: String) = letters.map { countOrbs(Board.parse(board).cells)[c(it)] }
        assertEquals(listOf(4), counts(light4, "L"))
        assertEquals(listOf(5, 2), counts(light5Fire2, "LR"))
        assertEquals(listOf(7), counts(light7, "L"))
        assertEquals(listOf(4, 4, 4, 5, 4), counts(teeL, "RBGLD"))
        assertEquals(listOf(2, 2, 2, 5, 2, 9), counts(teeLOnly, "RBGLDH"))
        assertEquals(listOf(2, 2, 2, 8, 2), counts(teeL8, "RBGLD"))
    }

    @Test
    fun missingOrbsForTheMainPattern() {
        impossible(light4, listOf(tee(c('L'))), listOf("Light: the goals need 5 orbs, the board has 4"))
    }

    @Test
    fun missingOrbsForASecondPatternOfAnotherColour() {
        impossible(light5Fire2, listOf(tee(c('L')), blob(c('R'))), listOf("Fire: the goals need 3 orbs, the board has 2"))
    }

    @Test
    fun missingOrbsForTwoPatternsOfOneColour() {
        impossible(light7, listOf(tee(c('L')), blob(c('L'))), listOf("Light: the goals need 8 orbs, the board has 7"))
    }

    @Test
    fun anyGoalLabels() {
        assertEquals("T shape of any colour", tee(Orbs.ANY).label)
        assertEquals("Row of any colour", Goal.Shape("row", Orbs.ANY).label)
        assertEquals("3+ any colour connected", blob(Orbs.ANY).label)
        assertEquals("exactly 5 any colour", Goal.Blob(Orbs.ANY, 5, 5).label)
        assertEquals("4-6 any colour connected", Goal.Blob(Orbs.ANY, 4, 6).label)
    }

    @Test
    fun feasibilityIgnoresTheOrbsOfAnyGoalsButNotTheirFit() {
        val board = Board.parse("RBG\nBGR\nGRB")
        assertEquals(emptyList<String>(), Solver.checkFeasibility(board, listOf(tee(Orbs.ANY))))
        assertEquals(
            listOf("4 in a line of any colour: does not fit on a 3x3 board"),
            Solver.checkFeasibility(board, listOf(Goal.Shape("tpa", Orbs.ANY))),
        )
    }

    @Test
    fun aTeeOfAnyColourBecomesLight() {
        val result = solved(teeL, listOf(tee(Orbs.ANY)))
        assertEquals('L', result.goals[0].anyColor)
        assertEquals("T shape of Light (any colour)", result.goals[0].label)
        assertEquals(1, result.combos.count { it.color == 'L' && "tshape" in it.shapes })
    }

    @Test
    fun twoAnyGoalsCannotShareAColour() {
        val result = impossible(
            teeLOnly,
            listOf(tee(Orbs.ANY), blob(Orbs.ANY)),
            listOf("Any colour: no assignment of distinct colours has enough orbs for \"T shape of any colour\", \"3+ any colour connected\""),
        )
        assertEquals(listOf("T shape of any colour", "3+ any colour connected"), result.goals.map { it.label })
        assertEquals(listOf(null, null), result.goals.map { it.anyColor })
    }

    @Test
    fun heartIsNotAnAnyColour() {
        impossible(teeLOnly, listOf(blob(Orbs.ANY, 6)), listOf("Any colour: no assignment of distinct colours has enough orbs for \"6+ any colour connected\""))
    }

    @Test
    fun twoAnyGoalsGetTwoDifferentColours() {
        val result = solved(teeL, listOf(tee(Orbs.ANY), blob(Orbs.ANY)))
        assertEquals('L', result.goals[0].anyColor)
        val other = result.goals[1].anyColor!!
        assertTrue("the blob took $other", other in "RBGD")
        assertTrue(result.combos.any { it.color == other && it.size >= 3 })
    }

    @Test
    fun anAnyGoalMayTakeTheColourOfAFixedGoal() {
        val result = solved(teeL8, listOf(tee(c('L')), blob(Orbs.ANY)))
        assertNull(result.goals[0].anyColor)
        assertEquals("T shape of Light", result.goals[0].label)
        assertEquals('L', result.goals[1].anyColor)
        assertEquals("3+ Light connected (any colour)", result.goals[1].label)
        assertTrue(result.combos.count { it.color == 'L' } >= 2)
    }

    @Test
    fun aFixedGoalWithoutItsOrbsIsReportedAsSuch() {
        impossible(light4, listOf(tee(c('L')), blob(Orbs.ANY)), listOf("Light: the goals need 5 orbs, the board has 4"))
    }

    @Test
    fun moreAnyGoalsThanAnyColoursIsImpossible() {
        val result = Solver.solve(Board.parse("RRRBBB\nGGGLLL\nDDDRRR\nBBBGGG\nLLLDDD"), List(6) { blob(Orbs.ANY) }, options)
        assertEquals("impossible", result.status)
        assertTrue(result.reasons[0], result.reasons[0].startsWith("Any colour: no assignment of distinct colours has enough orbs for "))
    }

    @Test
    fun theTimeLimitIsSharedByTheAssignments() {
        var t = 0L
        val result = Solver.solve(Board.parse(teeL), listOf(blob(Orbs.ANY), blob(Orbs.ANY)), SolveOptions(timeLimitMs = 400)) { t++ }
        assertNotEquals("impossible", result.status)
        assertTrue("searched ${result.stats.assignments}", result.stats.assignments in 2..Solver.ANY_ASSIGNMENT_LIMIT)
        assertNotEquals(result.goals[0].anyColor, result.goals[1].anyColor)
        assertTrue("the clock ran to $t ms", t < 1000)
    }

    @Test
    fun goalsWithoutAnySearchOneAssignment() {
        val result = Solver.solve(Board.parse(teeL), listOf(tee(c('L'))), options)
        assertEquals("solved", result.status)
        assertEquals(1, result.stats.assignments)
        assertEquals("T shape of Light", result.goals[0].label)
    }

    @Test
    fun anyRoundTripsThroughThePadSetupJson() {
        val setup = PadSetup(goals = listOf(tee(Orbs.ANY), Goal.Blob(Orbs.ANY, 3), Goal.Blob(Orbs.ANY, 5, 5), tee(c('L'))))
        val script = Script("any", ScreenSize(1080, 2400), listOf(Step.Solve()), pad = setup)
        val json = ScriptJson.toJson(script)
        assertEquals(json, 3, Regex("\"color\": \"any\"").findAll(json).count())
        assertEquals(setup, ScriptJson.parse(json).pad)
        assertEquals("T shape of any colour + 3+ any colour connected + exactly 5 any colour + T shape of Light · 150 ms/move", setup.summary())
        // Any letter case is read; "any" is what is written.
        for (spelling in listOf("ANY", "Any", "aNy")) {
            val parsed = PadSetup.fromJson(JSONObject("{\"goals\": [{\"type\": \"tshape\", \"color\": \"$spelling\"}]}"))
            assertEquals(listOf(tee(Orbs.ANY)), parsed.goals)
            assertEquals("any", parsed.toJson().getJSONArray("goals").getJSONObject(0).getString("color"))
        }
        for (bad in listOf("anything", "an", "J", "X", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                PadSetup.fromJson(JSONObject("{\"goals\": [{\"type\": \"blob\", \"color\": \"$bad\", \"min\": 3}]}"))
            }
        }
    }
}
