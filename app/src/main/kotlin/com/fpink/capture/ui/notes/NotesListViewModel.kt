package com.fpink.capture.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.SettingsStore
import com.fpink.core.model.Note
import com.fpink.core.model.ZettelkastenCategory
import com.fpink.core.storage.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotesListUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = true,
    val error: NotesListError? = null,
    val selectedIds: Set<String> = emptySet(),
    val pendingDeletionIds: List<String> = emptyList(),
    val isDeleting: Boolean = false,
    val deletionError: String? = null,
    val deletedCount: Int? = null,
    val zettelkastenEnabled: Boolean = false,
    val isMoving: Boolean = false,
    val moveError: String? = null,
) {
    val isSelecting: Boolean get() = selectedIds.isNotEmpty()
    val allSelected: Boolean get() = notes.isNotEmpty() && notes.all { it.id in selectedIds }
    val canInteract: Boolean get() = !isLoading && !isDeleting && !isMoving && error == null
    val canChangeSelection: Boolean get() = canInteract && pendingDeletionIds.isEmpty()

    /** Notes grouped into the three fixed Zettelkasten sections, in their canonical display order. */
    val zettelkastenSections: List<Pair<ZettelkastenCategory, List<Note>>>
        get() = ZettelkastenCategory.entries.map { category -> category to notes.filter { it.zettelkastenCategory == category } }
}

sealed interface NotesListError {
    data class Storage(val detail: String) : NotesListError
    data object Interrupted : NotesListError
}

class NotesListViewModel(
    private val noteRepository: NoteRepository,
    private val settingsStore: SettingsStore? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesListUiState())
    val uiState: StateFlow<NotesListUiState> = _uiState.asStateFlow()
    private var operation: Job? = null

    init {
        refresh()
        settingsStore?.let { store ->
            viewModelScope.launch {
                store.zettelkastenEnabled.collect { enabled ->
                    _uiState.update { it.copy(zettelkastenEnabled = enabled) }
                }
            }
        }
    }


    fun refresh() {
        // A delete performs its own reconciliation; overlapping lifecycle refreshes
        // must not publish an older library snapshot after it.
        if (operation?.isActive == true) return
        _uiState.update { it.copy(isLoading = true) }
        operation = viewModelScope.launch {
            try {
                loadNotes()
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(error = NotesListError.Interrupted) }
                throw cancelled
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun select(id: String) {
        val state = _uiState.value
        if (!state.canChangeSelection || state.notes.none { it.id == id }) return
        _uiState.update { it.copy(selectedIds = it.selectedIds + id) }
    }

    fun toggleSelection(id: String) {
        val state = _uiState.value
        if (!state.canChangeSelection || state.notes.none { it.id == id }) return
        _uiState.update {
            it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id)
        }
    }

    fun toggleSelectAll() {
        val state = _uiState.value
        if (!state.canChangeSelection) return
        _uiState.update {
            it.copy(selectedIds = if (it.allSelected) emptySet() else it.notes.mapTo(mutableSetOf()) { note -> note.id })
        }
    }

    fun clearSelection() {
        if (_uiState.value.isDeleting || _uiState.value.pendingDeletionIds.isNotEmpty()) return
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    fun requestDeletion() {
        val state = _uiState.value
        if (!state.canChangeSelection || !state.isSelecting) return
        _uiState.update {
            it.copy(pendingDeletionIds = it.notes.filter { note -> note.id in it.selectedIds }.map { note -> note.id })
        }
    }

    fun cancelDeletion() {
        _uiState.update { it.copy(pendingDeletionIds = emptyList()) }
    }

    fun confirmDeletion() {
        val state = _uiState.value
        if (!state.canInteract || state.pendingDeletionIds.isEmpty()) return
        val ids = state.pendingDeletionIds
        _uiState.update {
            it.copy(isDeleting = true, pendingDeletionIds = emptyList(), deletionError = null, deletedCount = null)
        }
        operation = viewModelScope.launch {
            try {
                var failure: Throwable? = null
                for (id in ids) {
                    val result = noteRepository.delete(id)
                    if (result.isFailure) {
                        failure = result.exceptionOrNull()
                        break
                    }
                }
                _uiState.update { it.copy(deletionError = failure?.description()) }
                // Even a failed delete may have committed before image cleanup failed.
                loadNotes()
                if (failure == null && _uiState.value.error == null) {
                    _uiState.update { it.copy(deletedCount = ids.size) }
                }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(error = NotesListError.Interrupted) }
                throw cancelled
            } finally {
                _uiState.update { it.copy(isDeleting = false) }
            }
        }
    }

    fun dismissDeletionError() {
        _uiState.update { it.copy(deletionError = null) }
    }

    fun onDeletionResultShown() {
        _uiState.update { it.copy(deletedCount = null) }
    }

    fun moveSelectionTo(category: ZettelkastenCategory) {
        val state = _uiState.value
        if (!state.canChangeSelection || !state.isSelecting) return
        val notesToMove = state.notes.filter { it.id in state.selectedIds && it.zettelkastenCategory != category }
        if (notesToMove.isEmpty()) {
            _uiState.update { it.copy(selectedIds = emptySet()) }
            return
        }
        _uiState.update { it.copy(isMoving = true, moveError = null) }
        operation = viewModelScope.launch {
            try {
                var failure: Throwable? = null
                for (note in notesToMove) {
                    val result = noteRepository.save(note.copy(zettelkastenCategory = category))
                    if (result.isFailure) {
                        failure = result.exceptionOrNull()
                        break
                    }
                }
                loadNotes()
                _uiState.update {
                    it.copy(moveError = failure?.description(), selectedIds = if (failure == null) emptySet() else it.selectedIds)
                }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(error = NotesListError.Interrupted) }
                throw cancelled
            } finally {
                _uiState.update { it.copy(isMoving = false) }
            }
        }
    }

    fun dismissMoveError() {
        _uiState.update { it.copy(moveError = null) }
    }

    private suspend fun loadNotes() {
        noteRepository.list().fold(
            onSuccess = { notes ->
                val ids = notes.mapTo(mutableSetOf()) { it.id }
                _uiState.update {
                    it.copy(notes = notes, selectedIds = it.selectedIds.intersect(ids), error = null)
                }
            },
            onFailure = { error ->
                _uiState.update { it.copy(error = NotesListError.Storage(error.description())) }
            },
        )
    }
}

private fun Throwable.description(): String = message ?: javaClass.simpleName
