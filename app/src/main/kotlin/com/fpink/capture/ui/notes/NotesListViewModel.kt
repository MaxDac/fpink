package com.fpink.capture.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.core.model.Note
import com.fpink.core.storage.NoteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotesListUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = true,
)

class NotesListViewModel(
    private val noteRepository: NoteRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesListUiState())
    val uiState: StateFlow<NotesListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val notes = noteRepository.list().getOrDefault(emptyList())
            _uiState.update { it.copy(notes = notes, isLoading = false) }
        }
    }
}
