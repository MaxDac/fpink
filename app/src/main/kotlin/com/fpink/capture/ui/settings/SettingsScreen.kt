package com.fpink.capture.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fpink.capture.ui.containerViewModel

// Phase 0: dev-only key entry. Will be replaced by Entra auth + Key Vault.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = containerViewModel { SettingsViewModel(it.settingsStore) },
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Azure OpenAI")

            OutlinedTextField(
                value = state.endpoint,
                onValueChange = viewModel::onEndpointChange,
                label = { Text("Endpoint") },
                placeholder = { Text("https://my-resource.openai.azure.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.deployment,
                onValueChange = viewModel::onDeploymentChange,
                label = { Text("Deployment") },
                placeholder = { Text("gpt-4o") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.apiVersion,
                onValueChange = viewModel::onApiVersionChange,
                label = { Text("API version") },
                placeholder = { Text("2024-12-01-preview") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.isSaving,
                ) { Text(if (state.isSaving) "Saving..." else "Save") }

                OutlinedButton(onClick = viewModel::testConnection) {
                    Text("Test Connection")
                }
            }

            state.message?.let { Text(it) }
        }
    }
}
