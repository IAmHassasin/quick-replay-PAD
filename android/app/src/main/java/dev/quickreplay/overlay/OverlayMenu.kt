package dev.quickreplay.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.quickreplay.R
import dev.quickreplay.store.LibraryEntry

/**
 * Menu opened from the floating panel's ☰ button: open the app, choose the active script or
 * combined script, rename it. Tapping outside closes it. The window only takes keyboard focus
 * while the rename field is shown.
 */
class OverlayMenu(
    context: Context,
    private val windowManager: WindowManager,
    private val actions: Actions,
) {
    interface Actions {
        fun entries(): List<LibraryEntry>
        fun activeId(): String?

        /** Name of the script or combined script [id], or null if it cannot be read. */
        fun nameOf(id: String): String?
        fun select(id: String)

        /** Renames [id]; returns an error message, or null on success. */
        fun rename(id: String, name: String): String?
        fun openApp()
    }

    private val context = ContextThemeWrapper(context, R.style.Theme_QuickReplay)
    private var shown = false

    /** `downTime` of the touch that last closed the menu from outside. */
    private var dismissedByTouchDown = Long.MIN_VALUE
    private var panelTop = 0
    private var panelBottom = 0

    private val root = LinearLayout(this.context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.argb(245, 24, 26, 32))
            cornerRadius = dp(16).toFloat()
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnTouchListener(OutsideTouch())
    }

    private val params = WindowManager.LayoutParams(
        dp(290),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        NOT_FOCUSABLE_FLAGS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    val isShown: Boolean get() = shown

    /**
     * Opens the menu next to the panel (left edge [x], panel from [top] to [bottom] on screen), or
     * closes it. [pressDownTime] is the `downTime` of the ☰ press: the same touch first reaches the
     * menu as an outside touch and closes it, and must not open it again.
     */
    fun toggle(x: Int, top: Int, bottom: Int, pressDownTime: Long) {
        if (shown) {
            hide()
            return
        }
        if (pressDownTime == dismissedByTouchDown) return
        val screenWidth = context.resources.displayMetrics.widthPixels
        params.x = x.coerceAtMost(screenWidth - params.width).coerceAtLeast(0)
        panelTop = top
        panelBottom = bottom
        showMain()
        place()
        windowManager.addView(root, params)
        shown = true
    }

    /**
     * Puts the menu below the panel if it fits, otherwise above it, so it never covers the panel.
     * The rename page stays in the upper half: the keyboard opens under accessibility overlays.
     */
    private fun place() {
        val screenHeight = context.resources.displayMetrics.heightPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST),
        )
        val height = root.measuredHeight
        val gap = dp(6)
        val limit = if (params.flags == FOCUSABLE_FLAGS) screenHeight / 2 else screenHeight
        params.y = when {
            panelBottom + gap + height <= limit -> panelBottom + gap
            panelTop - gap - height >= 0 && panelTop - gap <= limit -> panelTop - gap - height
            else -> (limit - height).coerceAtLeast(0)
        }
        if (shown) windowManager.updateViewLayout(root, params)
    }

    fun hide() {
        if (!shown) return
        hideKeyboard()
        windowManager.removeViewImmediate(root)
        shown = false
        setFocusable(false)
    }

    private fun showMain() {
        if (params.flags == FOCUSABLE_FLAGS) hideKeyboard() // coming back from the rename page
        setFocusable(false)
        root.removeAllViews()
        root.addView(text("Quick Replay", 16f, bold = true))
        val activeId = actions.activeId()
        val activeName = activeId?.let(actions::nameOf)
        root.addView(text(activeName?.let { "Active: $it" } ?: "No script selected", 13f, MUTED), spaced(4))
        root.addView(item("Open Quick Replay app") { hide(); actions.openApp() }, spaced(12))
        root.addView(item("Change script…") { showList() }, spaced(8))
        root.addView(
            item("Rename script…", enabled = activeName != null) { if (activeId != null && activeName != null) showRename(activeId, activeName) },
            spaced(8),
        )
        root.addView(item("Close") { hide() }, spaced(8))
        if (shown) place()
    }

    private fun showList() {
        root.removeAllViews()
        root.addView(text("Choose the script PLAY runs", 15f, bold = true))
        val entries = actions.entries()
        val active = actions.activeId()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (entries.isEmpty()) list.addView(text("No scripts yet. Record one with REC.", 13f, MUTED))
        for (entry in entries) {
            val isActive = entry.id == active
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(if (isActive) ACTIVE_BG else ITEM_BG)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                setOnClickListener {
                    actions.select(entry.id)
                    hide()
                }
            }
            row.addView(text((if (isActive) "● " else "") + entry.name, 14f, if (isActive) OK else FG, bold = true))
            row.addView(text(entry.detail, 12f, MUTED))
            list.addView(row, spaced(6))
        }
        val scroll = ScrollView(context).apply { addView(list) }
        val height = if (entries.size > MAX_VISIBLE_ITEMS) dp(MAX_VISIBLE_ITEMS * ITEM_HEIGHT_DP) else ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(6) })
        root.addView(item("Back") { showMain() }, spaced(10))
        place()
    }

    /** [id] is fixed when the page opens, so Save renames that script even if the active one changes. */
    private fun showRename(id: String, current: String) {
        root.removeAllViews()
        root.addView(text("Rename \"$current\"", 15f, bold = true))
        val error = text("", 12f, ERROR)
        val input = EditText(context).apply {
            setText(current)
            setSingleLine()
            setSelectAllOnFocus(true) // typing replaces the old name
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(FG)
        }
        val save = {
            val message = actions.rename(id, input.text.toString())
            if (message == null) hide() else error.text = message
        }
        input.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) save() } }
        input.setOnKeyListener { _, keyCode, event ->
            (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP).also { if (it) showMain() }
        }
        root.addView(input, spaced(8))
        root.addView(error)
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(item("Save", primary = true) { save() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(
            item("Cancel") { showMain() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) },
        )
        root.addView(buttons, spaced(8))
        // Typing needs a focusable window; it goes back to not focusable when the page closes.
        setFocusable(true)
        place()
        input.requestFocus()
        showKeyboardWhenFocused(input)
    }

    /**
     * The window only becomes focusable now, and the IME ignores requests from a window that does
     * not have focus yet; ask as soon as it gets focus.
     */
    private fun showKeyboardWhenFocused(input: EditText) {
        val show = {
            if (input.isAttachedToWindow) {
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        if (input.hasWindowFocus()) {
            input.post { show() }
            return
        }
        // Registered on the root's observer (the window's), which outlives the page's views.
        val observer = root.viewTreeObserver
        observer.addOnWindowFocusChangeListener(object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) return
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
                input.post { show() }
            }
        })
    }

    private fun setFocusable(focusable: Boolean) {
        val flags = if (focusable) FOCUSABLE_FLAGS else NOT_FOCUSABLE_FLAGS
        if (params.flags == flags) return
        params.flags = flags
        params.softInputMode = if (focusable) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0
        if (shown) windowManager.updateViewLayout(root, params)
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private inner class OutsideTouch : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility") // Only closes the menu.
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_OUTSIDE) return false
            hide()
            dismissedByTouchDown = event.downTime
            return true
        }
    }

    private fun text(value: String, size: Float, color: Int = FG, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun item(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        gravity = if (primary) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(if (enabled) FG else MUTED)
        background = rounded(if (primary) ACCENT else ITEM_BG)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        minHeight = 0
        minimumHeight = dp(44)
        isEnabled = enabled
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val FG = 0xFFE7E9EE.toInt()
        const val MUTED = 0xFF8B92A1.toInt()
        const val OK = 0xFF3ECF8E.toInt()
        const val ERROR = 0xFFFF6B6B.toInt()
        const val ACCENT = 0xFF4F8CFF.toInt()
        const val ITEM_BG = 0xFF262A33.toInt()
        const val ACTIVE_BG = 0xFF1F3A2E.toInt()
        const val MAX_VISIBLE_ITEMS = 6
        const val ITEM_HEIGHT_DP = 58

        const val NOT_FOCUSABLE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        const val FOCUSABLE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }
}
