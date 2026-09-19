package com.fpink.capture.acceptance

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.data.ImageImportStore
import com.fpink.core.ai.PreparedImage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageImportAcceptanceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun jpegExifAllEightOrientationsMatchDecodedPixelsAndPngBytes() = runBlocking {
        AcceptanceStorage().use { storage ->
            val width = 12
            val height = 8
            val jpeg = encodedBitmap(width, height, Bitmap.CompressFormat.JPEG) { x, y ->
                Color.rgb(x * 19, y * 29, (x * 11 + y * 17) % 256)
            }
            for (orientation in 1..8) {
                val source = storage.images.newCameraFile().apply { writeBytes(jpeg) }
                ExifInterface(source).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                assertEquals(orientation, ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
                // BitmapFactory intentionally ignores EXIF; this is the independently decoded JPEG pixel oracle.
                val original = requireNotNull(BitmapFactory.decodeFile(source.absolutePath))
                val outputWidth = if (orientation >= 5) height else width
                val outputHeight = if (orientation >= 5) width else height
                val expected = IntArray(width * height)
                try {
                    for (y in 0 until height) for (x in 0 until width) {
                        val (dx, dy) = when (orientation) {
                            2 -> width - 1 - x to y
                            3 -> width - 1 - x to height - 1 - y
                            4 -> x to height - 1 - y
                            5 -> y to x
                            6 -> height - 1 - y to x
                            7 -> height - 1 - y to width - 1 - x
                            8 -> y to width - 1 - x
                            else -> x to y
                        }
                        expected[dy * outputWidth + dx] = original.getPixel(x, y)
                    }
                } finally {
                    original.recycle()
                }
                val staged = storage.images.importCamera(source)
                assertFalse("Camera intermediate must be removed", source.exists())
                val prepared = storage.images.load(staged.sourceId)
                assertEquals("EXIF $orientation width", outputWidth, prepared.width)
                assertEquals("EXIF $orientation height", outputHeight, prepared.height)
                assertArrayEquals("EXIF $orientation pixels", expected, prepared.pixels)
                assertPngEquivalent(prepared)
                assertArrayEquals(prepared.bytes, staged.file.readBytes())
                storage.images.discard(staged.sourceId)
            }
        }
    }

    @Test fun transientProviderPngIsPrivateOpaqueAndSurvivesGrantRevocationAndSourceRemoval() = runBlocking {
        AcceptanceStorage().use { storage ->
            FixturePicker(activity.scenario).use { picker ->
                val originalPixels = intArrayOf(Color.TRANSPARENT, Color.RED, 0x80000000.toInt(), Color.BLUE)
                val png = encodedBitmap(4, 1, Bitmap.CompressFormat.PNG) { x, _ -> originalPixels[x] }
                val uri = picker.provide(png)
                assertEquals(PackageManager.PERMISSION_GRANTED, storage.context.checkUriPermission(
                    uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ))
                assertEquals("image/png", storage.context.contentResolver.getType(uri))
                val staged = storage.images.importContent(uri)
                assertTrue(staged.file.canonicalPath.startsWith(storage.context.noBackupFilesDir.canonicalPath + "/"))
                assertEquals(listOf("prepared.png"), staged.file.parentFile!!.list()!!.toList())
                assertFalse(storage.context.contentResolver.persistedUriPermissions.any { it.uri == uri })
                picker.remove(uri)
                assertEquals(PackageManager.PERMISSION_DENIED, storage.context.checkUriPermission(
                    uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ))
                try {
                    storage.context.contentResolver.openInputStream(uri)?.close()
                    fail("Original provider URI must no longer be readable")
                } catch (_: SecurityException) {
                    // The real OS grant has been revoked.
                } catch (_: java.io.FileNotFoundException) {
                    // The provider's file was also removed.
                }
                val prepared = ImageImportStore(storage.context).load(staged.sourceId)
                assertEquals(4, prepared.width)
                assertEquals(1, prepared.height)
                assertArrayEquals(intArrayOf(Color.WHITE, Color.RED, Color.rgb(127, 127, 127), Color.BLUE), prepared.pixels)
                assertPngEquivalent(prepared)
                storage.images.discard(staged.sourceId)
                assertFalse(staged.file.exists())
            }
        }
    }

    @Test fun bitmapDecoderDownsamplesWithinPublishedPixelAndEdgeLimits() = runBlocking {
        AcceptanceStorage().use { storage ->
            val source = storage.images.newCameraFile().apply {
                writeBytes(encodedBitmap(4096, 1024, Bitmap.CompressFormat.PNG) { _, _ -> Color.WHITE })
            }
            val staged = storage.images.importCamera(source)
            val prepared = storage.images.load(staged.sourceId)
            assertEquals(2048, prepared.width)
            assertEquals(512, prepared.height)
            assertTrue(prepared.width <= ImageImportStore.MAX_EDGE && prepared.height <= ImageImportStore.MAX_EDGE)
            assertTrue(prepared.width.toLong() * prepared.height <= ImageImportStore.MAX_PIXELS)
            assertTrue(prepared.pixels.all { it == Color.WHITE })
            assertPngEquivalent(prepared)
        }
    }
}

internal fun encodedBitmap(
    width: Int, height: Int, format: Bitmap.CompressFormat,
    pixel: (Int, Int) -> Int,
): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val pixels = IntArray(width * height) { pixel(it % width, it / width) }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return ByteArrayOutputStream().use {
            check(bitmap.compress(format, 100, it))
            it.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}

private fun assertPngEquivalent(prepared: PreparedImage) {
    assertEquals("image/png", prepared.mimeType)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size, bounds)
    assertEquals(prepared.mimeType, bounds.outMimeType)
    assertEquals(prepared.width, bounds.outWidth)
    assertEquals(prepared.height, bounds.outHeight)
    val decoded = requireNotNull(BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size))
    try {
        val pixels = IntArray(decoded.width * decoded.height)
        decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        assertArrayEquals(prepared.pixels, pixels)
    } finally {
        decoded.recycle()
    }
}
