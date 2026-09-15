package com.fpink.capture.acceptance

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.data.AndroidFileStore
import com.fpink.core.model.InkColorOrigin
import com.fpink.core.model.Note
import com.fpink.core.storage.FileStore
import com.fpink.core.storage.NoteRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteStorageAcceptanceTest {
    private lateinit var storage: AcceptanceStorage
    private lateinit var disk: AndroidFileStore

    @Before fun createStorage() {
        storage = AcceptanceStorage()
        disk = AndroidFileStore(storage.context)
    }

    @After fun cleanUp() {
        storage.close()
    }

    @Test fun paragraphRecordsSurviveRestartWithSharedSourceLifetime() = runBlocking {
        val image = imageBytes()
        val notes = paragraphs()
        NoteRepository(disk).saveBatch(storage.id, image, "png", notes).getOrThrow()

        val restored = NoteRepository(AndroidFileStore(storage.context))
        assertEquals(notes, restored.list().getOrThrow())
        assertArrayEquals(image, disk.read(notes.first().imagePath).getOrThrow())
        val edited = notes.first().copy(
            text = "Independently edited text",
            inkColorHex = "#112233",
            inkColorName = "Blue",
            inkColorOrigin = InkColorOrigin.USER_SELECTED,
            userEdited = true,
        )
        restored.save(edited).getOrThrow()
        assertEquals(edited, NoteRepository(disk).get(edited.id).getOrThrow())
        assertEquals(notes.last(), restored.get(notes.last().id).getOrThrow())

        restored.delete(edited.id).getOrThrow()
        assertTrue(disk.exists(edited.imagePath).getOrThrow())
        assertEquals(notes.last(), restored.get(notes.last().id).getOrThrow())
        restored.delete(notes.last().id).getOrThrow()
        assertFalse(disk.exists(edited.imagePath).getOrThrow())
        assertTrue(restored.isSourceCommitted(storage.id).getOrThrow())
        restored.saveBatch(storage.id, image, "png", notes).getOrThrow()
        assertTrue(restored.list().getOrThrow().isEmpty())
        assertFalse(disk.exists(edited.imagePath).getOrThrow())
    }

    @Test fun interruptedBatchPublicationIsRecoveredWithoutExposingPartialNotes() = runBlocking {
        val image = imageBytes()
        val notes = paragraphs()
        val failPublication = object : FileStore by disk {
            override suspend fun write(path: String, data: ByteArray): Result<Unit> =
                if (path == "batches/${storage.id}.json") {
                    Result.failure(IOException("Simulated publication failure"))
                } else {
                    disk.write(path, data)
                }
        }
        val result = NoteRepository(failPublication).saveBatch(storage.id, image, "png", notes)
        assertTrue(result.exceptionOrNull() is IOException)

        val restored = NoteRepository(AndroidFileStore(storage.context))
        assertTrue(restored.list().getOrThrow().isEmpty())
        assertFalse(disk.exists(notes.first().imagePath).getOrThrow())
        assertTrue(disk.list("staging").getOrThrow().isEmpty())
        restored.saveBatch(storage.id, image, "png", notes).getOrThrow()
        assertEquals(notes, restored.list().getOrThrow())
        assertArrayEquals(image, disk.read(notes.first().imagePath).getOrThrow())
    }

    private fun paragraphs(): List<Note> = (0..1).map { index ->
        Note(
            id = "${storage.id}-$index",
            capturedAt = Instant.fromEpochSeconds(1_000),
            imagePath = "images/${storage.id}.png",
            text = "Paragraph $index",
            inkColorHex = "#000000",
            inkColorName = "Black",
            inkColorOrigin = InkColorOrigin.DEFAULTED,
            sourceId = storage.id,
            paragraphIndex = index,
        )
    }

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.WHITE)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
