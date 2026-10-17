package dev.quickreplay.engine

import dev.quickreplay.model.Step

/** Platform-independent description of one gesture to inject. */
data class GestureSpec(val points: List<Pair<Float, Float>>, val durationMs: Long)

object GesturePlan {
    /**
     * Converts a step to a single gesture, clamping its duration to [maxDurationMs]
     * (`GestureDescription.getMaxGestureDuration()` on a device). Returns null for steps
     * that inject nothing, such as `wait`.
     */
    fun of(step: Step, maxDurationMs: Long): GestureSpec? = when (step) {
        is Step.Tap -> GestureSpec(listOf(step.x to step.y), step.durationMs.clamp(maxDurationMs))
        is Step.LongPress -> GestureSpec(listOf(step.x to step.y), step.durationMs.clamp(maxDurationMs))
        is Step.Swipe -> GestureSpec(
            step.points.map { it.x to it.y },
            (step.points.last().t - step.points.first().t).clamp(maxDurationMs),
        )
        is Step.Wait -> null
    }

    private fun Long.clamp(max: Long): Long = coerceIn(1, max)
}
