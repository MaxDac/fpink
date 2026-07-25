package com.fpink.capture.ui.capture

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptureUiState(
    val isReady: Boolean = false,
)

// Stub for Phase 0. Real CameraX wiring arrives in a later milestone.
class CaptureViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()
}
