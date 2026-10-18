package dev.quickreplay.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import dev.quickreplay.pad.Goal
import dev.quickreplay.pad.Orbs
import dev.quickreplay.pad.PadSetup
import dev.quickreplay.pad.Shapes

/**
 * Edits a [PadSetup]: goals, drag speed, diagonal moves, search limits, board size and what to do
 * when the goals cannot be met. Invalid input keeps the dialog open with a message.
 */
internal object PadSetupDialog {
    private val TYPES = Shapes.TYPES + "combos"
    private val TYPE_LABELS = Shapes.TYPES.map { Shapes.LABELS.getValue(it) } + "Combos (at least)"
    private val COLOR_LABELS = Orbs.NAMES.take(Orbs.ATTRIBUTES)

    fun show(activity: Activity, title: String, initial: PadSetup, onSave: (PadSetup) -> Unit) {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(context, 20)
            setPadding(pad, Ui.dp(context, 8), pad, 0)
        }
        form.addView(Ui.text(activity, "Goals (all must be met)", 12f, Ui.MUTED))
        val goals = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        initial.goals.forEach { addGoalRow(activity, goals, it) }
        form.addView(goals)
        form.addView(
            Ui.buttonRow(activity, Ui.button(activity, "Add goal") { addGoalRow(activity, goals, Goal.Shape("row", Orbs.code('L'))) }),
            Ui.spacedParams(activity, 4),
        )

        val msPerMove = field(activity, form, "Drag time per move (ms, ${PadSetup.MIN_MS_PER_MOVE}–${PadSetup.MAX_MS_PER_MOVE}). Raise it if the game misses cells.", initial.msPerMove)
        val diagonals = CheckBox(activity).apply {
            text = "Diagonal moves"
            setTextColor(Ui.FG)
            isChecked = initial.diagonals
        }
        form.addView(diagonals, Ui.spacedParams(activity, 6))
        val maxMoves = field(activity, form, "Max moves (1–${PadSetup.MAX_MOVES})", initial.maxMoves.toLong())
        val timeLimit = field(activity, form, "Solve time limit (ms). More time finds shorter paths.", initial.timeLimitMs)
        val cols = field(activity, form, "Board columns", initial.cols.toLong())
        val rows = field(activity, form, "Board rows", initial.rows.toLong())
        val dragWhenUnmet = CheckBox(activity).apply {
            text = "If the goals cannot be met, drag the best path anyway (otherwise the solve fails and a script stops)"
            setTextColor(Ui.FG)
            isChecked = initial.dragWhenUnmet
        }
        form.addView(dragWhenUnmet, Ui.spacedParams(activity, 6))

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val setup = try {
                    PadSetup(
                        goals = readGoals(goals),
                        rows = rows.number("Board rows").toInt(),
                        cols = cols.number("Board columns").toInt(),
                        diagonals = diagonals.isChecked,
                        maxMoves = maxMoves.number("Max moves").toInt(),
                        timeLimitMs = timeLimit.number("Solve time limit"),
                        msPerMove = msPerMove.number("Drag time per move"),
                        dragWhenUnmet = dragWhenUnmet.isChecked,
                    )
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(activity, e.message, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                onSave(setup)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun field(activity: Activity, form: LinearLayout, label: String, value: Long): EditText {
        form.addView(Ui.text(activity, label, 12f, Ui.MUTED), Ui.spacedParams(activity, 10))
        return Ui.input(activity, value.toString(), number = true).also(form::addView)
    }

    private fun EditText.number(name: String): Long =
        text.toString().toLongOrNull() ?: throw IllegalArgumentException("$name must be a number")

    private fun addGoalRow(activity: Activity, container: LinearLayout, goal: Goal) {
        val row = Ui.row(activity)
        val type = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS)
            setSelection(TYPES.indexOf(goal.type).coerceAtLeast(0))
        }
        val color = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, COLOR_LABELS)
            setSelection(((goal as? Goal.Shape)?.color ?: Orbs.code('L')).coerceIn(0, Orbs.ATTRIBUTES - 1))
        }
        val count = Ui.input(activity, ((goal as? Goal.Combos)?.count ?: 7).toString(), number = true).apply { minEms = 2 }
        fun sync() {
            val combos = TYPES[type.selectedItemPosition] == "combos"
            color.visibility = if (combos) android.view.View.GONE else android.view.View.VISIBLE
            count.visibility = if (combos) android.view.View.VISIBLE else android.view.View.GONE
        }
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = sync()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        sync()
        // Spinners share the width; the remove button keeps its own size.
        val share = { weight: Float -> LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight) }
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.addView(type, share(1.2f))
        row.addView(color, share(1f))
        row.addView(count, share(1f))
        row.addView(
            Ui.button(activity, "✕") { container.removeView(row) }.apply { contentDescription = "Remove goal" },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        row.tag = GoalRow(type, color, count)
        container.addView(row, Ui.spacedParams(activity, 4))
    }

    /** Views of one goal row, kept in the row's tag. */
    private class GoalRow(val type: Spinner, val color: Spinner, val count: EditText)

    private fun readGoals(container: LinearLayout): List<Goal> = (0 until container.childCount).map { i ->
        val row = container.getChildAt(i).tag as GoalRow
        val kind = TYPES[row.type.selectedItemPosition]
        if (kind == "combos") {
            Goal.Combos(row.count.text.toString().toIntOrNull() ?: throw IllegalArgumentException("Combo count must be a number"))
        } else {
            Goal.Shape(kind, row.color.selectedItemPosition)
        }
    }
}
