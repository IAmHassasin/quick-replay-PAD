package dev.quickreplay.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.quickreplay.model.ScriptFormatException
import dev.quickreplay.model.ScriptJson
import dev.quickreplay.model.SequenceJson
import dev.quickreplay.service.QuickReplayService
import dev.quickreplay.service.ReplayState
import dev.quickreplay.store.AppSettings
import dev.quickreplay.store.ScriptStore
import dev.quickreplay.store.StoredScript
import dev.quickreplay.store.StoredSequence
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Script library: enable the service, settings, import/export, choose the active script or
 * combined script, edit repeat settings, build combined scripts and open the guide.
 */
class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var store: ScriptStore
    private lateinit var settings: AppSettings
    private lateinit var status: TextView
    private lateinit var serviceButton: android.widget.Button
    private lateinit var defaultRepeat: TextView
    private lateinit var panelSwitch: Switch
    private lateinit var touchesSwitch: Switch
    private lateinit var list: LinearLayout
    private lateinit var sequenceList: LinearLayout
    private var pendingExport: PendingExport? = null
    private var resumed = false

    /** What the export file picker was opened for. */
    private data class PendingExport(val id: String, val sequence: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScriptStore(this)
        settings = AppSettings(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(context, 16)
            setPadding(pad, Ui.dp(context, 24), pad, pad)
            setBackgroundColor(Ui.BG)
        }
        content.addView(Ui.text(this, "Quick Replay", 24f, bold = true))
        content.addView(Ui.text(this, "Record and replay touches. No limits, open source.", 13f, Ui.MUTED))

        content.addView(
            Ui.buttonRow(
                this,
                Ui.button(this, "How to use", primary = true) { startActivity(Intent(this, GuideActivity::class.java)) },
                Ui.button(this, "Import JSON") { openImport() },
                Ui.button(this, "Playground") { startActivity(Intent(this, PlaygroundActivity::class.java)) },
            ),
            Ui.spacedParams(this),
        )

        val serviceCard = Ui.card(this)
        status = Ui.text(this, "", 14f)
        serviceButton = Ui.button(this, "Open accessibility settings", primary = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        serviceCard.addView(status)
        serviceCard.addView(serviceButton, Ui.spacedParams(this, 8))
        content.addView(serviceCard, Ui.spacedParams(this, 16))

        content.addView(settingsCard(), Ui.spacedParams(this, 12))

        content.addView(Ui.text(this, "Scripts", 16f, bold = true), Ui.spacedParams(this, 20))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        content.addView(Ui.text(this, "Combined scripts", 16f, bold = true), Ui.spacedParams(this, 24))
        content.addView(
            Ui.text(this, "Chain recorded scripts, e.g. A once, then B 3 times, then C twice, and play them as one.", 12f, Ui.MUTED),
        )
        content.addView(Ui.buttonRow(this, Ui.button(this, "New combined script") { openSequenceEditor(null) }), Ui.spacedParams(this, 8))
        sequenceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sequenceList)

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true // keep content clear of the status and navigation bars
            setBackgroundColor(Ui.BG)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        scope.launch { ReplayState.flow.collect(::renderStatus) }
        // Playback and recording emit a state per step; the list (which parses every file) is only
        // rebuilt when they start or end, or on any other state change, and only while visible.
        scope.launch {
            ReplayState.flow
                .distinctUntilChanged { old, new -> old::class == new::class && (new is ReplayState.Playing || new is ReplayState.Recording) }
                .collect { if (resumed) renderList() }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Settings can change elsewhere (e.g. adb debug commands); show the stored values.
        panelSwitch.isChecked = settings.showPanel
        touchesSwitch.isChecked = settings.showPlaybackTouches
        renderDefaultRepeat()
        renderStatus(ReplayState.flow.value)
        renderList()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun settingsCard(): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.text(this, "Settings", 15f, bold = true))
        defaultRepeat = Ui.text(this, "", 13f)
        card.addView(defaultRepeat, Ui.spacedParams(this, 8))
        card.addView(Ui.buttonRow(this, Ui.button(this, "Change") { editDefaultRepeat() }), Ui.spacedParams(this, 6))
        card.addView(
            Switch(this).also { panelSwitch = it }.apply {
                text = "Show floating panel"
                setTextColor(Ui.FG)
                isChecked = settings.showPanel
                setOnCheckedChangeListener { _, checked ->
                    settings.showPanel = checked
                    QuickReplayService.instance?.updatePanelVisibility()
                    renderStatus(ReplayState.flow.value)
                }
            },
            Ui.spacedParams(this, 10),
        )
        card.addView(
            Ui.text(this, "When off, the panel still appears while a script plays, so you can pause or stop it.", 12f, Ui.MUTED),
        )
        card.addView(
            Switch(this).also { touchesSwitch = it }.apply {
                text = "Show touches while replaying"
                setTextColor(Ui.FG)
                isChecked = settings.showPlaybackTouches
                setOnCheckedChangeListener { _, checked -> settings.showPlaybackTouches = checked }
            },
            Ui.spacedParams(this, 10),
        )
        card.addView(
            Ui.text(this, "Each script has its own repeat count: tap Settings on a script below.", 12f, Ui.MUTED),
            Ui.spacedParams(this, 6),
        )
        renderDefaultRepeat()
        return card
    }

    private fun renderDefaultRepeat() {
        defaultRepeat.text = "New recordings: ${Ui.repeatText(settings.defaultRepeat)}"
    }

    private fun renderStatus(state: ReplayState) {
        val connected = QuickReplayService.instance != null
        status.text = when (state) {
            ReplayState.Disconnected -> "Service is off. Enable \"Quick Replay\" in Accessibility settings to show the floating panel."
            is ReplayState.Idle -> (
                if (settings.showPanel) {
                    "Service is on. Use the floating panel: REC to record, PLAY to replay the active script, ☰ for more."
                } else {
                    "Service is on. The floating panel is hidden (Settings → Show floating panel)."
                }
                ) + (state.message?.let { "\nLast: $it" } ?: "")
            is ReplayState.Recording -> "Recording… ${state.steps} steps"
            is ReplayState.Playing -> (if (state.paused) "Paused: " else "Playing ") +
                "\"${state.scriptName}\" step ${state.progress.step}/${state.progress.totalSteps}, loop ${state.progress.loop}" +
                (state.partName?.let { "\nPart ${state.progress.part}/${state.progress.partCount}: $it (${state.progress.partLoop}/${state.progress.partRepeat})" } ?: "")
        }
        serviceButton.text = if (connected) "Accessibility settings" else "Enable service"
    }

    private fun renderList() {
        val active = store.activeId
        list.removeAllViews()
        val scripts = store.list()
        if (scripts.isEmpty()) {
            list.addView(
                Ui.text(this, "No scripts yet. Record one with the floating panel or import a JSON file. New here? Tap \"How to use\".", 13f, Ui.MUTED),
                Ui.spacedParams(this, 8),
            )
        }
        for (item in scripts) list.addView(scriptCard(item, item.id == active), Ui.spacedParams(this, 10))

        sequenceList.removeAllViews()
        for (item in store.listSequences()) sequenceList.addView(sequenceCard(item, item.id == active), Ui.spacedParams(this, 10))
    }

    private fun scriptCard(item: StoredScript, isActive: Boolean): LinearLayout {
        val s = item.script
        val card = Ui.card(this)
        card.addView(Ui.text(this, (if (isActive) "● " else "") + s.name, 15f, if (isActive) Ui.OK else Ui.FG, bold = true))
        val delay = if (s.loopDelayMs > 0) " · ${Ui.duration(s.loopDelayMs)} between loops" else ""
        card.addView(
            Ui.text(this, "${Ui.count(s.steps.size, "step")} · ${Ui.duration(s.passDurationMs)} · ${Ui.repeatText(s.repeat)}$delay · ${s.screen.width}×${s.screen.height}", 12f, Ui.MUTED),
        )
        val buttons = listOfNotNull(
            if (!isActive) Ui.button(this, "Use") { store.activeId = item.id; renderList() } else null,
            Ui.button(this, "Play") { play(item) },
            Ui.button(this, "Rename") { rename(item.id, s.name) },
            Ui.button(this, "Settings") { editScript(item) },
            Ui.button(this, "Export") { openExport(item.id, s.name, sequence = false) },
            Ui.button(this, "Delete") { confirmDelete(s.name) { store.delete(item.id) } },
        )
        card.addView(Ui.buttonRow(this, *buttons.toTypedArray()), Ui.spacedParams(this, 8))
        return card
    }

    private fun sequenceCard(item: StoredSequence, isActive: Boolean): LinearLayout {
        val seq = item.sequence
        val card = Ui.card(this)
        card.addView(Ui.text(this, (if (isActive) "● " else "") + seq.name, 15f, if (isActive) Ui.OK else Ui.FG, bold = true))
        val parts = seq.parts.joinToString(" → ") { "${it.script.name} ×${it.repeat}" }
        card.addView(Ui.text(this, parts, 13f))
        val steps = seq.parts.sumOf { it.script.steps.size.toLong() * it.repeat }
        val time = seq.parts.sumOf { it.delayBeforeMs + it.script.passDurationMs * it.repeat + it.script.loopDelayMs * (it.repeat - 1) }
        card.addView(Ui.text(this, "${Ui.count(seq.parts.size, "part")} · ${Ui.count(steps, "step")} · ${Ui.duration(time)} per loop · ${Ui.repeatText(seq.repeat)}", 12f, Ui.MUTED))
        val buttons = listOfNotNull(
            if (!isActive) Ui.button(this, "Use") { store.activeId = item.id; renderList() } else null,
            Ui.button(this, "Play") { play(item) },
            Ui.button(this, "Rename") { rename(item.id, seq.name) },
            Ui.button(this, "Edit") { openSequenceEditor(item.id) },
            Ui.button(this, "Export") { openExport(item.id, seq.name, sequence = true) },
            Ui.button(this, "Delete") { confirmDelete(seq.name) { store.deleteSequence(item.id) } },
        )
        card.addView(Ui.buttonRow(this, *buttons.toTypedArray()), Ui.spacedParams(this, 8))
        return card
    }

    private fun play(item: StoredScript) {
        val service = requireService() ?: return
        store.activeId = item.id
        service.play(item.script)
        moveTaskToBack(true) // let the script act on whatever is underneath
    }

    private fun play(item: StoredSequence) {
        val service = requireService() ?: return
        store.activeId = item.id
        service.play(item.sequence)
        moveTaskToBack(true)
    }

    private fun requireService(): QuickReplayService? =
        QuickReplayService.instance ?: run {
            toast("Enable the accessibility service first")
            null
        }

    private fun openSequenceEditor(id: String?) {
        if (id == null && store.list().isEmpty()) {
            toast("Record or import at least one script first")
            return
        }
        startActivity(Intent(this, SequenceEditorActivity::class.java).apply { id?.let { putExtra(SequenceEditorActivity.EXTRA_ID, it) } })
    }

    /** Name, repeat count (or forever) and pause between loops of one script. */
    private fun editScript(item: StoredScript) {
        val s = item.script
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(context, 20)
            setPadding(pad, Ui.dp(context, 8), pad, 0)
        }
        val name = Ui.input(this, s.name)
        val repeat = Ui.input(this, if (s.repeat == 0) "1" else s.repeat.toString(), number = true)
        val forever = CheckBox(this).apply {
            text = "Loop forever (until STOP)"
            setTextColor(Ui.FG)
            isChecked = s.repeat == 0
            repeat.isEnabled = !isChecked
            setOnCheckedChangeListener { _, checked -> repeat.isEnabled = !checked }
        }
        val loopDelay = Ui.input(this, s.loopDelayMs.toString(), number = true)
        form.addView(Ui.text(this, "Name", 12f, Ui.MUTED))
        form.addView(name)
        form.addView(Ui.text(this, "Play how many times", 12f, Ui.MUTED), Ui.spacedParams(this, 10))
        form.addView(repeat)
        form.addView(forever)
        form.addView(Ui.text(this, "Pause between loops (ms)", 12f, Ui.MUTED), Ui.spacedParams(this, 10))
        form.addView(loopDelay)
        AlertDialog.Builder(this)
            .setTitle("Script settings")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                val count = if (forever.isChecked) 0 else repeat.text.toString().toIntOrNull()
                val delay = loopDelay.text.toString().toLongOrNull()
                val newName = name.text.toString().trim()
                when {
                    newName.isEmpty() -> toast("Name cannot be empty")
                    count == null || count < 1 && !forever.isChecked -> toast("Enter a repeat count of 1 or more, or tick Loop forever")
                    delay == null || delay < 0 -> toast("Enter a pause of 0 ms or more")
                    else -> {
                        store.save(s.copy(name = newName, repeat = count, loopDelayMs = delay), item.id)
                        renderList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Renames a script or combined script. */
    private fun rename(id: String, current: String) {
        // The old name is selected, so typing replaces it.
        val input = Ui.input(this, current).apply { setSelectAllOnFocus(true) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(LinearLayout(this).apply {
                val pad = Ui.dp(context, 20)
                setPadding(pad, Ui.dp(context, 8), pad, 0)
                addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Name cannot be empty")
                    return@setPositiveButton
                }
                try {
                    if (store.rename(id, name)) renderList() else toast("The script no longer exists")
                } catch (e: ScriptFormatException) {
                    toast("Cannot read the script: ${e.message}")
                } catch (e: Exception) {
                    toast("Rename failed: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        // Focus the field and open the keyboard, so the selected old name is replaced by typing.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.setOnShowListener {
            input.requestFocus()
            input.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        }
        dialog.show()
    }

    private fun editDefaultRepeat() {
        val input = Ui.input(this, settings.defaultRepeat.toString(), number = true)
        AlertDialog.Builder(this)
            .setTitle("Repeat count for new recordings (0 = forever)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value == null || value < 0) {
                    toast("Enter 0 or a positive number")
                } else {
                    settings.defaultRepeat = value
                    renderDefaultRepeat()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(name: String, delete: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"$name\"?")
            .setPositiveButton("Delete") { _, _ -> delete(); renderList() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openImport() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            REQUEST_IMPORT,
        )
    }

    private fun openExport(id: String, name: String, sequence: Boolean) {
        pendingExport = PendingExport(id, sequence)
        val suffix = if (sequence) ".quickreplay-combo.json" else ".quickreplay.json"
        val fileName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_") + suffix
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, fileName),
            REQUEST_EXPORT,
        )
    }

    @Deprecated("Framework Activity API; this app has no AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_IMPORT -> importFrom(uri)
            REQUEST_EXPORT -> exportTo(uri)
        }
    }

    private fun importFrom(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                require(bytes.size <= MAX_IMPORT_BYTES) { "File is larger than 50 MB" }
                bytes.toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("Cannot open file")
            store.activeId = store.import(text)
            toast("Imported")
            renderList()
        } catch (e: ScriptFormatException) {
            toast("Invalid script: ${e.message}")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    private fun exportTo(uri: Uri) {
        val pending = pendingExport ?: return
        try {
            val json = if (pending.sequence) {
                store.loadSequence(pending.id)?.let(SequenceJson::toJson)
            } else {
                store.load(pending.id)?.let(ScriptJson::toJson)
            } ?: throw IllegalStateException("Script no longer exists")
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            toast("Exported")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQUEST_IMPORT = 1
        const val REQUEST_EXPORT = 2
        const val MAX_IMPORT_BYTES = 50 * 1024 * 1024
    }
}
