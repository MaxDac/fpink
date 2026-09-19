package com.fpink.recognition.paddle

import android.content.Context
import android.os.Build
import android.os.Process
import com.fpink.core.ai.PreparedImage
import com.fpink.core.ai.RecognitionDocument
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProvider
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.ai.TextRegion
import com.fpink.core.model.ImagePoint
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** CPU-only, bundled PP-OCRv5. Construction never loads models or starts work. */
class PaddleOcrProvider(context: Context) : RecognitionProvider {
    private val context = context.applicationContext

    override suspend fun recognize(image: PreparedImage): Result<RecognitionDocument> =
        withContext(Dispatchers.Default) {
            try {
                if (image.width > 8192 || image.height > 8192 ||
                    image.pixels.size > MAX_PIXELS || image.width < 16 || image.height < 16
                ) {
                    throw RecognitionError.UnsupportedDevice(
                        "Paddle requires a prepared image of 16–8192 pixels per side and at most 16 million pixels.",
                    )
                }
                // One native job process-wide, including jobs owned by different provider instances.
                inferenceLock.withLock {
                    currentCoroutineContext().ensureActive()
                    readiness(context).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    val files = materializeModels(context)
                    currentCoroutineContext().ensureActive()
                    val job = currentCoroutineContext()[Job]!!
                    val lines = NativeBridge.recognize(
                        files[0].absolutePath, files[1].absolutePath, files[2].absolutePath,
                        image.pixels, image.width, image.height, NativeCancellation(job),
                    )
                    // A kernel cannot be interrupted safely. Never publish a cancelled kernel's result.
                    currentCoroutineContext().ensureActive()
                    Result.success(
                        RecognitionDocument(
                            lines.mapNotNull { line ->
                                if (line.polygon.size != 8 || !line.confidence.isFinite() ||
                                    line.polygon.any { !it.isFinite() || it !in 0f..1f }
                                ) {
                                    throw RecognitionError.MalformedResponse("Paddle returned invalid text geometry.")
                                }
                                val text = line.text.toString(Charsets.UTF_8)
                                if (text.isBlank()) return@mapNotNull null
                                TextRegion(
                                    text = text,
                                    polygon = line.polygon.toList().chunked(2).map { (x, y) ->
                                        ImagePoint(x, y)
                                    },
                                    confidence = line.confidence.coerceIn(0f, 1f),
                                )
                            },
                            RecognitionProviderId.PADDLE,
                            MODEL_VERSION,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RecognitionError) {
                Result.failure(error)
            } catch (error: UnsatisfiedLinkError) {
                Result.failure(RecognitionError.UnsupportedDevice("The ARM64 Paddle native runtime cannot be loaded."))
            } catch (error: IllegalArgumentException) {
                Result.failure(RecognitionError.UnsupportedDevice(error.message ?: "Paddle input limit exceeded."))
            } catch (error: Exception) {
                Result.failure(RecognitionError.ModelUnavailable("Local Paddle inference failed.", error))
            }
        }

    companion object {
        const val MODEL_VERSION = "PP-OCRv5_mobile_det+rec/Lite-v2.14-rc"
        private const val MAX_PIXELS = 16_000_000
        private val inferenceLock = Mutex()
        private val assetLock = Any()
        private val assets = listOf(
            ModelAsset("PP-OCRv5_mobile_det.nb", 5_001_214,
                "bee84fdb3d7c5f312a797da81f2dd44c89088c08e1f58a51044a823cddfbd90c"),
            ModelAsset("PP-OCRv5_mobile_rec.nb", 16_718_470,
                "9d073b3ee01deee358bf929dd8952d4d355c9545f4a93d8070605581b4c21c0c"),
            ModelAsset("ppocr_keys_ocrv5.txt", 74_011,
                "17665d27ed39f0deb82007859992d626d3105d0ee4578c120b7c72138dc04d05"),
        )

        /** Checks packaged bytes and actual native loading, never network. Call off the UI thread. */
        fun readiness(context: Context): Result<Unit> = try {
            if ("arm64-v8a" !in Build.SUPPORTED_ABIS || !Process.is64Bit()) {
                throw RecognitionError.UnsupportedDevice("Offline Paddle currently supports ARM64 Android only.")
            }
            synchronized(assetLock) {
                assets.forEach { asset ->
                    context.assets.open("paddle/${asset.name}").use { stream ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            digest.update(buffer, 0, count)
                        }
                        check(total == asset.bytes && digest.digest().hex() == asset.sha256) {
                            "Bundled Paddle asset verification failed: ${asset.name}"
                        }
                    }
                }
                NativeBridge.load()
                check(NativeBridge.abi() == "arm64-v8a")
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RecognitionError) {
            Result.failure(error)
        } catch (error: UnsatisfiedLinkError) {
            Result.failure(RecognitionError.UnsupportedDevice("The bundled ARM64 Paddle runtime cannot be loaded."))
        } catch (error: Exception) {
            Result.failure(RecognitionError.ModelUnavailable("Bundled Paddle model assets are missing or corrupt.", error))
        }

        private fun materializeModels(context: Context): List<File> = synchronized(assetLock) {
            val directory = File(context.noBackupFilesDir, "paddle-v5")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create local Paddle model directory" }
            assets.map { asset ->
                val target = File(directory, asset.name)
                if (!target.isFile || target.length() != asset.bytes || target.sha256() != asset.sha256) {
                    val staged = File(directory, "${asset.name}.pending")
                    try {
                        context.assets.open("paddle/${asset.name}").use { input ->
                            FileOutputStream(staged).use { output ->
                                input.copyTo(output, 64 * 1024)
                                output.fd.sync()
                            }
                        }
                        check(staged.length() == asset.bytes && staged.sha256() == asset.sha256)
                        check(staged.renameTo(target)) { "Cannot publish local Paddle model" }
                    } finally {
                        if (staged.exists()) check(staged.delete()) { "Cannot clean staged Paddle model" }
                    }
                }
                target
            }
        }

        private fun File.sha256(): String = inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().hex()
        }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
        private data class ModelAsset(val name: String, val bytes: Long, val sha256: String)
    }
}

internal class NativeCancellation(private val job: Job) {
    fun isCancelled(): Boolean = !job.isActive
}

internal class NativeLine(val text: ByteArray, val polygon: FloatArray, val confidence: Float)

internal object NativeBridge {
    @Volatile private var loaded = false

    @Synchronized fun load() {
        if (!loaded) {
            System.loadLibrary("fpink_paddle")
            loaded = true
        }
    }

    external fun abi(): String
    external fun recognize(
        detector: String,
        recognizer: String,
        dictionary: String,
        pixels: IntArray,
        width: Int,
        height: Int,
        cancellation: NativeCancellation,
    ): Array<NativeLine>
}
