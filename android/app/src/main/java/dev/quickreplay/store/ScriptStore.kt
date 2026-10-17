package dev.quickreplay.store

import android.content.Context
import dev.quickreplay.model.Script
import dev.quickreplay.model.ScriptJson
import java.io.File
import java.util.UUID

data class StoredScript(val id: String, val script: Script)

/** Scripts are plain JSON files in app-private storage; there is no size or count limit. */
class ScriptStore(context: Context) {
    private val dir = File(context.filesDir, "scripts").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("quickreplay", Context.MODE_PRIVATE)

    fun list(): List<StoredScript> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file -> runCatching { StoredScript(file.nameWithoutExtension, ScriptJson.parse(file.readText())) }.getOrNull() }

    fun load(id: String): Script? = file(id).takeIf { it.exists() }?.let { ScriptJson.parse(it.readText()) }

    fun save(script: Script, id: String = UUID.randomUUID().toString()): String {
        val target = file(id)
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(ScriptJson.toJson(script))
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not save script" }
        }
        return id
    }

    fun delete(id: String) {
        file(id).delete()
        if (activeId == id) activeId = null
    }

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE, null)?.takeIf { file(it).exists() }
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    private fun file(id: String): File {
        require(ID.matches(id)) { "Invalid script id" }
        return File(dir, "$id.json")
    }

    private companion object {
        const val KEY_ACTIVE = "active_script"
        val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
