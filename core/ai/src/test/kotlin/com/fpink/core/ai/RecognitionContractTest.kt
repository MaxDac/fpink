package com.fpink.core.ai

import com.fpink.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RecognitionContractTest {
    @Test
    fun `settings default to offline and never print the key`() {
        assertEquals(RecognitionStrategyId.CURSIVE, RecognitionSettings().strategy)
        assertEquals("printed", RecognitionStrategyId.PRINTED.id)
        assertEquals("cursive", RecognitionStrategyId.CURSIVE.id)
        assertFalse(RecognitionSettings(RecognitionStrategyId("cloud"), mapOf("apiKey" to "private-key")).toString().contains("private-key"))
    }

    @Test
    fun `registry never shadows built-in strategies`() {
        assertEquals(null, RecognitionStrategyRegistry.find(RecognitionStrategyId.CURSIVE))
        assertEquals(emptyList<RecognitionStrategyPlugin>(), RecognitionStrategyRegistry.all())
    }

    @Test
    fun `detected lines are quadrilaterals and crops match their pixels`() {
        assertThrows(IllegalArgumentException::class.java) { DetectedLine(listOf(PixelPoint(0f, 0f)), 1f) }
        assertThrows(IllegalArgumentException::class.java) { LineCrop(2, 2, IntArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { PixelPoint(Float.NaN, 0f) }
    }

    @Test
    fun `image dimensions must match pixels`() {
        assertThrows(IllegalArgumentException::class.java) {
            PreparedImage(byteArrayOf(1), "image/png", 2, 2, intArrayOf(0))
        }
    }

    @Test
    fun `coordinates cannot be nonfinite or outside the image`() {
        assertThrows(IllegalArgumentException::class.java) { ImagePoint(Float.NaN, 0f) }
        assertThrows(IllegalArgumentException::class.java) { ImagePoint(0f, 1.1f) }
    }
}
