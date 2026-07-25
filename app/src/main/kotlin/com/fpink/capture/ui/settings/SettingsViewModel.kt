package com.fpink.capture.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.SettingsStore
import com.fpink.core.ai.AiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val endpoint: String = "",
    val deployment: String = "",
    val apiVersion: String = "",
    val apiKey: String = "",
    val isSaving: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.aiConfig.first()?.let { config ->
                _uiState.update {
                    it.copy(
                        endpoint = config.endpoint,
                        deployment = config.deployment,
                        apiVersion = config.apiVersion,
                        apiKey = config.apiKey,
                    )
                }
            }
        }
    }

    fun onEndpointChange(value: String) = _uiState.update { it.copy(endpoint = value, message = null) }
    fun onDeploymentChange(value: String) = _uiState.update { it.copy(deployment = value, message = null) }
    fun onApiVersionChange(value: String) = _uiState.update { it.copy(apiVersion = value, message = null) }
    fun onApiKeyChange(value: String) = _uiState.update { it.copy(apiKey = value, message = null) }

    fun save() {
        val state = _uiState.value
        if (!state.isValid()) {
            _uiState.update { it.copy(message = "All fields are required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, message = null) }
            settingsStore.saveConfig(state.toConfig())
            _uiState.update { it.copy(isSaving = false, message = "Saved") }
        }
    }

    // testConnection() is a stub for Phase 0: it only validates the fields are present.
    // A real reachability check against the Azure OpenAI endpoint comes in a later milestone.
    fun testConnection() {
        val state = _uiState.value
        val message = if (state.isValid()) "Looks good (validation only)" else "All fields are required"
        _uiState.update { it.copy(message = message) }
    }

    private fun SettingsUiState.isValid(): Boolean =
        endpoint.isNotBlank() && deployment.isNotBlank() &&
            apiVersion.isNotBlank() && apiKey.isNotBlank()

    private fun SettingsUiState.toConfig(): AiConfig =
        AiConfig(
            endpoint = endpoint.trim(),
            deployment = deployment.trim(),
            apiVersion = apiVersion.trim(),
            apiKey = apiKey.trim(),
        )
}
