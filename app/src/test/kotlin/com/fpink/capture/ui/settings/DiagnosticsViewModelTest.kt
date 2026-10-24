package com.fpink.capture.ui.settings

import com.fpink.capture.diagnostics.DiagnosticCategory
import com.fpink.capture.diagnostics.DiagnosticOperation
import com.fpink.capture.diagnostics.DiagnosticStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @TempDir lateinit var root: File
    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: DiagnosticStore

    @BeforeEach fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = DiagnosticStore(File(root, "logs"), File(root, "diagnostics-share"), dispatcher = dispatcher)
    }

    @AfterEach fun tearDown() {
        store.close()
        Dispatchers.resetMain()
    }

    @Test fun `empty logs prepare review without submitting and private route never falls back`() = runTest(dispatcher) {
        for (full in listOf(false, true)) {
            val model = DiagnosticsViewModel(store, full)
            model.prepareReport()
            assertTrue(model.uiState.value.busy)
            advanceUntilIdle()
            val draft = requireNotNull(model.uiState.value.draft)
            val repository = if (full) "fpink-private" else "fpink"
            assertTrue(draft.url.startsWith("https://github.com/MaxDac/$repository/issues/new?"))
            assertTrue(draft.url.length <= 2_000)
            assertTrue(draft.body.contains("No diagnostic events"))
            assertNull(model.uiState.value.export)
            model.dismissPreview()
            assertNull(model.uiState.value.draft)
        }
    }

    @Test fun `clear removes preview and export and reporting continues`() = runTest(dispatcher) {
        val model = DiagnosticsViewModel(store, false)
        store.report(DiagnosticOperation.RECOGNITION, DiagnosticCategory.MODEL_UNAVAILABLE)
        model.prepareExport()
        advanceUntilIdle()
        val export = requireNotNull(model.uiState.value.export)
        assertTrue(export.isFile)
        model.clear()
        advanceUntilIdle()
        assertEquals(DiagnosticsNotice.CLEARED, model.uiState.value.notice)
        assertNull(model.uiState.value.export)
        assertFalse(export.exists())
        assertTrue(store.snapshot().isEmpty())
        store.report(DiagnosticOperation.STORAGE_SAVE, DiagnosticCategory.STORAGE)
        assertEquals(1, store.snapshot().size)
    }

    @Test fun `storage failure and failed handoff have explicit safe notices`() = runTest(dispatcher) {
        store.close()
        val model = DiagnosticsViewModel(store, false)
        model.prepareReport()
        advanceUntilIdle()
        assertEquals(DiagnosticsNotice.UNAVAILABLE, model.uiState.value.notice)
        assertNull(model.uiState.value.draft)
        assertFalse(model.uiState.value.busy)
        model.handoffFailed()
        assertEquals(DiagnosticsNotice.HANDOFF_FAILED, model.uiState.value.notice)
    }
}
