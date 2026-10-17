package dev.quickreplay.model

/** A point of a free-form stroke. [t] is milliseconds since the stroke started. */
data class StrokePoint(val x: Float, val y: Float, val t: Long)

/** One step of an Android script (`quickreplay/v1`, see docs/script-format.md). */
sealed interface Step {
    val delayBeforeMs: Long

    data class Tap(
        val x: Float,
        val y: Float,
        val durationMs: Long = DEFAULT_TAP_MS,
        override val delayBeforeMs: Long = 0,
    ) : Step

    data class LongPress(
        val x: Float,
        val y: Float,
        val durationMs: Long,
        override val delayBeforeMs: Long = 0,
    ) : Step

    data class Swipe(
        val points: List<StrokePoint>,
        override val delayBeforeMs: Long = 0,
    ) : Step {
        init {
            require(points.size >= 2) { "A swipe needs at least 2 points" }
        }
    }

    data class Wait(val ms: Long, override val delayBeforeMs: Long = 0) : Step

    companion object {
        const val DEFAULT_TAP_MS = 50L
    }
}

data class ScreenSize(val width: Int, val height: Int)

data class Script(
    val name: String,
    val screen: ScreenSize,
    val steps: List<Step>,
    /** 0 = loop forever. */
    val repeat: Int = 1,
    val loopDelayMs: Long = 0,
    val createdAt: String? = null,
) {
    /** Returns a copy whose coordinates are mapped from [screen] to [target]. */
    fun scaledTo(target: ScreenSize): Script {
        if (target == screen) return this
        val sx = target.width.toFloat() / screen.width
        val sy = target.height.toFloat() / screen.height
        val scaled = steps.map { step ->
            when (step) {
                is Step.Tap -> step.copy(x = step.x * sx, y = step.y * sy)
                is Step.LongPress -> step.copy(x = step.x * sx, y = step.y * sy)
                is Step.Swipe -> step.copy(points = step.points.map { it.copy(x = it.x * sx, y = it.y * sy) })
                is Step.Wait -> step
            }
        }
        return copy(screen = target, steps = scaled)
    }
}
