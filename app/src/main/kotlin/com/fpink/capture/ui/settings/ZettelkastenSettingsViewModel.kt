package com.fpink.capture.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fpink.capture.data.SettingsStore
import com.fpink.core.model.ZettelkastenCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

data class ZettelkastenSettingsUiState(
    val loading: Boolean = true,
    val categoryColors: Map<ZettelkastenCategory, List<String>> = emptyMap(),
    val customHexInputs: Map<ZettelkastenCategory, String> = emptyMap(),
    val busy: Boolean = false,
    val message: String? = null,
) {
    /** Every colour already assigned to some category, so the picker can flag exclusivity conflicts. */
    val assignedColors: Set<String> get() = categoryColors.values.flatten().toSet()

    fun categoryOf(hex: String): ZettelkastenCategory? =
        categoryColors.entries.firstOrNull { hex in it.value }?.key
}

class ZettelkastenSettingsViewModel(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ZettelkastenSettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val stored = settingsStore.zettelkastenCategoryColors.first()
                _uiState.update { it.copy(categoryColors = stored, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(loading = false, message = "Colours could not be loaded. Nothing was changed.") }
            }
        }
    }

    /** Toggles one of the standard swatches for [category]: removes it if already assigned there,
     * assigns it if unassigned, and is a no-op (with a message) if another category holds it. */
    fun toggleSwatch(category: ZettelkastenCategory, hex: String) {
        val state = _uiState.value
        val owner = state.categoryOf(hex)
        when {
            owner == category -> removeColor(category, hex)
            owner != null -> _uiState.update {
                it.copy(message = "This colour is already assigned to another category. Remove it there first.")
            }
            else -> addColor(category, hex)
        }
    }

    fun onCustomHexChange(category: ZettelkastenCategory, value: String) =
        _uiState.update { it.copy(customHexInputs = it.customHexInputs + (category to value), message = null) }

    fun addCustomColor(category: ZettelkastenCategory) {
        val state = _uiState.value
        val hex = normalizeHex(state.customHexInputs[category].orEmpty())
        if (hex == null) {
            _uiState.update { it.copy(message = "Enter a colour as #RRGGBB before adding it.") }
            return
        }
        val owner = state.categoryOf(hex)
        if (owner != null && owner != category) {
            _uiState.update { it.copy(message = "This colour is already assigned to another category.") }
            return
        }
        addColor(category, hex)
        _uiState.update { it.copy(customHexInputs = it.customHexInputs + (category to "")) }
    }

    fun removeColor(category: ZettelkastenCategory, hex: String) = _uiState.update {
        it.copy(
            categoryColors = it.categoryColors + (category to (it.categoryColors[category].orEmpty() - hex)),
            message = null,
        )
    }

    private fun addColor(category: ZettelkastenCategory, hex: String) = _uiState.update { state ->
        val existing = state.categoryColors[category].orEmpty()
        if (hex in existing) return@update state
        state.copy(categoryColors = state.categoryColors + (category to existing + hex), message = null)
    }

    fun save() {
        if (_uiState.value.busy || _uiState.value.loading) return
        val colors = _uiState.value.categoryColors
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            try {
                settingsStore.saveZettelkastenCategoryColors(colors)
                _uiState.update { it.copy(message = "Zettelkasten categories saved.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "Could not save categories. Nothing was changed.") }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }
}

private fun normalizeHex(value: String): String? {
    val trimmed = value.trim().let { if (it.startsWith("#")) it else "#$it" }
    return trimmed.takeIf { HEX_COLOR.matches(it) }?.uppercase()
}
