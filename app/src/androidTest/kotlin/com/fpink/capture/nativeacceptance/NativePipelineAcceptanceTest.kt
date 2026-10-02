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
import com.fpink.core.ai.RecognitionStrategyId
import com.fpink.core.model.InkColorOrigin
import com.fpink.core.model.Note
import com.fpink.core.storage.NoteRepository
import com.fpink.recognition.strategies.BuiltInStrategies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the whole app pipeline with each built-in on-device strategy. ONNX Runtime ships native
 * arm64-v8a and x86_64 libraries; under an ARM-translation layer the strategies fail closed, so
 * these tests only run where the primary ABI is one of those two.
 */
@RunWith(AndroidJUnit4::class)
class NativePipelineAcceptanceTest {
    @Before fun nativeAbi() {
        assumeTrue("ONNX Runtime needs a native arm64-v8a or x86_64 ABI", Build.SUPPORTED_ABIS.firstOrNull() in setOf("arm64-v8a", "x86_64"))
    }

    @Test fun printedPageCreatesTwoIndependentLocallyColouredParagraphNotes() = runBlocking {
        AcceptanceStorage().use { storage ->
            val (notes, sourceId, disk) = recognize(storage, RecognitionStrategyId.PRINTED, colouredPage = true)
            assertEquals(2, notes.size)
            assertEquals(listOf(0, 1), notes.map { it.paragraphIndex })
            assertEquals(listOf("Blue", "Green"), notes.map { it.inkColorName })
            assertTrue(notes.first().text, notes.first().text.contains("HELLO", ignoreCase = true))
            assertTrue(notes.all {
                it.text.isNotBlank() && it.sourceId == sourceId &&
                    it.recognitionProvider == RecognitionStrategyId.PRINTED.id &&
                    it.recognitionModelVersion?.contains("PP-OCRv6_medium_rec") == true &&
                    it.inkColorOrigin == InkColorOrigin.DETECTED
            })
            assertEquals(1, notes.map { it.imagePath }.distinct().size)
            assertTrue(disk.exists(notes.first().imagePath).getOrThrow())
            assertFalse(storage.images.previewFile(sourceId).exists())
        }
    }

    @Test fun cursiveStrategyRecognizesAPageLocally() = runBlocking {
        AcceptanceStorage().use { storage ->
            storage.settings.save(RecognitionStrategyId.CURSIVE, removeConfig = true)
            val (notes, _, _) = recognize(storage, RecognitionStrategyId.CURSIVE, colouredPage = false)
            assertTrue("Cursive produced no notes", notes.isNotEmpty())
            assertTrue(notes.all {
                it.text.isNotBlank() && it.recognitionProvider == RecognitionStrategyId.CURSIVE.id &&
                    it.recognitionModelVersion?.contains("kraken") == true
            })
        }
    }

    private suspend fun recognize(
        storage: AcceptanceStorage,
        strategy: RecognitionStrategyId,
        colouredPage: Boolean,
    ): Triple<List<Note>, String, AndroidFileStore> {
        val original = storage.images.newCameraFile()
        val bitmap = Bitmap.createBitmap(1200, if (colouredPage) 1100 else 600, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(20, 50, 180)
                textSize = 74f
            }
            canvas.drawText("HELLO WORLD", 160f, 180f, ink)
            canvas.drawText("BLUE INK", 160f, 270f, ink)
            if (colouredPage) {
                ink.color = Color.rgb(20, 130, 40)
                canvas.drawText("LOCAL NOTES", 160f, 720f, ink)
                canvas.drawText("GREEN INK", 160f, 810f, ink)
            }
            original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val staged = storage.images.importCamera(original)
        assertFalse(original.exists())
        val disk = AndroidFileStore(storage.context)
        val coordinator = RecognitionCoordinator(
            storage.images, storage.settings, NoteRepository(disk), DefaultNoteProcessor(),
        ) { settings ->
            assertEquals("This test must never select or contact a cloud service", strategy, settings.strategy)
            checkNotNull(BuiltInStrategies.create(storage.context, settings.strategy))
        }
        // The camera override path: a built-in strategy chosen on the camera screen.
        assertEquals(strategy, coordinator.confirm(staged.sourceId, strategy).strategy)
        coordinator.start(staged.sourceId)
        val completed = withTimeout(180_000) {
            coordinator.state(staged.sourceId).first {
                it is RecognitionJobState.Complete || it is RecognitionJobState.Failed
            }
        }
        assertTrue("Pipeline did not complete: $completed", completed is RecognitionJobState.Complete)
        assertFalse((completed as RecognitionJobState.Complete).cleanupWarning)
        coordinator.release(staged.sourceId)
        return Triple(NoteRepository(AndroidFileStore(storage.context)).list().getOrThrow(), staged.sourceId, disk)
    }
}