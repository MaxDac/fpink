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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fpink.capture.ui.containerViewModel
import com.fpink.capture.R
import com.fpink.capture.data.ThemeMode
import com.fpink.capture.ui.components.ActionIconButton
import com.fpink.capture.ui.theme.ThemeUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    appearance: ThemeUiState,
    onThemeSelected: (ThemeMode) -> Unit,
    viewModel: SettingsViewModel = containerViewModel { SettingsViewModel(it.settingsStore) },
    onZettelkastenConfigureClick: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settingsExtension = remember { SettingsExtensionRegistry.find() }
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
            state.message?.let { Text(it) }
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
            settingsExtension?.let {
                HorizontalDivider()
                it.Content(Modifier.fillMaxWidth())
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
