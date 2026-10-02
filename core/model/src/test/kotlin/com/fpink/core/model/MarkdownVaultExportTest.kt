package com.fpink.core.model

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownVaultExportTest {
    private val exportedAt = Instant.parse("2026-09-30T09:00:00Z")
    private val flat = VaultOptions(groupByCategory = false, exportedAt = exportedAt, timeZone = TimeZone.UTC)
    private val grouped = flat.copy(groupByCategory = true)

    @Test
    fun `notes become markdown files with front matter body and image embed`() {
        val note = note("a", "Hello world\nSecond line").copy(
            inkColorHex = "#102030",
            inkColorName = "Blue \"Black\"",
            sourceId = "src",
            paragraphIndex = 1,
            recognitionProvider = "PADDLE",
            recognitionModelVersion = "v5",
            confidence = 0.5f,
            userEdited = true,
            tags = listOf("deep work", "#idea"),
            zettelkastenCategory = ZettelkastenCategory.PERMANENT,
        )
        val documents = MarkdownVaultExport.build(listOf(note), flat, setOf(note.imagePath))
        val document = documents.first()

        assertEquals("2026-01-01 1230 Hello world.md", document.path)
        val expected = """
            ---
            id: "a"
            created: "2026-01-01T12:30:00Z"
            type: permanent
            tags: ["fpink", "deep-work", "idea"]
            aliases: ["Hello world"]
            ink-color: "#102030"
            ink-color-name: "Blue \"Black\""
            source: "src"
            paragraph: 1
            recognition: "PADDLE v5"
            confidence: 0.5
            user-edited: true
            ---

            Hello world
            Second line

            ![[attachments/a.png]]

        """.trimIndent()
        assertEquals(expected, document.content)
        assertEquals(MarkdownVaultExport.INDEX_FILE, documents.last().path)
    }

    @Test
    fun `grouping places notes in category folders and tags them`() {
        val notes = listOf(
            note("a", "One").copy(zettelkastenCategory = ZettelkastenCategory.LITERATURE),
            note("b", "Two"),
        )
        val documents = MarkdownVaultExport.build(notes, grouped, emptySet())
        assertEquals("Literature/2026-01-01 1230 One.md", documents[0].path)
        assertEquals("Fleeting/2026-01-01 1230 Two.md", documents[1].path)
        assertTrue(documents[0].content.contains("\"zettelkasten/literature\""))
        assertFalse(documents[0].content.contains("![["))
        val index = documents.last().content
        assertTrue(index.indexOf("## Fleeting") < index.indexOf("## Literature"))
        assertTrue(index.contains("- [[2026-01-01 1230 One]]"))
        assertFalse(index.contains("## Permanent"))
    }

    @Test
    fun `file names are sanitised shortened and unique ignoring case`() {
        val notes = listOf(
            note("a", "  ...a/b:c*d?e\"f<g>h|i#j^k[l]m "),
            note("b", "A/B:C*D?E\"F<G>H|I#J^K[L]M"),
            note("c", "\n\n"),
            note("d", "one two three four five six seven eight nine ten"),
        )
        val paths = MarkdownVaultExport.build(notes, flat, emptySet()).map { it.path }
        assertEquals("2026-01-01 1230 a b c d e f g h.md", paths[0])
        assertEquals("2026-01-01 1230 A B C D E F G H (2).md", paths[1])
        assertEquals("2026-01-01 1230 Untitled.md", paths[2])
        assertEquals("2026-01-01 1230 one two three four five six seven eight.md", paths[3])
    }

    @Test
    fun `links resolve to exported wikilinks or fall back to plain ids`() {
        val notes = listOf(
            note("a", "Source").copy(links = listOf("b", "missing")),
            note("b", "Target"),
        )
        val content = MarkdownVaultExport.build(notes, flat, emptySet()).first().content
        assertTrue(content.contains("## Links\n\n- [[2026-01-01 1230 Target]]\n- missing\n"))
    }

    @Test
    fun `shortening a title never splits a surrogate pair`() {
        val title = MarkdownVaultExport.title(note("a", "x".repeat(59) + "\uD83D\uDE00tail"))
        assertEquals("x".repeat(59), title)
        assertFalse(title.any { it.isSurrogate() })
    }

    @Test
    fun `yaml strings escape control characters`() {
        assertEquals("\"a\\nb\\t\\\\\\\"\\u0001\"", MarkdownVaultExport.yamlString("a\nb\t\\\"\u0001"))
    }

    private fun note(id: String, text: String) = Note(
        id = id,
        capturedAt = Instant.parse("2026-01-01T12:30:00Z"),
        imagePath = "images/$id.png",
        text = text,
    )
}
