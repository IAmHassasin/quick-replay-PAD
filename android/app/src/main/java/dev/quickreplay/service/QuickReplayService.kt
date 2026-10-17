package dev.quickreplay.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.quickreplay.engine.GestureDispatcher
import dev.quickreplay.engine.GesturePlan
import dev.quickreplay.engine.GestureSpec
import dev.quickreplay.engine.PauseGate
import dev.quickreplay.engine.PlaybackProgress
import dev.quickreplay.engine.Player
import dev.quickreplay.model.ScreenSize
import dev.quickreplay.model.Script
import dev.quickreplay.model.ScriptFormatException
import dev.quickreplay.model.ScriptSequence
import dev.quickreplay.model.Step
import dev.quickreplay.model.withDelayBefore
import dev.quickreplay.overlay.CaptureLayer
import dev.quickreplay.overlay.OverlayMenu
import dev.quickreplay.overlay.OverlayPanel
import dev.quickreplay.overlay.PlaybackOverlay
import dev.quickreplay.record.RecordingSession
import dev.quickreplay.store.AppSettings
import dev.quickreplay.store.LibraryEntry
import dev.quickreplay.store.ScriptStore
import dev.quickreplay.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    private lateinit var settings: AppSettings
    private var panel: OverlayPanel? = null
    private var menu: OverlayMenu? = null
    private var capture: CaptureLayer? = null
    private var pauseGate: PauseGate? = null
    private var session: RecordingSession? = null
    private var playback: Job? = null
    private var playbackGeneration = 0
    @Volatile
    private var playbackOverlay: PlaybackOverlay? = null
    private var overlayHide: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Playback runs on its own thread: drawing overlays on the main thread must not delay the
    // timeline or the gesture completion callbacks.
    private val playerThread = HandlerThread("QuickReplayPlayer").apply { start() }
    private val playerHandler = Handler(playerThread.looper)
    private val playerContext = playerHandler.asCoroutineDispatcher()

    private val maxGestureMs: Long get() = GestureDescription.getMaxGestureDuration()

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        store = ScriptStore(this)
        settings = AppSettings(this)
        instance = this
        panel = OverlayPanel(
            this, windowManager,
            onRecord = ::toggleRecording, onPlay = ::togglePlayback, onPause = ::togglePause, onMenu = ::toggleMenu,
        )
        menu = OverlayMenu(this, windowManager, menuActions)
        updatePanelVisibility()
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
        playbackOverlay?.hide()
        playbackOverlay = null
        menu?.hide()
        menu = null
        panel?.hide()
        panel = null
        scope.cancel()
        playerThread.quitSafely()
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
        menu?.hide()
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
        val withDelay = step.withDelayBefore(current.delayBefore(downTimeMs))
        current.add(withDelay, upTimeMs)
        val layer = capture ?: return
        val action = layer.markRecorded(withDelay, current.size)
        ReplayState.mutable.value = ReplayState.Recording(current.size, action)
        Log.i(TAG, "recorded step ${current.size}: ${withDelay::class.simpleName} delayBefore=${withDelay.delayBeforeMs}ms")

        // Forward the same gesture to the app underneath so it reacts as if touched directly.
        val gesture = GesturePlan.of(withDelay, maxGestureMs) ?: return
        layer.setPassThrough(true)
        scope.launch {
            // Window flag changes reach the input dispatcher asynchronously; without this pause the
            // forwarded gesture can land on the capture layer instead of the app.
            delay(PASS_THROUGH_SETTLE_MS)
            val swallowed = layer.swallowedInjectedTouches
            inject(gesture)
            if (layer.swallowedInjectedTouches != swallowed && capture === layer) {
                // The flag change was still not applied (seen on a busy emulator): the app got
                // nothing, so forward once more now that the layer is surely pass-through.
                Log.w(TAG, "forwarded gesture landed on the capture layer; forwarding again")
                delay(PASS_THROUGH_SETTLE_MS)
                inject(gesture)
            }
            delay(PASS_THROUGH_SETTLE_MS / 2)
            capture?.setPassThrough(false)
        }
    }

    fun stopRecording(save: Boolean): String? {
        val layer = capture ?: return null
        layer.hide()
        capture = null
        updatePanelVisibility()
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
            repeat = settings.defaultRepeat,
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

    /**
     * Plays the given script, or the active script or combined script. Returns false if there is
     * nothing to play.
     */
    fun play(script: Script? = null): Boolean {
        if (capture != null) stopRecording(save = true)
        if (script != null) {
            playScript(script)
            return true
        }
        val id = store.activeId
        val (active, sequence) = try {
            val loaded = id?.let(store::load)
            loaded to if (loaded == null) id?.let(store::loadSequence) else null
        } catch (e: ScriptFormatException) {
            // A damaged file must not crash the service (it runs on the main thread).
            Log.w(TAG, "cannot read active script: ${e.message}")
            ReplayState.mutable.value = ReplayState.Idle("Cannot read the active script: ${e.message}")
            return false
        }
        when {
            active != null -> playScript(active)
            sequence != null -> play(sequence)
            else -> {
                ReplayState.mutable.value = ReplayState.Idle("No script selected")
                return false
            }
        }
        return true
    }

    /** Plays a combined script: each part [dev.quickreplay.model.SequencePart.repeat] times, in order. */
    fun play(sequence: ScriptSequence) {
        if (capture != null) stopRecording(save = true)
        val scaled = sequence.scaledTo(screenSize())
        Log.i(TAG, "playback started: sequence of ${scaled.parts.size} parts, repeat=${scaled.repeat}")
        startPlayback(
            run = { player -> player.play(scaled) },
            state = { p -> ReplayState.Playing(scaled.name, p, scaled.repeat, scaled.parts.getOrNull(p.part - 1)?.script?.name) },
        )
    }

    private fun playScript(script: Script) {
        val scaled = script.scaledTo(screenSize())
        Log.i(TAG, "playback started: ${scaled.steps.size} steps, repeat=${scaled.repeat}")
        startPlayback(
            run = { player -> player.play(scaled) },
            state = { p -> ReplayState.Playing(scaled.name, p, scaled.repeat) },
        )
    }

    private fun startPlayback(run: suspend (Player) -> PlaybackProgress, state: (PlaybackProgress) -> ReplayState.Playing) {
        stopPlayback()
        menu?.hide() // replayed gestures would press or close it
        // The panel is shown while playing even if hidden in the settings, so STOP stays reachable.
        updatePanelVisibility(playing = true)
        showPlaybackOverlay()
        val generation = ++playbackGeneration
        val gate = PauseGate().also { pauseGate = it }
        playback = scope.launch {
            val player = Player(playerGestures, maxGestureMs, SystemClock::uptimeMillis, gate) { progress ->
                // Runs on the player thread. A cancelled run can still report one more step, so
                // state is only written on the main thread and only by the current generation.
                playingStep = progress.step
                val update = state(progress)
                mainHandler.post {
                    if (generation == playbackGeneration) ReplayState.mutable.value = update.copy(paused = gate.isPaused)
                }
            }
            try {
                val result = withContext(playerContext) { run(player) }
                // Posted through the same handler as the progress updates, so it is queued after
                // the last of them (the coroutine itself may resume ahead of those messages).
                mainHandler.post {
                    if (generation != playbackGeneration) return@post
                    ReplayState.mutable.value = ReplayState.Idle(
                        "Finished ${result.loop} loop(s)" +
                            if (result.failedGestures > 0) ", ${result.failedGestures} gesture(s) interrupted" else "",
                    )
                    Log.i(TAG, "playback finished: loops=${result.loop} failed=${result.failedGestures}")
                }
            } finally {
                // A newer playback may already be running; only the latest one owns the overlays.
                if (generation == playbackGeneration) {
                    pauseGate = null
                    hidePlaybackOverlayLater(generation)
                    updatePanelVisibility(playing = false)
                }
            }
        }
    }

    fun stopPlayback() {
        val job = playback ?: return
        playback = null
        pauseGate = null
        // From now on the cancelled run must not touch the state (see startPlayback).
        val generation = ++playbackGeneration
        job.cancel()
        if (ReplayState.flow.value is ReplayState.Playing) {
            ReplayState.mutable.value = ReplayState.Idle("Stopped")
            Log.i(TAG, "playback stopped")
        }
        hidePlaybackOverlayLater(generation)
        updatePanelVisibility(playing = false)
    }

    /** Pauses or resumes the running playback; an interrupted pause keeps the time it had left. */
    fun togglePause() {
        val gate = pauseGate ?: return
        if (gate.isPaused) gate.resume() else gate.pause()
        (ReplayState.flow.value as? ReplayState.Playing)?.let { ReplayState.mutable.value = it.copy(paused = gate.isPaused) }
        Log.i(TAG, if (gate.isPaused) "playback paused" else "playback resumed")
    }

    val isPaused: Boolean get() = pauseGate?.isPaused == true

    // ---- Floating panel and menu ---------------------------------------------------------------

    /**
     * Shows the panel if the settings allow it, and always while recording or playing (so SAVE,
     * STOP and PAUSE can be reached). Called again when the setting changes in the app.
     */
    fun updatePanelVisibility(playing: Boolean = playback?.isActive == true) {
        val current = panel ?: return
        if (settings.showPanel || playing || capture != null) {
            current.show()
        } else {
            menu?.hide()
            current.hide()
        }
    }

    private fun toggleMenu(pressDownTime: Long) {
        val (x, top, bottom) = panel?.bounds() ?: return
        menu?.toggle(x, top, bottom, pressDownTime)
    }

    /** Menu callbacks run on the main thread; a damaged file or a full disk must not crash the service. */
    private val menuActions = object : OverlayMenu.Actions {
        override fun entries(): List<LibraryEntry> = store.entries()

        override fun activeId(): String? = store.activeId

        override fun nameOf(id: String): String? = try {
            store.nameOf(id)
        } catch (e: Exception) { // ScriptFormatException, or IOException if the file just went away
            Log.w(TAG, "cannot read script $id: ${e.message}")
            null
        }

        override fun select(id: String) {
            store.activeId = id
            if (ReplayState.flow.value is ReplayState.Idle) {
                ReplayState.mutable.value = ReplayState.Idle("Active: ${nameOf(id) ?: "unreadable script"}")
            }
        }

        override fun rename(id: String, name: String): String? {
            if (name.isBlank()) return "Name cannot be empty"
            return try {
                if (!store.rename(id, name)) return "The script no longer exists"
                if (ReplayState.flow.value is ReplayState.Idle) ReplayState.mutable.value = ReplayState.Idle("Renamed to ${name.trim()}")
                null
            } catch (e: ScriptFormatException) {
                "Cannot read the script: ${e.message}"
            } catch (e: Exception) {
                // e.g. IOException when the disk is full; shown in the menu instead of crashing.
                Log.w(TAG, "rename failed: ${e.message}")
                "Could not save: ${e.message}"
            }
        }

        override fun openApp() {
            startActivity(
                Intent(this@QuickReplayService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    private fun showPlaybackOverlay() {
        overlayHide?.cancel()
        if (!settings.showPlaybackTouches) {
            playbackOverlay?.hide()
            return
        }
        val overlay = playbackOverlay ?: PlaybackOverlay(this, windowManager).also { playbackOverlay = it }
        if (overlay.show()) {
            // Keep the panel above the overlay.
            panel?.hide()
            panel?.show()
        }
    }

    /** Lets the last markers fade out, then removes the overlay unless a new playback started. */
    private fun hidePlaybackOverlayLater(generation: Int) {
        overlayHide = scope.launch {
            delay(OVERLAY_LINGER_MS)
            if (generation == playbackGeneration && playback?.isActive != true) playbackOverlay?.hide()
        }
    }

    /** Step number being played, written on the player thread. */
    @Volatile
    private var playingStep: Int? = null

    /** Used by the player on [playerThread]; the overlay is only touched on the main thread. */
    private val playerGestures = object : GestureDispatcher {
        override suspend fun dispatch(gesture: GestureSpec): Boolean = inject(gesture, playerHandler)

        override suspend fun dispatch(gesture: GestureSpec, step: Step): Boolean {
            val number = playingStep
            playbackOverlay?.let { overlay -> mainHandler.post { overlay.onGesture(gesture, step, number) } }
            return inject(gesture, playerHandler)
        }
    }

    /** Injects [spec]; the result callback runs on [handler] (null = main thread). */
    private suspend fun inject(spec: GestureSpec, handler: Handler? = null): Boolean = suspendCancellableCoroutine { cont ->
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
        if (!dispatchGesture(gesture, callback, handler) && cont.isActive) cont.resume(false)
    }

    companion object {
        private const val TAG = "QuickReplay"
        private const val PASS_THROUGH_SETTLE_MS = 80L
        private const val OVERLAY_LINGER_MS = 2_000L

        @Volatile
        var instance: QuickReplayService? = null
            private set
    }
}
