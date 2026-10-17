package dev.quickreplay.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.quickreplay.model.ScriptFormatException
import dev.quickreplay.model.ScriptJson
import dev.quickreplay.service.QuickReplayService
import dev.quickreplay.service.ReplayState
import dev.quickreplay.store.ScriptStore
import dev.quickreplay.store.StoredScript
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Script library: enable the service, import/export, choose the active script, edit repeat. */
class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var store: ScriptStore
    private lateinit var status: TextView
    private lateinit var serviceButton: android.widget.Button
    private lateinit var list: LinearLayout
    private var pendingExportId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScriptStore(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(context, 16)
            setPadding(pad, Ui.dp(context, 24), pad, pad)
            setBackgroundColor(Ui.BG)
        }
        content.addView(Ui.text(this, "Quick Replay", 24f, bold = true))
        content.addView(Ui.text(this, "Record and replay touches. No limits, open source.", 13f, Ui.MUTED))

        val serviceCard = Ui.card(this)
        status = Ui.text(this, "", 14f)
        serviceButton = Ui.button(this, "Open accessibility settings", primary = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        serviceCard.addView(status)
        serviceCard.addView(serviceButton, Ui.spacedParams(this, 8))
        content.addView(serviceCard, Ui.spacedParams(this, 16))

        val actions = Ui.row(this)
        actions.addView(Ui.button(this, "Import JSON") { openImport() }, Ui.gapParams(this))
        actions.addView(Ui.button(this, "Playground") {
            startActivity(Intent(this, PlaygroundActivity::class.java))
        }, Ui.gapParams(this))
        content.addView(actions, Ui.spacedParams(this))

        content.addView(Ui.text(this, "Scripts", 16f, bold = true), Ui.spacedParams(this, 20))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true // keep content clear of the status and navigation bars
            setBackgroundColor(Ui.BG)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        scope.launch { ReplayState.flow.collect { renderStatus(it); renderList() } }
    }

    override fun onResume() {
        super.onResume()
        renderStatus(ReplayState.flow.value)
        renderList()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun renderStatus(state: ReplayState) {
        val connected = QuickReplayService.instance != null
        status.text = when (state) {
            ReplayState.Disconnected -> "Service is off. Enable \"Quick Replay\" in Accessibility settings to show the floating panel."
            is ReplayState.Idle -> "Service is on. Use the floating panel: REC to record, PLAY to replay the active script." +
                (state.message?.let { "\nLast: $it" } ?: "")
            is ReplayState.Recording -> "Recording… ${state.steps} steps"
            is ReplayState.Playing -> "Playing \"${state.scriptName}\" step ${state.progress.step}/${state.progress.totalSteps}, loop ${state.progress.loop}"
        }
        serviceButton.text = if (connected) "Accessibility settings" else "Enable service"
    }

    private fun renderList() {
        list.removeAllViews()
        val scripts = store.list()
        if (scripts.isEmpty()) {
            list.addView(Ui.text(this, "No scripts yet. Record one with the floating panel or import a JSON file.", 13f, Ui.MUTED))
            return
        }
        val active = store.activeId
        for (item in scripts) list.addView(scriptCard(item, item.id == active), Ui.spacedParams(this, 10))
    }

    private fun scriptCard(item: StoredScript, isActive: Boolean): LinearLayout {
        val s = item.script
        val card = Ui.card(this)
        card.addView(Ui.text(this, (if (isActive) "● " else "") + s.name, 15f, if (isActive) Ui.OK else Ui.FG, bold = true))
        val loops = if (s.repeat == 0) "loop forever" else "repeat ${s.repeat}×"
        card.addView(Ui.text(this, "${s.steps.size} steps · $loops · ${s.screen.width}×${s.screen.height}", 12f, Ui.MUTED))
        val buttons = Ui.row(this)
        if (!isActive) buttons.addView(Ui.button(this, "Use") { store.activeId = item.id; renderList() }, Ui.gapParams(this))
        buttons.addView(Ui.button(this, "Play") { play(item) }, Ui.gapParams(this))
        buttons.addView(Ui.button(this, "Repeat") { editRepeat(item) }, Ui.gapParams(this))
        buttons.addView(Ui.button(this, "Export") { openExport(item) }, Ui.gapParams(this))
        buttons.addView(Ui.button(this, "Delete") { confirmDelete(item) }, Ui.gapParams(this))
        card.addView(android.widget.HorizontalScrollView(this).apply { addView(buttons) }, Ui.spacedParams(this, 8))
        return card
    }

    private fun play(item: StoredScript) {
        val service = QuickReplayService.instance
        if (service == null) {
            toast("Enable the accessibility service first")
            return
        }
        store.activeId = item.id
        service.play(item.script)
        moveTaskToBack(true) // let the script act on whatever is underneath
    }

    private fun editRepeat(item: StoredScript) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(item.script.repeat.toString())
        }
        AlertDialog.Builder(this)
            .setTitle("Repeat count (0 = forever)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value == null || value < 0) toast("Enter 0 or a positive number")
                else {
                    store.save(item.script.copy(repeat = value), item.id)
                    renderList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(item: StoredScript) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${item.script.name}\"?")
            .setPositiveButton("Delete") { _, _ -> store.delete(item.id); renderList() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openImport() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            REQUEST_IMPORT,
        )
    }

    private fun openExport(item: StoredScript) {
        pendingExportId = item.id
        val fileName = item.script.name.replace(Regex("[^A-Za-z0-9._ -]"), "_") + ".quickreplay.json"
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
            val id = store.save(ScriptJson.parse(text))
            store.activeId = id
            toast("Imported")
            renderList()
        } catch (e: ScriptFormatException) {
            toast("Invalid script: ${e.message}")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    private fun exportTo(uri: Uri) {
        val id = pendingExportId ?: return
        val script = store.load(id) ?: return
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(ScriptJson.toJson(script).toByteArray()) }
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
