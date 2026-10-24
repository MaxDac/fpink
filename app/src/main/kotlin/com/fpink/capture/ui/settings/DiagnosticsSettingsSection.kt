package com.fpink.capture.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fpink.capture.BuildConfig
import com.fpink.capture.R
import com.fpink.capture.ui.containerViewModel
import java.io.File

@Composable
fun DiagnosticsSettingsSection(
    viewModel: DiagnosticsViewModel = containerViewModel {
        DiagnosticsViewModel(it.diagnostics, full = BuildConfig.FLAVOR == "full")
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val writeFailed by viewModel.writeFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val full = BuildConfig.FLAVOR == "full"
    var confirmClear by remember { mutableStateOf(false) }
    Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.diagnostics_help), style = MaterialTheme.typography.bodySmall)
    if (writeFailed) Text(stringResource(R.string.diagnostics_recording_failed))
    TextButton(onClick = viewModel::prepareReport, enabled = !state.busy) {
        Text(stringResource(R.string.diagnostics_report))
    }
    TextButton(onClick = viewModel::prepareExport, enabled = !state.busy) {
        Text(stringResource(R.string.diagnostics_export))
    }
    TextButton(onClick = { confirmClear = true }, enabled = !state.busy) {
        Text(stringResource(R.string.diagnostics_clear))
    }
    state.notice?.let { notice ->
        Text(stringResource(when (notice) {
            DiagnosticsNotice.UNAVAILABLE -> R.string.diagnostics_unavailable
            DiagnosticsNotice.CLEARED -> R.string.diagnostics_cleared
            DiagnosticsNotice.HANDOFF_FAILED -> R.string.diagnostics_handoff_failed
        }))
    }
    state.draft?.let { draft ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPreview,
            title = { Text(stringResource(R.string.diagnostics_report)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(if (full) R.string.diagnostics_private_warning else R.string.diagnostics_public_warning))
                    Text(stringResource(R.string.diagnostics_browser_warning))
                    Text(draft.body)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissPreview()
                    launchDiagnostics(context, Intent(Intent.ACTION_VIEW, Uri.parse(draft.url)), viewModel::handoffFailed)
                }) { Text(stringResource(R.string.diagnostics_open_draft)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPreview) { Text(stringResource(R.string.diagnostics_cancel)) }
            },
        )
    }
    state.export?.let { file ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPreview,
            title = { Text(stringResource(R.string.diagnostics_export)) },
            text = { Text(stringResource(R.string.diagnostics_export_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissPreview()
                    shareDiagnostics(context, file, viewModel::handoffFailed)
                }) { Text(stringResource(R.string.diagnostics_share)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPreview) { Text(stringResource(R.string.diagnostics_cancel)) }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.diagnostics_clear)) },
            text = { Text(stringResource(R.string.diagnostics_clear_warning)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; viewModel.clear() }) {
                    Text(stringResource(R.string.diagnostics_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.diagnostics_cancel)) }
            },
        )
    }
}

internal fun diagnosticShareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("FPInk diagnostics", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun shareDiagnostics(context: Context, file: File, failed: () -> Unit) {
    try {
        val chooser = Intent.createChooser(diagnosticShareIntent(context, file), context.getString(R.string.diagnostics_share))
        launchDiagnostics(context, chooser, failed)
    } catch (_: IllegalArgumentException) {
        failed()
    } catch (_: SecurityException) {
        failed()
    }
}

private fun launchDiagnostics(context: Context, intent: Intent, failed: () -> Unit) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        failed()
    } catch (_: SecurityException) {
        failed()
    }
}
