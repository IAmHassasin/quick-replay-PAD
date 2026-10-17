package dev.quickreplay

import dev.quickreplay.engine.GestureDispatcher
import dev.quickreplay.engine.Player
import dev.quickreplay.engine.Timeline
import dev.quickreplay.model.ScreenSize
import dev.quickreplay.model.Script
import dev.quickreplay.model.Step
import dev.quickreplay.record.RecordingSession
import dev.quickreplay.record.StrokeRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorded rhythm must survive record → replay, and replay latency must not add up. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimingTest {

    private val screen = ScreenSize(1080, 2400)

    /** Like dispatchGesture: the callback arrives after the gesture plus [latencyMs]. */
    private fun TestScope.realisticDispatcher(downs: MutableList<Long>, latencyMs: Long) = GestureDispatcher { gesture ->
        downs += currentTime
        delay(gesture.durationMs + latencyMs)
        true
    }

    private fun intervals(times: List<Long>) = times.zipWithNext { a, b -> b - a }

    @Test
    fun recordedTapsReplayWithTheSameIntervals() = runTest {
        // "tap 1, wait 3 s, tap 2, wait 0.5 s, tap 3" with 100 ms finger presses, as touch events.
        val recorder = StrokeRecorder(touchSlopPx = 10f, longPressMs = 500)
        val session = RecordingSession(startTimeMs = 0)
        val realDowns = listOf(1_000L, 4_000L, 4_500L)
        for ((i, down) in realDowns.withIndex()) {
            recorder.down(100f * (i + 1), 1500f, down)
            val up = down + 100
            session.add(recorder.up(100f * (i + 1), 1500f, up, session.delayBefore(down)), up)
        }
        val script = Script(name = "rec", screen = screen, steps = session.steps())

        val downs = mutableListOf<Long>()
        Player(realisticDispatcher(downs, latencyMs = 16), 60_000, { currentTime }).play(script)

        assertEquals(intervals(realDowns), intervals(downs)) // [3000, 500]
        assertEquals(1_000L, downs.first()) // first touch keeps its delay after REC
    }

    @Test
    fun callbackLatencyDoesNotAccumulate() = runTest {
        val steps = List(10) { Step.Tap(it.toFloat(), 0f, delayBeforeMs = 200) }
        val script = Script(name = "drift", screen = screen, repeat = 3, steps = steps)
        val downs = mutableListOf<Long>()
        Player(realisticDispatcher(downs, latencyMs = 10), 60_000, { currentTime }).play(script)

        assertEquals(30, downs.size)
        // Every tap lasts 50 ms and is followed by 200 ms: 250 ms apart, latency absorbed by the pauses.
        assertEquals(List(29) { 250L }, intervals(downs))
    }

    @Test
    fun pausesAreNeverShortenedByMoreThanTheCatchUpLimit() = runTest {
        // Back-to-back gestures with a very slow system leave a big backlog; a following 1 s pause
        // must still be (almost) a pause, not skipped.
        val steps = List(5) { Step.Tap(0f, 0f) } + Step.Tap(1f, 1f, delayBeforeMs = 1_000)
        val downs = mutableListOf<Long>()
        Player(realisticDispatcher(downs, latencyMs = 300), 60_000, { currentTime })
            .play(Script(name = "slow", screen = screen, steps = steps))

        val lastPause = downs[5] - (downs[4] + 50 + 300) // from the 5th gesture's completion
        assertTrue("pause was $lastPause ms", lastPause >= 1_000 - Timeline.MAX_CATCH_UP_MS)
    }

    @Test
    fun recoversCallbackLatencyUpToTheCatchUpLimit() = runTest {
        // Callbacks 170 ms late (measured under load): the taps must still be 250 ms apart.
        val steps = List(10) { Step.Tap(it.toFloat(), 0f, delayBeforeMs = 200) }
        val downs = mutableListOf<Long>()
        Player(realisticDispatcher(downs, latencyMs = 170), 60_000, { currentTime })
            .play(Script(name = "late", screen = screen, steps = steps))
        assertEquals(List(9) { 250L }, intervals(downs))
    }

    @Test
    fun hugeImportedDelaysDoNotOverflow() = runTest {
        val downs = mutableListOf<Long>()
        val script = Script(
            name = "huge", screen = screen,
            steps = listOf(Step.Tap(0f, 0f), Step.Tap(1f, 1f, delayBeforeMs = Long.MAX_VALUE)),
        )
        val job = launch { Player({ downs += currentTime; true }, 60_000, { currentTime }).play(script) }
        advanceTimeBy(10_000)
        assertEquals("the second tap must wait, not fire at once", 1, downs.size)
        job.cancel()
    }

    @Test
    fun cancelledGestureDoesNotShiftLaterSteps() = runTest {
        val downs = mutableListOf<Long>()
        // The system cancels each gesture immediately (e.g. the user touched the screen).
        val dispatcher = GestureDispatcher { downs += currentTime; false }
        val script = Script(
            name = "c", screen = screen,
            steps = listOf(Step.LongPress(0f, 0f, 2_000), Step.Tap(1f, 1f, delayBeforeMs = 300)),
        )
        val result = Player(dispatcher, 60_000, { currentTime }).play(script)
        assertEquals(listOf(0L, 300L), downs)
        assertEquals(2, result.failedGestures)
    }
}
