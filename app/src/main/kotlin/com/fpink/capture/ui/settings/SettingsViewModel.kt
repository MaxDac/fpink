package com.fpink.capture.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.SettingsStore
import com.fpink.core.ai.RecognitionProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * This (FOSS) build exposes the public offline recognition providers only. Credential storage UI
 * and connection tests remain private-overlay responsibilities.
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val selectedProvider: RecognitionProviderId = RecognitionProviderId.PADDLE,
    val recognitionProviders: List<RecognitionProviderOption> = publicRecognitionProviders(),
    val zettelkastenEnabled: Boolean = false,
    val message: String? = null,
)

data class RecognitionProviderOption(
    val id: RecognitionProviderId,
    val label: String,
    val description: String,
    val readiness: String = "Checking model status...",
)

fun publicRecognitionProviders(): List<RecognitionProviderOption> = listOf(
    RecognitionProviderOption(
        RecognitionProviderId.PADDLE,
        "PaddleOCR",
        "Offline default for printed and neat handwritten English. Requires ARM64.",
    ),
    RecognitionProviderOption(
        RecognitionProviderId.KRAKEN,
        "Kraken OCR",
        "Offline, cursive-focused Kraken-compatible ONNX provider.",
    ),
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val readinessChecks: Map<RecognitionProviderId, suspend () -> Result<Unit>> = emptyMap(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val zettelkastenEnabled = settingsStore.zettelkastenEnabled.first()
                val provider = settingsStore.storedSettings.first().settings.provider
                _uiState.update {
                    it.copy(
                        loading = false,
                        selectedProvider = provider,
                        recognitionProviders = publicRecognitionProviders().select(provider),
                        zettelkastenEnabled = zettelkastenEnabled,
                    )
                }
                refreshReadiness()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, message = "Settings could not be loaded.") }
            }
        }
        viewModelScope.launch {
            try {
                settingsStore.storedSettings.collect { stored ->
                    _uiState.update {
                        it.copy(
                            selectedProvider = stored.settings.provider,
                            recognitionProviders = it.recognitionProviders.select(stored.settings.provider),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "Recognition settings could not be loaded.") }
            }
        }
    }

    fun selectProvider(provider: RecognitionProviderId) {
        _uiState.update {
            it.copy(
                selectedProvider = provider,
                recognitionProviders = it.recognitionProviders.select(provider),
                message = null,
            )
        }
        viewModelScope.launch {
            try {
                settingsStore.save(provider, removeConfig = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "Could not save the recognition provider.") }
            }
        }
    }

    fun setZettelkastenEnabled(enabled: Boolean) {
        _uiState.update { it.copy(zettelkastenEnabled = enabled) }
        viewModelScope.launch {
            try {
                settingsStore.saveZettelkastenEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(zettelkastenEnabled = !enabled, message = "Could not save the beta feature toggle.") }
            }
        }
    }

    private fun refreshReadiness() {
        publicRecognitionProviders().forEach { option ->
            val check = readinessChecks[option.id] ?: return@forEach
            viewModelScope.launch {
                val status = withContext(Dispatchers.Default) {
                    check().fold(
                        onSuccess = { "Ready" },
                        onFailure = { error -> error.message ?: "Unavailable" },
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        recognitionProviders = state.recognitionProviders.map {
                            if (it.id == option.id) it.copy(readiness = status) else it
                        },
                    )
                }
            }
        }
    }

    private fun List<RecognitionProviderOption>.select(provider: RecognitionProviderId) =
        map { it.copy(readiness = it.readiness) }.ifEmpty { publicRecognitionProviders() }
}
