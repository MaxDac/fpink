package com.fpink.capture.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fpink.capture.R
import com.fpink.capture.ui.components.ActionIconButton
import com.fpink.capture.ui.components.InkColorSwatch
import com.fpink.capture.ui.components.STANDARD_INK_COLORS
import com.fpink.capture.ui.containerViewModel
import com.fpink.core.ai.inkColorName
import com.fpink.core.model.ZettelkastenCategory

fun ZettelkastenCategory.displayName(): String = when (this) {
    ZettelkastenCategory.FLEETING -> "Fleeting Notes"
    ZettelkastenCategory.LITERATURE -> "Literature Notes"
    ZettelkastenCategory.PERMANENT -> "Permanent Notes"
}

fun ZettelkastenCategory.description(): String = when (this) {
    ZettelkastenCategory.FLEETING -> "Quick, unprocessed captures. The default for notes whose colour is not configured or does not match."
    ZettelkastenCategory.LITERATURE -> "Notes tied to an external source you are reading or reviewing."
    ZettelkastenCategory.PERMANENT -> "Fully worked, atomic notes filed for the long term."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZettelkastenSettingsScreen(
    onBack: () -> Unit,
    viewModel: ZettelkastenSettingsViewModel = containerViewModel { ZettelkastenSettingsViewModel(it.settingsStore) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zettelkasten categories") },
                navigationIcon = { ActionIconButton(R.drawable.ic_back, R.string.back, onClick = onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Assign colours to each fixed Zettelkasten category. New captures are matched to the " +
                    "closest configured colour automatically, with a small tolerance for photo/scan " +
                    "variation. A colour can belong to only one category. Unmatched captures start as " +
                    "Fleeting Notes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ZettelkastenCategory.entries.forEach { category ->
                CategoryColorCard(
                    category = category,
                    assignedColors = state.categoryColors[category].orEmpty(),
                    otherAssignedColors = state.assignedColors - state.categoryColors[category].orEmpty().toSet(),
                    customHexInput = state.customHexInputs[category].orEmpty(),
                    enabled = !state.busy && !state.loading,
                    onToggleSwatch = { hex -> viewModel.toggleSwatch(category, hex) },
                    onCustomHexChange = { value -> viewModel.onCustomHexChange(category, value) },
                    onAddCustomColor = { viewModel.addCustomColor(category) },
                    onRemoveColor = { hex -> viewModel.removeColor(category, hex) },
                )
            }
            Button(onClick = viewModel::save, enabled = !state.busy && !state.loading) { Text("Save categories") }
            state.message?.let { Text(it) }
        }
    }
}

@Composable
private fun CategoryColorCard(
    category: ZettelkastenCategory,
    assignedColors: List<String>,
    otherAssignedColors: Set<String>,
    customHexInput: String,
    enabled: Boolean,
    onToggleSwatch: (String) -> Unit,
    onCustomHexChange: (String) -> Unit,
    onAddCustomColor: () -> Unit,
    onRemoveColor: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(category.displayName(), style = MaterialTheme.typography.titleMedium)
            Text(category.description(), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                STANDARD_INK_COLORS.forEach { hex ->
                    FilterChip(
                        selected = hex in assignedColors,
                        enabled = enabled && hex !in otherAssignedColors,
                        onClick = { onToggleSwatch(hex) },
                        leadingIcon = { InkColorSwatch(hex, size = 16.dp) },
                        label = { Text(inkColorName(hex)) },
                    )
                }
            }
            if (assignedColors.any { it !in STANDARD_INK_COLORS }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    assignedColors.filter { it !in STANDARD_INK_COLORS }.forEach { hex ->
                        FilterChip(
                            selected = true,
                            enabled = enabled,
                            onClick = { onRemoveColor(hex) },
                            leadingIcon = { InkColorSwatch(hex, size = 16.dp) },
                            label = { Text(hex) },
                        )
                    }
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customHexInput,
                    onValueChange = onCustomHexChange,
                    label = { Text("Custom colour (#RRGGBB)") },
                    enabled = enabled,
                    singleLine = true,
                    leadingIcon = { InkColorSwatch(customHexInput.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }, size = 16.dp) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = onAddCustomColor, enabled = enabled && customHexInput.isNotBlank()) { Text("Add colour") }
        }
    }
}
