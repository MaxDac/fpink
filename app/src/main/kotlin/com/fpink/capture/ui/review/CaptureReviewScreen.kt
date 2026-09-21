package com.fpink.capture.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.fpink.capture.R
import com.fpink.capture.ui.components.ActionIconButton
import com.fpink.capture.ui.containerViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureReviewScreen(
    sourceId: String,
    onComplete: () -> Unit,
    onExit: () -> Unit,
    viewModel: CaptureReviewViewModel = containerViewModel {
        CaptureReviewViewModel(sourceId, it.noteRepository)
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.completed) {
        if (state.completed) onComplete()
    }

    fun requestExit() {
        if (state.isSaving) return
        if (state.hasUnsavedChanges) showDiscardDialog = true else onExit()
    }

    BackHandler(enabled = !state.completed) { requestExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_captured_notes)) },
                navigationIcon = {
                    ActionIconButton(
                        R.drawable.ic_back,
                        R.string.back,
                        enabled = !state.isSaving,
                        onClick = ::requestExit,
                    )
                },
                actions = {
                    TextButton(
                        onClick = viewModel::onDone,
                        enabled = state.items.isNotEmpty() && !state.isLoading && !state.isSaving,
                    ) {
                        Text(stringResource(R.string.done))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> CircularProgressIndicator()
                state.items.isEmpty() -> Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        state.error ?: stringResource(R.string.review_notes_missing),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = viewModel::retry) {
                        Text(stringResource(R.string.retry))
                    }
                }
                else -> ReviewNotes(
                    state = state,
                    onTextChanged = viewModel::onTextChanged,
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.discard_corrections_title)) },
            text = { Text(stringResource(R.string.discard_corrections_message)) },
            confirmButton = {
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.discard_changes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.keep_reviewing))
                }
            },
        )
    }
}

@Composable
private fun ReviewNotes(
    state: CaptureReviewUiState,
    onTextChanged: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val imageFile = remember(state.items.first().note.imagePath) {
        File(context.filesDir, state.items.first().note.imagePath)
    }
    Column(Modifier.fillMaxSize()) {
        if (state.isSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AsyncImage(
                    model = imageFile,
                    contentDescription = stringResource(R.string.captured_source_image),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    stringResource(R.string.review_captured_notes_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            itemsIndexed(state.items, key = { _, item -> item.note.id }) { index, item ->
                val blankError = state.validationRequested && item.text.isBlank()
                OutlinedTextField(
                    value = item.text,
                    onValueChange = { onTextChanged(item.note.id, it) },
                    label = { Text(stringResource(R.string.review_note_label, index + 1)) },
                    enabled = !state.isSaving,
                    isError = blankError,
                    supportingText = if (blankError) {
                        { Text(stringResource(R.string.note_text_required)) }
                    } else {
                        null
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
