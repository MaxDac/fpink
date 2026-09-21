package com.fpink.core.model

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteSerializationTest {
    @Test
    fun `legacy notes retain content and receive optional metadata defaults`() {
        val note = Json.decodeFromString<Note>(
            """
            {
                "id": "legacy-note",
                "capturedAt": "2026-01-01T12:00:00Z",
                "imagePath": "images/legacy-note.jpg",
                "text": "Original transcription",
                "inkColorHex": "#124578",
                "userEdited": true,
                "tags": ["journal"],
                "links": ["another-note"]
            }
            """.trimIndent(),
        )

        assertEquals("Original transcription", note.text)
        assertEquals("#124578", note.inkColorHex)
        assertEquals(listOf("journal"), note.tags)
        assertEquals(listOf("another-note"), note.links)
        assertTrue(note.userEdited)
        assertNull(note.sourceId)
        assertNull(note.paragraphIndex)
        assertTrue(note.paragraphPolygon.isEmpty())
        assertNull(note.recognitionProvider)
        assertNull(note.recognitionModelVersion)
        assertNull(note.inkColorOrigin)
        assertEquals(ZettelkastenCategory.FLEETING, note.zettelkastenCategory)
    }

    @Test
    fun `paragraph metadata and colour provenance survive JSON round trips`() {
        InkColorOrigin.entries.forEach { origin ->
            ZettelkastenCategory.entries.forEach { category ->
                val note = Note(
                    id = "source-0",
                    capturedAt = Instant.parse("2026-01-01T12:00:00Z"),
                    imagePath = "images/source.png",
                    text = "Cursive paragraph",
                    inkColorHex = "#000000",
                    inkColorName = "Black",
                    sourceId = "source",
                    paragraphIndex = 0,
                    paragraphPolygon = listOf(
                        ImagePoint(0.1f, 0.2f),
                        ImagePoint(0.9f, 0.2f),
                        ImagePoint(0.9f, 0.4f),
                        ImagePoint(0.1f, 0.4f),
                    ),
                    recognitionProvider = "PADDLE",
                    recognitionModelVersion = "PP-OCRv5_mobile",
                    inkColorOrigin = origin,
                    zettelkastenCategory = category,
                )

                assertEquals(note, Json.decodeFromString<Note>(Json.encodeToString(note)))
            }
        }
    }
}
