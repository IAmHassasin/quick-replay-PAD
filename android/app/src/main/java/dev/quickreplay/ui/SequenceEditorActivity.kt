package dev.quickreplay.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.quickreplay.model.Script
import dev.quickreplay.model.ScriptSequence
import dev.quickreplay.model.SequencePart
import dev.quickreplay.store.ScriptStore
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds a combined script: pick recorded scripts in order and set how many times each one runs,
 * e.g. A ×1 → B ×3 → C ×2. Scripts are copied into the combined script when added.
 */
class SequenceEditorActivity : Activity() {

    /** Editable copy of one part. */
    private class PartDraft(val script: Script, var repeat: Int, var delayBeforeMs: Long)

    private lateinit var store: ScriptStore
    private var sequenceId: String? = null
    private var createdAt: String? = null
    private val parts = ArrayList<PartDraft>()
    private var name = ""
    private var repeatText = "1"
    private var forever = false
    private var loopDelayText = "0"
    private lateinit var partList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScriptStore(this)
        sequenceId = intent.getStringExtra(EXTRA_ID)
        val existing = sequenceId?.let(store::loadSequence)
        if (existing != null) {
            name = existing.name
            forever = existing.repeat == 0
            repeatText = if (forever) "1" else existing.repeat.toString()
            loopDelayText = existing.loopDelayMs.toString()
            createdAt = existing.createdAt
            existing.parts.forEach { parts += PartDraft(it.script, it.repeat, it.delayBeforeMs) }
        } else {
            sequenceId = null
            name = "Combo " + (store.listSequences().size + 1)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(context, 16)
            setPadding(pad, Ui.dp(context, 24), pad, pad)
        }
        content.addView(Ui.text(this, if (existing != null) "Edit combined script" else "New combined script", 22f, bold = true))
        content.addView(
            Ui.text(this, "Parts play from top to bottom. Each part runs its script the given number of times.", 13f, Ui.MUTED),
        )

        val settingsCard = Ui.card(this)
        settingsCard.addView(Ui.text(this, "Name", 12f, Ui.MUTED))
        settingsCard.addView(Ui.input(this, name) { name = it })
        settingsCard.addView(Ui.text(this, "Play the whole combined script how many times", 12f, Ui.MUTED), Ui.spacedParams(this, 10))
        val repeatInput = Ui.input(this, repeatText, number = true) { repeatText = it }
        repeatInput.isEnabled = !forever
        settingsCard.addView(repeatInput)
        settingsCard.addView(CheckBox(this).apply {
            text = "Loop forever (until STOP)"
            setTextColor(Ui.FG)
            isChecked = forever
            setOnCheckedChangeListener { _, checked -> forever = checked; repeatInput.isEnabled = !checked }
        })
        settingsCard.addView(Ui.text(this, "Pause between loops of the whole combined script (ms)", 12f, Ui.MUTED), Ui.spacedParams(this, 10))
        settingsCard.addView(Ui.input(this, loopDelayText, number = true) { loopDelayText = it })
        content.addView(settingsCard, Ui.spacedParams(this, 16))

        content.addView(Ui.text(this, "Parts", 16f, bold = true), Ui.spacedParams(this, 20))
        partList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(partList)
        content.addView(Ui.buttonRow(this, Ui.button(this, "+ Add script") { pickScript() }), Ui.spacedParams(this, 10))
        content.addView(
            Ui.buttonRow(
                this,
                Ui.button(this, "Save", primary = true) { save() },
                Ui.button(this, "Cancel") { finish() },
            ),
            Ui.spacedParams(this, 24),
        )

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Ui.BG)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        renderParts()
    }

    private fun renderParts() {
        partList.removeAllViews()
        if (parts.isEmpty()) {
            partList.addView(Ui.text(this, "No parts yet. Tap \"+ Add script\".", 13f, Ui.MUTED), Ui.spacedParams(this, 8))
            return
        }
        parts.forEachIndexed { index, part -> partList.addView(partCard(index, part), Ui.spacedParams(this, 10)) }
    }

    private fun partCard(index: Int, part: PartDraft): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.text(this, "${index + 1}. ${part.script.name}", 15f, bold = true))
        card.addView(Ui.text(this, "${Ui.count(part.script.steps.size, "step")} · ${Ui.duration(part.script.passDurationMs)} per run", 12f, Ui.MUTED))

        val count = Ui.text(this, "", 16f, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = Ui.dp(context, 48)
        }
        fun renderCount() {
            count.text = "${part.repeat}×"
        }
        renderCount()
        val stepper = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        stepper.addView(Ui.text(this, "Times", 13f), Ui.gapParams(this))
        stepper.addView(compact(Ui.button(this, "−") { if (part.repeat > 1) part.repeat--; renderCount() }), Ui.gapParams(this))
        stepper.addView(count, Ui.gapParams(this))
        stepper.addView(compact(Ui.button(this, "+") { part.repeat++; renderCount() }), Ui.gapParams(this))
        stepper.addView(compact(Ui.button(this, "Type") { editRepeat(part, ::renderCount) }), Ui.gapParams(this))
        card.addView(stepper, Ui.spacedParams(this, 8))

        card.addView(Ui.text(this, "Pause before this part (ms)", 12f, Ui.MUTED), Ui.spacedParams(this, 8))
        card.addView(Ui.input(this, part.delayBeforeMs.toString(), number = true) { part.delayBeforeMs = it.toLongOrNull() ?: 0 })

        val buttons = listOfNotNull(
            if (index > 0) Ui.button(this, "Move up") { move(index, index - 1) } else null,
            if (index < parts.lastIndex) Ui.button(this, "Move down") { move(index, index + 1) } else null,
            Ui.button(this, "Remove") { parts.removeAt(index); renderParts() },
        )
        card.addView(Ui.buttonRow(this, *buttons.toTypedArray()), Ui.spacedParams(this, 8))
        return card
    }

    /** Drops the framework's minimum button width so the stepper fits on narrow screens. */
    private fun compact(button: android.widget.Button) = button.apply {
        minWidth = 0
        minimumWidth = Ui.dp(context, 48)
    }

    private fun move(from: Int, to: Int) {
        parts.add(to, parts.removeAt(from))
        renderParts()
    }

    private fun editRepeat(part: PartDraft, onChanged: () -> Unit) {
        val input = Ui.input(this, part.repeat.toString(), number = true)
        AlertDialog.Builder(this)
            .setTitle("Run \"${part.script.name}\" how many times")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value == null || value < 1) toast("Enter 1 or more") else {
                    part.repeat = value
                    onChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickScript() {
        val scripts = store.list()
        if (scripts.isEmpty()) {
            toast("No scripts to add. Record or import one first.")
            return
        }
        val labels = scripts.map { "${it.script.name} (${Ui.count(it.script.steps.size, "step")})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add script")
            .setItems(labels) { _, which ->
                parts += PartDraft(scripts[which].script, repeat = 1, delayBeforeMs = 0)
                renderParts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        val repeat = if (forever) 0 else repeatText.toIntOrNull()
        val loopDelay = loopDelayText.toLongOrNull()
        when {
            name.isBlank() -> toast("Name cannot be empty")
            parts.isEmpty() -> toast("Add at least one script")
            repeat == null || repeat < 1 && !forever -> toast("Enter a repeat count of 1 or more, or tick Loop forever")
            loopDelay == null || loopDelay < 0 -> toast("Enter a pause of 0 ms or more")
            else -> {
                val sequence = ScriptSequence(
                    name = name.trim(),
                    parts = parts.map { SequencePart(it.script, it.repeat, it.delayBeforeMs.coerceAtLeast(0)) },
                    repeat = repeat,
                    loopDelayMs = loopDelay,
                    createdAt = createdAt ?: OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                )
                val id = sequenceId?.let { store.saveSequence(sequence, it) } ?: store.saveSequence(sequence)
                store.activeId = id
                toast("Saved. It is now the active script: PLAY on the panel runs it.")
                finish()
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ID = "dev.quickreplay.extra.SEQUENCE_ID"
    }
}
