package com.fpink.capture.ui.processing

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProcessingUiState(
    val isProcessing: Boolean = true,
    val error: String? = null,
)

// Stub for Phase 0. Real OCR/AI processing pipeline arrives in a later milestone.
class ProcessingViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ProcessingUiState())
    val uiState: StateFlow<ProcessingUiState> = _uiState.asStateFlow()
}
