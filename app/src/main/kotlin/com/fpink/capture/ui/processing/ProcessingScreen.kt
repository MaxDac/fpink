package com.fpink.capture.ui.processing

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.fpink.capture.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    imageUri: String,
    onComplete: (String) -> Unit,
    onDiscard: () -> Unit,
    viewModel: ProcessingViewModel = containerViewModel {
        ProcessingViewModel(
            aiClient = it.aiClient,
            noteRepository = it.noteRepository,
            settingsStore = it.settingsStore,
            context = it.appContext,
        )
    },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(imageUri) {
        viewModel.processImage(imageUri)
    }

    LaunchedEffect(uiState) {
        (uiState as? ProcessingUiState.Success)?.let { onComplete(it.noteId) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Processing") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val state = uiState) {
                is ProcessingUiState.Loading -> {
                    AsyncImage(
                        model = Uri.parse(imageUri),
                        contentDescription = "Captured page",
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(24.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Analysing handwriting…")
                }

                is ProcessingUiState.Error -> {
                    Text(
                        text = "⚠️",
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = onDiscard) {
                            Text("Discard")
                        }
                        if (state.isRetryable) {
                            Button(onClick = { viewModel.processImage(imageUri) }) {
                                Text("Retry")
                            }
                        }
                    }
                }

                is ProcessingUiState.Success -> {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
