package com.fpink.capture.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.ui.applyTextEdit
import com.fpink.core.model.Note
import com.fpink.core.model.InkColorOrigin
import com.fpink.core.ai.inkColorName
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
    val editedColorHex: String = "#000000",
    val colorTouched: Boolean = false,
    val colorError: String? = null,
    val isSaving: Boolean = false,
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
                            editedColorHex = note.inkColorHex ?: "#000000",
                            colorTouched = false,
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
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(editedText = newText, isEditing = true, error = null) }
    }

    fun onColorChanged(value: String) {
        if (_uiState.value.isSaving) return
        _uiState.update {
            it.copy(
                editedColorHex = value,
                colorTouched = true,
                isEditing = true,
                colorError = if (normalizeInkHex(value) == null) "Enter a six-digit colour such as #123ABC." else null,
                error = null,
            )
        }
    }

    fun onSave() {
        val state = _uiState.value
        val current = state.note ?: return
        if (state.isSaving || state.colorError != null) return
        if (state.editedText.isBlank()) {
            _uiState.update { it.copy(error = "The note text cannot be empty.") }
            return
        }
        val updated = applyNoteEdits(
            current, state.editedText, state.editedColorHex, state.colorTouched,
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            noteRepository.save(updated).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            note = updated, editedText = updated.text,
                            editedColorHex = updated.inkColorHex ?: "#000000",
                            colorTouched = false, isEditing = false, error = null, isSaving = false,
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = "Failed to save note: ${e.message}", isSaving = false) }
                },
            )
        }
    }

    fun onDelete() {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            noteRepository.delete(noteId).fold(
                onSuccess = { _uiState.update { it.copy(deleted = true) } },
                onFailure = { e ->
                    _uiState.update { it.copy(error = "Failed to delete note: ${e.message}", isSaving = false) }
                },
            )
        }

    }

    fun onToggleFullImage() {
        _uiState.update { it.copy(showFullImage = !it.showFullImage) }
    }
}

internal fun normalizeInkHex(value: String): String? =
    value.trim().takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.uppercase(java.util.Locale.ROOT)

internal fun applyNoteEdits(note: Note, text: String, colorHex: String, colorTouched: Boolean): Note {
    val hex = if (colorTouched) requireNotNull(normalizeInkHex(colorHex)) else note.inkColorHex
    return applyTextEdit(note, text).copy(
        inkColorHex = hex,
        inkColorName = if (colorTouched) inkColorName(checkNotNull(hex)) else note.inkColorName,
        inkColorOrigin = if (colorTouched) InkColorOrigin.USER_SELECTED else note.inkColorOrigin,
    )
}
