package com.fpink.core.ai

import com.fpink.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RecognitionContractTest {
    @Test
    fun `settings default to offline and never print the key`() {
        assertEquals(RecognitionProviderId.PADDLE, RecognitionSettings().provider)
        assertFalse(RecognitionSettings(RecognitionProviderId("cloud"), mapOf("apiKey" to "private-key")).toString().contains("private-key"))
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
