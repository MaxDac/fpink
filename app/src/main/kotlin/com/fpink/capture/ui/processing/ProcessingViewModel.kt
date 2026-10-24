package com.fpink.capture.ui.processing

import com.fpink.capture.diagnostics.DiagnosticOperation
import com.fpink.capture.diagnostics.DiagnosticReporter
import com.fpink.capture.diagnostics.NoOpDiagnosticReporter
import com.fpink.capture.diagnostics.classifyDiagnostic

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.ImageImportStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.capture.data.RecognitionJobState
import com.fpink.capture.data.CancellationPersistenceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProcessingViewModel(
    private val coordinator: RecognitionCoordinator,
    images: ImageImportStore,
    savedState: SavedStateHandle,
    private val reporter: DiagnosticReporter = NoOpDiagnosticReporter,
) : ViewModel() {
    val sourceId: String = savedState.get<String>("sourceId").orEmpty()
    val previewFile = try {
        images.previewFile(sourceId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        null
    } catch (error: Exception) {
        reporter.report(DiagnosticOperation.STORAGE_LOAD, classifyDiagnostic(error))
        null
    }
    val uiState = coordinator.state(sourceId)
    private val _discardError = MutableStateFlow<String?>(null)
    val discardError = _discardError.asStateFlow()

    init {
        coordinator.start(sourceId)
    }

    fun retry() = coordinator.start(sourceId, retry = true)
    fun release() = coordinator.release(sourceId)

    fun discard(onDiscarded: () -> Unit) {
        viewModelScope.launch {
            try {
                if (coordinator.discard(sourceId)) onDiscarded()
                else _discardError.value = "Saving has already started. Wait for the local save to finish."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: CancellationPersistenceException) {
                _discardError.value = "Cancellation could not be recorded safely. This job may resume after an app restart. Retry Discard before leaving."
            } catch (_: Exception) {
                _discardError.value = "Cancellation was saved, but the staged image could not be removed. It will not be processed again. Retry Discard."
            }
        }
    }
}
