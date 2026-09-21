package com.fpink.capture.data

import com.fpink.core.model.ZettelkastenCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZettelkastenSettingsTest {

    @Test fun `round trips a per category colour map`() {
        val colors = mapOf(
            ZettelkastenCategory.FLEETING to listOf("#000000"),
            ZettelkastenCategory.LITERATURE to listOf("#2447A7", "#ABCDEF"),
        )
        val encoded = encodeZettelkastenCategoryColors(colors)
        assertEquals(colors, decodeZettelkastenCategoryColors(encoded))
    }

    @Test fun `empty color lists are dropped so an empty map encodes and decodes back to empty`() {
        val colors = mapOf(ZettelkastenCategory.PERMANENT to emptyList<String>())
        assertEquals(emptyMap<ZettelkastenCategory, List<String>>(), decodeZettelkastenCategoryColors(encodeZettelkastenCategoryColors(colors)))
    }

    @Test fun `null or blank input decodes to an empty map`() {
        assertTrue(decodeZettelkastenCategoryColors(null).isEmpty())
        assertTrue(decodeZettelkastenCategoryColors("").isEmpty())
        assertTrue(decodeZettelkastenCategoryColors("   ").isEmpty())
    }

    @Test fun `corrupt json decodes to an empty map instead of throwing`() {
        assertTrue(decodeZettelkastenCategoryColors("not json").isEmpty())
        assertTrue(decodeZettelkastenCategoryColors("""{"unexpected":true}""").isEmpty())
    }
}
