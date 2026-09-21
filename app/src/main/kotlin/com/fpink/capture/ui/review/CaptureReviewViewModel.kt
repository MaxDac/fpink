package com.fpink.capture.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.ui.applyTextEdit
import com.fpink.core.model.Note
import com.fpink.core.storage.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CaptureReviewItem(
    val note: Note,
    val text: String,
) {
    val isDirty: Boolean get() = text != note.text
}

data class CaptureReviewUiState(
    val items: List<CaptureReviewItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val validationRequested: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
) {
    val hasUnsavedChanges: Boolean get() = items.any(CaptureReviewItem::isDirty)
}

class CaptureReviewViewModel(
    private val sourceId: String,
    private val noteRepository: NoteRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CaptureReviewUiState())
    val uiState: StateFlow<CaptureReviewUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() {
        if (!_uiState.value.isSaving) load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, validationRequested = false) }
            try {
                noteRepository.list().fold(
                    onSuccess = { notes ->
                        val items = notes
                            .filter { it.sourceId == sourceId }
                            .sortedWith(compareBy<Note> { it.paragraphIndex ?: Int.MAX_VALUE }.thenBy { it.id })
                            .map { CaptureReviewItem(it, it.text) }
                        _uiState.update {
                            if (items.isEmpty()) {
                                it.copy(
                                    items = emptyList(),
                                    isLoading = false,
                                    error = "No captured notes were found for this review.",
                                )
                            } else {
                                it.copy(items = items, isLoading = false, error = null)
                            }
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Failed to load captured notes: ${error.description()}",
                            )
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Loading was interrupted. Retry to review the captured notes.")
                }
                throw cancelled
            }
        }
    }

    fun onTextChanged(noteId: String, text: String) {
        val state = _uiState.value
        if (state.isLoading || state.isSaving || state.completed) return
        _uiState.update {
            it.copy(
                items = it.items.map { item -> if (item.note.id == noteId) item.copy(text = text) else item },
                error = null,
            )
        }
    }

    fun onDone() {
        val state = _uiState.value
        if (state.isLoading || state.isSaving || state.completed || state.items.isEmpty()) return
        if (state.items.any { it.text.isBlank() }) {
            _uiState.update {
                it.copy(
                    validationRequested = true,
                    error = "Every captured note needs transcription text.",
                )
            }
            return
        }
        val dirtyIds = state.items.filter(CaptureReviewItem::isDirty).map { it.note.id }
        if (dirtyIds.isEmpty()) {
            _uiState.update { it.copy(completed = true, error = null) }
            return
        }
        _uiState.update { it.copy(isSaving = true, validationRequested = false, error = null) }
        viewModelScope.launch {
            try {
                var failure: Throwable? = null
                for (id in dirtyIds) {
                    val item = _uiState.value.items.firstOrNull { it.note.id == id } ?: continue
                    if (!item.isDirty) continue
                    val updated = applyTextEdit(item.note, item.text)
                    noteRepository.save(updated).fold(
                        onSuccess = {
                            _uiState.update { current ->
                                current.copy(
                                    items = current.items.map {
                                        if (it.note.id == id) it.copy(note = updated) else it
                                    },
                                )
                            }
                        },
                        onFailure = {
                            failure = it
                        },
                    )
                    if (failure != null) break
                }
                _uiState.update {
                    if (failure == null) {
                        it.copy(isSaving = false, completed = true, error = null)
                    } else {
                        it.copy(
                            isSaving = false,
                            error = "Failed to save all corrections: ${failure.description()}. Retry Done to save the remaining changes.",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = "Saving was interrupted. Retry Done to reconcile the remaining changes.",
                    )
                }
                throw cancelled
            }
        }
    }
}

private fun Throwable?.description(): String =
    this?.message ?: this?.javaClass?.simpleName ?: "Unknown storage error"
