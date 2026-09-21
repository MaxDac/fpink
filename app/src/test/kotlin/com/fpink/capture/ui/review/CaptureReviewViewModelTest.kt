package com.fpink.capture.ui.review

import androidx.lifecycle.viewModelScope
import com.fpink.core.model.Note
import com.fpink.core.storage.FileStore
import com.fpink.core.storage.NoteRepository
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<CaptureReviewViewModel>()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun `loads only source notes in paragraph order`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.repository.saveImage("other", byteArrayOf(2)).getOrThrow()
        fixture.repository.save(note("other", null).copy(sourceId = null, imagePath = "images/other.jpg")).getOrThrow()

        val model = model(fixture.repository)
        runCurrent()

        assertEquals(listOf("source-0", "source-1"), model.uiState.value.items.map { it.note.id })
        assertFalse(model.uiState.value.isLoading)
        assertFalse(model.uiState.value.hasUnsavedChanges)
    }

    @Test fun `validates text and saves corrections without replacing metadata`() = runTest(dispatcher) {
        val fixture = fixture()
        val model = model(fixture.repository)
        runCurrent()

        model.onTextChanged("source-0", "")
        model.onDone()
        assertTrue(model.uiState.value.validationRequested)
        assertFalse(model.uiState.value.isSaving)
        assertNotNull(model.uiState.value.error)

        model.onTextChanged("source-0", "Corrected paragraph")
        model.onDone()
        advanceUntilIdle()

        val saved = fixture.repository.get("source-0").getOrThrow()
        assertEquals("Corrected paragraph", saved.text)
        assertTrue(saved.userEdited)
        assertEquals("#112233", saved.inkColorHex)
        assertEquals("model observation", saved.modelNotes)
        assertEquals(listOf("tag"), saved.tags)
        assertEquals(0, saved.paragraphIndex)
        assertTrue(model.uiState.value.completed)
        assertFalse(model.uiState.value.hasUnsavedChanges)
    }

    @Test fun `partial save failure keeps only remaining corrections dirty and retryable`() = runTest(dispatcher) {
        val fixture = fixture()
        val model = model(fixture.repository)
        runCurrent()
        fixture.store.calls.clear()
        model.onTextChanged("source-0", "First correction")
        model.onTextChanged("source-1", "Second correction")
        fixture.store.failOncePath = "notes/source-1.json"

        model.onDone()
        advanceUntilIdle()

        assertFalse(model.uiState.value.completed)
        assertNotNull(model.uiState.value.error)
        assertFalse(model.uiState.value.items[0].isDirty)
        assertTrue(model.uiState.value.items[1].isDirty)
        assertEquals("First correction", fixture.repository.get("source-0").getOrThrow().text)
        assertEquals("Paragraph 1", fixture.repository.get("source-1").getOrThrow().text)

        model.onDone()
        advanceUntilIdle()

        assertTrue(model.uiState.value.completed)
        assertFalse(model.uiState.value.hasUnsavedChanges)
        assertEquals("Second correction", fixture.repository.get("source-1").getOrThrow().text)
        assertEquals(1, fixture.store.calls.count { it == "write:notes/source-0.json" })
        assertEquals(2, fixture.store.calls.count { it == "write:notes/source-1.json" })
    }

    private fun model(repository: NoteRepository) =
        CaptureReviewViewModel("source", repository).also { models += it }

    private suspend fun fixture(): Fixture {
        val store = MemoryFileStore()
        val repository = NoteRepository(store)
        repository.saveBatch(
            sourceId = "source",
            imageBytes = byteArrayOf(1),
            imageExtension = "png",
            notes = listOf(note("source-1", 1), note("source-0", 0)),
        ).getOrThrow()
        return Fixture(store, repository)
    }

    private fun note(id: String, paragraphIndex: Int?) = Note(
        id = id,
        capturedAt = Instant.fromEpochSeconds(1_000),
        imagePath = "images/source.png",
        text = if (paragraphIndex == null) "Other" else "Paragraph $paragraphIndex",
        inkColorHex = "#112233",
        inkColorName = "Blue",
        modelNotes = "model observation",
        tags = listOf("tag"),
        sourceId = paragraphIndex?.let { "source" },
        paragraphIndex = paragraphIndex,
    )

    private data class Fixture(val store: MemoryFileStore, val repository: NoteRepository)

    private class MemoryFileStore : FileStore {
        val files = mutableMapOf<String, ByteArray>()
        val calls = mutableListOf<String>()
        var failOncePath: String? = null

        override suspend fun read(path: String): Result<ByteArray> = operation("read", path) {
            files[path] ?: throw IOException("Missing file: $path")
        }

        override suspend fun write(path: String, data: ByteArray): Result<Unit> = operation("write", path) {
            if (path == failOncePath) {
                failOncePath = null
                throw IOException("Injected write failure")
            }
            files[path] = data
        }

        override suspend fun delete(path: String): Result<Unit> = operation("delete", path) {
            files.remove(path)
        }

        override suspend fun list(directory: String): Result<List<String>> = operation("list", directory) {
            files.keys.filter { it.startsWith("$directory/") }.map { it.removePrefix("$directory/") }
        }

        override suspend fun exists(path: String): Result<Boolean> = operation("exists", path) {
            path in files
        }

        private fun <T> operation(name: String, path: String, block: () -> T): Result<T> = try {
            calls += "$name:$path"
            Result.success(block())
        } catch (error: IOException) {
            Result.failure(error)
        }
    }
}
