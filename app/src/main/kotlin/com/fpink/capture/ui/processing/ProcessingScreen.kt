package com.fpink.capture.ui.processing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.fpink.capture.data.RecognitionJobState
import com.fpink.capture.ui.savedContainerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    onComplete: (Int, Boolean, Boolean) -> Unit,
    onDiscard: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ProcessingViewModel = savedContainerViewModel { container, savedState ->
        ProcessingViewModel(container.recognitionCoordinator, container.imageImports, savedState)
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val discardError by viewModel.discardError.collectAsStateWithLifecycle()
    BackHandler { viewModel.discard(onDiscard) }
    LaunchedEffect(state) {
        (state as? RecognitionJobState.Complete)?.let {
            onComplete(it.count, it.cleanupWarning, it.previouslySaved)
            viewModel.release()
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Creating paragraph notes") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            when (val current = state) {
                is RecognitionJobState.Working -> {
                    AsyncImage(
                        model = viewModel.previewFile,
                        contentDescription = "Prepared source image",
                        modifier = Modifier.size(200.dp),
                    )
                    CircularProgressIndicator()
                    Text(current.message, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = { viewModel.discard(onDiscard) }, enabled = !current.saving) { Text("Cancel") }
                }
                is RecognitionJobState.Failed -> {
                    Text(current.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { viewModel.discard(onDiscard) }) { Text("Discard") }
                        if (current.retryable) Button(onClick = viewModel::retry) { Text("Retry same job") }
                    }
                    TextButton(onClick = onSettings) { Text("Open Settings") }
                    Text("Retries keep this job's original provider and resource. To use changed settings, discard and choose the image again.")
                }
                is RecognitionJobState.Complete -> CircularProgressIndicator()
            }
            discardError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
