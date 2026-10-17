package dev.quickreplay.record

import dev.quickreplay.model.Step
import dev.quickreplay.model.StrokePoint
import kotlin.math.hypot

/**
 * Turns the raw touch stream of one finger into a script step.
 *
 * - Movement within [touchSlopPx] and shorter than [longPressMs] -> `tap`
 * - Movement within [touchSlopPx] and at least [longPressMs] -> `longPress`
 * - Otherwise -> `swipe` with the sampled path and timing
 */
class StrokeRecorder(
    private val touchSlopPx: Float,
    private val longPressMs: Long,
    private val minSampleIntervalMs: Long = 8,
    private val maxPoints: Int = 500,
) {
    private val points = ArrayList<StrokePoint>()
    private var downTime = 0L
    private var maxDistance = 0f

    val isActive: Boolean get() = points.isNotEmpty()

    fun down(x: Float, y: Float, timeMs: Long) {
        points.clear()
        downTime = timeMs
        maxDistance = 0f
        points += StrokePoint(x, y, 0)
    }

    fun move(x: Float, y: Float, timeMs: Long) {
        if (points.isEmpty()) return
        val first = points.first()
        maxDistance = maxOf(maxDistance, hypot(x - first.x, y - first.y))
        val t = timeMs - downTime
        if (t - points.last().t >= minSampleIntervalMs) points += StrokePoint(x, y, t)
    }

    /** Finishes the stroke. [delayBeforeMs] is the pause since the previous step ended. */
    fun up(x: Float, y: Float, timeMs: Long, delayBeforeMs: Long): Step {
        check(points.isNotEmpty()) { "up() without down()" }
        val first = points.first()
        maxDistance = maxOf(maxDistance, hypot(x - first.x, y - first.y))
        val duration = (timeMs - downTime).coerceAtLeast(1)
        // The release point always ends the path, exactly at the stroke duration.
        if (points.size > 1 && points.last().t >= duration) points.removeAt(points.lastIndex)
        points += StrokePoint(x, y, duration)
        val step = when {
            maxDistance <= touchSlopPx && duration < longPressMs ->
                Step.Tap(first.x, first.y, durationMs = Step.DEFAULT_TAP_MS, delayBeforeMs = delayBeforeMs)
            maxDistance <= touchSlopPx ->
                Step.LongPress(first.x, first.y, durationMs = duration, delayBeforeMs = delayBeforeMs)
            else -> Step.Swipe(downsample(points), delayBeforeMs = delayBeforeMs)
        }
        points.clear()
        return step
    }

    private fun downsample(source: List<StrokePoint>): List<StrokePoint> {
        if (source.size <= maxPoints) return source.toList()
        val stride = (source.size - 1).toFloat() / (maxPoints - 1)
        return List(maxPoints) { i -> source[Math.round(i * stride)] }
    }
}

/** Accumulates steps of a recording and computes the pause before each one. */
class RecordingSession(startTimeMs: Long) {
    private val steps = ArrayList<Step>()
    private var lastEndMs = startTimeMs

    val size: Int get() = steps.size

    /** Pause between the end of the previous gesture (or the start) and [downTimeMs]. */
    fun delayBefore(downTimeMs: Long): Long = (downTimeMs - lastEndMs).coerceAtLeast(0)

    fun add(step: Step, upTimeMs: Long) {
        steps += step
        lastEndMs = upTimeMs
    }

    fun steps(): List<Step> = steps.toList()
}
