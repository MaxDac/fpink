package com.fpink.capture.nativeacceptance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.acceptance.AcceptanceStorage
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.capture.data.RecognitionJobState
import com.fpink.core.ai.DefaultNoteProcessor
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.model.InkColorOrigin
import com.fpink.core.storage.NoteRepository
import com.fpink.recognition.kraken.KrakenOcrProvider
import com.fpink.recognition.paddle.PaddleOcrProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately with the target APK installed for arm64-v8a, including on a native-bridge emulator. */
@RunWith(AndroidJUnit4::class)
class NativePipelineAcceptanceTest {
    @Test fun importedPageCreatesTwoIndependentLocallyColouredParagraphNotes() = runBlocking {
        AcceptanceStorage().use { storage ->
            val original = storage.images.newCameraFile()
            val bitmap = Bitmap.createBitmap(1200, 1100, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(20, 50, 180)
                    textSize = 74f
                }
                canvas.drawText("HELLO WORLD", 160f, 180f, ink)
                canvas.drawText("BLUE INK", 160f, 270f, ink)
                ink.color = Color.rgb(20, 130, 40)
                canvas.drawText("LOCAL NOTES", 160f, 720f, ink)
                canvas.drawText("GREEN INK", 160f, 810f, ink)
                original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val staged = storage.images.importCamera(original)
            assertFalse(original.exists())
            val disk = AndroidFileStore(storage.context)
            val repository = NoteRepository(disk)
            val coordinator = RecognitionCoordinator(
                storage.images, storage.settings, repository, DefaultNoteProcessor(),
            ) { settings ->
                assertEquals("This test must never select or contact a cloud provider", RecognitionProviderId.PADDLE, settings.provider)
                PaddleOcrProvider(storage.context)
            }
            assertEquals(RecognitionProviderId.PADDLE, coordinator.confirm(staged.sourceId).provider)
            coordinator.start(staged.sourceId)
            val completed = withTimeout(90_000) {
                coordinator.state(staged.sourceId).first {
                    it is RecognitionJobState.Complete || it is RecognitionJobState.Failed
                }
            }
            assertTrue("Pipeline did not complete: $completed", completed is RecognitionJobState.Complete)
            assertEquals(2, (completed as RecognitionJobState.Complete).count)
            assertFalse(completed.cleanupWarning)

            val notes = NoteRepository(AndroidFileStore(storage.context)).list().getOrThrow()
            assertEquals(2, notes.size)
            assertEquals(listOf(0, 1), notes.map { it.paragraphIndex })
            assertEquals(listOf("Blue", "Green"), notes.map { it.inkColorName })
            assertTrue(notes.first().text.contains("HELLO", ignoreCase = true))
            assertTrue(notes.all {
                it.text.isNotBlank() && it.sourceId == staged.sourceId &&
                    it.recognitionProvider == RecognitionProviderId.PADDLE.id &&
                    it.inkColorOrigin == InkColorOrigin.DETECTED
            })
            assertEquals(1, notes.map { it.imagePath }.distinct().size)
            assertTrue(disk.exists(notes.first().imagePath).getOrThrow())
            assertFalse(storage.images.previewFile(staged.sourceId).exists())
            coordinator.release(staged.sourceId)
        }
    }

    /** Needs real arm64 hardware: Kraken fails closed under x86 ARM-translation layers, so it is skipped there. */
    @Test fun importedPageIsRecognizedLocallyByKraken() = runBlocking {
        assumeTrue("Kraken needs a native arm64-v8a device", Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        AcceptanceStorage().use { storage ->
            assertTrue("Kraken readiness failed", KrakenOcrProvider.readiness(storage.context).isSuccess)
            storage.settings.save(RecognitionProviderId.KRAKEN, removeConfig = true)
            val original = storage.images.newCameraFile()
            val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(20, 50, 180)
                    textSize = 74f
                }
                canvas.drawText("HELLO WORLD", 160f, 180f, ink)
                canvas.drawText("BLUE INK", 160f, 270f, ink)
                original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val staged = storage.images.importCamera(original)
            val repository = NoteRepository(AndroidFileStore(storage.context))
            val coordinator = RecognitionCoordinator(
                storage.images, storage.settings, repository, DefaultNoteProcessor(),
            ) { settings ->
                assertEquals("This test must never select or contact a cloud provider", RecognitionProviderId.KRAKEN, settings.provider)
                KrakenOcrProvider(storage.context)
            }
            assertEquals(RecognitionProviderId.KRAKEN, coordinator.confirm(staged.sourceId).provider)
            coordinator.start(staged.sourceId)
            val completed = withTimeout(180_000) {
                coordinator.state(staged.sourceId).first {
                    it is RecognitionJobState.Complete || it is RecognitionJobState.Failed
                }
            }
            assertTrue("Pipeline did not complete: $completed", completed is RecognitionJobState.Complete)
            val notes = repository.list().getOrThrow()
            assertTrue("Kraken produced no notes", notes.isNotEmpty())
            assertTrue(notes.all { it.text.isNotBlank() && it.recognitionProvider == RecognitionProviderId.KRAKEN.id })
            coordinator.release(staged.sourceId)
        }
    }
}
