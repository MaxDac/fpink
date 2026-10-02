package com.fpink.capture.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.RecognitionOption
import com.fpink.capture.data.SettingsStore
import com.fpink.core.ai.RecognitionStrategyId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Default recognition" lists the built-in on-device strategies plus any plugin strategies bundled
 * into this build. Credential storage UI and connection tests remain private-overlay responsibilities.
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val selectedStrategy: RecognitionStrategyId = RecognitionStrategyId.DEFAULT,
    val recognitionStrategies: List<RecognitionStrategyOption> = recognitionStrategyOptions(builtInRecognitionOptions()),
    val zettelkastenEnabled: Boolean = false,
    val message: String? = null,
)

data class RecognitionStrategyOption(
    val id: RecognitionStrategyId,
    val label: String,
    val description: String,
    val readiness: String = "Checking model status...",
)

fun builtInRecognitionOptions(): List<RecognitionOption> = listOf(
    RecognitionOption(RecognitionStrategyId.PRINTED, "Printed", requiresNetwork = false),
    RecognitionOption(RecognitionStrategyId.CURSIVE, "Cursive", requiresNetwork = false),
)

fun recognitionStrategyOptions(options: List<RecognitionOption>): List<RecognitionStrategyOption> = options.map {
    RecognitionStrategyOption(
        id = it.id,
        label = it.label,
        description = when {
            it.id == RecognitionStrategyId.PRINTED -> "On-device PP-OCRv6 for printed and neat text. Requires ARM64."
            it.id == RecognitionStrategyId.CURSIVE -> "On-device PP-OCRv6 detection with a Kraken handwriting recognizer. Requires ARM64."
            it.requiresNetwork -> "Uploads the image to the configured recognition service."
            else -> "Recognition bundled into this build."
        },
        readiness = if (it.id in RecognitionStrategyId.BUILT_IN) "Checking model status..." else "",
    )
}

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val readinessChecks: Map<RecognitionStrategyId, suspend () -> Result<Unit>> = emptyMap(),
    options: List<RecognitionOption> = builtInRecognitionOptions(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState(recognitionStrategies = recognitionStrategyOptions(options)))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val zettelkastenEnabled = settingsStore.zettelkastenEnabled.first()
                val strategy = settingsStore.storedSettings.first().settings.strategy
                _uiState.update {
                    it.copy(loading = false, selectedStrategy = strategy, zettelkastenEnabled = zettelkastenEnabled)
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
                    _uiState.update { it.copy(selectedStrategy = stored.settings.strategy) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "Recognition settings could not be loaded.") }
            }
        }
    }

    fun selectStrategy(strategy: RecognitionStrategyId) {
        _uiState.update { it.copy(selectedStrategy = strategy, message = null) }
        viewModelScope.launch {
            try {
                // Built-in strategies need no config, so drop any stored service credentials;
                // a plugin strategy keeps the config its settings extension stored.
                settingsStore.save(strategy, removeConfig = strategy in RecognitionStrategyId.BUILT_IN)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "Could not save the default recognition.") }
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
        _uiState.value.recognitionStrategies.forEach { option ->
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
                        recognitionStrategies = state.recognitionStrategies.map {
                            if (it.id == option.id) it.copy(readiness = status) else it
                        },
                    )
                }
            }
        }
    }
}