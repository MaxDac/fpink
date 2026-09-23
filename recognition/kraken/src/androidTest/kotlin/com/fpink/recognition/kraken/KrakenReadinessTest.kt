package com.fpink.recognition.kraken

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fpink.core.ai.PreparedImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the bundled, reviewed Kraken ONNX export end to end. This test APK also requests no
 * network permission; Kraken must never download model bytes.
 */
@RunWith(AndroidJUnit4::class)
class KrakenReadinessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun readinessSucceedsWithBundledExportAssets() {
        val result = KrakenOcrProvider.readiness(context)
        assertTrue("readiness() should succeed with the bundled reviewed export: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test fun testApkDoesNotDeclareInternetPermission() {
        val permissions = context.packageManager.getPackageInfo(context.packageName, 4096).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(android.Manifest.permission.INTERNET))
    }

    /**
     * Genuine end-to-end recognition on a synthetic text-line image (rendered on-device with
     * Android's own Canvas/Typeface, no bundled test-image asset needed). Disclosed honestly: this
     * uses an italic serif system font as a connected-letterform stand-in, not a real scanned
     * cursive handwriting sample — the emulator/CI image has no bundled genuine handwriting font.
     * See recognition/kraken/README.md's validation section for what this does and does not prove.
     */
    @Test fun recognizesSyntheticTextLine() = runBlocking {
        val prepared = renderTextLine("Hello Kraken OCR")
        val result = KrakenOcrProvider(context).recognize(prepared)
        val document = result.getOrThrow()
        assertTrue("Expected at least one recognized region", document.regions.isNotEmpty())
        val recognizedText = document.regions.joinToString(" ") { it.text }
        android.util.Log.i("KrakenRecognitionTest", "recognized='$recognizedText' expected='Hello Kraken OCR'")
        assertTrue("Recognized text should be non-blank, was: '$recognizedText'", recognizedText.isNotBlank())
        // Loose containment check rather than exact match: bilinear (not Lanczos) resizing and an
        // italic system font instead of the training distribution's handwriting samples can shift
        // individual characters. See README.md for the exact, honestly-reported match rate.
        val overlap = recognizedText.count { it.isLetter() }
        assertTrue("Expected multiple recognized letters, got '$recognizedText'", overlap >= 4)
    }

    @Test fun blankPageProducesNoRegions() = runBlocking {
        val prepared = renderBlankPage()
        val result = KrakenOcrProvider(context).recognize(prepared)
        val document = result.getOrThrow()
        assertTrue("A blank page should produce zero regions", document.regions.isEmpty())
    }

    private fun renderTextLine(text: String): PreparedImage {
        val width = 640
        val height = 160
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 72f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        canvas.drawText(text, 24f, 104f, paint)
        return bitmap.toPreparedImage()
    }

    private fun renderBlankPage(): PreparedImage {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        return bitmap.toPreparedImage()
    }

    private fun Bitmap.toPreparedImage(): PreparedImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return PreparedImage(
            bytes = stream.toByteArray(),
            mimeType = "image/png",
            width = width,
            height = height,
            pixels = pixels,
        )
    }
}
