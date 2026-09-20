package com.fpink.capture.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.fpink.capture.ui.components.InkColorSwatch
import com.fpink.capture.R
import com.fpink.capture.ui.components.ActionFloatingButton
import com.fpink.capture.ui.components.ActionIconButton
import com.fpink.capture.ui.containerViewModel
import com.fpink.core.model.Note
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    onCaptureClick: () -> Unit,
    onNoteClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    resultMessage: String? = null,
    onResultShown: () -> Unit = {},
    viewModel: NotesListViewModel = containerViewModel { NotesListViewModel(it.noteRepository) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(resultMessage) {
        resultMessage?.let {
            snackbar.showSnackbar(it)
            onResultShown()
        }
    }
    val deletedMessage = state.deletedCount?.let { pluralStringResource(R.plurals.notes_deleted, it, it) }
    LaunchedEffect(deletedMessage) {
        deletedMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.onDeletionResultShown()
        }
    }

    BackHandler(enabled = state.isSelecting || state.isDeleting) {
        if (!state.isDeleting) {
            if (state.pendingDeletionIds.isNotEmpty()) viewModel.cancelDeletion()
            else viewModel.clearSelection()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.isSelecting) {
                            pluralStringResource(R.plurals.notes_selected, state.selectedIds.size, state.selectedIds.size)
                        } else {
                            "FPInk"
                        },
                    )
                },
                navigationIcon = {
                    if (state.isSelecting) {
                        ActionIconButton(
                            R.drawable.ic_back, R.string.clear_note_selection,
                            enabled = !state.isDeleting, onClick = viewModel::clearSelection,
                        )
                    }
                },
                actions = {
                    if (state.isSelecting) {
                        ActionIconButton(
                            R.drawable.ic_delete, R.string.delete_selected_notes,
                            enabled = state.canChangeSelection, onClick = viewModel::requestDeletion,
                        )
                    } else if (!state.isDeleting) {
                        ActionIconButton(R.drawable.ic_settings, R.string.settings, onClick = onSettingsClick)
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.isSelecting && !state.isDeleting) {
                ActionFloatingButton(R.drawable.ic_add, R.string.add_notes, onClick = onCaptureClick)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            state.deletionError?.let { error ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.notes_delete_error, error),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = viewModel::dismissDeletionError) {
                        Text(stringResource(R.string.dismiss))
                    }
                }
            }
            if (state.isSelecting) {
                SelectAllRow(
                    allSelected = state.allSelected,
                    enabled = state.canChangeSelection,
                    onClick = viewModel::toggleSelectAll,
                )
            }
            if (state.isDeleting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.deleting_notes), Modifier.padding(16.dp))
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val error = state.error
                when {
                    state.isLoading -> CircularProgressIndicator()
                    error != null -> Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = when (error) {
                                is NotesListError.Storage -> stringResource(R.string.notes_load_error, error.detail)
                                NotesListError.Interrupted -> stringResource(R.string.notes_operation_interrupted)
                            },
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = viewModel::refresh, enabled = !state.isDeleting) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                    state.notes.isEmpty() && state.isDeleting -> CircularProgressIndicator()
                    state.notes.isEmpty() -> EmptyState(onCaptureClick = onCaptureClick)
                    else -> NotesList(
                        notes = state.notes,
                        selectedIds = state.selectedIds,
                        enabled = state.canChangeSelection,
                        onNoteClick = { id ->
                            if (state.isSelecting) viewModel.toggleSelection(id) else onNoteClick(id)
                        },
                        onNoteLongClick = viewModel::select,
                    )
                }
            }
        }
    }

    if (state.pendingDeletionIds.isNotEmpty()) {
        val count = state.pendingDeletionIds.size
        AlertDialog(
            onDismissRequest = viewModel::cancelDeletion,
            title = { Text(pluralStringResource(R.plurals.delete_notes_confirmation, count, count)) },
            text = { Text(pluralStringResource(R.plurals.delete_notes_warning, count)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeletion, enabled = state.canInteract) {
                    Text(stringResource(R.string.delete_notes_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDeletion) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SelectAllRow(allSelected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val checkedState = if (allSelected) ToggleableState.On else ToggleableState.Indeterminate
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .triStateToggleable(state = checkedState, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .sizeIn(minHeight = 48.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TriStateCheckbox(state = checkedState, onClick = null, enabled = enabled)
        Text(stringResource(R.string.select_all_notes))
    }
}

@Composable
private fun EmptyState(onCaptureClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "🖋️", style = MaterialTheme.typography.displayMedium)
        Text(
            text = "Take a photo or choose an image",
            style = MaterialTheme.typography.titleMedium,
        )
        ActionIconButton(R.drawable.ic_add, R.string.add_notes, filled = true, onClick = onCaptureClick)
    }
}

@Composable
private fun NotesList(
    notes: List<Note>,
    selectedIds: Set<String>,
    enabled: Boolean,
    onNoteClick: (String) -> Unit,
    onNoteLongClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(notes, key = { it.id }) { note ->
            NoteRow(
                note = note,
                selectionMode = selectedIds.isNotEmpty(),
                selected = note.id in selectedIds,
                enabled = enabled,
                onClick = { onNoteClick(note.id) },
                onLongClick = { onNoteLongClick(note.id) },
            )
        }
    }
}

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())

private fun Note.formattedDate(): String =
    dateFormatter.format(java.time.Instant.ofEpochMilli(capturedAt.toEpochMilliseconds()))

@Composable
private fun NoteRow(
    note: Note,
    selectionMode: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .combinedClickable(
                enabled = enabled,
                role = if (selectionMode) Role.Checkbox else Role.Button,
                onClickLabel = stringResource(
                    if (!selectionMode) R.string.open_note
                    else if (selected) R.string.deselect_note
                    else R.string.select_note,
                ),
                onLongClickLabel = stringResource(R.string.select_note),
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics {
                if (selectionMode) toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
            }
            .sizeIn(minHeight = 48.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        }
        InkColorSwatch(colorHex = note.inkColorHex)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = note.text.lines().firstOrNull()?.ifBlank { "Untitled" } ?: "Untitled",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = note.formattedDate(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
