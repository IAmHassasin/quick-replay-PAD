package dev.quickreplay.store

import android.content.Context
import dev.quickreplay.model.Script
import dev.quickreplay.model.ScriptJson
import dev.quickreplay.model.ScriptSequence
import dev.quickreplay.model.SequenceJson
import java.io.File
import java.util.UUID

data class StoredScript(val id: String, val script: Script)

data class StoredSequence(val id: String, val sequence: ScriptSequence)

/** A script or combined script as shown in a list. */
data class LibraryEntry(val id: String, val name: String, val sequence: Boolean, val detail: String)

/**
 * Scripts and combined scripts (sequences) are plain JSON files in app-private storage; there is
 * no size or count limit. Ids are random UUIDs, unique across both kinds, so [activeId] can point
 * to either.
 */
class ScriptStore(context: Context) {
    private val dir = File(context.filesDir, "scripts").apply { mkdirs() }
    private val sequenceDir = File(context.filesDir, "sequences").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("quickreplay", Context.MODE_PRIVATE)

    fun list(): List<StoredScript> =
        jsonFiles(dir).mapNotNull { file -> runCatching { StoredScript(file.nameWithoutExtension, ScriptJson.parse(file.readText())) }.getOrNull() }

    fun load(id: String): Script? = file(dir, id).takeIf { it.exists() }?.let { ScriptJson.parse(it.readText()) }

    fun save(script: Script, id: String = newId()): String = write(dir, id, ScriptJson.toJson(script))

    fun delete(id: String) {
        file(dir, id).delete()
        if (activeId == id) activeId = null
    }

    fun listSequences(): List<StoredSequence> =
        jsonFiles(sequenceDir).mapNotNull { file ->
            runCatching { StoredSequence(file.nameWithoutExtension, SequenceJson.parse(file.readText())) }.getOrNull()
        }

    fun loadSequence(id: String): ScriptSequence? =
        file(sequenceDir, id).takeIf { it.exists() }?.let { SequenceJson.parse(it.readText()) }

    fun saveSequence(sequence: ScriptSequence, id: String = newId()): String =
        write(sequenceDir, id, SequenceJson.toJson(sequence))

    fun deleteSequence(id: String) {
        file(sequenceDir, id).delete()
        if (activeId == id) activeId = null
    }

    /** Saves a script or sequence file and returns its new id. Throws ScriptFormatException. */
    fun import(text: String): String {
        val root = ScriptJson.parseObject(text) // parsed once, then dispatched on "format"
        return if (SequenceJson.isSequence(root)) saveSequence(SequenceJson.fromJson(root)) else save(ScriptJson.fromJson(root))
    }

    /** Scripts first, then combined scripts, for pickers such as the floating panel's menu. */
    fun entries(): List<LibraryEntry> =
        list().map { LibraryEntry(it.id, it.script.name, sequence = false, count(it.script.steps.size, "step")) } +
            listSequences().map { LibraryEntry(it.id, it.sequence.name, sequence = true, "combined · " + count(it.sequence.parts.size, "part")) }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** Name of the script or combined script [id], or null if it does not exist. */
    fun nameOf(id: String): String? = load(id)?.name ?: loadSequence(id)?.name

    /** Renames a script or combined script. Returns false if [id] does not exist. */
    fun rename(id: String, name: String): Boolean {
        require(name.isNotBlank()) { "Name cannot be empty" }
        load(id)?.let { save(it.copy(name = name.trim()), id); return true }
        loadSequence(id)?.let { saveSequence(it.copy(name = name.trim()), id); return true }
        return false
    }

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE, null)?.takeIf { file(dir, it).exists() || file(sequenceDir, it).exists() }
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    private fun jsonFiles(folder: File): List<File> =
        folder.listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }

    private fun write(folder: File, id: String, text: String): String {
        val target = file(folder, id)
        val tmp = File(folder, "$id.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not save script" }
        }
        return id
    }

    private fun file(folder: File, id: String): File {
        require(ID.matches(id)) { "Invalid script id" }
        return File(folder, "$id.json")
    }

    private fun newId() = UUID.randomUUID().toString()

    private companion object {
        const val KEY_ACTIVE = "active_script"
        val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

/** User preferences shown in the app's Settings card. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("quickreplay", Context.MODE_PRIVATE)

    /** Repeat count given to new recordings (0 = loop forever). */
    var defaultRepeat: Int
        get() = prefs.getInt(KEY_DEFAULT_REPEAT, 1).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_REPEAT, value.coerceAtLeast(0)).apply()

    /**
     * Show the floating panel while idle. When off, the panel still appears while a script is
     * recording or playing, so STOP and PAUSE stay reachable.
     */
    var showPanel: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PANEL, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_PANEL, value).apply()

    /** Draw each replayed gesture on screen (taps, long presses, swipes). */
    var showPlaybackTouches: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PLAYBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_PLAYBACK, value).apply()

    private companion object {
        const val KEY_DEFAULT_REPEAT = "default_repeat"
        const val KEY_SHOW_PLAYBACK = "show_playback_touches"
        const val KEY_SHOW_PANEL = "show_panel"
    }
}
