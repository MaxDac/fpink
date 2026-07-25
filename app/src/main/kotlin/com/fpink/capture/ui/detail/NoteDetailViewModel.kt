package com.fpink.capture.ui.detail

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NoteDetailUiState(
    val isLoading: Boolean = true,
)

// Stub for Phase 0. Real note loading/editing arrives in a later milestone.
class NoteDetailViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(NoteDetailUiState())
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()
}
