package com.harmony.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.data.analysis.inspect.SpectralInspector
import com.harmony.domain.analysis.model.SpectralReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class FlacCheckViewModel @Inject constructor(
    private val inspector: SpectralInspector,
) : ViewModel() {

    data class State(
        val isRunning: Boolean = false,
        val report: SpectralReport? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun inspect(uri: String, fileName: String, sizeBytes: Long) {
        if (_state.value.isRunning) return
        _state.update { State(isRunning = true) }
        viewModelScope.launch {
            try {
                // Decoding and the FFT are both CPU-bound and run for several
                // seconds; keeping them off the main thread is what stops the
                // progress spinner from freezing.
                val report = withContext(Dispatchers.Default) {
                    inspector.inspect(
                        uriString = uri,
                        fileName = fileName,
                        fileSizeBytes = sizeBytes,
                        durationMs = 0L,
                    )
                }
                _state.value = State(report = report)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = State(
                    error = "Couldn't analyse this file: ${e.message ?: "unsupported format"}",
                )
            }
        }
    }
}
