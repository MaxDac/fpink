package com.fpink.capture.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.fpink.capture.ui.components.InkColorSwatch
import com.fpink.capture.ui.containerViewModel
import com.fpink.core.model.Note
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    noteId: String,
    onBack: () -> Unit,
    viewModel: NoteDetailViewModel = containerViewModel {
        NoteDetailViewModel(noteId = noteId, noteRepository = it.noteRepository)
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note Detail") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::onSave,
                        enabled = state.isEditing && state.note != null,
                    ) { Text("Save") }
                    TextButton(
                        onClick = { showDeleteDialog = true },
                        enabled = state.note != null,
                    ) { Text("Delete") }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            val note = state.note
            when {
                state.isLoading -> CircularProgressIndicator()
                note == null -> Text(
                    text = state.error ?: "Note not found",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
                else -> NoteDetailContent(
                    note = note,
                    editedText = state.editedText,
                    error = state.error,
                    onTextChanged = viewModel::onTextChanged,
                    onThumbnailClick = viewModel::onToggleFullImage,
                )
            }
        }
    }

    val note = state.note
    if (state.showFullImage && note != null) {
        FullImageDialog(note = note, onDismiss = viewModel::onToggleFullImage)
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete note?") },
            text = { Text("This note and its photo will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NoteDetailContent(
    note: Note,
    editedText: String,
    error: String?,
    onTextChanged: (String) -> Unit,
    onThumbnailClick: () -> Unit,
) {
    val context = LocalContext.current
    val imageFile = remember(note.imagePath) { File(context.filesDir, note.imagePath) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = imageFile,
            contentDescription = "Source photo",
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onThumbnailClick),
            contentScale = ContentScale.Crop,
        )

        OutlinedTextField(
            value = editedText,
            onValueChange = onTextChanged,
            label = { Text("Transcription") },
            modifier = Modifier.fillMaxWidth(),
        )

        MetadataRow(note = note)

        note.modelNotes?.takeIf { it.isNotBlank() }?.let { notes ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Model notes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(text = notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MetadataRow(note: Note) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InkColorSwatch(colorHex = note.inkColorHex)
        val colorLabel = buildString {
            append(note.inkColorHex ?: "unknown")
            note.inkColorName?.let { append(" · $it") }
        }
        Text(
            text = colorLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        note.confidence?.let { c ->
            Text(
                text = "${(c * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FullImageDialog(note: Note, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val imageFile = remember(note.imagePath) { File(context.filesDir, note.imagePath) }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = imageFile,
                contentDescription = "Source photo (full)",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
