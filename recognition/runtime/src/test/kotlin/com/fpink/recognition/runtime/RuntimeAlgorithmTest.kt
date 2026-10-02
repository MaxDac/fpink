package com.fpink.recognition.runtime

import com.fpink.core.ai.DetectedLine
import com.fpink.core.ai.PixelPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeAlgorithmTest {
    private val parameters = DbParameters(threshold = 0.3f, boxThreshold = 0.6f, unclipRatio = 1.5f, maxCandidates = 1000)

    @Test
    fun `detector input keeps aspect ratio on multiples of 32`() {
        assertEquals(960 to 480, DbPostProcessing.inputSize(1200, 600))
        assertEquals(480 to 960, DbPostProcessing.inputSize(600, 1200))
        assertEquals(960 to 32, DbPostProcessing.inputSize(8192, 16))
    }

    @Test
    fun `blank map has no lines and a block becomes one box`() {
        val map = FloatArray(200 * 100)
        assertTrue(DbPostProcessing.boxes(map, 200, 100, parameters).isEmpty())
        for (y in 30..45) for (x in 20..120) map[y * 200 + x] = 0.9f
        val box = DbPostProcessing.boxes(map, 200, 100, parameters).single()
        val quad = DbPostProcessing.scale(box, 200, 100, 1, 1).quad
        assertTrue(quad[0].x > 0f && quad[0].x < 0.1f)
        assertTrue(quad[0].y > 0.1f && quad[0].y < 0.3f)
        assertTrue(quad[2].x > 0.6f && quad[2].x < 0.8f)
        assertTrue(quad[2].y > 0.45f && quad[2].y < 0.7f)
        assertEquals(0.9f, box.score, 0.001f)
    }

    @Test
    fun `malformed maps are rejected`() {
        val map = FloatArray(200 * 100)
        map[0] = Float.NaN
        assertThrows(IllegalStateException::class.java) { DbPostProcessing.boxes(map, 200, 100, parameters) }
        assertThrows(IllegalStateException::class.java) { DbPostProcessing.boxes(FloatArray(10), 200, 100, parameters) }
    }

    @Test
    fun `slanted component keeps its orientation`() {
        val map = FloatArray(200 * 100)
        for (x in 25 until 150) for (y in 15 + x / 5 until 29 + x / 5) map[y * 200 + x] = 0.95f
        val quad = DbPostProcessing.boxes(map, 200, 100, parameters).single().quad
        assertTrue(quad[1].y > quad[0].y + 10)
        assertTrue(quad[1].x > quad[0].x + 100)
    }

    @Test
    fun `lines are returned in reading order`() {
        val map = FloatArray(200 * 100)
        for (y in 60..70) for (x in 10..90) map[y * 200 + x] = 0.9f
        for (y in 10..20) for (x in 110..190) map[y * 200 + x] = 0.9f
        for (y in 10..20) for (x in 10..90) map[y * 200 + x] = 0.9f
        val boxes = DbPostProcessing.boxes(map, 200, 100, parameters)
        assertEquals(3, boxes.size)
        assertTrue(boxes[0].quad[0].x < boxes[1].quad[0].x)
        assertTrue(boxes[1].quad[0].y < boxes[2].quad[0].y)
    }

    @Test
    fun `too many candidates fail closed`() {
        val map = FloatArray(100 * 100)
        for (y in 0 until 100 step 3) for (x in 0 until 100 step 3) map[y * 100 + x] = 0.9f
        assertThrows(IllegalArgumentException::class.java) {
            DbPostProcessing.boxes(map, 100, 100, parameters.copy(maxCandidates = 10))
        }
    }

    @Test
    fun `sampling reads rgb and composites transparency on white`() {
        val out = FloatArray(3)
        ImageSampling.sampleRgb(IntArray(4) { 0xff112233.toInt() }, 2, 2, 0.5f, 0.5f, out)
        assertEquals(listOf(17f, 34f, 51f), out.toList())
        ImageSampling.sampleRgb(IntArray(4) { 0x00112233 }, 2, 2, 0f, 0f, out)
        assertEquals(listOf(255f, 255f, 255f), out.toList())
    }

    @Test
    fun `detector tensor is bgr and imagenet normalised`() {
        val tensor = ImageSampling.detectorTensor(
            IntArray(4) { 0xffff0000.toInt() }, 2, 2, 2, 2,
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f),
        )
        assertEquals(0f, tensor[0], 0.0001f)
        assertEquals(1f, tensor[8], 0.0001f)
    }

    @Test
    fun `crops rectify a line and rotate tall lines`() {
        val width = 40
        val height = 20
        val pixels = IntArray(width * height) { 0xffffffff.toInt() }
        for (y in 5 until 10) for (x in 0 until 40) pixels[y * width + x] = 0xff000000.toInt()
        val line = DetectedLine(listOf(PixelPoint(0f, 5f), PixelPoint(40f, 5f), PixelPoint(40f, 10f), PixelPoint(0f, 10f)), 1f)
        val crop = ImageSampling.cropLine(pixels, width, height, line)!!
        assertEquals(40, crop.width)
        assertEquals(5, crop.height)
        assertTrue(crop.pixels.all { it and 0xff < 0x40 })
        val tall = DetectedLine(listOf(PixelPoint(0f, 0f), PixelPoint(4f, 0f), PixelPoint(4f, 20f), PixelPoint(0f, 20f)), 1f)
        val rotated = ImageSampling.cropLine(pixels, width, height, tall)!!
        assertEquals(20, rotated.width)
        assertEquals(4, rotated.height)
        val degenerate = DetectedLine(List(4) { PixelPoint(3f, 3f) }, 1f)
        assertNull(ImageSampling.cropLine(pixels, width, height, degenerate))
        val resized = ImageSampling.resize(crop, 80, 10)
        assertEquals(800, resized.pixels.size)
    }

    @Test
    fun `ctc decoder collapses repeats and averages emitted probabilities`() {
        val labels = listOf("A", "B", " ")
        val tokens = intArrayOf(0, 1, 1, 0, 1, 2, 3)
        val scores = FloatArray(7 * 4)
        tokens.forEachIndexed { step, token -> scores[step * 4 + token] = 0.9f }
        val decoded = CtcDecoder.greedy(scores, 7, 4, scoresAreProbabilities = true) { labels[it - 1] }
        assertEquals("AAB ", decoded.text)
        assertEquals(0.9f, decoded.confidence, 0.0001f)
        assertThrows(IllegalStateException::class.java) {
            CtcDecoder.greedy(scores, 7, 3, scoresAreProbabilities = true) { labels[it - 1] }
        }
    }

    @Test
    fun `ctc decoder softmaxes raw logits`() {
        val alphabet = listOf("h", "i", "!")
        val path = intArrayOf(0, 1, 1, 0, 2, 2, 3, 0, 3)
        val scores = FloatArray(path.size * 4) { -10f }
        path.forEachIndexed { step, token -> scores[step * 4 + token] = 10f }
        val decoded = CtcDecoder.greedy(scores, path.size, 4, scoresAreProbabilities = false) { alphabet[it - 1] }
        assertEquals("hi!!", decoded.text)
        assertTrue(decoded.confidence > 0.99f)
        val nan = scores.copyOf().also { it[0] = Float.NaN }
        assertThrows(IllegalStateException::class.java) {
            CtcDecoder.greedy(nan, path.size, 4, scoresAreProbabilities = false) { alphabet[it - 1] }
        }
    }
}
