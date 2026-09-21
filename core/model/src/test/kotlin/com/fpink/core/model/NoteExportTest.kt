package com.fpink.core.model

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteExportTest {
    @Test
    fun `json preserves note order and textual metadata without image paths`() {
        val first = note("first", "First note").copy(
            inkColorHex = "#102030",
            inkColorName = "Blue",
            confidence = 0.75f,
            modelNotes = "Detected as handwriting",
            userEdited = true,
            tags = listOf("work"),
            links = listOf("second"),
            sourceId = "source",
            paragraphIndex = 2,
            paragraphPolygon = listOf(ImagePoint(0.1f, 0.2f)),
            recognitionProvider = "PADDLE",
            recognitionModelVersion = "PP-OCRv5_mobile",
            inkColorOrigin = InkColorOrigin.USER_SELECTED,
        )
        val second = note("second", "Second note")

        val exported = Json.parseToJsonElement(NoteExport.asJson(listOf(first, second))).jsonArray

        assertEquals(listOf("first", "second"), exported.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val firstExport = exported.first().jsonObject
        assertFalse("imagePath" in firstExport)
        assertEquals("First note", firstExport.getValue("text").jsonPrimitive.content)
        assertEquals("#102030", firstExport.getValue("inkColorHex").jsonPrimitive.content)
        assertEquals("source", firstExport.getValue("sourceId").jsonPrimitive.content)
        assertEquals("PADDLE", firstExport.getValue("recognitionProvider").jsonPrimitive.content)
        assertEquals("USER_SELECTED", firstExport.getValue("inkColorOrigin").jsonPrimitive.content)
        assertTrue(firstExport.keys.containsAll(setOf("capturedAt", "confidence", "tags", "links", "paragraphPolygon")))
    }

    @Test
    fun `plain text contains only note bodies separated by a blank line`() {
        val notes = listOf(note("first", "First\nline"), note("second", "Second"))

        assertEquals("First\nline\n\nSecond", NoteExport.asText(notes))
        assertEquals("", NoteExport.asText(emptyList()))
    }

    private fun note(id: String, text: String) = Note(
        id = id,
        capturedAt = Instant.parse("2026-01-01T12:00:00Z"),
        imagePath = "images/$id.png",
        text = text,
    )
}
