package com.fpink.capture.ui.theme

import com.fpink.capture.data.ThemeMode
import com.fpink.capture.data.ThemeSettings
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterEach fun tearDown() = Dispatchers.resetMain()

    @Test fun `missing preference defaults system and stable values round trip`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStored(it.storedValue)) }
        assertThrows(IllegalArgumentException::class.java) { ThemeMode.fromStored("invalid") }
    }

    @Test fun `manual themes ignore system changes and system immediately follows current setting`() {
        for (system in listOf(false, true, false, true)) {
            assertEquals(system, ThemeMode.SYSTEM.isDark(system))
            assertFalse(ThemeMode.LIGHT.isDark(system))
            assertTrue(ThemeMode.DARK.isDark(system))
        }
    }

    @Test fun `loading is observable and saved choice restores after recreation`() = runTest(dispatcher) {
        val store = FakeSettings(ThemeMode.DARK)
        val model = ThemeViewModel(store)
        assertTrue(model.uiState.value.loading)
        runCurrent()
        assertEquals(ThemeMode.DARK, model.uiState.value.mode)
        assertFalse(model.uiState.value.loading)
        model.select(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, model.uiState.value.mode)
        assertTrue(model.uiState.value.saving)
        advanceUntilIdle()
        assertFalse(model.uiState.value.saving)
        val recreated = ThemeViewModel(store)
        runCurrent()
        assertEquals(ThemeMode.LIGHT, recreated.uiState.value.mode)
    }

    @Test fun `rapid choices render immediately and write serially with last selection winning`() = runTest(dispatcher) {
        val store = FakeSettings()
        val model = ThemeViewModel(store)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        store.writeGate = gate
        model.select(ThemeMode.DARK)
        runCurrent()
        model.select(ThemeMode.LIGHT)
        model.select(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, model.uiState.value.mode)
        assertEquals(listOf(ThemeMode.DARK), store.writes)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(ThemeMode.DARK, ThemeMode.SYSTEM), store.writes)
        assertEquals(ThemeMode.SYSTEM, store.saved)
        assertFalse(model.uiState.value.saving)
    }

    @Test fun `failed write restores last successful choice and can be retried`() = runTest(dispatcher) {
        val store = FakeSettings(ThemeMode.LIGHT)
        val model = ThemeViewModel(store)
        runCurrent()
        store.failWrite = true
        model.select(ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals(ThemeMode.LIGHT, model.uiState.value.mode)
        assertEquals(ThemeError.WRITE, model.uiState.value.error)
        store.failWrite = false
        model.select(ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, store.saved)
        assertNull(model.uiState.value.error)
    }

    @Test fun `stale write failure cannot roll back newer choice`() = runTest(dispatcher) {
        val store = FakeSettings()
        val model = ThemeViewModel(store)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        store.writeGate = gate
        store.failWrite = true
        model.select(ThemeMode.LIGHT)
        runCurrent()
        model.select(ThemeMode.DARK)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, store.saved)
        assertEquals(ThemeMode.DARK, model.uiState.value.mode)
        assertNull(model.uiState.value.error)
    }

    @Test fun `read failure does not write defaults and exposes retry`() = runTest(dispatcher) {
        val store = FakeSettings(ThemeMode.DARK).apply { failRead = true }
        val model = ThemeViewModel(store)
        runCurrent()
        assertEquals(ThemeError.READ, model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
        model.select(ThemeMode.LIGHT)
        assertTrue(store.writes.isEmpty())
        store.failRead = false
        model.retryLoad()
        assertTrue(model.uiState.value.loading)
        runCurrent()
        assertEquals(ThemeMode.DARK, model.uiState.value.mode)
        assertNull(model.uiState.value.error)
    }

    private class FakeSettings(var saved: ThemeMode = ThemeMode.SYSTEM) : ThemeSettings {
        var failRead = false
        var failWrite = false
        var writeGate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<ThemeMode>()
        override val themeMode: Flow<ThemeMode> = flow {
            if (failRead) throw IOException("Read failed")
            emit(saved)
        }
        override suspend fun saveThemeMode(mode: ThemeMode) {
            writes += mode
            writeGate?.await()
            if (failWrite) {
                failWrite = false
                throw IOException("Write failed")
            }
            saved = mode
        }
    }
}
