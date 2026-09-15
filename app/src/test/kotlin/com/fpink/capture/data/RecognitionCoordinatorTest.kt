package com.fpink.capture.data

import com.fpink.core.ai.AzureReadConfig
import com.fpink.core.ai.NoteProcessor
import com.fpink.core.ai.ParagraphDraft
import com.fpink.core.ai.PreparedImage
import com.fpink.core.ai.RecognitionDocument
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProvider
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.ai.RecognitionSettings
import com.fpink.core.model.InkColorOrigin
import com.fpink.core.storage.FileStore
import com.fpink.core.storage.NoteRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionCoordinatorTest {
    private val image = PreparedImage(byteArrayOf(1), "image/png", 1, 1, intArrayOf(-1))
    private val document = RecognitionDocument(emptyList(), RecognitionProviderId.PADDLE, "fixture")
    private val sourceId = "4d3c1a1f-5449-4113-a22b-6a93d0971885"
    private fun draft(text: String) = ParagraphDraft(text, emptyList(), "#000000", "Black", InkColorOrigin.DEFAULTED)

    @Test
    fun `reentry is one job with separate stable ordered paragraph notes`() = runTest {
        val fixture = Fixture(this)
        val coordinator = fixture.coordinator()
        coordinator.confirm(sourceId)
        coordinator.start(sourceId)
        coordinator.start(sourceId)
        advanceUntilIdle()
        assertEquals(1, fixture.calls)
        assertEquals(listOf("$sourceId-0", "$sourceId-1"), fixture.repository.list().getOrThrow().map { it.id })
        assertEquals(2, (coordinator.state(sourceId).value as RecognitionJobState.Complete).count)
        val recreated = fixture.coordinator()
        recreated.start(sourceId)
        advanceUntilIdle()
        assertEquals(1, fixture.calls)
        assertEquals(2, fixture.repository.list().getOrThrow().size)
    }

    @Test
    fun `job keeps confirmed offline provider when global settings change`() = runTest {
        val fixture = Fixture(this)
        val coordinator = fixture.coordinator()
        coordinator.confirm(sourceId)
        fixture.settings = RecognitionSettings(
            RecognitionProviderId.AZURE, AzureReadConfig("https://test.cognitiveservices.azure.com", "fixture-key"),
        )
        coordinator.start(sourceId)
        advanceUntilIdle()
        assertEquals(listOf(RecognitionProviderId.PADDLE), fixture.selectedProviders)
        assertEquals(RecognitionProviderId.PADDLE, fixture.savedSelection?.provider)
    }

    @Test
    fun `blank paragraphs are visible no-text failure and create zero notes`() = runTest {
        val fixture = Fixture(this).apply { paragraphs = listOf(draft(" \n ")) }
        val coordinator = fixture.coordinator()
        coordinator.confirm(sourceId)
        coordinator.start(sourceId)
        advanceUntilIdle()
        val failure = coordinator.state(sourceId).value as RecognitionJobState.Failed
        assertFalse(failure.retryable)
        assertTrue(failure.message.contains("No notes were created"))
        assertTrue(fixture.repository.list().getOrThrow().isEmpty())
    }

    @Test
    fun `persistence retry reuses recognition and never duplicates partial notes`() = runTest {
        val fixture = Fixture(this)
        fixture.files.failNextWrite = true
        val coordinator = fixture.coordinator()
        coordinator.confirm(sourceId)
        coordinator.start(sourceId)
        advanceUntilIdle()
        assertInstanceOf(RecognitionJobState.Failed::class.java, coordinator.state(sourceId).value)
        coordinator.start(sourceId, retry = true)
        advanceUntilIdle()
        assertEquals(1, fixture.calls)
        assertEquals(2, fixture.repository.list().getOrThrow().size)
    }

    @Test
    fun `late native completion after cancel cannot save notes`() = runTest {
        val fixture = Fixture(this)
        val native = CompletableDeferred<Unit>()
        fixture.recognize = {
            withContext(NonCancellable) { native.await() }
            Result.success(document)
        }
        val coordinator = fixture.coordinator()
        coordinator.confirm(sourceId)
        coordinator.start(sourceId)
        runCurrent()
        val discard = launch { assertTrue(coordinator.discard(sourceId)) }
        runCurrent()
        native.complete(Unit)
        advanceUntilIdle()
        discard.join()
        assertTrue(fixture.repository.list().getOrThrow().isEmpty())
    }

    @Test
    fun `unavailable native model never falls back to Azure`() = runTest {
        val fixture = Fixture(this)
        fixture.recognize = { Result.failure(RecognitionError.ModelUnavailable("missing fixture model")) }
        val coordinator = fixture.coordinator()
        coordinator.confirm(sourceId)
        coordinator.start(sourceId)
        advanceUntilIdle()
        assertEquals(listOf(RecognitionProviderId.PADDLE), fixture.selectedProviders)
        assertFalse((coordinator.state(sourceId).value as RecognitionJobState.Failed).retryable)
        assertTrue(fixture.repository.list().getOrThrow().isEmpty())
    }

    @Test
    fun `restart after deleting every paragraph uses tombstone without another recognition`() = runTest {
        val fixture = Fixture(this)
        val coordinator = fixture.coordinator()
        coordinator.confirm(sourceId)
        coordinator.start(sourceId)
        advanceUntilIdle()
        fixture.repository.list().getOrThrow().forEach { fixture.repository.delete(it.id).getOrThrow() }
        val restored = fixture.coordinator()
        restored.start(sourceId)
        advanceUntilIdle()
        val result = restored.state(sourceId).value as RecognitionJobState.Complete
        assertTrue(result.previouslySaved)
        assertEquals(0, result.count)
        assertEquals(1, fixture.calls)
        assertTrue(fixture.repository.list().getOrThrow().isEmpty())
    }

    @Test
    fun `durable cancel blocks restoration during native wait and after cleanup failure`() = runTest {
        val fixture = Fixture(this)
        fixture.settings = RecognitionSettings(
            RecognitionProviderId.AZURE, AzureReadConfig("https://test.cognitiveservices.azure.com", "fixture-key"),
        )
        fixture.cleanupFailure = IOException("Cannot delete staged files")
        val native = CompletableDeferred<Unit>()
        fixture.recognize = {
            withContext(NonCancellable) { native.await() }
            Result.success(document)
        }
        val coordinator = fixture.coordinator()
        coordinator.confirm(sourceId)
        coordinator.start(sourceId)
        runCurrent()
        var discardFailure: Throwable? = null
        val discard = launch {
            try {
                coordinator.discard(sourceId)
            } catch (failure: IOException) {
                discardFailure = failure
            }
        }
        runCurrent()
        assertTrue(sourceId in fixture.cancelledSources)
        assertTrue(discard.isActive, "Cancellation must be durable before waiting for native completion")
        val duringNativeWait = fixture.coordinator()
        duringNativeWait.start(sourceId)
        runCurrent()
        assertEquals(CANCELLED_JOB_MESSAGE, (duringNativeWait.state(sourceId).value as RecognitionJobState.Failed).message)
        native.complete(Unit)
        advanceUntilIdle()
        assertInstanceOf(IOException::class.java, discardFailure)
        assertTrue(fixture.savedSelection != null, "Failed cleanup deliberately leaves the original selection")
        val afterCleanupFailure = fixture.coordinator()
        afterCleanupFailure.start(sourceId)
        advanceUntilIdle()
        val restoredFailure = afterCleanupFailure.state(sourceId).value as RecognitionJobState.Failed
        assertEquals(CANCELLED_JOB_MESSAGE, restoredFailure.message)
        assertFalse(restoredFailure.retryable)
        assertEquals(1, fixture.calls)
        assertEquals(1, fixture.imageLoads)
        assertEquals(0, fixture.processingCalls)
        assertEquals(0, fixture.files.writeCalls)
        assertTrue(fixture.repository.list().getOrThrow().isEmpty())
    }

    @Test
    fun `persisted cancel prevents provider image processor and save for either provider`() = runTest {
        for (provider in RecognitionProviderId.entries) {
            val fixture = Fixture(this)
            fixture.savedSelection = RecognitionSettings(
                provider, AzureReadConfig("https://test.cognitiveservices.azure.com", "fixture-key"),
            )
            fixture.cancelledSources += sourceId
            val restored = fixture.coordinator()
            restored.start(sourceId)
            advanceUntilIdle()
            restored.start(sourceId, retry = true)
            advanceUntilIdle()
            assertEquals(CANCELLED_JOB_MESSAGE, (restored.state(sourceId).value as RecognitionJobState.Failed).message)
            assertInstanceOf(RecognitionError.Configuration::class.java, runCatching { restored.confirm(sourceId) }.exceptionOrNull())
            assertTrue(fixture.selectedProviders.isEmpty())
            assertEquals(0, fixture.calls)
            assertEquals(0, fixture.imageLoads)
            assertEquals(0, fixture.processingCalls)
            assertEquals(0, fixture.files.writeCalls)
        }
    }

    @Test
    fun `persisted cancel also blocks retry of a cached unsaved batch`() = runTest {
        val fixture = Fixture(this)
        fixture.files.failNextWrite = true
        val coordinator = fixture.coordinator()
        coordinator.confirm(sourceId)
        coordinator.start(sourceId)
        advanceUntilIdle()
        val writesBeforeCancellation = fixture.files.writeCalls
        fixture.cancelledSources += sourceId
        coordinator.start(sourceId, retry = true)
        advanceUntilIdle()
        assertEquals(CANCELLED_JOB_MESSAGE, (coordinator.state(sourceId).value as RecognitionJobState.Failed).message)
        assertEquals(writesBeforeCancellation, fixture.files.writeCalls)
        assertEquals(1, fixture.calls)
        assertTrue(fixture.repository.list().getOrThrow().isEmpty())
    }

    private inner class Fixture(val scope: TestScope) {
        val files = MemoryFiles()
        val repository = NoteRepository(files)
        var settings = RecognitionSettings()
        var savedSelection: RecognitionSettings? = null
        var calls = 0
        var imageLoads = 0
        var processingCalls = 0
        var cleanupFailure: IOException? = null
        val cancelledSources = mutableSetOf<String>()
        val selectedProviders = mutableListOf<RecognitionProviderId>()
        var paragraphs = listOf(draft("Wrapped paragraph stays together."), draft("Second paragraph."))
        var recognize: suspend () -> Result<RecognitionDocument> = { Result.success(document) }

        fun coordinator() = RecognitionCoordinator(
            readSettings = { settings },
            rememberSelection = { _, selected -> savedSelection = selected },
            restoreSelection = { savedSelection ?: error("Not confirmed") },
            loadImage = { imageLoads++; image },
            discardImage = { cleanupFailure?.let { throw it } },
            invalidateSelection = { cancelledSources += it },
            isCancelled = { it in cancelledSources },
            repository = repository,
            processor = object : NoteProcessor {
                override suspend fun process(image: PreparedImage, recognition: RecognitionDocument): Result<List<ParagraphDraft>> {
                    processingCalls++
                    return Result.success(paragraphs)
                }
            },
            providerFactory = { selected ->
                selectedProviders += selected.provider
                object : RecognitionProvider {
                    override suspend fun recognize(image: PreparedImage): Result<RecognitionDocument> {
                        calls++
                        return this@Fixture.recognize()
                    }
                }
            },
            scope = scope,
        )
    }

    private class MemoryFiles : FileStore {
        private val files = mutableMapOf<String, ByteArray>()
        var failNextWrite = false
        var writeCalls = 0
        override suspend fun read(path: String) = files[path]?.let { Result.success(it.copyOf()) }
            ?: Result.failure(IOException("Missing file"))
        override suspend fun write(path: String, data: ByteArray): Result<Unit> {
            writeCalls++
            if (failNextWrite) {
                failNextWrite = false
                return Result.failure(IOException("Full disk"))
            }
            files[path] = data.copyOf()
            return Result.success(Unit)
        }
        override suspend fun delete(path: String): Result<Unit> {
            files.remove(path)
            return Result.success(Unit)
        }
        override suspend fun list(directory: String) = Result.success(
            files.keys.filter { it.startsWith("$directory/") }.map { it.removePrefix("$directory/") },
        )
        override suspend fun exists(path: String) = Result.success(files.containsKey(path))
    }
}
