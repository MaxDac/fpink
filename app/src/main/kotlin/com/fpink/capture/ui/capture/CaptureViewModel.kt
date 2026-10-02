package com.fpink.capture.ui.capture

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.CropRect
import com.fpink.capture.data.ImageImportStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.capture.data.RecognitionOption
import com.fpink.capture.data.SettingsStore
import com.fpink.capture.data.StoredRecognitionSettings
import com.fpink.capture.ui.settings.builtInRecognitionOptions
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionStrategyId
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
    val providerLabel: String = recognitionLabel(RecognitionOption(RecognitionStrategyId.DEFAULT, "Cursive", false)),
    val providerAvailable: Boolean = false,
    val confirmedSourceId: String? = null,
    /** Strategies the camera picker offers: the built-ins plus the default when it is a plugin. */
    val strategyOptions: List<RecognitionOption> = emptyList(),
    /** The strategy this capture will use; the camera picker can change it, other sources use the default. */
    val selectedStrategy: RecognitionStrategyId = RecognitionStrategyId.DEFAULT,
    /** True once a photo was taken with the camera, so the picker stays through crop and review. */
    val fromCamera: Boolean = false,
) {
    /** The picker is offered on the camera viewfinder and kept through crop and review. */
    val showStrategyPicker: Boolean get() = fromCamera || (cameraChosen && sourceId == null)
}

internal fun recognitionLabel(option: RecognitionOption): String = when {
    option.id == RecognitionStrategyId.PRINTED -> "Printed · on-device · requires ARM64"
    option.id == RecognitionStrategyId.CURSIVE -> "Cursive · on-device · requires ARM64"
    option.requiresNetwork -> "${option.label} · this image will be uploaded to the selected service"
    else -> option.label
}

class CaptureViewModel(
    private val images: ImageImportStore,
    private val coordinator: RecognitionCoordinator,
    settings: SettingsStore,
    private val savedState: SavedStateHandle,
    private val options: List<RecognitionOption> = builtInRecognitionOptions(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CaptureUiState(
            cameraChosen = savedState.get<Boolean>("cameraChosen") == true,
            cropRect = restoredCropRect(),
            fromCamera = savedState.get<Boolean>("fromCamera") == true,
        ),
    )
    private var stored: StoredRecognitionSettings? = null
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
                settings.storedSettings.collect {
                    stored = it
                    refreshRecognition()
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

    /** Camera only: picks the strategy for this capture without changing the default in Settings. */
    fun selectStrategy(strategy: RecognitionStrategyId) {
        val state = _uiState.value
        if (state.busy || !state.showStrategyPicker || state.strategyOptions.none { it.id == strategy }) return
        savedState["strategy"] = strategy.id
        refreshRecognition()
    }

    private fun refreshRecognition() {
        val current = stored ?: return
        val default = current.settings.strategy
        val choices = options.filter { it.id in RecognitionStrategyId.BUILT_IN || it.id == default }
        _uiState.update { state ->
            val picked = savedState.get<String>("strategy")?.let(::RecognitionStrategyId)
                ?.takeIf { id -> state.showStrategyPicker && choices.any { it.id == id } }
            val strategy = picked ?: default
            val settingsError = when {
                strategy in RecognitionStrategyId.BUILT_IN -> null
                choices.none { it.id == strategy } -> "The default recognition is not available in this build. Choose another in Settings."
                else -> current.keyError ?: if (current.settings.config.isEmpty()) {
                    "Configure the selected recognition service's settings before using this image."
                } else null
            }
            state.copy(
                strategyOptions = choices,
                selectedStrategy = strategy,
                providerAvailable = settingsError == null,
                settingsError = settingsError,
                providerLabel = recognitionLabel(
                    choices.firstOrNull { it.id == strategy } ?: RecognitionOption(strategy, strategy.id, requiresNetwork = true),
                ),
            )
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
        refreshRecognition()
    }
    fun chooseOtherSource() {
        savedState["cameraChosen"] = false
        _uiState.update { it.copy(cameraChosen = false, busy = false) }
        refreshRecognition()
    }
    fun captureStarted(): Boolean {
        if (_uiState.value.busy || _uiState.value.previewFile != null || _uiState.value.cameraCropFile != null) return false
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
            if (operation?.isActive == true) return
            operation = viewModelScope.launch {
                _uiState.update { it.copy(busy = true, error = null) }
                try {
                    val image = images.stageCameraCrop(file)
                    savedState["sourceId"] = image.sourceId
                    savedState["transferred"] = false
                    savedState["cameraCropPending"] = true
                    savedState["fromCamera"] = true
                    saveCropRect(CropRect.Full)
                    _uiState.update {
                        it.copy(
                            sourceId = image.sourceId,
                            previewFile = null,
                            cameraCropFile = image.file,
                            cropRect = CropRect.Full,
                            busy = false,
                            fromCamera = true,
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
                        fromCamera = false,
                        error = null,
                    )
                }
                refreshRecognition()
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
                savedState["fromCamera"] = false
                _uiState.update { it.copy(sourceId = image.sourceId, previewFile = image.file, busy = false, fromCamera = false) }
                refreshRecognition()
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
                savedState.remove<String>("strategy")
                savedState["cameraChosen"] = false
                _uiState.update {
                    it.copy(
                        sourceId = null,
                        previewFile = null,
                        cameraCropFile = null,
                        cropRect = CropRect.Full,
                        cameraChosen = false,
                        fromCamera = false,
                        error = null,
                    )
                }
                refreshRecognition()
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
                val state = _uiState.value
                coordinator.confirm(sourceId, state.selectedStrategy.takeIf { state.showStrategyPicker })
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
        savedState.remove<Boolean>("fromCamera")
        clearSavedCrop()
    }
}
