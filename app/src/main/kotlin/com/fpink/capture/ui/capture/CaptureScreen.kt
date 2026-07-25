package com.fpink.capture.ui.capture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onImageCaptured: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Capture") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
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
            Text("Camera Preview")

            Button(
                onClick = { onImageCaptured("placeholder") },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
            ) {
                Text("Shutter")
            }
        }
    }
}
