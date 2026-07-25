package com.fpink.capture.ui.processing

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.SettingsStore
import com.fpink.core.ai.AiError
import com.fpink.core.ai.AzureOpenAiClient
import com.fpink.core.ai.PageAnalysis
import com.fpink.core.model.IdGenerator
import com.fpink.core.model.Note
import com.fpink.core.storage.NoteRepository
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface ProcessingUiState {
    data object Loading : ProcessingUiState
    data class Error(val message: String, val isRetryable: Boolean = true) : ProcessingUiState
    data class Success(val noteId: String) : ProcessingUiState
}

class ProcessingViewModel(
    private val aiClient: AzureOpenAiClient,
    private val noteRepository: NoteRepository,
    private val settingsStore: SettingsStore,
    private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ProcessingUiState>(ProcessingUiState.Loading)
    val uiState: StateFlow<ProcessingUiState> = _uiState.asStateFlow()

    fun processImage(imageUriString: String) {
        viewModelScope.launch {
            _uiState.value = ProcessingUiState.Loading

            val config = settingsStore.aiConfig.first()
            if (config == null) {
                _uiState.value = ProcessingUiState.Error(
                    "Azure OpenAI is not configured. Open Settings to add your endpoint and key.",
                    isRetryable = false,
                )
                return@launch
            }

            val imageBytes = try {
                val uri = Uri.parse(imageUriString)
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not open image stream")
            } catch (e: Exception) {
                _uiState.value = ProcessingUiState.Error(
                    "Failed to read the captured image: ${e.message}",
                    isRetryable = false,
                )
                return@launch
            }

            aiClient.analysePage(config, imageBytes, "image/jpeg").fold(
                onSuccess = { analysis -> saveNote(analysis, imageBytes) },
                onFailure = { error ->
                    val message = when (error) {
                        is AiError.NetworkError -> "Network error. Check your connection and try again."
                        is AiError.AuthError -> "Authentication failed. Check your API key in Settings."
                        is AiError.RateLimited -> "Rate limited. Please wait a moment and try again."
                        is AiError.MalformedResponse -> "Received an unexpected response. Please try again."
                        is AiError.Unknown -> "An unexpected error occurred: ${error.message}"
                        else -> "Error: ${error.message}"
                    }
                    _uiState.value = ProcessingUiState.Error(message, isRetryable = error !is AiError.AuthError)
                },
            )
        }
    }

    private suspend fun saveNote(analysis: PageAnalysis, imageBytes: ByteArray) {
        val noteId = IdGenerator.generate()
        val imagePath = "images/$noteId.jpg"

        noteRepository.saveImage(noteId, imageBytes).onFailure {
            _uiState.value = ProcessingUiState.Error("Failed to save the captured image: ${it.message}")
            return
        }

        val note = Note(
            id = noteId,
            capturedAt = Clock.System.now(),
            imagePath = imagePath,
            text = analysis.text,
            inkColorHex = analysis.inkColorHex,
            inkColorName = analysis.inkColorName,
            confidence = analysis.confidence,
            modelNotes = analysis.notes,
            userEdited = false,
        )

        noteRepository.save(note).fold(
            onSuccess = { _uiState.value = ProcessingUiState.Success(noteId) },
            onFailure = { _uiState.value = ProcessingUiState.Error("Failed to save note: ${it.message}") },
        )
    }
}
