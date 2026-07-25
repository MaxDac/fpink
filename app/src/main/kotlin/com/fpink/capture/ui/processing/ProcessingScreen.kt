package com.fpink.capture.ui.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProcessingScreen(
    imageUri: String,
    onComplete: (String) -> Unit,
    onDiscard: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Processing...",
            modifier = Modifier.padding(top = 16.dp),
        )
        TextButton(
            onClick = onDiscard,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Discard")
        }
    }
}
