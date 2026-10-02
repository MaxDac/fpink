package com.fpink.recognition.strategies

import com.fpink.core.ai.DetectedLine
import com.fpink.core.ai.PreparedImage
import com.fpink.core.ai.RecognitionDocument
import com.fpink.core.ai.RecognitionError
import com.fpink.core.ai.RecognitionStrategy
import com.fpink.core.ai.RecognitionStrategyId
import com.fpink.core.ai.TextDetector
import com.fpink.core.ai.TextLineRecognizer
import com.fpink.core.ai.TextRegion
import com.fpink.core.model.ImagePoint
import com.fpink.recognition.runtime.ImageSampling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Detects text lines on the page, then recognises each line crop in reading order. Pages run
 * one at a time process-wide (models are large) and the native sessions are released after
 * each page. Lines below [minimumConfidence] or with blank text are dropped.
 */
open class DetectThenRecognizeStrategy(
    override val id: RecognitionStrategyId,
    private val detector: TextDetector,
    private val recognizer: TextLineRecognizer,
    private val minimumConfidence: Float = 0.5f,
) : RecognitionStrategy {
    val modelVersion: String get() = "${detector.modelVersion}+${recognizer.modelVersion}"

    /** Combined readiness of every model this strategy needs; never touches the network. */
    suspend fun readiness(): Result<Unit> =
        detector.readiness().mapCatching { recognizer.readiness().getOrThrow() }

    override suspend fun recognize(image: PreparedImage): Result<RecognitionDocument> = withContext(Dispatchers.Default) {
        try {
            if (image.width < MIN_SIDE || image.height < MIN_SIDE || image.width > MAX_SIDE ||
                image.height > MAX_SIDE || image.pixels.size > MAX_PIXELS
            ) {
                throw RecognitionError.UnsupportedDevice(
                    "On-device recognition requires a prepared image of $MIN_SIDE-$MAX_SIDE pixels per side and at most 16 million pixels.",
                )
            }
            pageLock.withLock {
                try {
                    currentCoroutineContext().ensureActive()
                    readiness().getOrThrow()
                    val lines = readingOrder(detector.detect(image))
                    val regions = lines.mapNotNull { line ->
                        currentCoroutineContext().ensureActive()
                        val crop = ImageSampling.cropLine(image.pixels, image.width, image.height, line)
                            ?: return@mapNotNull null
                        val recognized = recognizer.recognize(crop) ?: return@mapNotNull null
                        if (recognized.text.isBlank() || recognized.confidence < minimumConfidence) return@mapNotNull null
                        TextRegion(
                            text = recognized.text,
                            polygon = line.quad.map {
                                ImagePoint((it.x / image.width).coerceIn(0f, 1f), (it.y / image.height).coerceIn(0f, 1f))
                            },
                            confidence = recognized.confidence,
                        )
                    }
                    Result.success(RecognitionDocument(regions, id, modelVersion))
                } finally {
                    detector.close()
                    recognizer.close()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RecognitionError) {
            Result.failure(error)
        } catch (error: IllegalArgumentException) {
            Result.failure(RecognitionError.UnsupportedDevice(error.message ?: "Recognition input limit exceeded."))
        } catch (error: Exception) {
            Result.failure(RecognitionError.ModelUnavailable("Local recognition failed.", error))
        }
    }

    companion object {
        private const val MIN_SIDE = 16
        private const val MAX_SIDE = 8192
        private const val MAX_PIXELS = 16_000_000
        private val pageLock = Mutex()

        /**
         * Top-to-bottom, then left-to-right for lines whose vertical centres overlap the
         * current row by at least half the smaller line height.
         */
        internal fun readingOrder(lines: List<DetectedLine>): List<DetectedLine> {
            val rows = mutableListOf<MutableList<DetectedLine>>()
            lines.sortedBy { line -> line.quad.minOf { it.y } }.forEach { line ->
                val row = rows.lastOrNull()
                if (row != null && row.any { overlapsVertically(it, line) }) row += line else rows += mutableListOf(line)
            }
            return rows.flatMap { row -> row.sortedBy { line -> line.quad.minOf { it.x } } }
        }

        private fun overlapsVertically(a: DetectedLine, b: DetectedLine): Boolean {
            val aTop = a.quad.minOf { it.y }
            val aBottom = a.quad.maxOf { it.y }
            val bTop = b.quad.minOf { it.y }
            val bBottom = b.quad.maxOf { it.y }
            val overlap = minOf(aBottom, bBottom) - maxOf(aTop, bTop)
            return overlap >= 0.5f * minOf(aBottom - aTop, bBottom - bTop)
        }
    }
}