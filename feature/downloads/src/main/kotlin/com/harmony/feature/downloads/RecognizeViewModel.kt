package com.harmony.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the Recognize screen is in one listen. */
sealed interface ListenPhase {
    data object Idle : ListenPhase
    data class Listening(val progress: Float = 0f, val level: Float = 0f) : ListenPhase
    data object Identifying : ListenPhase
    data class Found(val song: RecognizedSong) : ListenPhase
    data object NoMatch : ListenPhase
    data class Failed(val message: String, val needsToken: Boolean = false) : ListenPhase
}

data class RecognizeUiState(
    val phase: ListenPhase = ListenPhase.Idle,
    val history: List<RecognizedSong> = emptyList(),
    /** Blank: AudD's free daily requests. */
    val token: String = "",
)

@HiltViewModel
class RecognizeViewModel @Inject constructor(
    private val recognizer: SongRecognizer,
    private val handoff: RecognitionHandoff,
) : ViewModel() {
    private val phase = MutableStateFlow<ListenPhase>(ListenPhase.Idle)
    private var listening: Job? = null

    val state: StateFlow<RecognizeUiState> =
        combine(phase, recognizer.history, recognizer.token) { p, h, t -> RecognizeUiState(p, h, t) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RecognizeUiState())

    init { recognizer.load() }

    /** Starts listening; tapping again while listening stops. Needs RECORD_AUDIO. */
    fun listen() {
        if (listening?.isActive == true) {
            stop()
            return
        }
        phase.value = ListenPhase.Listening()
        listening = viewModelScope.launch {
            val outcome = recognizer.recognize(
                onLevel = { level -> (phase.value as? ListenPhase.Listening)?.let { phase.value = it.copy(level = level) } },
                onProgress = { p -> (phase.value as? ListenPhase.Listening)?.let { phase.value = it.copy(progress = p) } },
                onUploading = { phase.value = ListenPhase.Identifying },
            )
            phase.value = when (outcome) {
                is RecognitionOutcome.Match -> ListenPhase.Found(outcome.song)
                RecognitionOutcome.NoMatch -> ListenPhase.NoMatch
                is RecognitionOutcome.Failed -> ListenPhase.Failed(outcome.message, outcome.needsToken)
            }
        }
    }

    fun stop() {
        listening?.cancel()
        listening = null
        phase.value = ListenPhase.Idle
    }

    fun permissionDenied() {
        phase.value = ListenPhase.Failed("Harmony needs the microphone to hear the song. Allow it in the prompt or in Settings, then try again.")
    }

    fun show(song: RecognizedSong) { phase.value = ListenPhase.Found(song) }

    fun forget(song: RecognizedSong) {
        recognizer.forget(song)
        if ((phase.value as? ListenPhase.Found)?.song == song) phase.value = ListenPhase.Idle
    }

    fun setToken(value: String) {
        recognizer.setToken(value)
        if ((phase.value as? ListenPhase.Failed)?.needsToken == true) phase.value = ListenPhase.Idle
    }

    /** Queues a SpotiFLAC search for [song]; the caller then opens Downloads. */
    fun download(song: RecognizedSong) = handoff.searchSpotiFlac(song.searchQuery)

    override fun onCleared() {
        listening?.cancel()
        super.onCleared()
    }
}
