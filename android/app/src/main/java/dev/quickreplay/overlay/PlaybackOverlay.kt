package dev.quickreplay.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager
import dev.quickreplay.engine.GestureSpec
import dev.quickreplay.model.Step

/**
 * Full-screen, non-touchable layer that draws each gesture while it is replayed, so the user can
 * see what the script does. Being FLAG_NOT_TOUCHABLE, injected gestures pass through it to the app.
 */
@SuppressLint("ViewConstructor")
class PlaybackOverlay(
    context: Context,
    private val windowManager: WindowManager,
) : View(context) {

    private val markers = TouchMarkers(context)
    private val location = IntArray(2)
    private var shown = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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

    /** Returns true if the layer was added now (callers then re-add the panel above it). */
    fun show(): Boolean {
        if (shown) return false
        windowManager.addView(this, params)
        shown = true
        return true
    }

    fun hide() {
        if (shown) windowManager.removeViewImmediate(this)
        shown = false
    }

    /** Shows [gesture] of [step] as step [number] of the script. */
    fun onGesture(gesture: GestureSpec, step: Step, number: Int?) {
        if (!shown) return
        val kind = when (step) {
            is Step.Swipe -> TouchMarkers.Kind.SWIPE
            is Step.LongPress -> TouchMarkers.Kind.LONG_PRESS
            else -> TouchMarkers.Kind.TAP
        }
        markers.add(kind, gesture.points, TouchMarkers.label(number, kind, gesture.durationMs), activeMs = gesture.durationMs)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        val animating = markers.draw(canvas)
        canvas.restore()
        if (animating) postInvalidateOnAnimation()
    }
}
