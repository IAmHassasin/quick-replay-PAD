package dev.quickreplay.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import dev.quickreplay.engine.GesturePlan
import dev.quickreplay.model.Step
import dev.quickreplay.record.StrokeRecorder

/**
 * Full-screen transparent layer shown while recording. Android does not report coordinates of
 * touches on other apps' windows, so this layer receives the touch itself, records it and the
 * service then forwards the same gesture to the app below with dispatchGesture().
 *
 * Feedback: a dot follows the finger, a ring and timer appear once a still touch becomes a long
 * press, moving touches draw a trail; every recorded step leaves a labelled marker
 * ([markRecorded]). The border is red while touches are recorded and yellow while a gesture is
 * being forwarded (touches in that window reach the app but are not recorded).
 */
@SuppressLint("ViewConstructor")
class CaptureLayer(
    context: Context,
    private val windowManager: WindowManager,
    private val onStep: (step: Step, downTimeMs: Long, upTimeMs: Long) -> Unit,
) : View(context) {

    private val config = ViewConfiguration.get(context)
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val recorder = StrokeRecorder(
        touchSlopPx = config.scaledTouchSlop.toFloat(),
        longPressMs = longPressMs,
    )
    private val markers = TouchMarkers(context)
    private val density = resources.displayMetrics.density
    private val trail = Path()
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var busy = false
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TouchMarkers.SWIPE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f
    }

    /** Number of injected (forwarded) touches that landed on this layer instead of the app below. */
    var swallowedInjectedTouches = 0
        private set

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // Windows added from a service are software-rendered by default; animating a full-screen
            // layer that way stalls the main thread and with it the playback timing.
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    private val location = IntArray(2)
    private var shown = false

    fun show() {
        if (shown) return
        windowManager.addView(this, params)
        shown = true
    }

    fun hide() {
        if (shown) windowManager.removeViewImmediate(this)
        shown = false
    }

    /** While forwarding a gesture the layer must let touches through to the app below. */
    fun setPassThrough(enabled: Boolean) {
        busy = enabled
        invalidate()
        val flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (flags == params.flags || !shown) return
        params.flags = flags
        windowManager.updateViewLayout(this, params)
    }

    /**
     * Leaves a labelled marker for the step that was just recorded as step [number] and returns a
     * short description of it, e.g. "Tap 80 ms".
     */
    fun markRecorded(step: Step, number: Int): String? {
        val (kind, points) = when (step) {
            is Step.Tap -> TouchMarkers.Kind.TAP to listOf(step.x to step.y)
            is Step.LongPress -> TouchMarkers.Kind.LONG_PRESS to listOf(step.x to step.y)
            is Step.Swipe -> TouchMarkers.Kind.SWIPE to step.points.map { it.x to it.y }
            is Step.Wait -> return null
        }
        val duration = GesturePlan.of(step, Long.MAX_VALUE)?.durationMs ?: 0
        markers.add(kind, points, TouchMarkers.label(number, kind, duration))
        invalidate()
        return TouchMarkers.label(null, kind, duration)
    }

    @SuppressLint("ClickableViewAccessibility") // Touches are recorded, not handled as clicks.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Gestures injected by an accessibility service carry TOOL_TYPE_UNKNOWN; never record our own
        // forwarded gestures (same technique as Smart-AutoClicker's UserTouchWatcher).
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) swallowedInjectedTouches++
            return true
        }
        // Only the first pointer is recorded (multi-touch is out of scope for v1).
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                downX = event.rawX
                downY = event.rawY
                recorder.down(event.rawX, event.rawY, event.eventTime)
                trail.reset()
                trail.moveTo(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_MOVE -> if (recorder.isActive) {
                recorder.move(event.rawX, event.rawY, event.eventTime)
                trail.lineTo(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP -> if (recorder.isActive) {
                val step = recorder.up(event.rawX, event.rawY, event.eventTime, delayBeforeMs = 0)
                trail.reset()
                onStep(step, downTime, event.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                trail.reset()
                recorder.cancel()
            }
        }
        fingerX = event.rawX
        fingerY = event.rawY
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        borderPaint.color = if (busy) BUSY_COLOR else RECORDING_COLOR
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
        // The layer is laid out in screen space, so raw coordinates map 1:1 after the offset.
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        var animating = markers.draw(canvas)
        if (recorder.isActive) {
            drawLiveTouch(canvas)
            animating = true // the long-press timer keeps changing while the finger is down
        }
        canvas.restore()
        if (animating) postInvalidateOnAnimation()
    }

    /** Feedback while the finger is still on the screen. */
    private fun drawLiveTouch(canvas: Canvas) {
        val held = SystemClock.uptimeMillis() - downTime
        when {
            recorder.isMoving -> {
                canvas.drawPath(trail, trailPaint)
                fingerPaint.color = TouchMarkers.SWIPE_COLOR
                canvas.drawCircle(fingerX, fingerY, 11 * density, fingerPaint)
                markers.drawLabel(canvas, "Swipe…", fingerX, fingerY, 1f)
            }
            held >= longPressMs -> {
                fingerPaint.color = Color.argb(110, 255, 181, 71)
                canvas.drawCircle(downX, downY, 30 * density, fingerPaint)
                markers.drawLabel(canvas, "Long press " + TouchMarkers.duration(held), downX, downY, 1f)
            }
            else -> {
                fingerPaint.color = Color.argb(150, 79, 140, 255)
                canvas.drawCircle(downX, downY, 16 * density, fingerPaint)
            }
        }
    }

    private companion object {
        val RECORDING_COLOR = Color.argb(170, 255, 82, 82)
        val BUSY_COLOR = Color.argb(220, 255, 224, 102)
    }
}
