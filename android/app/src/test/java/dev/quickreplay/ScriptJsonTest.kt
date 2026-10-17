package dev.quickreplay

import dev.quickreplay.model.ScreenSize
import dev.quickreplay.model.Script
import dev.quickreplay.model.ScriptFormatException
import dev.quickreplay.model.ScriptJson
import dev.quickreplay.model.Step
import dev.quickreplay.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScriptJsonTest {

    private val sample = Script(
        name = "Sample",
        screen = ScreenSize(1080, 2400),
        repeat = 0,
        loopDelayMs = 250,
        steps = listOf(
            Step.Tap(540f, 1200f),
            Step.LongPress(100f, 200f, durationMs = 800, delayBeforeMs = 300),
            Step.Swipe(listOf(StrokePoint(500f, 1800f, 0), StrokePoint(500f, 600f, 300)), delayBeforeMs = 100),
            Step.Wait(1000),
        ),
    )

    @Test
    fun roundTripKeepsEverything() {
        assertEquals(sample, ScriptJson.parse(ScriptJson.toJson(sample)))
    }

    @Test
    fun parsesRepositoryExamples() {
        val examples = File("../../examples").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .filter { it.readText().contains("\"android\"") }
        assertTrue("expected android examples in /examples", examples.isNotEmpty())
        examples.forEach { ScriptJson.parse(it.readText()) }
    }

    @Test
    fun handlesLargeScripts() {
        val many = sample.copy(steps = List(100_000) { Step.Tap(it % 1000f, it % 2000f, delayBeforeMs = 1) })
        assertEquals(100_000, ScriptJson.parse(ScriptJson.toJson(many)).steps.size)
    }

    @Test
    fun rejectsInvalidScripts() {
        val cases = mapOf(
            "not json" to "Not valid JSON",
            """{"format":"x","name":"a","platform":"android","screen":{"width":1,"height":1},"steps":[]}""" to "format",
            """{"format":"quickreplay/v1","name":"a","platform":"browser","steps":[]}""" to "platform",
            """{"format":"quickreplay/v1","name":"a","platform":"android","steps":[]}""" to "screen",
            """{"format":"quickreplay/v1","name":"a","platform":"android","screen":{"width":1,"height":1},"steps":[{"type":"click","x":1,"y":1}]}""" to "not an android step",
            """{"format":"quickreplay/v1","name":"a","platform":"android","screen":{"width":1,"height":1},"steps":[{"type":"tap","x":1}]}""" to "steps[0].y",
            """{"format":"quickreplay/v1","name":"a","platform":"android","screen":{"width":1,"height":1},"repeat":-1,"steps":[]}""" to "repeat",
            """{"format":"quickreplay/v1","name":"a","platform":"android","screen":{"width":1,"height":1},"steps":[{"type":"swipe","points":[{"x":1,"y":1,"t":5},{"x":2,"y":2,"t":1}]}]}""" to "non-decreasing",
        )
        for ((json, message) in cases) {
            val error = assertThrows(ScriptFormatException::class.java) { ScriptJson.parse(json) }
            assertTrue("'${error.message}' should mention '$message'", error.message!!.contains(message))
        }
    }

    @Test
    fun scalesToAnotherScreen() {
        val scaled = sample.scaledTo(ScreenSize(540, 1200))
        assertEquals(Step.Tap(270f, 600f), scaled.steps[0])
        assertEquals(StrokePoint(250f, 300f, 300), (scaled.steps[2] as Step.Swipe).points[1])
        assertEquals(sample.steps[3], scaled.steps[3])
    }
}
