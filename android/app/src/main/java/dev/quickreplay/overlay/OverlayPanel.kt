package dev.quickreplay.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.quickreplay.service.ReplayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Small draggable floating panel: drag handle, record, play/stop, pause/resume (while playing),
 * the ☰ menu and a status line.
 */
class OverlayPanel(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onRecord: () -> Unit,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    /** Called with the `downTime` of the ☰ press (see [OverlayMenu.toggle]). */
    private val onMenu: (pressDownTime: Long) -> Unit,
) {
    private var scope: CoroutineScope? = null
    private var shown = false
    private val status = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(8), 0, dp(8), 0)
        // Long part names must not push the panel off the screen.
        maxWidth = dp(170)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val recordButton = button("REC") { onRecord() }
    private val playButton = button("PLAY") { onPlay() }
    private val pauseButton = button("PAUSE") { onPause() }.apply { visibility = View.GONE }
    private var menuPressDownTime = 0L

    @SuppressLint("ClickableViewAccessibility") // The listener only reads the time; clicks still work.
    private val menuButton = button("☰") {
        onMenu(menuPressDownTime)
        menuPressDownTime = -1 // a later click without a touch (e.g. TalkBack) must not reuse it
    }.apply {
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) menuPressDownTime = event.downTime
            false // the click itself is still handled by the button
        }
    }
    private val handle = TextView(context).apply {
        text = "⋮⋮"
        setTextColor(Color.LTGRAY)
        textSize = 18f
        setPadding(dp(10), dp(6), dp(6), dp(6))
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.argb(225, 24, 26, 32))
            cornerRadius = dp(24).toFloat()
        }
        setPadding(dp(4), dp(4), dp(8), dp(4))
        addView(handle)
        addView(recordButton)
        addView(playButton)
        addView(pauseButton)
        addView(menuButton)
        addView(status)
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(360)
    }

    init {
        enableDrag()
    }

    fun show() {
        if (shown) return
        windowManager.addView(root, params)
        shown = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { ReplayState.flow.collect(::render) }
        }
    }

    fun hide() {
        scope?.cancel()
        scope = null
        // removeView() detaches asynchronously; a quick hide()+show() must not see a stale state.
        if (shown) windowManager.removeViewImmediate(root)
        shown = false
    }

    /** Left edge, top and bottom of the panel on screen, for placing the menu. */
    fun bounds(): Triple<Int, Int, Int> {
        // Dragging does not clamp params, but the window manager keeps the panel on screen.
        val metrics = context.resources.displayMetrics
        val x = params.x.coerceIn(0, (metrics.widthPixels - root.width).coerceAtLeast(0))
        val y = params.y.coerceIn(0, (metrics.heightPixels - root.height).coerceAtLeast(0))
        return Triple(x, y, y + root.height)
    }

    private fun render(state: ReplayState) {
        pauseButton.visibility = if (state is ReplayState.Playing) View.VISIBLE else View.GONE
        // Replayed gestures would land on (or close) the menu, so it is only available while idle.
        menuButton.isEnabled = state !is ReplayState.Recording && state !is ReplayState.Playing
        when (state) {
            is ReplayState.Recording -> {
                recordButton.text = "SAVE"
                playButton.isEnabled = false
                status.text = "REC · ${state.steps}" + (state.lastAction?.let { " · ✓ $it" } ?: "")
            }
            is ReplayState.Playing -> {
                recordButton.isEnabled = false
                playButton.text = "STOP"
                pauseButton.text = if (state.paused) "RESUME" else "PAUSE"
                val p = state.progress
                val loops = if (state.repeat == 0) "∞" else state.repeat.toString()
                val part = state.partName?.let { "${it.take(14)} ${p.partLoop}/${p.partRepeat} · " }.orEmpty()
                status.text = (if (state.paused) "Paused · " else "") + "$part${p.step}/${p.totalSteps} · loop ${p.loop}/$loops"
            }
            is ReplayState.Idle -> {
                recordButton.text = "REC"
                recordButton.isEnabled = true
                playButton.text = "PLAY"
                playButton.isEnabled = true
                status.text = state.message ?: "Ready"
            }
            ReplayState.Disconnected -> status.text = "Off"
        }
    }

    @SuppressLint("ClickableViewAccessibility") // The handle only moves the panel.
    private fun enableDrag() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    if (shown) windowManager.updateViewLayout(root, params)
                }
            }
            true
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()
}
