package com.fpink.capture.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fpink.capture.diagnostics.DiagnosticCategory
import com.fpink.capture.diagnostics.DiagnosticOperation
import com.fpink.capture.diagnostics.DiagnosticReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SettingsStoreTest {
    @Test
    fun `credential load failures report once across collectors until recovery`() = runTest {
        val configKey = stringPreferencesKey("recognition_config_encrypted")
        val preferences = MutableStateFlow<Preferences>(mutablePreferencesOf(configKey to "malformed"))
        val dataStore = object : DataStore<Preferences> {
            override val data = preferences
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(preferences.value).also { preferences.value = it }
        }
        val events = mutableListOf<Pair<DiagnosticOperation, DiagnosticCategory>>()
        val settings = SettingsStore(dataStore, CredentialCipher(), object : DiagnosticReporter {
            override fun report(operation: DiagnosticOperation, category: DiagnosticCategory) {
                events += operation to category
            }
        })

        repeat(3) { assertNotNull(settings.storedSettings.first().keyError) }
        preferences.value = mutablePreferencesOf(
            configKey to "malformed",
            stringPreferencesKey("appearance_theme") to "dark",
        )
        assertNotNull(settings.storedSettings.first().keyError)
        assertEquals(listOf(DiagnosticOperation.SETTINGS_LOAD to DiagnosticCategory.CONFIGURATION), events)

        preferences.value = mutablePreferencesOf()
        assertNull(settings.storedSettings.first().keyError)
        preferences.value = mutablePreferencesOf(configKey to "malformed")
        assertNotNull(settings.storedSettings.first().keyError)
        assertEquals(
            List(2) { DiagnosticOperation.SETTINGS_LOAD to DiagnosticCategory.CONFIGURATION },
            events,
        )
    }
}
