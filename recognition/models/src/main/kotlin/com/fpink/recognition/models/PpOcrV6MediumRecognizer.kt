package com.fpink.recognition.models

import ai.onnxruntime.OnnxTensor
import android.content.Context
import com.fpink.core.ai.LineCrop
import com.fpink.core.ai.RecognizedLine
import com.fpink.core.ai.TextLineRecognizer
import com.fpink.recognition.runtime.BundledAssets
import com.fpink.recognition.runtime.CtcDecoder
import com.fpink.recognition.runtime.ImageSampling
import com.fpink.recognition.runtime.OnnxModelSession
import com.fpink.recognition.runtime.recognitionResult
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Official PP-OCRv6 medium line recognizer (CTC). Input follows the pinned `inference.yml`:
 * BGR, height 48, width `ceil(48 * w / h)` padded with zeros to at least 320, values
 * `x / 127.5 - 1`. Classes are blank, the bundled dictionary, then a space.
 */
class PpOcrV6MediumRecognizer(context: Context) : TextLineRecognizer {
    private val context = context.applicationContext
    private val session = OnnxModelSession(context, ModelAssets.PP_OCRV6_MEDIUM_REC, "PP-OCRv6 recognizer")
    private val dictionary by lazy {
        labels(BundledAssets.readText(this.context, ModelAssets.PP_OCRV6_MEDIUM_REC_DICT)).also {
            check(it.size == DICTIONARY_SIZE) { "Unexpected PP-OCRv6 dictionary size" }
        }
    }

    override val modelVersion: String = MODEL_VERSION

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        recognitionResult("PP-OCRv6 recognizer") { dictionary }.mapCatching { session.readiness().getOrThrow() }
    }

    override suspend fun recognize(line: LineCrop): RecognizedLine? = withContext(Dispatchers.Default) {
        val labels = dictionary
        val resizedWidth = ceil(HEIGHT.toFloat() * line.width / line.height).toInt().coerceIn(1, MAX_WIDTH)
        val inputWidth = max(MIN_WIDTH, resizedWidth)
        val resized = ImageSampling.resize(line, resizedWidth, HEIGHT)
        val plane = HEIGHT * inputWidth
        val tensor = FloatArray(3 * plane)
        for (y in 0 until HEIGHT) {
            for (x in 0 until resizedWidth) {
                val argb = resized.pixels[y * resizedWidth + x]
                tensor[y * inputWidth + x] = (argb and 0xff) / 127.5f - 1f
                tensor[plane + y * inputWidth + x] = (argb shr 8 and 0xff) / 127.5f - 1f
                tensor[2 * plane + y * inputWidth + x] = (argb shr 16 and 0xff) / 127.5f - 1f
            }
        }
        val decoded = session.run { environment, ort ->
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(tensor),
                longArrayOf(1, 3, HEIGHT.toLong(), inputWidth.toLong()),
            ).use { input ->
                ort.run(mapOf(ort.inputNames.first() to input)).use { result ->
                    val output = result.get(0) as OnnxTensor
                    val shape = output.info.shape
                    check(shape.size == 3 && shape[0] == 1L && shape[2] == labels.size + 2L) {
                        "Recognizer output does not match the pinned PP-OCRv6 dictionary"
                    }
                    val steps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val scores = FloatArray(steps * classes).also { output.floatBuffer.get(it) }
                    CtcDecoder.greedy(scores, steps, classes, scoresAreProbabilities = true) { token ->
                        if (token == classes - 1) " " else labels[token - 1]
                    }
                }
            }
        }
        decoded.text.trim().takeIf { it.isNotEmpty() }?.let { RecognizedLine(it, decoded.confidence) }
    }

    override fun close() = session.close()

    companion object {
        const val MODEL_VERSION = "PP-OCRv6_medium_rec@50c7eacaf"
        private const val HEIGHT = 48
        private const val MIN_WIDTH = 320
        private const val MAX_WIDTH = 2048
        private const val DICTIONARY_SIZE = 18_708
    }
}