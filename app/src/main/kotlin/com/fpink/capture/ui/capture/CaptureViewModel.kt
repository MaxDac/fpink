package com.fpink.capture.ui.capture

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CaptureUiState(
    val capturedImageUri: Uri? = null, // null = showing preview, non-null = confirm/retake
    val isCapturing: Boolean = false,
    val error: String? = null,
)

class CaptureViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    fun onImageCaptured(uri: Uri) {
        _uiState.update { it.copy(capturedImageUri = uri, isCapturing = false, error = null) }
    }

    fun onCaptureStarted() {
        _uiState.update { it.copy(isCapturing = true, error = null) }
    }

    fun onCaptureError(message: String) {
        _uiState.update { it.copy(isCapturing = false, error = message) }
    }

    fun onRetake() {
        _uiState.update { CaptureUiState() }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
