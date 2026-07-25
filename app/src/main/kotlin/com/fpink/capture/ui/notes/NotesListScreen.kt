package com.fpink.capture.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fpink.capture.ui.components.InkColorSwatch
import com.fpink.capture.ui.containerViewModel
import com.fpink.core.model.Note

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    onCaptureClick: () -> Unit,
    onNoteClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: NotesListViewModel = containerViewModel { NotesListViewModel(it.noteRepository) },
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FPInk") },
                actions = {
                    TextButton(onClick = onSettingsClick) { Text("Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Capture") },
                icon = {},
                onClick = onCaptureClick,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> CircularProgressIndicator()
                state.notes.isEmpty() -> EmptyState()
                else -> NotesList(notes = state.notes, onNoteClick = onNoteClick)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("📝")
        Text("Capture your first note")
    }
}

@Composable
private fun NotesList(
    notes: List<Note>,
    onNoteClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(notes, key = { it.id }) { note ->
            NoteRow(note = note, onClick = { onNoteClick(note.id) })
        }
    }
}

@Composable
private fun NoteRow(
    note: Note,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InkColorSwatch(colorHex = note.inkColorHex)
        Text(
            text = note.text.ifBlank { "(no text)" },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
