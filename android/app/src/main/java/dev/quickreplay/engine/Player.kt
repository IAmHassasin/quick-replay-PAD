package dev.quickreplay.engine

import dev.quickreplay.model.Script
import dev.quickreplay.model.Step
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/** Injects one gesture and suspends until the system reports it completed (true) or cancelled (false). */
fun interface GestureDispatcher {
    suspend fun dispatch(gesture: GestureSpec): Boolean
}

data class PlaybackProgress(val loop: Int, val step: Int, val totalSteps: Int, val failedGestures: Int)

/**
 * Plays a script step by step. There is no limit on steps or loops: steps are executed one at
 * a time, so memory use does not grow with script length or loop count.
 * Cancel the calling coroutine to stop.
 */
class Player(
    private val dispatcher: GestureDispatcher,
    private val maxGestureDurationMs: Long,
    private val onProgress: (PlaybackProgress) -> Unit = {},
) {
    suspend fun play(script: Script): PlaybackProgress {
        val total = script.steps.size
        var loop = 0
        var failed = 0
        var last = PlaybackProgress(0, 0, total, 0)
        if (total == 0) return last

        while (script.repeat == 0 || loop < script.repeat) {
            loop++
            if (loop > 1 && script.loopDelayMs > 0) delay(script.loopDelayMs)
            script.steps.forEachIndexed { index, step ->
                last = PlaybackProgress(loop, index + 1, total, failed)
                onProgress(last)
                if (step.delayBeforeMs > 0) delay(step.delayBeforeMs)
                if (step is Step.Wait) {
                    delay(step.ms)
                } else {
                    val gesture = GesturePlan.of(step, maxGestureDurationMs) ?: return@forEachIndexed
                    if (!dispatcher.dispatch(gesture)) failed++
                }
            }
            yield()
        }
        last = last.copy(failedGestures = failed)
        onProgress(last)
        return last
    }
}
