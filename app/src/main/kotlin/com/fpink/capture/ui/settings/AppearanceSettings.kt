package com.fpink.capture.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fpink.capture.R
import com.fpink.capture.data.ThemeMode
import com.fpink.capture.ui.theme.ThemeError
import com.fpink.capture.ui.theme.ThemeUiState

@Composable
fun AppearanceSettings(state: ThemeUiState, onSelected: (ThemeMode) -> Unit) {
    Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.appearance_help), style = MaterialTheme.typography.bodyMedium)
    Column(Modifier.selectableGroup()) {
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = state.mode == mode,
                    enabled = !state.loading && state.error != ThemeError.READ,
                    role = Role.RadioButton,
                    onClick = { onSelected(mode) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.mode == mode,
                    onClick = null,
                    modifier = Modifier.padding(12.dp),
                )
                Text(stringResource(when (mode) {
                    ThemeMode.SYSTEM -> R.string.theme_system
                    ThemeMode.LIGHT -> R.string.theme_light
                    ThemeMode.DARK -> R.string.theme_dark
                }))
            }
        }
    }
    if (state.saving) Text(stringResource(R.string.appearance_saving))
    if (state.error == ThemeError.WRITE) {
        Text(
            stringResource(R.string.appearance_write_error),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
