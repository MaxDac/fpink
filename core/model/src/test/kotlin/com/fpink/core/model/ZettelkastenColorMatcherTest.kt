package com.fpink.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ZettelkastenColorMatcherTest {
    private val scheme = mapOf(
        ZettelkastenCategory.FLEETING to listOf("#000000"),
        ZettelkastenCategory.LITERATURE to listOf("#2447A7"),
        ZettelkastenCategory.PERMANENT to listOf("#147D45", "#B42532"),
    )

    @Test
    fun `an exact configured colour matches its category`() {
        assertEquals(ZettelkastenCategory.LITERATURE, matchZettelkastenCategory("#2447A7", scheme))
        assertEquals(ZettelkastenCategory.PERMANENT, matchZettelkastenCategory("#147D45", scheme))
    }

    @Test
    fun `a slightly different photographed colour still matches within tolerance`() {
        // A one-off nudge per channel simulates ordinary photo/scan colour drift.
        assertEquals(ZettelkastenCategory.PERMANENT, matchZettelkastenCategory("#157E46", scheme))
    }

    @Test
    fun `an unconfigured or far colour defaults to fleeting`() {
        assertEquals(ZettelkastenCategory.FLEETING, matchZettelkastenCategory("#FFD700", scheme))
    }

    @Test
    fun `no detected colour defaults to fleeting`() {
        assertEquals(ZettelkastenCategory.FLEETING, matchZettelkastenCategory(null, scheme))
    }

    @Test
    fun `an empty scheme defaults to fleeting`() {
        assertEquals(ZettelkastenCategory.FLEETING, matchZettelkastenCategory("#147D45", emptyMap()))
    }

    @Test
    fun `an invalid hex value defaults to fleeting rather than throwing`() {
        assertEquals(ZettelkastenCategory.FLEETING, matchZettelkastenCategory("not-a-colour", scheme))
    }

    @Test
    fun `the closest configured colour wins when multiple are within tolerance`() {
        val close = mapOf(
            ZettelkastenCategory.LITERATURE to listOf("#147D45"),
            ZettelkastenCategory.PERMANENT to listOf("#157E46"),
        )
        assertEquals(ZettelkastenCategory.PERMANENT, matchZettelkastenCategory("#157E46", close))
    }
}
