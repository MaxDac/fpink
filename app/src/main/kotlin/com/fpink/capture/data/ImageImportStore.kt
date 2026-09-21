package com.fpink.capture.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import com.fpink.core.ai.PreparedImage
import com.fpink.core.ai.AzureReadConfig
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.ai.RecognitionSettings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.Properties
import java.security.MessageDigest
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class StagedImage(val sourceId: String, val file: File)

/** Only app-owned UUIDs cross navigation. External URI grants are consumed during import. */
class ImageImportStore(context: Context) {
    private val context = context.applicationContext
    private val directory = File(context.noBackupFilesDir, "image-imports")
    private val cameraDirectory = File(context.cacheDir, "camera-captures")
    private val cancellations = JobCancellationStore(
        File(context.noBackupFilesDir, "image-job-cancellations"),
        ::sourceDirectory,
    ) { file ->
        val descriptor = Os.open(file.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    fun previewFile(sourceId: String): File = File(sourceDirectory(sourceId), "prepared.png")
    fun cameraCropFile(sourceId: String): File = File(sourceDirectory(sourceId), "camera-crop.png")

    suspend fun importContent(uri: Uri): StagedImage = importOwned { sourceId ->
        require(uri.scheme == "content") { "Choose an image from a gallery or file provider." }
        val mime = context.contentResolver.getType(uri)
        require(mime == null || mime.startsWith("image/") || mime == "application/octet-stream") {
            "The selected item is not an image."
        }
        context.contentResolver.openInputStream(uri)?.use { stage(it, sourceId) }
            ?: throw IOException("The image provider could not open this image. Download it locally and choose it again.")
    }

    suspend fun importCamera(file: File): StagedImage {
        require(file.canonicalFile.parentFile == cameraDirectory.canonicalFile) { "Invalid camera capture." }
        try {
            return importOwned { sourceId -> FileInputStream(file).use { stage(it, sourceId) } }
        } finally {
            file.delete()
        }
    }

    suspend fun stageCameraCrop(file: File): StagedImage {
        require(file.canonicalFile.parentFile == cameraDirectory.canonicalFile) { "Invalid camera capture." }
        try {
            return importOwned { sourceId ->
                FileInputStream(file).use { stage(it, sourceId, cameraCropFile(sourceId)) }
            }
        } finally {
            file.delete()
        }
    }

    suspend fun applyCameraCrop(sourceId: String, crop: CropRect): StagedImage = withContext(Dispatchers.IO) {
        val input = cameraCropFile(sourceId)
        require(input.isFile) { "The captured photo is no longer available. Retake it." }
        val bitmap = BitmapFactory.decodeFile(input.absolutePath)
            ?: throw IOException("The captured photo cannot be decoded. Retake it.")
        val temporary = File(sourceDirectory(sourceId), "prepared.tmp")
        var cropped: Bitmap? = null
        try {
            val bounds = crop.pixels(bitmap.width, bitmap.height)
            cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width, bounds.height)
            FileOutputStream(temporary).use {
                check(cropped.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not encode the cropped photo." }
                it.fd.sync()
            }
            require(temporary.length() in 1..MAX_PREPARED_BYTES) { "The cropped image is too large." }
            try {
                Files.move(
                    temporary.toPath(),
                    previewFile(sourceId).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    previewFile(sourceId).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            check(input.delete()) { "Could not remove the uncropped camera image." }
            StagedImage(sourceId, previewFile(sourceId))
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            cropped?.takeIf { it !== bitmap }?.recycle()
            bitmap.recycle()
        }
    }

    fun newCameraFile(): File {
        check(cameraDirectory.isDirectory || cameraDirectory.mkdirs()) { "Camera storage is unavailable." }
        return File(cameraDirectory, "${UUID.randomUUID()}.jpg")
    }

    fun deleteCameraFile(file: File) {
        if (file.canonicalFile.parentFile == cameraDirectory.canonicalFile) file.delete()
    }

    suspend fun load(sourceId: String): PreparedImage = withContext(Dispatchers.IO) {
        val file = previewFile(sourceId)
        require(file.isFile) { "The prepared image is no longer available. Choose the image again." }
        require(file.length() in 1..MAX_PREPARED_BYTES) { "The prepared image is invalid." }
        val bytes = file.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth <= MAX_EDGE && bounds.outHeight <= MAX_EDGE &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS
        ) { "The prepared image is invalid." }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("The prepared image cannot be decoded.")
        try {
            require(bitmap.width.toLong() * bitmap.height <= MAX_PIXELS)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            PreparedImage(bytes, "image/png", bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun discard(sourceId: String) = withContext(Dispatchers.IO) {
        val file = sourceDirectory(sourceId)
        if (file.exists() && !file.deleteRecursively()) throw IOException("Could not remove the staged image.")
        Unit
    }

    suspend fun isCancelled(sourceId: String): Boolean = withContext(Dispatchers.IO) {
        cancellations.isCancelled(sourceId)
    }

    suspend fun invalidateSelection(sourceId: String) = withContext(NonCancellable + Dispatchers.IO) {
        cancellations.invalidate(sourceId)
    }

    suspend fun rememberSelection(sourceId: String, settings: RecognitionSettings) = withContext(Dispatchers.IO) {
        if (cancellations.isCancelled(sourceId)) throw RecognitionError.Configuration(CANCELLED_JOB_MESSAGE)
        check(previewFile(sourceId).isFile) { "The prepared image is unavailable." }
        val properties = Properties().apply {
            setProperty("provider", settings.provider.name)
            if (settings.provider == RecognitionProviderId.AZURE) {
                setProperty("endpoint", settings.azure.endpoint)
                setProperty("keyFingerprint", keyFingerprint(settings.azure.apiKey))
            }
        }
        FileOutputStream(File(sourceDirectory(sourceId), "selection")).use {
            properties.store(it, null)
            it.fd.sync()
        }
    }

    suspend fun restoreSelection(sourceId: String, current: suspend () -> RecognitionSettings): RecognitionSettings =
        withContext(Dispatchers.IO) {
            if (cancellations.isCancelled(sourceId)) throw RecognitionError.Configuration(CANCELLED_JOB_MESSAGE)
            val properties = Properties()
            val selection = File(sourceDirectory(sourceId), "selection")
            require(selection.isFile) { "Confirm this image again before processing it." }
            FileInputStream(selection).use { properties.load(it) }
            when (properties.getProperty("provider")) {
                RecognitionProviderId.PADDLE.name -> RecognitionSettings()
                RecognitionProviderId.AZURE.name -> {
                    val azure = current().azure
                    if (properties.getProperty("keyFingerprint") != keyFingerprint(azure.apiKey)) {
                        throw RecognitionError.Configuration(
                            "The Azure key changed or became unavailable after this job was confirmed. Choose the image again to approve a new job.",
                        )
                    }
                    RecognitionSettings(
                        RecognitionProviderId.AZURE,
                        AzureReadConfig(properties.getProperty("endpoint"), azure.apiKey),
                    )
                }
                else -> throw RecognitionError.Configuration("This job has no valid provider selection. Choose the image again.")
            }
        }

    suspend fun cleanExpired() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        cameraDirectory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun keyFingerprint(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun sourceDirectory(sourceId: String): File {
        require(SOURCE_ID.matches(sourceId)) { "Invalid source identifier." }
        return File(directory, sourceId)
    }

    private suspend fun importOwned(import: suspend (String) -> StagedImage): StagedImage {
        val sourceId = UUID.randomUUID().toString()
        try {
            return withContext(Dispatchers.IO) { import(sourceId) }
        } catch (error: Throwable) {
            // Also covers cancellation at withContext's return boundary, after decoding finished.
            withContext(NonCancellable + Dispatchers.IO) { sourceDirectory(sourceId).deleteRecursively() }
            throw error
        }
    }

    private suspend fun stage(
        input: InputStream,
        sourceId: String,
        destination: File = previewFile(sourceId),
    ): StagedImage {
        val folder = sourceDirectory(sourceId)
        check(folder.mkdirs()) { "Private image storage is unavailable." }
        val original = File(folder, "original")
        try {
            FileOutputStream(original).use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_IMPORT_BYTES) { "Choose an image no larger than 24 MB." }
                    output.write(buffer, 0, count)
                }
                require(total > 0) { "The selected image is empty." }
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            prepare(original, destination)
            currentCoroutineContext().ensureActive()
            check(original.delete()) { "Could not remove the intermediate image." }
            return StagedImage(sourceId, destination)
        } catch (error: Throwable) {
            folder.deleteRecursively()
            throw error
        }
    }

    private fun prepare(input: File, output: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width > 0 && height > 0) { "This image is corrupt or its format is not supported on this device." }
        require(width <= 32768 && height <= 32768 && width.toLong() * height <= 200_000_000) {
            "This image is too large to prepare safely. Export a smaller copy."
        }
        val sample = imageSampleSize(width, height)
        val decoded = BitmapFactory.decodeFile(
            input.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw IOException("This image cannot be decoded.")
        var oriented: Bitmap? = null
        var opaque: Bitmap? = null
        try {
            val orientation = ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
            val normalized = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, orientationMatrix(orientation), true)
            oriented = normalized
            require(normalized.width.toLong() * normalized.height <= MAX_PIXELS)
            val target = Bitmap.createBitmap(normalized.width, normalized.height, Bitmap.Config.ARGB_8888)
            opaque = target
            Canvas(target).apply {
                drawColor(Color.WHITE)
                drawBitmap(normalized, 0f, 0f, null)
            }
            FileOutputStream(output).use {
                check(target.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not encode the prepared image." }
                it.fd.sync()
            }
            require(output.length() <= MAX_PREPARED_BYTES) { "The prepared image is too large." }
        } finally {
            opaque?.recycle()
            if (oriented !== decoded) oriented?.recycle()
            decoded.recycle()
        }
    }

    companion object {
        const val MAX_IMPORT_BYTES = 24L * 1024 * 1024
        const val MAX_PREPARED_BYTES = 24L * 1024 * 1024
        const val MAX_PIXELS = 4_000_000L
        const val MAX_EDGE = 3072
        private val SOURCE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

internal fun imageSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while (((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > ImageImportStore.MAX_PIXELS ||
        (max(width, height).toLong() + sample - 1) / sample > ImageImportStore.MAX_EDGE
    ) {
        sample *= 2
    }
    return sample
}

internal fun orientationMatrix(orientation: Int) = Matrix().apply { setValues(exifTransform(orientation)) }
