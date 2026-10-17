package dev.quickreplay.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.quickreplay.model.ScriptJson
import dev.quickreplay.service.QuickReplayService
import dev.quickreplay.store.ScriptStore
import java.io.File

/**
 * Debug-build-only commands, e.g.
 * `adb shell am broadcast -n dev.quickreplay/.debug.DebugCommandReceiver -a dev.quickreplay.debug.PLAY`
 *
 * Actions: IMPORT (extra `file`: name inside the app's external `import/` folder), PLAY, STOP,
 * RECORD_START, RECORD_STOP, DUMP (logs the active script).
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
                    val id = store.save(ScriptJson.parse(File(dir, name).readText()))
                    store.activeId = id
                    "imported $name as $id"
                }
                "PLAY" -> if (service?.play() == true) "playing" else "not playing (service off or no script)"
                "STOP" -> service?.stopPlayback().let { "stopped" }
                "RECORD_START" -> service?.startRecording()?.let { "recording" } ?: "service off"
                "RECORD_STOP" -> service?.stopRecording(save = true)?.let { "saved $it" } ?: "nothing saved"
                "DUMP" -> store.activeId?.let(store::load)?.let(ScriptJson::toJson) ?: "no active script"
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
