package com.fpink.recognition.models

import ai.onnxruntime.OnnxTensor
import android.content.Context
import com.fpink.core.ai.DetectedLine
import com.fpink.core.ai.PreparedImage
import com.fpink.core.ai.TextDetector
import com.fpink.recognition.runtime.DbParameters
import com.fpink.recognition.runtime.DbPostProcessing
import com.fpink.recognition.runtime.ImageSampling
import com.fpink.recognition.runtime.OnnxModelSession
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Official PP-OCRv6 small text detector (DB). Pre- and post-processing follow the pinned
 * export's `inference.yml`: BGR, ImageNet mean/std, long side 960 on multiples of 32, and
 * `DBPostProcess` thresh 0.2, box_thresh 0.45, unclip_ratio 1.4, max_candidates 3000.
 */
class PpOcrV6SmallDetector(context: Context) : TextDetector {
    private val session = OnnxModelSession(context, ModelAssets.PP_OCRV6_SMALL_DET, "PP-OCRv6 detector")

    override val modelVersion: String = MODEL_VERSION

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) { session.readiness() }

    override suspend fun detect(image: PreparedImage): List<DetectedLine> = withContext(Dispatchers.Default) {
        val (inputWidth, inputHeight) = DbPostProcessing.inputSize(image.width, image.height, LONG_SIDE)
        val tensor = ImageSampling.detectorTensor(image.pixels, image.width, image.height, inputWidth, inputHeight, MEAN, STD)
        session.run { environment, ort ->
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(tensor),
                longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong()),
            ).use { input ->
                ort.run(mapOf(ort.inputNames.first() to input)).use { result ->
                    val output = result.get(0) as OnnxTensor
                    val shape = output.info.shape
                    check(shape.size == 4 && shape[0] == 1L && shape[1] == 1L) { "Unexpected detector output" }
                    val mapHeight = shape[2].toInt()
                    val mapWidth = shape[3].toInt()
                    val map = FloatArray(mapWidth * mapHeight).also { output.floatBuffer.get(it) }
                    DbPostProcessing.boxes(map, mapWidth, mapHeight, PARAMETERS).map {
                        DbPostProcessing.scale(it, mapWidth, mapHeight, image.width, image.height)
                    }
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val MODEL_VERSION = "PP-OCRv6_small_det@28fe5895"
        private const val LONG_SIDE = 960
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val PARAMETERS = DbParameters(threshold = 0.2f, boxThreshold = 0.45f, unclipRatio = 1.4f, maxCandidates = 3000)
    }
}