package com.fpink.core.ai

/**
 * One trained model behind a [RecognitionStrategy]. Implementations own native resources and
 * must be closed when no longer needed. [readiness] verifies the model can run on this device
 * without performing recognition.
 */
interface RecognitionModel : AutoCloseable {
    val modelVersion: String
    suspend fun readiness(): Result<Unit>
}

/** Finds text lines on a page. */
interface TextDetector : RecognitionModel {
    suspend fun detect(image: PreparedImage): List<DetectedLine>
}

/** Reads a single rectified text line. Returns null when nothing legible was found. */
interface TextLineRecognizer : RecognitionModel {
    suspend fun recognize(line: LineCrop): RecognizedLine?
}

/** A point in prepared-image pixel coordinates. */
data class PixelPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite()) { "Pixel coordinates must be finite" }
    }
}

/**
 * A detected text line as a quadrilateral in prepared-image pixels, ordered top-left, top-right,
 * bottom-right, bottom-left along the reading direction.
 */
data class DetectedLine(val quad: List<PixelPoint>, val score: Float) {
    init {
        require(quad.size == 4) { "A detected line must be a quadrilateral" }
        require(score.isFinite()) { "Detection score must be finite" }
    }
}

/** An upright, rectified line image as ARGB pixels, ready for a [TextLineRecognizer]. */
class LineCrop(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height) {
            "Line crop dimensions must match the pixel buffer"
        }
    }
}

data class RecognizedLine(val text: String, val confidence: Float)