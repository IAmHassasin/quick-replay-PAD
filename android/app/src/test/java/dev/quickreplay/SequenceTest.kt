package dev.quickreplay

import dev.quickreplay.engine.PlaybackProgress
import dev.quickreplay.engine.Player
import dev.quickreplay.model.ScreenSize
import dev.quickreplay.model.Script
import dev.quickreplay.model.ScriptFormatException
import dev.quickreplay.model.ScriptSequence
import dev.quickreplay.model.SequenceJson
import dev.quickreplay.model.SequencePart
import dev.quickreplay.model.Step
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SequenceTest {

    private val screen = ScreenSize(1000, 2000)

    /** A script of [taps] taps at x = [x], 100 ms apart; its own repeat is ignored inside a sequence. */
    private fun script(name: String, x: Float, taps: Int = 1, loopDelayMs: Long = 0) = Script(
        name = name, screen = screen, repeat = 99, loopDelayMs = loopDelayMs,
        steps = List(taps) { Step.Tap(x, 0f, delayBeforeMs = 100) },
    )

    private val abc = ScriptSequence(
        name = "ABC",
        parts = listOf(
            SequencePart(script("A", 1f), repeat = 1),
            SequencePart(script("B", 2f, taps = 2), repeat = 3),
            SequencePart(script("C", 3f), repeat = 2, delayBeforeMs = 500),
        ),
        repeat = 2,
        loopDelayMs = 1_000,
    )

    @Test
    fun playsPartsInOrderWithTheirRepeatCounts() = runTest {
        val xs = mutableListOf<Float>()
        val progress = mutableListOf<PlaybackProgress>()
        val result = Player({ xs += it.points.first().first; true }, 60_000, { currentTime }) { progress += it }.play(abc)

        val once = listOf(1f) + List(6) { 2f } + listOf(3f, 3f)
        assertEquals(once + once, xs)
        assertEquals(2, result.loop)
        // Step numbers run across the whole sequence: A has 1 step, B 2, C 1.
        assertEquals(PlaybackProgress(loop = 1, step = 2, totalSteps = 4, failedGestures = 0, part = 2, partCount = 3, partLoop = 1, partRepeat = 3), progress[1])
        assertEquals(3, progress[6].partLoop) // third run of B
        assertEquals(PlaybackProgress(2, 4, 4, 0, part = 3, partCount = 3, partLoop = 2, partRepeat = 2), result)
    }

    @Test
    fun appliesPartPauseAndLoopDelays() = runTest {
        val times = mutableListOf<Long>()
        val sequence = ScriptSequence(
            name = "t",
            parts = listOf(
                SequencePart(script("A", 1f, loopDelayMs = 400), repeat = 2),
                SequencePart(script("B", 2f), delayBeforeMs = 500),
            ),
            repeat = 2,
            loopDelayMs = 1_000,
        )
        Player({ times += currentTime; true }, 60_000, { currentTime }).play(sequence)
        // Taps are instant here. A: 100, (400 + 100) A: 600, (500 + 100) B: 1200, (1000 + 100) A: 2300 ...
        assertEquals(listOf(100L, 600L, 1_200L, 2_300L, 2_800L, 3_400L), times)
    }

    @Test
    fun loopsForeverUntilCancelled() = runTest {
        var count = 0
        val job = launch { Player({ count++; true }, 60_000, { currentTime }).play(abc.copy(repeat = 0)) }
        advanceTimeBy(60_000)
        job.cancel()
        assertTrue("played $count gestures", count > 18)
    }

    @Test
    fun emptySequenceFinishesImmediately() = runTest {
        val empty = ScriptSequence(name = "e", parts = listOf(SequencePart(script("A", 1f, taps = 0))), repeat = 0)
        assertEquals(0, Player({ true }, 60_000, { currentTime }).play(empty).loop)
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val withDate = abc.copy(createdAt = "2026-10-17T10:00:00+07:00")
        assertEquals(withDate, SequenceJson.parse(SequenceJson.toJson(withDate)))
        assertTrue(SequenceJson.isSequence(SequenceJson.toJson(abc)))
    }

    @Test
    fun parsesRepositoryExamples() {
        val examples = File("../../examples").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .filter { SequenceJson.isSequence(it.readText()) }
        assertTrue("expected a sequence example in /examples", examples.isNotEmpty())
        examples.forEach { SequenceJson.parse(it.readText()) }
    }

    @Test
    fun rejectsInvalidSequences() {
        val script = """{"format":"quickreplay/v1","name":"a","platform":"android","screen":{"width":1,"height":1},"steps":[]}"""
        val cases = mapOf(
            """{"format":"quickreplay-sequence/v1","name":"s","platform":"android"}""" to "parts",
            """{"format":"quickreplay-sequence/v1","name":"s","platform":"browser","parts":[]}""" to "platform",
            """{"format":"quickreplay-sequence/v1","name":"s","platform":"android","parts":[{"repeat":0,"script":$script}]}""" to "parts[0].repeat",
            """{"format":"quickreplay-sequence/v1","name":"s","platform":"android","parts":[{"repeat":1}]}""" to "parts[0].script is required",
            """{"format":"quickreplay-sequence/v1","name":"s","platform":"android","parts":[{"script":{"format":"x"}}]}""" to "parts[0].script: format",
            """{"format":"quickreplay-sequence/v1","name":"s","platform":"android","repeat":-1,"parts":[]}""" to "repeat",
        )
        for ((json, message) in cases) {
            val error = assertThrows(ScriptFormatException::class.java) { SequenceJson.parse(json) }
            assertTrue("'${error.message}' should mention '$message'", error.message!!.contains(message))
        }
        assertEquals(false, SequenceJson.isSequence(script))
        assertEquals(false, SequenceJson.isSequence("not json"))
    }

    @Test
    fun deeplyNestedJsonIsRejectedInsteadOfCrashing() {
        val hostile = "{\"a\":" + "[".repeat(200_000) + "]".repeat(200_000) + "}"
        // Run on a small stack so the recursion limit is reached quickly and deterministically.
        var error: Throwable? = null
        val thread = Thread(null, { error = runCatching { SequenceJson.parse(hostile) }.exceptionOrNull() }, "parse", 256 * 1024)
        thread.start()
        thread.join()
        assertTrue("got $error", error is ScriptFormatException)
        assertEquals(false, SequenceJson.isSequence(hostile))
    }
}
