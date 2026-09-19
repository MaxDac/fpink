package com.fpink.capture.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExifTransformTest {
    @Test
    fun `all eight EXIF orientations preserve expected pixel locations including mirrors`() {
        val expected = mapOf(
            1 to listOf(1 to 0, 0 to 1),
            2 to listOf(-1 to 0, 0 to 1),
            3 to listOf(-1 to 0, 0 to -1),
            4 to listOf(1 to 0, 0 to -1),
            5 to listOf(0 to 1, 1 to 0),
            6 to listOf(0 to 1, -1 to 0),
            7 to listOf(0 to -1, -1 to 0),
            8 to listOf(0 to -1, 1 to 0),
        )
        expected.forEach { (orientation, transformed) ->
            val matrix = exifTransform(orientation)
            assertEquals(transformed[0], matrix[0].toInt() to matrix[3].toInt())
            assertEquals(transformed[1], matrix[1].toInt() to matrix[4].toInt())
            assertEquals(1f, matrix[8])
        }
    }
}
