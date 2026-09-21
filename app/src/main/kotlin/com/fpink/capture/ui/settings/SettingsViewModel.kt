package com.fpink.capture.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.SettingsStore
import com.fpink.capture.data.isValidAzureEndpoint
import com.fpink.core.ai.AzureReadConfig
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.ai.RecognitionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val provider: RecognitionProviderId = RecognitionProviderId.PADDLE,
    val endpoint: String = "",
    val replacementKey: String = "",
    val hasStoredKey: Boolean = false,
    val removeKey: Boolean = false,
    val keyError: String? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val modelStatus: String = "Checking bundled model readiness…",
    val message: String? = null,
    val zettelkastenEnabled: Boolean = false,
) {
    override fun toString(): String = "SettingsUiState(provider=$provider, replacementKey=[redacted])"
}

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val testAzure: suspend (RecognitionSettings) -> Result<Unit>,
    private val modelReadiness: () -> Result<Unit>,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, message = "Settings could not be loaded. No provider was changed.") }
            }
        }
        viewModelScope.launch {
            val readiness = withContext(Dispatchers.IO) { modelReadiness() }
            _uiState.update {
                it.copy(
                    modelStatus = readiness.fold(
                        onSuccess = { "Bundled PP-OCRv5 mobile models and native runtime are ready." },
                        onFailure = { error ->
                            when (error) {
                                is RecognitionError.UnsupportedDevice -> error.message ?: "The bundled PaddleOCR native runtime cannot run on this device."
                                else -> "PaddleOCR is not ready: a bundled model or native runtime is missing, corrupt or incompatible. Offline recognition is unavailable in this build."
                            }
                        },
                    ),
                )
            }
        }
    }

    private suspend fun reload() {
        val stored = settingsStore.storedSettings.first()
        val zettelkastenEnabled = settingsStore.zettelkastenEnabled.first()
        _uiState.update {
            it.copy(
                provider = stored.settings.provider,
                endpoint = stored.settings.azure.endpoint,
                replacementKey = "",
                hasStoredKey = stored.hasStoredKey,
                keyError = stored.keyError,
                removeKey = false,
                loading = false,
                zettelkastenEnabled = zettelkastenEnabled,
            )
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

    fun selectProvider(provider: RecognitionProviderId) =
        _uiState.update { it.copy(provider = provider, message = null) }

    fun onEndpointChange(value: String) = _uiState.update { it.copy(endpoint = value, message = null) }
    fun onApiKeyChange(value: String) =
        _uiState.update { it.copy(replacementKey = value, removeKey = false, message = null) }

    fun removeKey() = _uiState.update {
        it.copy(
            removeKey = true, replacementKey = "", provider = RecognitionProviderId.PADDLE,
            message = "Save to remove the Azure key and select offline recognition.",
        )
    }

    fun save() {
        if (_uiState.value.busy || _uiState.value.loading) return
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            try {
                settingsStore.save(
                    state.provider, state.endpoint,
                    state.replacementKey.takeIf { it.isNotBlank() }, state.removeKey,
                )
                reload()
                _uiState.update { it.copy(message = "Saved. Existing processing jobs keep their original provider and resource.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RecognitionError.Configuration) {
                _uiState.update { it.copy(message = error.message ?: "Check the endpoint and key.") }
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "Could not save settings securely. Nothing was changed.") }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun testConnection() {
        val state = _uiState.value
        if (state.busy || state.loading || state.provider != RecognitionProviderId.AZURE) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            try {
                val stored = settingsStore.storedSettings.first()
                val key = state.replacementKey.trim().ifEmpty {
                    if (state.removeKey) "" else stored.settings.azure.apiKey
                }
                if (!isValidAzureEndpoint(state.endpoint) || key.isBlank()) {
                    throw RecognitionError.Configuration("Enter an HTTPS resource endpoint and an API key before testing.")
                }
                testAzure(RecognitionSettings(RecognitionProviderId.AZURE, AzureReadConfig(state.endpoint.trim(), key))).getOrThrow()
                _uiState.update {
                    it.copy(
                        message = "Azure accepted the key for the resource/model probe. No image was uploaded. This checks model access, not image-analysis permission or available quota. Unsaved settings remain unsaved.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RecognitionError.Configuration) {
                _uiState.update { it.copy(message = error.message ?: "Check the endpoint and key.") }
            } catch (_: RecognitionError.Authentication) {
                _uiState.update { it.copy(message = "Azure rejected this key or resource access. Check the endpoint and key.") }
            } catch (_: RecognitionError.RateLimited) {
                _uiState.update { it.copy(message = "Azure rate limited the probe. Wait and try again.") }
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "The Azure probe failed. Check the endpoint, network and resource provisioning. No image was uploaded.") }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }
}
