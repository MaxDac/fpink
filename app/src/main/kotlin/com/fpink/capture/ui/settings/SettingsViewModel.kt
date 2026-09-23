package com.fpink.capture.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * This (FOSS) build's Settings page only exposes Appearance and Zettelkasten. Recognition is
 * always PaddleOCR here: there is no provider selection, credential storage UI, or connection
 * test — those only exist in the private `full`-flavor overlay's own view model.
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val zettelkastenEnabled: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(private val settingsStore: SettingsStore) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val zettelkastenEnabled = settingsStore.zettelkastenEnabled.first()
                _uiState.update { it.copy(loading = false, zettelkastenEnabled = zettelkastenEnabled) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, message = "Settings could not be loaded.") }
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
}
