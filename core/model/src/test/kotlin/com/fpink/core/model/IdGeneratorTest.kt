package com.fpink.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdGeneratorTest {

    private val idRegex = Regex("^\\d{14}[a-z0-9]{4}$")

    @Test
    fun `generated id matches expected format`() {
        val id = IdGenerator.generate()
        assertTrue(idRegex.matches(id), "ID '$id' does not match $idRegex")
    }

    @Test
    fun `generated id has length 18`() {
        val id = IdGenerator.generate()
        assertEquals(18, id.length)
    }

    @Test
    fun `two consecutive ids differ`() {
        val first = IdGenerator.generate()
        val second = IdGenerator.generate()
        assertNotEquals(first, second)
    }
}
