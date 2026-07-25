package com.fpink.capture.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.core.model.Note
import com.fpink.core.storage.NoteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NoteDetailUiState(
    val note: Note? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val editedText: String = "",
    val isEditing: Boolean = false,
    val showFullImage: Boolean = false,
    val deleted: Boolean = false,
)

class NoteDetailViewModel(
    private val noteId: String,
    private val noteRepository: NoteRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteDetailUiState())
    val uiState: StateFlow<NoteDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            noteRepository.get(noteId).fold(
                onSuccess = { note ->
                    _uiState.update {
                        it.copy(
                            note = note,
                            editedText = note.text,
                            isEditing = false,
                            isLoading = false,
                            error = null,
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to load note: ${e.message}")
                    }
                },
            )
        }
    }

    fun onTextChanged(newText: String) {
        _uiState.update { it.copy(editedText = newText, isEditing = true) }
    }

    fun onSave() {
        val current = _uiState.value.note ?: return
        val newText = _uiState.value.editedText
        val textChanged = newText != current.text
        val updated = current.copy(
            text = newText,
            userEdited = current.userEdited || textChanged,
        )
        viewModelScope.launch {
            noteRepository.save(updated).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(note = updated, editedText = updated.text, isEditing = false, error = null)
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = "Failed to save note: ${e.message}") }
                },
            )
        }
    }

    fun onDelete() {
        viewModelScope.launch {
            noteRepository.delete(noteId).fold(
                onSuccess = { _uiState.update { it.copy(deleted = true) } },
                onFailure = { e ->
                    _uiState.update { it.copy(error = "Failed to delete note: ${e.message}") }
                },
            )
        }
    }

    fun onToggleFullImage() {
        _uiState.update { it.copy(showFullImage = !it.showFullImage) }
    }
}
