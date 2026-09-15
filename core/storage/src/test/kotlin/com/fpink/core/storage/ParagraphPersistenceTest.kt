package com.fpink.core.storage

import com.fpink.core.model.ImagePoint
import com.fpink.core.model.InkColorOrigin
import com.fpink.core.model.Note
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParagraphPersistenceTest {
    private val image = byteArrayOf(1, 2, 3, 4)

    private fun paragraphs(source: String = "source", count: Int = 3): List<Note> =
        (0 until count).map { index ->
            Note(
                id = "$source-$index",
                capturedAt = Instant.fromEpochSeconds(1_000),
                imagePath = "images/$source.png",
                text = "Paragraph $index",
                inkColorHex = "#123456",
                sourceId = source,
                paragraphIndex = index,
                paragraphPolygon = listOf(ImagePoint(.1f, .2f), ImagePoint(.8f, .2f), ImagePoint(.8f, .9f)),
                recognitionProvider = "paddle",
                recognitionModelVersion = "v5",
                inkColorOrigin = InkColorOrigin.DETECTED,
            )
        }

    private suspend fun NoteRepository.saveParagraphs(
        notes: List<Note> = paragraphs(),
        bytes: ByteArray = image,
    ): Result<Unit> = saveBatch(requireNotNull(notes.first().sourceId), bytes, "png", notes)

    @Test
    fun `old JSON keeps legacy values and defaults new metadata`() = runTest {
        val store = InMemoryFileStore()
        store.files["notes/old.json"] = """
            {"id":"old","capturedAt":"2025-01-02T03:04:05Z","imagePath":"images/old.jpg",
             "text":"Original handwriting","inkColorHex":"#abcdef","inkColorName":"Blue",
             "confidence":0.9,"modelNotes":"original","userEdited":true,"tags":["journal"],"links":["url"]}
        """.trimIndent().encodeToByteArray()
        val repo = NoteRepository(store)

        val note = repo.get("old").getOrThrow()
        assertEquals("Original handwriting", note.text)
        assertEquals("images/old.jpg", note.imagePath)
        assertEquals("#abcdef", note.inkColorHex)
        assertEquals(listOf("journal"), note.tags)
        assertNull(note.sourceId)
        assertNull(note.paragraphIndex)
        assertTrue(note.paragraphPolygon.isEmpty())
        assertNull(note.recognitionProvider)
        assertNull(note.recognitionModelVersion)
        assertNull(note.inkColorOrigin)
        assertEquals(listOf(note), repo.list().getOrThrow())
        repo.save(note.copy(text = "Edited legacy text")).getOrThrow()
        assertFalse(store.files.getValue("notes/old.json").decodeToString().contains("sourceId"))
        repo.delete("old").getOrThrow()
        assertTrue(repo.list().getOrThrow().isEmpty())
    }

    @Test
    fun `one image backs independent editable paragraph records and stable reading order`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        val notes = paragraphs(count = 12)

        repo.saveParagraphs(notes.reversed()).getOrThrow()

        assertEquals(notes, repo.list().getOrThrow())
        assertEquals(1, store.files.keys.count { it.startsWith("images/") })
        assertArrayEquals(image, store.files["images/source.png"])
        val edited = notes[1].copy(text = "My edit", inkColorHex = "#ff00ff", userEdited = true)
        repo.save(edited).getOrThrow()
        assertEquals(edited, repo.get(edited.id).getOrThrow())
        assertEquals(notes[0], repo.get(notes[0].id).getOrThrow())
        assertEquals(notes[2], repo.get(notes[2].id).getOrThrow())
        assertEquals(12, repo.list().getOrThrow().size)
    }

    @Test
    fun `cleaned tombstones require only linear manifest reads on restart`() = runTest {
        for (batchCount in listOf(100, 300)) {
            val store = InMemoryFileStore()
            repeat(batchCount) { index ->
                val source = "retired-$index"
                store.files["batches/$source.json"] = """
                    {"sourceId":"$source","imageExtension":"png","originalNoteIds":["$source-0"],"noteIds":[]}
                """.trimIndent().encodeToByteArray()
            }
            var manifestReads = 0
            store.fail = { operation, path ->
                if (operation == "read" && path.startsWith("batches/")) manifestReads++
                false
            }

            assertTrue(NoteRepository(store).list().getOrThrow().isEmpty())
            assertTrue(
                manifestReads <= batchCount * 3,
                "$batchCount cleaned tombstones caused $manifestReads manifest reads",
            )
            assertEquals(batchCount, store.files.size)
        }
    }

    @Test
    fun `equal timestamps keep distinct batches grouped deterministically`() = runTest {
        val repo = NoteRepository(InMemoryFileStore())
        repo.saveParagraphs(paragraphs("z")).getOrThrow()
        repo.saveParagraphs(paragraphs("a")).getOrThrow()

        assertEquals(
            listOf("a-0", "a-1", "a-2", "z-0", "z-1", "z-2"),
            repo.list().getOrThrow().map { it.id },
        )
    }

    @Test
    fun `publication follows durable source and all paragraph records`() = runTest {
        val store = InMemoryFileStore()
        val notes = paragraphs()
        store.afterWrite = { path ->
            if (path == "batches/source.json") {
                assertTrue(store.files.containsKey("images/source.png"))
                notes.forEach { assertTrue(store.files.containsKey("notes/${it.id}.json")) }
            } else if (path != "staging/source.json") {
                assertFalse(store.files.containsKey("batches/source.json"))
            }
        }

        NoteRepository(store).saveParagraphs(notes).getOrThrow()

        assertEquals(
            listOf("staging/source.json", "images/source.png", "notes/source-0.json",
                "notes/source-1.json", "notes/source-2.json", "batches/source.json"),
            store.writes,
        )
        assertFalse(store.files.containsKey("staging/source.json"))
    }

    @Test
    fun `failed source note or completion write stays invisible and restart permits retry`() = runTest {
        val failurePaths = listOf(
            "staging/source.json", "images/source.png", "notes/source-0.json",
            "notes/source-1.json", "notes/source-2.json", "batches/source.json",
        )
        for (failedPath in failurePaths) {
            val store = InMemoryFileStore()
            val repo = NoteRepository(store)
            store.fail = { operation, path -> operation == "write" && path == failedPath }

            assertTrue(repo.saveParagraphs().exceptionOrNull() is IOException, failedPath)
            assertFalse(store.files.containsKey("batches/source.json"), failedPath)
            store.fail = { _, _ -> false }
            val restarted = NoteRepository(store)
            assertTrue(restarted.list().getOrThrow().isEmpty(), failedPath)
            assertTrue(restarted.get("source-0").isFailure, failedPath)
            assertTrue(store.files.isEmpty(), failedPath)
            restarted.saveParagraphs().getOrThrow()
            assertEquals(paragraphs(), restarted.list().getOrThrow(), failedPath)
        }
    }

    @Test
    fun `restart rolls back a crash after each durable prepublication write`() = runTest {
        for (crashPath in listOf(
            "staging/source.json", "images/source.png", "notes/source-0.json",
            "notes/source-1.json", "notes/source-2.json",
        )) {
            val store = InMemoryFileStore()
            store.afterWrite = { if (it == crashPath) throw IOException("Simulated process loss") }
            assertTrue(NoteRepository(store).saveParagraphs().isFailure)
            store.afterWrite = {}

            assertTrue(NoteRepository(store).list().getOrThrow().isEmpty(), crashPath)
            assertTrue(store.files.isEmpty(), crashPath)
        }
    }

    @Test
    fun `failure after marker publication retains entire committed batch on restart`() = runTest {
        val store = InMemoryFileStore()
        store.afterWrite = { if (it == "batches/source.json") throw IOException("Lost acknowledgement") }
        assertTrue(NoteRepository(store).saveParagraphs().isFailure)
        store.afterWrite = {}

        val restarted = NoteRepository(store)
        assertEquals(paragraphs(), restarted.list().getOrThrow())
        restarted.saveParagraphs().getOrThrow()
        assertEquals(paragraphs(), restarted.list().getOrThrow())
        assertFalse(store.files.containsKey("staging/source.json"))
    }

    @Test
    fun `committed retry does not overwrite edited notes or recreate deleted paragraphs`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        val originals = paragraphs()
        repo.saveParagraphs(originals).getOrThrow()
        val edited = originals[1].copy(text = "Keep this user edit", userEdited = true)
        repo.save(edited).getOrThrow()
        repo.delete(originals[0].id).getOrThrow()
        val writeCount = store.writes.size

        NoteRepository(store).saveParagraphs(originals, byteArrayOf(9)).getOrThrow()

        assertEquals(writeCount, store.writes.size)
        assertEquals(listOf(edited, originals[2]), repo.list().getOrThrow())
        assertArrayEquals(image, store.files["images/source.png"])
        assertTrue(repo.get(originals[0].id).isFailure)
    }

    @Test
    fun `committed source lookup includes completed empty tombstones after restart`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        assertFalse(repo.isSourceCommitted("source").getOrThrow())
        assertTrue(repo.isSourceCommitted("../source").isFailure)

        repo.saveParagraphs().getOrThrow()
        assertTrue(repo.isSourceCommitted("source").getOrThrow())
        assertFalse(repo.isSourceCommitted("other").getOrThrow())
        repo.delete("source-0").getOrThrow()
        assertTrue(repo.isSourceCommitted("source").getOrThrow())
        repo.delete("source-1").getOrThrow()
        repo.delete("source-2").getOrThrow()

        val restarted = NoteRepository(store)
        assertTrue(restarted.list().getOrThrow().isEmpty())
        assertTrue(restarted.isSourceCommitted("source").getOrThrow())
        assertFalse(store.files.containsKey("images/source.png"))
    }

    @Test
    fun `committed source lookup recovers unpublished batches and propagates storage errors`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        store.fail = { operation, path -> operation == "write" && path == "batches/source.json" }
        assertTrue(repo.saveParagraphs().isFailure)
        store.fail = { _, _ -> false }
        assertFalse(NoteRepository(store).isSourceCommitted("source").getOrThrow())
        assertTrue(store.files.isEmpty())

        repo.saveParagraphs().getOrThrow()
        for ((operation, path) in listOf(
            "read" to "batches/source.json", "exists" to "batches/source.json",
            "read" to "notes/source-1.json", "exists" to "images/source.png",
        )) {
            store.fail = { actualOperation, actualPath -> actualOperation == operation && actualPath == path }
            assertTrue(repo.isSourceCommitted("source").exceptionOrNull() is IOException, "$operation $path")
        }
        store.fail = { _, _ -> false }
        store.files.remove("notes/source-1.json")
        assertTrue(repo.isSourceCommitted("source").isFailure)
    }

    @Test
    fun `image survives siblings and disappears after final deletion with retry tombstone`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        repo.saveParagraphs().getOrThrow()
        repo.saveParagraphs(paragraphs("other", 1)).getOrThrow()

        repo.delete("source-1").getOrThrow()
        assertTrue(store.files.containsKey("images/source.png"))
        assertEquals(listOf("other-0", "source-0", "source-2"), repo.list().getOrThrow().map { it.id })
        repo.delete("source-0").getOrThrow()
        assertTrue(store.files.containsKey("images/source.png"))
        repo.delete("source-2").getOrThrow()
        assertFalse(store.files.containsKey("images/source.png"))
        assertTrue(store.files.containsKey("images/other.png"))
        assertTrue(store.files.containsKey("batches/source.json"))

        NoteRepository(store).saveParagraphs().getOrThrow()
        assertEquals(listOf("other-0"), repo.list().getOrThrow().map { it.id })
        assertFalse(store.files.containsKey("images/source.png"))
        assertTrue(repo.save(paragraphs()[0]).isFailure)
        assertTrue(repo.save(paragraphs()[0].copy(sourceId = null)).isFailure)
    }

    @Test
    fun `shared images respect references from standalone notes too`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        val notes = paragraphs(count = 1)
        repo.saveParagraphs(notes).getOrThrow()
        repo.save(notes[0].copy(id = "legacy", sourceId = null, paragraphIndex = null)).getOrThrow()

        repo.delete("source-0").getOrThrow()
        assertTrue(store.files.containsKey("images/source.png"))
        repo.delete("legacy").getOrThrow()
        assertFalse(store.files.containsKey("images/source.png"))
    }

    @Test
    fun `standalone deletion uses actual stored image path and preserves other references`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        val first = paragraphs()[0].copy(id = "legacy-a", sourceId = null, paragraphIndex = null)
        val second = first.copy(id = "legacy-b")
        store.files["images/source.png"] = image
        store.files["images/unrelated.jpg"] = image
        repo.save(first).getOrThrow()
        repo.save(second).getOrThrow()

        repo.delete(first.id).getOrThrow()
        assertTrue(store.files.containsKey("images/source.png"))
        repo.delete(second.id).getOrThrow()
        assertFalse(store.files.containsKey("images/source.png"))
        assertTrue(store.files.containsKey("images/unrelated.jpg"))
    }

    @Test
    fun `batch deletion publication failure leaves note visible`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        repo.saveParagraphs().getOrThrow()
        store.fail = { operation, path -> operation == "write" && path == "batches/source.json" }

        assertTrue(repo.delete("source-0").exceptionOrNull() is IOException)
        store.fail = { _, _ -> false }
        assertEquals(paragraphs(), NoteRepository(store).list().getOrThrow())
    }

    @Test
    fun `published deletion recovers after note or final image cleanup fails`() = runTest {
        for (failedPath in listOf("notes/source-0.json", "images/source.png")) {
            val store = InMemoryFileStore()
            val repo = NoteRepository(store)
            repo.saveParagraphs(paragraphs(count = 1)).getOrThrow()
            store.fail = { operation, path -> operation == "delete" && path == failedPath }

            assertTrue(repo.delete("source-0").exceptionOrNull() is IOException, failedPath)
            assertTrue(repo.list().exceptionOrNull() is IOException, failedPath)
            store.fail = { _, _ -> false }
            val restarted = NoteRepository(store)
            restarted.delete("source-0").getOrThrow()
            assertTrue(restarted.list().getOrThrow().isEmpty(), failedPath)
            assertFalse(store.files.containsKey("notes/source-0.json"), failedPath)
            assertFalse(store.files.containsKey("images/source.png"), failedPath)
        }
    }

    @Test
    fun `legacy image cleanup errors are explicit and recover after restart`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        val note = paragraphs()[0].copy(id = "legacy", sourceId = null, paragraphIndex = null)
        store.files[note.imagePath] = image
        repo.save(note).getOrThrow()
        store.fail = { operation, path -> operation == "delete" && path == note.imagePath }

        assertTrue(repo.delete(note.id).exceptionOrNull() is IOException)
        assertTrue(repo.list().exceptionOrNull() is IOException)
        store.fail = { _, _ -> false }
        assertTrue(NoteRepository(store).list().getOrThrow().isEmpty())
        assertFalse(store.files.containsKey(note.imagePath))
        assertFalse(store.files.containsKey("deletions/legacy.json"))
    }

    @Test
    fun `staging cleanup errors are explicit and do not delete unrelated files`() = runTest {
        val store = InMemoryFileStore()
        store.files["images/unrelated.png"] = image
        store.files["unrelated/keep.txt"] = image
        val repo = NoteRepository(store)
        store.fail = { operation, path -> operation == "write" && path == "batches/source.json" }
        assertTrue(repo.saveParagraphs().isFailure)
        store.fail = { operation, path -> operation == "delete" && path == "images/source.png" }

        assertTrue(NoteRepository(store).list().exceptionOrNull() is IOException)
        assertTrue(store.files.containsKey("staging/source.json"))
        assertTrue(store.files.containsKey("images/unrelated.png"))
        assertTrue(store.files.containsKey("unrelated/keep.txt"))
        store.fail = { _, _ -> false }
        assertTrue(NoteRepository(store).list().getOrThrow().isEmpty())
        assertEquals(setOf("images/unrelated.png", "unrelated/keep.txt"), store.files.keys)
    }

    @Test
    fun `failure removing a published intent is explicit but retry preserves the batch`() = runTest {
        val store = InMemoryFileStore()
        store.fail = { operation, path -> operation == "delete" && path == "staging/source.json" }
        val repo = NoteRepository(store)
        assertTrue(repo.saveParagraphs().exceptionOrNull() is IOException)
        assertTrue(repo.list().exceptionOrNull() is IOException)
        store.fail = { _, _ -> false }

        NoteRepository(store).saveParagraphs().getOrThrow()
        assertEquals(paragraphs(), repo.list().getOrThrow())
    }

    @Test
    fun `listing read exists and malformed JSON failures are never an empty success`() = runTest {
        for ((operation, path) in listOf(
            "list" to "staging", "list" to "batches", "list" to "notes",
            "read" to "notes/source-1.json", "read" to "batches/source.json",
            "exists" to "images/source.png",
        )) {
            val store = InMemoryFileStore()
            val repo = NoteRepository(store)
            repo.saveParagraphs().getOrThrow()
            store.fail = { actualOperation, actualPath -> actualOperation == operation && actualPath == path }
            assertTrue(repo.list().exceptionOrNull() is IOException, "$operation $path")
        }
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        repo.saveParagraphs().getOrThrow()
        store.files["notes/source-1.json"] = "broken JSON".encodeToByteArray()
        assertTrue(repo.list().isFailure)
        assertTrue(repo.get("source-0").isFailure)
    }

    @Test
    fun `missing committed records or images fail rather than expose a partial batch`() = runTest {
        for (path in listOf("notes/source-1.json", "images/source.png")) {
            val store = InMemoryFileStore()
            val repo = NoteRepository(store)
            repo.saveParagraphs().getOrThrow()
            store.files.remove(path)

            assertTrue(repo.list().isFailure, path)
            assertTrue(repo.get("source-0").isFailure, path)
            assertTrue(repo.saveParagraphs().isFailure, path)
        }
    }

    @Test
    fun `a flat paragraph record without a completion marker is never visible`() = runTest {
        val store = InMemoryFileStore()
        val note = paragraphs()[0]
        store.files["notes/${note.id}.json"] = Json.encodeToString(note).encodeToByteArray()
        val repo = NoteRepository(store)

        assertTrue(repo.list().getOrThrow().isEmpty())
        assertTrue(repo.get(note.id).isFailure)
        assertTrue(repo.save(note).isFailure)
    }

    @Test
    fun `unsafe IDs extensions ownership and empty batches fail before writing`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        val notes = paragraphs()

        assertTrue(repo.saveBatch("../bad", image, "png", notes).isFailure)
        assertTrue(repo.saveBatch("source", image, "../png", notes).isFailure)
        assertTrue(repo.saveBatch("source", image, "png", emptyList()).isFailure)
        assertTrue(repo.saveBatch("source", byteArrayOf(), "png", notes).isFailure)
        assertTrue(repo.saveParagraphs(listOf(notes[0], notes[0])).isFailure)
        assertTrue(repo.saveParagraphs(listOf(notes[0].copy(id = "../outside"))).isFailure)
        assertTrue(repo.saveParagraphs(listOf(notes[0].copy(imagePath = "images/../outside.png"))).isFailure)
        assertTrue(repo.saveParagraphs(listOf(notes[0].copy(paragraphIndex = 1))).isFailure)
        assertTrue(repo.get("../outside").isFailure)
        assertTrue(repo.delete("../outside").isFailure)
        assertTrue(repo.saveImage("../outside", image).isFailure)
        assertTrue(repo.save(notes[0].copy(sourceId = null, imagePath = "/outside.png")).isFailure)
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun `existing images and unrelated note IDs cannot be overwritten or cleaned by a batch`() = runTest {
        for (conflictPath in listOf("images/source.png", "notes/source-0.json")) {
            val store = InMemoryFileStore()
            val original = if (conflictPath.startsWith("notes/")) {
                Json.encodeToString(paragraphs()[0].copy(sourceId = null)).encodeToByteArray()
            } else {
                image
            }
            store.files[conflictPath] = original

            assertTrue(NoteRepository(store).saveParagraphs().isFailure)
            assertEquals(setOf(conflictPath), store.files.keys)
            assertArrayEquals(original, store.files[conflictPath])
        }
    }

    @Test
    fun `invalid recovery metadata fails without deleting unrelated files`() = runTest {
        val store = InMemoryFileStore()
        val unrelated = paragraphs()[0].copy(id = "keep", sourceId = null, imagePath = "images/keep.png")
        store.files["notes/keep.json"] = Json.encodeToString(unrelated).encodeToByteArray()
        store.files["images/keep.png"] = image
        store.files["staging/source.json"] = """
            {"sourceId":"source","imageExtension":"png","originalNoteIds":["keep"]}
        """.trimIndent().encodeToByteArray()
        val snapshot = store.files.toMap()

        assertTrue(NoteRepository(store).list().isFailure)
        assertEquals(snapshot, store.files)
    }

    @Test
    fun `recovery refuses to remove a legacy note even when its ID matches a staged paragraph`() = runTest {
        val store = InMemoryFileStore()
        store.fail = { operation, path -> operation == "write" && path == "batches/source.json" }
        assertTrue(NoteRepository(store).saveParagraphs().isFailure)
        store.fail = { _, _ -> false }
        val unrelated = paragraphs()[0].copy(sourceId = null)
        val bytes = Json.encodeToString(unrelated).encodeToByteArray()
        store.files["notes/source-0.json"] = bytes

        assertTrue(NoteRepository(store).list().isFailure)
        assertArrayEquals(bytes, store.files["notes/source-0.json"])
        assertTrue(store.files.containsKey("images/source.png"))
    }

    @Test
    fun `legacy save read image and deletion-intent I O failures remain explicit`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        val original = paragraphs()[0].copy(
            id = "legacy", sourceId = null, paragraphIndex = null, imagePath = "images/legacy.jpg",
        )
        repo.save(original).getOrThrow()
        for ((operation, path) in listOf(
            "read" to "notes/legacy.json", "write" to "notes/legacy.json",
            "write" to "images/legacy.jpg", "write" to "deletions/legacy.json",
        )) {
            store.fail = { actualOperation, actualPath -> actualOperation == operation && actualPath == path }
            val result = when {
                operation == "read" -> repo.get(original.id)
                path.startsWith("notes/") -> repo.save(original.copy(text = "Do not persist"))
                path.startsWith("images/") -> repo.saveImage(original.id, image)
                else -> repo.delete(original.id)
            }
            assertTrue(result.exceptionOrNull() is IOException, "$operation $path")
            store.fail = { _, _ -> false }
            assertEquals(original, repo.get(original.id).getOrThrow())
        }
    }

    @Test
    fun `legacy image API cannot replace a committed batch source`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        val notes = paragraphs().map { it.copy(imagePath = "images/source.jpg") }
        repo.saveBatch("source", image, "jpg", notes).getOrThrow()

        assertTrue(repo.saveImage("source", byteArrayOf(9)).isFailure)
        assertArrayEquals(image, store.files["images/source.jpg"])
        assertEquals(notes, repo.list().getOrThrow())
    }

    @Test
    fun `edited paragraphs cannot migrate ownership or change their shared source`() = runTest {
        val store = InMemoryFileStore()
        val repo = NoteRepository(store)
        val note = paragraphs()[0]
        repo.saveParagraphs().getOrThrow()

        assertTrue(repo.save(note.copy(sourceId = null)).isFailure)
        assertTrue(repo.save(note.copy(sourceId = "other")).isFailure)
        assertTrue(repo.save(note.copy(imagePath = "images/other.png")).isFailure)
        assertTrue(repo.save(note.copy(paragraphIndex = 7)).isFailure)
        assertTrue(repo.saveParagraphs(paragraphs(count = 2)).isFailure)
        assertEquals(note, repo.get(note.id).getOrThrow())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `concurrent repositories cannot see staging or race publication with edits`() = runTest {
        val store = InMemoryFileStore()
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        store.afterWrite = {
            if (it == "notes/source-0.json") {
                paused.complete(Unit)
                resume.await()
            }
        }
        val writer = NoteRepository(store)
        val reader = NoteRepository(store)
        val saving = async { writer.saveParagraphs() }
        paused.await()
        val listing = async { reader.list() }
        runCurrent()
        assertFalse(listing.isCompleted)
        resume.complete(Unit)
        saving.await().getOrThrow()
        assertEquals(paragraphs(), listing.await().getOrThrow())
    }

    @Test
    fun `cancellation is not converted into successful recovery or a normal result`() = runTest {
        val store = InMemoryFileStore()
        store.afterWrite = { if (it == "notes/source-0.json") throw CancellationException("Cancelled") }
        var cancelled = false
        try {
            NoteRepository(store).saveParagraphs()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        store.afterWrite = {}
        assertTrue(NoteRepository(store).list().getOrThrow().isEmpty())
    }
}
