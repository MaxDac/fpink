package com.fpink.core.storage

import com.fpink.core.model.Note
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * In-memory [FileStore] backed by a [MutableMap]. Used for testing only.
 */
internal class InMemoryFileStore : FileStore {
    val files = mutableMapOf<String, ByteArray>()
    val writes = mutableListOf<String>()
    var fail: (operation: String, path: String) -> Boolean = { _, _ -> false }
    var afterWrite: suspend (String) -> Unit = {}

    private fun checkFailure(operation: String, path: String) {
        if (fail(operation, path)) throw java.io.IOException("Injected $operation failure: $path")
    }

    override suspend fun read(path: String): Result<ByteArray> = runCatching {
        checkFailure("read", path)
        files[path] ?: throw NoSuchElementException("No such file: $path")
    }

    override suspend fun write(path: String, data: ByteArray): Result<Unit> = runCatching {
        checkFailure("write", path)
        files[path] = data
        writes += path
        afterWrite(path)
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        checkFailure("delete", path)
        if (files.remove(path) == null) throw NoSuchElementException("No such file: $path")
    }

    override suspend fun list(directory: String): Result<List<String>> = runCatching {
        checkFailure("list", directory)
        val prefix = "$directory/"
        files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
    }

    override suspend fun exists(path: String): Result<Boolean> = runCatching {
        checkFailure("exists", path)
        files.containsKey(path)
    }
}

class NoteRepositoryTest {

    private fun note(id: String, epochSeconds: Long): Note = Note(
        id = id,
        capturedAt = Instant.fromEpochSeconds(epochSeconds),
        imagePath = "images/$id.jpg",
        text = "note $id",
    )

    @Test
    fun `save then get returns the same note`() = runTest {
        val repo = NoteRepository(InMemoryFileStore())
        val note = note("a1", 1_000)

        repo.save(note).getOrThrow()
        val loaded = repo.get("a1").getOrThrow()

        assertEquals(note, loaded)
    }

    @Test
    fun `list returns all notes sorted by capturedAt descending`() = runTest {
        val repo = NoteRepository(InMemoryFileStore())
        val oldest = note("a1", 1_000)
        val middle = note("a2", 2_000)
        val newest = note("a3", 3_000)

        repo.save(oldest).getOrThrow()
        repo.save(newest).getOrThrow()
        repo.save(middle).getOrThrow()

        val notes = repo.list().getOrThrow()

        assertEquals(3, notes.size)
        assertEquals(listOf("a3", "a2", "a1"), notes.map { it.id })
    }

    @Test
    fun `delete removes the note`() = runTest {
        val repo = NoteRepository(InMemoryFileStore())
        val note = note("a1", 1_000)
        repo.save(note).getOrThrow()

        repo.delete("a1").getOrThrow()

        assertTrue(repo.get("a1").isFailure)
    }

    @Test
    fun `get returns failure for non-existent note`() = runTest {
        val repo = NoteRepository(InMemoryFileStore())

        val result = repo.get("missing")

        assertTrue(result.isFailure)
    }

    @Test
    fun `list on empty store returns empty list`() = runTest {
        val repo = NoteRepository(InMemoryFileStore())

        val notes = repo.list().getOrThrow()

        assertTrue(notes.isEmpty())
    }

    @Test
    fun `exists reflects presence of note file`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        repo.save(note("a1", 1_000)).getOrThrow()

        assertTrue(store.exists("notes/a1.json").getOrThrow())
        assertFalse(store.exists("notes/nope.json").getOrThrow())
    }
}
