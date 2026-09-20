package com.fpink.capture.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.ThemeMode
import com.fpink.capture.data.ThemeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ThemeError { READ, WRITE }

data class ThemeUiState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: ThemeError? = null,
)

class ThemeViewModel(private val settings: ThemeSettings) : ViewModel() {
    private val _uiState = MutableStateFlow(ThemeUiState())
    val uiState = _uiState.asStateFlow()
    private data class Selection(val mode: ThemeMode, val revision: Long)
    private val selections = Channel<Selection>(Channel.CONFLATED)
    private var revision = 0L
    private var persisted = ThemeMode.SYSTEM

    init {
        load()
        viewModelScope.launch {
            for (selection in selections) {
                try {
                    settings.saveThemeMode(selection.mode)
                    persisted = selection.mode
                    if (selection.revision == revision) {
                        _uiState.update { it.copy(saving = false, error = null) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (selection.revision == revision) {
                        _uiState.update { it.copy(mode = persisted, saving = false, error = ThemeError.WRITE) }
                    }
                }
            }
        }
    }

    fun retryLoad() {
        if (_uiState.value.error == ThemeError.READ) load()
    }

    private fun load() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                persisted = settings.themeMode.first()
                _uiState.value = ThemeUiState(mode = persisted, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, error = ThemeError.READ) }
            }
        }
    }

    fun select(mode: ThemeMode) {
        if (_uiState.value.loading || _uiState.value.error == ThemeError.READ) return
        revision++
        _uiState.update { it.copy(mode = mode, saving = true, error = null) }
        check(selections.trySend(Selection(mode, revision)).isSuccess)
    }
}
