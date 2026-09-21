package com.fpink.capture.ui.capture

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.ImageImportStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.capture.data.SettingsStore
import com.fpink.capture.data.isValidAzureEndpoint
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProviderId
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CaptureUiState(
    val sourceId: String? = null,
    val previewFile: File? = null,
    val cameraChosen: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val settingsError: String? = null,
    val providerLabel: String = "PaddleOCR · offline · English · requires ARM64",
    val providerAvailable: Boolean = false,
    val confirmedSourceId: String? = null,
)

class CaptureViewModel(
    private val images: ImageImportStore,
    private val coordinator: RecognitionCoordinator,
    settings: SettingsStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CaptureUiState(cameraChosen = savedState.get<Boolean>("cameraChosen") == true))
    val uiState = _uiState.asStateFlow()
    private var operation: Job? = null
    private var cleared = false

    init {
        val restoredId = savedState.get<String>("sourceId")
        if (restoredId != null && savedState.get<Boolean>("transferred") != true) {
            val file = runCatching { images.previewFile(restoredId) }.getOrNull()
            if (file?.isFile == true) {
                _uiState.update { it.copy(sourceId = restoredId, previewFile = file) }
            } else {
                savedState.remove<String>("sourceId")
                _uiState.update { it.copy(error = "The previous image is unavailable. Choose it again.") }
            }
        }
        viewModelScope.launch {
            images.cleanExpired()
        }
        viewModelScope.launch {
            try {
                settings.storedSettings.collect { stored ->
                    val selection = stored.settings
                    val settingsError = if (selection.provider == RecognitionProviderId.PADDLE) null else {
                        stored.keyError ?: if (
                            selection.azure.apiKey.isBlank() || !isValidAzureEndpoint(selection.azure.endpoint)
                        ) "Configure your Azure endpoint and API key in Settings before using this image." else null
                    }
                    _uiState.update {
                        it.copy(
                            providerAvailable = settingsError == null,
                            settingsError = settingsError,
                            providerLabel = when (selection.provider) {
                                RecognitionProviderId.PADDLE -> "PaddleOCR · offline · English · requires ARM64"
                                RecognitionProviderId.AZURE -> "Azure Read · English / Italian · this image will be uploaded to ${selection.azure.endpoint}"
                            },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(providerAvailable = false, settingsError = "Recognition settings are unavailable. Open Settings before using this image.")
                }
            }
        }
    }

    fun consumeCameraEntry(requested: Boolean): Boolean {
        if (savedState.get<Boolean>("cameraEntryConsumed") == true) return false
        savedState["cameraEntryConsumed"] = true
        return requested && canChooseCamera() && _uiState.value.error == null
    }

    fun canChooseCamera(): Boolean = _uiState.value.let {
        !it.busy && it.sourceId == null && it.previewFile == null && !it.cameraChosen && it.confirmedSourceId == null
    }

    fun chooseCamera() {
        if (!canChooseCamera()) return
        savedState["cameraChosen"] = true
        _uiState.update { it.copy(cameraChosen = true, error = null) }
    }
    fun chooseOtherSource() {
        savedState["cameraChosen"] = false
        _uiState.update { it.copy(cameraChosen = false, busy = false) }
    }
    fun captureStarted(): Boolean {
        if (_uiState.value.busy || _uiState.value.previewFile != null) return false
        _uiState.update { it.copy(busy = true, error = null) }
        return true
    }
    fun error(message: String) = _uiState.update { it.copy(error = message, busy = false) }
    fun newCameraFile(): File = images.newCameraFile()
    fun deleteCameraFile(file: File) = images.deleteCameraFile(file)

    fun importCamera(file: File) {
        if (cleared) {
            images.deleteCameraFile(file)
        } else {
            importImage { images.importCamera(file) }
        }
    }

    private fun importImage(import: suspend () -> com.fpink.capture.data.StagedImage) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                val previous = _uiState.value.sourceId
                val image = import()
                savedState["sourceId"] = image.sourceId
                savedState["transferred"] = false
                _uiState.update { it.copy(sourceId = image.sourceId, previewFile = image.file, busy = false) }
                if (previous != null) images.discard(previous)
            } catch (cancelled: CancellationException) {
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

    fun chooseAnother() {
        if (_uiState.value.busy) return
        operation = viewModelScope.launch {
            try {
                _uiState.value.sourceId?.let { images.discard(it) }
                savedState.remove<String>("sourceId")
                savedState["cameraChosen"] = false
                _uiState.update { it.copy(sourceId = null, previewFile = null, cameraChosen = false, error = null) }
            } catch (_: Exception) {
                error("Could not remove the previous staged image. Check free storage and retry.")
            }
        }
    }

    fun confirm() {
        val sourceId = _uiState.value.sourceId ?: return
        if (_uiState.value.busy || !_uiState.value.providerAvailable) return
        operation = viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                coordinator.confirm(sourceId)
                savedState["transferred"] = true
                _uiState.update { it.copy(confirmedSourceId = sourceId, busy = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RecognitionError.Configuration) {
                error(failure.message ?: "Check recognition settings.")
            } catch (_: Exception) {
                error("Could not confirm the image and settings. Check available storage and try again.")
            }
        }
    }

    fun leave(onComplete: () -> Unit) {
        viewModelScope.launch {
            operation?.cancelAndJoin()
            try {
                _uiState.value.sourceId?.let { images.discard(it) }
                savedState.remove<String>("sourceId")
                onComplete()
            } catch (_: Exception) {
                error("Could not remove the staged image. Try again.")
            }
        }
    }

    override fun onCleared() {
        cleared = true
        if (savedState.get<Boolean>("transferred") != true) {
            _uiState.value.sourceId?.let { source ->
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { images.discard(source) } }
            }
        }
    }
}
