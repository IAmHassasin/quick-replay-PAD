package dev.quickreplay.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import dev.quickreplay.model.Step
import dev.quickreplay.record.StrokeRecorder

/**
 * Full-screen transparent layer shown while recording. Android does not report coordinates of
 * touches on other apps' windows, so this layer receives the touch itself, records it and the
 * service then forwards the same gesture to the app below with dispatchGesture().
 */
@SuppressLint("ViewConstructor")
class CaptureLayer(
    context: Context,
    private val windowManager: WindowManager,
    private val onStep: (step: Step, downTimeMs: Long, upTimeMs: Long) -> Unit,
) : View(context) {

    private val config = ViewConfiguration.get(context)
    private val recorder = StrokeRecorder(
        touchSlopPx = config.scaledTouchSlop.toFloat(),
        longPressMs = ViewConfiguration.getLongPressTimeout().toLong(),
    )
    private val trail = Path()
    private var downTime = 0L
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 82, 82)
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }
    private val borderPaint = Paint().apply {
        color = Color.argb(160, 255, 82, 82)
        style = Paint.Style.STROKE
        strokeWidth = 12f
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
        val flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (flags == params.flags || !shown) return
        params.flags = flags
        windowManager.updateViewLayout(this, params)
    }

    @SuppressLint("ClickableViewAccessibility") // Touches are recorded, not handled as clicks.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Gestures injected by an accessibility service carry TOOL_TYPE_UNKNOWN; never record our own
        // forwarded gestures (same technique as Smart-AutoClicker's UserTouchWatcher).
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN) return true
        // Only the first pointer is recorded (multi-touch is out of scope for v1).
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
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
            MotionEvent.ACTION_CANCEL -> trail.reset()
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
        // The layer is laid out in screen space, so raw coordinates map 1:1 after the offset.
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        canvas.drawPath(trail, trailPaint)
        canvas.restore()
    }
}
