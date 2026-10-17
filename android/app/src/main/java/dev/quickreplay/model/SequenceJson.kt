package dev.quickreplay.model

import org.json.JSONArray
import org.json.JSONObject

/** Reads and writes combined scripts (`quickreplay-sequence/v1`, see docs/script-format.md). */
object SequenceJson {
    const val FORMAT = "quickreplay-sequence/v1"

    /**
     * True if [text] is a sequence rather than a plain script. Parses the whole text; callers that
     * go on to load the file should parse once and use the JSONObject overload.
     */
    fun isSequence(text: String): Boolean = try {
        isSequence(ScriptJson.parseObject(text))
    } catch (e: ScriptFormatException) {
        false
    }

    internal fun isSequence(root: JSONObject): Boolean = root.optString("format") == FORMAT

    fun parse(text: String): ScriptSequence = fromJson(ScriptJson.parseObject(text))

    internal fun fromJson(root: JSONObject): ScriptSequence {
        if (root.optString("format") != FORMAT) throw ScriptFormatException("format must be \"$FORMAT\"")
        if (root.optString("platform") != "android") {
            throw ScriptFormatException("platform must be \"android\" (got \"${root.optString("platform")}\")")
        }
        val name = root.optString("name").ifBlank { throw ScriptFormatException("name is required") }
        val partsJson = root.optJSONArray("parts") ?: throw ScriptFormatException("parts must be an array")
        val parts = (0 until partsJson.length()).map { i ->
            val at = "parts[$i]"
            val part = partsJson.optJSONObject(i) ?: throw ScriptFormatException("$at must be an object")
            val repeat = part.optInt("repeat", 1)
            if (repeat < 1) throw ScriptFormatException("$at.repeat must be >= 1")
            val delay = part.optLong("delayBeforeMs", 0)
            if (delay < 0) throw ScriptFormatException("$at.delayBeforeMs must be >= 0")
            val scriptJson = part.optJSONObject("script") ?: throw ScriptFormatException("$at.script is required")
            val script = try {
                ScriptJson.fromJson(scriptJson)
            } catch (e: ScriptFormatException) {
                throw ScriptFormatException("$at.script: ${e.message}")
            }
            SequencePart(script, repeat, delay)
        }
        val repeat = root.optInt("repeat", 1)
        if (repeat < 0) throw ScriptFormatException("repeat must be >= 0")
        val loopDelay = root.optLong("loopDelayMs", 0)
        if (loopDelay < 0) throw ScriptFormatException("loopDelayMs must be >= 0")
        return ScriptSequence(
            name = name,
            parts = parts,
            repeat = repeat,
            loopDelayMs = loopDelay,
            createdAt = root.optString("createdAt").ifBlank { null },
        )
    }

    fun toJson(sequence: ScriptSequence): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("name", sequence.name)
            .put("platform", "android")
            .put("repeat", sequence.repeat)
            .put("loopDelayMs", sequence.loopDelayMs)
        sequence.createdAt?.let { root.put("createdAt", it) }
        val parts = JSONArray()
        for (part in sequence.parts) {
            val json = JSONObject().put("repeat", part.repeat)
            if (part.delayBeforeMs > 0) json.put("delayBeforeMs", part.delayBeforeMs)
            parts.put(json.put("script", ScriptJson.toJsonObject(part.script)))
        }
        root.put("parts", parts)
        return root.toString(2)
    }
}
