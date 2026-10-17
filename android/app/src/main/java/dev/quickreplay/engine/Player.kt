package dev.quickreplay.engine

import dev.quickreplay.model.Script
import dev.quickreplay.model.ScriptSequence
import dev.quickreplay.model.SequencePart
import dev.quickreplay.model.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** Injects one gesture and suspends until the system reports it completed (true) or cancelled (false). */
fun interface GestureDispatcher {
    suspend fun dispatch(gesture: GestureSpec): Boolean

    /** Same, also told which [step] the gesture comes from (e.g. to label it on screen). */
    suspend fun dispatch(gesture: GestureSpec, step: Step): Boolean = dispatch(gesture)
}

/**
 * [loop]/[step] refer to the whole script (or the whole sequence). For a sequence, [part] is the
 * 1-based part being played and [partLoop] its current repeat out of [partRepeat].
 */
data class PlaybackProgress(
    val loop: Int,
    val step: Int,
    val totalSteps: Int,
    val failedGestures: Int,
    val part: Int = 1,
    val partCount: Int = 1,
    val partLoop: Int = 1,
    val partRepeat: Int = 1,
)

/**
 * Plays a script step by step. There is no limit on steps or loops: steps are executed one at
 * a time, so memory use does not grow with script length or loop count.
 * Cancel the calling coroutine to stop.
 *
 * Timing: every step is scheduled on a timeline (`delayBeforeMs` after the previous gesture's
 * planned end) instead of sleeping after each gesture, so the latency of `dispatchGesture`
 * callbacks does not add up over long scripts and loops. [clock] must be monotonic and use the
 * same time base as [delay] (`SystemClock.uptimeMillis` on Android).
 */
class Player(
    private val dispatcher: GestureDispatcher,
    private val maxGestureDurationMs: Long,
    private val clock: () -> Long,
    private val pauseGate: PauseGate = PauseGate(),
    private val onProgress: (PlaybackProgress) -> Unit = {},
) {
    suspend fun play(script: Script): PlaybackProgress {
        val total = script.steps.size
        var last = PlaybackProgress(0, 0, total, 0)
        if (total == 0) return last
        val run = Run()
        var loop = 0
        while (script.repeat == 0 || loop < script.repeat) {
            loop++
            if (loop > 1) run.timeline.wait(script.loopDelayMs)
            run.playPass(script) { step -> PlaybackProgress(loop, step, total, run.failed).also { last = it } }
            yield()
        }
        return finish(last, run)
    }

    suspend fun play(sequence: ScriptSequence): PlaybackProgress {
        val parts = sequence.parts.withIndex().filter { it.value.script.steps.isNotEmpty() }
        val total = parts.sumOf { it.value.script.steps.size }
        var last = PlaybackProgress(0, 0, total, 0, partCount = sequence.parts.size)
        if (total == 0) return last
        val run = Run()
        var loop = 0
        while (sequence.repeat == 0 || loop < sequence.repeat) {
            loop++
            if (loop > 1) run.timeline.wait(sequence.loopDelayMs)
            var offset = 0 // steps of earlier parts, so step numbers run across the whole sequence
            for ((index, part) in parts) {
                val partIndex = index + 1
                run.timeline.wait(part.delayBeforeMs)
                playPart(run, part) { partLoop, step ->
                    PlaybackProgress(
                        loop, offset + step, total, run.failed,
                        part = partIndex, partCount = sequence.parts.size, partLoop = partLoop, partRepeat = part.repeat,
                    ).also { last = it }
                }
                offset += part.script.steps.size
            }
            yield()
        }
        return finish(last, run)
    }

    private suspend fun playPart(run: Run, part: SequencePart, progress: (partLoop: Int, step: Int) -> PlaybackProgress) {
        for (partLoop in 1..part.repeat) {
            if (partLoop > 1) run.timeline.wait(part.script.loopDelayMs)
            run.playPass(part.script) { step -> progress(partLoop, step) }
        }
    }

    private fun finish(last: PlaybackProgress, run: Run): PlaybackProgress =
        last.copy(failedGestures = run.failed).also(onProgress)

    /** State of one play() call. */
    private inner class Run {
        val timeline = Timeline(clock, pauseGate)
        var failed = 0

        /** Plays every step of [script] once. */
        suspend fun playPass(script: Script, progress: (step: Int) -> PlaybackProgress) {
            script.steps.forEachIndexed { index, step ->
                onProgress(progress(index + 1))
                timeline.wait(step.delayBeforeMs)
                if (step is Step.Wait) {
                    timeline.wait(step.ms)
                } else {
                    val gesture = GesturePlan.of(step, maxGestureDurationMs) ?: return@forEachIndexed
                    if (!dispatcher.dispatch(gesture, step)) failed++
                    timeline.gestureEnded(gesture.durationMs)
                }
            }
        }
    }
}

/**
 * Pauses a running [Player] from any thread. While paused no new gesture starts and pauses stop
 * counting down; after [resume] the interrupted pause continues with the time it had left.
 * A gesture that is already being injected finishes first.
 */
class PauseGate {
    private val paused = MutableStateFlow(false)

    val isPaused: Boolean get() = paused.value

    fun pause() {
        paused.value = true
    }

    fun resume() {
        paused.value = false
    }

    internal suspend fun awaitPaused() {
        paused.first { it }
    }

    internal suspend fun awaitResumed() {
        paused.first { !it }
    }
}

/**
 * Planned time of the playback. [wait] sleeps until "previous planned point + ms", so time spent
 * waiting for a gesture's completion callback is subtracted from the next pause. Time spent paused
 * through [gate] moves the whole plan later instead.
 */
internal class Timeline(private val clock: () -> Long, private val gate: PauseGate = PauseGate()) {
    private var cursor = clock()

    suspend fun wait(ms: Long) {
        val now = clock()
        // Never shorten a pause by more than MAX_CATCH_UP_MS, even after a long hiccup
        // (e.g. many back-to-back gestures with no pause to absorb their latency).
        cursor = saturatingAdd(maxOf(cursor, now - MAX_CATCH_UP_MS), ms)
        sleepUntilCursor()
    }

    /** Sleeps until the cursor; returns only when it is reached and playback is not paused. */
    private suspend fun sleepUntilCursor() {
        while (true) {
            if (gate.isPaused) {
                val pausedAt = clock()
                gate.awaitResumed()
                cursor = saturatingAdd(cursor, clock() - pausedAt) // keep what was left of the pause
                continue
            }
            val remaining = cursor - clock()
            if (remaining <= 0) return
            // Sleep, but wake up as soon as the user pauses.
            val pausedMeanwhile = withTimeoutOrNull(remaining) { gate.awaitPaused() } != null
            if (!pausedMeanwhile && !gate.isPaused) return
        }
    }

    /**
     * Called after a gesture of [durationMs] returned. A gesture that completed late (callback
     * latency) ends at its planned time; one that returned early (cancelled) ends now.
     */
    fun gestureEnded(durationMs: Long) {
        cursor = minOf(clock(), saturatingAdd(cursor, durationMs))
    }

    /** Imported scripts may hold absurd delays; never let the timeline wrap around. */
    private fun saturatingAdd(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b

    companion object {
        /**
         * Completion callbacks were measured up to ~170 ms late under load (emulator, overlay on);
         * the gesture itself is injected on time, so that much lag is recovered from the next pause.
         */
        const val MAX_CATCH_UP_MS = 250L
    }
}
