package com.fpink.capture.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fpink.capture.ui.containerViewModel
import com.fpink.capture.R
import com.fpink.capture.data.ThemeMode
import com.fpink.capture.ui.components.ActionIconButton
import com.fpink.capture.ui.theme.ThemeUiState
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.recognition.paddle.PaddleOcrProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    appearance: ThemeUiState,
    onThemeSelected: (ThemeMode) -> Unit,
    viewModel: SettingsViewModel = containerViewModel {
        SettingsViewModel(it.settingsStore, it::testAzureConnection) { PaddleOcrProvider.readiness(it.appContext) }
    },
    onZettelkastenConfigureClick: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { ActionIconButton(R.drawable.ic_back, R.string.back, onClick = onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppearanceSettings(appearance, onThemeSelected)
            HorizontalDivider()
            Text(stringResource(R.string.recognition_settings), style = MaterialTheme.typography.titleMedium)
            Text("Choose the provider explicitly. There is no automatic failover. Notes and ink-colour processing stay on this device.")
            RecognitionProviderId.entries.forEach { provider ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.provider == provider,
                        onClick = { viewModel.selectProvider(provider) },
                        enabled = !state.loading && !state.busy,
                    )
                    Text(if (provider == RecognitionProviderId.PADDLE) "PaddleOCR — offline (default)" else "Azure Document Intelligence Read — online")
                }
            }
            Text("PaddleOCR PP-OCRv5 mobile · English. Italian is not supported by this offline checkpoint; handwriting accuracy varies.")
            Text("Bundled version: ${PaddleOcrProvider.MODEL_VERSION}")
            Text("Offline PaddleOCR requires ARM64. On other supported CPU architectures, choose Azure explicitly to recognize images.")
            Text(state.modelStatus)
            Text("Azure Read supports English and Italian with language auto-detection. Selecting Azure and confirming an image uploads it to your own configured resource. Azure usage may be billed.")
            Text("Azure resource configuration is independent of the provider selection. Old endpoint/key settings are not reused.")
            OutlinedTextField(
                value = state.endpoint,
                onValueChange = viewModel::onEndpointChange,
                label = { Text("Document Intelligence HTTPS endpoint") },
                placeholder = { Text("https://your-resource.cognitiveservices.azure.com") },
                enabled = !state.loading && !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Text(
                when {
                    state.removeKey -> "Azure key will be removed when you save."
                    state.hasStoredKey -> "A key is stored using Android Keystore encryption. Leave the replacement empty to keep it."
                    else -> "No Azure key is stored."
                },
            )
            state.keyError?.let { Text(it) }
            OutlinedTextField(
                value = state.replacementKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text(if (state.hasStoredKey) "Replace API key" else "API key") },
                enabled = !state.loading && !state.busy,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.hasStoredKey) {
                TextButton(onClick = viewModel::removeKey, enabled = !state.busy && !state.loading) { Text("Remove saved Azure key") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::save, enabled = !state.loading && !state.busy) { Text("Save settings") }
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.loading && !state.busy && state.provider == RecognitionProviderId.AZURE,
                ) { Text("Test Azure access") }
            }
            Text("The explicit access test calls Azure without uploading an image. It does not prove image-analysis permission or available quota.")
            state.message?.let { Text(it) }
            HorizontalDivider()
            Text("Beta features", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Zettelkasten organisation (beta)")
                    Text(
                        "Group notes into Fleeting, Literature and Permanent sections, matched automatically by ink colour.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.zettelkastenEnabled,
                    onCheckedChange = viewModel::setZettelkastenEnabled,
                    enabled = !state.loading,
                )
            }
            if (state.zettelkastenEnabled) {
                TextButton(onClick = onZettelkastenConfigureClick) { Text("Configure Zettelkasten categories") }
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
