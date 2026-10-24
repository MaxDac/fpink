package com.fpink.capture.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.diagnostics.DiagnosticDraft
import com.fpink.capture.diagnostics.DiagnosticDraftBuilder
import com.fpink.capture.diagnostics.DiagnosticStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DiagnosticsNotice { UNAVAILABLE, CLEARED, HANDOFF_FAILED }

data class DiagnosticsUiState(
    val busy: Boolean = false,
    val draft: DiagnosticDraft? = null,
    val export: File? = null,
    val notice: DiagnosticsNotice? = null,
)

class DiagnosticsViewModel(
    private val store: DiagnosticStore,
    private val full: Boolean,
) : ViewModel() {
    private val state = MutableStateFlow(DiagnosticsUiState())
    val uiState = state.asStateFlow()
    val writeFailed = store.writeFailed

    fun prepareReport() = perform {
        val draft = DiagnosticDraftBuilder.build(store.snapshot(), full)
        state.update { it.copy(draft = draft) }
    }

    fun prepareExport() = perform {
        val file = store.export()
        state.update { it.copy(export = file) }
    }

    fun clear() = perform {
        state.update { it.copy(draft = null, export = null) }
        store.clear()
        state.update { it.copy(notice = DiagnosticsNotice.CLEARED) }
    }

    fun dismissPreview() {
        state.update { it.copy(draft = null, export = null) }
    }

    fun handoffFailed() {
        state.update { it.copy(notice = DiagnosticsNotice.HANDOFF_FAILED) }
    }

    private fun perform(action: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, notice = null, draft = null, export = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.update { it.copy(notice = DiagnosticsNotice.UNAVAILABLE) }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }
}
