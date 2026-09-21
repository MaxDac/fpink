package com.fpink.capture.ui.notes

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.ImageImportStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SourceImportUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val readySourceId: String? = null,
    val clipboardPasteAvailable: Boolean = false,
)

/**
 * Backs the notes list's Gallery/File/Paste contextual menu: privately stages the image through
 * [ImageImportStore] and exposes the resulting source id so the caller can navigate directly to
 * the prepared-image review flow, skipping the removed dedicated chooser screen.
 */
class SourceImportViewModel(
    private val images: ImageImportStore,
    private val clipboard: ClipboardImageReader? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SourceImportUiState())
    val uiState = _uiState.asStateFlow()
    private var operation: Job? = null

    fun importContent(uri: Uri) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            importStaged(uri)
        }
    }

    fun refreshClipboardAvailability() {
        val reader = clipboard ?: return
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) { reader.currentImage() is ClipboardImageResult.Available }
            _uiState.update { it.copy(clipboardPasteAvailable = available) }
        }
    }

    fun importClipboard() {
        val reader = clipboard ?: return error(AndroidClipboardImageReader.NO_IMAGE)
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            when (val result = withContext(Dispatchers.IO) { reader.currentImage() }) {
                is ClipboardImageResult.Available -> importStaged(result.uri)
                is ClipboardImageResult.Unavailable -> error(result.message)
            }
            refreshClipboardAvailability()
        }
    }

    fun pickerCancelled() = error("No image selected. Choose Gallery or File to try again.")

    fun error(message: String) = _uiState.update { it.copy(busy = false, error = message) }

    fun consumeReadySourceId() = _uiState.update { it.copy(readySourceId = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    private suspend fun importStaged(uri: Uri) {
        try {
            val staged = images.importContent(uri)
            _uiState.update { it.copy(busy = false, readySourceId = staged.sourceId) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                _uiState.update {
                    it.copy(busy = false, error = "Image import was cancelled. Copy the image and try again.")
                }
            }
            throw cancelled
        } catch (_: SecurityException) {
            error("The image provider denied access. Download the image locally and choose it again.")
        } catch (failure: IllegalArgumentException) {
            error(failure.message ?: "Choose a supported image no larger than 24 MB.")
        } catch (_: Exception) {
            error("Could not import this image. It may be unavailable, corrupt, unsupported, or storage may be full.")
        }
    }
}
