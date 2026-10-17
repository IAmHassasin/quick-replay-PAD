package dev.quickreplay.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.quickreplay.engine.GestureDispatcher
import dev.quickreplay.engine.GesturePlan
import dev.quickreplay.engine.GestureSpec
import dev.quickreplay.engine.Player
import dev.quickreplay.model.ScreenSize
import dev.quickreplay.model.Script
import dev.quickreplay.model.Step
import dev.quickreplay.overlay.CaptureLayer
import dev.quickreplay.overlay.OverlayPanel
import dev.quickreplay.record.RecordingSession
import dev.quickreplay.store.ScriptStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/**
 * Hosts the floating panel, records touches through [CaptureLayer] and replays scripts with
 * [dispatchGesture]. Requires the user to enable the service in Accessibility settings.
 */
class QuickReplayService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var store: ScriptStore
    private var panel: OverlayPanel? = null
    private var capture: CaptureLayer? = null
    private var session: RecordingSession? = null
    private var playback: Job? = null
    private var playbackGeneration = 0

    private val maxGestureMs: Long get() = GestureDescription.getMaxGestureDuration()

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        store = ScriptStore(this)
        instance = this
        panel = OverlayPanel(this, windowManager, onRecord = ::toggleRecording, onPlay = ::togglePlayback)
            .also { it.show() }
        ReplayState.mutable.value = ReplayState.Idle()
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopPlayback()
    }

    override fun onDestroy() {
        stopPlayback()
        stopRecording(save = false)
        panel?.hide()
        panel = null
        scope.cancel()
        instance = null
        ReplayState.mutable.value = ReplayState.Disconnected
        super.onDestroy()
    }

    fun screenSize(): ScreenSize {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            ScreenSize(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            ScreenSize(metrics.widthPixels, metrics.heightPixels)
        }
    }

    // ---- Recording -------------------------------------------------------------------------

    private fun toggleRecording() {
        if (capture != null) stopRecording(save = true) else startRecording()
    }

    fun startRecording() {
        if (capture != null) return
        stopPlayback()
        session = RecordingSession(SystemClock.uptimeMillis())
        val layer = CaptureLayer(this, windowManager, ::onRecorded)
        // The capture layer must sit below the panel so the panel's buttons stay usable.
        panel?.hide()
        layer.show()
        panel?.show()
        capture = layer
        ReplayState.mutable.value = ReplayState.Recording(0)
        Log.i(TAG, "recording started")
    }

    private fun onRecorded(step: Step, downTimeMs: Long, upTimeMs: Long) {
        val current = session ?: return
        val withDelay = when (step) {
            is Step.Tap -> step.copy(delayBeforeMs = current.delayBefore(downTimeMs))
            is Step.LongPress -> step.copy(delayBeforeMs = current.delayBefore(downTimeMs))
            is Step.Swipe -> step.copy(delayBeforeMs = current.delayBefore(downTimeMs))
            is Step.Wait -> step
        }
        current.add(withDelay, upTimeMs)
        ReplayState.mutable.value = ReplayState.Recording(current.size)
        Log.i(TAG, "recorded step ${current.size}: ${withDelay::class.simpleName}")

        // Forward the same gesture to the app underneath so it reacts as if touched directly.
        val gesture = GesturePlan.of(withDelay, maxGestureMs) ?: return
        val layer = capture ?: return
        layer.setPassThrough(true)
        scope.launch {
            // Window flag changes reach the input dispatcher asynchronously; without this pause the
            // forwarded gesture can land on the capture layer instead of the app.
            delay(PASS_THROUGH_SETTLE_MS)
            dispatch(gesture)
            delay(PASS_THROUGH_SETTLE_MS / 2)
            capture?.setPassThrough(false)
        }
    }

    fun stopRecording(save: Boolean): String? {
        val layer = capture ?: return null
        layer.hide()
        capture = null
        val steps = session?.steps().orEmpty()
        session = null
        if (!save || steps.isEmpty()) {
            ReplayState.mutable.value = ReplayState.Idle(if (save) "Nothing recorded" else null)
            return null
        }
        val script = Script(
            name = "Recording " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            screen = screenSize(),
            steps = steps,
            repeat = 1,
            createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
        val id = store.save(script)
        store.activeId = id
        ReplayState.mutable.value = ReplayState.Idle("Saved ${steps.size} steps")
        Log.i(TAG, "recording saved: ${steps.size} steps")
        return id
    }

    // ---- Playback --------------------------------------------------------------------------

    private fun togglePlayback() {
        if (playback?.isActive == true) stopPlayback() else play()
    }

    /** Plays the given script, or the active one. Returns false if there is nothing to play. */
    fun play(script: Script? = null): Boolean {
        if (capture != null) stopRecording(save = true)
        val toPlay = script ?: store.activeId?.let(store::load)
        if (toPlay == null) {
            ReplayState.mutable.value = ReplayState.Idle("No script selected")
            return false
        }
        stopPlayback()
        val scaled = toPlay.scaledTo(screenSize())
        val generation = ++playbackGeneration
        playback = scope.launch {
            val player = Player(dispatcher, maxGestureMs) { progress ->
                ReplayState.mutable.value = ReplayState.Playing(scaled.name, progress, scaled.repeat)
            }
            Log.i(TAG, "playback started: ${scaled.steps.size} steps, repeat=${scaled.repeat}")
            try {
                val result = player.play(scaled)
                ReplayState.mutable.value = ReplayState.Idle(
                    "Finished ${result.loop} loop(s)" +
                        if (result.failedGestures > 0) ", ${result.failedGestures} gesture(s) interrupted" else "",
                )
                Log.i(TAG, "playback finished: loops=${result.loop} failed=${result.failedGestures}")
            } finally {
                // A newer playback may already be running; only the latest one owns the state.
                if (generation == playbackGeneration && ReplayState.flow.value is ReplayState.Playing) {
                    ReplayState.mutable.value = ReplayState.Idle("Stopped")
                    Log.i(TAG, "playback stopped")
                }
            }
        }
        return true
    }

    fun stopPlayback() {
        playback?.cancel()
        playback = null
    }

    private val dispatcher = GestureDispatcher { spec -> dispatch(spec) }

    private suspend fun dispatch(spec: GestureSpec): Boolean = suspendCancellableCoroutine { cont ->
        val (startX, startY) = spec.points.first()
        val path = Path().apply {
            moveTo(startX.coerceAtLeast(0f), startY.coerceAtLeast(0f))
            spec.points.drop(1).forEach { (x, y) -> lineTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, spec.durationMs))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }
        if (!dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
    }

    companion object {
        private const val TAG = "QuickReplay"
        private const val PASS_THROUGH_SETTLE_MS = 80L

        @Volatile
        var instance: QuickReplayService? = null
            private set
    }
}
