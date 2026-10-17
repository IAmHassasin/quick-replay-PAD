package dev.quickreplay.service

import dev.quickreplay.engine.PlaybackProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface ReplayState {
    /** Accessibility service is not running. */
    data object Disconnected : ReplayState
    data class Idle(val message: String? = null) : ReplayState
    /** [lastAction] describes the step just recorded, e.g. "Tap 80 ms". */
    data class Recording(val steps: Int, val lastAction: String? = null) : ReplayState

    /** [partName] is set when a combined script (sequence) is playing. */
    data class Playing(
        val scriptName: String,
        val progress: PlaybackProgress,
        val repeat: Int,
        val partName: String? = null,
        val paused: Boolean = false,
    ) : ReplayState

    companion object {
        internal val mutable = MutableStateFlow<ReplayState>(Disconnected)
        val flow: StateFlow<ReplayState> get() = mutable
    }
}
