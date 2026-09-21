package com.fpink.capture.ui.capture

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.CropRect
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
    val cameraCropFile: File? = null,
    val cropRect: CropRect = CropRect.Full,
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
    private val _uiState = MutableStateFlow(
        CaptureUiState(
            cameraChosen = savedState.get<Boolean>("cameraChosen") == true,
            cropRect = restoredCropRect(),
        ),
    )
    val uiState = _uiState.asStateFlow()
    private var operation: Job? = null
    private var cleared = false

    init {
        val restoredId = savedState.get<String>("sourceId")
        if (restoredId != null && savedState.get<Boolean>("transferred") != true) {
            val pendingCrop = savedState.get<Boolean>("cameraCropPending") == true
            val cropFile = runCatching { images.cameraCropFile(restoredId) }.getOrNull()
            val previewFile = runCatching { images.previewFile(restoredId) }.getOrNull()
            val cropping = cropFile?.isFile == true && (pendingCrop || previewFile?.isFile != true)
            val file = if (cropping) cropFile else previewFile
            if (file?.isFile == true) {
                savedState["cameraCropPending"] = cropping
                if (!cropping) clearSavedCrop()
                _uiState.update {
                    it.copy(
                        sourceId = restoredId,
                        previewFile = file.takeUnless { cropping },
                        cameraCropFile = file.takeIf { cropping },
                    )
                }
            } else {
                clearSavedSource()
                _uiState.update { it.copy(error = "The previous image is unavailable. Choose it again.") }
                viewModelScope.launch { runCatching { images.discard(restoredId) } }
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
        !it.busy && it.sourceId == null && it.previewFile == null && it.cameraCropFile == null &&
            !it.cameraChosen && it.confirmedSourceId == null
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
        if (_uiState.value.busy || _uiState.value.previewFile != null || _uiState.value.cameraCropFile != null) return false
        _uiState.update { it.copy(busy = true, error = null) }
        return true
    }
    fun error(message: String) = _uiState.update { it.copy(error = message, busy = false) }
    fun pickerCancelled() = error("No image selected. You can choose an image or take a photo.")
    fun newCameraFile(): File = images.newCameraFile()
    fun deleteCameraFile(file: File) = images.deleteCameraFile(file)

    fun importContent(uri: Uri) = importImage { images.importContent(uri) }
    fun importCamera(file: File) {
        if (cleared) {
            images.deleteCameraFile(file)
        } else {
            if (operation?.isActive == true) return
            operation = viewModelScope.launch {
                _uiState.update { it.copy(busy = true, error = null) }
                try {
                    val image = images.stageCameraCrop(file)
                    savedState["sourceId"] = image.sourceId
                    savedState["transferred"] = false
                    savedState["cameraCropPending"] = true
                    saveCropRect(CropRect.Full)
                    _uiState.update {
                        it.copy(
                            sourceId = image.sourceId,
                            previewFile = null,
                            cameraCropFile = image.file,
                            cropRect = CropRect.Full,
                            busy = false,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IllegalArgumentException) {
                    error(failure.message ?: "The captured photo is invalid. Retake it.")
                } catch (_: Exception) {
                    error("Could not prepare the captured photo for cropping. Retake it or check available storage.")
                }
            }
        }
    }

    fun updateCropRect(cropRect: CropRect) {
        if (_uiState.value.busy || _uiState.value.cameraCropFile == null) return
        saveCropRect(cropRect)
        _uiState.update { it.copy(cropRect = cropRect, error = null) }
    }

    fun applyCrop() {
        val sourceId = _uiState.value.sourceId ?: return
        val crop = _uiState.value.cropRect
        if (_uiState.value.busy || _uiState.value.cameraCropFile == null) return
        operation = viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                val image = images.applyCameraCrop(sourceId, crop)
                savedState["cameraCropPending"] = false
                clearSavedCrop()
                _uiState.update {
                    it.copy(cameraCropFile = null, previewFile = image.file, cropRect = CropRect.Full, busy = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                error(failure.message ?: "The crop selection is invalid. Adjust it and retry.")
            } catch (_: Exception) {
                error("Could not apply the crop. Check available storage and retry, or retake the photo.")
            }
        }
    }

    fun retakePhoto() {
        if (_uiState.value.busy || _uiState.value.cameraCropFile == null) return
        operation = viewModelScope.launch {
            try {
                _uiState.value.sourceId?.let { images.discard(it) }
                clearSavedSource()
                _uiState.update {
                    it.copy(
                        sourceId = null,
                        previewFile = null,
                        cameraCropFile = null,
                        cropRect = CropRect.Full,
                        cameraChosen = true,
                        error = null,
                    )
                }
            } catch (_: Exception) {
                error("Could not remove the captured photo. Check free storage and retry.")
            }
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
                savedState["cameraCropPending"] = false
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
                clearSavedSource()
                savedState["cameraChosen"] = false
                _uiState.update {
                    it.copy(
                        sourceId = null,
                        previewFile = null,
                        cameraCropFile = null,
                        cropRect = CropRect.Full,
                        cameraChosen = false,
                        error = null,
                    )
                }
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
                clearSavedSource()
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

    private fun restoredCropRect() = CropRect.restored(
        savedState["cropLeft"],
        savedState["cropTop"],
        savedState["cropRight"],
        savedState["cropBottom"],
    )

    private fun saveCropRect(crop: CropRect) {
        savedState["cropLeft"] = crop.left
        savedState["cropTop"] = crop.top
        savedState["cropRight"] = crop.right
        savedState["cropBottom"] = crop.bottom
    }

    private fun clearSavedCrop() {
        savedState.remove<Float>("cropLeft")
        savedState.remove<Float>("cropTop")
        savedState.remove<Float>("cropRight")
        savedState.remove<Float>("cropBottom")
    }

    private fun clearSavedSource() {
        savedState.remove<String>("sourceId")
        savedState.remove<Boolean>("cameraCropPending")
        clearSavedCrop()
    }
}
