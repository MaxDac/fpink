package com.fpink.core.storage

import com.fpink.core.model.MarkdownVaultExport
import com.fpink.core.model.Note
import com.fpink.core.model.VaultOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownVaultArchiveTest {
    private val options = VaultOptions(groupByCategory = false, exportedAt = Instant.fromEpochSeconds(0), timeZone = TimeZone.UTC)

    @Test
    fun `archive contains each shared image once and embeds only available images`() = runTest {
        val store = InMemoryFileStore()
        val repository = NoteRepository(store)
        val paragraphs = (0..1).map {
            note("src-$it", "Paragraph $it").copy(imagePath = "images/src.png", sourceId = "src", paragraphIndex = it)
        }
        repository.saveBatch("src", byteArrayOf(7, 8), "png", paragraphs).getOrThrow()
        repository.save(note("legacy", "No image")).getOrThrow()
        val notes = repository.list().getOrThrow()

        val output = ByteArrayOutputStream()
        val summary = repository.readSourceImages { read -> MarkdownVaultArchive.write(notes, options, output, read) }.getOrThrow()

        val entries = unzip(output.toByteArray())
        assertEquals(3, summary.notes)
        assertEquals(1, summary.attachments)
        assertArrayEquals(byteArrayOf(7, 8), entries.getValue("attachments/src.png"))
        assertEquals(1, entries.keys.count { it.startsWith("attachments/") })
        assertTrue(entries.containsKey(MarkdownVaultExport.INDEX_FILE))
        val markdown = entries.filterKeys { it.endsWith(".md") && it != MarkdownVaultExport.INDEX_FILE }
            .mapValues { it.value.decodeToString() }
        assertEquals(3, markdown.size)
        assertEquals(2, markdown.values.count { it.contains("![[attachments/src.png]]") })
        assertFalse(markdown.values.single { it.contains("No image") }.contains("![["))
    }

    @Test
    fun `reading an image of a deleted note fails`() = runTest {
        val repository = NoteRepository(InMemoryFileStore())
        val note = note("gone", "Gone")
        repository.save(note).getOrThrow()
        assertNull(repository.readSourceImages { it(note) }.getOrThrow())
        repository.delete("gone").getOrThrow()
        assertTrue(repository.readSourceImages { it(note) }.isFailure)
    }

    @Test
    fun `exporting many sources reads each batch manifest a bounded number of times`() = runTest {
        val store = InMemoryFileStore()
        val repository = NoteRepository(store)
        val sources = 30
        repeat(sources) { index ->
            val paragraph = note("src$index-0", "Paragraph $index")
                .copy(imagePath = "images/src$index.png", sourceId = "src$index", paragraphIndex = 0)
            repository.saveBatch("src$index", byteArrayOf(index.toByte()), "png", listOf(paragraph)).getOrThrow()
        }
        val notes = repository.list().getOrThrow()
        val manifestReads = mutableListOf<String>()
        store.fail = { operation, path ->
            if (operation == "read" && path.startsWith("batches/")) manifestReads += path
            false
        }

        val summary = repository.readSourceImages { read ->
            MarkdownVaultArchive.write(notes, options, ByteArrayOutputStream(), read)
        }.getOrThrow()

        assertEquals(sources, summary.attachments)
        // One recovery pass plus one read per exported source, not one recovery pass per image.
        assertTrue(manifestReads.size <= 2 * sources) { "${manifestReads.size} manifest reads" }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun note(id: String, text: String) = Note(
        id = id,
        capturedAt = Instant.fromEpochSeconds(1_000),
        imagePath = "images/$id.jpg",
        text = text,
    )
}
