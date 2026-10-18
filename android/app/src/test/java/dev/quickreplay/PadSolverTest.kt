package dev.quickreplay

import dev.quickreplay.pad.Board
import dev.quickreplay.pad.Goal
import dev.quickreplay.pad.Matcher
import dev.quickreplay.pad.Orbs
import dev.quickreplay.pad.Shapes
import dev.quickreplay.pad.SolveOptions
import dev.quickreplay.pad.Solver
import dev.quickreplay.pad.applyPath
import dev.quickreplay.pad.countOrbs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PadSolverTest {
    private val docBoard = "LGHLBB\nLRDDGL\nBRRLRR\nHBRGBL\nLBBRRD"

    @Test
    fun parsesAndFormatsBoards() {
        val board = Board.parse(docBoard)
        assertEquals(5, board.rows)
        assertEquals(6, board.cols)
        assertEquals(docBoard, board.format())
        assertArrayEquals(intArrayOf(8, 7, 3, 7, 3, 2), countOrbs(board.cells).copyOf(6))
    }

    @Test
    fun cascadesWithoutSkyfall() {
        val board = Board.parse("LGB\nGDR\nDLR\nBBR")
        val sim = Matcher(board.rows, board.cols).simulate(board.cells)
        assertEquals(listOf(Triple(0, 3, 0), Triple(1, 3, 1)), sim.combos.map { Triple(it.color, it.cells.size, it.pass) })
        assertEquals("...\nLG.\nGD.\nDL.", board.format(sim.cells))
    }

    @Test
    fun detectsShapes() {
        val cols = 6
        fun at(r: Int, c: Int) = r * cols + c
        assertTrue(Shapes.has("row", intArrayOf(0, 1, 2, 3, 4, 5, 6), 5, cols))
        assertTrue(Shapes.has("cross", intArrayOf(at(1, 2), at(2, 1), at(2, 2), at(2, 3), at(3, 2)), 5, cols))
        assertTrue(Shapes.has("lshape", intArrayOf(at(0, 0), at(1, 0), at(2, 0), at(2, 1), at(2, 2)), 5, cols))
        assertFalse(Shapes.has("lshape", intArrayOf(at(0, 0), at(0, 1), at(0, 2), at(1, 1), at(2, 1)), 5, cols))
        assertEquals(48, Shapes.placements("lshape", 5, cols).size)
        assertEquals(27, Shapes.placements("tpa", 5, cols).size)
    }

    @Test
    fun reportsImpossibleGoals() {
        val result = Solver.solve(Board.parse(docBoard), listOf(Goal.Shape("square", Orbs.code('D'))))
        assertEquals("impossible", result.status)
        assertEquals(listOf("Dark: the goals need 9 orbs, the board has 3"), result.reasons)
    }

    /** The Kotlin port must find exactly the paths of the JavaScript solver. */
    @Test
    fun matchesSharedVectors() {
        val vectors = JSONObject(vectorsFile().readText()).getJSONArray("vectors")
        assertTrue(vectors.length() >= 10)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val name = v.getString("name")
            val board = Board.parse(v.getString("board"))
            val goals = parseGoals(v.getJSONArray("goals"))
            val o = v.getJSONObject("options")
            val options = SolveOptions(
                diagonals = o.optBoolean("diagonals", true),
                maxMoves = o.optInt("maxMoves", 40),
                maxBeamWidth = o.getInt("maxBeamWidth"),
                timeLimitMs = o.getLong("timeLimitMs"),
            )
            val expected = v.getJSONObject("expected")
            val result = Solver.solve(board, goals, options)
            assertEquals(name, expected.getString("status"), result.status)
            assertEquals(name, expected.getJSONArray("reasons").toStrings(), result.reasons)
            val path = expected.getJSONArray("path").let { a -> List(a.length()) { a.getInt(it) } }
            assertEquals(name, path, result.path)
            if (result.status != "impossible") {
                assertEquals(name, expected.getInt("combos"), result.combos.size)
                assertEquals(name, expected.getString("afterDrag"), board.format(result.afterDrag!!))
                assertEquals(name, expected.getInt("rounds"), result.stats.rounds)
                val met = expected.getJSONArray("goalsMet").let { a -> List(a.length()) { a.getBoolean(it) } }
                assertEquals(name, met, result.goals.map { it.met })
                assertArrayEquals(name, applyPath(board.cells, result.path), result.afterDrag)
            }
        }
    }

    @Test
    fun stopsAtTheTimeLimit() {
        var t = 0L
        val result = Solver.solve(Board.parse(docBoard), listOf(Goal.Combos(8)), SolveOptions(timeLimitMs = 100)) { t += 1000; t }
        assertTrue(result.stats.timedOut)
        assertTrue(result.path.size >= 2)
    }

    private fun parseGoals(array: JSONArray): List<Goal> = List(array.length()) { i ->
        val g = array.getJSONObject(i)
        if (g.getString("type") == "combos") Goal.Combos(g.getInt("count")) else Goal.Shape(g.getString("type"), Orbs.code(g.getString("color")[0]))
    }

    private fun JSONArray.toStrings() = List(length()) { getString(it) }

    private fun vectorsFile(): File =
        listOf("../../spec/pad-solver-vectors.json", "../spec/pad-solver-vectors.json", "spec/pad-solver-vectors.json")
            .map(::File).firstOrNull(File::isFile) ?: error("spec/pad-solver-vectors.json not found from ${File(".").absolutePath}")
}
