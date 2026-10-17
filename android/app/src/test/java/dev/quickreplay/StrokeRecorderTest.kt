package dev.quickreplay

import dev.quickreplay.model.Step
import dev.quickreplay.model.StrokePoint
import dev.quickreplay.record.RecordingSession
import dev.quickreplay.record.StrokeRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeRecorderTest {

    private val recorder = StrokeRecorder(touchSlopPx = 10f, longPressMs = 500)

    @Test
    fun shortStillTouchIsTap() {
        recorder.down(100f, 200f, 1_000)
        recorder.move(103f, 202f, 1_040)
        assertEquals(Step.Tap(100f, 200f, delayBeforeMs = 7), recorder.up(104f, 203f, 1_080, delayBeforeMs = 7))
    }

    @Test
    fun longStillTouchIsLongPress() {
        recorder.down(100f, 200f, 1_000)
        assertEquals(Step.LongPress(100f, 200f, 900), recorder.up(101f, 200f, 1_900, delayBeforeMs = 0))
    }

    @Test
    fun movingTouchIsSwipeWithTiming() {
        recorder.down(500f, 1800f, 1_000)
        recorder.move(500f, 1500f, 1_100)
        recorder.move(500f, 1501f, 1_103) // closer than the sample interval: skipped
        recorder.move(500f, 1000f, 1_200)
        val step = recorder.up(500f, 900f, 1_250, delayBeforeMs = 0) as Step.Swipe
        assertEquals(
            listOf(StrokePoint(500f, 1800f, 0), StrokePoint(500f, 1500f, 100), StrokePoint(500f, 1000f, 200), StrokePoint(500f, 900f, 250)),
            step.points,
        )
    }

    @Test
    fun longPathsAreDownsampled() {
        val small = StrokeRecorder(touchSlopPx = 10f, longPressMs = 500, maxPoints = 50)
        small.down(0f, 0f, 0)
        for (i in 1..1000) small.move(i.toFloat(), 0f, i * 10L)
        val step = small.up(1001f, 0f, 10_010, 0) as Step.Swipe
        assertEquals(50, step.points.size)
        assertEquals(0L, step.points.first().t)
        assertEquals(10_010L, step.points.last().t)
    }

    @Test
    fun sessionComputesPausesBetweenGestures() {
        val session = RecordingSession(startTimeMs = 10_000)
        assertEquals(1_500, session.delayBefore(11_500))
        session.add(Step.Tap(1f, 1f), upTimeMs = 11_550)
        assertEquals(450, session.delayBefore(12_000))
        assertEquals(0, session.delayBefore(11_000))
        assertTrue(session.steps().size == 1)
    }
}
