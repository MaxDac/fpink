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
private class InMemoryFileStore : FileStore {
    private val files = mutableMapOf<String, ByteArray>()

    override suspend fun read(path: String): Result<ByteArray> = runCatching {
        files[path] ?: throw NoSuchElementException("No such file: $path")
    }

    override suspend fun write(path: String, data: ByteArray): Result<Unit> = runCatching {
        files[path] = data
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        if (files.remove(path) == null) throw NoSuchElementException("No such file: $path")
    }

    override suspend fun list(directory: String): Result<List<String>> = runCatching {
        val prefix = "$directory/"
        files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
    }

    override suspend fun exists(path: String): Result<Boolean> = runCatching {
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
