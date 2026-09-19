package com.fpink.capture.ui.detail

import com.fpink.core.model.InkColorOrigin
import com.fpink.core.model.Note
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteEditingTest {
    private val note = Note(
        id = "source-0", capturedAt = Instant.fromEpochMilliseconds(1000),
        imagePath = "images/source.png", text = "Original paragraph",
        inkColorHex = "#000000", inkColorName = "Black", inkColorOrigin = InkColorOrigin.DEFAULTED,
        sourceId = "source", paragraphIndex = 0, recognitionProvider = "PADDLE",
    )

    @Test
    fun `colour only edit preserves text flags identity and shared image`() {
        val updated = applyNoteEdits(note, note.text, "#2447a7", colorTouched = true)
        assertEquals("#2447A7", updated.inkColorHex)
        assertEquals("Blue", updated.inkColorName)
        assertEquals(InkColorOrigin.USER_SELECTED, updated.inkColorOrigin)
        assertFalse(updated.userEdited)
        assertEquals(note.text, updated.text)
        assertEquals(note.sourceId, updated.sourceId)
        assertEquals(note.imagePath, updated.imagePath)
        assertEquals(note.paragraphIndex, updated.paragraphIndex)
    }

    @Test
    fun `selected black is distinct from fallback black`() {
        val updated = applyNoteEdits(note, note.text, "#000000", colorTouched = true)
        assertEquals(InkColorOrigin.USER_SELECTED, updated.inkColorOrigin)
        assertEquals("Black", updated.inkColorName)
    }

    @Test
    fun `text edit preserves detected colour and legacy metadata`() {
        val legacy = note.copy(inkColorHex = null, inkColorName = null, inkColorOrigin = null)
        val updated = applyNoteEdits(legacy, "Edited text", "#000000", colorTouched = false)
        assertNull(updated.inkColorHex)
        assertNull(updated.inkColorOrigin)
        assertTrue(updated.userEdited)
        assertTrue(applyNoteEdits(note.copy(userEdited = true), note.text, "#123456", true).userEdited)
    }

    @Test
    fun `hex input is strict and normalized`() {
        assertEquals("#AABBCC", normalizeInkHex(" #aabbcc "))
        listOf("", "red", "123456", "#123", "#12345678", "#12XY56").forEach { assertNull(normalizeInkHex(it)) }
    }
}
