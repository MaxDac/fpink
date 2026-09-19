package com.fpink.capture.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageLimitsTest {
    @Test
    fun `ordinary small handwriting images are not downsampled`() {
        assertEquals(1, imageSampleSize(1600, 2200))
        assertEquals(1, imageSampleSize(3072, 1000))
    }

    @Test
    fun `large images stay within both pixel and edge budgets`() {
        listOf(4000 to 3000, 12000 to 16000, 32768 to 500, 1 to 32768, 3073 to 1000).forEach { (width, height) ->
            val sample = imageSampleSize(width, height)
            val outputWidth = (width.toLong() + sample - 1) / sample
            val outputHeight = (height.toLong() + sample - 1) / sample
            assertTrue(outputWidth * outputHeight <= ImageImportStore.MAX_PIXELS)
            assertTrue(maxOf(outputWidth, outputHeight) <= ImageImportStore.MAX_EDGE)
            assertEquals(0, sample and (sample - 1))
        }
    }
}
