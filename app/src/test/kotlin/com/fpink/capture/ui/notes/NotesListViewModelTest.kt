package com.fpink.capture.ui.notes

import androidx.lifecycle.viewModelScope
import com.fpink.core.model.Note
import com.fpink.core.storage.FileStore
import com.fpink.core.storage.NoteRepository
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
class NotesListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<NotesListViewModel>()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun `selection is stable id based and select all includes the whole loaded list`() = runTest(dispatcher) {
        val fixture = fixture((1..80).map { "note-$it" })
        val model = model(fixture.repository)
        model.select("note-1")
        assertFalse(model.uiState.value.isSelecting)
        runCurrent()

        model.select("note-1")
        model.select("note-1")
        model.select("missing")
        assertEquals(setOf("note-1"), model.uiState.value.selectedIds)
        model.toggleSelection("note-2")
        assertEquals(setOf("note-1", "note-2"), model.uiState.value.selectedIds)
        assertFalse(model.uiState.value.allSelected)
        model.toggleSelectAll()
        assertEquals(80, model.uiState.value.selectedIds.size)
        assertTrue(model.uiState.value.allSelected)
        model.toggleSelectAll()
        assertFalse(model.uiState.value.isSelecting)
        model.select("note-1")
        model.toggleSelection("note-1")
        assertFalse(model.uiState.value.isSelecting)
        model.select("note-1")
        model.clearSelection()
        assertFalse(model.uiState.value.isSelecting)
        assertEquals(80, fixture.repository.list().getOrThrow().size)
    }

    @Test fun `empty library cannot create a selection or deletion request`() = runTest(dispatcher) {
        val model = model(fixture(emptyList()).repository)
        runCurrent()
        model.select("missing")
        model.toggleSelection("missing")
        model.toggleSelectAll()
        model.requestDeletion()
        model.confirmDeletion()
        assertFalse(model.uiState.value.isSelecting)
        assertFalse(model.uiState.value.allSelected)
        assertFalse(model.uiState.value.isDeleting)
        assertTrue(model.uiState.value.pendingDeletionIds.isEmpty())
    }

    @Test fun `refresh removes missing selections but never selects newly loaded notes`() = runTest(dispatcher) {
        val fixture = fixture(listOf("a", "b"))
        val model = model(fixture.repository)
        runCurrent()
        model.toggleSelectAll()
        fixture.repository.delete("a").getOrThrow()
        fixture.repository.save(note("c")).getOrThrow()
        model.refresh()
        model.toggleSelection("c")
        runCurrent()
        assertEquals(setOf("b"), model.uiState.value.selectedIds)
        assertFalse(model.uiState.value.allSelected)
        assertEquals(listOf("b", "c"), model.uiState.value.notes.map { it.id })
    }

    @Test fun `read failures preserve cached selection and block mutations until retry`() = runTest(dispatcher) {
        val fixture = fixture()
        val model = model(fixture.repository)
        runCurrent()
        model.select("a")
        fixture.store.fail = { operation, _ -> operation == "list" }
        model.refresh()
        runCurrent()
        assertTrue(model.uiState.value.error is NotesListError.Storage)
        assertEquals(3, model.uiState.value.notes.size)
        assertEquals(setOf("a"), model.uiState.value.selectedIds)
        model.toggleSelectAll()
        model.requestDeletion()
        assertTrue(model.uiState.value.pendingDeletionIds.isEmpty())
        assertEquals(setOf("a"), model.uiState.value.selectedIds)
        fixture.store.fail = { _, _ -> false }
        model.refresh()
        runCurrent()
        assertNull(model.uiState.value.error)
        assertEquals(setOf("a"), model.uiState.value.selectedIds)
    }

    @Test fun `cancel preserves selection and confirmation snapshots exclude later arrivals`() = runTest(dispatcher) {
        val fixture = fixture(listOf("a", "b"))
        val model = model(fixture.repository)
        runCurrent()
        model.toggleSelectAll()
        model.requestDeletion()
        model.cancelDeletion()
        assertEquals(setOf("a", "b"), model.uiState.value.selectedIds)
        assertTrue(model.uiState.value.pendingDeletionIds.isEmpty())
        model.confirmDeletion()
        assertFalse(model.uiState.value.isDeleting)

        model.requestDeletion()
        model.toggleSelection("a")
        model.clearSelection()
        fixture.repository.save(note("c")).getOrThrow()
        model.refresh()
        runCurrent()
        assertEquals(listOf("a", "b"), model.uiState.value.pendingDeletionIds)
        model.confirmDeletion()
        advanceUntilIdle()
        assertEquals(listOf("c"), NoteRepository(fixture.store).list().getOrThrow().map { it.id })
        assertFalse(model.uiState.value.isSelecting)
        assertEquals(2, model.uiState.value.deletedCount)
        model.onDeletionResultShown()
        assertNull(model.uiState.value.deletedCount)
    }

    @Test fun `subset deletion retains shared images until the final legacy reference is removed`() = runTest(dispatcher) {
        val fixture = fixture()
        for (id in listOf("b", "c")) {
            fixture.repository.save(note(id).copy(imagePath = "images/a.jpg")).getOrThrow()
        }
        val model = model(fixture.repository)
        runCurrent()
        model.select("a")
        model.select("c")
        deleteSelection(model)
        advanceUntilIdle()
        assertEquals(listOf("b"), NoteRepository(fixture.store).list().getOrThrow().map { it.id })
        assertTrue(fixture.store.files.containsKey("images/a.jpg"))
        assertEquals(2, model.uiState.value.deletedCount)

        model.select("b")
        deleteSelection(model)
        advanceUntilIdle()
        assertFalse(fixture.store.files.containsKey("images/a.jpg"))
        assertTrue(model.uiState.value.notes.isEmpty())
        assertFalse(model.uiState.value.isSelecting)
        assertEquals(1, model.uiState.value.deletedCount)
    }

    @Test fun `paragraph deletion preserves sibling and legacy references and retry tombstones`() = runTest(dispatcher) {
        val fixture = fixture(emptyList())
        val paragraphs = (0..2).map {
            note("source-$it").copy(imagePath = "images/source.png", sourceId = "source", paragraphIndex = it)
        }
        fixture.repository.saveBatch("source", byteArrayOf(1), "png", paragraphs).getOrThrow()
        fixture.repository.save(note("legacy").copy(imagePath = "images/source.png")).getOrThrow()
        val model = model(fixture.repository)
        runCurrent()
        model.select("source-0")
        model.select("source-2")
        deleteSelection(model)
        advanceUntilIdle()
        val restored = NoteRepository(fixture.store)
        assertEquals(listOf("legacy", "source-1"), restored.list().getOrThrow().map { it.id })
        assertTrue(fixture.store.files.containsKey("images/source.png"))
        model.toggleSelectAll()
        deleteSelection(model)
        advanceUntilIdle()
        assertFalse(fixture.store.files.containsKey("images/source.png"))
        assertTrue(restored.isSourceCommitted("source").getOrThrow())
        restored.saveBatch("source", byteArrayOf(1), "png", paragraphs).getOrThrow()
        assertTrue(restored.list().getOrThrow().isEmpty())
    }

    @Test fun `first or middle failure stops later attempts and remaining selection can be retried`() = runTest(dispatcher) {
        for (failedId in listOf("a", "b")) {
            val fixture = fixture()
            val model = model(fixture.repository)
            runCurrent()
            model.toggleSelectAll()
            fixture.store.fail = { operation, path -> operation == "write" && path == "deletions/$failedId.json" }
            deleteSelection(model)
            advanceUntilIdle()
            val remaining = listOf("a", "b", "c").dropWhile { it != failedId }
            assertEquals(remaining.toSet(), model.uiState.value.selectedIds)
            assertEquals(remaining, model.uiState.value.notes.map { it.id })
            assertFalse(fixture.store.calls.contains("write:deletions/c.json"))
            assertNotNull(model.uiState.value.deletionError)
            assertNull(model.uiState.value.error)
            assertNull(model.uiState.value.deletedCount)
            assertFalse(model.uiState.value.isDeleting)

            fixture.store.fail = { _, _ -> false }
            deleteSelection(model)
            advanceUntilIdle()
            assertTrue(model.uiState.value.notes.isEmpty())
            assertNull(model.uiState.value.deletionError)
            assertEquals(remaining.size, model.uiState.value.deletedCount)
        }
    }

    @Test fun `post commit error reconciles the removed note but does not delete later notes`() = runTest(dispatcher) {
        val fixture = fixture(listOf("a", "b"))
        val model = model(fixture.repository)
        runCurrent()
        model.toggleSelectAll()
        var failOnce = true
        fixture.store.fail = { operation, path ->
            (failOnce && operation == "delete" && path == "notes/a.json").also { if (it) failOnce = false }
        }
        deleteSelection(model)
        advanceUntilIdle()
        assertEquals(setOf("b"), model.uiState.value.selectedIds)
        assertEquals(listOf("b"), model.uiState.value.notes.map { it.id })
        assertNotNull(model.uiState.value.deletionError)
        assertNull(model.uiState.value.error)
        assertNull(model.uiState.value.deletedCount)
        assertFalse(fixture.store.calls.contains("write:deletions/b.json"))
        assertFalse(fixture.store.files.containsKey("images/a.jpg"))
        model.dismissDeletionError()
        assertNull(model.uiState.value.deletionError)
    }

    @Test fun `persistent cleanup failure remains retryable even after the last note is removed`() = runTest(dispatcher) {
        val fixture = fixture(listOf("a"))
        val model = model(fixture.repository)
        runCurrent()
        model.select("a")
        fixture.store.fail = { operation, path -> operation == "delete" && path == "images/a.jpg" }
        deleteSelection(model)
        advanceUntilIdle()
        assertFalse(fixture.store.files.containsKey("notes/a.json"))
        assertTrue(model.uiState.value.error is NotesListError.Storage)
        assertNotNull(model.uiState.value.deletionError)
        assertEquals(setOf("a"), model.uiState.value.selectedIds)
        assertFalse(model.uiState.value.canInteract)
        model.requestDeletion()
        assertTrue(model.uiState.value.pendingDeletionIds.isEmpty())

        fixture.store.fail = { _, _ -> false }
        model.refresh()
        advanceUntilIdle()
        assertNull(model.uiState.value.error)
        assertTrue(model.uiState.value.notes.isEmpty())
        assertFalse(model.uiState.value.isSelecting)
        assertFalse(fixture.store.files.containsKey("images/a.jpg"))
        assertNotNull(model.uiState.value.deletionError)
    }

    @Test fun `successful deletes followed by a read failure do not claim verified success`() = runTest(dispatcher) {
        val fixture = fixture(listOf("a"))
        val model = model(fixture.repository)
        runCurrent()
        model.select("a")
        fixture.store.before = { operation, path ->
            if (operation == "delete" && path == "deletions/a.json") {
                fixture.store.fail = { nextOperation, _ -> nextOperation == "list" }
            }
        }
        deleteSelection(model)
        advanceUntilIdle()
        assertTrue(model.uiState.value.error is NotesListError.Storage)
        assertEquals(setOf("a"), model.uiState.value.selectedIds)
        assertNull(model.uiState.value.deletedCount)
        assertFalse(model.uiState.value.isDeleting)
        fixture.store.fail = { _, _ -> false }
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.uiState.value.notes.isEmpty())
        assertFalse(model.uiState.value.isSelecting)
    }

    @Test fun `busy guard is synchronous and refresh or repeated confirmation cannot race deletion`() = runTest(dispatcher) {
        val fixture = fixture()
        val model = model(fixture.repository)
        runCurrent()
        model.select("a")
        val gate = CompletableDeferred<Unit>()
        fixture.store.before = { operation, path ->
            if (operation == "write" && path == "deletions/a.json") gate.await()
        }
        deleteSelection(model)
        assertTrue(model.uiState.value.isDeleting)
        model.confirmDeletion()
        model.select("b")
        model.toggleSelectAll()
        model.clearSelection()
        model.requestDeletion()
        model.refresh()
        runCurrent()
        assertEquals(setOf("a"), model.uiState.value.selectedIds)
        assertEquals(1, fixture.store.calls.count { it == "write:deletions/a.json" })
        model.refresh()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("b", "c"), model.uiState.value.notes.map { it.id })
        assertEquals(1, model.uiState.value.deletedCount)
        assertFalse(model.uiState.value.isDeleting)
        assertFalse(model.uiState.value.isLoading)
        assertEquals(1, fixture.store.calls.count { it == "write:deletions/a.json" })
    }

    @Test fun `overlapping initial and lifecycle refreshes are coalesced`() = runTest(dispatcher) {
        val fixture = fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.store.before = { operation, path ->
            if (operation == "list" && path == "staging") gate.await()
        }
        fixture.store.calls.clear()
        val model = model(fixture.repository)
        model.refresh()
        runCurrent()
        model.refresh()
        assertTrue(model.uiState.value.isLoading)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, fixture.store.calls.count { it == "list:staging" })
        assertEquals(3, model.uiState.value.notes.size)
        assertFalse(model.uiState.value.isLoading)
    }

    @Test fun `already absent confirmed notes are harmless and new notes are never included`() = runTest(dispatcher) {
        val fixture = fixture(listOf("a", "b"))
        val model = model(fixture.repository)
        runCurrent()
        model.select("a")
        model.requestDeletion()
        fixture.repository.delete("a").getOrThrow()
        model.confirmDeletion()
        advanceUntilIdle()
        assertEquals(listOf("b"), model.uiState.value.notes.map { it.id })
        assertNull(model.uiState.value.error)
        assertNull(model.uiState.value.deletionError)
        assertFalse(model.uiState.value.isSelecting)
    }

    @Test fun `cancelled refresh preserves selection and clears its busy state for retry`() = runTest(dispatcher) {
        val fixture = fixture()
        val model = model(fixture.repository)
        runCurrent()
        model.select("a")
        fixture.store.before = { operation, _ ->
            if (operation == "list") throw CancellationException("Interrupted read")
        }
        model.refresh()
        advanceUntilIdle()
        assertEquals(NotesListError.Interrupted, model.uiState.value.error)
        assertFalse(model.uiState.value.isLoading)
        assertEquals(setOf("a"), model.uiState.value.selectedIds)
        assertEquals(3, model.uiState.value.notes.size)
        fixture.store.before = { _, _ -> }
        model.refresh()
        advanceUntilIdle()
        assertNull(model.uiState.value.error)
        assertEquals(setOf("a"), model.uiState.value.selectedIds)
    }

    @Test fun `cancellation stops work invalidates cached state and requires a non destructive refresh`() = runTest(dispatcher) {
        val fixture = fixture()
        val model = model(fixture.repository)
        runCurrent()
        model.toggleSelectAll()
        fixture.store.before = { operation, path ->
            if (operation == "write" && path == "deletions/b.json") throw CancellationException("Interrupted")
        }
        deleteSelection(model)
        advanceUntilIdle()
        assertEquals(NotesListError.Interrupted, model.uiState.value.error)
        assertNull(model.uiState.value.deletedCount)
        assertNull(model.uiState.value.deletionError)
        assertFalse(model.uiState.value.isDeleting)
        assertFalse(model.uiState.value.canInteract)
        assertFalse(fixture.store.calls.contains("write:deletions/c.json"))
        fixture.store.before = { _, _ -> }
        model.refresh()
        advanceUntilIdle()
        assertEquals(setOf("b", "c"), model.uiState.value.selectedIds)
        assertEquals(listOf("b", "c"), model.uiState.value.notes.map { it.id })
        assertNull(model.uiState.value.error)
    }

    private fun model(repository: NoteRepository): NotesListViewModel =
        NotesListViewModel(repository).also { models += it }

    private fun deleteSelection(model: NotesListViewModel) {
        model.requestDeletion()
        model.confirmDeletion()
    }

    private suspend fun fixture(ids: List<String> = listOf("a", "b", "c")): Fixture {
        val store = MemoryFileStore()
        val repository = NoteRepository(store)
        ids.forEach {
            repository.saveImage(it, byteArrayOf(1)).getOrThrow()
            repository.save(note(it)).getOrThrow()
        }
        return Fixture(store, repository)
    }

    private fun note(id: String) = Note(
        id = id, capturedAt = Instant.fromEpochSeconds(1_000),
        imagePath = "images/$id.jpg", text = "Note $id",
    )

    private data class Fixture(val store: MemoryFileStore, val repository: NoteRepository)

    private class MemoryFileStore : FileStore {
        val files = mutableMapOf<String, ByteArray>()
        val calls = mutableListOf<String>()
        var fail: (String, String) -> Boolean = { _, _ -> false }
        var before: suspend (String, String) -> Unit = { _, _ -> }

        override suspend fun read(path: String): Result<ByteArray> = operation("read", path) {
            files[path] ?: throw IOException("Missing file: $path")
        }

        override suspend fun write(path: String, data: ByteArray): Result<Unit> = operation("write", path) {
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

        private suspend fun <T> operation(name: String, path: String, block: () -> T): Result<T> = try {
            calls += "$name:$path"
            before(name, path)
            if (fail(name, path)) throw IOException("Injected $name failure: $path")
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            Result.failure(error)
        }
    }
}
