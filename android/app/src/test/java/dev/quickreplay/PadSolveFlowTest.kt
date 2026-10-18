package dev.quickreplay

import dev.quickreplay.engine.GestureDispatcher
import dev.quickreplay.engine.GestureSpec
import dev.quickreplay.engine.Player
import dev.quickreplay.engine.SolveFailedException
import dev.quickreplay.engine.SolveRunner
import dev.quickreplay.model.ScreenSize
import dev.quickreplay.model.Script
import dev.quickreplay.model.ScriptFormatException
import dev.quickreplay.model.ScriptJson
import dev.quickreplay.model.Step
import dev.quickreplay.pad.Goal
import dev.quickreplay.pad.Orbs
import dev.quickreplay.pad.PadSetup
import dev.quickreplay.pad.PadSolveFlow
import dev.quickreplay.pad.Screenshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class PadSolveFlowTest {
    private fun screenshot(name: String): Screenshot {
        val file = listOf("src/test/resources/pad/$name", "app/src/test/resources/pad/$name").map(::File).first(File::isFile)
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)
        val width = img.javaClass.getMethod("getWidth").invoke(img) as Int
        val height = img.javaClass.getMethod("getHeight").invoke(img) as Int
        val getRgb = img.javaClass.getMethod(
            "getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java,
        )
        return Screenshot(getRgb.invoke(img, 0, 0, width, height, null, 0, width) as IntArray, width, height)
    }

    private val rowOfLight = PadSetup(goals = listOf(Goal.Shape("row", Orbs.code('L'))), msPerMove = 120, timeLimitMs = 500)

    @Test
    fun readsSolvesAndDragsThroughCellCentres() = runTest {
        val gestures = ArrayList<GestureSpec>()
        val flow = PadSolveFlow(
            capture = { screenshot("dawnglare-emulator.png") },
            drag = { gestures += it; true },
            clock = { currentTime },
            work = EmptyCoroutineContext,
        )
        val report = flow.run(rowOfLight)
        assertTrue(report.allGoalsMet)
        assertEquals(1, gestures.size)
        val gesture = gestures.single()
        assertEquals(report.moves + 1, gesture.points.size)
        assertEquals(report.moves * 120L, gesture.durationMs)
        // Every point is the centre of a board cell (board at x 242..838, y 336..830, ~99 px cells).
        for ((x, y) in gesture.points) {
            assertTrue("x=$x", x in 242f..838f)
            assertTrue("y=$y", y in 330f..835f)
        }
        // Consecutive points are neighbouring cells: at most one cell apart in x and y.
        gesture.points.zipWithNext().forEach { (a, b) ->
            assertTrue(Math.abs(a.first - b.first) < 110 && Math.abs(a.second - b.second) < 110)
        }
    }

    @Test
    fun failsWithoutDraggingWhenGoalsAreImpossibleOrTheBoardIsMissing() = runTest {
        var dragged = false
        val impossible = PadSolveFlow({ screenshot("dawnglare-emulator.png") }, { dragged = true; true }, { currentTime }, EmptyCoroutineContext)
        val square = PadSetup(goals = listOf(Goal.Shape("square", Orbs.code('D'))))
        val error = runCatching { impossible.run(square) }.exceptionOrNull()
        assertTrue(error is SolveFailedException)
        assertTrue(error!!.message!!, error.message!!.startsWith("Goals impossible: Dark"))

        val blank = PadSolveFlow({ Screenshot(IntArray(540 * 1200) { 0xff808080.toInt() }, 540, 1200) }, { dragged = true; true }, { currentTime }, EmptyCoroutineContext)
        val missing = runCatching { blank.run(rowOfLight) }.exceptionOrNull()
        assertEquals("No PAD board found on the screen", missing?.message)
        assertFalse(dragged)
    }

    @Test
    fun waitsUntilTheBoardStopsChanging() = runTest {
        val blank = Screenshot(IntArray(540 * 1200) { 0xff808080.toInt() }, 540, 1200)
        val board = screenshot("dawnglare-emulator.png")
        val other = screenshot("dawnglare-random-1.png")
        // Animation, then a board that stays: solved after the second identical read.
        val frames = mutableListOf(blank, other, board, board)
        var captures = 0
        val settling = PadSolveFlow({ captures++; frames.removeAt(0) }, { true }, { currentTime }, EmptyCoroutineContext)
        assertTrue(settling.run(rowOfLight).allGoalsMet)
        assertEquals(4, captures)
        assertEquals(3 * PadSolveFlow.SETTLE_INTERVAL_MS, currentTime)

        // A board that keeps changing is never solved.
        var flip = false
        var dragged = false
        val changing = PadSolveFlow({ flip = !flip; if (flip) board else other }, { dragged = true; true }, { currentTime }, EmptyCoroutineContext)
        val error = runCatching { changing.run(rowOfLight) }.exceptionOrNull()
        assertEquals("The board kept changing for 10 s", error?.message)
        assertFalse(dragged)
    }

    @Test
    fun playerRunsSolveStepsWithTheScriptSetupAndRestartsTheTimeline() = runTest {
        val log = ArrayList<String>()
        val setups = ArrayList<PadSetup>()
        val dispatcher = GestureDispatcher { gesture ->
            log += "tap@$currentTime"
            kotlinx.coroutines.delay(gesture.durationMs)
            true
        }
        val runner = SolveRunner { setup ->
            setups += setup
            log += "solve@$currentTime"
            kotlinx.coroutines.delay(1_700) // a solve takes as long as it takes
        }
        val script = Script(
            name = "farm",
            screen = ScreenSize(1080, 2400),
            steps = listOf(Step.Tap(1f, 1f, durationMs = 50), Step.Solve(delayBeforeMs = 100), Step.Tap(2f, 2f, durationMs = 50, delayBeforeMs = 300)),
            repeat = 2,
            pad = rowOfLight,
        )
        Player(dispatcher, 60_000, { currentTime }, solveRunner = runner).play(script)
        // Tap 0..50, solve 100 ms later (150..1850), the next tap 300 ms after the solve ended
        // (2150..2200); loop 2 starts right away.
        assertEquals(listOf("tap@0", "solve@150", "tap@2150", "tap@2200", "solve@2350", "tap@4350"), log)
        assertEquals(listOf(rowOfLight, rowOfLight), setups)
    }

    @Test
    fun aFailedSolveStopsThePlayback() = runTest {
        val script = Script("s", ScreenSize(10, 10), listOf(Step.Solve(), Step.Tap(1f, 1f)), pad = rowOfLight)
        var taps = 0
        val error = runCatching {
            Player({ taps++; true }, 60_000, { currentTime }, solveRunner = { throw SolveFailedException("No PAD board found on the screen") }).play(script)
        }.exceptionOrNull()
        assertEquals("No PAD board found on the screen", error?.message)
        assertEquals(0, taps)
    }

    @Test
    fun scriptJsonKeepsSolveStepsAndTheSetup() {
        val setup = PadSetup(
            goals = listOf(Goal.Shape("row", Orbs.code('L')), Goal.Shape("square", Orbs.code('D')), Goal.Combos(7)),
            diagonals = false, maxMoves = 30, timeLimitMs = 2000, msPerMove = 150, dragWhenUnmet = true,
        )
        val script = Script("farm", ScreenSize(1080, 2400), listOf(Step.Tap(5f, 6f), Step.Solve(delayBeforeMs = 250)), pad = setup)
        val json = ScriptJson.toJson(script)
        assertTrue(json, json.contains("\"type\": \"solve\""))
        assertTrue(json, json.contains("\"whenUnmet\": \"dragBest\""))
        val back = ScriptJson.parse(json)
        assertEquals(script.steps, back.steps)
        assertEquals(setup, back.pad)

        // A solve step needs the setup; bad setups are rejected with a readable message.
        val noSetup = json.replace(Regex("\"pad\": \\{[^}]*\\[[^]]*][^}]*\\},?"), "")
        assertFormatError(noSetup, "pad is required for solve steps")
        assertFormatError(json.replace("\"msPerMove\": 150", "\"msPerMove\": 5"), "pad: Drag time per move must be from 10 to 1000 ms")
        assertFormatError(json.replace("\"color\": \"D\"", "\"color\": \"J\""), "pad: pad.goals[1].color must be one of R, B, G, L, D, H")
        // Same strictness as the browser validator and the schema: no strings, fractions or lower case.
        assertFormatError(json.replace("\"msPerMove\": 150", "\"msPerMove\": \"150\""), "pad: pad.msPerMove must be an integer")
        assertFormatError(json.replace("\"msPerMove\": 150", "\"msPerMove\": 150.5"), "pad: pad.msPerMove must be an integer")
        assertFormatError(json.replace("\"color\": \"D\"", "\"color\": \"d\""), "pad: pad.goals[1].color must be one of R, B, G, L, D, H")
        assertFormatError(json.replace("\"diagonals\": false", "\"diagonals\": \"no\""), "pad: pad.diagonals must be a boolean")
        assertFormatError(json.replace(Regex("\"pad\": \\{[^}]*\\[[^]]*][^}]*\\}"), "\"pad\": 3"), "pad must be an object")
    }

    private fun assertFormatError(json: String, message: String) {
        try {
            ScriptJson.parse(json)
            fail("expected: $message")
        } catch (e: ScriptFormatException) {
            assertEquals(message, e.message)
        }
    }
}
