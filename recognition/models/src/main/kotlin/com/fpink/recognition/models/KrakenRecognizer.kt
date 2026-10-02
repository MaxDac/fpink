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
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kraken's PP-OCRv6 medium recognizer (Zenodo 10.5281/zenodo.21788410) as a reviewed ONNX
 * export. Preprocessing mirrors Kraken's `ImageInputTransforms` for this checkpoint: height 96,
 * 16 px of white padding left and right, RGB scaled to [0, 1] and inverted. Kraken resizes with
 * Lanczos; this port uses bilinear interpolation (see recognition/models/README.md). The codec
 * emits NFD graphemes, so output is normalised to NFC.
 */
class KrakenRecognizer(context: Context) : TextLineRecognizer {
    private val context = context.applicationContext
    private val session = OnnxModelSession(context, ModelAssets.KRAKEN_REC, "Kraken recognizer")
    private val alphabet by lazy {
        labels(BundledAssets.readText(this.context, ModelAssets.KRAKEN_ALPHABET)).also {
            check(it.size == ALPHABET_SIZE) { "Unexpected Kraken alphabet size" }
        }
    }

    override val modelVersion: String = MODEL_VERSION

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        recognitionResult("Kraken recognizer") { alphabet }.mapCatching { session.readiness().getOrThrow() }
    }

    override suspend fun recognize(line: LineCrop): RecognizedLine? = withContext(Dispatchers.Default) {
        val labels = alphabet
        val scaledWidth = (line.width.toFloat() * HEIGHT / line.height).toInt().coerceIn(1, MAX_WIDTH)
        val resized = ImageSampling.resize(line, scaledWidth, HEIGHT)
        val inputWidth = scaledWidth + 2 * PADDING
        val plane = HEIGHT * inputWidth
        // Inverted white padding is 0, so only the line itself needs writing.
        val tensor = FloatArray(3 * plane)
        for (channel in 0 until 3) {
            val shift = 16 - channel * 8
            for (y in 0 until HEIGHT) {
                for (x in 0 until scaledWidth) {
                    val component = (resized.pixels[y * scaledWidth + x] shr shift) and 0xff
                    tensor[channel * plane + y * inputWidth + x + PADDING] = 1f - component / 255f
                }
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
                    check(shape.size == 3 && shape[0] == 1L && shape[2] == labels.size + 1L) {
                        "Recognizer output does not match the pinned Kraken alphabet"
                    }
                    val steps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val scores = FloatArray(steps * classes).also { output.floatBuffer.get(it) }
                    CtcDecoder.greedy(scores, steps, classes, scoresAreProbabilities = false) { labels[it - 1] }
                }
            }
        }
        Normalizer.normalize(decoded.text, Normalizer.Form.NFC).trim().takeIf { it.isNotEmpty() }
            ?.let { RecognizedLine(it, decoded.confidence) }
    }

    override fun close() = session.close()

    companion object {
        const val MODEL_VERSION = "kraken-ppocrv6-medium/zenodo-10.5281-zenodo.21788410"
        private const val HEIGHT = 96
        private const val PADDING = 16
        private const val MAX_WIDTH = 4096
        private const val ALPHABET_SIZE = 1_622
    }
}