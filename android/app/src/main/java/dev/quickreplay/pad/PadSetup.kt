package dev.quickreplay.pad

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything a `solve` step needs: board size, goals, search options and drag speed. A recording
 * copies the setup of the app settings when it starts and stores it in the script (`pad`), so
 * replaying always uses the setup the script was recorded with.
 */
data class PadSetup(
    val goals: List<Goal> = listOf(Goal.Shape("row", Orbs.code('L'))),
    val rows: Int = 5,
    val cols: Int = 6,
    val diagonals: Boolean = true,
    val maxMoves: Int = 40,
    val timeLimitMs: Long = 3000,
    /**
     * Drag time from one cell to the next; raise it on slow devices that miss cells. Default 150:
     * on the (software-rendered) emulator 100 ms missed a cell in 1 of 4 replays, 150 ms none in 12.
     */
    val msPerMove: Long = 150,
    /** When the goals cannot be met: false = fail the step (playback stops), true = drag the best path anyway. */
    val dragWhenUnmet: Boolean = false,
) {
    init {
        require(goals.isNotEmpty()) { "Add at least one goal" }
        Board.checkSize(rows, cols)
        require(maxMoves in 1..MAX_MOVES) { "Max moves must be from 1 to $MAX_MOVES" }
        require(timeLimitMs in MIN_TIME_LIMIT_MS..MAX_TIME_LIMIT_MS) { "Time limit must be from $MIN_TIME_LIMIT_MS to $MAX_TIME_LIMIT_MS ms" }
        require(msPerMove in MIN_MS_PER_MOVE..MAX_MS_PER_MOVE) { "Drag time per move must be from $MIN_MS_PER_MOVE to $MAX_MS_PER_MOVE ms" }
        for (goal in goals) if (goal is Goal.Shape) require(goal.color < Orbs.ATTRIBUTES) { "Goal colour must be R, B, G, L, D or H" }
    }

    /** Beam width is capped below the solver default: the widest rounds need more memory than a phone app gets. */
    val solveOptions: SolveOptions
        get() = SolveOptions(diagonals = diagonals, maxMoves = maxMoves, timeLimitMs = timeLimitMs, maxBeamWidth = MAX_BEAM_WIDTH)

    fun toJson(): JSONObject = JSONObject()
        .put("rows", rows)
        .put("cols", cols)
        .put("goals", JSONArray(goals.map(::goalJson)))
        .put("diagonals", diagonals)
        .put("maxMoves", maxMoves)
        .put("timeLimitMs", timeLimitMs)
        .put("msPerMove", msPerMove)
        .put("whenUnmet", if (dragWhenUnmet) "dragBest" else "fail")

    /** One line for lists, e.g. "Row of Light · 100 ms/move". */
    fun summary(): String = goals.joinToString(" + ") { it.label } + " · $msPerMove ms/move"

    companion object {
        const val MAX_MOVES = 50
        const val MAX_BEAM_WIDTH = 16_000
        const val MIN_TIME_LIMIT_MS = 100L
        const val MAX_TIME_LIMIT_MS = 10_000L
        const val MIN_MS_PER_MOVE = 10L
        // 50 moves x 1000 ms stays below GestureDescription.getMaxGestureDuration() (60 s).
        const val MAX_MS_PER_MOVE = 1000L

        /** Parses the `pad` object of a script. Throws [IllegalArgumentException] with a readable message. */
        fun fromJson(json: JSONObject): PadSetup {
            val goalsJson = json.optJSONArray("goals") ?: throw IllegalArgumentException("pad.goals must be an array")
            val goals = List(goalsJson.length()) { i ->
                val g = goalsJson.optJSONObject(i) ?: throw IllegalArgumentException("pad.goals[$i] must be an object")
                parseGoal(g, "pad.goals[$i]")
            }
            val whenUnmet = json.opt("whenUnmet") ?: "fail"
            require(whenUnmet == "fail" || whenUnmet == "dragBest") { "pad.whenUnmet must be \"fail\" or \"dragBest\"" }
            val diagonals = json.opt("diagonals") ?: true
            require(diagonals is Boolean) { "pad.diagonals must be a boolean" }
            return PadSetup(
                goals = goals,
                rows = smallInt(json, "rows", 5),
                cols = smallInt(json, "cols", 6),
                diagonals = diagonals,
                maxMoves = smallInt(json, "maxMoves", 40),
                timeLimitMs = integer(json, "timeLimitMs", 3000),
                msPerMove = integer(json, "msPerMove", 150),
                dragWhenUnmet = whenUnmet == "dragBest",
            )
        }

        /**
         * A whole number, or [default] when absent. Strings and fractions are rejected (org.json's
         * optInt would convert them), so Android accepts exactly what the browser validator and the
         * schema accept.
         */
        private fun integer(json: JSONObject, key: String, default: Long): Long {
            val value = json.opt(key) ?: return default
            val number = value as? Number
            require(number != null && number.toDouble() == Math.floor(number.toDouble()) && !number.toDouble().isInfinite()) {
                "pad.$key must be an integer"
            }
            return number.toLong()
        }

        /** [integer] that must fit an Int (no silent truncation of e.g. 2^32 + 5 to 5). */
        private fun smallInt(json: JSONObject, key: String, default: Int): Int {
            val value = integer(json, key, default.toLong())
            require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "pad.$key is out of range" }
            return value.toInt()
        }

        fun goalJson(goal: Goal): JSONObject = when (goal) {
            is Goal.Combos -> JSONObject().put("type", "combos").put("count", goal.count)
            is Goal.Shape -> JSONObject().put("type", goal.type).put("color", Orbs.LETTERS[goal.color].toString())
        }

        private fun parseGoal(json: JSONObject, at: String): Goal {
            val type = json.optString("type")
            if (type == "combos") {
                val count = json.opt("count") as? Number
                require(count != null && count.toDouble() == Math.floor(count.toDouble()) && count.toDouble() in 1.0..Int.MAX_VALUE.toDouble()) {
                    "$at.count must be an integer >= 1"
                }
                return Goal.Combos(count.toInt())
            }
            require(type in Shapes.TYPES) { "$at.type \"$type\" is not a goal type" }
            // Upper-case only, like the browser validator and the schema.
            val letter = json.opt("color") as? String
            val color = if (letter != null && letter.length == 1 && letter[0].isUpperCase()) Orbs.code(letter[0]) else -1
            require(color in 0 until Orbs.ATTRIBUTES) { "$at.color must be one of R, B, G, L, D, H" }
            return Goal.Shape(type, color)
        }
    }
}
