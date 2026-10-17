package dev.quickreplay.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.quickreplay.model.ScriptJson
import dev.quickreplay.model.SequenceJson
import dev.quickreplay.service.QuickReplayService
import dev.quickreplay.store.AppSettings
import dev.quickreplay.store.ScriptStore
import java.io.File

/**
 * Debug-build-only commands, e.g.
 * `adb shell am broadcast -n dev.quickreplay/.debug.DebugCommandReceiver -a dev.quickreplay.debug.PLAY`
 *
 * Actions: IMPORT (extra `file`: script or combined script inside the app's external `import/`
 * folder), PLAY (active script or combined script), STOP, PAUSE, RESUME,
 * RECORD_START, RECORD_STOP, SETTINGS (extras `show_touches`, `show_panel` boolean, `default_repeat` int),
 * DUMP (logs the active script).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = ScriptStore(context)
        val service = QuickReplayService.instance
        val result = runCatching {
            when (intent.action?.substringAfterLast('.')) {
                "IMPORT" -> {
                    val name = intent.getStringExtra("file") ?: error("extra 'file' is required")
                    require(!name.contains('/') && !name.contains("..")) { "file must be a plain name" }
                    val dir = context.getExternalFilesDir("import") ?: error("external storage unavailable")
                    val id = store.import(File(dir, name).readText())
                    store.activeId = id
                    "imported $name as $id"
                }
                "PLAY" -> if (service?.play() == true) "playing" else "not playing (service off or no script)"
                "STOP" -> service?.stopPlayback().let { "stopped" }
                "PAUSE", "RESUME" -> service?.let {
                    if (it.isPaused != (intent.action?.endsWith("PAUSE") == true)) it.togglePause()
                    if (it.isPaused) "paused" else "running"
                } ?: "service off"
                "RECORD_START" -> service?.startRecording()?.let { "recording" } ?: "service off"
                "RECORD_STOP" -> service?.stopRecording(save = true)?.let { "saved $it" } ?: "nothing saved"
                "SETTINGS" -> {
                    val settings = AppSettings(context)
                    if (intent.hasExtra("show_touches")) settings.showPlaybackTouches = intent.getBooleanExtra("show_touches", true)
                    if (intent.hasExtra("default_repeat")) settings.defaultRepeat = intent.getIntExtra("default_repeat", 1)
                    if (intent.hasExtra("show_panel")) {
                        settings.showPanel = intent.getBooleanExtra("show_panel", true)
                        service?.updatePanelVisibility()
                    }
                    "show_touches=${settings.showPlaybackTouches} default_repeat=${settings.defaultRepeat} show_panel=${settings.showPanel}"
                }
                "DUMP" -> store.activeId?.let { id ->
                    store.load(id)?.let(ScriptJson::toJson) ?: store.loadSequence(id)?.let(SequenceJson::toJson)
                } ?: "no active script"
                else -> "unknown action ${intent.action}"
            }
        }
        val message = result.getOrElse { "error: ${it.message}" }
        Log.i(TAG, message)
        resultData = message
    }

    private companion object {
        const val TAG = "QuickReplayDebug"
    }
}
