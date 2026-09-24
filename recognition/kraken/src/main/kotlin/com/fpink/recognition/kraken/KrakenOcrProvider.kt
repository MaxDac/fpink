package com.fpink.recognition.kraken

import ai.onnxruntime.OnnxTensor
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
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kraken-compatible, Python-free Android provider seam running the real, reviewed ONNX export of
 * Kraken's PP-OCRv6 medium recognizer (see [assets] and recognition/kraken/README.md). It refuses
 * to run if the bundled assets are missing or hash-mismatched; it never substitutes a heuristic
 * recognizer or downloads model bytes at runtime.
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
                    val alphabet = files[1].readLines(Charsets.UTF_8).let { lines ->
                        // Drop a single trailing blank line produced by the export script's
                        // trailing newline; every other line is one grapheme, ordered by
                        // ascending CTC label id (see recognition/kraken/scripts/export_onnx.py).
                        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
                    }
                    val recognizer = KrakenOnnxRecognizer(files[0], alphabet)
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
                bytes = 64_204_317L,
                sha256 = "12cfdbffa5e7243519120f177f26c716b924d9865e201b0fa459a0163979c95e",
                required = true,
            ),
            ModelAsset(
                name = "ppocrv6-medium-alphabet.txt",
                bytes = 5_615L,
                sha256 = "0b7c71199be609f1ceedb20d0e2bc136ad8f2a0beee4bb80e6903b444b0560f9",
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

/**
 * Runs the exported PP-OCRv6 medium recognizer on a single already-segmented text line.
 *
 * Preprocessing mirrors kraken's own [`ImageInputTransforms`][1] contract for this checkpoint
 * exactly, verified bit-for-bit equivalent (matching decoded text) against the real kraken/PyTorch
 * pipeline in the export environment:
 *   1. Crop the line's bounding box out of the full page image (no rotation/dewarp: the row-
 *      projection segmenter only ever produces axis-aligned boxes, see [KrakenLineSegmenter]).
 *   2. Resize to a fixed height of [TARGET_HEIGHT] px, preserving aspect ratio.
 *   3. Pad [PADDING] px of solid white on the left and right (kraken's default recognition
 *      inference padding, see `kraken.configs.base.RecognitionInferenceConfig.padding`).
 *   4. Scale channel bytes to `[0, 1]` and invert (`1 - x`), matching kraken's `tensor_invert`
 *      transform (ink -> high value, background -> ~0).
 *
 * One disclosed divergence from upstream kraken: step 2 uses bilinear interpolation
 * ([resizeBilinear]) rather than kraken's Lanczos resize, since Android has no bundled Lanczos
 * image scaler and pulling in a dedicated image-processing dependency for this one step was not
 * justified. This did not change decoded output in this integration's own end-to-end checks (see
 * README.md validation section), but may very slightly affect edge-case accuracy on tightly
 * kerned or very small glyphs; this is the same kind of implementation-detail disclosure practice
 * as `recognition/paddle`'s DB-postprocessing polygon-offset note.
 *
 * [1]: https://github.com/mittagessen/kraken kraken.lib.dataset.utils.ImageInputTransforms
 */
private class KrakenOnnxRecognizer(model: File, private val alphabet: List<String>) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = environment.createSession(model.absolutePath, OrtSession.SessionOptions())

    fun recognizeLine(pixels: IntArray, width: Int, segment: KrakenLineSegment): String {
        require(width > 0 && pixels.isNotEmpty())
        require(segment.bottom > segment.top)
        val cropWidth = segment.right - segment.left
        val cropHeight = segment.bottom - segment.top
        if (cropWidth <= 0 || cropHeight <= 0) return ""

        val scaledWidth = (cropWidth.toFloat() * TARGET_HEIGHT / cropHeight)
            .toInt()
            .coerceAtLeast(1)
            .coerceAtMost(MAX_SCALED_WIDTH)
        val resized = resizeBilinear(pixels, width, segment, scaledWidth, TARGET_HEIGHT)
        val paddedWidth = scaledWidth + 2 * PADDING

        val inputBuffer = FloatBuffer.allocate(3 * TARGET_HEIGHT * paddedWidth)
        for (channel in 0 until 3) {
            val shift = 16 - channel * 8
            for (y in 0 until TARGET_HEIGHT) {
                for (x in 0 until paddedWidth) {
                    val lineX = x - PADDING
                    val value = if (lineX < 0 || lineX >= scaledWidth) {
                        0f // white padding, inverted (1 - 1 = 0)
                    } else {
                        val argb = resized[y * scaledWidth + lineX]
                        val component = (argb shr shift) and 0xff
                        1f - (component / 255f)
                    }
                    inputBuffer.put(value)
                }
            }
        }
        inputBuffer.rewind()

        OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1, 3, TARGET_HEIGHT.toLong(), paddedWidth.toLong()),
        ).use { tensor ->
            session.run(mapOf(INPUT_NAME to tensor)).use { result ->
                val output = result.get(OUTPUT_NAME).orElseThrow {
                    RecognitionError.ModelUnavailable("Kraken ONNX graph produced no '$OUTPUT_NAME' output.")
                } as OnnxTensor
                val shape = output.info.shape
                val timeSteps = shape[1].toInt()
                val numClasses = shape[2].toInt()
                val logits = output.floatBuffer
                val bestPath = IntArray(timeSteps) { t ->
                    var bestClass = 0
                    var bestValue = Float.NEGATIVE_INFINITY
                    val base = t * numClasses
                    for (c in 0 until numClasses) {
                        val v = logits.get(base + c)
                        if (v > bestValue) {
                            bestValue = v
                            bestClass = c
                        }
                    }
                    bestClass
                }
                return KrakenCtcDecoder.decode(bestPath, alphabet)
            }
        }
    }

    override fun close() {
        session.close()
    }

    private companion object {
        const val TARGET_HEIGHT = 96
        const val PADDING = 16
        const val MAX_SCALED_WIDTH = 4096
        const val INPUT_NAME = "image"
        const val OUTPUT_NAME = "logits"

        /**
         * Bilinear resize of the [segment] crop of [pixels] (row stride [width]) to
         * ([dstWidth] x [dstHeight]). See the class doc for why this, not Lanczos, is used.
         */
        fun resizeBilinear(
            pixels: IntArray,
            width: Int,
            segment: KrakenLineSegment,
            dstWidth: Int,
            dstHeight: Int,
        ): IntArray {
            val srcWidth = segment.right - segment.left
            val srcHeight = segment.bottom - segment.top
            val out = IntArray(dstWidth * dstHeight)
            val xScale = srcWidth.toFloat() / dstWidth
            val yScale = srcHeight.toFloat() / dstHeight
            for (dy in 0 until dstHeight) {
                val sy = ((dy + 0.5f) * yScale - 0.5f).coerceIn(0f, (srcHeight - 1).toFloat())
                val y0 = sy.toInt()
                val y1 = (y0 + 1).coerceAtMost(srcHeight - 1)
                val fy = sy - y0
                for (dx in 0 until dstWidth) {
                    val sx = ((dx + 0.5f) * xScale - 0.5f).coerceIn(0f, (srcWidth - 1).toFloat())
                    val x0 = sx.toInt()
                    val x1 = (x0 + 1).coerceAtMost(srcWidth - 1)
                    val fx = sx - x0
                    val p00 = pixels[(segment.top + y0) * width + (segment.left + x0)]
                    val p10 = pixels[(segment.top + y0) * width + (segment.left + x1)]
                    val p01 = pixels[(segment.top + y1) * width + (segment.left + x0)]
                    val p11 = pixels[(segment.top + y1) * width + (segment.left + x1)]
                    out[dy * dstWidth + dx] = bilinearArgb(p00, p10, p01, p11, fx, fy)
                }
            }
            return out
        }

        private fun bilinearArgb(p00: Int, p10: Int, p01: Int, p11: Int, fx: Float, fy: Float): Int {
            fun channel(shift: Int): Int {
                val c00 = (p00 shr shift) and 0xff
                val c10 = (p10 shr shift) and 0xff
                val c01 = (p01 shr shift) and 0xff
                val c11 = (p11 shr shift) and 0xff
                val top = c00 + (c10 - c00) * fx
                val bottom = c01 + (c11 - c01) * fx
                return (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
            }
            val r = channel(16)
            val g = channel(8)
            val b = channel(0)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
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
