package dev.quickreplay.service

import dev.quickreplay.engine.PlaybackProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface ReplayState {
    /** Accessibility service is not running. */
    data object Disconnected : ReplayState
    data class Idle(val message: String? = null) : ReplayState
    data class Recording(val steps: Int) : ReplayState
    data class Playing(val scriptName: String, val progress: PlaybackProgress, val repeat: Int) : ReplayState

    companion object {
        internal val mutable = MutableStateFlow<ReplayState>(Disconnected)
        val flow: StateFlow<ReplayState> get() = mutable
    }
}
