package com.fpink.recognition.paddle

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fpink.core.ai.PreparedImage
import com.fpink.core.ai.RecognitionProviderId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on ARM64 with networking disabled; this test APK requests no network permission. */
@RunWith(AndroidJUnit4::class)
class PaddleNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledModelsRecognizeEnglishAndMapOriginalCoordinates() = runBlocking {
        PaddleOcrProvider.readiness(context).getOrThrow()
        val image = fixture("HELLO WORLD")
        val result = PaddleOcrProvider(context).recognize(image).getOrThrow()
        assertEquals(RecognitionProviderId.PADDLE, result.provider)
        assertTrue("Expected actual English recognition, got ${result.regions.map { it.text }}",
            result.regions.any { it.text.uppercase().contains("HELLO") })
        val region = result.regions.first { it.text.uppercase().contains("HELLO") }
        assertTrue(region.polygon.all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(region.polygon.minOf { it.x } in 0.05f..0.3f)
        assertTrue(region.polygon.minOf { it.y } in 0.2f..0.5f)
        assertTrue(region.polygon.maxOf { it.y } in 0.4f..0.7f)
    }

    @Test fun blankPageCreatesNoFakeRecognition() = runBlocking {
        val result = PaddleOcrProvider(context).recognize(fixture("")).getOrThrow()
        assertTrue(result.regions.isEmpty())
    }

    @Test fun preCancelledNativeJobNeverReturnsLines() {
        NativeBridge.load()
        val cancelled = Job().also { it.cancel() }
        try {
            NativeBridge.recognize("", "", "", IntArray(256), 16, 16, NativeCancellation(cancelled))
            throw AssertionError("Native cancellation must propagate")
        } catch (_: CancellationException) {
            // This must be thrown before opening a model or allocating predictors.
        }
    }

    @Test fun cancellationIsNotAResultFailure() = runBlocking {
        var returned = false
        val worker = launch {
            PaddleOcrProvider(context).recognize(fixture("HELLO"))
            returned = true
        }
        worker.cancelAndJoin()
        assertFalse(returned)
    }

    @Test fun repeatedProviderUseDoesNotRetainBlankOrPreviousResults() = runBlocking {
        val provider = PaddleOcrProvider(context)
        val first = provider.recognize(fixture("HELLO WORLD")).getOrThrow()
        assertTrue(first.regions.any { it.text.uppercase().contains("HELLO") })
        assertTrue(provider.recognize(fixture("")).getOrThrow().regions.isEmpty())
        val repeated = provider.recognize(fixture("HELLO WORLD")).getOrThrow()
        assertEquals(first.regions.map { it.text }, repeated.regions.map { it.text })
        assertFalse(first === repeated)
    }

    @Test fun partialExtractedModelsAreRepairedFromVerifiedBundledAssets() = runBlocking {
        val root = File(context.cacheDir, "paddle-recovery-${UUID.randomUUID()}")
        check(root.mkdir())
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        try {
            val models = File(root, "paddle-v5")
            check(models.mkdir())
            File(models, "PP-OCRv5_mobile_det.nb").writeBytes(byteArrayOf(1, 2, 3))
            File(models, "PP-OCRv5_mobile_rec.nb.pending").writeBytes(byteArrayOf(4, 5))
            val result = PaddleOcrProvider(isolated).recognize(fixture("HELLO WORLD")).getOrThrow()
            assertTrue(result.regions.any { it.text.uppercase().contains("HELLO") })
            for (name in listOf("PP-OCRv5_mobile_det.nb", "PP-OCRv5_mobile_rec.nb", "ppocr_keys_ocrv5.txt")) {
                assertArrayEquals(
                    digest(context.assets.open("paddle/$name")),
                    digest(File(models, name).inputStream()),
                )
            }
            assertFalse(models.listFiles().orEmpty().any { it.name.endsWith(".pending") })
        } finally {
            check(root.deleteRecursively()) { "Could not remove test-owned model cache" }
        }
    }

    private fun digest(input: InputStream): ByteArray = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest()
    }

    private fun fixture(text: String): PreparedImage {
        val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(text, 180f, 310f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 86f
        })
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
        bitmap.recycle()
        return PreparedImage(bytes, "image/png", 1200, 600, pixels)
    }
}
