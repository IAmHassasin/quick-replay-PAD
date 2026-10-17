package dev.quickreplay.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A test surface for scripts: counts taps, long presses and swipes, and draws touch trails.
 * Every detected gesture is also logged (tag "QuickReplayPlayground") so runs can be verified
 * from `adb logcat`.
 */
class PlaygroundActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(PlaygroundView(this))
    }
}

@SuppressLint("ViewConstructor")
private class PlaygroundView(context: Context) : View(context) {
    private var taps = 0
    private var longPresses = 0
    private var swipes = 0
    private var lastSwipe = "-"
    private val trails = ArrayDeque<Path>()
    private var current: Path? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var maxMove = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout()

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.FG; textSize = Ui.dp(context, 22).toFloat() }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = Ui.dp(context, 14).toFloat() }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 4).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 79, 140, 255) }
    private var lastDot: Pair<Float, Float>? = null

    init {
        setBackgroundColor(Ui.BG)
    }

    @SuppressLint("ClickableViewAccessibility") // Gestures are counted, not clicks.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                maxMove = 0f
                current = Path().apply { moveTo(event.x, event.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                maxMove = maxOf(maxMove, hypot(event.x - downX, event.y - downY))
                current?.lineTo(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                maxMove = maxOf(maxMove, hypot(event.x - downX, event.y - downY))
                val duration = event.eventTime - downTime
                when {
                    maxMove <= slop && duration < longPressMs -> {
                        taps++
                        lastDot = event.x to event.y
                        Log.i(TAG, "tap #$taps at ${event.rawX.toInt()},${event.rawY.toInt()}")
                    }
                    maxMove <= slop -> {
                        longPresses++
                        Log.i(TAG, "longPress #$longPresses ${duration}ms")
                    }
                    else -> {
                        swipes++
                        val dx = event.x - downX
                        val dy = event.y - downY
                        lastSwipe = if (abs(dx) > abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
                        Log.i(TAG, "swipe #$swipes $lastSwipe")
                    }
                }
                current?.let { trails.addLast(it); if (trails.size > 30) trails.removeFirst() }
                current = null
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val pad = Ui.dp(context, 24).toFloat()
        var y = Ui.dp(context, 72).toFloat()
        canvas.drawText("Playground", pad, y, text)
        y += Ui.dp(context, 28)
        canvas.drawText("Taps: $taps   Long presses: $longPresses   Swipes: $swipes", pad, y, small)
        y += Ui.dp(context, 22)
        canvas.drawText("Last swipe: $lastSwipe", pad, y, small)
        trails.forEach { canvas.drawPath(it, trailPaint) }
        current?.let { canvas.drawPath(it, trailPaint) }
        lastDot?.let { (x, py) -> canvas.drawCircle(x, py, Ui.dp(context, 18).toFloat(), dot) }
    }

    private companion object {
        const val TAG = "QuickReplayPlayground"
    }
}
