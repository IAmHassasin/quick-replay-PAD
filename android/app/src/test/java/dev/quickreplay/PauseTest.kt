package dev.quickreplay

import dev.quickreplay.engine.PauseGate
import dev.quickreplay.engine.Player
import dev.quickreplay.model.ScreenSize
import dev.quickreplay.model.Script
import dev.quickreplay.model.ScriptSequence
import dev.quickreplay.model.SequencePart
import dev.quickreplay.model.Step
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PauseTest {

    private val screen = ScreenSize(1080, 2400)

    /** tap, 3 s, tap, 0.5 s, tap with 50 ms taps: downs at 0, 3050, 3600 when never paused. */
    private val script = Script(
        name = "p", screen = screen,
        steps = listOf(Step.Tap(1f, 0f), Step.Tap(2f, 0f, delayBeforeMs = 3_000), Step.Tap(3f, 0f, delayBeforeMs = 500)),
    )

    @Test
    fun pauseDuringAWaitKeepsTheTimeThatWasLeft() = runTest {
        val gate = PauseGate()
        val downs = mutableListOf<Long>()
        val job = launch {
            Player({ downs += currentTime; delay(it.durationMs); true }, 60_000, { currentTime }, gate).play(script)
        }
        advanceTimeBy(1_050) // 1 s into the 3 s pause
        gate.pause()
        advanceTimeBy(10_000) // paused for 10 s: nothing may happen
        assertEquals(listOf(0L), downs)
        gate.resume()
        advanceUntilIdle()
        job.join()
        // 2 s of the pause were left: second tap 2 s after resuming (11 050 + 2 000).
        assertEquals(listOf(0L, 13_050L, 13_600L), downs)
    }

    @Test
    fun pauseDuringAGestureHoldsTheNextStepUntilResumed() = runTest {
        val gate = PauseGate()
        val downs = mutableListOf<Long>()
        val longPress = Script(
            name = "lp", screen = screen,
            steps = listOf(Step.LongPress(0f, 0f, 1_000), Step.Tap(1f, 1f, delayBeforeMs = 200)),
        )
        val job = launch {
            Player({ downs += currentTime; delay(it.durationMs); true }, 60_000, { currentTime }, gate).play(longPress)
        }
        advanceTimeBy(500)
        gate.pause() // the long press is still being injected: it finishes, then playback holds
        advanceTimeBy(5_000)
        assertEquals(listOf(0L), downs)
        gate.resume()
        advanceUntilIdle()
        job.join()
        assertEquals(2, downs.size)
        assertTrue("tap at ${downs[1]}", downs[1] in 5_500L..5_700L) // resumed at 5 500, then the 200 ms pause
    }

    @Test
    fun pauseAndResumeWorkInsideSequencesAndLoopDelays() = runTest {
        val gate = PauseGate()
        val downs = mutableListOf<Long>()
        val sequence = ScriptSequence(
            name = "s",
            parts = listOf(SequencePart(Script(name = "a", screen = screen, loopDelayMs = 1_000, steps = listOf(Step.Tap(0f, 0f))), repeat = 2)),
        )
        val job = launch { Player({ downs += currentTime; true }, 60_000, { currentTime }, gate).play(sequence) }
        runCurrent()
        advanceTimeBy(400) // inside the 1 s pause between the part's two runs
        gate.pause()
        advanceTimeBy(3_000)
        gate.resume()
        advanceUntilIdle()
        job.join()
        assertEquals(listOf(0L, 4_000L), downs) // 600 ms were left after resuming at 3 400
    }

    @Test
    fun stoppingWhilePausedEndsPlayback() = runTest {
        val gate = PauseGate()
        var count = 0
        val job = launch { Player({ count++; true }, 60_000, { currentTime }, gate).play(script.copy(repeat = 0)) }
        advanceTimeBy(100)
        gate.pause()
        advanceTimeBy(1_000)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, count)
    }
}
