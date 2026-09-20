package com.fpink.capture.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onCameraClick: () -> Unit,
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
                title = { Text("FPInk") },
                actions = {
                    ActionIconButton(R.drawable.ic_settings, R.string.settings, onClick = onSettingsClick)
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ActionFloatingButton(R.drawable.ic_add, R.string.add_notes, onClick = onCaptureClick)
                ActionFloatingButton(R.drawable.ic_camera, R.string.take_photo, onClick = onCameraClick)
            }
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
                state.error != null -> Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
                state.notes.isEmpty() -> EmptyState(onCaptureClick = onCaptureClick)
                else -> NotesList(notes = state.notes, onNoteClick = onNoteClick)
            }
        }
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
    onNoteClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(notes, key = { it.id }) { note ->
            NoteRow(note = note, onClick = { onNoteClick(note.id) })
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
        Column(modifier = Modifier.fillMaxWidth()) {
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
