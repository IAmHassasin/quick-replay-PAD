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

fun Step.withDelayBefore(ms: Long): Step = when (this) {
    is Step.Tap -> copy(delayBeforeMs = ms)
    is Step.LongPress -> copy(delayBeforeMs = ms)
    is Step.Swipe -> copy(delayBeforeMs = ms)
    is Step.Wait -> copy(delayBeforeMs = ms)
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
    /** Time one pass of the steps takes when replayed: pauses plus gesture durations. */
    val passDurationMs: Long
        get() = steps.sumOf { step ->
            step.delayBeforeMs + when (step) {
                is Step.Tap -> step.durationMs
                is Step.LongPress -> step.durationMs
                is Step.Swipe -> step.points.last().t - step.points.first().t
                is Step.Wait -> step.ms
            }
        }

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

/**
 * One script of a [ScriptSequence]. The part's [repeat] (>= 1) replaces the embedded script's own
 * `repeat`; the script's `loopDelayMs` is still used between the part's repeats.
 */
data class SequencePart(
    val script: Script,
    val repeat: Int = 1,
    /** Pause before the part starts, on top of the first step's own `delayBeforeMs`. */
    val delayBeforeMs: Long = 0,
) {
    init {
        require(repeat >= 1) { "A part must run at least once" }
        require(delayBeforeMs >= 0) { "delayBeforeMs must be >= 0" }
    }
}

/**
 * A combined script (`quickreplay-sequence/v1`): parts are played in order, each [SequencePart.repeat]
 * times, and the whole sequence [repeat] times (0 = loop forever). Scripts are embedded, so a
 * sequence keeps working after its source scripts are deleted and exports as a single file.
 */
data class ScriptSequence(
    val name: String,
    val parts: List<SequencePart>,
    val repeat: Int = 1,
    val loopDelayMs: Long = 0,
    val createdAt: String? = null,
) {
    fun scaledTo(target: ScreenSize): ScriptSequence =
        copy(parts = parts.map { it.copy(script = it.script.scaledTo(target)) })
}
