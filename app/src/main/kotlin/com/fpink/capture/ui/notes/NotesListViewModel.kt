package com.fpink.capture.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.SettingsStore
import com.fpink.core.model.Note
import com.fpink.core.model.NoteSearch
import com.fpink.core.model.ZettelkastenCategory
import com.fpink.core.storage.NoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    val query: String = "",
    val categoryFilter: Set<ZettelkastenCategory> = emptySet(),
    /** Notes matching [query], ranked by relevance; null while no query is active. */
    val searchResults: List<Note>? = null,
) {
    val isSelecting: Boolean get() = selectedIds.isNotEmpty()
    val canInteract: Boolean get() = !isLoading && !isDeleting && !isMoving && error == null
    val canChangeSelection: Boolean get() = canInteract && pendingDeletionIds.isEmpty()

    /** The category filter only applies while the Zettelkasten method is enabled. */
    val activeCategoryFilter: Set<ZettelkastenCategory>
        get() = if (zettelkastenEnabled) categoryFilter else emptySet()
    val isFiltering: Boolean get() = query.isNotBlank() || activeCategoryFilter.isNotEmpty()

    /** Notes shown in the list after search ranking and the Zettelkasten category filter. */
    val visibleNotes: List<Note>
        get() {
            val base = if (query.isBlank()) notes else searchResults ?: notes
            val filter = activeCategoryFilter
            return if (filter.isEmpty()) base else base.filter { it.zettelkastenCategory in filter }
        }
    val allSelected: Boolean get() = visibleNotes.let { visible -> visible.isNotEmpty() && visible.all { it.id in selectedIds } }

    /** Visible notes grouped into the Zettelkasten sections, in canonical order, omitting filtered-out categories. */
    val zettelkastenSections: List<Pair<ZettelkastenCategory, List<Note>>>
        get() {
            val visible = visibleNotes
            val filter = activeCategoryFilter
            return ZettelkastenCategory.entries
                .filter { filter.isEmpty() || it in filter }
                .map { category -> category to visible.filter { it.zettelkastenCategory == category } }
        }

    fun categoryCount(category: ZettelkastenCategory): Int =
        (if (query.isBlank()) notes else searchResults ?: notes).count { it.zettelkastenCategory == category }
}

sealed interface NotesListError {
    data class Storage(val detail: String) : NotesListError
    data object Interrupted : NotesListError
}

class NotesListViewModel(
    private val noteRepository: NoteRepository,
    private val settingsStore: SettingsStore? = null,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
    }

    private val _uiState = MutableStateFlow(NotesListUiState())
    val uiState: StateFlow<NotesListUiState> = _uiState.asStateFlow()
    private var operation: Job? = null
    private var searchJob: Job? = null

    init {
        refresh()
        settingsStore?.let { store ->
            viewModelScope.launch {
                store.zettelkastenEnabled.collect { enabled ->
                    _uiState.update { it.copy(zettelkastenEnabled = enabled).pruned() }
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
        if (!state.canChangeSelection || state.visibleNotes.none { it.id == id }) return
        _uiState.update { it.copy(selectedIds = it.selectedIds + id) }
    }

    fun toggleSelection(id: String) {
        val state = _uiState.value
        if (!state.canChangeSelection || state.visibleNotes.none { it.id == id }) return
        _uiState.update {
            it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id)
        }
    }

    fun toggleSelectAll() {
        val state = _uiState.value
        if (!state.canChangeSelection) return
        _uiState.update {
            it.copy(selectedIds = if (it.allSelected) emptySet() else it.visibleNotes.mapTo(mutableSetOf()) { note -> note.id })
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

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        scheduleSearch(debounce = true)
    }

    fun toggleCategoryFilter(category: ZettelkastenCategory) {
        _uiState.update {
            it.copy(categoryFilter = if (category in it.categoryFilter) it.categoryFilter - category else it.categoryFilter + category).pruned()
        }
    }

    fun clearFilters() {
        _uiState.update { it.copy(query = "", categoryFilter = emptySet()) }
        scheduleSearch(debounce = false)
    }

    private fun scheduleSearch(debounce: Boolean) {
        searchJob?.cancel()
        val snapshot = _uiState.value
        val query = snapshot.query
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = null).pruned() }
            return
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val results = withContext(computeDispatcher) { NoteSearch.rank(snapshot.notes, query) }
            _uiState.update {
                if (it.query == query && it.notes === snapshot.notes) it.copy(searchResults = results).pruned() else it
            }
        }
    }

    /** Hidden notes are deselected so bulk actions never affect notes the user cannot see. */
    private fun NotesListUiState.pruned(): NotesListUiState {
        if (selectedIds.isEmpty()) return this
        val visible = visibleNotes.mapTo(mutableSetOf()) { it.id }
        return copy(selectedIds = selectedIds.intersect(visible))
    }

    private suspend fun loadNotes() {
        noteRepository.list().fold(
            onSuccess = { notes ->
                val ids = notes.mapTo(mutableSetOf()) { it.id }
                _uiState.update {
                    it.copy(
                        notes = notes,
                        searchResults = it.searchResults?.filter { note -> note.id in ids },
                        selectedIds = it.selectedIds.intersect(ids),
                        error = null,
                    ).pruned()
                }
                if (_uiState.value.query.isNotBlank()) scheduleSearch(debounce = false)
            },
            onFailure = { error ->
                _uiState.update { it.copy(error = NotesListError.Storage(error.description())) }
            },
        )
    }
}

private fun Throwable.description(): String = message ?: javaClass.simpleName
