package dev.quickreplay.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Short-lived on-screen markers for gestures: a ripple for a tap, a ring for a long press and the
 * path with an arrow for a swipe, each with a label such as "#3 Tap 80 ms". Coordinates are
 * screen pixels. Used by [CaptureLayer] (what was recorded) and [PlaybackOverlay] (what is played).
 */
internal class TouchMarkers(private val context: Context) {

    enum class Kind { TAP, LONG_PRESS, SWIPE }

    private class Marker(
        val kind: Kind,
        val points: List<Pair<Float, Float>>,
        val label: String,
        val startMs: Long,
        /** Time the gesture itself is running (playback); 0 for a finished recorded gesture. */
        val activeMs: Long,
    ) {
        val path = Path().apply {
            moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
    }

    private val markers = ArrayList<Marker>()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, context.resources.displayMetrics)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 24, 26, 32) }
    private val arc = RectF()
    private val measure = PathMeasure()
    private val position = FloatArray(2)

    fun add(kind: Kind, points: List<Pair<Float, Float>>, label: String, activeMs: Long = 0) {
        if (points.isEmpty()) return
        markers += Marker(kind, points, label, SystemClock.uptimeMillis(), activeMs)
        if (markers.size > MAX_MARKERS) markers.removeAt(0)
    }

    /** Draws all live markers; returns true while something is still animating. */
    fun draw(canvas: Canvas): Boolean {
        val now = SystemClock.uptimeMillis()
        markers.removeAll { now - it.startMs > it.activeMs + VISIBLE_MS }
        for (marker in markers) {
            val age = now - marker.startMs
            val fadeStart = marker.activeMs + VISIBLE_MS - FADE_MS
            val alpha = if (age <= fadeStart) 1f else 1f - (age - fadeStart).toFloat() / FADE_MS
            drawMarker(canvas, marker, age, alpha.coerceIn(0f, 1f))
        }
        return markers.isNotEmpty()
    }

    private fun drawMarker(canvas: Canvas, marker: Marker, age: Long, alpha: Float) {
        val color = colorOf(marker.kind)
        val (x, y) = marker.points.first()
        val progress = if (marker.activeMs > 0) (age.toFloat() / marker.activeMs).coerceIn(0f, 1f) else 1f
        when (marker.kind) {
            Kind.TAP -> {
                val t = (age / RIPPLE_MS.toFloat()).coerceIn(0f, 1f)
                stroke.color = withAlpha(color, alpha * (1f - 0.6f * t))
                canvas.drawCircle(x, y, dp(14f) + dp(30f) * t, stroke)
                fill.color = withAlpha(color, alpha * 0.85f)
                canvas.drawCircle(x, y, dp(9f), fill)
                drawLabel(canvas, marker.label, x, y, alpha)
            }
            Kind.LONG_PRESS -> {
                fill.color = withAlpha(color, alpha * 0.35f)
                canvas.drawCircle(x, y, dp(30f), fill)
                stroke.color = withAlpha(color, alpha)
                arc.set(x - dp(30f), y - dp(30f), x + dp(30f), y + dp(30f))
                canvas.drawArc(arc, -90f, 360f * progress, false, stroke)
                fill.color = withAlpha(color, alpha)
                canvas.drawCircle(x, y, dp(8f), fill)
                drawLabel(canvas, marker.label, x, y, alpha)
            }
            Kind.SWIPE -> {
                stroke.color = withAlpha(color, alpha)
                canvas.drawPath(marker.path, stroke)
                fill.color = withAlpha(color, alpha)
                canvas.drawCircle(x, y, dp(7f), fill)
                drawArrowHead(canvas, marker.points)
                if (progress < 1f) {
                    // The injected stroke moves at constant speed along the path; show where it is.
                    measure.setPath(marker.path, false)
                    measure.getPosTan(measure.length * progress, position, null)
                    canvas.drawCircle(position[0], position[1], dp(12f), fill)
                }
                val (ex, ey) = marker.points.last()
                drawLabel(canvas, marker.label, ex, ey, alpha)
            }
        }
    }

    private fun drawArrowHead(canvas: Canvas, points: List<Pair<Float, Float>>) {
        val (ex, ey) = points.last()
        // Use the last point that is far enough away to give a stable direction.
        val from = points.asReversed().firstOrNull { (px, py) -> hypot(ex - px, ey - py) > dp(12f) } ?: return
        val angle = atan2(ey - from.second, ex - from.first)
        val size = dp(16f)
        val head = Path().apply {
            moveTo(ex, ey)
            lineTo(ex - size * cos(angle - ARROW_SPREAD), ey - size * sin(angle - ARROW_SPREAD))
            lineTo(ex - size * cos(angle + ARROW_SPREAD), ey - size * sin(angle + ARROW_SPREAD))
            close()
        }
        canvas.drawPath(head, fill)
    }

    /** Pill-shaped label above the point, kept inside the canvas. */
    fun drawLabel(canvas: Canvas, label: String, x: Float, y: Float, alpha: Float) {
        if (label.isEmpty()) return
        val pad = dp(8f)
        val width = labelText.measureText(label) + pad * 2
        val height = labelText.textSize + pad * 1.4f
        val left = (x - width / 2).coerceIn(pad, (canvas.width - width - pad).coerceAtLeast(pad))
        var top = y - dp(52f) - height
        if (top < pad) top = y + dp(52f)
        labelBg.alpha = (215 * alpha).toInt()
        labelText.alpha = (255 * alpha).toInt()
        arc.set(left, top, left + width, top + height)
        canvas.drawRoundRect(arc, height / 2, height / 2, labelBg)
        canvas.drawText(label, left + pad, top + height / 2 - (labelText.ascent() + labelText.descent()) / 2, labelText)
    }

    fun colorOf(kind: Kind): Int = when (kind) {
        Kind.TAP -> TAP_COLOR
        Kind.LONG_PRESS -> LONG_PRESS_COLOR
        Kind.SWIPE -> SWIPE_COLOR
    }

    private fun withAlpha(color: Int, alpha: Float) =
        Color.argb((255 * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    companion object {
        const val TAP_COLOR = 0xFF4F8CFF.toInt()
        const val LONG_PRESS_COLOR = 0xFFFFB547.toInt()
        const val SWIPE_COLOR = 0xFF3ECF8E.toInt()
        private const val VISIBLE_MS = 1_400L
        private const val FADE_MS = 500L
        private const val RIPPLE_MS = 450L
        private const val MAX_MARKERS = 24
        private const val ARROW_SPREAD = 0.45f

        /** "80 ms" or "1.2 s". */
        fun duration(ms: Long): String = if (ms < 1_000) "$ms ms" else String.format(Locale.ROOT, "%.1f s", ms / 1000f)

        /** Label used for a gesture of [kind], e.g. "#3 Tap 80 ms". */
        fun label(number: Int?, kind: Kind, durationMs: Long): String {
            val name = when (kind) {
                Kind.TAP -> "Tap"
                Kind.LONG_PRESS -> "Long press"
                Kind.SWIPE -> "Swipe"
            }
            return listOfNotNull(number?.let { "#$it" }, name, duration(durationMs)).joinToString(" ")
        }
    }
}
