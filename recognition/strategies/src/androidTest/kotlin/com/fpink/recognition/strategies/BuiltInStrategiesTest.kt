package com.fpink.recognition.strategies

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fpink.core.ai.PreparedImage
import com.fpink.core.ai.RecognitionStrategyId
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises both built-in strategies end to end with the bundled, pinned ONNX models. The test APK
 * requests no network permission: models must never be downloaded.
 *
 * The images are rendered on-device with Android's own fonts; the italic serif line is a
 * connected-letterform stand-in, not real handwriting (see recognition/models/README.md).
 */
@RunWith(AndroidJUnit4::class)
class BuiltInStrategiesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bothStrategiesAreReadyWithBundledAssets() = runBlocking {
        for (id in BuiltInStrategies.ids) {
            val result = strategy(id).readiness()
            assertTrue("$id readiness failed: ${result.exceptionOrNull()}", result.isSuccess)
        }
    }

    @Test fun testApkDoesNotDeclareInternetPermission() {
        val permissions = context.packageManager.getPackageInfo(context.packageName, 4096).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(android.Manifest.permission.INTERNET))
    }

    @Test fun printedRecognizesASyntheticPrintedLine() = runBlocking {
        val text = recognize(RecognitionStrategyId.PRINTED, renderLine("Hello printed world", Typeface.SANS_SERIF, Typeface.NORMAL))
        assertTrue("Expected the printed words, got '$text'", text.contains("Hello", ignoreCase = true) && text.contains("world", ignoreCase = true))
    }

    @Test fun cursiveRecognizesASyntheticItalicLine() = runBlocking {
        val text = recognize(RecognitionStrategyId.CURSIVE, renderLine("Hello Kraken OCR", Typeface.SERIF, Typeface.ITALIC))
        // Loose check: an italic system font is outside the handwriting training distribution.
        assertTrue("Expected multiple recognized letters, got '$text'", text.count { it.isLetter() } >= 4)
    }

    @Test fun blankPageProducesNoRegionsWithEitherStrategy() = runBlocking {
        for (id in BuiltInStrategies.ids) {
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { Canvas(this).drawColor(Color.WHITE) }
            val document = strategy(id).recognize(bitmap.toPreparedImage()).getOrThrow()
            assertTrue("$id: a blank page should produce zero regions", document.regions.isEmpty())
        }
    }

    private fun strategy(id: RecognitionStrategyId) = checkNotNull(BuiltInStrategies.create(context, id))

    private suspend fun recognize(id: RecognitionStrategyId, image: PreparedImage): String {
        val document = strategy(id).recognize(image).getOrThrow()
        assertEquals(id, document.strategy)
        assertTrue("$id: expected at least one region", document.regions.isNotEmpty())
        return document.regions.joinToString(" ") { it.text }.also { Log.i("BuiltInStrategiesTest", "$id recognized '$it'") }
    }

    private fun renderLine(text: String, family: Typeface, style: Int): PreparedImage {
        val bitmap = Bitmap.createBitmap(900, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 72f
            typeface = Typeface.create(family, style)
        }
        canvas.drawText(text, 32f, 124f, paint)
        return bitmap.toPreparedImage()
    }

    private fun Bitmap.toPreparedImage(): PreparedImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return PreparedImage(stream.toByteArray(), "image/png", width, height, pixels)
    }
}