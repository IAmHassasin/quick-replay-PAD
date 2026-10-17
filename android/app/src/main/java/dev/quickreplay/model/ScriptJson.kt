package dev.quickreplay.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ScriptFormatException(message: String) : Exception(message)

/** Reads and writes `quickreplay/v1` JSON for the Android platform. */
object ScriptJson {
    const val FORMAT = "quickreplay/v1"

    fun parse(text: String): Script {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw ScriptFormatException("Not valid JSON: ${e.message}")
        }
        if (root.optString("format") != FORMAT) throw ScriptFormatException("format must be \"$FORMAT\"")
        if (root.optString("platform") != "android") {
            throw ScriptFormatException("platform must be \"android\" (got \"${root.optString("platform")}\")")
        }
        val name = root.optString("name").ifBlank { throw ScriptFormatException("name is required") }
        val screenJson = root.optJSONObject("screen") ?: throw ScriptFormatException("screen is required")
        val screen = ScreenSize(
            positiveInt(screenJson, "width", "screen.width"),
            positiveInt(screenJson, "height", "screen.height"),
        )
        val stepsJson = root.optJSONArray("steps") ?: throw ScriptFormatException("steps must be an array")
        val steps = (0 until stepsJson.length()).map { i ->
            val step = stepsJson.optJSONObject(i) ?: throw ScriptFormatException("steps[$i] must be an object")
            parseStep(step, "steps[$i]")
        }
        val repeat = root.optInt("repeat", 1)
        if (repeat < 0) throw ScriptFormatException("repeat must be >= 0")
        val loopDelay = root.optLong("loopDelayMs", 0)
        if (loopDelay < 0) throw ScriptFormatException("loopDelayMs must be >= 0")
        return Script(
            name = name,
            screen = screen,
            steps = steps,
            repeat = repeat,
            loopDelayMs = loopDelay,
            createdAt = root.optString("createdAt").ifBlank { null },
        )
    }

    private fun parseStep(json: JSONObject, at: String): Step {
        val delay = json.optLong("delayBeforeMs", 0)
        if (delay < 0) throw ScriptFormatException("$at.delayBeforeMs must be >= 0")
        return when (val type = json.optString("type")) {
            "tap" -> Step.Tap(
                number(json, "x", at), number(json, "y", at),
                json.optLong("durationMs", Step.DEFAULT_TAP_MS).coerceAtLeast(1), delay,
            )
            "longPress" -> Step.LongPress(
                number(json, "x", at), number(json, "y", at),
                positiveLong(json, "durationMs", at), delay,
            )
            "swipe" -> {
                val array = json.optJSONArray("points") ?: throw ScriptFormatException("$at.points is required")
                if (array.length() < 2) throw ScriptFormatException("$at.points needs at least 2 points")
                val points = (0 until array.length()).map { i ->
                    val p = array.optJSONObject(i) ?: throw ScriptFormatException("$at.points[$i] must be an object")
                    StrokePoint(number(p, "x", "$at.points[$i]"), number(p, "y", "$at.points[$i]"), p.optLong("t", 0))
                }
                if (points.zipWithNext().any { (a, b) -> b.t < a.t }) {
                    throw ScriptFormatException("$at.points must have non-decreasing t")
                }
                Step.Swipe(points, delay)
            }
            "wait" -> Step.Wait(json.optLong("ms", -1).also {
                if (it < 0) throw ScriptFormatException("$at.ms must be >= 0")
            }, delay)
            else -> throw ScriptFormatException("$at.type \"$type\" is not an android step")
        }
    }

    fun toJson(script: Script): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("name", script.name)
            .put("platform", "android")
            .put("repeat", script.repeat)
            .put("loopDelayMs", script.loopDelayMs)
            .put("screen", JSONObject().put("width", script.screen.width).put("height", script.screen.height))
        script.createdAt?.let { root.put("createdAt", it) }
        val steps = JSONArray()
        for (step in script.steps) {
            val json = JSONObject()
            when (step) {
                is Step.Tap -> json.put("type", "tap").put("x", step.x.round()).put("y", step.y.round())
                    .apply { if (step.durationMs != Step.DEFAULT_TAP_MS) put("durationMs", step.durationMs) }
                is Step.LongPress -> json.put("type", "longPress").put("x", step.x.round()).put("y", step.y.round())
                    .put("durationMs", step.durationMs)
                is Step.Swipe -> json.put("type", "swipe").put(
                    "points",
                    JSONArray(step.points.map { JSONObject().put("x", it.x.round()).put("y", it.y.round()).put("t", it.t) }),
                )
                is Step.Wait -> json.put("type", "wait").put("ms", step.ms)
            }
            if (step.delayBeforeMs > 0) json.put("delayBeforeMs", step.delayBeforeMs)
            steps.put(json)
        }
        root.put("steps", steps)
        return root.toString(2)
    }

    private fun Float.round(): Double = Math.round(this * 10.0) / 10.0

    private fun number(json: JSONObject, key: String, at: String): Float {
        if (!json.has(key)) throw ScriptFormatException("$at.$key is required")
        val value = json.optDouble(key, Double.NaN)
        if (value.isNaN()) throw ScriptFormatException("$at.$key must be a number")
        return value.toFloat()
    }

    private fun positiveInt(json: JSONObject, key: String, label: String): Int {
        val value = json.optInt(key, 0)
        if (value <= 0) throw ScriptFormatException("$label must be a positive integer")
        return value
    }

    private fun positiveLong(json: JSONObject, key: String, at: String): Long {
        val value = json.optLong(key, 0)
        if (value <= 0) throw ScriptFormatException("$at.$key must be > 0")
        return value
    }
}
