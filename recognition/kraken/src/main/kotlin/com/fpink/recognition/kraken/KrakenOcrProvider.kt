package com.fpink.recognition.kraken

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import com.fpink.core.ai.PreparedImage
import com.fpink.core.ai.RecognitionDocument
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionProvider
import com.fpink.core.ai.RecognitionProviderId
import com.fpink.core.ai.RecognitionProviderPlugin
import com.fpink.core.ai.RecognitionSettings
import com.fpink.core.ai.TextRegion
import com.fpink.core.model.ImagePoint
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kraken-compatible, Python-free Android provider seam. This class deliberately refuses to run
 * until the reviewed ONNX export assets listed in [assets] are bundled; it never substitutes a
 * heuristic recognizer or downloads model bytes at runtime.
 */
class KrakenOcrProvider(context: Context) : RecognitionProvider {
    private val context = context.applicationContext

    override suspend fun recognize(image: PreparedImage): Result<RecognitionDocument> =
        withContext(Dispatchers.Default) {
            try {
                if (image.width > 8192 || image.height > 8192 ||
                    image.pixels.size > MAX_PIXELS || image.width < 16 || image.height < 16
                ) {
                    throw RecognitionError.UnsupportedDevice(
                        "Kraken requires a prepared image of 16-8192 pixels per side and at most 16 million pixels.",
                    )
                }
                inferenceLock.withLock {
                    currentCoroutineContext().ensureActive()
                    readiness(context).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    val files = materializeModels(context)
                    val segments = KrakenLineSegmenter.segment(image.pixels, image.width, image.height)
                    if (segments.isEmpty()) {
                        return@withLock Result.success(
                            RecognitionDocument(emptyList(), RecognitionProviderId.KRAKEN, MODEL_VERSION),
                        )
                    }
                    val recognizer = KrakenOnnxRecognizer(files.first())
                    recognizer.use {
                        val regions = segments.mapNotNull { segment ->
                            currentCoroutineContext().ensureActive()
                            val text = it.recognizeLine(image.pixels, image.width, segment)
                            if (text.isBlank()) return@mapNotNull null
                            TextRegion(
                                text = text,
                                polygon = segment.polygon(image.width, image.height),
                                confidence = null,
                            )
                        }
                        Result.success(RecognitionDocument(regions, RecognitionProviderId.KRAKEN, MODEL_VERSION))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RecognitionError) {
                Result.failure(error)
            } catch (error: UnsatisfiedLinkError) {
                Result.failure(RecognitionError.UnsupportedDevice("ONNX Runtime for Kraken cannot be loaded on this device."))
            } catch (error: Exception) {
                Result.failure(RecognitionError.ModelUnavailable("Local Kraken inference failed.", error))
            }
        }

    companion object {
        const val MODEL_VERSION = "kraken-ppocrv6-medium/zenodo-10.5281-zenodo.21788410"
        private const val MAX_PIXELS = 16_000_000
        private val inferenceLock = Mutex()
        private val assetLock = Any()
        private val assets = listOf(
            ModelAsset(
                name = "ppocrv6-medium-recognition.onnx",
                bytes = 0L,
                sha256 = "MISSING_REVIEWED_EXPORT",
                required = true,
            ),
            ModelAsset(
                name = "ppocrv6-medium-alphabet.txt",
                bytes = 0L,
                sha256 = "MISSING_REVIEWED_EXPORT",
                required = true,
            ),
        )

        /** Checks packaged bytes and ONNX Runtime loadability, never network. Call off the UI thread. */
        fun readiness(context: Context): Result<Unit> = try {
            if (Build.SUPPORTED_ABIS.none { it in supportedAbis }) {
                throw RecognitionError.UnsupportedDevice(
                    "Offline Kraken requires an Android ABI supported by ONNX Runtime (${supportedAbis.joinToString()}).",
                )
            }
            synchronized(assetLock) {
                assets.forEach { asset ->
                    if (asset.sha256 == "MISSING_REVIEWED_EXPORT") {
                        throw RecognitionError.ModelUnavailable(
                            "Kraken ONNX export assets are not bundled yet; see recognition/kraken/README.md.",
                        )
                    }
                    context.assets.open("kraken/${asset.name}").use { stream ->
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
                            "Bundled Kraken asset verification failed: ${asset.name}"
                        }
                    }
                }
                val environment = OrtEnvironment.getEnvironment()
                context.assets.open("kraken/${assets.first().name}").use { stream ->
                    val staged = File.createTempFile("kraken-readiness", ".onnx", context.cacheDir)
                    try {
                        FileOutputStream(staged).use { output -> stream.copyTo(output, 64 * 1024) }
                        environment.createSession(staged.absolutePath, OrtSession.SessionOptions()).use { }
                    } finally {
                        if (staged.exists()) staged.delete()
                    }
                }
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RecognitionError) {
            Result.failure(error)
        } catch (error: UnsatisfiedLinkError) {
            Result.failure(RecognitionError.UnsupportedDevice("ONNX Runtime for Kraken cannot be loaded on this device."))
        } catch (error: Exception) {
            Result.failure(RecognitionError.ModelUnavailable("Bundled Kraken model assets are missing or corrupt.", error))
        }

        private val supportedAbis = setOf("arm64-v8a", "x86_64")

        private fun materializeModels(context: Context): List<File> = synchronized(assetLock) {
            val directory = File(context.noBackupFilesDir, "kraken-ppocrv6-medium")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create local Kraken model directory" }
            assets.map { asset ->
                val target = File(directory, asset.name)
                if (!target.isFile || target.length() != asset.bytes || target.sha256() != asset.sha256) {
                    val staged = File(directory, "${asset.name}.pending")
                    try {
                        context.assets.open("kraken/${asset.name}").use { input ->
                            FileOutputStream(staged).use { output ->
                                input.copyTo(output, 64 * 1024)
                                output.fd.sync()
                            }
                        }
                        check(staged.length() == asset.bytes && staged.sha256() == asset.sha256)
                        check(staged.renameTo(target)) { "Cannot publish local Kraken model" }
                    } finally {
                        if (staged.exists()) check(staged.delete()) { "Cannot clean staged Kraken model" }
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
        private data class ModelAsset(val name: String, val bytes: Long, val sha256: String, val required: Boolean)
    }
}

class KrakenOcrProviderPlugin : RecognitionProviderPlugin {
    override val id: RecognitionProviderId = RecognitionProviderId.KRAKEN
    override fun create(context: Any, settings: RecognitionSettings) =
        KrakenOcrProvider(context as Context)
}

private class KrakenOnnxRecognizer(model: File) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = environment.createSession(model.absolutePath, OrtSession.SessionOptions())

    fun recognizeLine(pixels: IntArray, width: Int, segment: KrakenLineSegment): String {
        require(width > 0 && pixels.isNotEmpty())
        require(segment.bottom > segment.top)
        throw RecognitionError.ModelUnavailable(
            "Kraken ONNX tensor preprocessing/CTC output mapping is intentionally disabled until reviewed exported assets are bundled.",
        )
    }

    override fun close() {
        session.close()
    }
}

internal data class KrakenLineSegment(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun polygon(width: Int, height: Int): List<ImagePoint> = listOf(
        ImagePoint(left.toFloat() / width, top.toFloat() / height),
        ImagePoint(right.toFloat() / width, top.toFloat() / height),
        ImagePoint(right.toFloat() / width, bottom.toFloat() / height),
        ImagePoint(left.toFloat() / width, bottom.toFloat() / height),
    )
}

internal object KrakenLineSegmenter {
    fun segment(pixels: IntArray, width: Int, height: Int): List<KrakenLineSegment> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val inkByRow = IntArray(height)
        for (y in 0 until height) {
            var ink = 0
            val offset = y * width
            for (x in 0 until width) {
                if (isInk(pixels[offset + x])) ink++
            }
            inkByRow[y] = ink
        }
        val threshold = (width * 0.01f).toInt().coerceAtLeast(2)
        val raw = mutableListOf<IntRange>()
        var start = -1
        for (y in 0 until height) {
            if (inkByRow[y] >= threshold && start < 0) start = y
            if ((inkByRow[y] < threshold || y == height - 1) && start >= 0) {
                val end = if (inkByRow[y] < threshold) y - 1 else y
                if (end - start >= 2) raw += start..end
                start = -1
            }
        }
        return raw.mapNotNull { rows ->
            val top = (rows.first - 4).coerceAtLeast(0)
            val bottom = (rows.last + 5).coerceAtMost(height)
            var left = width
            var right = 0
            for (y in top until bottom) {
                val offset = y * width
                for (x in 0 until width) {
                    if (isInk(pixels[offset + x])) {
                        left = minOf(left, x)
                        right = maxOf(right, x + 1)
                    }
                }
            }
            if (left >= right) null else KrakenLineSegment(
                (left - 4).coerceAtLeast(0),
                top,
                (right + 4).coerceAtMost(width),
                bottom,
            )
        }
    }

    private fun isInk(argb: Int): Boolean {
        val r = argb shr 16 and 0xff
        val g = argb shr 8 and 0xff
        val b = argb and 0xff
        return (r + g + b) / 3 < 210
    }
}

internal object KrakenCtcDecoder {
    fun decode(bestPath: IntArray, alphabet: List<String>, blank: Int = 0): String {
        val output = StringBuilder()
        var previous = blank
        bestPath.forEach { index ->
            if (index != blank && index != previous) {
                output.append(alphabet.getOrNull(index - 1).orEmpty())
            }
            previous = index
        }
        return output.toString()
    }
}
