package com.fpink.recognition.kraken

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KrakenAlgorithmTest {
    @Test
    fun `blank page has no line segments`() {
        val pixels = IntArray(100 * 60) { 0xffffffff.toInt() }
        assertTrue(KrakenLineSegmenter.segment(pixels, 100, 60).isEmpty())
    }

    @Test
    fun `projection segmenter maps dark rows to normalized geometry`() {
        val width = 100
        val height = 60
        val pixels = IntArray(width * height) { 0xffffffff.toInt() }
        for (y in 20 until 30) {
            for (x in 10 until 80) pixels[y * width + x] = 0xff111111.toInt()
        }
        val segment = KrakenLineSegmenter.segment(pixels, width, height).single()
        assertTrue(segment.left <= 10)
        assertTrue(segment.right >= 80)
        val polygon = segment.polygon(width, height)
        assertEquals(4, polygon.size)
        assertTrue(polygon.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test
    fun `ctc decoder removes blanks and repeated labels`() {
        val decoded = KrakenCtcDecoder.decode(
            intArrayOf(0, 1, 1, 0, 2, 2, 3, 0, 3),
            listOf("h", "i", "!"),
        )
        assertEquals("hi!!", decoded)
    }
}
