package com.fpink.core.model

import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteSearchTest {
    @Test
    fun `blank query returns the input unchanged`() {
        val notes = listOf(note("a", "First"), note("b", "Second"))
        assertSame(notes, NoteSearch.rank(notes, "   "))
        assertEquals(0.0, NoteSearch.score(notes[0], ""))
    }

    @Test
    fun `matching ignores case punctuation and diacritics`() {
        val notes = listOf(note("a", "Café au lait, s'il vous plaît"), note("b", "Tea"))
        assertEquals(listOf("a"), ids(NoteSearch.rank(notes, "CAFE plait")))
    }

    @Test
    fun `tolerates recognition typos and transpositions`() {
        val notes = listOf(note("a", "Zettelkasten methodology"), note("b", "Unrelated"))
        assertEquals(listOf("a"), ids(NoteSearch.rank(notes, "zettlekasten")))
        assertEquals(listOf("a"), ids(NoteSearch.rank(notes, "methodolgy")))
        assertNull(NoteSearch.score(notes[1], "zettlekasten"))
    }

    @Test
    fun `short tokens require exact or prefix matches`() {
        val notes = listOf(note("a", "pen"), note("b", "open"), note("c", "pan"))
        assertEquals(listOf("a"), ids(NoteSearch.rank(notes, "pe")))
    }

    @Test
    fun `every query token must match`() {
        val notes = listOf(note("a", "fountain pen ink"), note("b", "fountain water"))
        assertEquals(listOf("a"), ids(NoteSearch.rank(notes, "fountain ink")))
    }

    @Test
    fun `exact matches outrank prefixes and fuzzy matches and ties keep input order`() {
        val notes = listOf(
            note("fuzzy", "notbook"),
            note("prefix", "notebooks"),
            note("exact", "notebook"),
            note("exact2", "notebook"),
        )
        assertEquals(listOf("exact", "exact2", "prefix", "fuzzy"), ids(NoteSearch.rank(notes, "notebook")))
    }

    @Test
    fun `tags and ink colour names are searchable with lower weight`() {
        val tagged = note("tagged", "Something").copy(tags = listOf("research"))
        val inText = note("text", "research summary")
        val coloured = note("colour", "Plain").copy(inkColorName = "Oxblood")
        assertEquals(listOf("text", "tagged"), ids(NoteSearch.rank(listOf(tagged, inText), "research")))
        assertNotNull(NoteSearch.score(coloured, "oxblood"))
    }

    @Test
    fun `tokens in reading order get a bonus`() {
        val ordered = note("ordered", "deep work habit")
        val reversed = note("reversed", "habit work deep")
        assertEquals(listOf("ordered", "reversed"), ids(NoteSearch.rank(listOf(reversed, ordered), "deep work habit")))
    }

    @Test
    fun `bounded edit distance stops above the limit`() {
        assertEquals(1, NoteSearch.boundedEditDistance("abcd", "abdc", 2))
        assertEquals(1, NoteSearch.boundedEditDistance("kitten", "kittn", 2))
        assertTrue(NoteSearch.boundedEditDistance("kitten", "sitting", 1) > 1)
    }

    private fun ids(notes: List<Note>) = notes.map { it.id }

    private fun note(id: String, text: String) = Note(
        id = id,
        capturedAt = Instant.parse("2026-01-01T12:00:00Z"),
        imagePath = "images/$id.png",
        text = text,
    )
}
