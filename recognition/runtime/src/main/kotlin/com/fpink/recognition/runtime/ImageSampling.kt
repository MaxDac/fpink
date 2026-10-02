package com.fpink.recognition.runtime

import com.fpink.core.ai.DetectedLine
import com.fpink.core.ai.LineCrop
import com.fpink.core.ai.PixelPoint
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel sampling and tensor packing shared by the bundled ONNX models. */
object ImageSampling {
    /** Upper bound for the height of a rectified line; recognizers only ever scale down from it. */
    const val MAX_CROP_HEIGHT = 192
    const val MAX_CROP_WIDTH = 8192

    /**
     * Bilinear RGB sample of ARGB [pixels] at ([x], [y]), compositing transparency on white.
     * Returns channels in R, G, B order as 0..255 floats in [out].
     */
    fun sampleRgb(pixels: IntArray, width: Int, height: Int, x: Float, y: Float, out: FloatArray) {
        val cx = x.coerceIn(0f, (width - 1).toFloat())
        val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = cx - x0
        val fy = cy - y0
        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        for (c in 0 until 3) {
            val shift = 16 - c * 8
            out[c] = (composite(p00, shift) * (1 - fx) + composite(p10, shift) * fx) * (1 - fy) +
                (composite(p01, shift) * (1 - fx) + composite(p11, shift) * fx) * fy
        }
    }

    private fun composite(argb: Int, shift: Int): Float {
        val alpha = (argb ushr 24) / 255f
        return ((argb shr shift) and 0xff) * alpha + 255f * (1f - alpha)
    }

    /**
     * Rectifies [line] into an upright crop. A detector rectangle maps to an affine
     * quadrilateral in the image, so bilinear interpolation between its vertices is exact.
     * Lines at least 1.5 times taller than wide are rotated (the PP-OCR rotate-crop convention;
     * no orientation classifier is bundled). Returns null for degenerate quads.
     */
    fun cropLine(pixels: IntArray, width: Int, height: Int, line: DetectedLine): LineCrop? {
        var quad: List<PixelPoint> = line.quad
        var cropWidth = max(length(quad[0], quad[1]), length(quad[3], quad[2]))
        var cropHeight = max(length(quad[0], quad[3]), length(quad[1], quad[2]))
        if (cropWidth < 1f || cropHeight < 1f) return null
        if (cropHeight >= cropWidth * 1.5f) {
            quad = listOf(quad[1], quad[2], quad[3], quad[0])
            cropWidth = cropHeight.also { cropHeight = cropWidth }
        }
        val scale = min(1f, MAX_CROP_HEIGHT / cropHeight)
        val outHeight = max(1, (cropHeight * scale).roundToInt())
        val outWidth = (ceil(cropWidth * scale).toInt()).coerceIn(1, MAX_CROP_WIDTH)
        val out = IntArray(outWidth * outHeight)
        val rgb = FloatArray(3)
        for (y in 0 until outHeight) {
            val v = (y + 0.5f) / outHeight
            for (x in 0 until outWidth) {
                val u = (x + 0.5f) / outWidth
                val sx = (1 - v) * ((1 - u) * quad[0].x + u * quad[1].x) + v * ((1 - u) * quad[3].x + u * quad[2].x)
                val sy = (1 - v) * ((1 - u) * quad[0].y + u * quad[1].y) + v * ((1 - u) * quad[3].y + u * quad[2].y)
                sampleRgb(pixels, width, height, sx - 0.5f, sy - 0.5f, rgb)
                out[y * outWidth + x] = argb(rgb)
            }
        }
        return LineCrop(outWidth, outHeight, out)
    }

    /** Bilinear resize of an opaque [crop] to [targetWidth] x [targetHeight]. */
    fun resize(crop: LineCrop, targetWidth: Int, targetHeight: Int): LineCrop {
        require(targetWidth > 0 && targetHeight > 0)
        val out = IntArray(targetWidth * targetHeight)
        val xScale = crop.width.toFloat() / targetWidth
        val yScale = crop.height.toFloat() / targetHeight
        val rgb = FloatArray(3)
        for (y in 0 until targetHeight) {
            val sy = (y + 0.5f) * yScale - 0.5f
            for (x in 0 until targetWidth) {
                sampleRgb(crop.pixels, crop.width, crop.height, (x + 0.5f) * xScale - 0.5f, sy, rgb)
                out[y * targetWidth + x] = argb(rgb)
            }
        }
        return LineCrop(targetWidth, targetHeight, out)
    }

    /**
     * Resizes the page to [inputWidth] x [inputHeight] and packs it as a 1x3xHxW BGR tensor
     * normalised with `(value / 255 - mean[c]) / std[c]`, matching the PP-OCR `NormalizeImage`
     * applied to an OpenCV BGR image.
     */
    fun detectorTensor(
        pixels: IntArray,
        width: Int,
        height: Int,
        inputWidth: Int,
        inputHeight: Int,
        mean: FloatArray,
        std: FloatArray,
    ): FloatArray {
        val plane = inputWidth * inputHeight
        val tensor = FloatArray(3 * plane)
        val rgb = FloatArray(3)
        for (y in 0 until inputHeight) {
            val sy = (y + 0.5f) * height / inputHeight - 0.5f
            for (x in 0 until inputWidth) {
                sampleRgb(pixels, width, height, (x + 0.5f) * width / inputWidth - 0.5f, sy, rgb)
                for (c in 0 until 3) {
                    // BGR channel c reads RGB channel 2 - c.
                    tensor[c * plane + y * inputWidth + x] = (rgb[2 - c] / 255f - mean[c]) / std[c]
                }
            }
        }
        return tensor
    }

    private fun length(a: PixelPoint, b: PixelPoint): Float = hypot(a.x - b.x, a.y - b.y)

    private fun argb(rgb: FloatArray): Int {
        val r = rgb[0].roundToInt().coerceIn(0, 255)
        val g = rgb[1].roundToInt().coerceIn(0, 255)
        val b = rgb[2].roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
}
