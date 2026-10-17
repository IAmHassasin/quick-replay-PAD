package dev.quickreplay

import dev.quickreplay.engine.GesturePlan
import dev.quickreplay.engine.GestureSpec
import dev.quickreplay.engine.PlaybackProgress
import dev.quickreplay.engine.Player
import dev.quickreplay.model.ScreenSize
import dev.quickreplay.model.Script
import dev.quickreplay.model.Step
import dev.quickreplay.model.StrokePoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineTest {

    private val screen = ScreenSize(1000, 2000)

    @Test
    fun gesturePlanClampsDurations() {
        assertEquals(GestureSpec(listOf(1f to 2f), 50), GesturePlan.of(Step.Tap(1f, 2f), 60_000))
        assertEquals(60_000L, GesturePlan.of(Step.LongPress(1f, 2f, 90_000), 60_000)!!.durationMs)
        val swipe = Step.Swipe(listOf(StrokePoint(0f, 0f, 100), StrokePoint(5f, 5f, 400)))
        assertEquals(300L, GesturePlan.of(swipe, 60_000)!!.durationMs)
        assertNull(GesturePlan.of(Step.Wait(10), 60_000))
    }

    @Test
    fun playsStepsInOrderWithDelaysAndLoops() = runTest {
        val dispatched = mutableListOf<Pair<Long, GestureSpec>>()
        val player = Player({ dispatched += currentTime to it; true }, 60_000, { currentTime })
        val script = Script(
            name = "t", screen = screen, repeat = 2, loopDelayMs = 1000,
            steps = listOf(Step.Tap(1f, 1f, delayBeforeMs = 100), Step.Wait(500), Step.Tap(2f, 2f, delayBeforeMs = 50)),
        )
        val result = player.play(script)
        assertEquals(listOf(100L, 650L, 1750L, 2300L), dispatched.map { it.first })
        assertEquals(PlaybackProgress(loop = 2, step = 3, totalSteps = 3, failedGestures = 0), result)
    }

    @Test
    fun infiniteRepeatRunsUntilCancelled() = runTest {
        var count = 0
        val progress = mutableListOf<PlaybackProgress>()
        val player = Player({ count++; count % 2 == 0 }, 60_000, { currentTime }) { progress += it }
        val script = Script(name = "t", screen = screen, repeat = 0, steps = listOf(Step.Tap(1f, 1f, delayBeforeMs = 10)))
        val job = launch { player.play(script) }
        advanceTimeBy(10_005)
        job.cancel()
        assertEquals(1000, count)
        assertTrue(progress.last().loop >= 1000)
        assertEquals(500, progress.last().failedGestures)
    }

    @Test
    fun emptyScriptFinishesImmediately() = runTest {
        val result = Player({ true }, 60_000, { currentTime }).play(Script(name = "e", screen = screen, repeat = 0, steps = emptyList()))
        assertEquals(0, result.loop)
    }
}
